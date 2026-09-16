(ns mqttkat.web.state
  "Everything the console displays, read once and formatted once.

   The page is server-rendered with these values and then kept up to date over
   the websocket, so both have to agree about what a number means and how it
   reads. Rather than format in Clojure for the first paint and again in
   JavaScript for every update — two implementations of `4.82 GB` to drift
   apart — this hands out display *strings* keyed by the element id they
   belong in. Hiccup puts them in the page; the browser assigns them to the
   same ids. There is one formatter, here.

   The charts are the exception: they need numbers to scale an axis, so
   `sample` carries raw ones.

   Rates come from this namespace's own sampler rather than from the $SYS load
   averages, which are per *minute* smoothed over 1/5/15 minutes and only move
   when the $SYS publisher is running. A console that reports nothing when
   -Dmqttkat.sysInterval=0 would be a confusing way to find out that setting
   exists."
  (:require [clojure.string :as str]
            [mqttkat.handlers :as h]
            [mqttkat.rama.cluster :as cluster]
            [mqttkat.sys :as sys]
            [mqttkat.util :as util])
  (:import [java.lang.management ManagementFactory]
           [java.util.concurrent.atomic LongAdder]
           [java.nio.channels SelectionKey]
           [org.mqttkat MqttStat TopicStats]))

(set! *warn-on-reflection* true)

(defn- sum ^long [^LongAdder a] (.sum a))

(defn- sum-type ^long [^"[Ljava.util.concurrent.atomic.LongAdder;" counters ^long packet-type]
  (sum (aget counters (int packet-type))))

;; ── formatting ────────────────────────────────────────────────────────

(defn commas
  "12345 -> \"12,345\". Locale/ROOT so the grouping does not follow whatever
   the host happens to be set to; the page is one design, not per-locale."
  [n]
  (String/format java.util.Locale/ROOT "%,d" (to-array [(long n)])))

(defn bytes-str
  "Bytes at three significant figures, which is what fits the metric row.
   1024-based, and labelled so — a broker reporting GB when it means GiB is a
   4% lie by the time it matters."
  [n]
  (let [n (double n)]
    (loop [v     n
           units ["B" "KiB" "MiB" "GiB" "TiB"]]
      (if (or (< v 1024.0) (= 1 (count units)))
        (str (String/format java.util.Locale/ROOT
                            (if (or (>= v 100.0) (= "B" (first units))) "%.0f" "%.1f")
                            (to-array [v]))
             " " (first units))
        (recur (/ v 1024.0) (rest units))))))

(defn duration-str
  "Seconds as the two largest units that are non-zero, so an uptime stays
   readable at a minute and at a month."
  [seconds]
  (let [s (long seconds)
        d (quot s 86400)
        h (quot (rem s 86400) 3600)
        m (quot (rem s 3600) 60)]
    (cond
      (pos? d) (format "%d d %02d h" d h)
      (pos? h) (format "%d h %02d m" h m)
      (pos? m) (format "%d m %02d s" m (rem s 60))
      :else    (format "%d s" s))))

(defn- rate-str
  "A per-second rate. Under ten it gets a decimal, because the difference
   between 0.2/s and 2/s is the whole point and both round to the same
   integer."
  [r]
  (let [r (double r)]
    (cond
      (zero? r)  "0/s"
      (< r 10.0) (str (String/format java.util.Locale/ROOT "%.1f" (to-array [r])) "/s")
      :else      (str (commas (Math/round r)) "/s"))))

(defn- percent-str [^double fraction]
  (str (Math/round (* 100.0 fraction)) "%"))

(defn- clock-str [^long millis]
  (.format (java.time.format.DateTimeFormatter/ofPattern "HH:mm:ss")
           (java.time.LocalTime/ofInstant (java.time.Instant/ofEpochMilli millis)
                                          (java.time.ZoneId/systemDefault))))

;; ── the readings ──────────────────────────────────────────────────────

(def ^:private ^java.lang.management.RuntimeMXBean runtime-mx
  (ManagementFactory/getRuntimeMXBean))
(def ^:private ^java.lang.management.OperatingSystemMXBean os-mx
  (ManagementFactory/getOperatingSystemMXBean))

(def cores (.getAvailableProcessors os-mx))

