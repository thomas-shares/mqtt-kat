(ns mqttkat.bridge
  "Broker to broker, in MQTT.

   With several brokers in front of one Rama, a publish on this broker may
   match subscriptions held by clients of another. Rama says which brokers
   those are — every broker's copy of the cluster's subscriptions names the
   broker on each entry — and this is how the message gets there: this broker
   is a client of that one, and publishes it on. One connection per peer,
   opened on first use, in the protocol both ends already speak, with the
   QoS 1 and 2 flows the client side of it owes.

   The receiving broker delivers to its own subscribers and no further. It
   knows a bridge by its client id, `mqttkat-bridge/<broker-id>`, and a
   publish arriving on one is never forwarded again — that is the whole of
   the loop prevention, and it is enough because every entry is held by
   exactly one broker: a message goes from the publisher's broker straight
   to each holder, one hop, never through a third.

   Not through Rama, on purpose. Rama holds the state the brokers share;
   the traffic between them is one TCP hop, and a depot append, a topology,
   a PState write and a proxy push per message would be several
   milliseconds and a disk write where a socket write will do.

   This namespace knows nothing about Rama or the broker. `forwarder` is the
   seam: whoever knows the other brokers installs a function there, and the
   publish path calls `forward!`."
  (:require [clojure.tools.logging :as log]
            [mqttkat.client :as client])
  (:import [java.io IOException]
           [java.util.concurrent.atomic AtomicInteger]
           [org.mqttkat MqttHandler]))

(def client-id-prefix
  "What a bridge connection calls itself, followed by the broker it comes
   from. The receiving broker recognises it by this."
  "mqttkat-bridge/")

(defn bridge?
  "Whether `client-id` is another broker's bridge connection."
  [client-id]
  (boolean (and client-id (.startsWith ^String client-id client-id-prefix))))

;; ── the seam ─────────────────────────────────────────────────────────────

