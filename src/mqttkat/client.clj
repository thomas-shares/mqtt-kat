(ns mqttkat.client
  (:require [clojure.spec.gen.alpha :as gen]
            [clojure.spec.alpha :as s]
            [mqttkat.spec]
            [clojure.core.async :as async])
  (:import [org.mqttkat.client MqttClient]
           [org.mqttkat MqttHandler]
           [org.mqttkat.packages MqttConnect MqttPingReq MqttPublish
            MqttDisconnect MqttSubscribe MqttPubRel MqttPubAck MqttPubRec
            MqttPubComp MqttUnsubscribe]))

(set! *warn-on-reflection* true)

(def o (Object.))

(defn logger [msg & args]
  (when true
    (locking 0
      (println msg args))))

(defn handler-fn [msg _]
  (println "clj handler: " msg))

(defn client
  ([] (client "localhost" 1883))
  ([host port] (client host port (MqttHandler. ^clojure.lang.IFn handler-fn 2)))
  ([host port handler]
   client (MqttClient. ^String host ^int port 2 handler ^Object (async/chan 1))))

(defn connect
  ([client] (let [map (gen/generate (s/gen :mqtt/connect))
                  _ (logger "S " map " " client)
                  buf (MqttConnect/encode (assoc map :keep-alive 60))]
                  ;ch (async/chan 1)]
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
         _ (logger "S " map " " client)
         buf (MqttPublish/encode map)]
     (.sendMessage ^MqttClient client buf)
     (select-keys map [:qos :payload :packet-identifier])))
  ([topic msg qos]
   (let [bufs (MqttPublish/encode {:packet-type :PUBLISH :qos qos :topic topic :payload msg :retain? false :duplicate? false})]
     (.sendMessage ^MqttClient client bufs))))

(defn subscribe [client]
  (let [map (gen/generate (s/gen :mqtt/subscribe))
        filtered (filterv #(boolean (re-find #"\w+" (:topic-filter %))) (:topics map))
        map (assoc map :topics filtered)
        _ (logger "S " map " " client)
        buf (MqttSubscribe/encode map)]
    (.sendMessage ^MqttClient client buf)
    map))

(defn pingreq [client]
  (let [map (gen/generate (s/gen :mqtt/pinreq))
        bufs (MqttPingReq/encode map)]
    (.sendMessage ^MqttClient client bufs)))

(defn disconnect [client]
  (->> (MqttDisconnect/encode)
       (.sendMessage ^MqttClient client)))

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

(defn send-message [client msg]
  (let [buffer (case (:packet-type msg)
                 :CONNECT (MqttConnect/encode msg)
                 :PUBLISH (MqttPublish/encode msg)
                 :SUBSCRIBE (MqttSubscribe/encode msg)
                 :DISCONNECT (MqttDisconnect/encode)
                 :PUBACK (MqttPubAck/encode msg)
                 :UNSUBSCRIBE (MqttUnsubscribe/encode msg))]
    (.sendMessage ^MqttClient client buffer)))

(defn connected? [client]
  (.isConnected ^MqttClient client))