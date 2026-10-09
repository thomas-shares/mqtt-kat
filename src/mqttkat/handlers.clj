(ns mqttkat.handlers
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [mqttkat.s :refer [*server*]]
            [overtone.at-at :as at]
            [clojurewerkz.triennium.mqtt :as tr]
            [mqttkat.events :as events]
            [mqttkat.bridge :as bridge]
            [mqttkat.intent :as intent]
            [mqttkat.retained :as retained]
            [mqttkat.trace :as trace]
            [mqttkat.trie :refer [trie-insert trie-delete trie-matching-vals sieve-dollar]])
  (:import [java.util.concurrent CompletableFuture]
           [java.util.concurrent.atomic LongAdder]
           [java.util.function BiConsumer]
           [org.mqttkat MqttStat MqttReasonCode TopicStats]
           [java.nio.channels SelectionKey]
           [org.mqttkat.server Connection MqttServer]
           [org.mqttkat.packages MqttPublish MqttDisconnect
            MqttPubRel MqttPubAck MqttPubRec
            MqttPubComp MqttSubAck MqttPingResp MqttUnSubAck]))

(def max-packet-identifier
  "MQTT 3.1.1 §2.3.1: identifiers run 1..65535, and 0 is not one."
  65535)

(def inflight-window
  "How many unacknowledged QoS 1/2 messages the broker will hold for one
   client before it stops accepting more for that client.

   This is the resource that is actually scarce — identifiers are not, there
   are 65535 of them per connection. Keeping the window well under that is
   also what lets the counter below wrap without ever colliding with a live
   identifier. Mosquitto's equivalent, max_inflight_messages, defaults to 20."
  128)

(def pending-limit
  "How many QoS 1/2 messages will wait for a window slot before the broker
   gives up on them.

   The window alone is not a policy: something has to happen to the message
   that cannot have an identifier yet. Blocking the fan-out thread is what the
   old pool did, and it deadlocks a client that both publishes and subscribes.
   Disconnecting the subscriber, which is what this first tried, turns a busy
   moment into 408 dropped connections under load. So it waits here instead,
   and is refused only once this is full too — the same shape as the QoS 0
   queue limit, and what Mosquitto does with max_queued_messages."
  4096)

(def pause-threshold
  "Pending depth at which the broker stops reading from a publisher feeding
   this subscriber.

   Well below pending-limit on purpose. Clearing OP_READ stops new bytes
   arriving, but the publisher's reader thread still has whatever was already
   framed to work through, and every one of those publishes fans out. The gap
   between this and pending-limit is the headroom for that overshoot."
  512)

(def resume-threshold
  "Pending depth at which those publishers are read again. Hysteresis: resuming
   at the same depth that paused would flap the interest ops once per packet."
  128)

(def ^:dynamic *clients* (atom {}))
(def ^:dynamic *inflight* (atom {}))
(def ^:dynamic *subscriber-trie* (atom (tr/make-trie)))

(def ^:dynamic *offline-trie*
  "Subscriptions belonging to persistent sessions whose client is not connected.

   §3.1.2.4 makes a CleanSession 0 session outlive its connection, and §4.1
   requires QoS 1 and 2 messages matching its subscriptions to be kept for it
   while it is away. Those subscriptions used to be deleted from the live trie
   on disconnect and restored on reconnect, so a publish in between matched
   nothing and there was nothing to keep.

   They live in a trie of their own rather than staying in the live one because
   the fan-out writes to a SelectionKey, and an offline session has none: what
   matches here is queued against the client-id instead of written to a socket."
  (atom (tr/make-trie)))

;; The two tries below are mqttkat.trie tries: triennium's layout, with the
;; insert, match and delete the broker needed to get right on top of it. See
;; that namespace for what was wrong with triennium's own.

(def ^:dynamic *live-clients*
  "client-id -> the SelectionKey of its current connection.

   MQTT 3.1.1 §3.1.4 requires a second CONNECT under an existing client id to
   disconnect the first, so every CONNECT has to ask \"is this id already
   connected?\". An index rather than a scan of *clients* because the answer is
   needed on the connect path: connection-scale-remote-test opens fifty
   thousand clients, and a walk per connect would make that quadratic.

   Live connections only. A parked session has no connection to take over and
   is found in *clients* under its client-id as before."
  (atom {}))

(def ^:dynamic *outbound*
  "client-id -> an atom holding that client's outbound state.

   An atom of atoms, which is the point. This used to be one atom holding every
   client's state, and every QoS 1 publish and every PUBACK did
   `update-in [client-id :inflight]` on it. With a couple of thousand clients
   that is path-copying through a map of that size on each message, and since
   every connection thread hits the same atom, CAS retries on top of it. A CPU
   profile of a 2,200-client run put 14% of the broker's time inside
   `clojure.lang.Atom.swap`, with acquire and release of packet identifiers
   costing seven times what encoding the packet cost.

   Now the registry is only read on the hot path — one lookup, no swap — and
   written when a session is created or discarded. The mutation happens on the
   client's own atom, uncontended except by its own threads, over a map holding
   at most `inflight-window` entries.

   Still keyed by client-id and not by connection, which is deliberate and
   unchanged: §4.4 requires a persistent session's unacknowledged messages to
   survive the connection and be redelivered on the next one."
  (atom {}))
(def ^:dynamic *retained*
  "topic -> {:qos :payload :properties :stored-at}. mqttkat.retained's own
   atom, so a read here and a write there are the same map; writes go
   through that namespace, which is how the cluster hears about them."
  retained/store)

(defn matching-offline-sessions
  "Persistent sessions subscribed to `topic` whose client is not connected."
  [^String topic]
  (sieve-dollar topic (trie-matching-vals @*offline-trie* topic)))

(defn matching-subscribers
  "The subscribers a message on `topic` should go to.

   MQTT 3.1.1 §4.7.2: a topic filter beginning with a wildcard must not match a
   topic name beginning with $. Those names are the server's own — $SYS and the
   like — and a client subscribing to `#` is asking for the application's
   traffic, not the broker's internals. A filter that names the $ level
   itself, `$SYS/#`, still matches, so the rule is about the first level of the
   filter rather than about $ appearing anywhere.

   The trie does not know this rule, so the filter each subscription was made
   with is kept alongside it and the matches are sieved here."
  [^String topic]
  (sieve-dollar topic (trie-matching-vals @*subscriber-trie* topic)))

(def my-pool (at/mk-pool))
(declare qos-0)
(declare hand-over-unacknowledged!)
(declare qos-1-send)
(declare qos-2-send)
(declare remove-client!)
(declare remove-timer!)
(declare discard-inbound-qos-2!)
(declare disconnect-with-reason!)
(declare select-shared)
(declare coalesce-subscriptions)

(def forwarded-publish-properties
  "The PUBLISH properties that travel from publisher to subscriber (§3.3.2.3).

   Topic Alias is deliberately not among them. An alias is per connection and
   per direction, so the publisher's number means nothing on the subscriber's
   connection — forwarding it would bind the subscriber to a mapping it never
   agreed, and every later publish under that alias would go to the wrong
   topic.

   Subscription Identifier is absent for the opposite reason: the server adds
   it on the way out rather than passing one on."
  [:payload-format-indicator :message-expiry-interval :content-type
   :response-topic :correlation-data :user-properties])

(defn forwardable-properties [properties]
  (select-keys properties forwarded-publish-properties))

(declare route)
(declare forward-to-brokers!)
(declare session-source new-message-key)

