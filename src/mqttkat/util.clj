(ns mqttkat.util
  (:require [clojure.tools.logging :as log]
            [mqttkat.handlers :as handlers])
  (:import  [org.mqttkat MqttStat]))

(def interval 10)

(defn info []
  (loop [sent-message-last-time 0
         received-message-last-time 0]
    (let [sent-now (.get MqttStat/sentMessages)
          received-now (.get MqttStat/receivedMessages)
          map {:last-active (get-in @handlers/*clients* [(first (keys @handlers/*clients*)) :last-active])
               :clients (count @handlers/*clients*)
               :sent-per-second (float (/ (- sent-now sent-message-last-time) interval))
               :total-sent sent-now
               :received-per-second (float(/ (- received-now received-message-last-time) interval))
               :total-received received-now}]
      (log/trace map)
      ;(log/trace (map #(select-keys (val %) [:client-id]) @handlers/clients))
      (log/info "stats" map "subscribed-topics"
                (get-in @handlers/*clients* [(first (keys @handlers/*clients*)) :subscribed-topics]))
      (Thread/sleep ^long (* interval 1000))
      (recur sent-now received-now))))
