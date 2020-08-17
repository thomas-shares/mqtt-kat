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
  (cond
    (contains? @*clients* client-key)
    (disconnect-client client-key)
    (not= protocol-version 4)
    (do (send-buffer [client-key]
                    (MqttConnAck/encode {:packet-type :CONNACK
                                         :session-present? false?
                                         :connect-return-code 0x01}))
      (disconnect client-key))
    (not= protocol-name "MQTT")
    (do (logger "DISCONNECTING!!!")
      (disconnect-client client-key))
    :else (do (swap! *clients* assoc client-key (dissoc msg :packet-type))
              (when-not (zero? keep-alive)
                (add-timer! client-key keep-alive))
            (send-buffer [client-key] (MqttConnAck/encode {:packet-type :CONNACK
                                                           :session-present? false
                                                           :connect-return-code 0x00})))))



