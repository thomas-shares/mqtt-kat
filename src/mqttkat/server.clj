(ns mqttkat.server
  (:require [mqttkat.handlers :as h])
  (:use [mqttkat.s :only [server]])
  (:import [org.mqttkat.server MqttServer MqttHandler]))


(def handler-map {:CONNECT h/connect
                  :CONNACK h/connack
                  :PUBLISH h/publish
                  :PUBACK  h/puback
                  :PUBREC  h/pubrec
                  :PUBREL  h/pubrel
                  :PUBCOMP h/pubcomp
                  :SUBSCRIBE h/subscribe
                  :UNSCUBSCRIBE h/unsubscribe
                  :PINGREQ h/pingreq
                  :PINGRES h/pingresp
                  :DISCONNECT h/disconnect
                  :AUTHENTICATE h/authenticate})

(defn handler-fn [msg]
  ;(println msg)
  (when-let [packet-type (:packet-type msg)]
    ((packet-type handler-map) msg)))

(defn run-server [ip port]
  (let [s (MqttServer. ip port (MqttHandler. handler-fn 4))]
    (.start s)
    (with-meta
      (fn stop-server [& {:keys [timeout] :or {timeout 100}}]
        (.stop s timeout))
      ;(fn send-message [key msg]
      ;  (.sendMessage s key msg))
      {:local-port (.getPort s)
       :server s})))

(defn start []
  (reset! server (run-server "0.0.0.0" 1883)))

(defn stop []
  (when-not (nil? @server)
    (@server :timeout 100)
    (reset! server nil)))
