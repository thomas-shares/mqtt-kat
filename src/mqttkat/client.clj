(ns mqttkat.client
  (:import [org.mqttkat.client MqttClient]
           [org.mqttkat MqttHandler]
           [org.mqttkat.packages MqttConnect MqttPingReq MqttPublish MqttDisconnect]))


(set! *warn-on-reflection* true)

(def client-atom (atom nil))

(defn handler-fn [msg]
  (println msg))

(defn client [host port]
  (let [client (MqttClient. ^String host ^int port 2 ( MqttHandler. ^clojure.lang.IFn handler-fn 16))]
    (reset! client-atom client)))

(defn connect
  ([] (connect "localhost" 1883))
  ([host port]
   (client host port)
   (let [bufs (MqttConnect/encode {:packet-type :CONNECT
                                   :client-id "test"
                                   :protocol-name "MQTT"
                                   :protocol-version (byte 4)
                                   :keep-alive 16532
                                   :clean-session true
                                   :user-credentials {:username "user-name"
                                                      :password "secret"}})]
     (.sendMessage ^MqttClient @client-atom bufs))))

(defn publish
  ([] (publish "test" "test-message" 0))
  ([topic msg qos]
   (let [bufs (MqttPublish/encode {:packet-type :PUBLISH :qos qos :topic topic :payload msg})]
     (.sendMessage ^MqttClient @client-atom bufs))))


(defn pingreq []
  (let [bufs (MqttPingReq/encode {:packet-type :PINGREQ})]
    (.sendMessage ^MqttClient @client-atom bufs)))

(defn disconnect []
  (let [bufs (MqttDisconnect/encode {:packet-type :DISCONNECT})]
    (.sendMessage ^MqttClient @client-atom bufs)))
