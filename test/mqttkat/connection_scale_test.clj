(ns mqttkat.connection-scale-test
  "How many connections the broker will hold at once.

   Tagged ^:performance, so `lein test` skips it; run it with
   `lein test :performance`, or on its own with

     lein test :performance :only mqttkat.connection-scale-test

   The ceiling on this machine is not the broker. Every connection to one
   listener from one address needs an ephemeral port, and
   /proc/sys/net/ipv4/ip_local_port_range is 32768-60999 — 28,232 of them, some
   already spoken for. Past that, connect fails with EADDRNOTAVAIL and the
   number says nothing about the broker at all. File descriptors are not the
   limit here (524,288), and neither is memory.

   Both ends run in this JVM, so a connection costs two sockets and the
   figures below cover the client side as well as the broker's.

   Only the first step of the ramp measures a clean accept rate. Closing ten
   thousand connections leaves ten thousand sockets in TIME-WAIT holding their
   ephemeral ports for a minute, so each step starts with fewer ports than the
   last and the kernel works harder to find each one. The rate falling off
   across the ramp is that, not the broker. Watch it with

     ss -tan | grep -c TIME-WAIT

   while this runs; reading it from inside the JVM is not an option, because
   /proc/net/tcp throws IOException: Invalid argument there while cat and
   python read it perfectly well."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [mqttkat.client :as client]
            [mqttkat.util :as util]
            [mqttkat.test-util :as tu])
  (:import [java.lang.management ManagementFactory]
           [java.util.concurrent.atomic AtomicInteger]
           [org.mqttkat MqttHandler]
           [org.mqttkat.client MqttClient]))

(use-fixtures :once tu/broker-fixture)

(def ^:private ramp
  "Connection counts to try, in order. Each step reconnects from nothing.

   Override with -Dmqttkat.scaleRamp=1000,5000,20000 to push it further.

   25,000 is not in the default ramp because it got this JVM killed by the
   kernel's OOM reaper — not an OutOfMemoryError, a SIGKILL. The Java heap was
   only 443 MB at 20,000, so it is not heap: both ends run here, so 25,000
   connections is 50,000 sockets in one process, and the kernel's send and
   receive buffers for those are native memory this JVM never sees. Going
   higher wants the client on another machine."
  (if-let [override (System/getProperty "mqttkat.scaleRamp")]
    (mapv #(Long/parseLong (str/trim %)) (str/split override #","))
    [10000 20000]))

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
                      (client/send-message c (connect-msg (str "scale-" i)))
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
          (wait-for #(= n (:connected (util/client-counts))) 30000
                    "the broker to report them all")
          (let [counts (util/client-counts)]
            (is (= n (:connected counts))
                (str "the broker should be holding " n " connections"))
            (println
             (format "  %6d connections in %6d ms  (%6.0f/s)  heap %5d MB  platform threads %d (+%d)"
                     n connect-ms (/ (double n) (max 1 (/ connect-ms 1000.0)))
                     (heap-mb) (platform-threads) (- (platform-threads) before-threads))))

          (close-all! clients)
          (wait-for #(zero? (:connected (util/client-counts))) 120000
                    "the broker to notice they had all gone")
          (is (zero? (:connected (util/client-counts)))
              "every connection should have been cleaned up before the next step")
          (System/gc)
          (Thread/sleep 500))))))
