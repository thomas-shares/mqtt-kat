(ns mqttkat.client
  (:require [clojure.spec.gen.alpha :as gen]
            [clojure.spec.alpha :as s]
            [mqttkat.spec :refer :all])
  (:import [org.mqttkat.client MqttClient]
           [org.mqttkat MqttHandler]
           [org.mqttkat.packages MqttConnect MqttPingReq MqttPublish
             MqttDisconnect MqttSubscribe]))


(set! *warn-on-reflection* true)

(def client-atom (atom nil))

(defn handler-fn [msg]
  (println "clj handler: " msg))

(defn client
  ([host port] (client host port (MqttHandler. ^clojure.lang.IFn handler-fn 2)))
  ([host port handler]
   (when (nil? @client-atom)
      (let [client (MqttClient. ^String host ^int port 2 handler)]
        (reset! client-atom client)))))

(defn client2 [host port]
  (let [client (MqttClient. ^String host ^int port 2 ( MqttHandler. ^clojure.lang.IFn handler-fn 2))]
    (reset! client-atom client)))

(defn connect
  ([] (connect "127.0.0.1" 1883))
  ([host port] (client host port (MqttHandler. ^clojure.lang.IFn handler-fn 2)))
  ([host port handler]
   (client host port handler)
   (let [map (gen/generate (s/gen :mqtt/connect))
         _ (println map)
         bufs (MqttConnect/encode map)]
     (.sendMessage ^MqttClient @client-atom bufs))))

(defn publish
  ([topic] (let [map (gen/generate (s/gen :mqtt/publish))
                 map (assoc map :topic topic)
                 _ (println map)
                 bufs (MqttPublish/encode map)]
             (.sendMessage ^MqttClient @client-atom bufs)
             (:payload map)))
  ([topic msg qos]
   (let [bufs (MqttPublish/encode {:packet-type :PUBLISH :qos qos :topic topic :payload msg})]
     (.sendMessage ^MqttClient @client-atom bufs))))

(defn subscribe []
  (let [map (gen/generate (s/gen :mqtt/subscribe))
        _ (println map)
        bufs (MqttSubscribe/encode map)]
     (.sendMessage ^MqttClient @client-atom bufs)
    map))


(defn pingreq []
  (let [map (gen/generate (s/gen :mqtt/pinreq))
        bufs (MqttPingReq/encode map)]
    (.sendMessage ^MqttClient @client-atom bufs)))

(defn disconnect []
  (let [map (gen/generate (s/gen :mqtt/disconnect))
        bufs (MqttDisconnect/encode map)]
    (.sendMessage ^MqttClient @client-atom bufs)
    (reset! client-atom nil)))

(defn close []
  (.close ^MqttClient @client-atom))
