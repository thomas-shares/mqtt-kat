(ns mqttkat.client-generator
  (:require [causatum.event-streams :as es]
            [clojure.test :refer [deftest]]
            [mqttkat.client :as client]
            [mqttkat.spec :refer :all]
            [clojure.core.async :as async])
  (:import  [org.mqttkat MqttHandler]))


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
     :connack [{:publish {:weight 1}}]
     :publish [{:disconnect {:weight 1}}]
               ;{:publish {:weight 2}}]
     :disconnect [{:connect {:weight 1}}]}})

;(def client (client/client "localhost" 1883))

;
(defn connect []
  (client/connect "localhost" 1883 (MqttHandler. ^clojure.lang.IFn handler-fn 2)))

(defn connack []
  (println "receive connack")
  (let [msg (async/<!! channel)]
    (= (:packet-type msg) :CONNACK)))

(defn publish []
  (client/publish))

(defn disconnect []
  (client/disconnect))

(deftest simulation
    ;; We create an event stream (or chain of state transitions, if you will) by
    ;; calling Causatum's event-stream function with our model and an initial seed
    ;; state.
    (doseq [{state :state} (take 10   (es/event-stream model [{:rtime 0, :state :connect}]))]
      (println "State:" state)
      (Thread/sleep 50)
      (({:connect connect, :publish publish, :disconnect disconnect, :connack connack} state))))
