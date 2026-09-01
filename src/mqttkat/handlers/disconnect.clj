(ns mqttkat.handlers.disconnect
  (:require [clojure.tools.logging :as log]
            [mqttkat.s :refer [*server*]]
            [mqttkat.handlers :refer [handle-will-if-present remove-client! remove-timer!]])
  (:import [org.mqttkat.server MqttServer]))

(defn disconnect-client [client-key]
  (log/trace "Disconnecting client::" client-key)
  (handle-will-if-present client-key)
  (remove-timer! client-key)
  (remove-client! client-key)
  (let [{s :server} (meta @*server*)]
    (.closeConnection ^MqttServer s client-key)))

(defn disconnect [msg]
  (log/trace "Disconnecting client:" msg)
  (disconnect-client (:client-key msg)))
