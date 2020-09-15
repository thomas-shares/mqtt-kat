(ns mqttkat.server
  (:require [mqttkat.handlers :as h]
            [mqttkat.handlers.connect :as connect]
            [mqttkat.handlers.disconnect :as disconnect]
            [mqttkat.util :as util]
            [mqttkat.s :refer [*server*]])
  ;[clj-async-profiler.core :as prof])
  (:import [org.mqttkat.server MqttServer]
           [org.mqttkat MqttHandler])
  (:gen-class))

(set! *warn-on-reflection* true)

(def handler-map {:CONNECT      connect/connect
                  :CONNACK      h/connack
                  :PUBLISH      h/publish
                  :PUBACK       h/puback
                  :PUBREC       h/pubrec
                  :PUBREL       h/pubrel
                  :PUBCOMP      h/pubcomp
                  :SUBSCRIBE    h/subscribe
                  :UNSUBSCRIBE  h/unsubscribe
                  :PINGREQ      h/pingreq
                  :PINGRESP     h/pingresp
                  :DISCONNECT   disconnect/disconnect
                  :AUTHENTICATE h/authenticate})

(defn default-handler-fn [{:keys [packet-type] :as msg} _]
  ;(println msg)
  (when packet-type
    ((packet-type handler-map) msg)))

(defn run-server [ip port handler]
  ;(prof/start {})
  (let [s (MqttServer. ^String ip ^int port handler)
        stop-server (fn stop-server [& {:keys [timeout] :or {timeout 100}}]
                      (println "meta stop...")
                      (.stop s timeout))]
    (.start s)
    (with-meta stop-server {:local-port (.getPort s)
                            :server     s})))


(defn start!
  ([] (start! "0.0.0.0" 1883 (MqttHandler. ^clojure.lang.IFn default-handler-fn 4)))
  ([ip port handler]
   (reset! *server* (run-server ip port handler))))

(defn stop! []
  (when (@*server*)
    (do (println "Server stopping...")
        (alter-meta! *server* #(assoc % :timeout 1000))
        (reset! *server* nil))))


(defn -main [& _]
  (start!)
  (util/info))
