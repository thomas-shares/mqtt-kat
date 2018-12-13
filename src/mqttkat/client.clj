(ns mqttkat.client
  (:import [org.mqttkat.client MqttClient]
           [org.mqttkat.packages MqttConnect MqttPingReq]))


(set! *warn-on-reflection* true)

(def client-atom (atom nil))

(defn- client [host port]
  (let [client (MqttClient. ^String host ^int port 2)]
    (reset! client-atom client)))

(defn connect
  ([] (connect "localhost" 1883))
  ([host port]
   (client host port)
   (let [bufs (MqttConnect/encode {:packet-type :CONNECT
                                   :client-id "test"
                                   :protocol-name "MQTT"
                                   :protocol-version (int 4)
                                   :keep-alive 600})]
     (.sendMessage @client-atom bufs))))

(defn publish
  ([] (publish "test" "test-message" 0))
  ([topic msg qos]
   (.sendMessage @client-atom {:packet-type :PUBLISH :qos qos :topic topic :payload msg})))


(defn pingreq []
  (let [bufs (MqttPingReq/encode {:packet-type :PINGREQ})]
    (.sendMessage @client-atom bufs)))
