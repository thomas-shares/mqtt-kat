(ns mqttkat.server
  (:require [mqttkat.handlers :as h]
            [mqttkat.util :as util]
            [mqttkat.s :refer [server]])
            ;[clj-async-profiler.core :as prof])
  (:import [org.mqttkat.server MqttServer]
           [org.mqttkat MqttHandler])
  (:gen-class))

(set! *warn-on-reflection* true)

(def handler-map {:CONNECT h/connect
                  :CONNACK h/connack
                  :PUBLISH h/publish
                  :PUBACK  h/puback
                  :PUBREC  h/pubrec
                  :PUBREL  h/pubrel
                  :PUBCOMP h/pubcomp
                  :SUBSCRIBE h/subscribe
                  :UNSUBSCRIBE h/unsubscribe
                  :PINGREQ h/pingreq
                  :PINGRESP h/pingresp
                  :DISCONNECT h/disconnect
                  :AUTHENTICATE h/authenticate})

(defn handler-fn [msg dummy]
  ;(println msg)
  (when-let [packet-type (:packet-type msg)]
    ((packet-type handler-map) msg)))

(defn run-server [ip port handler]
  ;(prof/start {})
  (let [s (MqttServer. ^String ip ^int port handler)]
    (.start s)
    (with-meta
      (fn stop-server [& {:keys [timeout] :or {timeout 100}}]
        (println "meta stop...")
        (.stop s timeout))
      #_(fn send-message [key msg]
         (.sendMessage s key msg))
      {:local-port (.getPort s)
       :server s})))

(defn start
  ([] (start "0.0.0.0" 1883 (MqttHandler. ^clojure.lang.IFn handler-fn 4)))
  ([ip port handler]
   (reset! server (run-server ip port handler))))

(defn stop []
  (when-not (nil? @server)
    ;(prof/stop {})
    (println "Server stopping...")
    (@server :timeout 1000)
    (reset! server nil)))

(defn -main [& args]
  (start)
  (util/info))