(defonce planner
  ;; (fn [topic] -> plan) or nil. Installed by mqttkat.rama.cluster when the
  ;; broker is attached to a cluster; nil means a broker on its own, which
  ;; forwards nothing and pays nothing for it.
  ;;
  ;; A plan is {:brokers {peer-id [group-key ...]} :skip #{group-key ...}}:
  ;; the other brokers to send this publish to, each with the shared groups
  ;; it is to serve, and the shared groups this broker must leave alone
  ;; because another broker serves them. A group-key is [group topic-filter],
  ;; which together are the identity of a share (§4.8.2).
  (atom nil))

(defn plan
  "Where a publish on `topic` has to go besides here, or nil."
  [topic]
  (when-let [f @planner]
    (f topic)))

;; ── shared groups on the wire ────────────────────────────────────────────

(def share-property
  "The user property a forwarded publish carries once per shared group the
   receiving broker is to serve: its value is `group/topic-filter`. A group
   name may not contain a slash (§4.8.2), so the first one is the split. A
   forwarded publish carrying none serves no shared group at the other end —
   the choice of which broker serves a group is made where the whole group
   is visible, on the publisher's broker, and every other broker is told."
  "mqttkat-share")

(defn- group-key->string [[group topic-filter]]
  (str group "/" topic-filter))

(defn- string->group-key [^String s]
  (let [slash (.indexOf s "/")]
    (when (pos? slash)
      [(subs s 0 slash) (subs s (inc slash))])))

(defn with-shares
  "`properties` with the share property for each of `group-keys`."
  [properties group-keys]
  (if (empty? group-keys)
    properties
    (update properties :user-properties
            (fn [ups] (into (vec ups) (map #(vector share-property (group-key->string %))) group-keys)))))

(defn take-shares
  "Split a bridged publish's `properties` into [group-keys properties']:
   the shared groups this broker is to serve, and the properties with those
   instructions removed — they were for this broker, not its subscribers."
  [properties]
  (let [ups    (:user-properties properties)
        shares (into #{} (keep (fn [[k v]] (when (= share-property k) (string->group-key v)))) ups)
        rest   (remove (fn [[k _]] (= share-property k)) ups)]
    [shares (if (seq rest)
              (assoc properties :user-properties (vec rest))
              (dissoc properties :user-properties))]))

;; ── connections to peers ─────────────────────────────────────────────────

(defonce ^:private connections
  ;; peer broker-id -> {:client <MqttClient> :ids <AtomicInteger>}, or
  ;; {:down-until millis} after a failure, so a peer that is not there is
  ;; tried again in a while rather than on every publish.
  (atom {}))

(def retry-after-ms
  "How long a peer that could not be reached is left alone."
  5000)

(defn- next-packet-id
  "1..65535, wrapping. Safe against reuse because nothing here holds an
   identifier for anywhere near that long."
  ^long [^AtomicInteger ids]
  (inc (mod (.getAndIncrement ids) 65535)))

(defn- on-packet
  "What the peer sends back. A bridge subscribes to nothing, so this is
   acknowledgements: the QoS 2 handshake needs a PUBREL from this side, and
   a PUBACK or PUBCOMP needs nothing."
  [holder peer-id {:keys [packet-type packet-identifier] :as msg}]
  (case packet-type
    :PUBREC  (when-let [c @holder]
               (client/send-message c {:packet-type :PUBREL :packet-identifier packet-identifier}))
    :CONNACK (log/info "bridge to" peer-id "up")
    :DISCONNECT (log/info "bridge to" peer-id "closed by the other end:" (:reason-code msg))
    nil))

(defn- open!
  "Connect to `peer` and introduce this broker. Does not wait for the
   CONNACK: the publish that follows is behind the CONNECT on the same
   socket, and the other end handles them in order."
  [my-id peer-id {:keys [host port]}]
  (let [holder  (atom nil)
        handler (MqttHandler. ^clojure.lang.IFn (fn [msg _] (on-packet holder peer-id msg)) 1)
        c       (client/client host (int port) handler)]
    (reset! holder c)
    (client/send-message c {:packet-type      :CONNECT
                            :protocol-name    "MQTT"
                            :protocol-version 5
                            :keep-alive       0
                            :clean-session?   true
                            :client-id        (str client-id-prefix my-id)})
    {:client c :ids (AtomicInteger. 0)}))

(defn- connection!
  "The connection to `peer-id`, opened if there is none. nil if the peer was
   unreachable recently. Under a lock so two publishes racing on first use
   do not open two."
  [my-id peer-id peer]
  (locking connections
    (let [{:keys [client down-until] :as existing} (get @connections peer-id)]
      (cond
        (and client (client/connected? client)) existing
        (and down-until (< (System/currentTimeMillis) (long down-until))) nil
        :else
        (try
          (let [opened (open! my-id peer-id peer)]
            (swap! connections assoc peer-id opened)
            opened)
          (catch IOException e
            (log/warn "bridge to" peer-id "at" (:host peer) (:port peer) "could not connect:" (.getMessage e))
            (swap! connections assoc peer-id {:down-until (+ (System/currentTimeMillis) retry-after-ms)})
            nil))))))

(defn drop!
  "Close and forget the connection to `peer-id`, if any: the registry says
   it is gone, or a send just failed."
  [peer-id]
  (when-let [{:keys [client]} (get @connections peer-id)]
    (swap! connections dissoc peer-id)
    (when client
      (try (client/close client) (catch Exception _ nil)))))

(defn close-all! []
  (doseq [peer-id (keys @connections)]
    (drop! peer-id)))

(defn send-to!
  "Publish `msg` to `peer-id` at `peer` — {:host :port} — as this broker,
   telling it which shared groups are its to serve.

   Retain is off on the way out: what is retained is recorded once, by the
   publisher's broker, and the other end must not store a copy under its own
   name. Version 5 on the wire whatever the publisher spoke, so the
   properties travel; the receiving broker strips them for its 3.1.1
   subscribers as it does for any publish."
  [my-id peer-id peer group-keys topic {:keys [qos payload properties]}]
  (when-let [{:keys [client ids]} (connection! my-id peer-id peer)]
    (let [qos (long (or qos 0))]
      (try
        (client/send-message client
                             (cond-> {:packet-type      :PUBLISH
                                      :protocol-version 5
                                      :topic            topic
                                      :qos              qos
                                      :payload          payload
                                      :retain?          false
                                      :duplicate?       false
                                      :properties       (with-shares (or properties {}) group-keys)}
                               (pos? qos) (assoc :packet-identifier (next-packet-id ids))))
        (catch IOException e
          (log/warn "bridge to" peer-id "lost:" (.getMessage e))
          (drop! peer-id))))))

(def control-prefix
  "Where an instruction to the other broker goes: a publish on a topic
   under this, over the bridge, is for the broker, not its subscribers."
  "$mqttkat/")

(defn takeover!
  "Tell `peer-id` that `client-id` has connected here, so the connection it
   holds for it — named by its connect-id, so a newer one is left alone —
   is to end (§3.1.4). QoS 0: if the peer is not there to hear it, the
   connection it held is not there either."
  [my-id peer-id peer client-id connect-id]
  (when-let [{:keys [client]} (connection! my-id peer-id peer)]
    (try
      (client/send-message client {:packet-type      :PUBLISH
                                   :protocol-version 5
                                   :topic            (str control-prefix "takeover")
                                   :qos              0
                                   :payload          (byte-array 0)
                                   :retain?          false
                                   :duplicate?       false
                                   :properties       {:user-properties [["client-id" client-id]
                                                                        ["connect-id" (str connect-id)]]}})
      (catch IOException e
        (log/warn "bridge to" peer-id "lost:" (.getMessage e))
        (drop! peer-id)))))

(defonce forwarder
  ;; (fn [plan topic msg]) or nil, installed alongside `planner`: it knows
  ;; the peers' addresses and this broker's name, which this namespace does
  ;; not.
  (atom nil))

(defn forward!
  "Carry out `plan` for a publish of `msg` — {:qos :payload :properties} —
   on `topic`: one copy to each broker named, with its groups, and whatever
   else the planner put in the plan for the forwarder to do."
  [plan topic msg]
  (when-let [f @forwarder]
    (when plan
      (f plan topic msg))))

(defn peers
  "The peers this broker currently has a connection to."
  []
  (into #{} (keep (fn [[id {:keys [client]}]] (when client id))) @connections))
