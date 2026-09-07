(ns mqttkat.handlers.connect
  (:require [clojure.tools.logging :as log]
            [mqttkat.handlers :refer [*clients* *retained* *outbound* send-buffer add-client!
                                      add-timer! flush-pending!]]
            [mqttkat.handlers.disconnect :refer :all])
  (:import [org.mqttkat MqttReasonCode]
           [org.mqttkat.packages MqttConnAck MqttPublish]))

#_(defn add-client [msg]
    (let [client-id (:client-id msg)
          _ (log/debug client-id)
          x (some #(and (= (:client-id (second %)) client-id) %) @*clients*)
          _ (log/debug (count @*clients*))]
      x))

(defn client-contains? [client-key]
  (contains? @*clients* client-key))

(def supported-protocol-versions
  "4 is MQTT 3.1.1 and 5 is MQTT 5.0. Version 3 (MQTT 3.1, protocol name
   \"MQIsdp\") is refused — it is refused by protocol-name-not-valid? too, so
   this is belt and braces."
  #{4 5})

(def broker-properties
  "What this broker tells a version 5 client about itself (§3.2.2.3).

   Only what is true. A client that is told nothing assumes the defaults —
   QoS 2, retain, wildcards, shared subscriptions and subscription identifiers
   all available — and then discovers otherwise when something it was promised
   quietly does not work. Shared subscriptions and subscription identifiers are
   not implemented yet, and a topic alias maximum of 0 says this broker accepts
   no aliases, so these three say so up front.

   Take an entry out of here when the feature behind it lands, not before —
   topic-alias-maximum was 0 until aliases worked."
  {:retain-available                  true
   :maximum-qos                       2
   :wildcard-subscription-available   true
   :shared-subscription-available     true
   :subscription-identifier-available true
   ;; §3.2.2.3.3. A client that is not told assumes 65,535 and may flood; this
   ;; is the window the broker has always held per client, now stated.
   :receive-maximum                   mqttkat.handlers/inflight-window
   ;; The highest alias a client may use, so it may use 1..10. Bounded because
   ;; each one is remembered against the connection for as long as it lasts.
   :topic-alias-maximum               10})

(defn protocol-version-not-valid? [version]
  (not (contains? supported-protocol-versions (long version))))

(defn version-5?
  "Whether to answer this client in the MQTT 5 dialect.

   Anything at or above 5 gets a version 5 answer, including a version this
   broker does not know: a client asking for 6 cannot be assumed to understand
   3.1.1's return codes, and 5 is the most recent thing worth guessing at."
  [version]
  (>= (long (or version 0)) 5))

(defn protocol-name-not-valid? [name]
  (not= name "MQTT"))

(defn handle-not-valid-protocol-version
  [{:keys [client-key protocol-version]}]
  ;; The refusal has to be in a dialect the client can read. A 3.1.1 client
  ;; would make nothing of a reason code and a property block, and a version 5
  ;; one expects both — 0x84 rather than 0x01 for the same complaint.
  (send-buffer [client-key]
               (MqttConnAck/encode
                (if (version-5? protocol-version)
                  {:packet-type :CONNACK
                   :protocol-version 5
                   :session-present? false
                   :reason-code MqttReasonCode/UNSUPPORTED_PROTOCOL_VERSION}
                  {:packet-type :CONNACK
                   :session-present? false
                   :connect-return-code 0x01})))
  (Thread/sleep 25)
  (disconnect-client client-key))

(defn connack-for
  "The CONNACK that answers a successful CONNECT, in the client's own dialect.

   §3.2.2.2: Session Present is 0 whenever CleanSession is 1, whatever the
   server happens to have stored — the session is about to be discarded, so
   saying it is present would be a lie the client acts on."
  [{:keys [protocol-version client-id clean-session?]}]
  (let [present? (and (false? clean-session?) (contains? @*clients* client-id))]
    (if (version-5? protocol-version)
      {:packet-type      :CONNACK
       :protocol-version 5
       :session-present? present?
       :reason-code      MqttReasonCode/SUCCESS
       :properties       broker-properties}
      {:packet-type         :CONNACK
       :session-present?    present?
       :connect-return-code 0x00})))

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
  
  ;; Session Present used to report whatever was parked under the client-id
  ;; regardless of clean-session, so a client asking for a fresh session was
  ;; told it had resumed one. See connack-for.
  (send-buffer [client-key] (MqttConnAck/encode (connack-for msg)))
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
