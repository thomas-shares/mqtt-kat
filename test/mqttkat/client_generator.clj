(ns mqttkat.client-generator
  (:require [clojure.test :refer [deftest is]]
            [mqttkat.client :as client]
            ;;[mqttkat.spec :as mqtt]
            [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [clojure.core.async :as async]
            [overtone.at-at :as at])
  (:import  [org.mqttkat MqttHandler MqttStat]
            [org.mqttkat.client MqttClient]))

(def subscribe-topics (atom {}))
(def my-pool (at/mk-pool))
;(def channel (async/chan 1))

(defn handler-fn [msg chan]
  ;;(println "Posting on async channel: ")
  ;(clojure.pprint/pprint (dissoc msg :client-key))
  (async/go (async/>! chan msg)))

(defn client []
  (client/client "localhost" 1883 (MqttHandler. ^clojure.lang.IFn handler-fn 2)))

(defn connect [client]
  (client/connect client))

(defn connack [client]
  (let [msg (async/<!! (.getChannel ^MqttClient client))]
    (is (= (:packet-type msg) :CONNACK))
    (client/logger "R " msg)))

(defn compare-packet-identifier [p-id-1 p-id-2]
  (is (= p-id-1 p-id-2)))

(defn compare-payload [payload-1 payload-2]
  (is (= (seq payload-1) (seq payload-2))))

(defn qos-zero [client payload]
  (let [msg (async/<!! (.getChannel ^MqttClient client))]
    (compare-payload payload (:payload  msg))
    (is (= 0 (:qos msg)))))

(defn process-qos-one [client msg]
  (when-not (zero? (:qos msg))
    (client/puback client (:packet-identifier msg))))

(defn qos-one [client payload packet-identifier]
  ;(println "QOS1 " packet-identifier)
  (let [first-message (async/<!! (.getChannel ^MqttClient client))
        _ (client/logger  "first: " first-message)
        second-message (async/<!! (.getChannel ^MqttClient client))
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
        (let [pubrel (async/<!! (.getChannel ^MqttClient client))]
          (client/logger  "R " pubrel)
          (is (= :PUBREL (:packet-type pubrel)))
          (client/pubcomp client (:packet-identifier pubrel))))))

(defn qos-two [client payload packet-identifier]
  ;(client/logger  "QOS2 " packet-identifier)
  (let [pubrec (async/<!! (.getChannel ^MqttClient client))]
    (client/logger  "R " pubrec)
    (compare-packet-identifier packet-identifier (:packet-identifier pubrec))
    (client/pubrel client packet-identifier)
    (let [first-message (async/<!! (.getChannel ^MqttClient client))
          second-message (async/<!! (.getChannel ^MqttClient client))]
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
  (let [filter (rand-nth (into [] (get @subscribe-topics client)))
        topic (filter-to-topic filter)
        ;_ (client/logger "S filter: " filter)
        ;_ (client/logger "S topic: " topic)
        {payload :payload qos :qos packet-identifier :packet-identifier} (client/publish client topic)]
     (condp = qos
       0 (qos-zero client payload)
       1 (qos-one client payload packet-identifier)
       2 (qos-two client payload packet-identifier))))


(defn disconnect [client]
  (reset! subscribe-topics #{})
  (client/disconnect))

(defn subscribe [client]
  (let [topic-filter (client/subscribe ^MqttClient client)
        topics (map #(:topic-filter % ) (:topics topic-filter))
        c (count topics)]
    (swap! subscribe-topics assoc client topics)
    (let [msg (async/<!! (.getChannel ^MqttClient client))
          ret-count (count (:response msg))]
      (client/logger  "R " msg)
      (is (= c ret-count)))))


(defn start-client [client]
  (connect client)
  (connack client)
  (subscribe client)
  (at/interspaced 1000 #(publish client) my-pool :initial-delay 1000))


(deftest multiple-clients
  (let [;;start-time (System/currentTimeMillis)
        clients (into [] (take 2000   (repeatedly #(client))))]
    (doseq [client clients]
      ;;(println client)
      (at/after 5 #(start-client client) my-pool))
    (Thread/sleep 60000)
    (println "done sleeping....")))
