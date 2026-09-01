(ns mqttkat.client
  (:require [clojure.tools.logging :as log]
            [clojure.spec.gen.alpha :as gen]
            [clojure.spec.alpha :as s]
            [mqttkat.spec]
            [clojure.core.async :as async])
  (:import [org.mqttkat.client MqttClient]
           [org.mqttkat MqttHandler]
           [org.mqttkat.packages MqttConnect MqttPingReq MqttPublish
            MqttDisconnect MqttSubscribe MqttPubRel MqttPubAck MqttPubRec
            MqttPubComp MqttUnsubscribe]))

(set! *warn-on-reflection* true)

(defn handler-fn [msg _]
  (log/debug "clj handler:" msg))

(defn client
  ([] (client "localhost" 1883))
  ([host port] (client host port (MqttHandler. ^clojure.lang.IFn handler-fn 2)))
  ([host port handler]
   client (MqttClient. ^String host ^int port 2 handler ^Object (async/chan 1))))

(defn connect
  "Connect `client` with a generated CONNECT.

   The protocol is pinned to 3.1.1 rather than generated: :mqtt/connect is an
   s/or over MQTT 3.1 (\"MQIsdp\", version 3) and 3.1.1 (\"MQTT\", version 4),
   and the broker answers an unsupported protocol name by dropping the
   connection with no CONNACK at all. Generating it meant every simulation run
   was a coin flip on whether its very first packet was answered."
  ([client] (let [map (gen/generate (s/gen :mqtt/connect))
                  _ (log/debug "S" map client)
                  buf (MqttConnect/encode (assoc map
                                                 :keep-alive 60
                                                 :protocol-name "MQTT"
                                                 :protocol-version 4))]
              (.sendMessage ^MqttClient client buf))))

;  ([host port] (client host port (MqttHandler. ^clojure.lang.IFn handler-fn 2)));
;  ([host port handler]
;   (connect (client host port handler))
;   (let [map (gen/generate (s/gen :mqtt/connect))
;         _ (log/debug "S" map)
;         bufs (MqttConnect/encode map)
;     (.sendMessage ^MqttClient @client-atom bufs)))

(defn publish
  ([client topic]
   (let [map (gen/generate (s/gen :mqtt/publish-qos-gt0))
         map (assoc map :topic topic)
         _ (log/debug "S" map client)
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
        _ (log/debug "S" map client)
        buf (MqttSubscribe/encode map)]
    (.sendMessage ^MqttClient client buf)
    map))

(defn pingreq [client]
  (let [map (gen/generate (s/gen :mqtt/pingreq))
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
        _ (log/debug "S" map)
        buf (MqttPubAck/encode map)]
    (.sendMessage ^MqttClient client buf)))

(defn pubrec [client id]
  (let [map {:packet-type :PUBREC :packet-identifier id}
        _ (log/debug "S" map)
        buf (MqttPubRec/encode map)]
    (.sendMessage ^MqttClient client buf)))

(defn pubcomp [client id]
  (let [map {:packet-type :PUBCOMP :packet-identifier id}
        _ (log/debug "S" map)
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