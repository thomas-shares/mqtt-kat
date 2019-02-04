(ns mqttkat.client
  (:require [clojure.spec.gen.alpha :as gen]
            [clojure.spec.alpha :as s]
            [mqttkat.spec :refer :all])
  (:import [org.mqttkat.client MqttClient]
           [org.mqttkat MqttHandler]
           [org.mqttkat.packages MqttConnect MqttPingReq MqttPublish
             MqttDisconnect MqttSubscribe MqttPubRel MqttPubAck MqttPubRec
             MqttPubComp]))

(set! *warn-on-reflection* true)

(def client-atom (atom nil))

(defn logger [msg & args]
  (when true
    (println msg args)))

(defn handler-fn [msg]
  (println "clj handler: " msg))

(defn client
  ([host port] (client host port (MqttHandler. ^clojure.lang.IFn handler-fn 2)))
  ([host port handler]
   (when (nil? @client-atom)
      (let [client (MqttClient. ^String host ^int port 2 handler)]
        (reset! client-atom client)
        client))))

(defn client2 [host port]
  (let [client (MqttClient. ^String host ^int port 2 ( MqttHandler. ^clojure.lang.IFn handler-fn 2))]
    (reset! client-atom client)))

(defn connect
  ([client] (let [map (gen/generate (s/gen :mqtt/connect))
                  _ (logger "S " map)
                  buf (MqttConnect/encode map)]
                (.sendMessage ^MqttClient client buf))))

;  ([host port] (client host port (MqttHandler. ^clojure.lang.IFn handler-fn 2)));
;  ([host port handler]
;   (connect (client host port handler))
;   (let [map (gen/generate (s/gen :mqtt/connect))
;         _ (logger "S " map)
;         bufs (MqttConnect/encode map)
;     (.sendMessage ^MqttClient @client-atom bufs)))

(defn publish
  ([client topic]
   (let [map (gen/generate (s/gen :mqtt/publish-qos-gt0))
         map (assoc map :topic topic)
         _ (logger "S " map)
         buf (MqttPublish/encode map)]
      (.sendMessage ^MqttClient client buf)
      (select-keys map [:qos :payload :packet-identifier])))
  ([topic msg qos]
   (let [bufs (MqttPublish/encode {:packet-type :PUBLISH :qos qos :topic topic :payload msg :retain? false :duplicate? false})]
     (.sendMessage ^MqttClient @client-atom bufs))))

(defn subscribe [client]
  (let [map (gen/generate (s/gen :mqtt/subscribe))
        _ (logger "S " map)
        buf (MqttSubscribe/encode map)]
     (.sendMessage ^MqttClient client buf)
    map))


(defn pingreq [client]
  (let [map (gen/generate (s/gen :mqtt/pinreq))
        bufs (MqttPingReq/encode map)]
    (.sendMessage ^MqttClient client bufs)))

(defn disconnect [client]
  (let [map (gen/generate (s/gen :mqtt/disconnect))
        bufs (MqttDisconnect/encode map)]
    (.sendMessage ^MqttClient client bufs)
    (reset! client-atom nil)))

(defn close [client]
  (.close ^MqttClient client))

(defn pubrel [client id]
  (let [bufs (MqttPubRel/encode {:packet-type :PUBREL :packet-identifier id})]
    (.sendMessage ^MqttClient client bufs)))

(defn puback [client id]
  (let [map {:packet-type :PUBACK :packet-identifier id}
        _ (logger "S " map)
        buf (MqttPubAck/encode map)]
    (.sendMessage ^MqttClient client buf)))

(defn pubrec [client id]
  (let [map {:packet-type :PUBREC :packet-identifier id}
        _ (logger "S " map)
        buf (MqttPubRec/encode map)]
    (.sendMessage ^MqttClient client buf)))

(defn pubcomp [client id]
  (let [map {:packet-type :PUBCOMP :packet-identifier id}
        _ (logger "S " map)
        buf (MqttPubComp/encode map)]
    (.sendMessage ^MqttClient client buf)))
