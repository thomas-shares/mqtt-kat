(ns mqttkat.handlers.connect
  (:require [clojure.tools.logging :as log]
            [mqttkat.handlers :as handlers]
            [mqttkat.handlers :refer [*clients* send-buffer add-client!
                                      add-timer! flush-pending!]]
            [mqttkat.bridge :as bridge]
            [mqttkat.handlers.disconnect :refer :all]
            [mqttkat.retained :as retained])
  (:import [org.mqttkat MqttReasonCode]
           [org.mqttkat.packages MqttConnAck MqttDisconnect MqttPublish]))

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
  ;; No :maximum-qos. §3.2.2.3.4 allows the property the values 0 and 1
  ;; only — it exists to say a broker does *less* than QoS 2 — and calls any
  ;; other value a Protocol Error; absent means 2. This broker sent
  ;; :maximum-qos 2 for every version 5 CONNACK, and mosquitto's client, which
  ;; checks, answered every one with "a network protocol error occurred".
  ;; The Paho suite did not check.
  {:retain-available                  true
   :wildcard-subscription-available   true
   :shared-subscription-available     true
   :subscription-identifier-available true
   ;; §3.2.2.3.3. A client that is not told assumes 65,535 and may flood; this
   ;; is the window the broker has always held per client, now stated.
   :receive-maximum                   mqttkat.handlers/inflight-window
   ;; §3.2.2.3.8. The highest alias a client may use, so it may use 1..N.
   ;; Named rather than repeated: this number is what makes the limit binding,
   ;; and the code that rejects an alias above it has to be quoting the same
   ;; one the client was promised.
   :topic-alias-maximum               mqttkat.handlers/broker-topic-alias-maximum})

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

(defn handle-redirect
  "Send a version 5 client elsewhere (§4.13), one of two ways.

   :disconnect — accept it, CONNACK Success with Session Present as the
   cluster knows it, and at once DISCONNECT Use another server with the
   Server Reference. The connection is closed before the client can do
   anything on it, and nothing here has been touched: no session, no
   record. What most client libraries follow.

   :connack — CONNACK Use another server with the Server Reference, then
   the close §3.2.2.2 requires after a CONNACK that is not Success.

   0x9C rather than 0x9D Server moved either way: the client is asked to
   use the other broker for now, not told this one is gone."
  [{:keys [client-key client-id clean-session?]}
   {:keys [server-reference via session-present?]}]
  (log/info "redirecting" client-id "to" server-reference "via" (name via))
  (case via
    :connack
    (send-buffer [client-key]
                 (MqttConnAck/encode {:packet-type      :CONNACK
                                      :protocol-version 5
                                      :session-present? false
                                      :reason-code      MqttReasonCode/USE_ANOTHER_SERVER
                                      :properties       {:server-reference server-reference}}))
    (do
      ;; §3.2.2.1.1: 0 whenever Clean Start is 1, whatever is kept.
      (send-buffer [client-key]
                   (MqttConnAck/encode {:packet-type      :CONNACK
                                        :protocol-version 5
                                        :session-present? (boolean (and (false? clean-session?)
                                                                        session-present?))
                                        :reason-code      MqttReasonCode/SUCCESS
                                        :properties       broker-properties}))
      (send-buffer [client-key]
                   (MqttDisconnect/encode {:packet-type      :DISCONNECT
                                           :protocol-version 5
                                           :reason-code      MqttReasonCode/USE_ANOTHER_SERVER
                                           :properties       {:server-reference server-reference}}))))
  (handlers/pause-before-close!)
  (disconnect-client client-key))

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
  ;; The close waits for the writer to send that CONNACK; this leaves the
  ;; client a moment to read it.
  (handlers/pause-before-close!)
  (disconnect-client client-key))

(def server-keep-alive
  "The longest Keep Alive the broker will agree to, in seconds (§3.2.2.3.5).

   A client asking for more is told this number instead and must use it. The
   point is the broker's own housekeeping: Keep Alive is what lets it notice a
   connection whose peer has gone without a FIN, and a client naming two hours
   would keep a dead entry — its session, its subscriptions, its queued
   messages — for two hours.

   A client asking for less keeps its own, and is told nothing: §3.2.2.3.5 has
   it use what it sent when the property is absent, so repeating the number
   back would only be noise."
  60)

(defn effective-keep-alive
  "What both ends will actually use."
  [asked]
  (let [asked (long (or asked 0))]
    (if (and (pos? asked) (> asked (long server-keep-alive)))
      (long server-keep-alive)
      asked)))

