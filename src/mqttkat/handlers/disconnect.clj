(ns mqttkat.handlers.disconnect
  (:require [mqttkat.s :refer [server]]
            [mqttkat.handlers :refer :all])
  (:import [org.mqttkat.packages MqttDisconnect]
           [org.mqttkat.server MqttServer]))

(defn disconnect-client [client-key]
  (logger "Disconnecting client " client-key)
  (swap! clients dissoc client-key)
  (logger (keys @clients))
  (let [s (:server (meta @server))]
    (.closeConnection ^MqttServer s client-key)))


(defn disconnect [msg]
  (logger "clj DISCONNECT received: " msg)
  ;(println "count: " (count (get @subscribers "test")))
  ;(swap! sub2 tr/delete-matching  (:client-key msg))
  (logger "before swap "  (keys @clients))
  (swap! clients dissoc (:client-key msg))
  (logger "after swap " (keys @clients))
  (disconnect-client (:client-key msg)))
