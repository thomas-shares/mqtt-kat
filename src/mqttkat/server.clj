(ns mqttkat.server
  (:require [mqttkat.handlers :as h]
            [mqttkat.handlers.connect :as connect]
            [mqttkat.handlers.disconnect :as disconnect]
            [mqttkat.util :as util]
            [mqttkat.interfaces :refer [*system* *server*]]
            [overtone.at-at :as at]
            [clj-async-profiler.core :as prof]
            [environ.core :refer [env]]
            [integrant.core :as ig])
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


(def config
  (some-> env
          :config-file
          slurp
          ig/read-string))


(defn default-handler-fn [{:keys [packet-type] :as msg} _]
  ;;(println "message is received. " msg)
  (when packet-type
    ((packet-type handler-map) msg)))

(defmethod ig/init-key :broker/service
  [_ {:keys [port ip] :or {port 1883 ip "0.0.0.0"} :as opts}]
  (let [h (MqttHandler. ^clojure.lang.IFn default-handler-fn 4)
        s (-> (MqttServer. ^String ip ^int port h)
              (.start))]
    (assoc opts :server s)))

(defmethod ig/init-key :broker/profiler
  [_ {:keys [port :enabled?] :or {port 8080}}]
  (when :enabled?
    (prof/serve-files port)))

(defmethod ig/halt-key! :broker/service
  [_ {:keys [server stop-timeout] :or {stop-timeout 1000}}]
  (do
    (let [^MqttServer server server]
      (.stop server stop-timeout))
    (at/stop-and-reset-pool! h/my-pool)))


(defmethod ig/halt-key! :broker/profiler
  [_ _]
  (when-not (= (prof/status) "Profiler is not active\n")
    (prof/stop)))

(defn start!
  ([] (do
        (dosync
          (ref-set *system* (ig/init config)))
        (reset! *server* (get-in @*system* [:broker/service :server]))))
  ([config-keys]
   (do
     (dosync
       (ref-set *system* (ig/init-key config config-keys)))
     (reset! *server* (get-in @*system* [:broker/service :server])))))

(defn stop!
  ([]
   (do
     (println "Server stopping...")
     (ig/halt! @*system*)
     (dosync (ref-set *system* nil))
     (reset! *server* nil))))

(defn -main [& _]
  (start!)
  (util/info))


(comment
  (start!)
  (stop!)

  (do
    (stop!)
    (start!)))
