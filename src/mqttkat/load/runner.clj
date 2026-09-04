(ns mqttkat.load.runner
  "A load generator for MQTT brokers.

     lein run -m mqttkat.load.runner --publishers 100 --subscribers 1000 \\
       --topics 50 --messages 1000000 --rate 20000 --qos 1

   Points at any broker, not just this one: --host and --port are all it knows
   about the far end, so the same run can be pointed at mosquitto for a
   comparison that means something.

   Two things it tries hard to be honest about.

   It reports when it could not keep up. A generator that publishes as fast as
   it can and reports the latency of what got through is measuring itself; this
   one is given a rate, holds a schedule, and records every way it fell behind
   — time spent late, time blocked on the in-flight window, sends that failed.
   If those are not near zero the throughput number is the generator's and not
   the broker's, and the report says so rather than leaving it to be inferred.

   It reports two latencies, service and response. See the comment on
   mqttkat.load.client/on-publish: the gap between them is how much of the
   delay was the generator's own lateness."
  (:require [clojure.string :as str]
            [mqttkat.load.client :as lc]
            [mqttkat.load.stats :as stats])
  (:import [java.util.concurrent CountDownLatch TimeUnit]
           [java.util.concurrent.atomic AtomicBoolean LongAdder]
           [java.util.concurrent.locks LockSupport])
  (:gen-class))

(set! *warn-on-reflection* true)

;; ── options ───────────────────────────────────────────────────────────

(def defaults
  {:host "localhost" :port 1883
   :publishers 10 :subscribers 10 :topics 5
   :messages 100000 :rate 10000 :qos 0 :size 128
   :window 100
   :duration 0
   :progress-ms 5000
   :drain-ms 5000
   :max-drain-ms 300000
   :source-ips 0})

(def ^:private option-doc
  [["--host HOST"          "broker host (localhost)"]
   ["--port PORT"          "broker port (1883)"]
   ["--publishers N"       "publishing clients (10)"]
   ["--subscribers N"      "subscribing clients (10)"]
   ["--topics N"           "topics, shared between both pools (5)"]
   ["--messages N"         "total messages to publish; 0 to keep going until stopped (100000)"]
   ["--duration N"         "stop after N seconds; 0 for no time limit (0)"]
   ["--progress-ms N"      "how often to print a progress line while running (5000)"]
   ["--rate N"             "target messages per second, aggregate; 0 for unlimited (10000)"]
   ["--qos 0|1|2"          "publish and subscribe QoS (0)"]
   ["--size N"             "payload bytes, minimum 28 (128)"]
   ["--window N"           "unacknowledged publishes allowed per publisher (100)"]
   ["--drain-ms N"         "quiet period that counts as fully drained (5000)"]
   ["--max-drain-ms N"     "cap on the whole drain, however much is still arriving (300000)"]
   ["--source-ips N"       "spread clients over N source addresses; 0 to choose automatically"]])

(defn parse-args
  "`--key value` pairs. Hand-rolled rather than pulling in tools.cli for a
   dozen numeric options, all of which are --key value."
  [args]
  (loop [[a v & more] args, opts defaults]
    (cond
      (nil? a) opts
      (not (str/starts-with? a "--")) (throw (ex-info (str "unexpected argument: " a) {:arg a}))
      (nil? v) (throw (ex-info (str "no value for " a) {:arg a}))
      :else
      (let [k (keyword (subs a 2))]
        (when-not (contains? defaults k)
          (throw (ex-info (str "unknown option: " a) {:arg a})))
        (recur more (assoc opts k (if (string? (defaults k)) v (parse-long v))))))))

(defn usage []
  (str "mqtt-kat load generator\n\n"
       (str/join "\n" (map (fn [[flag doc]] (format "  %-18s %s" flag doc)) option-doc))
       "\n"))

;; ── addresses ─────────────────────────────────────────────────────────

