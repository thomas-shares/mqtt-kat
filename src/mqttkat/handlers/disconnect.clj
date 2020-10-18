(ns mqttkat.handlers.disconnect
  (:require [mqttkat.interfaces :refer [*server*]]
            [mqttkat.handlers :refer :all])
  (:import [org.mqttkat.server MqttServer]))

(defn disconnect-client [client-key]
  (do
    (logger "Disconnecting client " client-key)
    (swap! *clients* dissoc client-key)
    (logger (keys @*clients*))
    (.closeConnection ^MqttServer  @*server* client-key)))

(defn disconnect
  [{:keys [client-key] :as msg}]
  (do
    (logger "clj DISCONNECT received: " msg)
    (logger "before swap " (keys @*clients*))
    (swap! *clients* dissoc client-key)
    (logger "after swap " (keys @*clients*))
    (disconnect-client client-key)))

