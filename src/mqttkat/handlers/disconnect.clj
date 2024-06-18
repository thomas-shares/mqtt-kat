(ns mqttkat.handlers.disconnect
  (:require [mqttkat.s :refer [*server*]]
            [mqttkat.handlers :refer [logger handle-will-if-present remove-client! remove-timer!]])
  (:import [org.mqttkat.server MqttServer]))

(defn disconnect-client [client-key]
  (logger "Disconnecting client " client-key)
  (handle-will-if-present client-key)
  (remove-timer! client-key)
  (remove-client! client-key)
  (let [{s :server} (meta @*server*)]
    (.closeConnection ^MqttServer s client-key)))

(defn disconnect [msg]
  (disconnect-client (:client-key msg)))
