(ns mqttkat.client-generator
  (:require [causatum.event-streams :as es]
            [clojure.test :refer [deftest]]
            [mqttkat.client :as client]
            [mqttkat.spec :refer :all]
            [clojure.core.async :as async])
  (:import  [org.mqttkat MqttHandler]))

(def subscribe-topics (atom #{}))

(def channel (async/chan 1))

(defn handler-fn [msg]
  ;(println "Posting on async channel: ")
  ;(clojure.pprint/pprint (dissoc msg :client-key))
  (async/>!! channel msg))

(def model
  {:graph
    {:connect [;{:disconnect {:weight 1}}
               ;;{:publish {:weight 1}}
               {:connack {:weight 1}}]
     :connack [{:subscribe {:weight 1}}]
     :subscribe [{:publish {:weight 1}}]
     :publish [{:publish {:weight 1}}]
               ;{:publish {:weight 2}}]
     :disconnect [{:connect {:weight 1}}]}})



(defn connect []
  (client/connect "localhost" 1883 (MqttHandler. ^clojure.lang.IFn handler-fn 2)))

(defn connack []
  (let [msg (async/<!! channel)]
    (= (:packet-type msg) :CONNACK)
    (println msg)))

(defn compare-packet-identifier [p-id-1 p-id-2]
  (when-not (= p-id-1 p-id-2)
    (throw (Exception. (str "Wrong packet identifier returned expected: " p-id-1 " but got " p-id-2)))))

(defn compare-payload [payload-1 payload-2]
   (when-not (= (seq payload-1) (seq payload-2))
      (throw (Exception. (str "MISCOMPARE!!!!!!!!!")))))

(defn qos-zero [payload]
  (let [msg (async/<!! channel)]
    (when-not (= (seq payload) (seq (:payload msg)))
       (do
         (println " NOT EQUAL MISCOMPARE!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!")
         (println (seq payload))
         (println (seq (:payload msg)))))))


(defn qos-one [payload packet-identifier]
  ;(println "QOS1 " packet-identifier)
  (let [first-message (async/<!! channel)
        _ (println first-message)
        second-message (async/<!! channel)
        _ (println second-message)]
    (if (= :PUBACK (:packet-type first-message))
      (do (let [received-packet-identifier (:packet-identifier first-message)]
            (compare-packet-identifier packet-identifier received-packet-identifier)
            (compare-payload payload (:payload second-message))))
      (do (let [received-packet-identifier (:packet-identifier second-message)]
            (compare-packet-identifier packet-identifier received-packet-identifier)
            (compare-payload payload (:payload first-message)))))))

(defn qos-two [payload packet-identifier]
  ;;(println "QOS2 " packet-identifier)
  (let [pubrec (async/<!! channel)]
    (println pubrec)
    (compare-packet-identifier packet-identifier (:packet-identifier pubrec))
    (client/pubrel packet-identifier)
    (let [first-message (async/<!! channel)
          second-message (async/<!! channel)]
      (println first-message)
      (println second-message)
      (if (= :PUBCOMP (:packet-type first-message))
        (do (let [packet-identifier (:packet-identifier first-message)]
              (compare-packet-identifier packet-identifier (:packet-identifier first-message))
              (compare-payload payload (:payload second-message))))
        (do (let [packet-identifier (:packet-identifier second-message)]
              (compare-packet-identifier packet-identifier (:packet-identifier second-message))
              (compare-payload payload (:payload first-message))))))))

(defn publish []
  (let [topic (rand-nth (into [] @subscribe-topics))
        ;;_ (println "received" topic)
        {payload :payload qos :qos packet-identifier :packet-identifier} (client/publish topic)]
     (condp = qos
       0 (qos-zero payload)
       1 (qos-one payload packet-identifier)
       2 (qos-two payload packet-identifier))))


(defn disconnect []
  (reset! subscribe-topics #{})
  (client/disconnect))

(defn subscribe []
  (let [topic-filter (client/subscribe)
        topics (map #(:topic-filter % ) (:topics topic-filter))
        c (count topics)]
    (swap! subscribe-topics (partial apply conj) topics)
    (let [msg (async/<!! channel)
          ret-count (count (:response msg))]
      (println msg)
      (= c ret-count))))





(deftest simulation
    ;; We create an event stream (or chain of state transitions, if you will) by
    ;; calling Causatum's event-stream function with our model and an initial seed
    ;; state.
    (doseq [{state :state} (take 10000   (es/event-stream model [{:rtime 0, :state :connect}]))]
      ;;(println "State:" state)
      ;;(Thread/sleep 10)
      (({:connect connect, :publish publish, :disconnect disconnect, :connack connack :subscribe subscribe} state))))
