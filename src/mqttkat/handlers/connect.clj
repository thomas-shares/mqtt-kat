(ns mqttkat.handlers.connect
  (:require [mqttkat.handlers :refer [logger *clients* *retained* *outbound* send-buffer add-client! add-timer!]]
            [mqttkat.handlers.disconnect :refer :all])
  (:import [org.mqttkat.packages MqttConnAck MqttPublish]))

#_(defn add-client [msg]
    (let [client-id (:client-id msg)
          _ (logger client-id)
          x (some #(and (= (:client-id (second %)) client-id) %) @*clients*)
          _ (logger (count @*clients*))]
      x))

(defn client-contains? [client-key]
  (contains? @*clients* client-key))

(defn protocol-version-not-valid? [version]
  (not= version 4))

(defn protocol-name-not-valid? [name]
  (not= name "MQTT"))

(defn handle-not-valid-protocol-version
  [{:keys [client-key]}]
  (send-buffer [client-key] (MqttConnAck/encode {:packet-type :CONNACK
                                                 :session-present? false
                                                 :connect-return-code 0x01}))
  (Thread/sleep 25)
  (disconnect-client client-key))

(defn handle-success
  [{:keys [client-key keep-alive client-id] :as msg}]
  ;;(logger "SUCCESS here now...." (contains? @*clients* client-id))
  (when (and (contains? msg :will) (true? (get-in msg [:will :will-retain])))
    (let [topic (get-in msg [:will :will-topic])
          payload (get-in msg [:will :will-message])
          qos (get-in msg [:will :will-qos])]
      #_(logger "there is a RETAINED will!" (str (:will msg)))
      #_(logger "storing retain: " topic qos (empty? payload))
      (if (empty? payload)
        (swap! *retained* dissoc topic)
        (swap! *retained* assoc topic {:qos qos :payload payload}))))
  
  (when (pos? keep-alive)
    (add-timer! client-key keep-alive))
  (send-buffer [client-key] (MqttConnAck/encode {:packet-type :CONNACK
                                                 :session-present? (contains? @*clients* client-id)
                                                 :connect-return-code 0x00}))
  (add-client! msg))

(defn no-client-id-and-no-clean-session [client-id clean-session?]
  (and (empty? client-id) (not clean-session?)))

(defn handle-incorrect-clean-session [{:keys [client-key]}]
  (send-buffer [client-key] (MqttConnAck/encode {:packet-type :CONNACK
                                                 :session-present? false
                                                 :connect-return-code 0x02}))
  (Thread/sleep 25)
  (disconnect-client client-key))

(defn connect [{:keys [protocol-name protocol-version client-key client-id clean-session?] :as msg}]
  (logger "CONNECT: " (dissoc msg :client-key))
  (cond
    (protocol-name-not-valid? protocol-name) (disconnect-client client-key)
    (protocol-version-not-valid? protocol-version) (handle-not-valid-protocol-version msg)
    (client-contains? client-key) (disconnect-client client-key)
    (no-client-id-and-no-clean-session client-id clean-session?) (handle-incorrect-clean-session msg)
    :else (handle-success msg))
  ;;(logger "Checking for message that are being proccessed: " (contains? @*outbound* client-id))
  (when (contains? @*outbound* client-id)
    #_(logger "Remaining message found for client: " (get @*outbound* client-id))
    (doseq [stalled-id (keys (get @*outbound* client-id))]
      #_(logger "Sending message to client: " stalled-id)
      #_(logger (get-in @*outbound* [client-id stalled-id]))
      (let [{:keys [topic payload qos] } (get-in @*outbound* [client-id stalled-id]) ]
         (send-buffer [client-key]
                   (MqttPublish/encode {:packet-type       :PUBLISH
                                        :payload           payload
                                        :topic             topic
                                        :qos               qos
                                        :retain?           false
                                        :duplicate?        true
                                        :packet-identifier stalled-id}))))))
