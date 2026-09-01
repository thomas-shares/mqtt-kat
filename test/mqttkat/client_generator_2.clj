(ns mqttkat.client-generator-2
  "A Causatum-driven simulation, not a unit test: it walks a state machine of
   MQTT exchanges for a thousand events. Tagged ^:performance, so `lein test`
   skips it (see :test-selectors in project.clj) — run it on purpose with
   `lein test :performance`."
  (:require [causatum.event-streams :as es]
            [mqttkat.test-util :as tu]
            [clojure.test :refer [deftest is use-fixtures]]
            [mqttkat.client :as client]
            ;;[mqttkat.spec :as mqtt]
            [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [clojure.core.async :as async])
  (:import  [org.mqttkat MqttHandler MqttStat]
            [org.mqttkat.client MqttClient]))

(def subscribe-topics (atom #{}))

;(def channel (async/chan 1))


(defn handler-fn [msg chan]
  ;;(println "Posting on async channel: ")
  ;(clojure.pprint/pprint (dissoc msg :client-key))
  (async/go (async/>! chan msg)))

(def handler (MqttHandler. ^clojure.lang.IFn handler-fn 2))

(use-fixtures :once tu/broker-fixture)


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
    (client/logger "R " msg)))

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
  ;(println "QOS1 " packet-identifier)
  (let [first-message (recv! client)
        _ (client/logger  "first: " first-message)
        second-message (recv! client)
        _ (client/logger  "second " second-message)]
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
          (client/logger  "R " pubrel)
          (is (= :PUBREL (:packet-type pubrel)))
          (client/pubcomp client (:packet-identifier pubrel))))))

(defn qos-two [client payload packet-identifier]
  ;(client/logger  "QOS2 " packet-identifier)
  (let [pubrec (recv! client)]
    (client/logger  "R " pubrec)
    (compare-packet-identifier packet-identifier (:packet-identifier pubrec))
    (client/pubrel client packet-identifier)
    (let [first-message (recv! client)
          second-message (recv! client)]
      (client/logger  "R "first-message)
      (client/logger  "R " second-message)
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
  (let [filter (rand-nth (into [] @subscribe-topics))
        topic (filter-to-topic filter)
        _ (client/logger "S filter: " filter)
        _ (client/logger "S topic: " topic)
        {payload :payload qos :qos packet-identifier :packet-identifier} (client/publish client topic)]
     (condp = qos
       0 (qos-zero client payload)
       1 (qos-one client payload packet-identifier)
       2 (qos-two client payload packet-identifier))))


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
      (client/logger  "R " msg)
      (is (= c ret-count)))))


(deftest ^:performance simulation
    ;; We create an event stream (or chain of state transitions, if you will) by
    ;; calling Causatum's event-stream function with our model and an initial seed
    ;; state.
   (let [start-time (System/currentTimeMillis)
         client-numbers 1
         client (client)]
         ;clients (take client-numbers (repeatedly (client)))
         ;streams (take client-numbers (repeatedly (es/event-stream model [{:rtime 0, :state :connect}])))]
     (doseq [{state :state} (take 1000   (es/event-stream model [{:rtime 0, :state :connect}]))]
       ;;(println "State:" state)
       ;;(Thread/sleep 10)
       (({:connect connect, :publish publish, :disconnect disconnect, :connack connack :subscribe subscribe} state) client))
     (let [time (/ (- (System/currentTimeMillis) start-time) 1000.0)]
       (println
         "sent per sec "(/ #_{:clj-kondo/ignore [:java-static-field-call]}
                           (MqttStat/sentMessages) time)
         "received per sec " (/ #_{:clj-kondo/ignore [:java-static-field-call]}
                                (MqttStat/receivedMessages) time)))))
