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

(defn publish []
  (let [topic (rand-nth (into [] @subscribe-topics))
        _ (println "received" topic)
        payload (client/publish topic)
        msg (async/<!! channel)]
      (when-not (= (seq payload) (seq (:payload msg)))
         (do
           (println " NOT EQUAL MISCOMPARE!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!")
           (println (seq payload))
           (println (seq (:payload msg)))))))

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
    (doseq [{state :state} (take 10   (es/event-stream model [{:rtime 0, :state :connect}]))]
      ;;(println "State:" state)
      (Thread/sleep 20)
      (({:connect connect, :publish publish, :disconnect disconnect, :connack connack :subscribe subscribe} state))))
