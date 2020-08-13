(ns mqttkat.handlers.connect
  (:require [mqttkat.handlers :refer :all]
            [mqttkat.handlers.disconnect :refer :all])
  (:import [org.mqttkat.packages MqttConnAck]))


#_(defn add-client [msg]
    (let [client-id (:client-id msg)
          _ (logger client-id)
          x (some #(and (= (:client-id (second %)) client-id ) %) @*clients*)
          _ (logger (count @*clients*))]
      x))

(defn connect [{:keys [protocol-name protocol-version client-key keep-alive] :as msg}]
  (logger "clj CONNECT: " protocol-name protocol-version client-key msg)
  (when (contains? @*clients* client-key)
    (disconnect-client client-key))
  (when-not (= protocol-version 4)
    (send-buffer [client-key]
                 (MqttConnAck/encode {:packet-type :CONNACK
                                      :session-present? false?
                                      :connect-return-code 0x01}))
    (disconnect client-key))
  (when-not (= protocol-name "MQTT")
    (logger "DISCONNECTING!!!")
    (disconnect-client client-key))
  (swap! *clients* assoc client-key (dissoc msg :packet-type))
  (when-not (zero? keep-alive)
    (add-timer client-key keep-alive))
  (send-buffer [client-key]
               (MqttConnAck/encode {:packet-type :CONNACK
                                    :session-present? false
                                    :connect-return-code 0x00})))