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

(defn client-contains? [client-key]
  (contains? @*clients* client-key))

(defn protocol-version-not-valid? [version]
  (not= version 4))

(defn protocol-name-not-valid? [name]
  (not= name "MQTT"))

(defn handle-not-valid-protocol-version
  [{:keys [client-key] :as msg}]
  (do (send-buffer [client-key] (MqttConnAck/encode {:packet-type :CONNACK
                                                     :session-present? false
                                                     :connect-return-code 0x01}))
      (disconnect msg)))

(defn handle-not-valid-protocol-name
  [client-key]
  (do (logger "DISCONNECTING!!!")
      (disconnect-client client-key)))

(defn handle-success
  [{:keys [client-key keep-alive] :as msg}]
  (do
    (swap! *clients* assoc client-key (dissoc msg :packet-type))
    (when (pos? keep-alive) (add-timer! client-key keep-alive))
    (send-buffer [client-key] (MqttConnAck/encode {:packet-type :CONNACK
                                                   :session-present? false
                                                   :connect-return-code 0x00}))))

(defn connect [{:keys [protocol-name protocol-version client-key] :as msg}]
  (logger "clj CONNECT: " protocol-name protocol-version client-key msg)
  (cond
    (client-contains? client-key)
    (disconnect-client client-key)
    (protocol-version-not-valid? protocol-version)
    (handle-not-valid-protocol-version msg)
    (protocol-name-not-valid? protocol-name)
    (handle-not-valid-protocol-name client-key)
    :else (handle-success msg)))