(defn cpu-nanos
  "CPU time this process has used, or nil where that cannot be read.

   The counter rather than getProcessCpuLoad, which is the obvious call and is
   what this started as. Both work; the difference is the denominator.
   getProcessCpuLoad is a share of the whole machine, and on a 24-core host a
   broker genuinely using 11% of a core reports 0.005 and displays as 0% —
   which is what the console showed while it was pushing 2,500 messages a
   second. Differencing the counter here gives cores-used, which is the number
   worth showing, and does it the same way every other rate on this page is
   worked out."
  []
  (when (instance? com.sun.management.OperatingSystemMXBean os-mx)
    (let [v (.getProcessCpuTime ^com.sun.management.OperatingSystemMXBean os-mx)]
      (when-not (neg? v) v))))

(defn cpu-cores
  "Cores used between two readings — 0.5 is half a core, 3.0 is three of them.

   Not capped at one. A broker that is using four cores should say so, and
   top's %CPU has the same convention for the same reason. nil when the JVM
   will not report the counter at all, so the page can say it does not know
   rather than print a zero it cannot stand behind."
  [^double elapsed-seconds before now]
  (when (and before now (pos? elapsed-seconds))
    (max 0.0 (/ (- (long now) (long before)) (* elapsed-seconds 1e9)))))

(defn uptime-seconds []
  (quot (.getUptime runtime-mx) 1000))

(defn- inflight-count
  "Messages awaiting an acknowledgement, and those queued behind the window."
  []
  (h/queued-count))

