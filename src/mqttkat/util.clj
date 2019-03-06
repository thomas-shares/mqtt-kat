(ns mqttkat.util
  (:require [mqttkat.handlers :as handlers])
  (:import  [org.mqttkat MqttStat]))

(def interval 10)

(defn info []
  (loop [sent-message-last-time 0
         received-message-last-time 0]
    (let [sent-now (.get MqttStat/sentMessages)
          received-now (.get MqttStat/receivedMessages)
          map {:clients (count @handlers/clients)
               :sent-per-second (float (/ (- sent-now sent-message-last-time) interval))
               :total-sent sent-now
               :received-per-second (float(/ (- received-now received-message-last-time) interval))
               :total-received received-now}]
      (clojure.pprint/pprint map)

      (Thread/sleep (* interval 1000))
      (recur sent-now received-now))))
