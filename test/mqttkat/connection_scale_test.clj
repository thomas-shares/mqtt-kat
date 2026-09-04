(ns mqttkat.connection-scale-test
  "How many connections the broker will hold at once.

   Tagged ^:performance, so `lein test` skips it; run it with
   `lein test :performance`, or on its own with

     lein test :performance :only mqttkat.connection-scale-test

   The ceiling on this machine is not the broker, and it is half of what it
   looks like. Every connection to one listener from one address needs an
   ephemeral port, and /proc/sys/net/ipv4/ip_local_port_range is 32768-60999,
   28,232 of them — but Linux splits that range between its two allocators and
   hands connect() the even ports and bind() the odd ones. Measured here, 400
   sockets each way: a plain connect() got 400 even ports and no odd ones,
   bind(src,0) got 400 odd and no even. These clients connect without binding,
   so one source address gives them about 14,116 ports and not 28,232.

   That is why the ramp stops at 10,000. It is not a cliff — connect does not
   start failing with EADDRNOTAVAIL — it is the allocator scanning ever harder
   for a free port as they run out, and it is steep: opening 20,000 measured
   8,400-13,400/s up to 12,500 and then 460/s for the rest, 14.1 s in total, on
   a broker with no other connections and nothing in TIME-WAIT. A rung past
   ~12,000 measures the kernel's port table, not the broker. For more than
   that, connection-scale-remote-test spreads across source addresses.

   TIME-WAIT between rungs, which this docstring used to blame for the rate
   falling off, turns out not to matter here: net.ipv4.tcp_tw_reuse is 2, which
   enables reuse for loopback, and 10,000 twice in a row measured 14,106/s then
   14,782/s with the test's own 500 ms pause between them. Watch it anyway with

     ss -tan | grep -c TIME-WAIT

   while this runs; reading it from inside the JVM is not an option, because
   /proc/net/tcp throws IOException: Invalid argument there — even from slurp,
   which reads /proc/sys files perfectly well — while cat and python read it.

   Both ends run in this JVM, so a connection costs two sockets and the
   figures below cover the client side as well as the broker's."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [mqttkat.client :as client]
            [mqttkat.handlers :as handlers]
            [mqttkat.test-util :as tu])
  (:import [java.lang.management ManagementFactory]
           [java.nio.channels SelectionKey]
           [java.util.concurrent.atomic AtomicInteger]
           [org.mqttkat MqttHandler]
           [org.mqttkat.client MqttClient]))

(use-fixtures :once tu/broker-fixture)