(defn- subscription-count []
  (reduce + 0 (map #(count (:subscribed-topics %)) (vals @h/*clients*))))

(defn- sys-topic? [entry]
  (str/starts-with? (str (key entry)) "$SYS/"))

(defn- retained-count
  "Retained topics, counted twice: everything, and everything the broker did
   not publish about itself.

   The second is the one the counters table shows, because the broker puts
   about seventy of its own retained messages out every interval and reporting
   those back as retained data says more about the reporting than about the
   broker. The first is what the topics page lists, so that a stat strip
   reading 0 does not sit over a table of sixty-eight rows."
  []
  (let [all @h/*retained*]
    {:retained (count (remove sys-topic? all))
     :listed   (count all)}))

(defn reading
  "Every raw number the console shows. Pure — no clock is advanced and nothing
   is remembered, so a test or a REPL can call it freely."
  []
  (let [{:keys [connected parked-sessions]} (util/client-counts)
        rt      (Runtime/getRuntime)
        written (sum MqttStat/writtenMessages)
        queued  (max 0 (- (sum MqttStat/sentMessages)
                          written
                          (sum MqttStat/discardedMessages)))]
    (merge
     {:t             (System/currentTimeMillis)
       :clients       connected
       :parked        parked-sessions
       :max-clients   (MqttStat/maxConnectedClients)
       ;; Packets, not messages: Connection increments these once for every
       ;; packet that crosses the boundary, right beside countReceived and
       ;; countSent. Named for what they are — they used to be :received and
       ;; :written and were displayed under "messages in / out", which at QoS 1
       ;; with a fan-out is about five times the message rate, because every
       ;; delivery brings back a PUBACK. 24k publishes/s in and 98k out showed
       ;; as 122k each way.
       :packets-in    (sum MqttStat/receivedMessages)
       :packets-out   written
       :bytes-in      (sum MqttStat/receivedBytes)
       :bytes-out     (sum MqttStat/writtenBytes)
       :queued        queued
       :inflight      (inflight-count)
       :dropped       (+ (sum MqttStat/droppedMessages) (sum MqttStat/discardedMessages))
       :throttled     (sum MqttStat/publisherPauses)
       :sockets       (sum MqttStat/socketConnections)
       :connects      (sum-type MqttStat/receivedByType 1)
       :disconnects   (sum-type MqttStat/receivedByType 14)
       :publish-in    (sum-type MqttStat/receivedByType 3)
       :publish-out   (sum-type MqttStat/sentByType 3)
       :subscriptions (subscription-count)
       :heap          (- (.totalMemory rt) (.freeMemory rt))
       :heap-max      (.maxMemory rt)
       :cpu-nanos     (cpu-nanos)
       :uptime        (uptime-seconds)}
     (retained-count))))

;; ── rates ─────────────────────────────────────────────────────────────

(def ^:private rated
  "The counters a per-second rate is worked out for, and the id of the cell it
   is shown in."
  ;; "in" and "out" are the headline pair and the two lines of the throughput
  ;; chart, and they are PUBLISH — what a message is. The packet rates are
  ;; alongside them rather than instead of them.
  [[:publish-in "in"] [:publish-out "out"]
   [:packets-in "packets-in"] [:packets-out "packets-out"]
   [:bytes-in "bytes-in"] [:bytes-out "bytes-out"]
   [:dropped "dropped"] [:sockets "sockets"]
   [:publish-in "publish-in"] [:publish-out "publish-out"]
   [:connects "connects"] [:disconnects "disconnects"]
   [:subscriptions "subscriptions"] [:retained "retained"]])

(def client-limit
  "How many clients the console lists.

   The connection-scale tests open fifty thousand of them; a table of that many
   rows is a hung browser, not a console. The count beside the table says how
   many there really are."
  50)

(defn- protocol-name [version]
  (if (>= (long (or version 4)) 5) "5.0" "3.1.1"))

(defn client-rows
  "The connected clients, busiest housekeeping first.

   *clients* is keyed by SelectionKey while a client is connected and re-keyed
   to its client id once the socket has gone and the session is being kept
   (§3.1.2.4), so the key type is what says which of the two a row is. Both are
   listed: a parked session still holds subscriptions and still has messages
   queued for it, which is exactly what someone looking at this page wants to
   know."
  []
  (let [now      (System/currentTimeMillis)
        outbound @h/*outbound*
        entries  (for [[k v] @h/*clients*
                       :let  [id (:client-id v)]
                       :when id]
                   (let [state (some-> (get outbound id) deref)]
                     {:id            id
                      :connected     (instance? SelectionKey k)
                      :protocol      (protocol-name (:protocol-version v))
                      :clean         (boolean (:clean-session? v))
                      :subscriptions (count (:subscribed-topics v))
                      :inflight      (count (:inflight state))
                      :queued        (count (:pending state))
                      ;; Idle is only known for clients that asked for a keep
                      ;; alive — :last-active is created by add-timer! and
                      ;; nothing else — so age is what the table shows. It is
                      ;; known for every client.
                      :age-ms        (when-let [t (:connected-at v)]
                                       (max 0 (- now (long t))))
                      :idle-ms       (when-let [t (:last-active v)]
                                       (max 0 (- now (long @t))))}))]
    {:total (count entries)
     ;; Connected before parked, then the ones with work outstanding, then by
     ;; name so the order is stable second to second.
     :rows  (->> entries
                 (sort-by (juxt (complement :connected)
                                (comp - :inflight)
                                (comp - :queued)
                                :id))
                 (take client-limit)
                 (vec))}))

(defonce ^:private previous (atom nil))
(defonce ^:private latest-rates (atom {}))
(defonce ^:private latest-cpu (atom nil))
(defonce ^:private previous-topics (atom nil))
(defonce ^:private latest-topics (atom []))

(def active-topic-limit
  "How many topics the console lists. The broker tracks more than it shows —
   the point of the list is the busiest few, not a directory."
  12)

(defn- topic-activity
  "The busiest topics since the last sample, newest counts against previous.

   Rate comes from the difference, like every other rate here, so a topic that
   went quiet drops off rather than sitting near the top on the strength of
   what it did an hour ago. Total is carried too, because a rate alone cannot
   tell a topic that has never been used from one that is merely idle now."
  [now-counts before-counts elapsed]
  (when (and elapsed (pos? elapsed))
    (->> now-counts
         (map (fn [[topic total]]
                (let [before (get before-counts topic 0)]
                  {:topic topic
                   :total total
                   :rate  (max 0.0 (/ (- total before) (double elapsed)))})))
         ;; Busiest first, and a stable tie-break so equal rates do not shuffle
         ;; the table every second — movement that reads as data.
         (sort-by (juxt (comp - :rate) (comp - :total) :topic))
         (take active-topic-limit)
         (vec))))

(defn- rates
  "Per second, over the time that actually elapsed rather than the interval
   asked for: a sampler that ran late would otherwise report the catch-up as a
   spike. Nothing before the second reading, so the first one is not the whole
   history of the process divided by one second."
  [now before]
  (let [elapsed (when before (/ (- (:t now) (:t before)) 1000.0))]
    (if-not (and elapsed (pos? elapsed))
      {}
      (into {} (for [[k id] rated]
                 [id (max 0.0 (/ (- (k now 0) (k before 0)) elapsed))])))))

(defn- cpu-since
  "CPU over the same interval the rates are worked out over. Kept apart from
   them because it is cores rather than a per-second count, and because it may
   legitimately be unknown."
  [now before]
  (when before
    (cpu-cores (/ (- (:t now) (:t before)) 1000.0)
               (:cpu-nanos before) (:cpu-nanos now))))

(defn sample!
  "Take a reading, and the rates since the last one. Called once a second by
   the websocket ticker; the only thing here that keeps state."
  []
  (let [now      (reading)
        prev     @previous
        r        (rates now prev)
        cpu      (cpu-since now prev)
        counts   (TopicStats/snapshot)
        elapsed  (when prev (/ (- (:t now) (:t prev)) 1000.0))
        topics   (or (topic-activity counts @previous-topics elapsed) @latest-topics)]
    (reset! previous now)
    (reset! previous-topics counts)
    (reset! latest-rates r)
    (reset! latest-cpu cpu)
    (reset! latest-topics topics)
    (assoc now :rates r :cpu cpu :topics topics)))

(defn current
  "The state as it stands, with the rates from the most recent sample.

   Reading must not sample: `sample!` is driven by one clock, and a page load
   passing through here with a second one would halve the interval that every
   rate had just been worked out over."
  []
  (assoc (reading) :rates @latest-rates :cpu @latest-cpu :topics @latest-topics))

(defn forget!
  "Drop the sampler's memory, so the next sample! reports no rates rather than
   a rate over however long the broker was stopped. For tests and restarts."
  []
  (reset! previous nil)
  (reset! previous-topics nil)
  (reset! latest-rates {})
  (reset! latest-topics [])
  (reset! latest-cpu nil))

;; ── what goes in the page ─────────────────────────────────────────────

(def counter-rows
  "The counters table: element id, label, and how to read value and rate out
   of a `reading`. Defined once so the server renders the rows and the browser
   fills the same ids — a row added here appears in both.

   One figure per row, never two. `PUBLISH in / out` and `Connect /
   disconnect` used to put a pair in one cell, and that is what made the
   column jump: the cell was as wide as two independent counters happened to
   be, so it grew whenever either of them crossed a digit — 0 / 680 to
   0 / 5,440 — and every number in the column shifted with it. It also made
   the widest cell too wide to pin, since a pair of seven-digit counters plus
   the longest label needs 380px in a 348px panel."
  [{:id "subscriptions" :name "Subscriptions"     :value #(commas (:subscriptions %))}
   {:id "retained"      :name "Retained messages" :value #(commas (:retained %))}
   {:id "dropped"       :name "Dropped messages"  :value #(commas (:dropped %))}
   {:id "throttled"     :name "Publisher pauses"  :value #(commas (:throttled %))}
   {:id "bytes-in"      :name "Bytes in"          :value #(bytes-str (:bytes-in %))}
   {:id "bytes-out"     :name "Bytes out"         :value #(bytes-str (:bytes-out %))}
   {:id "publish-in"    :name "PUBLISH in"        :value #(commas (:publish-in %))}
   {:id "publish-out"   :name "PUBLISH out"       :value #(commas (:publish-out %))}
   ;; Every packet, not just the ones carrying a payload. Next to the PUBLISH
   ;; rows on purpose: the gap between them is the acknowledgement traffic,
   ;; which at QoS 1 with a wide fan-out is most of what the broker is doing
   ;; and is invisible in a message count.
   {:id "packets-in"    :name "Packets in"        :value #(commas (:packets-in %))}
   {:id "packets-out"   :name "Packets out"       :value #(commas (:packets-out %))}
   {:id "connects"      :name "Connects"          :value #(commas (:connects %))}
   {:id "disconnects"   :name "Disconnects"       :value #(commas (:disconnects %))}
   {:id "sockets"       :name "Sockets accepted"  :value #(commas (:sockets %))}
   {:id "uptime"        :name "Broker uptime"     :value #(duration-str (:uptime %)) :rate :none}])

(defn fields
  "Display strings keyed by the element id they belong in."
  [reading]
  (let [r        (:rates reading {})
        rate-of  (fn [id] (if-let [v (get r id)] (rate-str v) "—"))
        msg-rate (+ (get r "in" 0.0) (get r "out" 0.0))]
    (into
     {"stamp"          (str "Updated " (clock-str (:t reading)))
      "uptime-foot"    (str "up " (duration-str (:uptime reading)))

      ;; PUBLISH only, which is what "messages" means to whoever is reading it
      ;; and what the load generator counts. The packet rate is in the table.
      "m-throughput"      (commas (Math/round (double msg-rate)))
      "m-throughput-note" (str (rate-str (get r "in" 0.0)) " in · "
                               (rate-str (get r "out" 0.0)) " out")

      "m-clients"      (commas (:clients reading))
      "m-clients-unit" (str "of " (commas (:max-clients reading)) " max")
      "m-clients-note" (str (commas (:parked reading)) " parked · "
                            (commas (:subscriptions reading)) " subscriptions")

      "m-queued"       (commas (:queued reading))
      "m-queued-unit"  (str "/ " (commas (:inflight reading)))
      "m-queued-note"  (str (commas (:dropped reading)) " dropped · "
                            (commas (:throttled reading)) " pauses")

      ;; Of one core, so 140% is a broker on more than one of them. Of the
      ;; machine it would read 6% on this host and 0% on a bigger one, which
      ;; says more about the host than about the broker.
      "m-mem"          (if-let [c (:cpu reading)] (percent-str c) "—")
      "m-mem-unit"     (bytes-str (:heap reading))
      "m-mem-note"     (str (bytes-str (:heap-max reading)) " heap · " cores " cores")

      "active-topics-note"
      (let [n (TopicStats/size)]
        (cond
          (zero? n)               ""
          (TopicStats/isTruncated) (str "busiest " active-topic-limit " of " (commas n) "+ tracked")
          :else                   (str "busiest " (min active-topic-limit n) " of " (commas n))))
      "c-connected"    (commas (:clients reading))
      "c-parked"       (commas (:parked reading))
      "c-subs"         (commas (:subscriptions reading))
      "clients-note"
      (let [{:keys [total]} (client-rows)]
        (cond
          (zero? total)            ""
          (> total client-limit)   (str "first " client-limit " of " (commas total))
          :else                    (str (commas total) " listed")))
      "t-topics"       (commas (:listed reading))
      "t-subs"         (commas (:subscriptions reading))
      "t-rate"         (commas (Math/round (double msg-rate)))}
     (mapcat (fn [{:keys [id value rate]}]
               (cond-> [[(str "c-" id) (value reading)]]
                 (not= :none rate) (conj [(str "c-" id "-rate") (rate-of id)])))
             counter-rows))))

(defn broker-stats
  "What this broker tells the cluster about itself, for the other brokers'
   consoles: a handful of figures, sent every few seconds and stored on the
   registry entry every broker watches — so not the whole reading, and no
   history."
  [reading]
  (let [r (:rates reading {})]
    {:clients       (:clients reading)
     :parked        (:parked reading)
     :subscriptions (:subscriptions reading)
     :retained      (:retained reading)
     :in            (Math/round (double (get r "in" 0.0)))
     :out           (Math/round (double (get r "out" 0.0)))
     :queued        (:queued reading)
     :inflight      (:inflight reading)
     :dropped       (:dropped reading)
     :heap          (:heap reading)
     :heap-max      (:heap-max reading)
     :cpu           (:cpu reading)
     :uptime        (:uptime reading)
     :version       sys/broker-version}))

(def stale-after-ms
  "A broker whose last report is older than this is shown as stale: three
   reports missed. It may be gone without having said so, or only busy."
  15000)

(defn broker-rows
  "Every broker in the cluster, as the registry every broker watches has it,
   this one first. Empty when not attached to a cluster — the page says so."
  []
  (let [now (System/currentTimeMillis)]
    (->> (cluster/brokers)
         (map (fn [[id {:keys [host port at stats stats-at]}]]
                (let [age (when stats-at (max 0 (- now (long stats-at))))]
                  {:id       id
                   :self     (= id cluster/broker-id)
                   :address  (str host ":" port)
                   :up-ms    (when at (max 0 (- now (long at))))
                   :stale    (or (nil? age) (> age stale-after-ms))
                   :age-ms   age
                   :stats    stats})))
         (sort-by (fn [{:keys [self id]}] [(if self 0 1) id]))
         vec)))

(defn sample-point
  "The raw numbers the charts and sparklines are drawn from — one field per
   line that is actually plotted, and no more. A hundred and twenty of these
   are held and sent to every browser that opens the page, so this is not the
   place to put everything just in case."
  [reading]
  (let [r (:rates reading {})]
    {:t       (:t reading)
     :clients (:clients reading)
     :in      (Math/round (double (get r "in" 0.0)))
     :out     (Math/round (double (get r "out" 0.0)))
     :queued  (:queued reading)
     :heap    (:heap reading)}))
