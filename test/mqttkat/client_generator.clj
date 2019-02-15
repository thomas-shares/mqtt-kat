(ns mqttkat.client-generator
  (:require [causatum.event-streams :as es]
            [clojure.test :refer [deftest is]]
            [mqttkat.client :as client]
            [mqttkat.spec :refer :all]
            [clojure.core.async :as async])
  (:import  [org.mqttkat MqttHandler MqttStat]))

(def subscribe-topics (atom #{}))

(def channel (async/chan 1))

(defn handler-fn [msg]
  ;(println "Posting on async channel: ")
  ;(clojure.pprint/pprint (dissoc msg :client-key))
  (async/go (async/>! channel msg)))

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
  (client/client "localhost" 1883 (MqttHandler. ^clojure.lang.IFn handler-fn 2)))

(defn connect [client]
  (client/connect client))

(defn connack [client]
  (let [msg (async/<!! channel)]
    (is (= (:packet-type msg) :CONNACK))
    (client/logger "R " msg)))

(defn compare-packet-identifier [p-id-1 p-id-2]
  (is (= p-id-1 p-id-2)))

(defn compare-payload [payload-1 payload-2]
  (is (= (seq payload-1) (seq payload-2))))

(defn qos-zero [payload]
  (let [msg (async/<!! channel)]
    (compare-payload payload (:payload  msg))
    (is (= 0 (:qos msg)))))

(defn process-qos-one [client msg]
  (when-not (zero? (:qos msg))
    (client/puback client (:packet-identifier msg))))

(defn qos-one [client payload packet-identifier]
  ;(println "QOS1 " packet-identifier)
  (let [first-message (async/<!! channel)
        _ (client/logger  "first: " first-message)
        second-message (async/<!! channel)
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
        (let [pubrel (async/<!! channel)]
          (client/logger  "R " pubrel)
          (is (= :PUBREL (:packet-type pubrel)))
          (client/pubcomp client (:packet-identifier pubrel))))))

(defn qos-two [client payload packet-identifier]
  ;(client/logger  "QOS2 " packet-identifier)
  (let [pubrec (async/<!! channel)]
    (client/logger  "R " pubrec)
    (compare-packet-identifier packet-identifier (:packet-identifier pubrec))
    (client/pubrel client packet-identifier)
    (let [first-message (async/<!! channel)
          second-message (async/<!! channel)]
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

(defn publish [client]
  (let [topic (rand-nth (into [] @subscribe-topics))
        ;_ (client/logger  "S" topic)
        {payload :payload qos :qos packet-identifier :packet-identifier} (client/publish client topic)]
     (condp = qos
       0 (qos-zero payload)
       1 (qos-one client payload packet-identifier)
       2 (qos-two client payload packet-identifier))))


(defn disconnect [client]
  (reset! subscribe-topics #{})
  (client/disconnect))

(defn subscribe [client]
  (let [topic-filter (client/subscribe client)
        topics (map #(:topic-filter % ) (:topics topic-filter))
        c (count topics)]
    (swap! subscribe-topics (partial apply conj) topics)
    (let [msg (async/<!! channel)
          ret-count (count (:response msg))]
      (client/logger  "R " msg)
      (is (= c ret-count)))))


 


(deftest simulation
    ;; We create an event stream (or chain of state transitions, if you will) by
    ;; calling Causatum's event-stream function with our model and an initial seed
    ;; state.
   (let [start-time (System/currentTimeMillis)
         client (client)]
     (doseq [{state :state} (take 100   (es/event-stream model [{:rtime 0, :state :connect}]))]
       ;;(println "State:" state)
       ;;(Thread/sleep 10)
       (({:connect connect, :publish publish, :disconnect disconnect, :connack connack :subscribe subscribe} state) client))
     (let [time (/ (- (System/currentTimeMillis) start-time) 1000.0)]
       (println
         "sent per sec "(/ (MqttStat/sentMessages) time)
         "received per sec " (/ (MqttStat/receivedMessage) time)))))
