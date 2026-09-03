(ns mqttkat.handlers.connect
  (:require [clojure.tools.logging :as log]
            [mqttkat.handlers :refer [*clients* *retained* *outbound* send-buffer add-client!
                                      add-timer! flush-pending!]]
            [mqttkat.handlers.disconnect :refer :all])
  (:import [org.mqttkat.packages MqttConnAck MqttPublish]))

#_(defn add-client [msg]
    (let [client-id (:client-id msg)
          _ (log/debug client-id)
          x (some #(and (= (:client-id (second %)) client-id) %) @*clients*)
          _ (log/debug (count @*clients*))]
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
  [{:keys [client-key keep-alive client-id clean-session?] :as msg}]
  (log/trace "SUCCESS here now...." (contains? @*clients* client-id))
  (when (and (contains? msg :will) (true? (get-in msg [:will :will-retain])))
    (let [topic (get-in msg [:will :will-topic])
          payload (get-in msg [:will :will-message])
          qos (get-in msg [:will :will-qos])]
      (log/trace "there is a RETAINED will!" (str (:will msg)))
      (log/trace "storing retain:" topic qos (empty? payload))
      (if (empty? payload)
        (swap! *retained* dissoc topic)
        (swap! *retained* assoc topic {:qos qos :payload payload}))))
  
  ;; §3.2.2.2: Session Present is 0 whenever CleanSession is 1, whatever the
  ;; server happens to have stored — the session is about to be discarded, so
  ;; saying it is present would be a lie the client acts on. This reported
  ;; whatever was parked under the client-id regardless of clean-session, so a
  ;; client asking for a fresh session was told it had resumed one.
  (send-buffer [client-key] (MqttConnAck/encode {:packet-type :CONNACK
                                                 :session-present? (and (false? clean-session?)
                                                                        (contains? @*clients* client-id))
                                                 :connect-return-code 0x00}))
  (add-client! msg)
  ;; After add-client!, never before: it replaces this key's whole entry, which
  ;; would throw away the :timer and :last-active that add-timer! writes.
  (when (pos? keep-alive)
    (add-timer! client-key keep-alive)))

(defn no-client-id-and-no-clean-session [client-id clean-session?]
  (and (empty? client-id) (not clean-session?)))

(defn handle-incorrect-clean-session [{:keys [client-key]}]
  (send-buffer [client-key] (MqttConnAck/encode {:packet-type :CONNACK
                                                 :session-present? false
                                                 :connect-return-code 0x02}))
  (Thread/sleep 25)
  (disconnect-client client-key))

(defn connect [{:keys [protocol-name protocol-version client-key client-id clean-session?] :as msg}]
  (log/debug "CONNECT:" (dissoc msg :client-key))
  (cond
    (protocol-name-not-valid? protocol-name) (disconnect-client client-key)
    (protocol-version-not-valid? protocol-version) (handle-not-valid-protocol-version msg)
    (client-contains? client-key) (disconnect-client client-key)
    (no-client-id-and-no-clean-session client-id clean-session?) (handle-incorrect-clean-session msg)
    :else (handle-success msg))
  ;; Anything this client left unacknowledged is still recorded against its
  ;; client-id, under the same identifiers it was sent with, so redelivery
  ;; reuses them rather than reserving new ones.
  (let [stalled (get-in @*outbound* [client-id :inflight])]
    (log/trace "Checking for messages that are being processed:" (count stalled))
    (doseq [[stalled-id {:keys [topic payload qos]}] stalled]
      (log/trace "Redelivering to client:" client-id "identifier:" stalled-id)
      (send-buffer [client-key]
                   (MqttPublish/encode {:packet-type       :PUBLISH
                                        :payload           payload
                                        :topic             topic
                                        :qos               qos
                                        :retain?           false
                                        :duplicate?        true
                                        :packet-identifier stalled-id}))))
  ;; Then whatever arrived while this session was away — after the
  ;; redeliveries above, which were already on their way before it left.
  (flush-pending! client-key client-id))