(def ^:private ramp
  "Connection counts to try, in order. Each step reconnects from nothing.

   Override with -Dmqttkat.scaleRamp=1000,5000,20000 to push it further.

   It stops at 10,000 because one source address only has about 14,116 ports
   for connect() — see the namespace docstring — and a rung past ~12,000 spends
   its time in the kernel's port allocator rather than in the broker. The
   20,000 that used to be here took 14.5 s and reported 1,379/s, which was a
   measurement of that and not of anything this test is about.

   25,000 is not reachable in this process anyway: it got this JVM killed by
   the kernel's OOM reaper — not an OutOfMemoryError, a SIGKILL. The Java heap
   was only 443 MB at 20,000, so it is not heap: both ends run here, so 25,000
   connections is 50,000 sockets in one process, and the kernel's send and
   receive buffers for those are native memory this JVM never sees.

   connection-scale-remote-test goes further on both counts, by putting the
   broker in its own process and spreading the client across source addresses."
  (if-let [override (System/getProperty "mqttkat.scaleRamp")]
    (mapv #(Long/parseLong (str/trim %)) (str/split override #","))
    [5000 10000]))

(def ^:private ^AtomicInteger connacks (AtomicInteger.))

(defn- shared-handler
  "One handler for every client. Packets are delivered on each client's own
   thread, so this only has to count; giving each client its own handler would
   mean ten thousand thread pools and channels that never get used."
  []
  (MqttHandler. ^clojure.lang.IFn
                (fn [msg _]
                  (when (= :CONNACK (:packet-type msg))
                    (.incrementAndGet connacks)))
                1))

(def ^:private client-id-prefix
  "Shared by the CONNECTs this test sends and by the count that looks for
   them, so the two cannot drift apart."
  "scale-")

(defn- held
  "How many of *this test's* connections the broker is holding.

   Counted by client id rather than from the broker's total. The broker is
   shared with the rest of the run and other namespaces leave clients on it —
   client-generator-2's walks have their disconnect transitions commented out,
   so two of its clients were still connected when this test started and the
   total read 10,002 where 10,000 was expected. Nothing but this test can move
   this number."
  []
  (count (filter (fn [[k v]]
                   (and (instance? SelectionKey k)
                        (str/starts-with? (str (:client-id v)) client-id-prefix)))
                 @handlers/*clients*)))

(defn- connect-msg [id]
  {:packet-type :CONNECT :protocol-name "MQTT" :protocol-version 4
   ;; Keep alive 0, so no timer is scheduled per client. The keep-alive reaper
   ;; is a separate scaling question — ten thousand at-at jobs — and mixing it
   ;; in would make it impossible to tell which of the two was hurting.
   :keep-alive 0 :clean-session? true :client-id id})

(defn- wait-for [pred ms what]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (loop []
      (cond
        (pred)                                  true
        (> (System/currentTimeMillis) deadline) (do (is false (str "timed out waiting for " what)) false)
        :else                                   (do (Thread/sleep 50) (recur))))))

(defn- heap-mb []
  (let [rt (Runtime/getRuntime)]
    (quot (- (.totalMemory rt) (.freeMemory rt)) (* 1024 1024))))

(defn- platform-threads []
  (.getThreadCount (ManagementFactory/getThreadMXBean)))

(defn- connect-batch!
  "Open `n` connections, returning them. Sequential on purpose: the broker
   accepts on one selector thread, and hammering it from many threads measures
   the client's ability to spam connect() rather than the broker's to hold
   what it accepted."
  [n handler]
  (.set connacks 0)
  (let [started (System/nanoTime)
        clients (loop [i 0, acc (transient [])]
                  (if (= i n)
                    (persistent! acc)
                    (let [c (MqttClient. ^String tu/host ^int (int tu/port)
                                         ^int (int 1) handler nil)]
                      (client/send-message c (connect-msg (str client-id-prefix i)))
                      (recur (inc i) (conj! acc c)))))]
    {:clients clients
     :connect-ms (quot (- (System/nanoTime) started) 1000000)}))

(defn- close-all! [clients]
  (doseq [^MqttClient c clients]
    (try (.close c) (catch Exception _ nil))))

(deftest ^:performance connection-scale
  (let [handler (shared-handler)]
    (doseq [n ramp]
      (testing (str n " connections")
        (let [before-threads (platform-threads)
              {:keys [clients connect-ms]} (connect-batch! n handler)
              connected? (wait-for #(= n (.get connacks)) 60000
                                   (str n " CONNACKs"))]
          (is connected? (str "expected " n " CONNACKs, got " (.get connacks)))
          (wait-for #(= n (held)) 30000 "the broker to report them all")
          (let [holding (held)]
            (is (= n holding)
                (str "the broker should be holding " n " connections"))
            (println
             (format "  %6d connections in %6d ms  (%6.0f/s)  heap %5d MB  platform threads %d (+%d)"
                     n connect-ms (/ (double n) (max 1 (/ connect-ms 1000.0)))
                     (heap-mb) (platform-threads) (- (platform-threads) before-threads))))

          (close-all! clients)
          (wait-for #(zero? (held)) 120000 "the broker to notice they had all gone")
          (is (zero? (held))
              "every connection should have been cleaned up before the next step")
          (System/gc)
          (Thread/sleep 500))))))