(defn connack-for
  "The CONNACK that answers a successful CONNECT, in the client's own dialect.

   §3.2.2.2: Session Present is 0 whenever CleanSession is 1, whatever the
   server happens to have stored — the session is about to be discarded, so
   saying it is present would be a lie the client acts on."
  [{:keys [protocol-version client-id clean-session? assigned-client-id? keep-alive]}]
  (let [present? (and (false? clean-session?) (contains? @*clients* client-id))]
    (if (version-5? protocol-version)
      {:packet-type      :CONNACK
       :protocol-version 5
       :session-present? present?
       :reason-code      MqttReasonCode/SUCCESS
       ;; §3.2.2.3.7: sent only when the server chose the name, which is the
       ;; only case where the client does not already know it.
       :properties       (cond-> broker-properties
                           ;; Another broker's bridge: the window a link
                           ;; between brokers needs, not a client's.
                           (bridge/bridge? client-id)
                           (assoc :receive-maximum bridge/receive-maximum)
                           assigned-client-id?
                           (assoc :assigned-client-identifier client-id)
                           (not= (long (or keep-alive 0))
                                 (effective-keep-alive keep-alive))
                           (assoc :server-keep-alive (effective-keep-alive keep-alive)))}
      {:packet-type         :CONNACK
       :session-present?    present?
       :connect-return-code 0x00})))

(defn- connection-id
  "A name for one connection, unique across restarts too. Random rather than
   counted for that reason: two runs of the broker must not both call a
   connection \"1\", because the record of the first outlives it."
  []
  (str (java.util.UUID/randomUUID)))

(defn handle-success
  [{:keys [client-key keep-alive client-id clean-session?] :as msg}]
  (log/trace "SUCCESS here now...." (contains? @*clients* client-id))
  (when (and (contains? msg :will) (true? (get-in msg [:will :will-retain])))
    (let [topic   (get-in msg [:will :will-topic])
          payload (get-in msg [:will :will-message])
          qos     (get-in msg [:will :will-qos])
          ;; Same whitelist as a forwarded publish, so a retained will keeps
          ;; its content type and user properties and loses the Will Delay
          ;; Interval, which is an instruction to the broker rather than part
          ;; of the message.
          props   (handlers/forwardable-properties (get-in msg [:will :properties]))]
      (log/trace "there is a RETAINED will!" (str (:will msg)))
      (log/trace "storing retain:" topic qos (empty? payload))
      (if (empty? payload)
        (retained/clear! topic)
        ;; Stamped like any other retained message, so a Will Message published
        ;; with a Message Expiry Interval expires on the same terms.
        (retained/retain! topic {:qos       qos
                                 :payload   payload
                                 :properties props
                                 :stored-at (System/currentTimeMillis)}))))
  
  ;; The session may be live on another broker, which then has to let it
  ;; go (§3.1.4); and a persistent one may be parked there, or on the
  ;; cluster after the broker that parked it went away. Asked before
  ;; Session Present is decided, and parked here from then on.
  (handlers/adopt-session! client-id)
  ;; Session Present used to report whatever was parked under the client-id
  ;; regardless of clean-session, so a client asking for a fresh session was
  ;; told it had resumed one. See connack-for. Decided here, before
  ;; add-client! takes the parked entry it is decided on.
  ;;
  ;; The negotiated keep-alive, not the one asked for: having told the client
  ;; to use 60 the broker cannot go on timing it out against its own 120. It
  ;; is what the timer runs on and what the stored client says — the CONNACK
  ;; is built from the original, which is how it knows to mention the
  ;; change. And a name for this one connection, so that whatever hears it
  ;; ended can say which one: the client-id is the same across every
  ;; connection the client ever makes.
  (let [connack (MqttConnAck/encode (connack-for msg))
        agreed  (if (version-5? (:protocol-version msg))
                  (effective-keep-alive keep-alive)
                  keep-alive)]
    ;; The session first, the CONNACK second. The CONNACK used to go out
    ;; before add-client!, so for a moment the client was told it had its
    ;; session while its resumed subscriptions were not yet in the live trie
    ;; — a publish from anyone else in that moment missed it. The moment
    ;; was short; asking the cluster on the way in made it long enough to
    ;; be seen.
    (add-client! (assoc msg :keep-alive agreed :connect-id (connection-id)))
    (send-buffer [client-key] connack)
    ;; After add-client!, never before: it replaces this key's whole entry,
    ;; which would throw away the :timer and :last-active that add-timer!
    ;; writes.
    (when (pos? (long agreed))
      (add-timer! client-key agreed))))

(defn no-client-id-and-no-clean-session [client-id clean-session?]
  (and (empty? client-id) (not clean-session?)))

(defn handle-incorrect-clean-session [{:keys [client-key]}]
  (send-buffer [client-key] (MqttConnAck/encode {:packet-type :CONNACK
                                                 :session-present? false
                                                 :connect-return-code 0x02}))
  (handlers/pause-before-close!)
  (disconnect-client client-key))