(defn publish-will [{:keys [topic qos retain payload properties]}]
  (log/trace "Sending will message on topic:" payload)
  ;; §3.1.3.2: the Will Properties are the message's, and go out with it.
  ;; Through the same whitelist as a forwarded publish, which is what keeps
  ;; the Will Delay Interval out of it — that one is an instruction to the
  ;; broker about when to send this, and means nothing to a subscriber.
  ;; Routed like any publish: the client whose will this is was here, so
  ;; this is never a bridged copy, and the other brokers get theirs.
  ;; Named, as any QoS 1 or 2 publish entering the cluster is, so that the
  ;; brokers it is forwarded to queue it as they would any other.
  (let [msg (cond-> {:qos qos :payload payload :properties (forwardable-properties properties)}
              (and (pos? (long (or qos 0))) @session-source) (assoc ::msg-key (new-message-key)))
        {:keys [plan serve-group?]} (route topic msg)
        live (when-let [keys (coalesce-subscriptions (select-shared (matching-subscribers topic) serve-group?))]
               (log/trace "Will keys:" keys)
               (case (long qos)
                 0 (qos-0 keys topic msg retain)
                 1 (qos-1-send keys topic msg)
                 2 (qos-2-send keys topic msg)))]
    (forward-to-brokers! plan topic msg (if (set? live) live #{}))))

(defonce ^:private delayed-wills
  ;; client-id -> the scheduled job that will publish its will. Held so a
  ;; reconnection can cancel it, which is the whole reason the delay exists.
  ;; A comment rather than a docstring: defonce takes none.
  (atom {}))

(defn will-delay-ms
  "How long to hold this client's will before publishing it (§3.1.3.2.2).

   The smaller of the Will Delay Interval and the Session Expiry Interval: the
   will goes out when the delay elapses *or the session ends, whichever
   happens first*. A session expiry of 0 — the default, and what a 3.1.1 client
   effectively has — therefore means immediately, however long a delay was
   asked for. There would be nothing left to come back to."
  [client]
  (let [delay  (or (get-in client [:will :properties :will-delay-interval]) 0)
        expiry (or (get-in client [:properties :session-expiry-interval]) 0)]
    (* 1000 (min (long delay) (long expiry)))))

(defn cancel-delayed-will!
  "Delete a will that has not been published yet (§3.1.2.5).

   Called when a client reconnects under the same id: it has not really gone
   away, and telling its subscribers it had would be wrong."
  [client-id]
  (when-let [job (get @delayed-wills client-id)]
    (log/trace "client" client-id "came back — deleting its pending will")
    (swap! delayed-wills dissoc client-id)
    (try (at/kill job) (catch Exception _ nil))))

(defn- take-will!
  "Remove the will from `key`'s entry and return the entry as it was, will
   included — or nil if there was no will to take.

   One atomic step, so a will goes out at most once. Several paths reach
   handle-will-if-present for the same connection (the keep-alive reaper, the
   socket closing, a takeover), and a will left in place after firing was also
   carried into the parked session of a persistent client."
  [key]
  (let [[old _] (swap-vals! *clients*
                            (fn [clients]
                              (if (contains? (get clients key) :will)
                                (update clients key dissoc :will)
                                clients)))
        client  (get old key)]
    (when (contains? client :will)
      client)))

(defn handle-will-if-present [key]
  (when-let [client (take-will! key)]
    ;; Captured now, not read when the job fires: by then this client's
    ;; entry has been removed and there would be no will left to send.
    (let [will   {:topic      (get-in client [:will :will-topic])
                  :qos        (get-in client [:will :will-qos])
                  :payload    (get-in client [:will :will-message])
                  :retain     (get-in client [:will :will-retain])
                  :properties (get-in client [:will :properties])}
          ms        (will-delay-ms client)
          client-id (:client-id client)]
      (if (and (pos? ms) client-id)
        (do
          (cancel-delayed-will! client-id)
          (swap! delayed-wills assoc client-id
                 (at/after ms
                           (fn []
                             (swap! delayed-wills dissoc client-id)
                             (try
                               (publish-will will)
                               (catch Throwable t
                                 (log/error t "publishing a delayed will failed"))))
                           my-pool)))
        (publish-will will)))))

(defn check-timer
  "Drop `key` if nothing has been received from it for `time-out` ms.

   MQTT 3.1.1 §3.1.2.10: the server disconnects a client it has not heard from
   for one and a half times the Keep Alive interval, which is what add-timer!
   passes as `time-out`."
  [key time-out]
  (when-let [last-active (get-in @*clients* [key :last-active])]
    (let [idle (- (System/currentTimeMillis) @last-active)]
      (log/debug "timer fired:" time-out idle)
      (when (<= time-out idle)
        (log/debug "Timer fired for client:" key)
        ;; handle-will-if-present takes the will off the client as it sends
        ;; it, and remove-client! kills this timer.
        (handle-will-if-present key)
        (remove-client! key)
        (log/debug "about to close")
        ;; *server* holds the stop-server closure; the MqttServer itself lives
        ;; in its metadata, the same way send-buffer reaches it. The close is
        ;; guarded so a socket that has already gone cannot kill the timer.
        (try
          (.closeConnection ^MqttServer (:server (meta @*server*)) key)
          (catch Exception e
            (log/warn e "closing the connection of a timed-out client failed")))
        (log/debug "closed....")))))

(defn- keep-alive-tick
  "One tick of `timer`, the keep-alive job add-timer! filed under `key`.

   remove-timer! can only stop a timer it finds in the entry, so one whose
   entry was removed or replaced without it used to go on firing every
   interval for the life of the broker, against a client that was no longer
   there. The tick checks it is still the job filed under `key` first, and
   cancels itself if not."
  [key time-out timer]
  (if (identical? timer (get-in @*clients* [key :timer]))
    (check-timer key time-out)
    (do
      (log/debug "keep-alive timer outlived its client, stopping it:" key)
      (at/kill timer))))

(defn add-timer!
  [key time]
  (log/trace "adding client to timer" time " and key:   " key)
  (let [time-out (* 1500 time)
        ;; The tick needs its own job to know whether it is still wanted, and
        ;; the job does not exist until at/every returns. The first tick is a
        ;; whole time-out away, long after the promise is delivered.
        timer    (promise)]
    ;; One timer per connection: a second one would overwrite the first in
    ;; the entry, and remove-timer! would never find the first again.
    (remove-timer! key)
    ;; Stamp liveness BEFORE scheduling. The job's initial delay starts running
    ;; the moment at/every is called, so a stamp taken afterwards leaves the
    ;; first tick measuring fractionally less than time-out of idleness — the
    ;; client then survives that cycle and is only reaped on the next one.
    (swap! *clients* assoc-in [key :last-active] (volatile! (System/currentTimeMillis)))
    (deliver timer (at/every time-out #(keep-alive-tick key time-out @timer) my-pool
                             :initial-delay time-out))
    (swap! *clients* assoc-in [key :timer] @timer))
  #_(log/trace @*clients*))

(defn remove-timer! [key]
  ;; Taken off the entry in one step, and only from an entry that is there.
  ;; The read and the write used to be separate, so a client removed between
  ;; them came back as {key {:timer nil}}, an entry nothing would ever delete.
  (let [[old _] (swap-vals! *clients*
                            (fn [clients]
                              (if (get-in clients [key :timer])
                                (assoc-in clients [key :timer] nil)
                                clients)))]
    (when-let [timer (get-in old [key :timer])]
      (at/kill timer))))

(defn discard-session!
  "Forget everything stored under `client-id`: the parked session, the
   subscriptions held for it while offline, and anything queued or in flight."
  [client-id]
  (doseq [topic (:subscribed-topics (get @*clients* client-id))]
    (swap! *offline-trie* trie-delete (:topic-filter topic)
           {:client-id client-id :qos (:qos topic) :topic-filter (:topic-filter topic)}))
  (swap! *clients* dissoc client-id)
  (swap! *outbound* dissoc client-id)
  (swap! *inflight* #(into {} (remove (fn [[[id _] _]] (= id client-id))) %))
  (discard-inbound-qos-2! client-id))

(defn live-connection
  "The key of the connection currently holding `client-id`, if any."
  [client-id]
  (get @*live-clients* client-id))

(defn live-client
  "The client map of the connection currently holding `client-id`, if any."
  [client-id]
  (some->> (live-connection client-id) (get @*clients*)))

(defn live-key?
  "Whether `key` is the connection holding its client right now, and so one
   to deliver to. A connection being torn down is not, once remove-client!
   has parked its session and taken it out of the index: what it matched is
   queued for the session from then on (queue-for-offline-sessions!, or the
   cluster's plan) instead of written to a socket that is going. One being
   set up is not either, until add-client! has its subscriptions in the live
   trie."
  ([key] (live-key? @*live-clients* @*clients* key))
  ([live clients key]
   (let [client-id (get-in clients [key :client-id])]
     (and client-id (= key (get live client-id))))))

(defn awaiting-connack?
  "Whether this connection's CONNACK has not gone out yet. §3.2.0-1: the
   CONNACK is the first packet the server sends, so anything for it until
   then waits (see deliver-or-queue!)."
  [key]
  (boolean (get-in @*clients* [key :awaiting-connack?])))

(defn connack-sent!
  "The CONNACK is written: deliveries to `key` go straight out from here."
  [key]
  (swap! *clients* (fn [m] (if (contains? m key) (update m key dissoc :awaiting-connack?) m))))

(defn live-sessions
  "Every client connected here now, as *clients* holds it: the terms of its
   CONNECT, its connect-id and its subscriptions."
  []
  (let [clients @*clients*]
    (keep (fn [[_ key]] (get clients key)) @*live-clients*)))

(defn- register-live! [client-id client-key]
  (when client-id
    (swap! *live-clients* assoc client-id client-key)))

(defn forget-live!
  "Drop this connection from the index, but only if it is still the one
   registered.

   The check matters on takeover: the displaced connection's own teardown runs
   after the replacement has registered, and an unconditional dissoc would
   remove the new connection from the index and leave it un-takeoverable."
  [client-id client-key]
  (when client-id
    (swap! *live-clients*
           (fn [m] (if (= client-key (get m client-id)) (dissoc m client-id) m)))))

(defonce ^:private session-expiries
  ;; client-id -> {:job the timer that will discard its parked session, :token
  ;; what that timer has to find here to go ahead}. Cancelled when the client
  ;; comes back, or the session would be torn out from under the live
  ;; connection that resumed it.
  (atom {}))

(defonce ^:private session-expiry-lock
  ;; Held by cancel-session-expiry! and by an expiry deciding to go ahead, so
  ;; a cancel either lands first and the expiry stands down, or waits until
  ;; the session is gone and the client is let in to a fresh one.
  (Object.))

(defn session-expiry-seconds
  "How long this client's session outlives its connection (§3.1.2.11.2).

   Absent means 0, which is why a 3.1.1 client — which cannot send the property
   at all — behaves exactly as it always did."
  [client]
  (long (or (get-in client [:properties :session-expiry-interval]) 0)))

(defn keep-session?
  "Whether to park this session rather than discard it.

   Version 5 decides on the Session Expiry Interval, not on Clean Start:
   §3.1.2.11.2 splits the two questions 3.1.1 answered with one flag. Clean
   Start says whether to *resume* an existing session; the expiry says how long
   this one *survives*. A client may therefore start fresh and still keep its
   session, which is what the broker used to get wrong — it read clean-session?
   and threw away sessions their owners had asked to keep."
  [client]
  (if (>= (long (or (:protocol-version client) 4)) 5)
    (pos? (session-expiry-seconds client))
    (false? (:clean-session? client))))

(defn set-session-expiry!
  "Override the interval this connection will expire on (§3.14.2.2.2).

   A DISCONNECT may carry one, letting a client decide on the way out that it
   will be back — without having planned for it when it connected."
  [client-key seconds]
  (when (contains? @*clients* client-key)
    (swap! *clients* assoc-in [client-key :properties :session-expiry-interval]
           (long seconds))))

(defn cancel-session-expiry! [client-id]
  (locking session-expiry-lock
    (when-let [{:keys [job]} (get @session-expiries client-id)]
      (swap! session-expiries dissoc client-id)
      (try (at/kill job) (catch Exception _ nil)))))

(defn expire-session!
  "Discard `client-id`'s parked session, if `token` is still the expiry filed
   for it.

   at/kill stops a job that has not started, not one already running. The job
   used to take itself out of session-expiries and then discard, so a reconnect
   landing in between found nothing to cancel, resumed the session — CONNACK
   saying it was present — and then had discard-session! empty *outbound* and
   *inflight* under it, those being keyed by client-id and not by connection.
   The redelivery that followed found nothing, and the next delivery numbered
   its identifiers from 1 again. The token also stops an expiry that was held
   up past a resume and a second disconnect from discarding the session that
   second disconnect parked, whose own timer has not run out."
  [client-id token]
  (locking session-expiry-lock
    (if (identical? token (:token (get @session-expiries client-id)))
      (do (swap! session-expiries dissoc client-id)
          (log/debug "session expired for" client-id)
          (discard-session! client-id))
      (log/debug "expiry for" client-id "was cancelled before it ran"))))

(def ^:private never-expires
  "0xFFFFFFFF — §3.1.2.11.2's \"do not expire\", not a very long timer."
  4294967295)

(defn schedule-session-expiry!
  "Discard the parked session once `seconds` have passed."
  [client-id seconds]
  (when (and client-id (pos? (long seconds)) (not= (long seconds) never-expires))
    ;; Filed under the lock, so a timer short enough to fire before it is filed
    ;; waits for it rather than finding someone else's token and standing down.
    (locking session-expiry-lock
      (cancel-session-expiry! client-id)
      (let [token (Object.)]
        (swap! session-expiries assoc client-id
               {:token token
                :job   (at/after (* 1000 (long seconds))
                                 (fn []
                                   (try
                                     (expire-session! client-id token)
                                     (catch Throwable t
                                       (log/error t "expiring a session failed"))))
                                 my-pool)})))))

(defn add-client! [{:keys [client-key client-id clean-session?] :as msg}]
  (if (and (false? clean-session?)
           (contains? @*clients* client-id))
    (let [client (get @*clients* client-id)]
      (log/trace "client-id already exists:" client-id)
      ;; Stamped on the resumed connection, not carried over from the one that
      ;; went away: the console shows how long this connection has been up.
      ;; The new connection's name too, not the one that parked the session:
      ;; that connection is over, and its disconnect has been reported.
      ;; The session from the parked entry, the connection from this CONNECT:
      ;; its protocol version, keep alive, will and properties (Session
      ;; Expiry, Receive Maximum, Maximum Packet Size) are this connection's
      ;; terms, not the last one's. A session adopt-session! parked from the
      ;; cluster has only its subscriptions, so resuming it as it was left
      ;; the connection with no version — answered in 3.1.1, which a version
      ;; 5 client cannot parse — and no Session Expiry, so the session was
      ;; discarded on the next disconnect.
      ;;
      ;; First, before the tries: a live match on this key finds nobody to
      ;; deliver to until the record is here.
      (swap! *clients* assoc client-key (merge client
                                               (dissoc msg :packet-type :client-key)
                                               {:connected-at      (System/currentTimeMillis)
                                                :awaiting-connack? true}))
      ;; Then the session moves from the offline trie to the live one, live
      ;; first. The other way round there was a moment it was in neither, and
      ;; a publish then matched nobody and was lost. In the overlap it is in
      ;; both, and a publish that reaches it live leaves it out of the offline
      ;; queue (queue-for-offline-sessions!), so it gets the message once.
      (let [subscriptions (get-in client [:subscribed-topics])]
        (log/trace "subscriptions:" subscriptions)
        (doseq [topic subscriptions]
          (log/trace "Adding to sub-trie for topic:" (:topic-filter topic)  "   qos: " (:qos topic))
          ;; The whole entry, exactly as subscribe stores one and as
          ;; remove-client! will delete it. This used to insert only the
          ;; filter and QoS, so a resumed session lost its version 5 options
          ;; — and its entry, deleted by the full value on the next
          ;; disconnect, was never found: it stayed in the live trie pointing
          ;; at a socket that was gone, for the life of the broker.
          (swap! *subscriber-trie* trie-insert (:topic-filter topic)
                 (assoc topic :client-key client-key)))
        ;; Live from here: deliveries on this key go ahead (see live-key?).
        (register-live! client-id client-key)
        (doseq [topic subscriptions]
          (swap! *offline-trie* trie-delete (:topic-filter topic)
                 {:client-id client-id :qos (:qos topic) :topic-filter (:topic-filter topic)})))
      (log/trace "client-id:" client-id)
      (swap! *clients* dissoc client-id))
    (let [client (-> (dissoc msg :packet-type :client-key)
                     ;; When this connection was accepted. The console has no
                     ;; other way to say how long a client has been here:
                     ;; :last-active exists only for clients that asked for a
                     ;; keep alive, so it is nil for most of them.
                     (assoc :connected-at (System/currentTimeMillis)))
          client-added (-> client
                           (update-in [:subscribed-topics] (fnil conj #{}))
                           (assoc :awaiting-connack? true))]
      ;; §3.1.2.4: connecting with CleanSession 1 discards any session stored
      ;; under this client-id. Without this the parked entry, its offline
      ;; subscriptions and its queued messages stayed for the life of the
      ;; process, and the next persistent connect resumed a session the client
      ;; had explicitly asked to be rid of.
      (when (contains? @*clients* client-id)
        (discard-session! client-id))
      (swap! *clients* assoc client-key client-added)))
  (register-live! client-id client-key)
  (MqttStat/clientConnected)
  ;; The id goes with the event so a watcher can say *which* client, which is
  ;; the difference between a console that reports a number and one that
  ;; reports what happened. And the terms of the CONNECT, for a watcher that
  ;; keeps a record of the session — what it asked for, under the name this
  ;; connection was given.
  (events/emit! {:event     :client-connected
                 :client-id client-id
                 :connect   (select-keys msg [:connect-id :client-id :protocol-version
                                              :clean-session? :keep-alive :properties])
                 :clients   (MqttStat/connectedClients)})
  (log/trace "ADD: Subscriber trie POST:" @*subscriber-trie*)
  (log/trace "ADD: Clients:" @*clients*))


;; ── topic aliases (§3.3.2.3.4) ───────────────────────────────────────────
;;
;; An alias replaces a topic name with a two-byte integer for the rest of a
;; connection. It is worth having because brokers carry the same long topic
;; over and over: `sensors/building-4/floor-2/room-17/temperature` costs 49
;; bytes every time, and 2 after the first.
;;
;; Two independent mappings, one per direction, each bounded by what the
;; *receiver* said it would accept — the broker's Topic Alias Maximum in the
;; CONNACK bounds what a client may send, the client's in the CONNECT bounds
;; what the broker may send back. A client's alias 1 and the broker's alias 1
;; on the same connection are different things pointing at different topics.
;;
;; Kept here, keyed by the connection, rather than in *clients* where the
;; inbound half used to live. *clients* is what a persistent session is parked
;; under when the socket drops: aliases stored there survived into the resumed
;; session, and the new connection would then resolve a number it had never
;; declared to whatever the old one had bound it to. The lifetime is the
;; connection's, so the storage should be too — remove-client! empties it.

(def broker-topic-alias-maximum
  "The highest alias number the broker will accept from a client (§3.1.2.11.5).

   Sent in the CONNACK, which is the only thing that makes it binding — a
   client may use aliases 1..this and nothing else. Ten is enough for the
   handful of topics a publisher repeats; the table is per connection, so a
   large number here is a per-connection cost paid across every client."
  10)

(defonce ^:private topic-aliases
  ;; {client-key {:inbound {alias topic} :outbound {topic alias} :assigned n}}
  (atom {}))

(defn forget-topic-aliases! [client-key]
  (swap! topic-aliases dissoc client-key))

(defn- client-topic-alias-maximum
  "How many aliases this client agreed to be sent (§3.1.2.11.5).

   Absent means zero: a client that says nothing has not agreed to any, and
   sending it one would be a number it cannot resolve. That default is also
   what keeps the common fan-out cheap — every 3.1.1 subscriber and every
   version 5 one that did not ask lands in the same group with the topic
   spelled out, exactly as before aliases existed."
  ^long [client-key]
  (long (or (get-in @*clients* [client-key :properties :topic-alias-maximum]) 0)))

(defn alias-outbound
  "Decide how to address a delivery of `topic` to `client-key`.

   Returns {:topic ... :topic-alias ...}, where :topic-alias may be nil.
   Three outcomes, in the order they happen over a connection's life:

     * no alias yet and room for one — assign it, and send the topic *and*
       the alias. Both, because an alias the receiver has never seen means
       nothing to it; the saving starts with the next message.
     * an alias already bound — send it with an empty topic name.
     * the client's allowance spent — send the topic in full, no alias. The
       alternative is refusing to deliver, and a topic name is never wrong.

   The whole decision is one swap!, so two threads publishing different topics
   to the same subscriber cannot read the same free slot and both take it."
  ([client-key topic] (alias-outbound client-key topic (client-topic-alias-maximum client-key)))
  ([client-key topic ^long maximum]
   (if (zero? maximum)
     {:topic topic}
     (let [[before after]
           (swap-vals! topic-aliases update client-key
                       (fn [{:keys [outbound assigned] :or {assigned 0} :as entry}]
                         (if (or (get outbound topic) (>= (long assigned) maximum))
                           entry
                           (-> entry
                               (assoc-in [:outbound topic] (inc (long assigned)))
                               (assoc :assigned (inc (long assigned)))))))
           bound (get-in after [client-key :outbound topic])]
       (cond
         ;; The allowance was already spent on other topics.
         (nil? bound) {:topic topic}
         ;; Fresh exactly when this swap! is what created it — which is what
         ;; the before/after pair answers and a bare "is it the highest
         ;; number" test does not: with two aliases in play, re-publishing
         ;; the most recently assigned topic would spell its name out again
         ;; on every message, and the alias would never save anything.
         (nil? (get-in before [client-key :outbound topic]))
         {:topic topic :topic-alias bound}
         :else {:topic "" :topic-alias bound})))))

(defn with-topic-alias
  "Apply an alias decision to an encoded-shaped PUBLISH.

   Only touches a packet that already has a property block, which is exactly
   the version 5 ones — publish-for adds it for those and must not for 3.1.1,
   where a property length byte would be read as the first byte of the
   payload. A version 4 subscriber never gets an alias anyway, its advertised
   maximum being zero, so this is belt and braces around that."
  [packet {:keys [topic topic-alias]}]
  (cond-> (assoc packet :topic topic)
    (and topic-alias (contains? packet :properties))
    (assoc-in [:properties :topic-alias] topic-alias)))

(defn- park-or-drop!
  "The second half of remove-client!: out of the live trie, and the session
   parked, left to its new connection, or dropped."
  [key]
  (log/trace "REMOVE: clean session?" (get-in @*clients* [key :clean-session?] true))
  (log/trace "key:" key)
  (let [client            (get @*clients* key)
        client-id         (:client-id client)
        clean-session?    (get client :clean-session? true)
        subscribed-topics (:subscribed-topics client)]
    ;; Out of the live trie either way. This used to happen only for a
    ;; persistent session, so a clean one left its subscriptions behind
    ;; pointing at a dead SelectionKey — for the life of the broker, growing
    ;; the trie with every client that ever connected and matching them on
    ;; every publish.
    (doseq [topic subscribed-topics]
      (log/trace "Removing from sub-trie for topic:" (:topic-filter topic) "  qos:" (:qos topic))
      ;; The entry as it was stored, not one rebuilt from the filter and QoS
      ;; — trie-delete matches on the whole value, and an MQTT 5 subscription
      ;; carries more than those two.
      (swap! *subscriber-trie* trie-delete (:topic-filter topic)
             (assoc topic :client-key key)))
    (cond
      ;; The id already belongs to another connection: the session is that
      ;; connection's now, so this one leaves it alone. The records are keyed
      ;; by client-id, not by connection, and a displaced connection's teardown
      ;; can run after its replacement has connected and started sending.
      ;; Clearing them would empty the new connection's window; parking would
      ;; hand its in-flight messages over, put its subscriptions in the offline
      ;; trie alongside the live ones, and start an expiry that discards the
      ;; session out from under it. forget-live! above has taken this key out
      ;; of the index, so whatever is still there is someone else.
      (and client-id (some? (live-connection client-id)))
      (do
        ;; Unless the replacement took over between the parking at the top and
        ;; the forget-live! after it: then this one parked a session that is
        ;; live again, and its entries come out of the offline trie.
        (doseq [topic subscribed-topics]
          (swap! *offline-trie* trie-delete (:topic-filter topic)
                 {:client-id client-id :qos (:qos topic) :topic-filter (:topic-filter topic)}))
        (swap! *clients* dissoc key))

      (not (keep-session? client))
      (do
        ;; A session that is not kept keeps nothing. Its in-flight records would
        ;; otherwise sit in *outbound* and *inflight* for the life of the
        ;; process, since only a reconnect under the same client-id ever reads
        ;; them again.
        (when client-id
          (swap! *outbound* dissoc client-id)
          (swap! *inflight* #(into {} (remove (fn [[[id _] _]] (= id client-id))) %)))
        (swap! *clients* dissoc key))

      :else
      (do
        ;; While attached, what it leaves unacknowledged goes to the cluster,
        ;; so it can come back anywhere; a broker on its own keeps it here.
        (hand-over-unacknowledged! client-id)
        ;; Parked rather than forgotten: its subscriptions went into the
        ;; offline trie at the top, before they left the live one.
        ;; Parking the session under its client-id happens once, not once per
        ;; subscribed topic: these two were inside the doseq above, so a
        ;; persistent session with no subscriptions was dropped instead of
        ;; kept, and CONNACK then reported session-present? false on reconnect.
        (swap! *clients* dissoc key)
        (swap! *clients* assoc client-id client)
        ;; And a timer to forget it again. Without one a version 5 session with
        ;; an expiry of five seconds lived as long as the broker did.
        (schedule-session-expiry! client-id (session-expiry-seconds client)))))
  (log/trace "REMOVE: Subscriber trie POST:" @*subscriber-trie*)
  (log/trace "REMOVE: Clients:" @*clients*))

(def view-lag-millis
  "How long another broker's copy of the cluster may still have a client
   connected here after it has gone: a copy of a publish that broker sends
   here for it in that time is queued here instead. See route. Not only
   the lag of that copy, but of the bridge too: a copy planned before the
   client left can wait behind thousands of others on a link that is
   behind, and two seconds lost that many for clients that reconnected
   under load. The longest a bridge keeps a copy waiting for word of its
   delivery, mqttkat.bridge/awaiting-limit-ms."
  60000)

(declare session-source)

(defonce ^:private recently-gone
  ;; client-id -> when its persistent session left this broker, while
  ;; attached; only the last view-lag-millis matter.
  (atom {}))

(defn- gone! [client-id]
  (let [now (System/currentTimeMillis)]
    (swap! recently-gone
           (fn [m]
             (let [m (assoc m client-id now)]
               (if (> (count m) 256)
                 (into {} (filter #(< (- now (long (val %))) (long view-lag-millis))) m)
                 m))))))

(defn- recently-gone? [client-id]
  (when-let [at (get @recently-gone client-id)]
    (< (- (System/currentTimeMillis) (long at)) (long view-lag-millis))))

(declare forget-catching-up!)

(declare origin-views)

(defn remove-client! [key]
  (swap! origin-views dissoc key)
  (let [client (get @*clients* key)]
    (when (and @session-source (:client-id client) (keep-session? client))
      (gone! (:client-id client)))
    (forget-catching-up! (:client-id client) (:connect-id client)))
  (remove-timer! key)
  ;; Both alias tables go with the connection, not with the session (§3.3.2.3.4).
  (forget-topic-aliases! key)
  ;; A session that is kept goes into the offline trie while it is still
  ;; live, and only then stops being live. A publish either finds it live
  ;; and delivers to it, or finds it not and queues for it from the offline
  ;; trie — never neither, which lost the message, and never both, since
  ;; the offline queue leaves out whoever the publish reached live
  ;; (queue-for-offline-sessions!, and the cluster's plan). Decided exactly
  ;; as the cond below decides to park: not for a connection that has been
  ;; displaced, whose session is someone else's.
  (let [client    (get @*clients* key)
        client-id (:client-id client)]
    (when (and client-id
               (contains? #{nil key} (live-connection client-id))
               (keep-session? client))
      (doseq [topic (:subscribed-topics client)]
        (swap! *offline-trie* trie-insert (:topic-filter topic)
               {:client-id client-id :qos (:qos topic) :topic-filter (:topic-filter topic)})))
    (forget-live! client-id key))
  ;; Only for a client that was actually there: remove-client! can be reached
  ;; twice for one connection, and a count that drifts is worse than no count.
  (let [disconnected
        (when (contains? @*clients* key)
          (MqttStat/clientDisconnected)
          ;; Which connection, not only which client: on a takeover this fires for
          ;; the displaced connection after the replacement has already announced
          ;; itself, and a watcher keeping a record would otherwise mark the new
          ;; connection as gone.
          (cond-> {:event      :client-disconnected
                   :client-id  (get-in @*clients* [key :client-id])
                   :connect-id (get-in @*clients* [key :connect-id])
                   :clients    (MqttStat/connectedClients)}
            ;; The interval as it stands now: a DISCONNECT may have
            ;; changed it (§3.14.2.2.2), and it decides how long the
            ;; session is kept.
            (>= (long (or (get-in @*clients* [key :protocol-version]) 4)) 5)
            (assoc :session-expiry-interval (session-expiry-seconds (get @*clients* key)))))]
    (park-or-drop! key)
    ;; Told last: the cluster records the disconnect on hearing it, and a
    ;; broker resuming the session elsewhere reads the queue once it sees the
    ;; session gone from here. Told before the hand-over, as it was, a resume
    ;; could read the queue before what was handed over reached it, and the
    ;; chaos run saw those messages delivered again at the next resume — QoS 2
    ;; twice.
    (some-> disconnected events/emit!)))


;; ── packet identifiers ───────────────────────────────────────────────────
;;
;; These used to come from one global core.async channel holding 1024 values,
;; taken with a blocking <!!. That had four problems, two of them permanent
;; hangs rather than slowdowns:
;;
;;   * it leaked. Identifiers came back only via PUBACK/PUBCOMP, so every
;;     client that disconnected with unacknowledged messages burned its
;;     identifiers for good. After 1024 of those the take blocked forever and
;;     QoS 1/2 delivery stopped broker-wide, silently.
;;   * any client could break it. PUBACK returned whatever identifier the
;;     client sent, unchecked: an unsolicited one overfilled a channel sized
;;     exactly 1024 and blocked that connection's reader thread forever, and a
;;     duplicate put a live identifier back into circulation so the next
;;     delivery reused it.
;;   * it was global, where §2.3.1 scopes identifiers to a connection — 1024
;;     shared out among every client instead of 65535 each.
;;   * the take blocked the publisher's fan-out thread, which cost about 21%
;;     under load and deadlocks outright if a client that both publishes and
;;     subscribes ends up waiting on an identifier its own unread PUBACKs
;;     would have released.
;;
;; *outbound* already records what is in flight for a client, keyed by
;; client-id and deliberately outliving the connection so a persistent session
;; can be redelivered on reconnect. So it is the allocator: one place that
;; knows what is outstanding, rather than a pool that has to be kept in step
;; with it.

(defn- outbound-atom
  "This client's outbound state, created on first use.

   The read comes first and is what almost every call does; the swap! runs once
   per client, on its first outstanding message."
  [client-id]
  (or (get @*outbound* client-id)
      (get (swap! *outbound*
                  (fn [registry]
                    (if (contains? registry client-id)
                      registry
                      (assoc registry client-id (atom {})))))
           client-id)))

(defn- existing-outbound
  "This client's outbound state if it has any, without creating it.

   For the paths that answer a client rather than send to one — releasing an
   identifier nobody issued must not conjure a session for a client that has
   gone."
  [client-id]
  (get @*outbound* client-id))

(defn queued-count
  "Messages the broker is holding for clients: in flight awaiting an
   acknowledgement, plus those waiting for a window slot.

   Here rather than in the two places that report it, so the shape of the
   outbound state stays this namespace's business."
  []
  (reduce (fn [acc a]
            (let [state @a]
              (+ acc (count (:inflight state)) (count (:pending state)))))
          0
          (vals @*outbound*)))

(defn- next-identifier
  "The next free identifier for this client, or nil if there is none.

   A plain wrapping counter is enough because `inflight-window` is far below
   65535: the counter cannot lap a live identifier. The containment check is
   belt and braces for a window raised carelessly."
  [{:keys [next-id inflight] :or {next-id 0}}]
  (loop [candidate (inc (mod next-id max-packet-identifier))
         tried     0]
    (cond
      (>= tried max-packet-identifier)  nil
      (contains? inflight candidate)    (recur (inc (mod candidate max-packet-identifier)) (inc tried))
      :else                             candidate)))

(defn- reserve
  "Record `msg` against a fresh identifier, or leave the state alone when it
   cannot be sent yet.

   It cannot be sent when the in-flight window is full — and also when anything
   is already waiting, unless this message is the head of that queue. MQTT
   3.1.1 §4.6 requires a client's messages to be delivered in the order they
   were published, and without the second check a fresh publish could take a
   slot the moment an acknowledgement freed one, overtaking everything queued
   behind it. That showed up as a handful of messages arriving early out of two
   hundred: the fan-out thread and the thread draining the queue on each
   acknowledgement were competing for the same slots."
  ([state msg] (reserve state msg false inflight-window))
  ([state msg from-pending?] (reserve state msg from-pending? inflight-window))
  ([state msg from-pending? window]
   (let [inflight (:inflight state {})]
     (if (or (>= (count inflight) (long window))
             (and (not from-pending?) (seq (:pending state)))
             ;; No identifier while another broker may still hold some of
             ;; this session's: see gate-identifiers!.
             (:gate state))
       state
       (if-let [id (next-identifier state)]
         (assoc state :next-id id :inflight (assoc inflight id msg))
         state)))))

(defn acquire-packet-identifier!
  "Reserve an identifier for `client-id` and record `msg` against it.

   Returns the identifier, or nil when the client already has `window`
   messages outstanding. Never blocks: a client that has stopped acknowledging
   is the caller's problem to handle, not a reason to park the thread doing the
   fan-out."
  ([client-id msg] (acquire-packet-identifier! client-id msg inflight-window))
  ([client-id msg window]
   (let [[before after] (swap-vals! (outbound-atom client-id) reserve msg false window)]
     (when (> (count (:inflight after)) (count (:inflight before)))
       (:next-id after)))))

(defn release-packet-identifier!
  "Retire `id` for `client-id`.

   Returns the message that was in flight under it, or nil if this identifier
   was never issued — an unsolicited or duplicate acknowledgement, which is
   then ignored rather than acted on. That check is the whole defence against
   a client corrupting the identifier space."
  [client-id id]
  (when-let [a (existing-outbound client-id)]
    (let [[before _] (swap-vals! a update :inflight dissoc id)]
      (get-in before [:inflight id]))))

(defn inflight-count
  "How many messages are outstanding for `client-id`."
  [client-id]
  (count (:inflight (some-> (existing-outbound client-id) deref))))

(defn pending-count
  "How many messages are waiting for a window slot for `client-id`."
  [client-id]
  (count (:pending (some-> (existing-outbound client-id) deref))))

(defonce redirector
  ;; (fn [client-id] -> {:server-reference :via :session-present?} or nil)
  ;; or nil. Installed by mqttkat.rama.cluster
  ;; when the broker is attached: where to send a version 5 client that has
  ;; just connected, if the cluster's policy says somewhere else (§4.13,
  ;; Use another server). nil, the broker on its own, takes every client.
  (atom nil))

(defn redirect-for
  "Where this CONNECT should be sent instead of taken, or nil.

   Only a version 5 client can be told — 3.1.1 has no reason code and no
   Server Reference to carry it — and never another broker's bridge, which
   came here because the cluster said this is where its client's
   subscriptions are."
  [{:keys [protocol-version client-id]}]
  (when-let [f @redirector]
    (when (and (>= (long (or protocol-version 0)) 5)
               (not (bridge/bridge? client-id)))
      (f client-id))))

(defonce session-source
  ;; {:resume    (fn [client-id] -> {:session :subscriptions :queued} or nil)
  ;;  :enqueue!  (fn [client-id msg key]) — key nil for a new one
  ;;  :dequeue!  (fn [client-id keys])
  ;;  :queued    (fn [client-id limit] -> [[key msg] ...]), the first `limit`
  ;;             queued now, oldest first
  ;;  :settled!  (fn [broker-id msg-keys])
  ;;  :takeover! (fn [broker-id client-id connect-id])} or nil.
  ;; Installed by mqttkat.rama.cluster when the broker is attached to a
  ;; cluster: sessions then live there, a client may come back to any
  ;; broker, and what is owed to it while it is away is queued there too —
  ;; so this broker queues nothing in memory for a session that is away,
  ;; hands over what a session leaves unacknowledged when it goes, and asks
  ;; on a CONNECT whether there is a session to take over and a connection
  ;; elsewhere to end. nil means a broker on its own, which parks and
  ;; queues in memory as it always did.
  (atom nil))

(declare queue-pending! flush-pending!)

(defn new-message-key
  "The name a QoS 1 or 2 publish is given where it enters the cluster: the
   time first, so a queue keyed by it comes back in the order the messages
   were published, then enough randomness that two in the same millisecond,
   here or on another broker, are two. It travels with every copy (see
   mqttkat.bridge/msg-key-property), and every broker that queues the
   message for a client queues it under this name, so that however many of
   them decide the client is away it is on the client's queue once."
  []
  (format "%013d-%s" (System/currentTimeMillis) (subs (str (java.util.UUID/randomUUID)) 0 8)))

(def reconcile-window-millis
  "How long after a persistent client connects its live deliveries are also
   taken off the cluster's queue: as long as another broker's copy of the
   cluster may still have it away, and queue for it what it is being sent
   here."
  10000)

(def redequeue-after-millis
  "How long after the first a second take-off follows: for a copy queued
   by a broker whose word arrived after the first."
  5000)

(defonce ^:private dequeue-batch
  ;; {client-id {key last?}}: take-offs of live deliveries waiting to go to
  ;; the cluster together. One append per message delivered to a client
  ;; that connected recently filled Rama's depot buffer under the chaos
  ;; run's churn.
  (atom {}))

(def dequeue-batch-millis
  "How long take-offs of live deliveries gather before they are recorded:
   one append per client per batch. At 250 ms, under a chaos run that kills
   publishers as well as subscribers, Rama's depot buffer filled."
  1000)

(declare landed! once-done!)

(defn- flush-dequeues! []
  (let [[batch _] (reset-vals! dequeue-batch {})]
    (when-let [{:keys [dequeue!]} @session-source]
      (doseq [[client-id ks] batch]
        (once-done! (dequeue! client-id (keys ks))
                    #(doseq [[k last?] ks :when last?]
                       (landed! client-id k)))))))

(defn- dequeue-soon!
  "Take `k` off `client-id`'s queue in the cluster with the next batch;
   `last?` when no other take-off of it follows."
  [client-id k last?]
  (let [[before _] (swap-vals! dequeue-batch update-in [client-id k] #(or % last?))]
    (when (empty? before)
      (at/after dequeue-batch-millis flush-dequeues! my-pool))))

(defonce ^:private origin-holds
  ;; {[origin msg-key] {:clients #{client-id ...} :at millis}}: messages
  ;; another broker forwarded here, and the clients here that keep their
  ;; sessions it was delivered to and that have not acknowledged it yet.
  ;; ::fanout stands in the set while the message is being delivered, so it
  ;; cannot empty before every client has been counted.
  (atom {}))

(defonce ^:private settle-batch
  ;; {origin #{msg-key ...}}: word to the forwarding broker waiting to go.
  (atom {}))

(def settle-batch-millis
  "How long word that forwarded messages are delivered gathers before it
   goes back to their broker: one message per broker per batch."
  100)

(def origin-hold-limit-millis
  "How long a forwarded message is held for its clients here before it is
   let go without them: the forwarding broker stops waiting at about this
   long too (mqttkat.bridge/awaiting-limit-ms)."
  60000)

(declare sweep-origin-holds!)

(defonce ^:private origin-holds-swept (atom 0))

(defn- flush-settled! []
  (let [now (System/currentTimeMillis)]
    (when (> (- now (long @origin-holds-swept)) 5000)
      (reset! origin-holds-swept now)
      (sweep-origin-holds!)))
  (let [[batch _] (reset-vals! settle-batch {})]
    (when-let [{:keys [settled!]} @session-source]
      (doseq [[origin ks] batch]
        (try
          (settled! origin ks)
          (catch Exception e
            (log/warn e "could not tell" origin "that" (count ks) "messages are delivered")))))))

(defn- settle-soon!
  "Tell `origin` with the next batch that `k` has reached everyone here."
  [origin k]
  (let [[before _] (swap-vals! settle-batch update origin (fnil conj #{}) k)]
    (when (empty? before)
      (at/after settle-batch-millis flush-settled! my-pool))))

(defn- release-origin-hold!
  "`member` — a client-id, or ::fanout — no longer holds the message `k`
   that `origin` forwarded here. The last one out tells `origin`, which has
   been keeping it in case this broker died first (see mqttkat.bridge,
   \"delivered, not only taken\")."
  [origin k member]
  (let [[before after] (swap-vals! origin-holds
                                   (fn [m]
                                     (if-let [{:keys [clients]} (get m [origin k])]
                                       (let [left (disj clients member)]
                                         (if (empty? left)
                                           (dissoc m [origin k])
                                           (assoc-in m [[origin k] :clients] left)))
                                       m)))]
    (when (and (contains? before [origin k]) (not (contains? after [origin k])))
      (settle-soon! origin k))))

(defn- hold-for-origin!
  "Count `client-id` among those holding `origin`'s message `k`."
  [origin k client-id]
  (swap! origin-holds (fn [m]
                        (if (contains? m [origin k])
                          (update-in m [[origin k] :clients] conj client-id)
                          m))))

(defn- sweep-origin-holds!
  "Let go of holds older than origin-hold-limit-millis, without word."
  []
  (let [cutoff (- (System/currentTimeMillis) (long origin-hold-limit-millis))]
    (swap! origin-holds (fn [m]
                          (if (some #(< (long (:at (val %))) cutoff) m)
                            (into {} (remove #(< (long (:at (val %))) cutoff)) m)
                            m)))))

(defn- delivering-for-origin
  "Call `deliver`, which delivers `msg` here, holding the message for its
   broker of origin — if it came over a bridge — until every client that
   keeps its session and was sent it has acknowledged it. Returns what
   `deliver` returns."
  [{origin ::origin k ::msg-key} deliver]
  (if-not (and origin k)
    (deliver)
    (do
      (swap! origin-holds (fn [m]
                            (if (contains? m [origin k])
                              (update-in m [[origin k] :clients] conj ::fanout)
                              (assoc m [origin k] {:clients #{::fanout}
                                                   :at      (System/currentTimeMillis)}))))
      (try
        (deliver)
        (finally
          (release-origin-hold! origin k ::fanout))))))

(def ^:dynamic *hand-offs*
  "While a publish is handled on a cluster: the writes to Rama that must land
   before its publisher is told the broker has it. Nil otherwise."
  nil)

(defn hand-off!
  "Note `fut`, a write of the message being published to somewhere that
   outlives this broker, so its acknowledgement waits for it. Returns `fut`.
   Outside a publish, nothing waits: a write a lost bridge copy makes later
   is for a message its publisher was told of long ago."
  [fut]
  (when (and fut *hand-offs*)
    (.add ^java.util.List *hand-offs* fut))
  fut)

(defn- once-done!
  "Run `f` once `fut` completes, however it does — at once when it is not a
   future."
  [fut f]
  (if (instance? CompletableFuture fut)
    (.whenComplete ^CompletableFuture fut (reify BiConsumer (accept [_ _ _] (f))))
    (f)))

(defn- once-handed-off!
  "Call `ack!` once every write in `held` has landed, or failed: a failure is
   logged where the write was made, and holding the acknowledgement for ever
   would only keep the publisher's slot. At once when there are none, which
   is every publish with nobody away and nobody behind."
  [^java.util.List held ack!]
  (if (.isEmpty held)
    (ack!)
    (.whenComplete (CompletableFuture/allOf (into-array CompletableFuture held))
                   (reify BiConsumer
                     (accept [_ _ _] (ack!))))))

(defn- settled!
  "A message has been acknowledged, or found expired: it is done. One from
   the cluster's queue comes off it there. One delivered live under its
   message key, to a client another broker may have taken for away and
   queued it for, comes off the queue too, now and once more a little
   later: the client has it, and a copy queued anywhere would be a second
   delivery on its next resume. Any other message was never on it. One
   another broker forwarded here is no longer held for it by this client."
  [client-id msg]
  (trace/trace! client-id msg "settled" (or (::cluster-key msg) ""))
  (when-let [origin (::origin msg)]
    (release-origin-hold! origin (::msg-key msg) client-id))
  (when-let [{:keys [dequeue!]} @session-source]
    (when-let [k (::cluster-key msg)]
      (cond-> (dequeue! client-id [k])
        ;; One reconciled is let go after its second take-off, below.
        (not (::reconcile? msg)) (once-done! #(landed! client-id k))))
    (when (::reconcile? msg)
      (when-let [k (::msg-key msg)]
        (trace/trace! client-id msg "taken off the cluster's queue: had live" k)
        (when-not (= k (::cluster-key msg))
          (dequeue-soon! client-id k false))
        (at/after redequeue-after-millis #(dequeue-soon! client-id k true) my-pool)))))

(defonce ^:private handing-over
  ;; client-id -> a future of its hand-over's writes to the cluster's queue,
  ;; until the cluster is told it disconnected: see handed-over.
  (atom {}))

(defn- hand-over-unacknowledged!
  "A session is going away while attached: whatever the broker was still
   to deliver to it goes to the cluster's queue, and nothing stays here.
   What came from that queue and is still waiting is on it already, and is
   left out; what is in flight, from there or delivered live, is put on
   with its packet identifier, and what was delivered live and is waiting
   is put on as it is, so a resume anywhere sends it (§4.4). The queue
   here is emptied either way: a resume, here or elsewhere, starts from the
   cluster's copy, and a second copy here would be delivered twice."
  [client-id]
  (when-let [{:keys [enqueue! dequeue!]} @session-source]
    (when-let [a (existing-outbound client-id)]
      (let [handed (volatile! #{})
            writes (java.util.ArrayList.)
            enqueue! (fn [& args]
                       (let [fut (apply enqueue! args)]
                         (when (instance? CompletableFuture fut)
                           (.add writes fut))
                         fut))
            [{:keys [pending inflight]} _]
            (swap-vals! a assoc :pending clojure.lang.PersistentQueue/EMPTY :inflight {})]
        ;; Nor, as a message, what the client has answered with a PUBREC: it
        ;; has it (§4.3.3), and sending it again would be a second delivery.
        ;; What is left of that exchange is the PUBREL, which goes on the
        ;; queue as one, under a key of its own, for a resume to send (§4.4).
        ;; It was let go as a PUBREL the client could do without. The client
        ;; could not: it holds the identifier until the PUBREL comes, and took
        ;; the next message sent under it, by a broker that had never heard
        ;; of it, for the one it had, and dropped it. A chaos run lost some
        ;; thousands of QoS 2 messages that way.
        (doseq [[identifier msg] (sort-by first inflight)
                :when (::released? msg)]
          (enqueue! client-id
                    {:topic (:topic msg) :qos 2 :packet-identifier identifier :released? true}
                    (or (::release-key msg)
                        (str (or (::msg-key msg) (::cluster-key msg) (new-message-key)) "-rel"))))
        (doseq [msg pending
                :when (::cluster-key msg)]
          (trace/trace! client-id msg "left on the cluster's queue at the hand-over" (::cluster-key msg)))
        (doseq [[identifier msg] (concat (sort-by first inflight) (map vector (repeat nil) pending))
                :when (not (::released? msg))
                ;; And what came from the cluster's queue and is still waiting
                ;; here stays where it is there, unsent.
                :when (or identifier (not (::cluster-key msg)))]
          ;; In flight with its identifier, so a resume sends it again under
          ;; the same one (§4.4) and the client can tell it is the one it may
          ;; already have. It went out under a new one, and a QoS 2 message
          ;; the client had taken, but not yet acknowledged when it dropped,
          ;; was delivered twice.
          ;; Under the message's own key, when it has one: another broker
          ;; that took the client for away may have queued it already, and
          ;; this is then the same entry, not a second.
          (let [k (or (::msg-key msg) (::cluster-key msg))]
            (trace/trace! client-id msg "handed over to the cluster's queue" (or identifier "") k)
            (vswap! handed conj msg)
            ;; The broker that forwarded it lets its copy go only once the
            ;; cluster has this one: the write can take seconds when Rama is
            ;; behind, and a broker that died meanwhile took it with it.
            (once-done! (enqueue! client-id
                                  (cond-> (select-keys msg [:topic :payload :qos :properties])
                                    (::queued-at msg) (assoc :queued-at (::queued-at msg))
                                    identifier        (assoc :packet-identifier identifier))
                                  k)
                        #(when-let [origin (::origin msg)]
                           (release-origin-hold! origin (::msg-key msg) client-id)))
            ;; One from the cluster's queue was on it without an identifier,
            ;; which is how it went out again under a new one: the chaos run
            ;; saw QoS 2 delivered twice to a client that moved to another
            ;; broker. Put back with its identifier above, it comes off under
            ;; the old key, if that is another; in that order, so a failure
            ;; in between repeats it rather than losing it.
            (when-let [old (::cluster-key msg)]
              (when-not (= old k)
                (dequeue! client-id [old])))))
        ;; Had by the client already, or on the cluster's queue from
        ;; before: either way no longer this broker's alone.
        (doseq [msg (concat (vals inflight) pending)
                :let [origin (::origin msg)]
                :when (and origin (not (contains? @handed msg)))]
          (release-origin-hold! origin (::msg-key msg) client-id))
        (when-not (.isEmpty writes)
          (swap! handing-over assoc client-id
                 (CompletableFuture/allOf (into-array CompletableFuture writes))))))))

(defn handed-over
  "Once what hand-over-unacknowledged! last put on the cluster's queue for
   `client-id` has landed: a future, taken here by whoever tells the cluster
   the client has gone, so that it says so after it. Nil when nothing was
   handed over."
  [client-id]
  (let [[before _] (swap-vals! handing-over dissoc client-id)]
    (get before client-id)))

(declare restore-release! restore-message!)

(defn- restore-queued!
  "Put a message from the cluster's queue on `client-id`'s outbound state: in
   flight under the identifier it was sent with, when hand-over-unacknowledged!
   recorded one and it is free here, so the resume's redelivery sends it again
   as the same message (§4.4); otherwise on the queue, for a new identifier.
   False when it stays on the cluster's queue for a later read instead."
  [client-id msg]
  (if (:released? msg)
    (restore-release! client-id msg)
    (restore-message! client-id msg)))

(defn- restore-message! [client-id msg]
  (let [identifier (:packet-identifier msg)
        msg        (dissoc msg :packet-identifier)
        placed?    (when identifier
                     (let [[before after]
                           (swap-vals! (outbound-atom client-id)
                                       (fn [state]
                                         (if (contains? (:inflight state) identifier)
                                           state
                                           (assoc-in state [:inflight identifier] msg))))]
                       (not (identical? before after))))]
    (or placed? (queue-pending! client-id msg))))

(defn- restore-release!
  "A PUBREL a hand-over left on the cluster's queue: in flight here under its
   identifier, released, so that the resume sends the PUBREL (see
   redeliver-inflight!) and the PUBCOMP takes it off the queue. False, and
   left on the cluster's queue, when the identifier is in use here already:
   a later read sends it once the identifier is free (resend-handed-over!).
   Taken off instead, as it once was, the client went on holding the
   identifier for a PUBREL that never came, and took the next message sent
   under it for one it had."
  [client-id {:keys [packet-identifier] :as msg}]
  (let [k (::cluster-key msg)
        [before after]
        (swap-vals! (outbound-atom client-id)
                    (fn [state]
                      (if (contains? (:inflight state) packet-identifier)
                        state
                        (assoc-in state [:inflight packet-identifier]
                                  {:topic (:topic msg) :qos 2 ::released? true ::release-key k}))))]
    (not (identical? before after))))

(def catch-up-reads-millis
  "When, after a persistent session resumes here, its queue on the cluster
   is read again. The read on CONNECT has what had landed by then; a broker
   that took the client for away a moment longer queues after it, and with
   Rama behind its write can land seconds later. Read once, that message
   waited on the queue for the client's next resume, and a chaos run that
   reconnected every persistent subscriber at the end of its load counted
   it lost. Read up to a minute on, since that run had some land after ten
   seconds; and for as long, the client's live deliveries are taken off the
   queue too, as in its first reconcile-window-millis (see keyed)."
  [2000 5000 10000 20000 40000 60000])

(defonce ^:private catching-up
  ;; {client-id {:connect-id c :had {key landed} :reconcile-until millis
  ;;             :reading? :again? :scheduled?}}: a persistent session
  ;; connected here, for as long as it is, whose queue on the cluster is
  ;; read again — on the timer after a resume, and whenever the cluster
  ;; says something new is on it (nudged!) — and every key it has had from
  ;; there or live under a message key while reconciled, which a read
  ;; leaves out. Each with when its take-off from the queue landed, nil
  ;; until it has: the key is let go by the first read begun after that
  ;; which does not find it (end-read!). Let go sooner, a read that was
  ;; under way found it and sent it again.
  (atom {}))

(defn- had
  "`had` with `ks` in it, those already there left as they are: a take-off
   that landed first is not undone by the note that it was sent."
  [had & ks]
  (reduce (fn [h k] (if (contains? h k) h (assoc h k nil))) (or had {}) ks))

(defn- note-had! [client-id k]
  (when (contains? @catching-up client-id)
    (swap! catching-up (fn [m] (if (contains? m client-id)
                                 (update-in m [client-id :had] had k)
                                 m)))))

(defn- landed!
  "`k`'s take-off from `client-id`'s queue on the cluster has landed: see
   catching-up."
  [client-id k]
  (when (contains? @catching-up client-id)
    (let [now (System/currentTimeMillis)]
      ;; Whether or not it is noted yet: the note can come after.
      (swap! catching-up (fn [m] (if (contains? m client-id)
                                   (assoc-in m [client-id :had k] now)
                                   m))))))

(defn- forget-catching-up!
  "The connection `connect-id` has gone: nothing more is read for it."
  [client-id connect-id]
  (when (and client-id connect-id)
    (swap! catching-up (fn [m] (if (= connect-id (get-in m [client-id :connect-id]))
                                 (dissoc m client-id)
                                 m)))))

(defn- note-delivered!
  "A live delivery reconciled with the cluster's queue is on its way to
   the client: a read of the queue leaves its copy there alone. Only once
   it is: noted before, one refused by a full queue here was taken for
   had, and its copy on the cluster's queue was never sent."
  [client-id msg]
  (when (::reconcile? msg)
    (when-let [k (::msg-key msg)]
      (note-had! client-id k))))

(def hand-over-wait-millis
  "How long a CONNECT that resumes a session another broker still has
   connected waits for that broker to say it has gone, before it reads the
   session's queue: it says so once what it handed over is on the queue."
  10000)

(defn- connected-elsewhere? [session my-broker-id]
  (boolean (and (:connected? session)
                (:broker-id session)
                (not= (:broker-id session) my-broker-id))))

(defn- once-gone
  "`client-id`'s session as the cluster has it once the connection `session`
   records on another broker has ended there, read again every 100 ms for
   hand-over-wait-millis at most; at once when that broker is not in the
   cluster any more, since it will not say, and after a few seconds when it
   is still listed but refuses every connection (bridge/peer-gone?): a
   killed broker stays listed for ten minutes. Read before then, the queue
   lacked what that broker was handing over, and a catch-up read found it
   later, after the identifiers it was in flight under had gone to other
   messages: a QoS 2 message went out again under a new one, and a PUBREL
   was never sent. A load run that moved every persistent session at its
   end delivered hundreds of them twice."
  [resume client-id session my-broker-id]
  (let [deadline (+ (System/currentTimeMillis) (long hand-over-wait-millis))
        same?    (fn [s] (and (connected-elsewhere? s my-broker-id)
                              (= (:connect-id s) (:connect-id session))))]
    (loop []
      (let [{s :session :as resumed} (resume client-id)]
        (cond
          (not (same? s))                         resumed
          (bridge/peer-gone? (:broker-id s))      resumed
          (> (System/currentTimeMillis) deadline)
          (do (log/warn "session" client-id "is still recorded on" (:broker-id s)
                        "after" hand-over-wait-millis "ms - resuming it without its hand-over,"
                        "and giving out no identifiers until it lands")
              (assoc resumed ::waited-out? true))
          :else (do (Thread/sleep 100) (recur)))))))

(def identifier-gate-millis
  "How long a session resumed without its hand-over (see once-gone) gives
   out no packet identifiers while the broker it left still has it, at
   most. Past that, the broker is taken to have nothing to hand over."
  60000)

(defn- open-gate!
  "Give out identifiers for `client-id` again, and send what waited."
  [client-id]
  (let [[before _] (swap-vals! (outbound-atom client-id) dissoc :gate)]
    (when (:gate before)
      (when-let [key (live-connection client-id)]
        (flush-pending! key client-id)))))

(declare read-soon!)

(defn- gate-identifiers!
  "`client-id` resumed here while `session` still had it on another broker,
   whose hand-over had not landed: give out no identifiers until it has, or
   the other broker has left the cluster, or identifier-gate-millis has
   gone by. The hand-over carries the identifiers the client holds messages
   under; given out here first, they went to new messages, and the client
   took a new QoS 2 message under an identifier it still held for the old
   one as that one, and never had it. What would have taken one waits on
   the pending queue.

   Once the record moves on, the queue is read first, so the hand-over
   takes its identifiers, and that read opens the gate (read-again!)."
  [resume client-id session my-broker-id]
  (swap! (outbound-atom client-id) assoc :gate :closed)
  (future
    (try
      (let [deadline (+ (System/currentTimeMillis) (long identifier-gate-millis))
            same?    (fn [s] (and (connected-elsewhere? s my-broker-id)
                                  (= (:connect-id s) (:connect-id session))))]
        (loop []
          (let [gate (:gate @(outbound-atom client-id))]
            (cond
              (nil? gate) nil

              (> (System/currentTimeMillis) deadline)
              (do (log/warn "session" client-id "still has no hand-over from" (:broker-id session)
                            "after" identifier-gate-millis "ms more - giving out identifiers again")
                  (open-gate! client-id))

              ;; Landed, and the read that opens the gate asked for: waited
              ;; on here only in case that read never comes.
              (= :landed gate) (do (Thread/sleep 250) (recur))

              :else
              (let [s (:session (resume client-id))]
                (if (or (not (same? s)) (bridge/peer-gone? (:broker-id s)))
                  (let [cid (get-in @catching-up [client-id :connect-id])]
                    (swap! (outbound-atom client-id) #(if (:gate %) (assoc % :gate :landed) %))
                    (if cid
                      (read-soon! client-id cid 0)
                      (open-gate! client-id))
                    (recur))
                  (do (Thread/sleep 250) (recur))))))))
      (catch Throwable t
        (log/warn t "could not follow the hand-over of" client-id "- giving out identifiers again")
        (open-gate! client-id)))))

(defn adopt-session!
  "Take over `client-id`'s session from the cluster, on its CONNECT.

   Three things, each only when it applies. If the client is connected on
   another broker, that broker is told to drop it — §3.1.4's takeover,
   across brokers. If the session is persistent, it is parked here from the
   cluster's copy — the record and the offline trie, exactly as
   remove-client! leaves a session that went away from this broker — so
   that add-client! resumes it like any other; a copy already parked here
   is replaced, since the cluster's is the one that was kept up to date
   while the client was elsewhere. And what is queued for it is put on the
   broker's own queue, so that flush-pending! sends it, each message still
   carrying the key it has on the cluster's queue: it comes off there when
   the client acknowledges it, not before.

   Returns true when the cluster knew the session."
  [client-id]
  ;; Never for another broker's bridge. It is not a client session: it has
  ;; nothing to resume, and every broker's bridges share one client id — one
  ;; per peer it connects to — so to the cluster's session table a broker
  ;; with two peers looks like one client connected in two places, and the
  ;; takeover below had its bridges knock each other off in turn.
  (when-let [{:keys [resume takeover! my-broker-id]} (when-not (bridge/bridge? client-id) @session-source)]
    (when-let [{:keys [session subscriptions queued] ::keys [waited-out?] :as adopted}
               (let [{:keys [session] :as resumed} (resume client-id)]
                 (if (connected-elsewhere? session my-broker-id)
                   (do (log/info "session" client-id "is connected on" (:broker-id session) "- taking it over")
                       (takeover! (:broker-id session) client-id (:connect-id session))
                       (if (false? (:clean-session? session))
                         (once-gone resume client-id session my-broker-id)
                         resumed))
                   resumed))]
      (when (false? (:clean-session? session))
        (let [entries (set (vals subscriptions))]
          (when-let [parked (get @*clients* client-id)]
            (doseq [{:keys [topic-filter qos]} (:subscribed-topics parked)]
              (swap! *offline-trie* trie-delete topic-filter
                     {:client-id client-id :qos qos :topic-filter topic-filter})))
          (doseq [{:keys [topic-filter qos]} entries]
            (swap! *offline-trie* trie-insert topic-filter
                   {:client-id client-id :qos qos :topic-filter topic-filter}))
          (swap! *clients* assoc client-id {:client-id         client-id
                                            :clean-session?    false
                                            :subscribed-topics entries})
          (log/info "session" client-id "taken over from the cluster:"
                    (count entries) "subscriptions," (count queued) "queued"))
        ;; Had: what was restored here. What stays on the cluster's queue
        ;; is left for the reads that follow.
        (let [restored (into [] (keep (fn [[k msg]]
                                        (if (restore-queued! client-id (assoc msg
                                                                              ::queued-at (:queued-at msg)
                                                                              ::cluster-key k))
                                          (do (trace/trace! client-id msg "restored from the cluster's queue"
                                                            k (or (:packet-identifier msg) ""))
                                              k)
                                          (trace/trace! client-id msg "left on the cluster's queue at the resume" k))))
                             queued)]
          (swap! catching-up assoc client-id {:had (zipmap restored (repeat nil))}))
        (if waited-out?
          (gate-identifiers! resume client-id (:session adopted) my-broker-id)
          (swap! (outbound-atom client-id) dissoc :gate)))
      true)))

(declare deliver-queued!)

(def catch-up-again-millis
  "After the last of catch-up-reads-millis, how often the queue is read
   again while a read finds more on it than the broker could take: a
   session that comes back to more than pending-limit messages gets the
   rest as its client works through the first."
  2000)

(defn- begin-read!
  "When a read of `client-id`'s queue may begin now for `connect-id`, the
   moment it began; nil when it may not. One at a time, so that what a read
   leaves out is what no read under way can still find. One asked for while
   another is on is made once that one is done."
  [client-id connect-id]
  (let [now (System/currentTimeMillis)
        [before after]
        (swap-vals! catching-up
                    (fn [m]
                      (let [{c :connect-id :keys [reading?] :as e} (get m client-id)]
                        (cond
                          (not= connect-id c) m
                          reading?            (update m client-id assoc :again? true :scheduled? false)
                          :else
                          (assoc m client-id
                                 (assoc e :reading? true :again? false :scheduled? false))))))]
    (when (and (get-in after [client-id :reading?])
               (not (get-in before [client-id :reading?])))
      now)))

(defn- end-read!
  "A read for `connect-id`, begun at `began`, is done, having sent the keys
   `took`. Lets go of the keys whose take-off landed before it began and
   that it did not find, `gone?`: the cluster has taken them off. Landed
   alone is not enough. A take-off tried again is done once Rama's depot
   has it (see mqttkat.rama.cluster/send-batch!), and the queue a read
   finds is the topology's, which can be far behind that: let go when it
   landed, a key was found on the queue still, and sent again, on every
   read until the topology caught up. A load run delivered QoS 2 messages
   to one client ten times. Whether another read was asked for meanwhile."
  [client-id connect-id began took gone?]
  (let [[before _] (swap-vals! catching-up
                               (fn [m] (if (= connect-id (get-in m [client-id :connect-id]))
                                         (update m client-id
                                                 #(-> %
                                                      (assoc :reading? false :again? false)
                                                      (update :had (fn [h]
                                                                     (into {}
                                                                           (remove (fn [[k landed]]
                                                                                     (and landed
                                                                                          (< (long landed) (long began))
                                                                                          (gone? k))))
                                                                           h)))
                                                      (update :had (fn [h] (apply had h took)))))
                                         m)))]
    (and (= connect-id (get-in before [client-id :connect-id]))
         (boolean (get-in before [client-id :again?])))))


(defn- read-again!
  "Deliver what is on `client-id`'s queue on the cluster and not yet had,
   while `connect-id` is still the connection it is for. Whether it left
   any of it there — a full queue here refused it — so that it is worth
   reading again. False when another read is under way, which reads again
   once it is done."
  [client-id connect-id]
  (if-let [began (when (:queued @session-source) (begin-read! client-id connect-id))]
    (let [took    (volatile! #{})
          gone?   (volatile! (constantly false))
          landed? (= :landed (some-> (existing-outbound client-id) deref :gate))]
      (try
        (let [{:keys [queued]} @session-source
              ;; The head of it: what was had and is still there, and as
              ;; much again as the broker's own queue holds.
              limit   (+ (count (get-in @catching-up [client-id :had])) pending-limit)
              entries (queued client-id limit)
              ;; What it has had by now, not when the read began: a live
              ;; delivery noted during a slow read is on the queue still,
              ;; and went out twice.
              had     (get-in @catching-up [client-id :had])
              fresh   (remove #(contains? had (first %)) entries)
              found   (set (map first entries))
              ;; The queue comes in key order, so one cut off at `limit`
              ;; says nothing of the keys after its last.
              upto    (when (>= (count entries) (long limit)) (first (last entries)))]
          (vreset! gone? (fn [k] (and (not (contains? found k))
                                      (or (nil? upto) (neg? (compare k upto))))))
          (vreset! took (deliver-queued! client-id connect-id fresh (keys had)))
          ;; Begun once the hand-over had landed: it has taken its
          ;; identifiers, and the rest may have theirs. See gate-identifiers!.
          (when landed? (open-gate! client-id))
          (< (count @took) (count fresh)))
        (catch Throwable t
          (log/warn t "could not read the queue of" client-id "again")
          false)
        (finally
          (when (end-read! client-id connect-id began @took @gone?)
            (read-soon! client-id connect-id 0)))))
    false))

(defn- read-soon!
  "Read `client-id`'s queue again after `ms`, and then for as long as a
   read leaves some of it behind."
  [client-id connect-id ms]
  (at/after ms
            #(future
               (when (read-again! client-id connect-id)
                 (read-soon! client-id connect-id catch-up-again-millis)))
            my-pool))

(defn- catch-up-after!
  "Read `client-id`'s queue again after each of `gaps`, and then for as
   long as a read leaves some of it behind."
  [client-id connect-id gaps]
  (at/after (first gaps)
            #(future
               (let [left? (read-again! client-id connect-id)]
                 (cond
                   (next gaps) (catch-up-after! client-id connect-id (next gaps))
                   left?       (read-soon! client-id connect-id catch-up-again-millis))))
            my-pool))

(defn catch-up!
  "`client-id` connected as `connect-id`. A persistent session resumed from
   the cluster has its queue there read again, at catch-up-reads-millis, and
   after them while there is more on it than this broker could take; and,
   resumed or not, whenever the cluster says more has been put on it."
  [client-id connect-id]
  (when (:queued @session-source)
    (let [now     (System/currentTimeMillis)
          kept?   #(keep-session? (get @*clients* (live-connection client-id)))
          [before _]
          (swap-vals! catching-up
                      (fn [m] (cond
                                (contains? m client-id)
                                (update m client-id assoc
                                        :connect-id connect-id
                                        :reconcile-until (+ now (long (last catch-up-reads-millis))))
                                (kept?) (assoc m client-id {:connect-id connect-id :had {}})
                                :else   m)))]
      (when (contains? before client-id)
        (catch-up-after! client-id connect-id
                         (map - catch-up-reads-millis (cons 0 catch-up-reads-millis)))))))

(defn nudged!
  "The cluster says something was put on `client-id`'s queue while it was
   connected here, after it read it: read it again, if the client is still
   here with a session that is kept. Once for any number of these until the
   read begins."
  [client-id]
  (let [[before after]
        (swap-vals! catching-up
                    (fn [m] (if (and (get-in m [client-id :connect-id])
                                     (not (get-in m [client-id :scheduled?])))
                              (assoc-in m [client-id :scheduled?] true)
                              m)))]
    (when (and (get-in after [client-id :scheduled?])
               (not (get-in before [client-id :scheduled?])))
      (read-soon! client-id (get-in after [client-id :connect-id]) 0))))

(declare send-buffer send-publish! receive-maximum-of)

(defn- resend-handed-over!
  "A message, or a PUBREL, a hand-over put on the cluster's queue with the
   identifier it was in flight under, found there while the client is
   connected here: in flight here under the same identifier and sent at
   once, as redeliver-inflight! sends one restored on the resume. Put on the
   queue here instead, as it was, it went out under a new identifier, and a
   QoS 2 message the client had and had not yet completed was delivered to
   it twice; and a PUBREL went in flight here and was never sent, so the
   client held its identifier and took the next message under it for the
   one it had.

   False, and nothing done, while the identifier is in use here or the
   client's window is full: it stays on the cluster's queue for the next
   read. The broker gave the identifier to another message before it knew."
  [key client-id k {:keys [packet-identifier released?] :as msg}]
  (let [entry (if released?
                {:topic (:topic msg) :qos 2 ::released? true ::release-key k}
                (assoc (dissoc msg :packet-identifier)
                       ::queued-at (:queued-at msg)
                       ::cluster-key k))
        [before after]
        (swap-vals! (outbound-atom client-id)
                    (fn [{:keys [inflight] :as state}]
                      (if (or (contains? inflight packet-identifier)
                              (>= (count inflight) (long (receive-maximum-of key))))
                        state
                        (assoc-in state [:inflight packet-identifier] entry))))]
    (when-not (identical? before after)
      (trace/trace! client-id msg "resent under its identifier" k packet-identifier)
      (if released?
        (send-buffer [key] (MqttPubRel/encode {:packet-type       :PUBREL
                                               :packet-identifier packet-identifier}))
        (when-not (send-publish! key (assoc entry :duplicate? true) packet-identifier)
          (release-packet-identifier! client-id packet-identifier)
          (settled! client-id entry)))
      true)))

(defn deliver-queued!
  "Send a connected client what the cluster queued for it while it was taken
   for away: `queued` as the cluster's queue has it, [key msg] oldest first.
   Only while `connect-id` is still the connection here — one that has gone
   or been replaced leaves the queue to the next resume, which reads it
   whole. Each message goes on the broker's own queue as adopt-session! puts
   it there, carrying its key, so it comes off the cluster's queue when the
   client acknowledges it; one handed over with an identifier is sent again
   under it (resend-handed-over!). A key already here, waiting or in flight,
   or in `taken` — the keys an earlier call for the same restatement sent —
   is left out, so reading the queue twice sends nothing twice.

   Nothing before the connection's CONNACK (§3.2.0-1): a restatement can
   read the queue of a connection whose CONNECT is still waiting on its
   record, and a handed-over message went out ahead of the CONNACK. It
   stays on the cluster's queue for the reads catch-up! starts after it.

   Returns the keys it took."
  [client-id connect-id queued taken]
  (let [key (live-connection client-id)]
    (if-not (and key
                 (= connect-id (get-in @*clients* [key :connect-id]))
                 (not (awaiting-connack? key)))
      #{}
      ;; One call at a time per client: two readers of the queue — a
      ;; restatement's and a nudge's — each found the other's messages not
      ;; yet here, and both sent them.
      (locking (outbound-atom client-id)
      (let [{:keys [pending inflight]} (some-> (existing-outbound client-id) deref)
            ;; And one delivered here live under the same message key: the
            ;; broker that queued it had the client away, and it was not.
            here  (into (into (set taken)
                              (when (= connect-id (get-in @catching-up [client-id :connect-id]))
                                (keys (get-in @catching-up [client-id :had]))))
                        (mapcat (juxt ::cluster-key ::msg-key ::release-key))
                        (concat pending (vals inflight)))
            queued? (volatile! false)
            ;; A full queue here refuses the rest, which stay on the
            ;; cluster's for the next read or the next resume.
            took  (into #{}
                        (keep (fn [[k msg]]
                                (cond
                                  (contains? here k)
                                  (trace/trace! client-id msg "left on the cluster's queue: had here" k)

                                  (:packet-identifier msg)
                                  (if (resend-handed-over! key client-id k msg)
                                    k
                                    (trace/trace! client-id msg "left on the cluster's queue: identifier in use"
                                                  k (:packet-identifier msg)))

                                  ;; Half the queue here at most, so live
                                  ;; deliveries still find room; the rest
                                  ;; stays for the next read.
                                  (and (< (long (pending-count client-id))
                                          (quot (long pending-limit) 2))
                                       (queue-pending! client-id (assoc msg
                                                                        ::queued-at (:queued-at msg)
                                                                        ::cluster-key k))
                                       (vreset! queued? true))
                                  k

                                  :else
                                  (trace/trace! client-id msg "left on the cluster's queue: full here" k))))
                        queued)]
        (doseq [[k msg] queued
                :when (contains? took k)]
          (trace/trace! client-id msg "taken from the cluster's queue" k))
        (when (seq took)
          ;; Had, whoever read them: a read under way could find them
          ;; again once they are acknowledged, before the take-off lands.
          (swap! catching-up (fn [m] (if (= connect-id (get-in m [client-id :connect-id]))
                                       (update-in m [client-id :had] #(apply had % took))
                                       m)))
          (log/info "delivering" (count took) "messages queued for" client-id "while it was taken for away"
                    (str "- from " (first (sort took)))))
        (when @queued?
          (flush-pending! key client-id))
        took)))))

(defn read-queue!
  "Read `client-id`'s queue on the cluster for `connect-id` after each of
   `delays`, as a nudge does — one read at a time, leaving out what it has
   had — if it is a session read that way. False, and nothing done, if it
   is not."
  [client-id connect-id & delays]
  (if (= connect-id (get-in @catching-up [client-id :connect-id]))
    (do (doseq [ms delays] (read-soon! client-id connect-id ms))
        true)
    false))

(defn queue-pending!
  "Hold `msg` for `client-id` until a window slot frees up.

   Returns true if it was queued, false if this client's pending queue is full
   too — at which point the message is refused, which is the only honest thing
   left: it has not been delivered and nothing is pretending otherwise."
  [client-id msg]
  (let [[before after]
        (swap-vals! (outbound-atom client-id)
                    (fn [state]
                      (if (>= (count (:pending state)) pending-limit)
                        state
                        ;; PersistentQueue, not a vector: this is a FIFO whose
                        ;; head is removed once per acknowledgement, and
                        ;; dropping the head of a vector copies the whole
                        ;; thing. At a 4096-deep queue that copy, inside a
                        ;; swap! on a contended atom, cost about 6x the
                        ;; broker's publish throughput.
                        (update state :pending
                                (fnil conj clojure.lang.PersistentQueue/EMPTY)
                                ;; Stamped on the way in, so the wait can be
                                ;; subtracted from the Message Expiry Interval
                                ;; on the way out (§3.3.2.3.3). Namespaced and
                                ;; outside :properties, so it cannot reach the
                                ;; wire — forwardable-properties would not pass
                                ;; it even if it tried.
                                ;; Unless it was stamped already: a message
                                ;; that waited in the cluster's queue has
                                ;; been waiting since then, not since now.
                                (update msg ::queued-at #(or % (System/currentTimeMillis)))))))]
    (> (count (:pending after)) (count (:pending before)))))

(defn take-pending!
  "Reserve an identifier for the next message waiting on `client-id`'s window.

   Returns [identifier msg], or nil when nothing is waiting or the window is
   still full. Called as acknowledgements come back, so the queue drains at
   exactly the rate the client is acknowledging."
  ([client-id] (take-pending! client-id inflight-window))
  ([client-id window]
   (let [[before after]
         (swap-vals! (outbound-atom client-id)
                     (fn [state]
                       (if-let [msg (peek (:pending state))]
                         ;; from-pending?: this message *is* the head, so the
                         ;; queue being non-empty must not block it.
                         (let [reserved (reserve state msg true window)]
                           (if (identical? reserved state)
                             state                    ; window still full
                             (update reserved :pending pop)))
                         state)))]
     (when (< (count (:pending after)) (count (:pending before)))
       [(:next-id after) (peek (:pending before))]))))

(defn drop-unsubscribed-pending!
  "Take off `client-id`'s pending queue what only `removed` subscriptions
   wanted, now that the client has unsubscribed from them.

   §3.10.4 in both 3.1.1 and 5.0: the server MUST stop adding messages and
   MUST complete the QoS 1 and 2 deliveries it has started, and MAY go on
   delivering what is buffered. So :inflight is left alone — those have
   identifiers and the client is owed their acknowledgement flow — and only
   :pending, which the client has never seen, is trimmed.

   A queued message records the topic it was published to, not the
   subscription that matched it, so it is kept whenever `remaining` still
   matches that topic: overlapping filters are ordinary, and unsubscribing
   from one must not cost a message another still asks for. A message a
   removed shared subscription matches is kept as well: it was picked for
   this member on the group's behalf, no other member will be sent it, and
   dropping it would lose it for the whole group.

   Returns the number dropped."
  [client-id removed remaining]
  (let [trie-of     (fn [subs]
                      (reduce #(trie-insert %1 (:topic-filter %2) %2) (tr/make-trie) subs))
        wanted      (fn [trie topic]
                      (seq (sieve-dollar topic (trie-matching-vals trie topic))))
        {shared true ordinary false} (group-by #(some? (:share-group %)) removed)
        ordinary    (trie-of ordinary)
        keep-anyway (trie-of (concat remaining shared))
        drop?       (fn [{:keys [topic]}]
                      (and topic
                           (wanted ordinary topic)
                           (not (wanted keep-anyway topic))))]
    (if-let [a (when (seq ordinary) (existing-outbound client-id))]
      (let [[before after]
            (swap-vals! a (fn [state]
                            (if (some drop? (:pending state))
                              (update state :pending
                                      #(into clojure.lang.PersistentQueue/EMPTY
                                             (remove drop?) %))
                              state)))
            dropped (when-not (identical? before after)
                      (filter drop? (:pending before)))]
        ;; One from the cluster's queue would otherwise be sent again on the
        ;; client's next resume, to a subscription it no longer has.
        (doseq [msg dropped] (settled! client-id msg))
        (count dropped))
      0)))

(declare send-buffer)

(defn receive-maximum-of
  "How many QoS 1 or 2 messages this client will take at once (§3.1.2.11.3).

   The broker's own window is the ceiling: a client asking for more than the
   broker is willing to hold gets the broker's number, and one asking for less
   gets its own. A client that says nothing means 65,535, which the ceiling
   then reduces to the window this broker always used."
  [client-key]
  (let [asked (get-in @*clients* [client-key :properties :receive-maximum])]
    (if asked
      (min (long asked) inflight-window)
      inflight-window)))

(defn maximum-packet-size-of
  "The largest packet this client agreed to be sent, or nil for no limit
   (§3.1.2.11.4).

   Absent means no limit — §3.1.2.11.4 says the client imposes none, not that
   it wants the default. There is nothing to compare against in that case,
   which is also the fast path for every 3.1.1 client and most version 5 ones."
  [client-key]
  (get-in @*clients* [client-key :properties :maximum-packet-size]))

(defn too-large-for?
  "Whether a packet of `size` bytes may not be sent to this client."
  [client-key ^long size]
  (when-let [limit (maximum-packet-size-of client-key)]
    (> size (long limit))))

(defn protocol-version-of
  "Which dialect to answer this subscriber in.

   Four unless its CONNECT said otherwise, which is also the answer for a key
   that has gone: a delivery to a client that is no longer there is dropped
   further down either way."
  [client-key]
  (long (get-in @*clients* [client-key :protocol-version] 4)))


(defn valid-topic-filter?
  "Whether `f` is a topic filter the broker can honour (§4.7.1).

   Both wildcards take a whole level. `#` matches this level and every one
   below it, so nothing may follow it — `sport/#/tennis` names levels that `#`
   has already consumed — and `sport#` is a level that is neither a literal
   name nor a wildcard. `+` is the same rule without the tail: a level is `+`
   or it is not.

   The broker used to accept all of these and answer Success. That is worse
   than refusing them: the subscription goes into the trie, matches by accident
   or not at all, and the client has been told it worked.

   Length is not checked here. §4.7.3 caps a filter at 65,535 bytes, which the
   wire format enforces on the way in — it is length-prefixed with two bytes."
  [^String f]
  (boolean
   (and f
        (pos? (.length f))
        (every? (fn [^String level]
                  (or (not (or (.contains level "#") (.contains level "+")))
                      (= level "+")
                      (= level "#")))
                (str/split f #"/" -1))
        ;; -1 keeps trailing empty levels, so `a/` splits to ["a" ""] and the
        ;; index below is the real one.
        (let [levels (str/split f #"/" -1)]
          (or (not (some #(= "#" %) levels))
              (= "#" (last levels)))))))

(def share-prefix "$share/")

(defn parse-subscription-filter
  "Split `$share/{group}/{filter}` into its parts (§4.8.2).

   Returns {:filter <as sent> :topic-filter <what matches topics> :share-group
   <name>}, or nil when the share is malformed. An ordinary filter comes back
   with :topic-filter equal to :filter and no group.

   The distinction matters because the two strings are used for different
   things. `$share/workers/a/#` never matches the topic `a/b` — it starts with
   a literal $share level — so what goes into the trie is the inner filter,
   while the original is what the client will name when it unsubscribes.

   A group name may not be empty and may not contain /, + or #: it is a name,
   not a filter, and allowing a wildcard in it would make two different groups
   collide."
  [^String subscription-filter]
  (if-not (and subscription-filter (.startsWith subscription-filter share-prefix))
    (when (valid-topic-filter? subscription-filter)
      {:filter subscription-filter :topic-filter subscription-filter})
    (let [rest  (subs subscription-filter (count share-prefix))
          slash (.indexOf rest "/")]
      (when (pos? slash)
        (let [group (subs rest 0 slash)
              inner (subs rest (inc slash))]
          (when (and (seq group)
                     (valid-topic-filter? inner)
                     (not (re-find #"[/+#]" group)))
            {:filter subscription-filter :topic-filter inner :share-group group}))))))

(defn subscription-filter-error
  "Why the broker cannot accept `subscription-filter`, as the reason code it
   disconnects with, or nil when the filter is fine.

   A malformed filter is a protocol violation, not a refusal (§4.7.1, §4.8.2).
   0x8F Topic Filter Invalid is for a filter that is \"correctly formed but is
   not accepted\"; one with a wildcard in the wrong place is not correctly
   formed at all, and 3.1.1 §4.8 says to close the connection on a protocol
   violation. The codes are the ones Mosquitto sends, so the two brokers can be
   held to the same test:

     - 0x81 Malformed Packet: an empty filter, a wildcard that is not a whole
       level, a level after `#`, or a share name containing + or #;
     - 0x82 Protocol Error: a share that is missing its group or its filter,
       `$share/`, `$share/group`, `$share//topic` or `$share/group/`."
  [^String subscription-filter]
  (if-not (and subscription-filter (.startsWith subscription-filter share-prefix))
    (when-not (valid-topic-filter? subscription-filter)
      MqttReasonCode/MALFORMED_PACKET)
    (let [rest  (subs subscription-filter (count share-prefix))
          slash (.indexOf rest "/")
          group (if (neg? slash) rest (subs rest 0 slash))
          inner (when-not (neg? slash) (subs rest (inc slash)))]
      (cond
        (re-find #"[+#]" group)            MqttReasonCode/MALFORMED_PACKET
        (or (empty? group) (empty? inner)) MqttReasonCode/PROTOCOL_ERROR
        (not (valid-topic-filter? inner))  MqttReasonCode/MALFORMED_PACKET))))

(defonce ^:private shared-cursor
  ;; Which member of each group gets the next message. §4.8.2 leaves the choice
  ;; to the implementation — the reference broker picks at random — but a
  ;; rotation spreads the load evenly and makes a test able to assert that both
  ;; members were used rather than only that the total was right.
  (atom {}))

(defn- pick-shared
  "One member of a group, rotating.

   Sorted before indexing because the matches arrive as a set, and an unstable
   order would turn the rotation into another random choice."
  [group-key members]
  (let [ordered (vec (sort-by #(str (:client-id (get @*clients* (:client-key %)))) members))
        n       (count ordered)]
    (when (pos? n)
      (let [i (get (swap! shared-cursor update group-key (fnil inc -1)) group-key)]
        (nth ordered (mod i n))))))

(defn select-shared
  "Collapse each shared group to a single recipient (§4.8.2).

   Ordinary subscriptions pass through untouched, including one held by a
   client that also belongs to a group: the two subscriptions are independent,
   and a client subscribed both ways receives the message twice."
  ([matches] (select-shared matches (constantly true)))
  ([matches serve-group?]
   (let [{shared true ordinary false} (group-by #(some? (:share-group %)) matches)]
     (if (empty? shared)
       matches
       (into (vec ordinary)
             (keep (fn [[k members]]
                     ;; A group another broker serves for this publish is
                     ;; left alone here, however many of its members are
                     ;; local: one member of the group, cluster-wide, is
                     ;; the whole point of a share.
                     (when (serve-group? k)
                       (pick-shared k members))))
             ;; Grouped by name *and* filter, which together are the identity
             ;; of a share — the same name on two filters is two groups.
             (group-by (juxt :share-group :topic-filter) shared))))))

(defn deliverable-subscribers
  "The matches that should actually be sent to, once No Local is applied.

   §3.8.3.1: a subscription made with No Local must not be sent messages that
   the same connection published. Without it a client that both publishes and
   subscribes to a topic — a bridge, or anything mirroring one topic to
   another — feeds itself."
  [matches publisher-key]
  (if (nil? publisher-key)
    ;; The broker is the publisher: a will, or a retained replay. There is no
    ;; connection for No Local to be about.
    matches
    (remove #(and (:no-local? %) (= publisher-key (:client-key %))) matches)))

(defn identifiers-of
  "The Subscription Identifiers to send with a delivery, as a seq or nil.

   Reads either shape, which is what lets coalesce-subscriptions hand back the
   matches untouched when there is nothing to merge: an unmerged subscription
   still carries the singular :subscription-identifier it was stored with, and
   only a merged one has the plural. Allocates only when there is an identifier
   at all, which most subscriptions have not got."
  [subscription]
  (or (:subscription-identifiers subscription)
      (when-let [id (:subscription-identifier subscription)] [id])))

(defn- delivery-of
  "Fold one matching subscription into the delivery a client will receive.

   `so-far` is what its earlier matching subscriptions have already built, or
   nil for the first — which is the overwhelmingly common case, and costs one
   assoc."
  [subscription so-far]
  (if (nil? so-far)
    (assoc subscription
           :subscription-identifiers
           (if-let [id (:subscription-identifier subscription)] [id] [])
           :retain-as-published? (boolean (:retain-as-published? subscription)))
    (let [id      (:subscription-identifier subscription)
          ids     (:subscription-identifiers so-far)
          ;; Only subscriptions that carry one contribute, so a client mixing
          ;; identified and unidentified filters does not see a phantom.
          ids     (if (and id (not (some #(= id %) ids))) (conj ids id) ids)
          ;; §3.3.5-1: the highest QoS of the matching subscriptions. Taking
          ;; the lowest would quietly downgrade one the client asked for.
          winner  (if (> (long (:qos subscription)) (long (:qos so-far)))
                    subscription so-far)]
      (assoc winner
             :subscription-identifiers ids
             :retain-as-published? (boolean (or (:retain-as-published? so-far)
                                                (:retain-as-published? subscription)))))))

(defn- needs-merging?
  "Whether any client appears twice, or any subscription is a shared one.

   One pass and one transient set, against the alternative of rebuilding every
   subscription map on every publish. A shared subscription forces the slow
   path only because it is keyed differently there, not because it merges."
  [matches]
  (loop [seen (transient #{}) xs (seq matches)]
    (if-not xs
      false
      (let [subscription (first xs)
            k            (:client-key subscription)]
        (if (or (:share-group subscription) (contains? seen k))
          true
          (recur (conj! seen k) (next xs)))))))

(defn coalesce-subscriptions
  "One delivery per client, not one per matching subscription (§3.3.4).

   A client holding both `sport/#` and `sport/tennis` gets a single copy of a
   message published to `sport/tennis`, carrying the Subscription Identifiers
   of both. §3.3.4 allows either that or one copy per subscription; this is the
   cheaper of the two, and it is the shape that makes the identifiers useful —
   the client learns every reason the message reached it, rather than being
   told the same thing twice with half the answer each time.

   The copy is delivered at the highest QoS of the matching subscriptions
   (§3.3.5-1). Taking the lowest would quietly downgrade a subscription the
   client had asked for at QoS 1.

   Shared subscriptions are deliberately left alone. A client subscribed both
   ordinarily and as a member of a group has asked for the message twice, in
   two capacities, and §4.8.2 keeps those independent — see select-shared."
  [matches]
  ;; One pass, merging as it goes, rather than group-by followed by a second
  ;; pass over the groups. This runs once per publish for every matching
  ;; subscription, so the intermediate vector-per-client that group-by builds
  ;; is pure garbage on the hottest path in the broker — and in the ordinary
  ;; case, where each client matches exactly once, there is nothing to merge at
  ;; all and the work is a single assoc.
  (if-not (needs-merging? matches)
    ;; The overwhelmingly common case: every client matched exactly once, so
    ;; the merged result would be each subscription with two keys renamed. The
    ;; consumers read either shape (see identifiers-of), so nothing has to be
    ;; rebuilt at all — which on a wide fan-out is one allocation per publish
    ;; instead of one per subscriber.
    matches
    (let [merged
          (reduce
           (fn [acc subscription]
             (if (:share-group subscription)
               ;; Shared subscriptions are never merged: a client subscribed both
               ;; ordinarily and as a group member has asked for the message
               ;; twice, in two capacities (§4.8.2). Keyed by the subscription
               ;; itself so each stands alone.
               (assoc! acc subscription (delivery-of subscription nil))
               (let [k (:client-key subscription)]
                 (assoc! acc k (delivery-of subscription (get acc k))))))
           (transient {})
           matches)]
      (vec (vals (persistent! merged))))))

(defn delivery-retain?
  "The RETAIN flag to put on a delivery to one subscriber.

   §3.3.1.3: a message forwarded to an existing subscriber has RETAIN 0, so the
   subscriber can tell live traffic from a replayed backlog. §3.8.3.1's Retain
   As Published turns that off for a subscription that asked to see the flag as
   the publisher set it — which is what a bridge needs, since a retained
   message that arrives without its flag becomes an ordinary one on the far
   side."
  [subscription replaying-retained? published-retain?]
  (boolean (or replaying-retained?
               (and (:retain-as-published? subscription) published-retain?))))

(defn publish-for
  "A PUBLISH built for one subscriber's protocol version.

   Version 5 subscribers need a property block on every delivery and version 4
   ones must not be sent one — a 3.1.1 client reads the property length as the
   first byte of the payload."
  ([version base properties] (publish-for version base properties nil))
  ([version base properties subscription-identifiers]
   (cond-> base
     (>= (long version) 5)
     (assoc :protocol-version 5
            :properties (cond-> (forwardable-properties properties)
                          (seq subscription-identifiers)
                          ;; §3.3.4: added by the server on the way out, from
                          ;; the subscription that matched — never forwarded
                          ;; from whatever the publisher sent.
                          (assoc :subscription-identifiers (vec subscription-identifiers)))))))

(defn expiring-properties
  "The properties to send with a message that has been waiting (§3.3.2.3.3).

   Returns ::expired when the Message Expiry Interval has run out, and
   otherwise the properties with the interval reduced by the time spent
   waiting. A message with no interval never expires, and one that did not wait
   keeps whatever it was published with — nothing was spent, so nothing is
   subtracted, and a client forwarding it on should see the publisher's number.

   The subtraction is the half that is easy to leave out. Without it a message
   queued for an hour arrives claiming its full lifetime still ahead of it, and
   every hop that passes it on resets the clock."
  [properties queued-at]
  (let [expiry (:message-expiry-interval properties)]
    (if-not (and expiry queued-at)
      properties
      (let [waited    (quot (- (System/currentTimeMillis) (long queued-at)) 1000)
            remaining (- (long expiry) waited)]
        (if (pos? remaining)
          (assoc properties :message-expiry-interval remaining)
          ::expired)))))

(defn retained-for-delivery
  "The retained message on `topic` as it should be sent now, or nil.

   nil covers both \"nothing is retained here\" and \"what was retained has
   expired\", which are the same thing to a subscriber — §3.3.1.3 makes an
   expired retained message no retained message at all, rather than one the
   broker is withholding.

   The interval that goes out is what is left of it, by the same rule as a
   queued message (§3.3.2.3.3): a retained message published with a ten minute
   life must not still be claiming ten minutes an hour later, or anything
   bridging it onward resets the clock every hop."
  [topic]
  (when-let [{:keys [properties stored-at] :as entry} (get @*retained* topic)]
    (let [live (expiring-properties properties stored-at)]
      (when-not (= ::expired live)
        (assoc entry :properties live)))))

(defn sweep-retained!
  "Drop retained messages whose interval has passed.

   Delivery is already safe without this — retained-for-delivery refuses an
   expired message whether or not it is still in the map. This is about the map
   itself: $SYS and the console both count what is in it, and an entry nobody
   ever subscribes to again would otherwise be held, and reported, for the life
   of the broker."
  []
  (doseq [[topic {:keys [properties stored-at]}] @*retained*
          :when (= ::expired (expiring-properties properties stored-at))]
    ;; Through clear!, so the record hears it too: every broker sweeps its
    ;; own copy, and the first to find a message expired clears it for all.
    (retained/clear! topic)))

(def retained-sweep-interval-ms
  "How often expired retained messages are cleared out.

   Nothing depends on this being prompt — delivery already refuses an expired
   message the moment it is asked for, whatever the map still holds. This only
   decides how long a dead entry keeps being counted."
  10000)

(defonce ^:private retained-sweep (atom nil))

(defn start-retained-sweep!
  "Begin clearing expired retained messages. Safe to call again: the previous
   job is killed first, which matters because stopping the broker resets the
   pool and leaves the old handle pointing at nothing."
  []
  (when-let [job @retained-sweep]
    (try (at/kill job) (catch Exception _ nil)))
  (reset! retained-sweep
          (at/every retained-sweep-interval-ms
                    (fn []
                      (try
                        (sweep-retained!)
                        (catch Throwable t
                          ;; Housekeeping must never take the broker down.
                          (log/error t "sweeping retained messages failed"))))
                    my-pool
                    :initial-delay retained-sweep-interval-ms)))

(defonce ^:private malformed-noted (atom 0))

(defn- note-malformed!
  "A queued or in-flight message this broker cannot build a PUBLISH from.
   Logged at most every five seconds, by its keys and not its payload, so
   that where it came from can be found."
  [client-id msg what]
  (let [now  (System/currentTimeMillis)
        last @malformed-noted]
    (when (and (> (- now (long last)) 5000) (compare-and-set! malformed-noted last now))
      (log/warn "a message for" client-id what "- its keys:" (vec (keys msg))
                "cluster key:" (::cluster-key msg) "message key:" (::msg-key msg)))))

(defn- send-publish!
  [key {:keys [topic payload qos subscription-identifiers retain? duplicate?]
        properties :properties queued-at ::queued-at :as msg} packet-identifier]
  ;; The alias is decided here rather than where the message was queued. A QoS
  ;; 1 or 2 message can wait in an offline session and go out on a later
  ;; connection, and an alias belongs to the connection it is sent on — one
  ;; stamped at queueing time would be a number the new connection never
  ;; agreed to.
  ;; A message with no QoS threw out of MqttPublish/encode. On a resume that
  ;; was the connect handler's flush, so the rest of the session's queue
  ;; was never sent, and on a forwarded publish it was the fan-out, so the
  ;; subscribers after this one never had it and the forwarding broker
  ;; never had its PUBACK. Only QoS 1 and 2 are ever held, so it goes out
  ;; at 1, at least once; one with no topic cannot go out at all.
  (let [properties (expiring-properties properties queued-at)
        client-id  #(:client-id (get @*clients* key))
        qos        (or qos (when topic
                             (note-malformed! (client-id) msg "had no QoS - sent at QoS 1")
                             1))]
    (cond
      (nil? topic)
      (do (note-malformed! (client-id) msg "had no topic - discarded")
          (.increment ^LongAdder MqttStat/droppedMessages)
          false)

      (= ::expired properties)
      ;; §3.3.2.3.3: no longer worth delivering. Treated exactly as a packet over
      ;; the maximum size below — discarded, and the identifier given back.
      (do (log/debug "discarding an expired publish for" key)
          (.increment ^LongAdder MqttStat/droppedMessages)
          false)

      :else
      (let [buf (MqttPublish/encode
                 (with-topic-alias
                   (publish-for (protocol-version-of key)
                                {:packet-type       :PUBLISH
                                 :payload           payload
                                 :topic             topic
                                 :qos               qos
                                 :retain?           (boolean retain?)
                                 :duplicate?        (boolean duplicate?)
                                 :packet-identifier packet-identifier}
                                properties
                                subscription-identifiers)
                   (alias-outbound key topic)))]
        ;; §3.1.2.11.4: too big for what this client agreed to receive, so it is
        ;; discarded and the server behaves as if delivery had completed. Reported
        ;; back so the caller can give the packet identifier up — the identifier is
        ;; reserved before the packet is built, and holding on to one per oversized
        ;; message would fill the client's window with things never sent.
        (if (too-large-for? key (.remaining ^java.nio.ByteBuffer buf))
          (do (log/debug "discarding a publish over the maximum packet size for" key)
              (.increment ^LongAdder MqttStat/droppedMessages)
              false)
          (do (send-buffer [key] buf)
              true))))))

(defn- connection-of ^Connection [key]
  (when key
    (.attachment ^SelectionKey key)))

(defn- throttle-publisher!
  "Stop reading from the publisher feeding a subscriber whose pending queue is
   filling up, and have the subscriber hold it until that queue has drained.

   pauseUntilAcked, not pauseUntilDrained: that one releases on a short socket
   write queue, which is QoS 0's backlog; a QoS 1 subscriber's backlog is its
   pending queue, and its write queue is short all along. Then the queue is
   looked at again: a drain that ran between the decision to throttle and the
   hold being taken found nothing to release, and may have been the last."
  [subscriber-key publisher-key client-id]
  (when-let [subscriber (connection-of subscriber-key)]
    (when-let [publisher (connection-of publisher-key)]
      (.pauseUntilAcked subscriber publisher)
      (when (<= (pending-count client-id) resume-threshold)
        (.ackDrained subscriber)))))

(def refused-log-ms
  "How often the broker says how many QoS 1 and 2 messages it refused."
  5000)

(defonce ^:private refused
  ;; The refusals not yet logged: {:since millis, :bridge n, :client n,
  ;; :sample #{client-id ...}}.
  (atom {:since 0}))

(defn- note-refused!
  "A QoS 1 or 2 message refused for `client-id`, whose pending queue was
   full: its publisher has been told the broker has it, and nobody will be
   sent it. The one loss under load nothing else reports — droppedMessages
   counts QoS 0 dropped from a full write queue alongside it, which is
   allowed. Said once at the first, then counted, by where the messages came
   from — a bridge's are not held back by a publisher's small window — in
   one line each refused-log-ms."
  [client-id publisher-key]
  (let [now  (System/currentTimeMillis)
        from (if (bridge/bridge? (:client-id (get @*clients* publisher-key))) :bridge :client)
        [before after]
        (swap-vals! refused
                    (fn [{:keys [since] :as m}]
                      (if (>= (- now (long since)) (long refused-log-ms))
                        {:since now from 1 :sample #{client-id}}
                        (cond-> (update m from (fnil inc 0))
                          (< (count (:sample m)) 5) (update :sample (fnil conj #{}) client-id)))))]
    (when-not (= (:since before) (:since after))
      (let [counts (dissoc before :since :sample)]
        (when (seq counts)
          (log/warn "refused" counts "QoS 1/2 messages in the" (- now (long (:since before)))
                    "ms before, their subscribers' pending queues full - for" (vec (:sample before)))))
      (log/warn "refusing a QoS 1/2 message from a" (name from) "for" client-id
                "- its pending queue is full at" pending-limit "- counting the rest for" refused-log-ms "ms"))))

(declare deliver-now-or-queue!)

(defn- deliver-or-queue!
  "Send `msg` to a subscriber if its window has room; hold it if not.

   Holding is not enough on its own — an unbounded hold is just the old
   unbounded queue by another name — so once the queue passes `pause-threshold`
   the publisher that is filling it stops being read. That is the whole point:
   QoS 1 is at-least-once, so the pressure has to go back to the source rather
   than be paid for in dropped messages. The refusal below it is a backstop for
   memory, and under back-pressure it should never fire.

   Returns false when it fired: the client was not delivered to, and the
   cluster queues it for a session that is kept as for one that is away."
  [key client-id msg publisher-key]
  (cond
    ;; Before its CONNACK: held, and sent by the flush that follows the
    ;; CONNACK. Unless that flush has been and gone while this was queued,
    ;; which the second look catches.
    (awaiting-connack? key)
    (let [held? (queue-pending! client-id msg)]
      (trace/trace! client-id msg (if held? "held for its CONNACK" "refused before its CONNACK: queue full"))
      (if held?
        (note-delivered! client-id msg)
        (do (.increment ^LongAdder MqttStat/droppedMessages)
            (note-refused! client-id publisher-key)))
      (when-not (awaiting-connack? key)
        (flush-pending! key client-id))
      held?)

    :else
    (deliver-now-or-queue! key client-id msg publisher-key)))

(defn- taken-back!
  "Take `msg` out of `client-id`'s window again, in flight under
   `packet-identifier` or, with none, waiting: whether it was still there.
   Not there, a hand-over has it, and the cluster's queue with it."
  [client-id packet-identifier msg]
  (let [[before after]
        (swap-vals! (outbound-atom client-id)
                    (fn [state]
                      (if packet-identifier
                        (if (identical? msg (get-in state [:inflight packet-identifier]))
                          (update state :inflight dissoc packet-identifier)
                          state)
                        ;; Waiting, it is a copy stamped on the way in: the
                        ;; same payload is what says it is this one. The last
                        ;; such, since it went in last: a client with two
                        ;; matching subscriptions is sent the publish twice.
                        (let [pending (vec (:pending state))
                              i       (last (keep-indexed
                                             (fn [i m] (when (identical? (:payload m) (:payload msg)) i))
                                             pending))]
                          (if i
                            (assoc state :pending (into clojure.lang.PersistentQueue/EMPTY
                                                        (concat (subvec pending 0 i)
                                                                (subvec pending (inc i)))))
                            state)))))]
    (not (identical? before after))))

(defn- left-meanwhile?
  "The connection `key` has left since the caller found it live, and the
   message it put in the window is out of it again, so that the caller
   queues it as for a session that is away. The caller asks a moment before
   the window is touched, and remove-client! can hand the window over in
   between: the message then went in flight to a socket that was gone,
   neither handed over nor queued, and nothing sent it again. The 3,000 a
   second chaos run lost a handful at its final reconnect.

   Asked after the message is in the window rather than under a lock with
   the hand-over: the delivery runs on a connection's virtual thread, and
   one waiting on a monitor holds its carrier, so contended ones stopped
   every connection on the broker. A hand-over after this sees the
   message, since the connection stops being live before the hand-over
   begins."
  [key client-id packet-identifier msg]
  (and (not (live-key? key))
       (taken-back! client-id packet-identifier msg)))

(defn- deliver-now-or-queue!
  [key client-id msg publisher-key]
  (if-let [packet-identifier (acquire-packet-identifier! client-id msg
                                                         (receive-maximum-of key))]
    (if (left-meanwhile? key client-id packet-identifier msg)
      (do (trace/trace! client-id msg "not sent: its connection has left")
          false)
      ;; A refused send has to give the identifier back, or the window fills with
      ;; messages that were discarded rather than sent and the subscriber
      ;; eventually stops being delivered to entirely.
      ;; Discarded as expired or too large counts as delivered: nobody will be.
      (let [sent? (send-publish! key msg packet-identifier)]
        (trace/trace! client-id msg (if sent? "sent" "not sent: expired or too large") packet-identifier)
        (if sent?
          (note-delivered! client-id msg)
          (release-packet-identifier! client-id packet-identifier))
        true))
    (let [held? (queue-pending! client-id msg)]
      (if (and held? (left-meanwhile? key client-id nil msg))
        (do (trace/trace! client-id msg "not held: its connection has left")
            false)
        (do
          (trace/trace! client-id msg (if held? "pending: window full" "refused: pending queue full")
                        (pending-count client-id))
          (if held?
            (note-delivered! client-id msg)
            (do (.increment ^LongAdder MqttStat/droppedMessages)
                (note-refused! client-id publisher-key)))
          (when (>= (pending-count client-id) pause-threshold)
            (throttle-publisher! key publisher-key client-id))
          held?)))))

(declare flush-pending!)

(defn queue-for-offline-sessions!
  "Keep a publish for persistent sessions that are subscribed but not connected.

   QoS 0 is deliberately not kept. §4.1 requires this of QoS 1 and 2 only, and
   at-most-once means a message for a client that is not there has already been
   delivered as well as it is going to be. The QoS stored is the lesser of the
   publish and the subscription, as it would be on delivery — so a QoS 0
   subscription keeps nothing either, whatever the publish's QoS. It used to
   keep it at QoS 0, which nothing ever acknowledges: the message stayed in
   the session's window and went out again on every reconnect."
  ([topic msg] (queue-for-offline-sessions! topic msg #{}))
  ;; `live-ids`: the clients this publish was delivered to live, left out.
  ;; A session moving between the live trie and the offline one is in both
  ;; for a moment (see add-client! and remove-client!), and would otherwise
  ;; be sent it twice.
  ([topic {:keys [qos payload properties]} live-ids]
   ;; Not when attached to a cluster: sessions that are away are queued for
   ;; there, by whichever broker saw the publish, from the cluster's own copy
   ;; of the subscriptions — see mqttkat.rama.cluster/plan. Queuing here as
   ;; well would deliver twice on resume.
   (when (and (pos? (long qos)) (nil? @session-source))
     (doseq [{:keys [client-id] sub-qos :qos} (matching-offline-sessions topic)
             :when (and (pos? (long (or sub-qos 0)))
                        (not (contains? live-ids client-id)))]
       (when client-id
         ;; With its properties. They used to be dropped here, so a message that
         ;; waited for its session arrived stripped of the content type, response
         ;; topic and user properties that an identical message delivered live
         ;; kept — and with no Message Expiry Interval, there was nothing to
         ;; expire it by either.
         (when (queue-pending! client-id {:topic      topic
                                          :payload    payload
                                          :properties (forwardable-properties properties)
                                          :qos        (min (long qos) (long sub-qos))})
           ;; Back already: it resumed between the match above and the queue,
           ;; and its connect flushed a queue this was not in yet. Nothing
           ;; else would send it until the client acknowledged something.
           (when-let [key (live-connection client-id)]
             (flush-pending! key client-id))))))))

(defn flush-pending!
  "Send what was queued for `client-id` while it was away.

   Bounded by the in-flight window rather than by the queue: take-pending!
   returns nil once the window is full, and the rest drains on acknowledgements
   the ordinary way."
  [key client-id]
  ;; Not before the CONNACK (§3.2.0-1): the flush that follows it sends this.
  (when-not (awaiting-connack? key)
    (loop [sent 0]
      (when (< sent pending-limit)
        (when-let [[packet-identifier msg] (take-pending! client-id
                                                          (receive-maximum-of key))]
          ;; A message that expired while it waited is exactly what this loop
          ;; finds, so the identifier has to come back here as well — otherwise a
          ;; session that was away long enough returns to a window full of
          ;; messages it will never be sent.
          (let [sent? (send-publish! key msg packet-identifier)]
            (trace/trace! client-id msg (if sent? "sent from pending" "not sent from pending: expired")
                          packet-identifier)
            (when-not sent?
              (release-packet-identifier! client-id packet-identifier)
              ;; Expired here is expired there: nobody will be sent it.
              (settled! client-id msg)))
          (recur (inc sent)))))))

(defn redeliver-inflight!
  "Resend whatever this session left unacknowledged (§4.4).

   This used to be a loop in the connect handler that built the PUBLISH by
   hand: topic, payload, QoS, DUP and the identifier, and nothing else. Two
   things were wrong with that. It never set the protocol version, so no
   property block was written — and a version 5 client reads the byte where
   that block should be as the first byte of the payload, so the packet is
   malformed and the redelivery arrives as nonsense or not at all. And it
   dropped the properties themselves, so even a client that could read it got a
   different message from the one it had missed.

   Going through send-publish! instead means a redelivery is built exactly like
   a first delivery — right dialect, properties, subscription identifiers,
   topic alias and maximum packet size — differing only in DUP."
  [key client-id]
  (doseq [[identifier msg] (sort-by first (:inflight (some-> (existing-outbound client-id) deref)))]
    (log/trace "redelivering to" client-id "identifier:" identifier)
    (trace/trace! client-id msg "redelivered on the resume" identifier
                  (or (::msg-key msg) (::cluster-key msg) (::release-key msg) ""))
    (if (::released? msg)
      ;; The client answered it with a PUBREC: what is owed now is the
      ;; PUBREL, not the message again (§4.4). A client that had the PUBREL
      ;; already, and had forgotten the identifier, would otherwise take a
      ;; resent PUBLISH as a new message and deliver it twice.
      (send-buffer [key] (MqttPubRel/encode {:packet-type       :PUBREL
                                             :packet-identifier identifier}))
      (when-not (send-publish! key (assoc msg :duplicate? true) identifier)
        (release-packet-identifier! client-id identifier)))))

(defn- drain-pending!
  "Send the next message waiting on this client's window, if any, and let any
   throttled publishers go once the queue is comfortably clear.

   The release check runs on every acknowledgement rather than only when the
   queue empties, so a pause that raced a drain is undone on the next ack
   instead of sticking."
  [key client-id]
  (when-let [[packet-identifier msg] (take-pending! client-id
                                                    (receive-maximum-of key))]
    (when-not (send-publish! key msg packet-identifier)
      (release-packet-identifier! client-id packet-identifier)))
  (when (<= (pending-count client-id) resume-threshold)
    (when-let [subscriber (connection-of key)]
      (.ackDrained subscriber))))

#_(defn send-message [keys msg]
    (log/debug "sending message  from  clj" (:packet-type msg) " " (:packet-identifier msg))
    (log/trace (class  keys))
    (let [s (:server (meta @*server*))]))
;  (.sendMessage ^MqttServer s keys msg)))

(defn update-timestamps
  "Mark these clients as alive.

   One read of *clients* per client, not two: this used to check that
   :last-active was present and then look it up again to write it, and the
   client could disconnect in between — the second lookup then returned nil and
   vreset! threw. A client going away while a packet of its own is still being
   handled is ordinary, so it is skipped rather than reported."
  [client-keys]
  (doseq [client-key client-keys]
    (when-let [last-active (get-in @*clients* [client-key :last-active])]
      (vreset! last-active (System/currentTimeMillis)))))

(defn send-buffer [keys buf]
  #_(log/trace "sending buffer from clj")
  #_(log/trace  keys)
  ;; No update-timestamps here on purpose: keep alive measures the time since
  ;; a packet was RECEIVED from the client, so writing to it proves nothing.
  ;; mqttkat.server/default-handler-fn marks liveness on the inbound path.
  (let [{s :server} (meta @*server*)]
    (.sendMessageBuffer ^MqttServer s keys buf)))

(defn send-buffer-droppable
  "Fan out a QoS 0 publish.

   Unlike send-buffer, a subscriber that is already far behind may refuse
   these: QoS 0 is at-most-once, so dropping degrades that subscriber's feed
   instead of costing the broker unbounded memory. Refusals show up as
   :dropped in the stats line."
  [keys buf publisher-key]
  (let [{s :server} (meta @*server*)]
    (.sendMessageBuffer ^MqttServer s keys buf true publisher-key)))

(defn- send-encoded-to
  "Write one encoded QoS 0 publish to a group of subscribers, minus any whose
   Maximum Packet Size it exceeds (§3.1.2.11.4).

   The whole point of the grouping is that one buffer is written to many
   sockets, so the size is checked against each client rather than the buffer
   being rebuilt per client — the buffer is the same for all of them, and only
   the limit differs. Clients over the limit are dropped from the write and
   counted; the specification requires the server to discard the packet and
   behave as if delivery had completed."
  [group ^java.nio.ByteBuffer buf publisher-key clients]
  (let [size    (.remaining buf)
        allowed (into [] (comp (map :client-key)
                               ;; `clients` is passed in rather than deref'd
                               ;; per member: one fan-out is one snapshot, and
                               ;; this runs once per subscriber per publish.
                               (remove (fn [k]
                                         (when-let [limit (get-in clients [k :properties :maximum-packet-size])]
                                           (> size (long limit))))))
                      group)
        refused (- (count group) (count allowed))]
    (when (pos? refused)
      (log/debug "discarding a" size "byte publish for" refused
                 "subscriber(s) over their maximum packet size")
      (dotimes [_ refused] (.increment ^LongAdder MqttStat/droppedMessages)))
    (when (seq allowed)
      (send-buffer-droppable allowed buf publisher-key))))

(defn qos-0 [keys topic {:keys [payload properties] publisher-key :client-key :as msg} retain]
  (log/trace "--> respond QOS 0 topic:" topic " retained: " retain " payload: " payload " count keys: " (count keys))
  ;; Nothing is owed to the publisher at QoS 0, so with no subscribers there is
  ;; nothing to do — and no reason to encode a packet for nobody.
  (when (seq keys)
    ;; Grouped rather than encoded per subscriber. One buffer written to every
    ;; matching client is what makes a wide fan-out cheap, and it stays true
    ;; within a group — the key is everything that changes the bytes: the
    ;; protocol version, the RETAIN flag Retain As Published decides, and the
    ;; subscription identifier the delivery carries. In the ordinary case, a
    ;; crowd of plain 3.1.1 subscribers, that is still one group and one encode.
    ;; One deref of *clients* for the whole fan-out, and one lookup per
    ;; subscriber. This was three derefs and about seven lookups each — version,
    ;; topic alias maximum, maximum packet size — which at twenty subscribers a
    ;; publish measured as a 10% throughput regression against the version
    ;; before any of it existed.
    (let [clients   @*clients*
          live      @*live-clients*
          ;; Only connections that are live and past their CONNACK: one
          ;; being torn down is going, and one being set up may not be sent
          ;; anything yet (§3.2.0-1). At most once allows either to miss it.
          keys      (filter #(let [k (:client-key %)]
                               (and (live-key? live clients k)
                                    (not (get-in clients [k :awaiting-connack?]))))
                            keys)
          published (:retain? msg)
          ;; One object shared by every subscriber that agreed to no aliases,
          ;; rather than an identical map built per subscriber purely to be a
          ;; grouping key. Identity makes the hashing trivial as well.
          plain     {:topic topic}]
      (doseq [[[version retain-flag identifiers addressing] group]
              (group-by (fn [subscription]
                          (let [k      (:client-key subscription)
                                client (get clients k)
                                alias-max (long (or (get-in client [:properties :topic-alias-maximum]) 0))]
                            [(long (get client :protocol-version 4))
                             (delivery-retain? subscription retain published)
                             (identifiers-of subscription)
                             ;; Subscribers that agreed to no aliases — every
                             ;; 3.1.1 one and every version 5 one that did not
                             ;; ask — all answer `plain` and stay in the single
                             ;; group they were in before aliases existed.
                             (if (zero? alias-max)
                               plain
                               (alias-outbound k topic alias-max))]))
                        keys)]
        (send-encoded-to group
                         (MqttPublish/encode
                          (with-topic-alias
                            (publish-for version
                                         {:packet-type :PUBLISH
                                          :payload     payload
                                          :topic       topic
                                          :qos         0
                                          :retain?     retain-flag}
                                         properties
                                         identifiers)
                            addressing))
                         ;; nil for a will or a replayed retained message:
                         ;; the broker is the publisher there and there is
                         ;; nothing to slow down.
                         publisher-key
                         clients))
      ;; Who it went to, for the other brokers: see forward-to-brokers!.
      (into #{} (keep #(get-in clients [(:client-key %) :client-id])) keys))))

(defn- holds-message?
  "Whether `client-id`'s outbound state here has the message named `k`,
   waiting or in flight: from the cluster's queue, or delivered live."
  [client-id k]
  (when-let [a (existing-outbound client-id)]
    (let [{:keys [pending inflight]} @a]
      (boolean (some #(or (= k (::msg-key %)) (= k (::cluster-key %)))
                     (concat pending (vals inflight)))))))

(defn- keyed
  "A live `delivery` to the client at `key`, carrying the publish's message
   key when it has one — see new-message-key — and marked to be reconciled
   with the cluster's queue when the client is persistent and connected
   recently enough that another broker may still have it away, and have
   queued it this message. Nil, for nothing to send, when such a client has
   the message here already: from the queue it was read from on connect."
  [delivery msg key client-id]
  (if-let [k (::msg-key msg)]
    (let [client  (get @*clients* key)
          kept?   (keep-session? client)
          now     (System/currentTimeMillis)
          ;; What it has had from its queue on the cluster is known by key
          ;; for as long as that may still be there.
          entry   (when kept? (get @catching-up client-id))
          ;; Resumed from the cluster, and its queue there still read
          ;; again on the timer: its live deliveries are taken off there.
          catching (when (and entry (< now (long (or (:reconcile-until entry) 0)))) entry)
          recent? (and kept?
                       (or catching
                           (< (- now (long (or (:connected-at client) 0)))
                              (long reconcile-window-millis))))
          ;; Forwarded by another broker, to a client whose session outlives
          ;; this broker: held for it until the client has it, see
          ;; delivering-for-origin.
          origin  (when kept? (::origin msg))
          held    #(if origin
                     (do (hold-for-origin! origin k client-id)
                         (assoc % ::origin origin))
                     %)]
      (cond
        (contains? (:had entry) k)   nil
        (not recent?)                (held (assoc delivery ::msg-key k))
        (and (not catching)
             (holds-message? client-id k)) nil
        :else                        (held (assoc delivery ::msg-key k ::reconcile? true))))
    delivery))

(defn qos-1-send
  ;; `retain` says this is a replay to a new subscriber rather than live
  ;; traffic, exactly as in qos-0. It used to be missing here, and send-publish!
  ;; wrote :retain? false on every delivery — so a QoS 1 or 2 subscriber never
  ;; saw the flag set, whether the message was a replayed retained one or a
  ;; live one its subscription asked to see as published (§3.8.3.1).
  ([keys topic msg] (qos-1-send keys topic msg false))
  ([keys topic {:keys [payload properties retain?] publisher-key :client-key :as msg} retain]
   #_(log/trace "respond qos 1:" (count keys))
   ;; Returns the client-ids it delivered to, which queue-for-offline-sessions!
   ;; and the cluster's plan leave out.
   (reduce
    (fn [live subscription]
      (let [key (:client-key subscription)]
        ;; No client-id means the subscriber went away between the trie lookup
        ;; and here, which is ordinary: there is nobody left to deliver to
        ;; here, and the session, if it is kept, is in the offline trie by
        ;; now (remove-client! puts it there before it stops being live).
        (if-let [client-id (and (live-key? key) (:client-id (get @*clients* key)))]
          ;; Not one whose full queue here refused it: it has not had it,
          ;; and left out of the live ones it is queued on the cluster, for
          ;; the next resume to send, when its session is kept. A load run
          ;; lost the messages a session resumed to a full queue was refused.
          (let [delivery (keyed {:topic topic :payload payload :qos 1
                                 :properties properties
                                 :retain? (delivery-retain? subscription retain retain?)
                                 :subscription-identifiers (identifiers-of subscription)}
                                msg key client-id)]
            (when (nil? delivery)
              (trace/trace! client-id msg "not sent: it has it here already"))
            (if (or (nil? delivery) (deliver-or-queue! key client-id delivery publisher-key))
              (conj live client-id)
              live))
          live)))
    #{}
    keys)))

(defn qos-n? [num {:keys [qos] :as m}]
  (when (= num qos) m))

(defn qos-0? [m]
  (qos-n? 0 m))

(defn qos-1? [m]
  (qos-n? 1 m))

(defn qos-2? [m]
  (qos-n? 2 m))

(defn qos-1-or-2? [m]
  ((some-fn qos-1? qos-2?) m))

(defn ack-for
  "An acknowledgement in the publisher's own dialect.

   §3.4.2.1: 0x10 No Matching Subscribers says the message was accepted and
   went nowhere. It is below 0x80, so it is not a failure — the publisher owes
   nothing more — but until version 5 there was no way to tell it at all, and a
   PUBACK meant only \"I have this\".

   A 3.1.1 publisher is answered exactly as before: there is nowhere in its
   PUBACK to put a reason code."
  [packet-type client-key packet-identifier delivered?]
  (cond-> {:packet-type       packet-type
           :packet-identifier packet-identifier}
    (>= (protocol-version-of client-key) 5)
    (assoc :protocol-version 5
           :reason-code (if delivered?
                          MqttReasonCode/SUCCESS
                          MqttReasonCode/NO_MATCHING_SUBSCRIBERS))))

(defn qos-1
  "Deliver a QoS 1 publish to `keys`, this broker's subscribers, and return
   the client-ids it reached live. The PUBACK first, unless `ack?` is false:
   on a cluster publish-keyed sends it itself, once the message is safe from
   this broker's death."
  ([keys topic msg] (qos-1 keys topic msg true))
  ([keys topic {:keys [client-key packet-identifier] :as msg} ack?]
  #_(log/trace  "qos 1 received... " (count keys))
  (when ack?
    (send-buffer [client-key]
                 (MqttPubAck/encode
                  (ack-for :PUBACK client-key packet-identifier (seq keys)))))
  (some-> (filter qos-0? keys)
          (seq)
          (qos-0 topic msg false))
  (or (some-> (filter qos-1-or-2? keys)
              (seq)
              (qos-1-send topic msg))
      #{})))

;  (doseq [k qos-1-keys]
;    (log/trace "K" k)
;    (swap! outbound assoc (:client-key k) (:packet-identifier msg))))))

(defn inbound-qos-2-id
  "Where on the cluster a publisher's QoS 2 messages wait for their PUBREL:
   a queue of its own, beside the queues of what is owed to clients, under a
   name no client can have — U+0000 is not allowed in an MQTT string
   (§1.5.4). The PUBREL can come to another broker than the PUBLISH did,
   when the first went after its PUBREC (§4.4): there it found nothing,
   answered the PUBCOMP, and the message was published nowhere."
  [client-id]
  (str "\u0000qos2-in\u0000" client-id))

(def ^:private inbound-qos-2-read-limit
  "How much of a publisher's waiting QoS 2 messages a broker reads to find
   one by its identifier: more than any client has in flight."
  65535)

(defn- held-on-cluster?
  "Whether a QoS 2 publish from `client` is held on the cluster until its
   PUBREL: on a cluster, from a client whose session outlives its
   connection, and not from another broker's bridge, which sends its own
   again to the broker it was on (mqttkat.bridge/resume-inflight!). A clean
   session's messages in flight end with its connection, as its session
   does (§4.1)."
  [client]
  (boolean (and @session-source
                client
                (keep-session? client)
                (not (bridge/bridge? (:client-id client))))))

(defn- inbound-on-cluster
  "[key msg] for `client-id`'s QoS 2 message held on the cluster under
   `packet-identifier`, or nil."
  [client-id packet-identifier]
  (when-let [{:keys [queued]} @session-source]
    (some (fn [[k m]] (when (= packet-identifier (:packet-identifier m)) [k m]))
          (queued (inbound-qos-2-id client-id) inbound-qos-2-read-limit))))

(defn discard-inbound-qos-2!
  "Let go of whatever QoS 2 messages of `client-id`'s the cluster holds for
   their PUBREL: its session is starting afresh, and will not send one
   (§4.1). In the background, since it reads the cluster."
  [client-id]
  (when-let [{:keys [queued dequeue!]} @session-source]
    (future
      (try
        (let [id   (inbound-qos-2-id client-id)
              keys (map first (queued id inbound-qos-2-read-limit))]
          (when (seq keys) (dequeue! id keys)))
        (catch Throwable t
          (log/warn t "could not let go of the QoS 2 messages held for" client-id))))))

(defn- for-cluster
  "A received publish as the cluster can keep it: without the connection it
   came on, which is this broker's alone."
  [msg]
  (dissoc msg :client-key))

(defn- qos-2-accept
  [delivered? topic {:keys [client-key packet-identifier duplicate?] :as recv-msg}]
  ;; Keyed by client-id, not by the SelectionKey. A client that disconnects
  ;; between PUBREC and PUBREL comes back on a different key, and the broker
  ;; could then never find the message it had already taken responsibility for:
  ;; it answered the PUBCOMP and dropped the publish, and the entry sat in
  ;; *inflight* for the life of the process.
  ;;
  ;; The matched subscribers are deliberately not stored either. MQTT 3.1.1
  ;; §4.3.3 publishes the message when PUBREL arrives, so the subscribers are
  ;; whoever is subscribed then — and any key captured at PUBLISH time may
  ;; belong to a connection that has since gone.
  ;;
  ;; On a cluster, a kept session's message is held on the cluster as well,
  ;; and the PUBREC waits for it to land, as a PUBACK waits for its writes:
  ;; the PUBREL may come to another broker (inbound-qos-2-id). A resend
  ;; (DUP) of one another broker took and held there is that message, not a
  ;; new one: taken as it was held, it is published once, on its PUBREL.
  (let [client    (get @*clients* client-key)
        client-id (:client-id client)
        local?    (contains? @*inflight* [client-id packet-identifier])
        cluster?  (held-on-cluster? client)
        taken     (when (and cluster? duplicate? (not local?))
                    (inbound-on-cluster client-id packet-identifier))
        k         (or (first taken) (new-message-key))
        held      (if taken
                    (assoc (second taken) :client-key client-key)
                    recv-msg)
        write     (when (and cluster? (not taken))
                    ;; Under the topic it resolved to: a version 5 publish
                    ;; may name it by an alias only this connection knows.
                    ((:enqueue! @session-source) (inbound-qos-2-id client-id)
                                                 (for-cluster (assoc recv-msg :topic topic)) k))
        pubrec!   #(send-buffer [client-key]
                                (MqttPubRec/encode
                                 (ack-for :PUBREC client-key packet-identifier delivered?)))]
    ;; Counted only when the identifier is new, so a DUP redelivery of a
    ;; message already in flight does not consume a second slot.
    (when-not local?
      (swap! *clients* update-in [client-key :inbound-inflight] (fnil inc 0)))
    (swap! *inflight* assoc [client-id packet-identifier]
           (cond-> {:msg held :topic (:topic held topic)}
             cluster? (assoc ::inbound-key k)))
    ;; Reported on the PUBREC, the first answer of the handshake, rather than on
    ;; the PUBCOMP at the end (§3.5.2.1). The subscribers counted here are the
    ;; ones matching now; §4.3.3 publishes on PUBREL, so the set can differ by
    ;; then — but "nobody is subscribed to this topic" is the answer the
    ;; publisher can act on, and it is the one version 5 asks for here.
    (once-done! write pubrec!)))

(defn inbound-inflight
  "QoS 2 messages this client has sent that are still in flight — a PUBREC has
   gone back but no PUBREL has arrived.

   Counted rather than derived from *inflight*, which is keyed by [client-id
   identifier] across every client: scanning it on each publish would be O(all
   in-flight messages on the broker) on the hot path."
  [client-key]
  (get-in @*clients* [client-key :inbound-inflight] 0))

(defn inbound-window
  "The Receive Maximum this broker gave `client-key` (§3.2.2.3.3): its own
   window for a client, a far wider one for another broker's bridge."
  [client-key]
  (if (bridge/bridge? (:client-id (get @*clients* client-key)))
    bridge/receive-maximum
    inflight-window))

(defn over-receive-maximum?
  "Whether this client has broken the quota the broker advertised (§4.9).

   Only for version 5 clients: 3.1.1 has no Receive Maximum, so there is no
   promise to break, and no DISCONNECT it could read if there were.

   QoS 1 is not counted because it is barely outstanding here — the broker sends
   the PUBACK as it handles the publish, or on a cluster once the writes to
   Rama it made have landed, so the window is milliseconds wide.
   QoS 2 is the one a client can fill, by publishing and never releasing."
  [client-key]
  (and (>= (protocol-version-of client-key) 5)
       (>= (inbound-inflight client-key) (inbound-window client-key))))

(defn qos-2
  ;; Whether anything matched, not who: the PUBREC reports that much, and the
  ;; recipients are chosen on the PUBREL (§4.3.3) — see anyone-to-deliver-to?.
  [delivered? topic {:keys [client-key packet-identifier] :as recv-msg}]
  #_(log/trace "QOS 2")
  (if (over-receive-maximum? client-key)
    ;; §4.9. Without this the broker accepts everything and answers nothing —
    ;; which is not merely impolite: the Paho conformance suite publishes one
    ;; too many and then blocks for ever waiting for the DISCONNECT that says
    ;; so, so the whole suite hangs here.
    (do
      (log/warn "client" client-key "exceeded the receive maximum of" (inbound-window client-key))
      (disconnect-with-reason! client-key
                               MqttReasonCode/RECEIVE_MAXIMUM_EXCEEDED
                               (str "more than " (inbound-window client-key) " QoS 2 messages in flight")))
    (qos-2-accept delivered? topic recv-msg)))

(defn subscribers-for
  "Who this publish is actually delivered to, in one place.

   Three steps that every publish needs and that were previously applied — or
   not — at each call site separately: drop the subscriptions No Local
   excludes, collapse each shared group to one member, then collapse each
   client's several matching subscriptions to one delivery. The PUBREL path
   skipped all three, so No Local and shared subscriptions simply did not apply
   to QoS 2 messages, and a will skipped them too."
  ([topic publisher-key] (subscribers-for topic publisher-key (constantly true)))
  ([topic publisher-key serve-group?] (subscribers-for topic publisher-key serve-group? false))
  ([topic publisher-key serve-group? groups-only?]
   (subscribers-for topic publisher-key serve-group? groups-only? nil))
  ([topic publisher-key serve-group? groups-only? withhold?]
   (subscribers-for topic publisher-key serve-group? groups-only? withhold? nil))
  ;; `matches`, matching-subscribers for `topic` when the caller has them
  ;; already: see judged.
  ([topic publisher-key serve-group? groups-only? withhold? matches]
   (coalesce-subscriptions
    (select-shared
     (cond->> (deliverable-subscribers (or matches (matching-subscribers topic)) publisher-key)
       ;; A copy sent only for shared groups: the ordinary subscribers here
       ;; had theirs already (see mqttkat.bridge/groups-only-property).
       groups-only? (filter :share-group)
       ;; A copy whose sender meant some of the ordinary subscribers here
       ;; to have it elsewhere: see route.
       withhold?    (remove #(and (nil? (:share-group %)) (withhold? %))))
     serve-group?))))

(defn- anyone-to-deliver-to?
  "Whether subscribers-for would find anyone, without choosing who.

   For the PUBREC of a QoS 2 publish, which only reports whether anything
   matched. subscribers-for cannot answer that for it: picking a shared-group
   member moves the group's rotation on, so asking at PUBLISH and asking again
   at PUBREL spent two turns per message, and with two members every QoS 2
   message went to the same one."
  [topic publisher-key serve-group? groups-only?]
  (boolean
   (some #(if (nil? (:share-group %))
            (not groups-only?)
            (serve-group? [(:share-group %) (:topic-filter %)]))
         (deliverable-subscribers (matching-subscribers topic) publisher-key))))

(defonce ^:private origin-views
  ;; The key of a bridge connection -> what this broker has of its sender's
  ;; view, as mqttkat.intent keeps it: what came down that connection, and
  ;; so in order with the copies it judges. Forgotten with the connection;
  ;; the next one starts from a snapshot.
  (atom {}))

(defn- view-changed!
  "A change to the view of the broker at the other end of bridge `key`, or
   a snapshot of it."
  [key ^bytes payload]
  (when-let [me (:my-broker-id @session-source)]
    (let [change (edn/read-string (String. payload "UTF-8"))]
      ;; Not for a connection already gone: nothing would forget it.
      (swap! origin-views
             (fn [views]
               (let [view (intent/view-apply (get views key) me change)]
                 (if (and view (get @*clients* key))
                   (assoc views key view)
                   (dissoc views key))))))))

(defonce ^:private withheld-live
  ;; client-id -> how many copies were withheld from it while it was live
  ;; here: see note-withheld-live!.
  (atom {}))

(defn- note-withheld-live!
  "Log, the first time and at every power of ten after, a copy withheld
   from `client-id` while it is connected here: its sender had it somewhere
   else or away, and some other broker is to queue it. A load run lost
   nearly everything thirty such clients were sent from the other brokers
   while they sat on one broker the whole time; this says whether the
   copies reached their broker and were left out here."
  [client-id sender v state not-served?]
  (let [n (long (get (swap! withheld-live update client-id (fnil inc 0)) client-id))]
    (when (contains? #{1 10 100 1000 10000 100000} n)
      (log/warn "withheld" n "copies from" client-id "while it is connected here; the last from"
                sender "at version" v "- its view had" (pr-str state)
                (if not-served? "and said it served the client itself" "")))))

(defn- judged
  "What judging bridged copy `msg` on `topic` by its sender's view takes,
   or nil when it cannot be: no version on it, no view of its sender here
   yet, or a view that starts after the copy was planned.

   Judged once, here, for every subscriber it might go to: `:local`, this
   broker's subscribers to it whose sessions are kept, client-id -> the
   highest QoS of their subscriptions here, and `:withheld`, those of them
   its sender served itself or had elsewhere (intent/withhold?). Only a
   kept session is withheld: a clean one here is a new session, whatever
   the sender had for an older one. Asked once per subscriber and again
   per plan, as it first was, judging a copy to 170 subscribers took
   1.6 ms on the one thread that reads the bridge."
  [topic {:keys [client-key] v ::view-v :as msg}]
  (when v
    (let [view (get @origin-views client-key)
          me   (:my-broker-id @session-source)]
      (when (and me (intent/covers? view v))
        (let [clients    @*clients*
              not-served (or (::not msg) #{})
              t          (intent/topic topic)
              matches    (matching-subscribers topic)
              local      (reduce (fn [m {:keys [client-key qos share-group]}]
                                   (let [c (get clients client-key)]
                                     (if (or share-group (nil? (:client-id c)) (not (keep-session? c)))
                                       m
                                       (update m (:client-id c) (fnil max 0) (long (or qos 0))))))
                                 {}
                                 matches)
              withheld   (into #{}
                               (filter #(intent/withhold? view me % t v not-served))
                               (keys local))]
          (doseq [c withheld]
            (when (trace/on? c topic)
              (trace/trace! c (assoc msg :topic topic) "withheld: its sender had it elsewhere"
                            "view" v (pr-str (intent/state-at view c v))))
            (when (live-connection c)
              (note-withheld-live! c (:client-id (get clients client-key)) v
                                   (intent/state-at view c v) (contains? not-served c))))
          {:view       view :me me :v v :not-served not-served :topic t
           :matches    matches
           :local      local
           :withheld   withheld})))))

(defn- withhold-fn
  "For a judged copy: whether to leave a subscriber out. A kept session
   that its sender meant elsewhere is that broker's to queue for."
  [{:keys [withheld]}]
  (fn [{:keys [client-key]}]
    (contains? withheld (:client-id (get @*clients* client-key)))))

(defn- unsubscribed-here?
  "Whether `client-id`, which the sender had here on `t` and which is not
   among `local` — this broker's subscribers the copy matched in the live
   trie — is connected here with no subscription to it: it unsubscribed,
   and the sender had not heard. Asked of the connection's own record, not
   of the trie. A session moving in is live a moment before its
   subscriptions are in the live trie, and one being taken over here is in
   neither for a moment; a copy judged then took the client for one that
   had unsubscribed, neither delivered it nor queued it, and a chaos run
   lost the copies that reached two clients 2 and 3 ms after each resumed."
  [client-id t local]
  (boolean
   (when-not (contains? local client-id)
     (when-let [client (some->> (live-connection client-id) (get @*clients*))]
       (not-any? #(and (nil? (:share-group %)) (intent/filter-matches? (:topic-filter %) t))
                 (:subscribed-topics client))))))

(defn- judged-plan
  "For a judged copy on `topic`: whom this broker queues it for, as
   {:leaving [{:client-id :qos}]}, unless it delivered to them live. Its
   clients whose sessions are kept and whom it serves, should the delivery
   be refused; and every client the sender had here, connected or not,
   unless it is live here with no such subscription — it unsubscribed, and
   the sender had not heard. And, as before there were views, those this
   broker's own copy has here or just gone, if the view's history of them
   does not reach back to the copy."
  [topic {:keys [view me v not-served local withheld] t :topic}]
  (let [served  (keep (fn [[c q]] (when-not (contains? withheld c) {:client-id c :qos q})) local)
        owed    (remove #(unsubscribed-here? (:client-id %) t local)
                        (intent/owed view me t v not-served))
        ;; A client whose history the view no longer reaches back to is
        ;; judged as copies were before there was a view: see route. Only
        ;; asked when there may be one.
        unknown (when (intent/may-be-unknown? view v)
                  (when-let [p (bridge/plan topic {:away-only? true})]
                    (let [unknown? #(= ::intent/unknown (intent/state-at view (:client-id %) v))
                          gone?    (comp recently-gone? :client-id)]
                      (concat (filter #(and (unknown? %) (gone? %)) (:queue p))
                              (filter unknown? (:leaving p))
                              (filter #(and (unknown? %) (gone? %)) (:moved p))))))
        leaving (reduce (fn [m {:keys [client-id qos]}] (update m client-id (fnil max 0) (long qos)))
                        {}
                        (concat served owed unknown))]
    (when (seq leaving)
      {:leaving (mapv (fn [[c q]] {:client-id c :qos q}) leaving)})))

(defn- route
  "Where a publish on `topic` goes, when there are other brokers.

   Returns {:plan :serve-group? :groups-only? :withhold? :matches}, the
   last being matching-subscribers when judging asked for it already. A
   publish that arrived over a bridge came from another broker for this
   one's subscribers and goes no further —
   every subscription is held by exactly one broker, so one hop is the whole
   route — and it serves only the shared groups its sender named (see
   mqttkat.bridge/share-property). Any other publish is planned: the plan
   says which brokers get a copy and which groups they serve, and this
   broker leaves those groups to them. With no other brokers the plan is
   nil and every group is served here, which is how the broker always
   behaved on its own."
  [topic {:keys [client-key qos] :as msg}]
  (if (bridge/bridge? (:client-id (get @*clients* client-key)))
    (if-let [j (when-not (::groups-only? msg) (judged topic msg))]
      ;; Its sender said whom it meant, by the version of its view the copy
      ;; was planned at: see mqttkat.intent. This broker delivers to whom
      ;; that view had here, or had nowhere, and queues — under the message's
      ;; key — for whom it had here and did not get it live. Any other
      ;; broker the view had a client on does the same for it there.
      {:plan         (when (and (::msg-key msg) (pos? (long (or qos 0))))
                       (judged-plan topic j))
       :withhold?    (withhold-fn j)
       :matches      (:matches j)
       :serve-group? (or (::shares msg) #{})
       :groups-only? false}
    ;; Not judged — a copy for groups only, or from a sender this broker
    ;; has no view of to judge it by. Its sender planned from its own copy
    ;; of the cluster, which may have a client here that has just left, or
    ;; away that is here. So this broker
    ;; queues, under the message's key, for the clients its own copy has
    ;; leaving or just away, and it did not deliver to: the sender queued for
    ;; those it had away, and the same key makes the two one entry. Not a
    ;; copy for groups only: the first copy did this already.
    ;; Only those that left here in the last moment, though, or that its
    ;; copy has still here: queuing for every client last seen here doubled
    ;; the writes to Rama, and filled its buffer.
    {:plan         (when (and (::msg-key msg) (not (::groups-only? msg)))
                     (when-let [p (bridge/plan topic {:away-only? true})]
                       ;; A client that left here and is connected elsewhere
                       ;; by now was here when the sender planned: it went
                       ;; nowhere else. Queued if its session is kept, as one
                       ;; leaving is, for the broker it is on to find there
                       ;; (catch-up!). A chaos run lost a second or two of
                       ;; traffic for each subscriber to every topic that
                       ;; reconnected while the bridges were behind.
                       (let [gone? (comp recently-gone? :client-id)
                             p     (-> p
                                       (update :queue #(filterv gone? %))
                                       (update :leaving into (filter gone?) (:moved p))
                                       (dissoc :moved))]
                         (when (or (seq (:queue p)) (seq (:leaving p)))
                           p))))
     :serve-group? (or (::shares msg) #{})
     :groups-only? (boolean (::groups-only? msg))})
    (let [plan (bridge/plan topic)]
      {:plan         plan
       :serve-group? (if-let [skip (seq (:skip plan))]
                       (complement (set skip))
                       (constantly true))})))

(defn- forward-to-brokers!
  "Hand a publish to the brokers its plan names. No-op on a nil plan.

   With the connection it came in on, which the bridge stops reading if a
   link to another broker falls behind — none for a will, whose publisher
   is gone."
  ([plan topic msg] (forward-to-brokers! plan topic msg #{}))
  ;; `live-ids`, the clients this broker delivered it to live: the plan's
  ;; :leaving are the ones it did not, see mqttkat.rama.cluster/plan.
  ([plan topic {:keys [qos payload properties client-key] msg-key ::msg-key} live-ids]
   (when plan
     (bridge/forward! (assoc plan :delivered live-ids) topic
                      {:qos        qos
                       :payload    payload
                       :properties (forwardable-properties properties)
                       :msg-key    msg-key
                       :publisher  (when (instance? SelectionKey client-key)
                                     (connection-of client-key))}))))

;; Trace, when asked for: a publish on a followed topic as it arrives and
;; where it is planned to go, and the followed clients matching it here that
;; it is not delivered to. See mqttkat.trace.
(defn- trace-publish! [topic msg plan chosen]
  (when (and (trace/publishes? topic) (trace/message? msg))
    (let [followed (fn [cs] (filterv #(trace/on? % topic) (map :client-id cs)))]
      (trace/publish! msg "arrived from" (:client-id (get @*clients* (:client-key msg)))
                      "planned" (pr-str (cond-> {:brokers (vec (keys (:brokers plan)))}
                                          (:holders plan)
                                          (assoc :holders (into {} (map (fn [[b cs]] [b [(count cs) (followed cs)]]))
                                                                (:holders plan)))
                                          (seq (:queue plan))   (assoc :queue (followed (:queue plan)))
                                          (seq (:leaving plan)) (assoc :leaving (followed (:leaving plan)))))
                      "chosen here" (count chosen))))
  (when (and (trace/following? topic) (not= 2 (long (or (:qos msg) 0))))
    (let [chosen (into #{} (map :client-key) chosen)]
      (doseq [{:keys [client-key]} (matching-subscribers topic)
              :let [client-id (:client-id (get @*clients* client-key))]
              :when (and (trace/on? client-id topic) (not (contains? chosen client-key)))]
        (trace/trace! client-id msg "matched here but not chosen")))))

(defn- publish-keyed [{:keys [topic qos retain? payload properties] :as msg}]
  (log/debug "PUBLISH:" (dissoc msg :client-key))
  ;; Counted once per publish, not once per subscriber: this is how busy the
  ;; topic is, not how much fan-out it caused.
  (TopicStats/record topic)
  (log/trace "Matched Keys:" (matching-subscribers topic))
  ;(log/trace (str "valid publish: " (s/valid? :mqtt/publish msg)))
  ;(s/explain :mqtt/publish msg)
  (when retain?
    (log/trace "publish with retain:" topic qos (empty? payload))
    (if (empty? payload)
      (retained/clear! topic)
      ;; The properties are kept with it (§3.3.1.3). What is retained is the
      ;; message, not just its bytes: a subscriber arriving later should not be
      ;; able to tell it was not there at the time, and it could — content
      ;; type, response topic, correlation data and the user properties all
      ;; reached live subscribers and none of them survived being retained.
      ;; Stamped, so the Message Expiry Interval on a retained message means
      ;; something. §3.3.1.3: when it passes, the message is discarded and the
      ;; topic simply has no retained message any more.
      (retained/retain! topic {:qos        qos
                               :payload    payload
                               :properties (forwardable-properties properties)
                               :stored-at  (System/currentTimeMillis)})))
  ;; `let` rather than `when-let`, which is what this was. The two behave the
  ;; same here only because triennium returns #{} for a topic nobody is
  ;; subscribed to, and an empty set is truthy — so the acknowledgements below
  ;; were always sent. `let` says that on purpose instead of by accident:
  ;; PUBACK and PUBREC are the receiver's answer for the packet, not a report
  ;; on delivery (§4.3.2, §4.3.3), so they must not be conditional on there
  ;; being subscribers. A `matching-vals` that returned nil for no match would
  ;; otherwise have left a QoS 1 publisher retrying for ever.
  (let [{:keys [plan serve-group? groups-only? withhold? matches]} (route topic msg)
        ;; Not chosen at all for QoS 2 — see anyone-to-deliver-to?.
        keys (when-not (= 2 (long qos))
               (subscribers-for topic (:client-key msg) serve-group? groups-only? withhold? matches))]
    (trace-publish! topic msg plan keys)
    (case (long qos)
      0 (forward-to-brokers! plan topic msg (or (qos-0 keys topic msg false) #{}))
      ;; Queued for the sessions that are away after the live deliveries,
      ;; and without the ones those reached: a session on its way in or out
      ;; is in both tries for a moment, and whether it is live is decided
      ;; once, by the delivery. A subscriber back since, whose queue was
      ;; flushed without this, is flushed again by queue-for-offline-sessions!.
      ;; On a cluster the PUBACK waits for the writes to Rama this publish
      ;; made, one for each session that is away. Sent first, as it was, a
      ;; broker killed a moment later had told the publisher it had a
      ;; message that was only ever in its memory, and the sessions it was
      ;; for resumed elsewhere without it. It is still the receiver's
      ;; answer, not a report on delivery: nothing waits for a subscriber,
      ;; and a copy held here behind a full window is not written to Rama.
      ;; It was, for a while, and at 3,000 publishes a second those writes
      ;; and their take-offs were most of what Rama was asked to do.
      ;; A copy from another broker is also held for its broker of origin
      ;; until the sessions here have it (delivering-for-origin).
      1 (if-not @session-source
          (let [live (qos-1 keys topic msg)]
            (queue-for-offline-sessions! topic msg live)
            (forward-to-brokers! plan topic msg live))
          (let [held (java.util.ArrayList.)
                ack  (MqttPubAck/encode
                      (ack-for :PUBACK (:client-key msg) (:packet-identifier msg) (seq keys)))]
            (binding [*hand-offs* held]
              (delivering-for-origin
               msg
               #(let [live (qos-1 keys topic msg false)]
                  (queue-for-offline-sessions! topic msg live)
                  (forward-to-brokers! plan topic msg live))))
            (once-handed-off! held #(send-buffer [(:client-key msg)] ack))))
      ;; Not for QoS 2: that message is not published until its PUBREL
      ;; arrives (§4.3.3), so it is kept for offline sessions there — and
      ;; routed there, for the same reason: the subscribers are whoever
      ;; matches then.
      2 (qos-2 (anyone-to-deliver-to? topic (:client-key msg) serve-group? groups-only?) topic msg))))

(defn- publish-resolved
  "A publish, named where it enters the cluster unless it came over a
   bridge named already; see new-message-key. QoS 2 keeps the name until its
   PUBREL, in the message qos-2-accept holds."
  [{:keys [qos] :as msg}]
  (publish-keyed (cond-> msg
                   (and (pos? (long (or qos 0))) @session-source (nil? (::msg-key msg)))
                   (assoc ::msg-key (new-message-key)))))

(defn resolve-topic-alias
  "Turn a topic alias back into the topic it stands for (§3.3.2.3.4).

   Two shapes arrive. A publish carrying both a topic name and an alias is
   declaring the mapping — remember it and carry on. A publish carrying an
   alias and an empty topic name is using it, and the name has to be looked up.

   Returns the message with its topic filled in, or ::invalid-alias when the
   client broke the rules: alias 0, which is not a small alias but no alias at
   all; an alias above the maximum the CONNACK offered, which the client agreed
   to by connecting; or one it never declared. All three are Topic Alias
   Invalid, and the caller disconnects.

   The mapping is per connection and per direction, so an alias a client sent
   us says nothing about what it will accept back — see alias-outbound for the
   other half."
  [{:keys [client-key topic properties] :as msg}]
  (if-let [alias (:topic-alias properties)]
    (let [alias (long alias)]
      (cond
        ;; §2.2.2.2: zero is not a usable alias, and storing it would hand the
        ;; client a mapping it could never legally quote back.
        (zero? alias) ::invalid-alias
        (> alias (long broker-topic-alias-maximum)) ::invalid-alias
        (empty? topic)
        (if-let [known (get-in @topic-aliases [client-key :inbound alias])]
          (assoc msg :topic known)
          ::invalid-alias)
        :else
        (do (swap! topic-aliases assoc-in [client-key :inbound alias] topic)
            msg)))
    msg))

(def grace-before-close-ms
  "How long to leave a socket open after writing a final packet to it.

   Only on the paths where the broker hangs up on a client and has told it why.
   Not on every close: an ordinary disconnect has nothing in flight to miss,
   and paying this on each of fifty thousand teardowns would be its own
   problem."
  25)

(defn pause-before-close!
  "Give a client a moment to read the last packet written to it.

   The close itself already waits for the writer, so the packet has certainly
   been *written* — see MqttServer.closeConnection. This is the other half:
   written is not read. The broker has stopped reading this socket, so a client
   still sending into it has data sitting unread, and closing in that state
   sends RST rather than FIN, which can discard the very packet just written
   before the client gets to it.

   A named call rather than a bare Thread/sleep at four sites, which read like
   an unexplained pause — and the long coercion happens once here instead of
   being repeated to keep the reflection warning away."
  []
  (Thread/sleep (long grace-before-close-ms)))

(defn disconnect-with-reason!
  "Tell a version 5 client why it is about to be hung up on, then hang up.

   §4.13. There is no server-to-client DISCONNECT in 3.1.1, so a client of that
   version is only closed — sending one would be a packet it has no case for
   and would read as a protocol violation from the broker.

   The close goes through the server rather than mqttkat.handlers.disconnect,
   which requires this namespace and so cannot be required back."
  [client-key reason-code reason-string]
  (when (>= (protocol-version-of client-key) 5)
    (log/debug "disconnecting" client-key "with" (MqttReasonCode/name reason-code)
               reason-string)
    (send-buffer [client-key]
                 (MqttDisconnect/encode
                  {:packet-type      :DISCONNECT
                   :protocol-version 5
                   :reason-code      reason-code
                   :properties       (cond-> {}
                                       reason-string (assoc :reason-string reason-string))})))
  ;; Closed either way. The reason code is a courtesy; the connection is over
  ;; because the client broke the protocol on it.
  ;;
  ;; Two different waits, and both are needed. closeConnection waits for the
  ;; writer, which is what guarantees the DISCONNECT above is actually written
  ;; — that used to be a 25ms guess and lost the packet under load. The pause
  ;; below is the other half of it; see pause-before-close!.
  (pause-before-close!)
  (try
    (.closeConnection ^MqttServer (:server (meta @*server*)) client-key)
    (catch Exception e
      (log/debug e "closing a connection after a protocol error failed"))))

(defn- unbridge
  "A publish as it came over a bridge, with the other broker's instructions
   taken off the properties and kept aside under ::shares. Anything else
   passes untouched."
  [{:keys [client-key properties] :as msg}]
  (if (and (:user-properties properties)
           (bridge/bridge? (:client-id (get @*clients* client-key))))
    (let [only?                (bridge/groups-only? properties)
          [shares properties'] (bridge/take-shares properties)
          k                    (bridge/msg-key properties)]
      (cond-> (assoc msg :properties properties' ::shares shares ::groups-only? only?)
        k (assoc ::msg-key k)
        ;; Which version of its sender's view it was planned at, and whom
        ;; the sender served itself: see route.
        (bridge/view-v properties) (assoc ::view-v (bridge/view-v properties)
                                          ::not (bridge/not-served properties))
        ;; Where it came from, for word back once it is delivered. Not for
        ;; a copy for groups only: its sender holds nothing for it.
        (and k (not only?)) (assoc ::origin (bridge/origin (:client-id (get @*clients* client-key))))))
    msg))

(def control-prefix
  "Where the brokers talk to each other in-band: a publish on a topic under
   this, arriving over a bridge, is an instruction for this broker, not a
   message for its subscribers. `$mqttkat/takeover` names a client and the
   connection this broker holds for it, which is to end because the client
   has connected elsewhere (§3.1.4)."
  bridge/control-prefix)

(defn- take-over-for-elsewhere!
  "Drop `client-id`'s connection here, if it is still the one named."
  [client-id connect-id]
  (if-let [key (live-connection client-id)]
    (if (= connect-id (get-in @*clients* [key :connect-id]))
      (do (log/info "session" client-id "taken over by another broker")
          ;; Told why, then closed — and then forgotten here by hand: a close
          ;; the broker starts is not reported back to the handlers the way
          ;; a socket going away is, and the will, the timer and the record
          ;; all have to go exactly as they do when a client on this broker
          ;; takes the session over (see connect/take-over-existing!).
          (disconnect-with-reason! key MqttReasonCode/SESSION_TAKEN_OVER
                                   "the client connected to another broker")
          (handle-will-if-present key)
          (remove-client! key))
      (log/debug "takeover for" client-id "names a connection that is already over"))
    (log/debug "takeover for" client-id "which is not connected here")))

(defn- control!
  "Act on an instruction from another broker."
  [{:keys [topic properties payload client-key]}]
  (let [ups (into {} (map vec) (:user-properties properties))]
    (case (subs topic (count control-prefix))
      "takeover" (take-over-for-elsewhere! (get ups "client-id") (get ups "connect-id"))
      ;; The messages this broker forwarded that the other one has now
      ;; delivered to everyone there it was for.
      "settled"  (bridge/settled-by! (bridge/origin (:client-id (get @*clients* client-key)))
                                     (str/split-lines (String. ^bytes payload "UTF-8")))
      ;; What changed in the other broker's view, ahead of the copies
      ;; planned from it.
      "view"     (view-changed! client-key payload)
      (log/warn "unknown instruction from another broker:" topic))))

(defn- control-message?
  [{:keys [client-key topic]}]
  (and (string? topic)
       (.startsWith ^String topic control-prefix)
       (bridge/bridge? (:client-id (get @*clients* client-key)))))

(defn publish
  "A PUBLISH from a client, with any topic alias resolved first."
  [msg]
  (let [resolved (unbridge (resolve-topic-alias msg))]
    (cond
      (or (= ::invalid-alias resolved) (nil? (:topic resolved)))
      ;; §3.3.2.3.4: a bad alias is a protocol error, and the publisher
      ;; deserves to hear about it. This used to log and drop, so a client
      ;; could publish into a void indefinitely without ever learning its
      ;; alias had never been bound.
      (do
        (log/warn "invalid topic alias:" (:topic-alias (:properties msg))
                  "from" (:client-key msg))
        (disconnect-with-reason! (:client-key msg)
                                 MqttReasonCode/TOPIC_ALIAS_INVALID
                                 "topic alias is zero, out of range, or was never declared"))

      (control-message? resolved)
      (control! resolved)

      :else
      (publish-resolved resolved))))


(defn puback [{:keys [packet-identifier client-key]}]
  #_(log/debug "PUBACK:" packet-identifier)
  (let [client-id (:client-id (get @*clients* client-key))]
    (if-let [msg (release-packet-identifier! client-id packet-identifier)]
      (do
        (settled! client-id msg)
        ;; A slot just freed, so let the next message waiting on it through.
        (drain-pending! client-key client-id))
      ;; An acknowledgement for something never sent. Ignoring it is the point:
      ;; acting on it used to put a live identifier back into circulation.
      (log/debug "PUBACK from" client-id "for identifier" packet-identifier
                 "which was never issued to it - ignored"))))

(defn pubrec [{:keys [client-key packet-identifier]}]
  #_(log/debug "PUBREC:" packet-identifier)
  ;; §4.3.3: on PUBREC the receiver has the message, and the sender keeps
  ;; only the identifier until PUBCOMP. That is the moment a message from
  ;; the cluster's queue is done there.
  ;; Marked, too, so that a resume sends the PUBREL again rather than the
  ;; message, and a hand-over does not queue it (§4.4).
  (let [client-id (:client-id (get @*clients* client-key))]
    (when-let [a (existing-outbound client-id)]
      (let [[before _] (swap-vals! a (fn [state]
                                       (if (get-in state [:inflight packet-identifier])
                                         (update-in state [:inflight packet-identifier]
                                                    assoc ::released? true)
                                         state)))]
        (when-let [msg (get-in before [:inflight packet-identifier])]
          (settled! client-id msg)))))
  (send-buffer [client-key]
               (MqttPubRel/encode
                {:packet-type :PUBREL :packet-identifier packet-identifier})))

(defn qos-2-send
  ([keys topic msg] (qos-2-send keys topic msg false))
  ([keys topic {:keys [payload properties retain?] publisher-key :client-key :as msg} retain]
   (some-> (filter qos-0? keys)
           (seq)
           (qos-0 topic msg retain))
   (into
    (or (some-> (filter qos-1? keys)
                (seq)
                (qos-1-send topic msg retain))
        #{})
   ;; Over the subscriptions rather than over their keys, and passing the
   ;; publisher's properties on: this delivery map was written out by hand as
   ;; topic, payload and QoS, so a QoS 2 message arrived stripped of its content
   ;; type, response topic, correlation data and user properties, and of the
   ;; subscription identifier the server owes it (§3.3.4). QoS 0 and 1 were
   ;; right, which is what made it hard to see — the same publish delivered
   ;; correctly at two QoS levels out of three.
   ;; The client-ids delivered to at QoS 1 and 2, as qos-1-send returns them.
    (keep (fn [subscription]
            (let [key (:client-key subscription)]
              (when-let [client-id (and (live-key? key) (:client-id (get @*clients* key)))]
                ;; As in qos-1-send: not one refused by a full queue.
                (let [delivery (keyed {:topic topic :payload payload :qos 2
                                       :properties properties
                                       :retain? (delivery-retain? subscription retain retain?)
                                       :subscription-identifiers (identifiers-of subscription)}
                                      msg key client-id)]
                  (when (or (nil? delivery) (deliver-or-queue! key client-id delivery publisher-key))
                    client-id))))))
    (filter qos-2? keys))))

(defn serve-groups!
  "Deliver a publish of `msg` on `topic` to one member here of each shared
   group in `group-keys`, and to nobody else — the group was to be served
   by another broker, and the copy for it never got there. Ordinary
   subscribers here had the message already, when it was first published.

   Returns whether there was a member here to take it. The publisher is not
   known to this path, which matters only for No Local, and §3.8.3.1 makes
   No Local on a shared subscription a Protocol Error."
  [topic {:keys [qos] :as msg} group-keys]
  (let [gks  (set group-keys)
        keys (coalesce-subscriptions
              (select-shared
               (filter #(contains? gks [(:share-group %) (:topic-filter %)])
                       (matching-subscribers topic))
               gks))]
    (when (seq keys)
      (case (long (or qos 0))
        0 (qos-0 keys topic msg false)
        1 (qos-1-send keys topic msg)
        2 (qos-2-send keys topic msg))
      true)))

;;there is no need to do
(defn pubrel
  [{:keys [packet-identifier client-key]}]
  #_(log/debug "received (PUBREL:" packet-identifier)
  (let [client    (get @*clients* client-key)
        client-id (:client-id client)
        local     (get @*inflight* [client-id packet-identifier])
        ;; Not here: the PUBLISH went to another broker, which went after
        ;; its PUBREC. Held on the cluster, it is published from there.
        taken     (when (and (nil? local) (held-on-cluster? client))
                    (inbound-on-cluster client-id packet-identifier))
        {:keys [topic msg]} (or local (when-let [[_ m] taken] {:topic (:topic m) :msg m}))
        inbound-key (if local (::inbound-key local) (first taken))
        ;; From the connection the PUBREL came on, not the one the PUBLISH
        ;; did: a client that reconnected in between, its session kept, is
        ;; this connection now. Another broker's bridge does that when its
        ;; link drops, and the publish, taken for a client's, went on to
        ;; every other broker.
        msg       (some-> msg (assoc :client-key client-key))
        held      (java.util.ArrayList.)]
    (when topic
      ;; §4.3.3 publishes on the PUBREL, so the subscribers are whoever matches
      ;; now — but they are chosen the same way as on any other publish.
      ;; Collecting the writes to Rama it makes, as a QoS 1 publish does for
      ;; its PUBACK: see publish-keyed.
      (binding [*hand-offs* (when @session-source held)]
        (let [{:keys [plan serve-group? groups-only? withhold? matches]} (route topic msg)]
          (delivering-for-origin
           msg
           #(let [live (qos-2-send (subscribers-for topic (:client-key msg) serve-group? groups-only?
                                                    withhold? matches)
                                   topic msg)]
              (queue-for-offline-sessions! topic msg live)
              (forward-to-brokers! plan topic msg live))))
        ;; Published: no broker is to publish it again on a PUBREL. Taken off
        ;; before the PUBCOMP, which is the publisher's word that it may use
        ;; the identifier for another.
        (when-let [{:keys [dequeue!]} (when inbound-key @session-source)]
          (let [fut (dequeue! (inbound-qos-2-id client-id) [inbound-key])]
            (when (instance? CompletableFuture fut) (.add held fut))))))
    (when (contains? @*inflight* [client-id packet-identifier])
      ;; The slot is given back on PUBREL, which is what makes the quota a
      ;; limit on messages in flight rather than on messages ever sent.
      (swap! *clients* update-in [client-key :inbound-inflight]
             (fn [n] (max 0 (dec (or n 0))))))
    (swap! *inflight* dissoc [client-id packet-identifier])
    ;; Last, not first: the PUBCOMP tells the publisher it is done with this
    ;; identifier. Sent before the message was queued for the sessions that
    ;; are away, a subscriber reconnecting on it could miss it; sent before
    ;; the entry was removed, a new publish reusing the identifier at once
    ;; had its own entry removed by the dissoc above. And on a cluster not
    ;; before those writes have landed, for the reason the PUBACK waits.
    (once-handed-off! held
                      #(send-buffer [client-key]
                                    (MqttPubComp/encode {:packet-type       :PUBCOMP
                                                         :packet-identifier packet-identifier})))))

(defn pubcomp [{:keys [packet-identifier client-key] :as msg}]
  #_(log/debug "received PUBCOMP:" (dissoc msg :client-key))
  (let [client-id (:client-id (get @*clients* client-key))
        released  (some-> (existing-outbound client-id) deref (get-in [:inflight packet-identifier]))]
    ;; A PUBREL a hand-over left on the cluster's queue is done there now.
    (when-let [k (::release-key released)]
      (when-let [{:keys [dequeue!]} @session-source]
        (dequeue! client-id [k])))
    (if (release-packet-identifier! client-id packet-identifier)
      (drain-pending! client-key client-id)
      (log/debug "PUBCOMP from" client-id "for identifier" packet-identifier
                 "which was never issued to it - ignored"))))

(defn add-subscriber [subscribers topic key]
  (if (contains? subscribers topic)
    (update-in subscribers [topic] conj key)
    (assoc subscribers topic [key])))

(defn process-retained-messages
  "Replay whatever is retained on the topics `key` has just subscribed to.

   `replay-filters`, when given, is the set of topic filters whose retain
   handling allows a replay — see replay-retained?.

   The subscriber maps are the real ones from the trie, carrying the QoS the
   subscription was granted. They used to be rebuilt here as `{:client-key k}`
   with no :qos at all, and qos-2-send dispatches by exactly that key — so a
   retained QoS 2 message matched none of its branches and was silently never
   replayed, while QoS 0 and 1 came through."
  ([key] (process-retained-messages key nil))
  ([key replay-filters]
   (doseq [retained-topic (keys @*retained*)]
     (let [subs (filter #(and (= key (:client-key %))
                              ;; nil means "no restriction" — a reconnect
                              ;; replaying a parked session, not a SUBSCRIBE.
                              (or (nil? replay-filters)
                                  (contains? replay-filters (:topic-filter %))))
                        (matching-subscribers retained-topic))
           subs (coalesce-subscriptions subs)]
       (when (seq subs)
         ;; Through retained-for-delivery, not the map: it is what applies
         ;; §3.3.1.3's expiry and counts down §3.3.2.3.3's interval, and every
         ;; one of the three QoS branches below needs both.
         (when-let [{:keys [payload properties qos]} (retained-for-delivery retained-topic)]
           (log/trace "retained payload:" payload)
           (let [msg {:payload payload :properties properties}]
             (case (long qos)
               0 (qos-0 subs retained-topic msg true)
               1 (qos-1-send subs retained-topic msg true)
               2 (qos-2-send subs retained-topic msg true)))))))))

(defn subscription-entry
  "What is stored for one subscription, in the trie and against the client.

   The version 5 options ride along with it, so the fan-out has them without a
   second lookup — and so does the trie, whose delete matches on the whole
   stored value. Absent options are left out rather than defaulted here, which
   keeps a 3.1.1 subscription byte-identical to what it always was."
  [{:keys [qos no-local? retain-as-published? retain-handling]}
   parsed
   subscription-identifier]
  (cond-> {:filter       (:filter parsed)
           :topic-filter (:topic-filter parsed)
           :qos          qos}
    (:share-group parsed) (assoc :share-group (:share-group parsed))
    no-local?               (assoc :no-local? true)
    retain-as-published?    (assoc :retain-as-published? true)
    subscription-identifier (assoc :subscription-identifier subscription-identifier)
    (and retain-handling (pos? (long retain-handling)))
    (assoc :retain-handling (long retain-handling))))

(defn existing-subscription
  "The subscription this client already holds under `filter`, if any.

   Matched on the filter the client sent, not the one that went into the trie:
   for a shared subscription those differ, and an UNSUBSCRIBE names the
   `$share/...` form."
  ;; Not named `filter`: that shadows clojure.core/filter, and the call below
  ;; then tries to invoke the string. It compiles, and fails at run time with
  ;; "String cannot be cast to IFn" from inside the SUBSCRIBE handler.
  [client-key subscription-filter]
  (first (filter #(= subscription-filter (:filter %))
                 (get-in @*clients* [client-key :subscribed-topics]))))

(defn replay-retained?
  "Whether subscribing should replay what is retained (§3.8.3.1).

   0 always, 1 only when the subscription is new, 2 never. 3.1.1 has no such
   option and behaves as 0."
  [retain-handling existed?]
  (case (long (or retain-handling 0))
    0 true
    1 (not existed?)
    2 false
    true))

(defn- not-a-client?
  "Whether `client-key` has no client behind it: a packet on a connection
   the broker never accepted, or has already let go. A client sent to
   another broker is accepted and dismissed in the same breath, and may
   well have its SUBSCRIBE in flight already; acting on it would leave a
   subscription pointing at a socket that is gone, and tell the cluster
   about a client with no name."
  [client-key]
  (when-not (contains? @*clients* client-key)
    (log/debug "ignoring a packet from a connection that is not a client:" client-key)
    true))

(defn subscribe [{:keys [client-key topics packet-identifier properties] :as msg}]
  #_(log/debug "SUBSCRIBE:" (dissoc msg :client-key))
  #_(log/trace "Subscribed PRE ADD:" @*subscriber-trie*)
  (when-not (not-a-client? client-key)
  (let [version    (protocol-version-of client-key)
        ;; §3.8.2.1.2: at most one, and it applies to every filter in the packet.
        identifier (first (:subscription-identifiers properties))
        parsed     (mapv #(assoc % :parsed (parse-subscription-filter (:topic-filter %))) topics)
        malformed  (some #(when-let [code (subscription-filter-error (:topic-filter %))]
                            [code (:topic-filter %)])
                         topics)]
    (cond
      ;; One malformed filter ends the connection, and none of the packet is
      ;; subscribed: there is no SUBACK to report the good ones on. See
      ;; subscription-filter-error for why this is not a per-filter 0x8F.
      malformed
      (let [[code topic-filter] malformed]
        (disconnect-with-reason! client-key code (str "malformed topic filter: " topic-filter)))

      ;; §3.8.3.1: No Local on a shared subscription is a Protocol Error, not a
      ;; filter this broker happens to refuse — there is no single publisher it
      ;; could mean, so the specification declines to define one.
      (some #(and (:no-local? %) (:share-group (:parsed %))) parsed)
      (disconnect-with-reason! client-key
                               MqttReasonCode/PROTOCOL_ERROR
                               "no local is not allowed on a shared subscription")

      :else
      (let [accepted (filter :parsed parsed)
            ;; Worked out before the subscriptions are added, because adding one
            ;; is exactly what makes it stop being new.
            replay   (into #{} (comp (filter #(replay-retained?
                                               (:retain-handling %)
                                               (some? (existing-subscription
                                                       client-key
                                                       (:filter (:parsed %))))))
                                     (map #(:topic-filter (:parsed %))))
                           accepted)]
        (doseq [topic accepted]
          (let [entry (subscription-entry topic (:parsed topic) identifier)]
            ;; Replaces any subscription this client already had on the filter
            ;; (§3.8.4), rather than leaving two with different options.
            (when-let [old (existing-subscription client-key (:filter (:parsed topic)))]
              (swap! *clients* update-in [client-key :subscribed-topics] disj old)
              (swap! *subscriber-trie* trie-delete (:topic-filter old)
                     (assoc old :client-key client-key)))
            (swap! *clients* update-in [client-key :subscribed-topics] conj entry)
            (swap! *subscriber-trie* trie-insert (:topic-filter entry)
                   (assoc entry :client-key client-key))
            ;; For whoever keeps a record of the session: the filter as the
            ;; client named it and the entry as stored, under the name of
            ;; this connection. A replacement is a subscribe like any other —
            ;; the same filter, a new entry.
            (let [client (get @*clients* client-key)]
              (events/emit! {:event      :client-subscribed
                             :client-id  (:client-id client)
                             :connect-id (:connect-id client)
                             :filter     (:filter entry)
                             :entry      entry}))))
        #_(log/trace "subscribers POST ADD:" @*subscriber-trie*)
        (send-buffer [client-key]
                     (MqttSubAck/encode
                      (cond-> {:packet-type       :SUBACK
                               :packet-identifier packet-identifier
                               ;; Every filter here parsed: a malformed one
                               ;; closed the connection above.
                               :response          (mapv #(long (:qos %)) parsed)}
                        (>= version 5) (assoc :protocol-version 5 :properties {}))))
        (process-retained-messages client-key replay))))))

(defn unsubscribe
  [{:keys [topics client-key] :as msg}]
  #_(log/debug "UNSUBSCRIBE:" (dissoc msg :client-key))
  ;(swap! subscribers remove-subsciber (:topics msg) (:client-key msg))
  (when-not (not-a-client? client-key)
  (let [version (protocol-version-of client-key)
        removed (doall (keep #(existing-subscription client-key %) topics))
        ;; One reason code per filter, in the order they were asked about
        ;; (§3.11.3). 0x00 if the subscription was there to remove, 0x11 if it
        ;; never existed — which in 3.1.1 was indistinguishable from success,
        ;; because that UNSUBACK has no payload to say it in.
        codes (doall
               (for [topic topics]
                 ;; The stored subscription, not one rebuilt from the filter
                 ;; and QoS: see the matching comment in remove-client!. An
                 ;; MQTT 5 subscription carries options too, and trie-delete
                 ;; matches on the whole value.
                 (if-let [entry (existing-subscription client-key topic)]
                   (do
                     (log/trace "Unsubscribing from topic:" topic (:qos entry))
                     (swap! *clients* update-in [client-key :subscribed-topics] disj entry)
                     (log/trace "Unsubscribing from trie :" topic " client-key: " client-key)
                     ;; (:topic-filter entry), not `topic`: for a shared
                     ;; subscription those differ — the client names
                     ;; $share/g/x and the trie holds x — and deleting under
                     ;; the name the client used misses the entry entirely,
                     ;; leaving the client in the group after it had left.
                     (swap! *subscriber-trie* trie-delete (:topic-filter entry)
                            (assoc entry :client-key client-key))
                     (let [client (get @*clients* client-key)]
                       (events/emit! {:event      :client-unsubscribed
                                      :client-id  (:client-id client)
                                      :connect-id (:connect-id client)
                                      :filter     (:filter entry)}))
                     (long MqttReasonCode/SUCCESS))
                   (do
                     (log/trace "No such subscription to remove:" topic)
                     (long MqttReasonCode/NO_SUBSCRIPTION_EXISTED)))))]
    ;; What was queued for the subscriptions just removed, and nothing still
    ;; subscribed wants, is not sent after all (§3.10.4 allows either); what
    ;; is in flight still completes. A shorter queue may also be the one a
    ;; throttled publisher is waiting on.
    ;; Before the UNSUBACK, not after: once the client has its answer, nothing
    ;; it just unsubscribed from should still be on its way to it.
    (let [{:keys [client-id] :as client} (get @*clients* client-key)]
      (when (and client-id
                 (pos? (long (drop-unsubscribed-pending! client-id removed
                                                         (:subscribed-topics client))))
                 (<= (pending-count client-id) resume-threshold))
        (some-> (connection-of client-key) .ackDrained)))
    (send-buffer [client-key]
                 (MqttUnSubAck/encode
                  (cond-> {:packet-type       :UNSUBACK
                           :packet-identifier (:packet-identifier msg)}
                    (>= version 5) (assoc :protocol-version 5
                                          :properties {}
                                          :response (vec codes)))))
    (log/trace "Unsubscribed trie:" @*subscriber-trie*)
    (log/trace "Unsubscribed clients:" (get-in @*clients* [client-key])))))

(defn pingreq [{:keys [client-key] :as msg}]
  (log/debug "PINGREQ:" (dissoc msg :client-key))
  (send-buffer [client-key] (MqttPingResp/encode {:packet-type :PINGRESP})))

(defn pingresp [msg]
  (log/debug "PINGRESP:" (dissoc msg :client-key)))

(comment
  (defn remove-subsciber [m [topic] key]
    (update m topic (fn [v] (filterv #(not= key %) v))))

  (defn remove-client-subscriber [m val]
    (into {} (map (fn [[k v]] (let [nv (filterv #(not= val %) v)] {k nv})) m))))

(defn authenticate [msg]
  (log/debug "AUTHENTICATE:" msg))