(def ^:private bind-capacity
  "Connections to give one source address before moving to the next.

   Linux hands bind() the odd half of net.ipv4.ip_local_port_range, so an
   address supplies about 14,000 ports and not the 28,000 the range suggests,
   and the allocator starts scanning well before that is used up. 8,000 keeps
   clear of the knee. The same limit, and the measurements behind it, are in
   connection-scale-remote-test."
  8000)

(defn- loopback? [host]
  (or (= host "localhost")
      (str/starts-with? (str host) "127.")))

(defn source-addresses
  "The addresses to spread `n` clients over, or [nil] to let the kernel choose.

   Only for a broker on loopback: 127.0.0.0/8 is ours to hand out, and any
   user can bind to any of it without configuring anything first. Against a
   broker somewhere else the local addresses are whatever the machine has, so
   this leaves well alone and the port ceiling applies."
  [host n requested]
  (let [wanted (cond
                 (pos? (long requested)) (long requested)
                 (not (loopback? host))  1
                 :else (max 1 (long (Math/ceil (/ (double n) bind-capacity)))))]
    (if (or (= 1 wanted) (not (loopback? host)))
      [nil]
      (mapv #(str "127.0.0." (inc %)) (range wanted)))))

;; ── the run ───────────────────────────────────────────────────────────

(defn- topic-name [i] (str "load/" i))

(defn- open-pool!
  "Open `n` clients and wait for all their CONNACKs at once, rather than a
   round trip each."
  [{:keys [host port qos] :as opts} prefix n shared]
  (let [addrs (source-addresses host n (:source-ips opts))
        clients (mapv (fn [i]
                        (lc/open! (assoc shared
                                         :host host :port port
                                         :client-id (str prefix "-" i)
                                         :index i
                                         :window (:window opts)
                                         :source-address (nth addrs (mod i (count addrs))))))
                      (range n))]
    (doseq [c clients]
      (when-not (lc/await-connack c 30000)
        (throw (ex-info (str "no CONNACK for " (:client-id c)) {:client-id (:client-id c)}))))
    clients))

(defn burst-size
  "How many messages a publisher sends per wake-up.

   One per millisecond of schedule, because parkNanos cannot pace finer than
   that. Above a millisecond of interval this is 1 and the schedule is followed
   exactly; below it, the publisher parks once and sends the burst, which hits
   the target rate but means the load arrives in clumps and some messages go
   out ahead of their own intended time."
  ^long [interval-ns]
  (if (pos? (long interval-ns))
    (max 1 (long (Math/ceil (/ 1000000.0 (double interval-ns)))))
    1))

(defn- publish-loop!
  "One publisher, holding a schedule.

   The schedule is absolute — the nth message is due at start + n*interval —
   rather than sleeping for the interval each time. Sleeping between sends
   makes the interval the floor and every overshoot permanent, so the run
   silently drifts to a lower rate than it reports."
  ;; Unhinted: a primitive-taking fn is limited to four parameters. Enough of
  ;; them travel together now that they come in a map.
  [client {:keys [opts topics count-for-me interval-ns start-ns
                  lateness blocked running per-topic]}]
  (let [qos         (long (:qos opts))
        size        (long (:size opts))
        interval-ns (long interval-ns)
        start-ns    (long start-ns)
        n-topics    (count topics)
        ;; parkNanos does not have a microsecond to give: asked for 1 ms it
        ;; comes back around 1.5, and at 5,000/s across five publishers that
        ;; was 457 us of lateness on every single message. So park once per
        ;; millisecond of schedule and send that millisecond's worth in a
        ;; burst. Each message keeps its own intended time, which is the part
        ;; that has to stay exact — the response latency is measured from it,
        ;; and a burst's later messages really are late, which is a thing to
        ;; report and not to hide.
        batch       (burst-size interval-ns)]
    (loop [i 0]
      (when (and (< i (long count-for-me)) (.get ^AtomicBoolean running))
        (let [intended (+ start-ns (* (long i) interval-ns))
              now      (System/nanoTime)]
          (when (> intended now)
            (LockSupport/parkNanos (- intended now)))
          (dotimes [k (min batch (- (long count-for-me) i))]
            (when (.get ^AtomicBoolean running)
              (let [j        (+ i k)
                    now      (System/nanoTime)
                    ;; Unlimited means there is no schedule, so there is
                    ;; nothing to be late against and the intended time is
                    ;; now. It used to be start-ns for every message, which
                    ;; made "lateness" the elapsed time of the run — 200 ms
                    ;; per message on a 320 ms run, reported as though the
                    ;; generator had been struggling when it had simply been
                    ;; asked for no pace.
                    intended (if (pos? interval-ns)
                               (+ start-ns (* (long j) interval-ns))
                               now)]
                (when (> now intended)
                  (.add ^LongAdder lateness (quot (- now intended) 1000)))
                ;; Round-robin across every topic, so no topic is left without
                ;; traffic when there are more topics than publishers.
                (let [t (mod (+ (:index client) j) n-topics)
                      {:keys [blocked-ns sent?]}
                      (lc/publish! client (nth topics t) qos intended j size)]
                  (.add ^LongAdder blocked (quot (long blocked-ns) 1000))
                  ;; Counted here, as each message goes, rather than all of
                  ;; them up front. Up front was wrong twice over: a run cut
                  ;; short by ctrl-c would compute the deliveries it expected
                  ;; from messages it never sent, and a run with no message
                  ;; limit has no count to loop over at all.
                  (when sent?
                    (.increment ^LongAdder
                                (aget ^"[Ljava.util.concurrent.atomic.LongAdder;" per-topic
                                      (int t))))))))
          (recur (+ i batch)))))))

(defn- await-quiet
  "Wait for the deliveries still in flight when publishing stopped.

   Finished means `quiet-ms` has passed with no new delivery at all; `max-ms`
   is the cap on the whole wait, so a broker dribbling forever cannot hang the
   run. Returns {:drained-ms n :quiet? bool}, and `quiet?` matters: a drain
   that hit the cap ended with messages still arriving, which makes the
   delivered count a floor rather than a total, and the report has to say so.

   The quiet window restarts every time the count moves. It did not, which is
   the bug this is fixing: the deadline was absolute from the moment draining
   began, so the wait could never exceed drain-ms — five seconds by default —
   however much was still in flight. A two million message run ended with
   deliveries still arriving at 44,000/s and reported them as though they were
   never coming."
  [counters quiet-ms max-ms]
  (let [started (System/currentTimeMillis)
        hard    (+ started (long max-ms))]
    (loop [previous (long -1), last-progress started]
      (let [now    (long (:received (stats/read-counters counters)))
            t      (System/currentTimeMillis)
            moved  (not= now previous)
            since  (if moved t last-progress)]
        (cond
          (>= t hard)                          {:drained-ms (- t started) :quiet? false}
          (>= (- t since) (long quiet-ms))     {:drained-ms (- t started) :quiet? true}
          :else (do (Thread/sleep 250) (recur now since)))))))

;; ── report ────────────────────────────────────────────────────────────

(defn- ms [^long micros] (/ micros 1000.0))

(defn- delay-added
  "How much the generator's own lateness added to the average delivery, in
   milliseconds. The difference between the two latencies is exactly that, and
   it is a better answer than any total: it is measured at the subscriber, in
   the units the report is already in.

   From the means, not the medians. The means are kept as an exact sum divided
   by an exact count; the medians come out of buckets six percent apart, and at
   a median of 2.7 s that bucket is 170 ms wide — wide enough to swallow the
   whole difference and print `added 0.000 ms` next to a mean lateness of
   285 ms."
  [{:keys [service response]}]
  (when (and service response)
    (ms (- (:mean response) (:mean service)))))

(defn- latency-line [label snap]
  (if snap
    (format "    %-10s n %9d  min %8.2f  med %8.2f  mean %8.2f  sd %8.2f  p95 %8.2f  p99 %8.2f  p99.9 %8.2f  max %8.2f"
            label (:n snap) (ms (:min snap)) (ms (:p50 snap)) (ms (:mean snap))
            (ms (:sd snap)) (ms (:p95 snap)) (ms (:p99 snap)) (ms (:p999 snap)) (ms (:max snap)))
    (format "    %-10s no samples" label)))

(defn report
  "Everything the run measured. Returns the map as well as printing it, so a
   test can assert on it."
  [{:keys [opts counts service response ack elapsed-ms expected outstanding
           lateness-us blocked-us burst ended setup-ms total-ms drain]}]
  (let [attempted (:attempted counts 0)
        published (:published counts 0)
        received  (:received counts 0)
        secs      (max 0.001 (/ (double elapsed-ms) 1000.0))
        ;; Deliveries are measured over publishing *and* draining, publishes
        ;; over publishing alone. Dividing both by the same window inflated the
        ;; delivery rate by whatever the tail took — on a run whose tail was
        ;; half its length, by a factor of two.
        all-secs  (max 0.001 (/ (double (or total-ms elapsed-ms)) 1000.0))
        payload   (max (long (:size opts)) lc/header-bytes)
        result {:opts opts :counts counts :expected expected :outstanding outstanding
                :elapsed-ms elapsed-ms :ended (or ended :finished) :setup-ms setup-ms
                :publish-rate (/ (double published) secs)
                :deliver-rate (/ (double received) all-secs)
                :total-ms (or total-ms elapsed-ms)
                :drain drain
                :delivery-ratio (when (pos? (long expected)) (/ (double received) expected))
                :mb-published (/ (* (double published) payload) 1048576.0)
                :mb-delivered (/ (* (double received) payload) 1048576.0)
                :fully-drained? (:quiet? drain true)
                :service service :response response :ack ack
                :lateness-us lateness-us :blocked-us blocked-us :burst burst}]
    (println)
    (println "  ────────────────────────────────────────────────────────────────")
    (println (format "  RESULTS   (%s)" (name (or ended :finished))))
    (println "  ────────────────────────────────────────────────────────────────")
    (println)
    (println "  run")
    (println (format "    broker      %s:%d" (:host opts) (:port opts)))
    (println (format "    clients     %d publishers, %d subscribers over %d topics"
                     (:publishers opts) (:subscribers opts) (:topics opts)))
    (println (format "    messages    QoS %d, %d byte payloads, window %d"
                     (:qos opts) (:size opts) (:window opts)))
    (println (format "    target      %s%s"
                     (if (pos? (long (:rate opts))) (str (:rate opts) "/s") "unlimited")
                     (if (> (long (or burst 1)) 1)
                       (format ", sent in bursts of %d" burst)
                       "")))
    (println (format "    asked for   %s"
                     (cond
                       (pos? (long (:messages opts))) (format "%d messages" (:messages opts))
                       (pos? (long (:duration opts))) (format "%d seconds" (:duration opts))
                       :else "no limit — until stopped")))
    (when setup-ms
      (println (format "    setup       %d ms to connect and subscribe" setup-ms)))
    (println (format "    ran for     %.2f s publishing%s"
                     secs
                     (if drain
                       (format ", %.2f s draining" (/ (:drained-ms drain) 1000.0))
                       "")))
    (println)
    (println "  throughput")
    (println (format "    published  %9d in %6.2f s  (%9.0f/s)" published secs (:publish-rate result)))
    (println (format "    delivered  %9d in %6.2f s  (%9.0f/s)" received all-secs (:deliver-rate result)))
    (println (format "    expected   %9d               (%s)"
                     expected
                     (if-let [r (:delivery-ratio result)] (format "%.4f delivered" r) "n/a")))
    ;; Said plainly, because the ratio above is the headline number and a
    ;; truncated drain makes it a floor rather than a result. This used to be
    ;; capped at five seconds with no way of knowing.
    (when (and drain (not (:quiet? drain)))
      (println (format "    ATTENTION  deliveries were still arriving when the %.0f s drain cap was reached;"
                       (/ (double (:max-drain-ms opts)) 1000.0)))
      (println "               delivered and the ratio above are floors, not totals — raise --max-drain-ms"))
    ;; Payload bytes, not wire bytes: the fixed header, topic name and packet
    ;; identifier are on top of this, and vary per packet. Called what it is
    ;; so nobody reconciles it against the broker's byte counters and finds a
    ;; discrepancy that is not one.
    (println (format "    payload    %9.2f MB out, %9.2f MB in  (%.2f MB/s, %.2f MB/s)"
                     (:mb-published result) (:mb-delivered result)
                     (/ (:mb-published result) secs) (/ (:mb-delivered result) secs)))
    (println)
    (println "  latency, milliseconds")
    (println (latency-line "service" service))
    (println (latency-line "response" response))
    (when ack (println (latency-line "ack" ack)))
    (println)
    ;; Printed whether or not it is bad. A number that only appears when
    ;; something is wrong is a number nobody learns to read.
    (println "  was the generator the bottleneck?")
    (let [target (long (:rate opts))
          attain (when (pos? target) (/ (:publish-rate result) target))]
      (println (format "    achieved      %9.0f/s against %s  %s"
                       (:publish-rate result)
                       (if (pos? target) (format "%d/s asked for" target) "no target")
                       (cond
                         (nil? attain) "(unlimited: this is a ceiling for generator AND broker together)"
                         (>= attain 0.98) "(the target was met, so this is the broker's number)"
                         :else "(TARGET MISSED — something here could not keep up)")))
      ;; Mean, not the sum: summed over a million messages any number looks
      ;; alarming, and the first version of this line called a 457 us average
      ;; SIGNIFICANT on a run that hit its target exactly.
      (println (format "    mean lateness %9.3f ms per message%s"
                       (if (pos? published) (/ (double lateness-us) published 1000.0) 0.0)
                       (if-let [gap (delay-added result)]
                         ;; The sign is worth spelling out. Negative means the
                         ;; burst sent ahead of the ideal schedule, which is
                         ;; not the generator being slow — printing "added
                         ;; -0.037 ms" made it look like one.
                         (if (neg? gap)
                           (format "   (bursts ran %.3f ms ahead of schedule on average)" (- gap))
                           (format "   (added %.3f ms to the average delivery)" gap))
                         ""))))
    (println (format "    window-blocked %8.2f s total  (waiting for acknowledgements)"
                     (/ (double blocked-us) 1e6)))
    (println (format "    send failures  %8d" (:failed counts 0)))
    (println (format "    unacknowledged %8d publishes still outstanding at the end" outstanding))
    (println (format "    attempted %d, published %d" attempted published))
    (println)
    ;; Every counter, whatever its value. The two below used to print only when
    ;; non-zero, which meant a clean run and a run where nobody had thought to
    ;; look produced the same output.
    (println "  counters")
    (doseq [k stats/counter-names]
      (println (format "    %-22s %12d" (name k) (get counts k 0))))
    (println)
    result))

(defn- progress-printer
  "A line every `progress-ms` while the run is going.

   Rates since the previous line rather than since the start: a run held at a
   steady rate for an hour should print the same number every time, and a
   cumulative average would take that hour to notice the broker had stalled."
  [counters printing progress-ms]
  (.start (Thread/ofVirtual)
          ^Runnable
          (fn []
            (let [begin (System/nanoTime)]
              (loop [last-at begin, last (stats/read-counters counters)]
                (Thread/sleep (long progress-ms))
                (when (.get ^AtomicBoolean printing)
                  (let [now-at  (System/nanoTime)
                        now     (stats/read-counters counters)
                        secs    (max 0.001 (/ (- now-at last-at) 1e9))
                        per-sec (fn [k] (/ (double (- (get now k 0) (get last k 0))) secs))]
                    ;; Elapsed since the run began, not since the last line —
                    ;; the first version printed the interval, so every line of
                    ;; a ten minute run said "2 s".
                    (println (format "    %6.0f s   published %9d (%8.0f/s)   delivered %10d (%9.0f/s)"
                                     (/ (- now-at begin) 1e9)
                                     (:published now) (per-sec :published)
                                     (:received now) (per-sec :received)))
                    (recur now-at now))))))))

(defn execute
  "Open the pools, publish, wait for the tail, report.

   Not called run!, which would shadow clojure.core/run!."
  [opts]
  (let [{:keys [publishers subscribers topics messages rate qos duration]} opts
        counters (stats/counters)
        service  (stats/histogram)
        response (stats/histogram)
        ack      (stats/histogram)
        shared   {:counters counters :service-latency service
                  :response-latency response :ack-latency ack}
        topic-names (mapv topic-name (range topics))
        ^"[Ljava.util.concurrent.atomic.LongAdder;" published-per-topic
        (into-array LongAdder (repeatedly topics #(LongAdder.)))
        running     (AtomicBoolean. true)
        reported    (AtomicBoolean. false)
        interrupted (AtomicBoolean. false)
        ;; Separate from `running`, which stops the publishers: the progress
        ;; lines have to carry on through the drain, where on a big run most
        ;; of the deliveries still arrive.
        printing    (AtomicBoolean. true)
        ;; Held by the shutdown hook until the report has been printed.
        done        (CountDownLatch. 1)]
    (println (format "  connecting %d subscribers and %d publishers to %s:%d"
                     subscribers publishers (:host opts) (:port opts)))
    ;; Timed and printed as they happen. A run that looks hung is nearly
    ;; always still in one of these, and without the phases there is no way to
    ;; tell setting up ten thousand clients apart from a broker that has
    ;; stopped answering.
    (let [setup-t0 (System/currentTimeMillis)
          t0   (System/currentTimeMillis)
          subs (open-pool! opts "load-sub" subscribers shared)
          _    (println (format "    %d subscribers connected in %d ms"
                                subscribers (- (System/currentTimeMillis) t0)))
          t1   (System/currentTimeMillis)
          _    (doseq [[i c] (map-indexed vector subs)]
                 (lc/subscribe! c (topic-name (mod i topics)) qos))
          _    (doseq [c subs]
                 (when-not (lc/await-suback c 30000)
                   (throw (ex-info (str "no SUBACK for " (:client-id c)) {}))))
          _    (println (format "    subscribed to %d topics in %d ms"
                                topics (- (System/currentTimeMillis) t1)))
          t2   (System/currentTimeMillis)
          pubs (open-pool! opts "load-pub" publishers shared)
          _    (println (format "    %d publishers connected in %d ms"
                                publishers (- (System/currentTimeMillis) t2)))
          setup-ms (- (System/currentTimeMillis) setup-t0)
          unlimited? (not (pos? (long messages)))
          ;; Exact, not messages/publishers rounded: the remainder is spread
          ;; over the first few publishers so the total is the number asked for.
          share (fn [i] (if unlimited?
                          Long/MAX_VALUE
                          (+ (quot messages publishers)
                             (if (< i (mod messages publishers)) 1 0))))
          interval-ns (if (pos? (long rate))
                        (long (/ 1e9 (/ (double rate) publishers)))
                        0)
          lateness (LongAdder.)
          blocked  (LongAdder.)
          latch    (CountDownLatch. publishers)
          started  (System/currentTimeMillis)
          start-ns (System/nanoTime)
          finish!
          (fn [ended]
            ;; Guarded, because there are two ways to get here and on a normal
            ;; exit both happen: the run ends and reports, then System/exit
            ;; runs the shutdown hook. Whichever arrives first reports; the
            ;; other returns nil.
            (when (.compareAndSet reported false true)
              (.set running false)
              (let [elapsed (- (System/currentTimeMillis) started)
                    _ (println (format "  publishing stopped after %.2f s — draining"
                                       (/ elapsed 1000.0)))
                    drain (await-quiet counters (:drain-ms opts) (:max-drain-ms opts))
                    total (- (System/currentTimeMillis) started)]
                (.set printing false)
                (let [subs-per-topic (mapv (fn [t] (count (filter #(= t (mod % topics))
                                                                  (range subscribers))))
                                           (range topics))
                      expected (reduce + 0 (map (fn [t] (* (.sum ^LongAdder (aget published-per-topic (int t)))
                                                           (long (nth subs-per-topic t))))
                                                (range topics)))
                      outstanding (reduce + 0 (map lc/outstanding pubs))]
                  (report {:opts opts
                           :counts (stats/read-counters counters)
                           :service (stats/snapshot service)
                           :response (stats/snapshot response)
                           :ack (stats/snapshot ack)
                           :elapsed-ms elapsed
                           :expected expected
                           :outstanding outstanding
                           :lateness-us (.sum lateness)
                           :blocked-us (.sum blocked)
                           :burst (burst-size interval-ns)
                           :ended ended
                           :setup-ms setup-ms
                           :total-ms total
                           :drain drain})))))]
      ;; ctrl-c. The hook is what turns "kill it when you have seen enough"
      ;; into a measurement: without it the terminal fills with progress lines
      ;; and the run you actually watched reports nothing at all.
      ;;
      ;; It signals and waits rather than reporting itself. Reporting from the
      ;; hook meant two threads racing to finish the same run: the publishers
      ;; stopped, the main thread walked out of its await, closed every client
      ;; and called System/exit while the hook was still gathering — and the
      ;; report was never printed at all. One reporting path, and the hook
      ;; holds the JVM open until it has run.
      (.addShutdownHook (Runtime/getRuntime)
                        (Thread. ^Runnable (fn []
                                             (when-not (.get reported)
                                               (println)
                                               (println "  stopping — waiting for the run to report")
                                               (.set interrupted true)
                                               (.set running false)
                                               (.await ^CountDownLatch done 60 TimeUnit/SECONDS)))))
      (println (format "  publishing %s across %d topics%s"
                       (if unlimited? "until stopped" (str messages " messages"))
                       topics
                       (if (pos? (long duration)) (format ", for %d s" duration) "")))
      (when (pos? (long (:progress-ms opts)))
        (progress-printer counters printing (:progress-ms opts)))
      (doseq [[i c] (map-indexed vector pubs)]
        (.start (Thread/ofVirtual)
                ^Runnable (fn []
                            (try
                              (publish-loop! c {:opts opts :topics topic-names
                                                :count-for-me (share i)
                                                :interval-ns interval-ns :start-ns start-ns
                                                :lateness lateness :blocked blocked
                                                :running running :per-topic published-per-topic})
                              (catch Throwable t
                                (println "  publisher failed:" (.getMessage t)))
                              (finally (.countDown latch))))))
      (if (pos? (long duration))
        (do (.await latch (long duration) TimeUnit/SECONDS)
            (.set running false)
            (.await latch 30 TimeUnit/SECONDS))
        (.await latch 365 TimeUnit/DAYS))
      (.set running false)
      (let [result (finish! (cond
                              (.get interrupted)     :interrupted
                              (pos? (long duration)) :duration-reached
                              :else                  :complete))]
        (flush)
        (.countDown done)
        (doseq [c (concat pubs subs)] (lc/close! c))
        result))))

(defn -main [& args]
  (if (some #{"--help" "-h"} args)
    (println (usage))
    (try
      (execute (parse-args args))
      ;; No System/exit on the way out. Every thread this starts is virtual and
      ;; so a daemon, and the JVM leaves on its own — whereas calling exit here
      ;; raced the shutdown hook on ctrl-c and lost the report.
      (flush)
      (catch Exception e
        (println "  error:" (.getMessage e))
        (println)
        (println (usage))
        (System/exit 1)))))