(defn take-over-existing!
  "Disconnect whatever connection is already holding `client-id` (§3.1.4).

   \"If the ClientId represents a Client already connected to the Server then
   the Server MUST disconnect the existing Client.\" This broker let both live,
   and since the outbound window and the in-flight map are keyed by client id,
   the newcomer inherited a window the incumbent had already filled — which is
   what stops the Paho conformance suite part-way through, every test in it
   reconnecting as the same id.

   The connection goes; the *session* does not. disconnect-client parks a
   persistent session under its client-id exactly as an ordinary disconnect
   does, so the CONNECT that displaced it can then resume it. Discarding here
   would turn every takeover into a silent clean start."
  [client-id new-key]
  (when-let [old-key (handlers/live-connection client-id)]
    (when-not (= old-key new-key)
      (log/info "session taken over for client-id" client-id)
      ;; §4.13.1: tell it why, or it sees an unexplained close, reconnects, and
      ;; takes the connection straight back off whoever displaced it. The close
      ;; below waits for the writer, so this is sent rather than raced.
      (when (>= (handlers/protocol-version-of old-key) 5)
        (send-buffer [old-key]
                     (MqttDisconnect/encode
                      {:packet-type      :DISCONNECT
                       :protocol-version 5
                       :reason-code      MqttReasonCode/SESSION_TAKEN_OVER}))
        (handlers/pause-before-close!))
      (disconnect-client old-key))))

(defonce ^:private assigned-counter (atom 0))

(defn assign-client-id
  "A name for a client that did not give one (§3.1.3.1).

   It has to be unique, and visibly so: §3.1.4 disconnects whatever connection
   already holds an id, so two anonymous clients sharing a name would take each
   other's sessions over in turn. They did — both were stored under \"\", and
   the second to connect knocked the first off with 0x8E.

   The counter is enough on its own within a process; the start time keeps two
   runs of the broker from handing out the same names to sessions that outlive
   a restart."
  []
  (str "mqttkat-" (Long/toString (System/currentTimeMillis) 36)
       "-" (swap! assigned-counter inc)))

(defn connect [{:keys [protocol-name protocol-version client-key clean-session?] :as connect-msg}]
  (log/debug "CONNECT:" (dissoc connect-msg :client-key))
  ;; §3.1.3.1: a zero-length id means "you name me". Done here, before anything
  ;; keys off it — the session, the takeover check, the will and the CONNACK
  ;; all have to be talking about the same client.
  (let [anonymous? (empty? (:client-id connect-msg))
        msg        (cond-> connect-msg
                     anonymous? (assoc :client-id (assign-client-id)
                                       :assigned-client-id? true))
        client-id  (:client-id msg)
        valid?     (and (not (protocol-name-not-valid? protocol-name))
                        (not (protocol-version-not-valid? protocol-version)))
        ;; Decided first: a client sent elsewhere has not connected here, so
        ;; nothing below about this client-id's state — the takeover, the
        ;; cancellations, the session — is about it.
        redirect   (when valid? (handlers/redirect-for msg))]
  ;; Before anything else that touches this client-id's state.
  (when (and valid? (not redirect))
    ;; Takeover first, then the cancel — not the other way round. Closing the
    ;; displaced connection runs its will through handle-will-if-present, which
    ;; *schedules* a delayed one; cancelling before that happens leaves the
    ;; newly scheduled will to fire a few seconds later, announcing the death
    ;; of a client that is sitting right there on the new connection.
    ;;
    ;; §3.1.2.5: a will still waiting out its delay is deleted when a new
    ;; connection for the client id is opened. That covers both cases — the one
    ;; just scheduled by the takeover, and one left by a connection that had
    ;; already closed on its own.
    (take-over-existing! client-id client-key)
    (handlers/cancel-delayed-will! client-id)
    ;; The session is being resumed or replaced either way, so the timer that
    ;; would have discarded it must not fire behind the new connection.
    (handlers/cancel-session-expiry! client-id))
  (cond
    (protocol-name-not-valid? protocol-name) (disconnect-client client-key)
    (protocol-version-not-valid? protocol-version) (handle-not-valid-protocol-version msg)
    (client-contains? client-key) (disconnect-client client-key)
    redirect (handle-redirect msg redirect)
    ;; The original emptiness, not the assigned name — and only for 3.1.1.
    ;; Its §3.1.3.1 refuses a zero-length id that asks to resume a session,
    ;; because there is no way to tell the client what it was named and a
    ;; session stored under that name would be unreachable. Version 5 dropped
    ;; the restriction when it gained Assigned Client Identifier (§3.1.3.1):
    ;; the server names the client, says so in the CONNACK, and the session is
    ;; addressable after all. Applying the old rule to a version 5 client
    ;; refused a connection the specification requires be accepted.
    (and anonymous? (not clean-session?) (not (version-5? protocol-version)))
    (handle-incorrect-clean-session msg)
    :else (handle-success msg))
    ;; Not for a client sent elsewhere: its session, if it has one here,
    ;; is for the broker it was sent to.
    (when-not redirect
      ;; Anything this client left unacknowledged is still recorded against
      ;; its client-id, under the same identifiers it was sent with, so
      ;; redelivery reuses them rather than reserving new ones.
      (handlers/redeliver-inflight! client-key client-id)
      ;; Then whatever arrived while this session was away — after the
      ;; redeliveries above, which were already on their way before it left.
      (flush-pending! client-key client-id))))
