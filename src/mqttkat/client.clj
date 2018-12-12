(ns mqttkat.client
  (:import [org.mqttkat.client MqttClient]))


(set! *warn-on-reflection* true)

(def client-atom (atom nil))

(defn- client [host port]
  (let [client (MqttClient. ^String host ^int port 2)]
    (reset! client-atom client)))

(defn connect
  ([] (connect "localhost" 1883))
  ([host port]
   (client host port)
   (^MqttClient .sendMessage @client-atom {:packet-type :CONNECT
                                           :client-id "test"
                                           :protocol-name "MQTT"
                                           :protocol-version (int 4)
                                           :keep-alive 600})))

(defn publish
  ([] (publish "test" "test-message" 0))
  ([topic msg qos]
   (.sendMessage @client-atom {:packet-type :PUBLISH :qos qos :topic topic :payload msg})))


(defn pingreq []
  (.sendMessage @client-atom {:packet-type :PINGREQ}))
