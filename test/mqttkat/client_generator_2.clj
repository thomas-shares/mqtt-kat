(ns mqttkat.client-generator-2
  "A Causatum-driven simulation, not a unit test: it walks a state machine of
   MQTT exchanges for a thousand events. Tagged ^:performance, so `lein test`
   skips it (see :test-selectors in project.clj) — run it on purpose with
   `lein test :performance`."
  (:require [clojure.tools.logging :as log]
            [clojure.string :as str]
            [causatum.event-streams :as es]
            [mqttkat.test-util :as tu]
            [clojure.test :refer [deftest is use-fixtures]]
            [mqttkat.client :as client]
            ;;[mqttkat.spec :as mqtt]
            [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [clojure.core.async :as async])
  (:import  [java.util.concurrent.atomic AtomicLong]
            [org.mqttkat MqttHandler MqttStat]
            [org.mqttkat.client MqttClient]))

(def subscribe-topics (atom #{}))

;(def channel (async/chan 1))


(defn handler-fn [msg chan]
  (log/trace "Posting on async channel:")
  (log/trace (dissoc msg :client-key))
  (async/go (async/>! chan msg)))

(def handler (MqttHandler. ^clojure.lang.IFn handler-fn 2))

(use-fixtures :once tu/broker-fixture)


(def ^:private timings
  "One [qos prepare-nanos round-trip-nanos] per publish.

   prepare is the test client's own cost — generating a message from the spec,
   building a topic and encoding it. round-trip is send to last acknowledgement,
   which is the number that says anything about the broker. Timing them as one
   would report spec generation as though it were latency."
  (atom []))

(defn- ms [nanos] (/ (double nanos) 1e6))

(defn- fmt
  "Two decimals, always with a dot: %f follows the default locale, and half of
   Europe would render these with a comma."
  [x]
  (String/format java.util.Locale/US "%.2f" (to-array [(double x)])))

(defn- pct [sorted p]
  (nth sorted (min (dec (count sorted)) (int (* p (count sorted))))))

(defn- summary [nanos-seq]
  (let [sorted (vec (sort nanos-seq))
        n      (count sorted)]
    (when (pos? n)
      (let [mean (/ (reduce + 0 sorted) n)
            var  (/ (reduce + 0 (map #(let [d (- % mean)] (* d d)) sorted)) n)]
        {:n n :min (first sorted) :median (pct sorted 0.5) :mean mean
         :sd (Math/sqrt (double var)) :p95 (pct sorted 0.95) :p99 (pct sorted 0.99)
         :max (peek sorted)}))))

(defn- line [label s]
  (when s
    (format "    %-14s n %-5d min %-8s med %-8s mean %-8s sd %-8s p95 %-8s p99 %-8s max %s"
            label (:n s) (fmt (ms (:min s))) (fmt (ms (:median s))) (fmt (ms (:mean s)))
            (fmt (ms (:sd s))) (fmt (ms (:p95 s))) (fmt (ms (:p99 s))) (fmt (ms (:max s))))))

(defn- counters []
  {:sent           (.get ^AtomicLong MqttStat/sentMessages)
   :received       (.get ^AtomicLong MqttStat/receivedMessages)
   :sent-bytes     (.get ^AtomicLong MqttStat/sentBytes)
   :received-bytes (.get ^AtomicLong MqttStat/receivedBytes)})

(defn- report!
  "Log the run's statistics. `before` is a counters snapshot taken at the start:
   MqttStat's counters are static and live for the whole JVM, so the other
   performance test's traffic is in them too and has to be subtracted."
  [events elapsed-ms before]
  (let [after   (counters)
        secs    (/ (double elapsed-ms) 1000.0)
        delta   #(- (get after %) (get before %))
        samples @timings
        by-qos  (group-by first samples)
        rt      #(map (fn [[_ _ round-trip]] round-trip) %)
        prep    #(map (fn [[_ prepare _]] prepare) %)]
    (log/info
     (str "simulation summary\n"
          (format "    %-14s %d events in %ss\n" "events" events (fmt secs))
          (format "    %-14s qos0 %d  qos1 %d  qos2 %d  (total %d, %d skipped)\n"
                  "publishes"
                  (count (get by-qos 0)) (count (get by-qos 1)) (count (get by-qos 2))
                  (count samples) (- events (count samples)))
          "  round trip, milliseconds (publish sent -> last acknowledgement)\n"
          (str/join "\n" (keep identity
                               [(line "all" (summary (rt samples)))
                                (line "qos 0" (summary (rt (get by-qos 0))))
                                (line "qos 1" (summary (rt (get by-qos 1))))
                                (line "qos 2" (summary (rt (get by-qos 2))))]))
          "\n  client-side prepare, milliseconds (spec generation + encode)\n"
          (line "all" (summary (prep samples)))
          "\n  broker throughput over this test only\n"
          (format "    %-14s %s msg/s in, %s msg/s out\n"
                  "messages" (fmt (/ (delta :received) secs)) (fmt (/ (delta :sent) secs)))
          (format "    %-14s %s KB/s in, %s KB/s out"
                  "bytes" (fmt (/ (delta :received-bytes) secs 1024.0))
                  (fmt (/ (delta :sent-bytes) secs 1024.0)))))))

(def model
  {:graph
    {:connect [;{:disconnect {:weight 1}}
               ;;{:publish {:weight 1}}
               {:connack {:weight 1}}]
     :connack [{:subscribe {:weight 1}}]
     :subscribe [{:publish {:weight 1}}]
     :publish [{:publish {:weight 2}}]
              ;{:disconnect {:weight 1}}]
     :disconnect [{:connect {:weight 1}}]}})

(defn client []
  (client/client tu/host tu/port (MqttHandler. ^clojure.lang.IFn handler-fn 2)))

(defn- recv!
  "Next packet for `client`, or a failure. Every read in this namespace goes
   through here: a bare <!! on the client channel blocks forever when a packet
   goes missing, which used to wedge the whole suite rather than fail one test."
  [^MqttClient client]
  (or (first (async/alts!! [(.getChannel client) (async/timeout 2000)]))
      (throw (ex-info "no packet arrived within 2s" {:client client}))))

(defn connect [client]
  (client/connect client))

(defn connack [client]
  (let [msg (recv! client)]
    (is (= (:packet-type msg) :CONNACK))
    (log/debug "R" msg)))

(defn compare-packet-identifier [p-id-1 p-id-2]
  (is (= p-id-1 p-id-2)))

(defn compare-payload [payload-1 payload-2]
  (is (= (seq payload-1) (seq payload-2))))

(defn qos-zero [client payload]
  (let [msg (recv! client)]
    (compare-payload payload (:payload  msg))
    (is (= 0 (:qos msg)))))

(defn process-qos-one [client msg]
  (when-not (zero? (:qos msg))
    (client/puback client (:packet-identifier msg))))

(defn qos-one [client payload packet-identifier]
  (log/trace "QOS1" packet-identifier)
  (let [first-message (recv! client)
        _ (log/debug  "first: " first-message)
        second-message (recv! client)
        _ (log/debug  "second " second-message)]
    (if (= :PUBACK (:packet-type first-message))
      (do (let [received-packet-identifier (:packet-identifier first-message)]
            (compare-packet-identifier packet-identifier received-packet-identifier)
            (compare-payload payload (:payload second-message))
            (process-qos-one client second-message)))
      (do (let [received-packet-identifier (:packet-identifier second-message)]
            (compare-packet-identifier packet-identifier received-packet-identifier)
            (compare-payload payload (:payload first-message))
            (process-qos-one client first-message))))))

(defn process-return [client msg]
  (condp = (:qos msg)
    0 nil
    1 (process-qos-one client msg)
    2 (do
        (client/pubrec client (:packet-identifier msg))
        (let [pubrel (recv! client)]
          (log/debug  "R " pubrel)
          (is (= :PUBREL (:packet-type pubrel)))
          (client/pubcomp client (:packet-identifier pubrel))))))

(defn qos-two [client payload packet-identifier]
  (log/trace  "QOS2 " packet-identifier)
  (let [pubrec (recv! client)]
    (log/debug  "R " pubrec)
    (compare-packet-identifier packet-identifier (:packet-identifier pubrec))
    (client/pubrel client packet-identifier)
    (let [first-message (recv! client)
          second-message (recv! client)]
      (log/debug  "R "first-message)
      (log/debug  "R " second-message)
      (if (= :PUBCOMP (:packet-type first-message))
        (do (let [packet-identifier (:packet-identifier first-message)]
              (compare-packet-identifier packet-identifier (:packet-identifier first-message))
              (compare-payload payload (:payload second-message))
              (process-return client  second-message)))
        (do (let [packet-identifier (:packet-identifier second-message)]
              (compare-packet-identifier packet-identifier (:packet-identifier second-message))
              (compare-payload payload (:payload first-message))
              (process-return client first-message)))))))

(defn filter-to-topic [filter]
  (-> filter
    (clojure.string/replace  #"\+" (gen/generate (s/gen (s/and string? #(<= 2 (count %))))))
    (clojure.string/replace  #"#" (gen/generate (s/gen (s/and string? #(<= 2 (count %))))))))

(defn publish [client]
  ;; client/subscribe filters the generated topic list, and can filter it down
  ;; to nothing — rand-nth on an empty vector then throws and takes the whole
  ;; simulation with it.
  (if-let [filters (seq @subscribe-topics)]
    (let [started (System/nanoTime)
          filter  (rand-nth (vec filters))
          topic   (filter-to-topic filter)
          _ (log/debug "S filter:" filter)
          _ (log/debug "S topic:" topic)
          {payload :payload qos :qos packet-identifier :packet-identifier} (client/publish client topic)
          sent    (System/nanoTime)]
      (condp = qos
        0 (qos-zero client payload)
        1 (qos-one client payload packet-identifier)
        2 (qos-two client payload packet-identifier))
      (swap! timings conj [qos (- sent started) (- (System/nanoTime) sent)]))
    (log/debug "nothing subscribed yet, skipping publish")))


(defn disconnect [client]
  (reset! subscribe-topics #{})
  (client/disconnect client))

(defn subscribe [client]
  (let [topic-filter (client/subscribe ^MqttClient client)
        topics (map #(:topic-filter % ) (:topics topic-filter))
        c (count topics)]
    (swap! subscribe-topics (partial apply conj) topics)
    (let [msg (recv! client)
          ret-count (count (:response msg))]
      (log/debug  "R " msg)
      (is (= c ret-count)))))


(deftest ^:performance simulation
    ;; We create an event stream (or chain of state transitions, if you will) by
    ;; calling Causatum's event-stream function with our model and an initial seed
    ;; state.
   (let [start-time (System/currentTimeMillis)
         _ (reset! timings [])
         before (counters)
         events 1000
         client-numbers 1
         client (client)]
         ;clients (take client-numbers (repeatedly (client)))
         ;streams (take client-numbers (repeatedly (es/event-stream model [{:rtime 0, :state :connect}])))]
     (doseq [{state :state} (take events (es/event-stream model [{:rtime 0, :state :connect}]))]
       (log/trace "State:" state)
       ;;(Thread/sleep 10)
       (({:connect connect, :publish publish, :disconnect disconnect, :connack connack :subscribe subscribe} state) client))
     (report! events (- (System/currentTimeMillis) start-time) before)))
