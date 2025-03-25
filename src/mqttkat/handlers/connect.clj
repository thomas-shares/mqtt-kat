(ns mqttkat.handlers.connect
  (:require [mqttkat.handlers :refer [logger *clients* send-buffer add-client! add-timer!]]
            [mqttkat.handlers.disconnect :refer :all])
  (:import [org.mqttkat.packages MqttConnAck]))

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
  [{:keys [client-key] :as msg}]
  (do (send-buffer [client-key] (MqttConnAck/encode {:packet-type :CONNACK
                                                     :session-present? false
                                                     :connect-return-code 0x01}))
      (disconnect-client client-key)))

#_(defn handle-not-valid-protocol-name
    [client-key]
    (do (logger "DISCONNECTING!!!")
        (disconnect-client client-key)))

(defn handle-success
  [{:keys [client-key keep-alive] :as msg}]
  ;;(logger "success here now....")
  (do
    (add-client! msg)
    (when (pos? keep-alive)
      (add-timer! client-key keep-alive))
    (send-buffer [client-key] (MqttConnAck/encode {:packet-type :CONNACK
                                                   :session-present? false
                                                   :connect-return-code 0x00}))))

(defn no-client-id-and-no-clean-session [client-id clean-session?]
  ;;(logger client-id clean-session?)
  (and (empty? client-id) (not clean-session?)))

(defn handle-incorrect-clean-session
  [{:keys [client-key] :as msg}]
  (do (send-buffer [client-key] (MqttConnAck/encode {:packet-type :CONNACK
                                                     :session-present? false
                                                     :connect-return-code 0x02}))
      (disconnect-client client-key)))

(defn connect [{:keys [protocol-name protocol-version client-key client-id clean-session?] :as msg}]
  (logger "clj CONNECT: " msg)
  (cond
    (protocol-name-not-valid? protocol-name) (disconnect-client client-key)
    (protocol-version-not-valid? protocol-version) (handle-not-valid-protocol-version msg)
    (client-contains? client-key) (disconnect-client client-key)
    (no-client-id-and-no-clean-session client-id clean-session?) (handle-incorrect-clean-session msg)
    :else (handle-success msg)))
