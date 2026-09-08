(ns mqttkat.handlers
  (:require [clojure.tools.logging :as log]
            [mqttkat.s :refer [*server*]]
            [overtone.at-at :as at]
            [clojurewerkz.triennium.mqtt :as tr]
            [mqttkat.events :as events])
  (:import [java.util.concurrent.atomic LongAdder]
           [org.mqttkat MqttStat MqttReasonCode]
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

;; ── the subscription tries ───────────────────────────────────────────────
;;
;; Both tries go through these two rather than through triennium's insert and
;; delete, because triennium's insert corrupts a node it did not create.
;;
;;   (-> (tr/make-trie) (tr/insert "a/b" x) (tr/insert "a" y))
;;
;; The second insert finds a node already at ["a"] — created as a parent of
;; ["a" "b"] — and its :values is nil, so `(conj (:values node) val)` conjes
;; onto nil and stores a *list*. Delete then calls disj on it and throws
;; ClassCastException: PersistentList cannot be cast to IPersistentSet.
;;
;; A subscription filter that is a prefix of another is entirely ordinary —
;; `sport/#` alongside `sport/tennis/#` — so this fired in the wild rather
;; than in theory. It threw out of the CONNECT handler while restoring a
;; resumed session's subscriptions, which left that client never added, and
;; the broker then wedged for anything that waited on it.

(defn trie-insert
  "Add `value` under `topic-filter`, keeping :values a set whether or not the
   node was already there as somebody else's parent."
  [trie topic-filter value]
  (update-in trie (conj (vec (tr/split-topic topic-filter)) :values)
             (fnil conj #{}) value))

(defn trie-delete
  "Remove `value` from under `topic-filter`, matching on the whole stored
   value — an MQTT 5 subscription carries No Local, Retain As Published,
   Retain Handling and a subscription identifier as well as its QoS, and an
   entry rebuilt from the filter alone matches none of them.

   delete-matching rather than delete: it rebuilds the collection with `set`
   instead of calling disj on it, so it also copes with any list an earlier
   insert left behind, and it prunes empty nodes the same way."
  [trie topic-filter value]
  (tr/delete-matching trie topic-filter #(= % value)))

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

(def ^:dynamic *outbound* (atom {}))
(def ^:dynamic *retained* (atom {}))  ;; {:topic {:qos qos :payload payload}})

(defn- wildcard-rooted?
  "Whether a topic filter begins with a wildcard level."
  [^String topic-filter]
  (and topic-filter
       (or (.startsWith topic-filter "#")
           (.startsWith topic-filter "+"))))

(defn- sieve-dollar
  "Drop wildcard-rooted filters when the topic name begins with $."
  [^String topic matched]
  (if (and topic (.startsWith topic "$"))
    (into #{} (remove (comp wildcard-rooted? :topic-filter)) matched)
    matched))

(defn matching-offline-sessions
  "Persistent sessions subscribed to `topic` whose client is not connected."
  [^String topic]
  (sieve-dollar topic (tr/matching-vals @*offline-trie* topic)))

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
  (sieve-dollar topic (tr/matching-vals @*subscriber-trie* topic)))

(def my-pool (at/mk-pool))
(declare qos-0)
(declare qos-1-send)
(declare qos-2-send)
(declare remove-client!)
(declare disconnect-with-reason!)

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

(defn publish-will [{:keys [topic qos retain payload properties]}]
  (log/trace "Sending will message on topic:" payload)
  (when-let [keys (matching-subscribers topic)]
    (log/trace "Will keys:" keys)
    ;; §3.1.3.2: the Will Properties are the message's, and go out with it.
    ;; Through the same whitelist as a forwarded publish, which is what keeps
    ;; the Will Delay Interval out of it — that one is an instruction to the
    ;; broker about when to send this, and means nothing to a subscriber.
    (let [msg {:payload payload :properties (forwardable-properties properties)}]
      (case (long qos)
        0 (qos-0 keys topic msg retain)
        1 (qos-1-send keys topic msg)
        2 (qos-2-send keys topic msg)))))

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

(defn handle-will-if-present [key]
  (when (contains? (get @*clients* key) :will)
    (let [client (get @*clients* key)
          ;; Captured now, not read when the job fires: by then this client's
          ;; entry has been removed and there would be no will left to send.
          will   {:topic      (get-in client [:will :will-topic])
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
        (handle-will-if-present key)
        ;; TODO 
        ;; Remove Timer!!!
        ;; once we have sent the will message remove the will from the client,
        ;; so that it won't get send again.
        #_(swap! *clients* assoc-in [key] dissoc :will)
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

(defn add-timer!
  [key time]
  (log/trace "adding client to timer" time " and key:   "key)
  (let [time-out (* 1500 time)]
    ;; Stamp liveness BEFORE scheduling. The job's initial delay starts running
    ;; the moment at/every is called, so a stamp taken afterwards leaves the
    ;; first tick measuring fractionally less than time-out of idleness — the
    ;; client then survives that cycle and is only reaped on the next one.
    (swap! *clients* assoc-in [key :last-active] (volatile! (System/currentTimeMillis)))
    (swap! *clients* assoc-in [key :timer]
           (at/every time-out #(check-timer key time-out) my-pool :initial-delay time-out)))
  (log/trace @*clients*))

(defn remove-timer! [key]
  (when-let [timer (get-in @*clients* [key :timer])]
    (at/kill timer)
    (swap! *clients* assoc-in [key :timer] nil)))

(defn discard-session!
  "Forget everything stored under `client-id`: the parked session, the
   subscriptions held for it while offline, and anything queued or in flight."
  [client-id]
  (doseq [topic (:subscribed-topics (get @*clients* client-id))]
    (swap! *offline-trie* trie-delete (:topic-filter topic)
           {:client-id client-id :qos (:qos topic) :topic-filter (:topic-filter topic)}))
  (swap! *clients* dissoc client-id)
  (swap! *outbound* dissoc client-id)
  (swap! *inflight* #(into {} (remove (fn [[[id _] _]] (= id client-id))) %)))

(defn live-connection
  "The key of the connection currently holding `client-id`, if any."
  [client-id]
  (get @*live-clients* client-id))

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
  ;; client-id -> the job that will discard its parked session. Cancelled when
  ;; the client comes back, or the session would be torn out from under the
  ;; live connection that resumed it.
  (atom {}))

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
  (when-let [job (get @session-expiries client-id)]
    (swap! session-expiries dissoc client-id)
    (try (at/kill job) (catch Exception _ nil))))

(def ^:private never-expires
  "0xFFFFFFFF — §3.1.2.11.2's \"do not expire\", not a very long timer."
  4294967295)

(defn schedule-session-expiry!
  "Discard the parked session once `seconds` have passed."
  [client-id seconds]
  (when (and client-id (pos? (long seconds)) (not= (long seconds) never-expires))
    (cancel-session-expiry! client-id)
    (swap! session-expiries assoc client-id
           (at/after (* 1000 (long seconds))
                     (fn []
                       (swap! session-expiries dissoc client-id)
                       (try
                         (log/debug "session expired for" client-id)
                         (discard-session! client-id)
                         (catch Throwable t
                           (log/error t "expiring a session failed"))))
                     my-pool))))

(defn add-client! [{:keys [client-key client-id clean-session?] :as msg}]
  (if (and (false? clean-session?)
           (contains? @*clients* client-id))
    (let [client (get @*clients* client-id)]
      (log/trace "client-id already exists:" client-id)
      (let [subscriptions (get-in client [:subscribed-topics])]
        (log/trace "subscriptions:" subscriptions)
        (doseq [topic subscriptions]
          (log/trace "Adding to sub-trie for topic:" (:topic-filter topic)  "   qos: " (:qos topic))
          (swap! *offline-trie* trie-delete (:topic-filter topic)
                 {:client-id client-id :qos (:qos topic) :topic-filter (:topic-filter topic)})
          (swap! *subscriber-trie* trie-insert (:topic-filter topic)
                 {:client-key client-key :qos (:qos topic) :topic-filter (:topic-filter topic)})))
      (log/trace "client-id:" client-id)
      (swap! *clients* assoc client-key client)
      (swap! *clients* dissoc client-id))
    (let [client (dissoc msg :packet-type :client-key)
          client-added (update-in client [:subscribed-topics] (fnil conj #{}) )]
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
 ;; reports what happened.
 (events/emit! {:event     :client-connected
                :client-id client-id
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
  [client-key topic]
  (let [maximum (client-topic-alias-maximum client-key)]
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

(defn remove-client! [key]
  (remove-timer! key)
  ;; Both alias tables go with the connection, not with the session (§3.3.2.3.4).
  (forget-topic-aliases! key)
  (forget-live! (get-in @*clients* [key :client-id]) key)
  ;; Only for a client that was actually there: remove-client! can be reached
  ;; twice for one connection, and a count that drifts is worse than no count.
  (when (contains? @*clients* key)
    (MqttStat/clientDisconnected)
    (events/emit! {:event     :client-disconnected
                   :client-id (get-in @*clients* [key :client-id])
                   :clients   (MqttStat/connectedClients)}))
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
    (if-not (keep-session? client)
      (do
        ;; A session that is not kept keeps nothing. Its in-flight records would
        ;; otherwise sit in *outbound* and *inflight* for the life of the
        ;; process, since only a reconnect under the same client-id ever reads
        ;; them again.
        (when client-id
          (swap! *outbound* dissoc client-id)
          (swap! *inflight* #(into {} (remove (fn [[[id _] _]] (= id client-id))) %)))
        (swap! *clients* dissoc key))
      (do
        ;; Parked rather than forgotten: a publish arriving while this session
        ;; is away still has to match it, or there is nothing to queue.
        (doseq [topic subscribed-topics]
          (swap! *offline-trie* trie-insert (:topic-filter topic)
                 {:client-id client-id :qos (:qos topic) :topic-filter (:topic-filter topic)}))
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
             (and (not from-pending?) (seq (:pending state))))
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
  (let [[before after] (swap-vals! *outbound* update client-id reserve msg false window)]
    (when (> (count (get-in after [client-id :inflight]))
             (count (get-in before [client-id :inflight])))
      (get-in after [client-id :next-id])))))

(defn release-packet-identifier!
  "Retire `id` for `client-id`.

   Returns the message that was in flight under it, or nil if this identifier
   was never issued — an unsolicited or duplicate acknowledgement, which is
   then ignored rather than acted on. That check is the whole defence against
   a client corrupting the identifier space."
  [client-id id]
  (let [[before _] (swap-vals! *outbound* update-in [client-id :inflight] dissoc id)]
    (get-in before [client-id :inflight id])))

(defn inflight-count
  "How many messages are outstanding for `client-id`."
  [client-id]
  (count (get-in @*outbound* [client-id :inflight])))

(defn pending-count
  "How many messages are waiting for a window slot for `client-id`."
  [client-id]
  (count (get-in @*outbound* [client-id :pending])))

(defn queue-pending!
  "Hold `msg` for `client-id` until a window slot frees up.

   Returns true if it was queued, false if this client's pending queue is full
   too — at which point the message is refused, which is the only honest thing
   left: it has not been delivered and nothing is pretending otherwise."
  [client-id msg]
  (let [[before after]
        (swap-vals! *outbound* update client-id
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
                                (fnil conj clojure.lang.PersistentQueue/EMPTY) msg))))]
    (> (count (get-in after [client-id :pending]))
       (count (get-in before [client-id :pending])))))

(defn take-pending!
  "Reserve an identifier for the next message waiting on `client-id`'s window.

   Returns [identifier msg], or nil when nothing is waiting or the window is
   still full. Called as acknowledgements come back, so the queue drains at
   exactly the rate the client is acknowledging."
  ([client-id] (take-pending! client-id inflight-window))
  ([client-id window]
  (let [[before after]
        (swap-vals! *outbound* update client-id
                    (fn [state]
                      (if-let [msg (peek (:pending state))]
                        ;; from-pending?: this message *is* the head, so the
                        ;; queue being non-empty must not block it.
                        (let [reserved (reserve state msg true window)]
                          (if (identical? reserved state)
                            state                     ; window still full
                            (update reserved :pending pop)))
                        state)))]
    (when (< (count (get-in after [client-id :pending]))
             (count (get-in before [client-id :pending])))
      [(get-in after [client-id :next-id])
       (peek (get-in before [client-id :pending]))]))))

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

(defn protocol-version-of
  "Which dialect to answer this subscriber in.

   Four unless its CONNECT said otherwise, which is also the answer for a key
   that has gone: a delivery to a client that is no longer there is dropped
   further down either way."
  [client-key]
  (long (get-in @*clients* [client-key :protocol-version] 4)))


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
    (when (seq subscription-filter)
      {:filter subscription-filter :topic-filter subscription-filter})
    (let [rest  (subs subscription-filter (count share-prefix))
          slash (.indexOf rest "/")]
      (when (pos? slash)
        (let [group (subs rest 0 slash)
              inner (subs rest (inc slash))]
          (when (and (seq group)
                     (seq inner)
                     (not (re-find #"[/+#]" group)))
            {:filter subscription-filter :topic-filter inner :share-group group}))))))

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
  [matches]
  (let [{shared true ordinary false} (group-by #(some? (:share-group %)) matches)]
    (if (empty? shared)
      matches
      (into (vec ordinary)
            (keep (fn [[k members]] (pick-shared k members)))
            ;; Grouped by name *and* filter, which together are the identity of
            ;; a share — the same name on two filters is two groups.
            (group-by (juxt :share-group :topic-filter) shared)))))

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

(defn- send-publish!
  [key {:keys [topic payload qos properties subscription-identifier]} packet-identifier]
  ;; The alias is decided here rather than where the message was queued. A QoS
  ;; 1 or 2 message can wait in an offline session and go out on a later
  ;; connection, and an alias belongs to the connection it is sent on — one
  ;; stamped at queueing time would be a number the new connection never
  ;; agreed to.
  (send-buffer [key]
               (MqttPublish/encode
                (with-topic-alias
                  (publish-for (protocol-version-of key)
                               {:packet-type       :PUBLISH
                                :payload           payload
                                :topic             topic
                                :qos               qos
                                :retain?           false
                                :duplicate?        false
                                :packet-identifier packet-identifier}
                               properties
                               (when subscription-identifier [subscription-identifier]))
                  (alias-outbound key topic)))))

(defn- connection-of ^Connection [key]
  (when key
    (.attachment ^SelectionKey key)))

(defn- throttle-publisher!
  "Stop reading from the publisher feeding a subscriber that is filling up, and
   record it so the subscriber releases it once drained."
  [subscriber-key publisher-key]
  (when-let [subscriber (connection-of subscriber-key)]
    (when-let [publisher (connection-of publisher-key)]
      (.pauseUntilDrained subscriber publisher))))

(defn- deliver-or-queue!
  "Send `msg` to a subscriber if its window has room; hold it if not.

   Holding is not enough on its own — an unbounded hold is just the old
   unbounded queue by another name — so once the queue passes `pause-threshold`
   the publisher that is filling it stops being read. That is the whole point:
   QoS 1 is at-least-once, so the pressure has to go back to the source rather
   than be paid for in dropped messages. The refusal below it is a backstop for
   memory, and under back-pressure it should never fire."
  [key client-id msg publisher-key]
  (if-let [packet-identifier (acquire-packet-identifier! client-id msg
                                                         (receive-maximum-of key))]
    (send-publish! key msg packet-identifier)
    (do
      (when-not (queue-pending! client-id msg)
        (.increment ^LongAdder MqttStat/droppedMessages))
      (when (>= (pending-count client-id) pause-threshold)
        (throttle-publisher! key publisher-key)))))

(defn queue-for-offline-sessions!
  "Keep a publish for persistent sessions that are subscribed but not connected.

   QoS 0 is deliberately not kept. §4.1 requires this of QoS 1 and 2 only, and
   at-most-once means a message for a client that is not there has already been
   delivered as well as it is going to be. The QoS stored is the lesser of the
   publish and the subscription, as it would be on delivery."
  [topic {:keys [qos payload]}]
  (when (pos? (long qos))
    (doseq [{:keys [client-id] sub-qos :qos} (matching-offline-sessions topic)]
      (when client-id
        (queue-pending! client-id {:topic   topic
                                   :payload payload
                                   :qos     (min (long qos) (long sub-qos))})))))

(defn flush-pending!
  "Send what was queued for `client-id` while it was away.

   Bounded by the in-flight window rather than by the queue: take-pending!
   returns nil once the window is full, and the rest drains on acknowledgements
   the ordinary way."
  [key client-id]
  (loop [sent 0]
    (when (< sent pending-limit)
      (when-let [[packet-identifier msg] (take-pending! client-id
                                                        (receive-maximum-of key))]
        (send-publish! key msg packet-identifier)
        (recur (inc sent))))))

(defn- drain-pending!
  "Send the next message waiting on this client's window, if any, and let any
   throttled publishers go once the queue is comfortably clear.

   The release check runs on every acknowledgement rather than only when the
   queue empties, so a pause that raced a drain is undone on the next ack
   instead of sticking."
  [key client-id]
  (when-let [[packet-identifier msg] (take-pending! client-id
                                                    (receive-maximum-of key))]
    (send-publish! key msg packet-identifier))
  (when (<= (pending-count client-id) resume-threshold)
    (when-let [subscriber (connection-of key)]
      (.drained subscriber))))

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
  (log/trace "sending buffer from clj")
  (log/trace  keys)
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
    (doseq [[[version retain-flag identifier addressing] group]
            (group-by (juxt (comp protocol-version-of :client-key)
                            #(delivery-retain? % retain (:retain? msg))
                            :subscription-identifier
                            ;; How this delivery is addressed — a topic name, or
                            ;; an alias, or both — is part of what changes the
                            ;; bytes, so it belongs in the key with the rest.
                            ;; Subscribers that agreed to no aliases, which is
                            ;; every 3.1.1 one and every version 5 one that did
                            ;; not ask, all answer {:topic topic} and stay in
                            ;; the single group they were in before.
                            #(alias-outbound (:client-key %) topic))
                      keys)]
      (send-buffer-droppable (mapv :client-key group)
                             (MqttPublish/encode
                              (with-topic-alias
                                (publish-for version
                                             {:packet-type :PUBLISH
                                              :payload     payload
                                              :topic       topic
                                              :qos         0
                                              :retain?     retain-flag}
                                             properties
                                             (when identifier [identifier]))
                                addressing))
                             ;; nil for a will or a replayed retained message:
                             ;; the broker is the publisher there and there is
                             ;; nothing to slow down.
                             publisher-key))))

(defn qos-1-send [keys topic {:keys [payload properties] publisher-key :client-key}]
  (log/trace "respond qos 1:" (count keys) )
  (doseq [subscription keys]
    (let [key (:client-key subscription)]
      ;; No client-id means the subscriber went away between the trie lookup
      ;; and here, which is ordinary — there is nobody left to deliver to.
      (when-let [client-id (:client-id (get @*clients* key))]
        (deliver-or-queue! key client-id
                           {:topic topic :payload payload :qos 1
                            :properties properties
                            :subscription-identifier (:subscription-identifier subscription)}
                           publisher-key)))))

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

(defn qos-1 [keys topic {:keys [client-key packet-identifier] :as msg}]
  (log/trace  "qos 1 received... " (count keys))
  (send-buffer [client-key]
               (MqttPubAck/encode
                (ack-for :PUBACK client-key packet-identifier (seq keys))))
  (some-> (filter qos-0? keys)
          (seq)
          (qos-0 topic msg false))
  (some-> (filter qos-1-or-2? keys)
          (seq)
          (qos-1-send topic msg)))

;  (doseq [k qos-1-keys]
;    (log/trace "K" k)
;    (swap! outbound assoc (:client-key k) (:packet-identifier msg))))))

(defn- qos-2-accept
  [keys topic {:keys [client-key packet-identifier] :as recv-msg}]
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
  (let [client-id (:client-id (get @*clients* client-key))]
    ;; Counted only when the identifier is new, so a DUP redelivery of a
    ;; message already in flight does not consume a second slot.
    (when-not (contains? @*inflight* [client-id packet-identifier])
      (swap! *clients* update-in [client-key :inbound-inflight] (fnil inc 0)))
    (swap! *inflight* assoc [client-id packet-identifier] {:msg recv-msg :topic topic}))
  ;; Reported on the PUBREC, the first answer of the handshake, rather than on
  ;; the PUBCOMP at the end (§3.5.2.1). The subscribers counted here are the
  ;; ones matching now; §4.3.3 publishes on PUBREL, so the set can differ by
  ;; then — but "nobody is subscribed to this topic" is the answer the
  ;; publisher can act on, and it is the one version 5 asks for here.
  (send-buffer [client-key]
               (MqttPubRec/encode
                (ack-for :PUBREC client-key packet-identifier (seq keys)))))

(defn inbound-inflight
  "QoS 2 messages this client has sent that are still in flight — a PUBREC has
   gone back but no PUBREL has arrived.

   Counted rather than derived from *inflight*, which is keyed by [client-id
   identifier] across every client: scanning it on each publish would be O(all
   in-flight messages on the broker) on the hot path."
  [client-key]
  (get-in @*clients* [client-key :inbound-inflight] 0))

(defn over-receive-maximum?
  "Whether this client has broken the quota the broker advertised (§4.9).

   Only for version 5 clients: 3.1.1 has no Receive Maximum, so there is no
   promise to break, and no DISCONNECT it could read if there were.

   QoS 1 is not counted because it is never outstanding here — the broker sends
   the PUBACK as it handles the publish, so the window is microseconds wide.
   QoS 2 is the one a client can fill, by publishing and never releasing."
  [client-key]
  (and (>= (protocol-version-of client-key) 5)
       (>= (inbound-inflight client-key) inflight-window)))

(defn qos-2
  ;; `keys`, not `_keys`: the subscriber list is used now, to tell the
  ;; publisher whether anything matched. Left underscored it resolved to
  ;; clojure.core/keys — which compiles, and fails at run time.
  [keys topic {:keys [client-key packet-identifier] :as recv-msg}]
  (log/trace "QOS 2")
  (if (over-receive-maximum? client-key)
    ;; §4.9. Without this the broker accepts everything and answers nothing —
    ;; which is not merely impolite: the Paho conformance suite publishes one
    ;; too many and then blocks for ever waiting for the DISCONNECT that says
    ;; so, so the whole suite hangs here.
    (do
      (log/warn "client" client-key "exceeded the receive maximum of" inflight-window)
      (disconnect-with-reason! client-key
                               MqttReasonCode/RECEIVE_MAXIMUM_EXCEEDED
                               (str "more than " inflight-window " QoS 2 messages in flight")))
    (qos-2-accept keys topic recv-msg)))

(defn- publish-resolved [{:keys [topic qos retain? payload] :as msg}]
  (log/debug "PUBLISH:" (dissoc msg :client-key))
  (log/trace "Matched Keys:" (matching-subscribers topic))
  ;(log/trace (str "valid publish: " (s/valid? :mqtt/publish msg)))
  ;(s/explain :mqtt/publish msg)
  (when retain?
    (log/trace "publish with retain:" topic qos (empty? payload))
    (if (empty? payload)
      (swap! *retained* dissoc topic)
      (swap! *retained* assoc topic {:qos qos :payload payload})))
  ;; `let` rather than `when-let`, which is what this was. The two behave the
  ;; same here only because triennium returns #{} for a topic nobody is
  ;; subscribed to, and an empty set is truthy — so the acknowledgements below
  ;; were always sent. `let` says that on purpose instead of by accident:
  ;; PUBACK and PUBREC are the receiver's answer for the packet, not a report
  ;; on delivery (§4.3.2, §4.3.3), so they must not be conditional on there
  ;; being subscribers. A `matching-vals` that returned nil for no match would
  ;; otherwise have left a QoS 1 publisher retrying for ever.
  (let [keys (select-shared
              (deliverable-subscribers (matching-subscribers topic) (:client-key msg)))]
    (case (long qos)
      0 (qos-0 keys topic msg false)
      1 (do (qos-1 keys topic msg)
            (queue-for-offline-sessions! topic msg))
      ;; Not for QoS 2: that message is not published until its PUBREL
      ;; arrives (§4.3.3), so it is kept for offline sessions there.
      2 (qos-2 keys topic msg))))

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
  ;; The pause is the same one handle-not-valid-protocol-version takes before
  ;; its rejection CONNACK, and for the same reason: Connection.close queues
  ;; STOP_WRITING behind whatever is already waiting, but closeConnection then
  ;; shuts the channel, and the writer thread has to get there first. It is a
  ;; race made unlikely rather than a race removed — draining properly means
  ;; waiting on the writer, which is a change to every close in the broker.
  (Thread/sleep 25)
  (try
    (.closeConnection ^MqttServer (:server (meta @*server*)) client-key)
    (catch Exception e
      (log/debug e "closing a connection after a protocol error failed"))))

(defn publish
  "A PUBLISH from a client, with any topic alias resolved first."
  [msg]
  (let [resolved (resolve-topic-alias msg)]
    (if (or (= ::invalid-alias resolved) (nil? (:topic resolved)))
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
      (publish-resolved resolved))))


(defn puback [{:keys [packet-identifier client-key]}]
  (log/debug "PUBACK:" packet-identifier)
  (let [client-id (:client-id (get @*clients* client-key))]
    (if (release-packet-identifier! client-id packet-identifier)
      ;; A slot just freed, so let the next message waiting on it through.
      (drain-pending! client-key client-id)
      ;; An acknowledgement for something never sent. Ignoring it is the point:
      ;; acting on it used to put a live identifier back into circulation.
      (log/debug "PUBACK from" client-id "for identifier" packet-identifier
                 "which was never issued to it - ignored"))))

(defn pubrec [{:keys [client-key packet-identifier]}]
  (log/debug "PUBREC:" packet-identifier)
  (send-buffer [client-key]
               (MqttPubRel/encode
                {:packet-type :PUBREL :packet-identifier packet-identifier})))

(defn qos-2-send [keys topic {:keys [payload properties] publisher-key :client-key :as msg}]
  (some-> (filter qos-0? keys)
          (seq)
          (qos-0 topic msg false))
  (some-> (filter qos-1? keys)
          (seq)
          (qos-1-send topic msg))
  ;; Over the subscriptions rather than over their keys, and passing the
  ;; publisher's properties on: this delivery map was written out by hand as
  ;; topic, payload and QoS, so a QoS 2 message arrived stripped of its content
  ;; type, response topic, correlation data and user properties, and of the
  ;; subscription identifier the server owes it (§3.3.4). QoS 0 and 1 were
  ;; right, which is what made it hard to see — the same publish delivered
  ;; correctly at two QoS levels out of three.
  (doseq [subscription (some->> (filter qos-2? keys) (seq))]
    (let [key (:client-key subscription)]
      (when-let [client-id (:client-id (get @*clients* key))]
        (deliver-or-queue! key client-id
                           {:topic topic :payload payload :qos 2
                            :properties properties
                            :subscription-identifier (:subscription-identifier subscription)}
                           publisher-key)))))

;;there is no need to do
(defn pubrel
  [{:keys [packet-identifier client-key]}]
  (log/debug "received (PUBREL:" packet-identifier)
  (send-buffer [client-key]
               (MqttPubComp/encode {:packet-type       :PUBCOMP
                                    :packet-identifier packet-identifier}))
  (let [client-id (:client-id (get @*clients* client-key))
        {:keys [topic msg]} (get @*inflight* [client-id packet-identifier])]
    (when topic
      (qos-2-send (matching-subscribers topic) topic msg)
      (queue-for-offline-sessions! topic msg))
    (when (contains? @*inflight* [client-id packet-identifier])
      ;; The slot is given back on PUBREL, which is what makes the quota a
      ;; limit on messages in flight rather than on messages ever sent.
      (swap! *clients* update-in [client-key :inbound-inflight]
             (fn [n] (max 0 (dec (or n 0))))))
    (swap! *inflight* dissoc [client-id packet-identifier])))

(defn pubcomp [{:keys [packet-identifier client-key] :as msg}]
  (log/debug "received PUBCOMP:" (dissoc msg :client-key))
  (let [client-id (:client-id (get @*clients* client-key))]
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
                       (matching-subscribers retained-topic))]
      (when (seq subs)
        (when-let [payload (get-in @*retained* [retained-topic :payload])]
          (log/trace "retained payload:" payload)
          (case (long (get-in @*retained* [retained-topic :qos]))
            0 (qos-0 subs retained-topic {:payload payload} true)
            1 (qos-1-send subs retained-topic {:payload payload})
            2 (qos-2-send subs retained-topic {:payload payload}))))))))

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

(defn subscribe [{:keys [client-key topics packet-identifier properties] :as msg}]
  (log/debug "SUBSCRIBE:" (dissoc msg :client-key))
  (log/trace "Subscribed PRE ADD:" @*subscriber-trie*)
  (let [version    (protocol-version-of client-key)
        ;; §3.8.2.1.2: at most one, and it applies to every filter in the packet.
        identifier (first (:subscription-identifiers properties))
        parsed     (mapv #(assoc % :parsed (parse-subscription-filter (:topic-filter %))) topics)]
    ;; §3.8.3.1: No Local on a shared subscription is a Protocol Error, not a
    ;; filter this broker happens to refuse — there is no single publisher it
    ;; could mean, so the specification declines to define one.
    (if (some #(and (:no-local? %) (:share-group (:parsed %))) parsed)
      (disconnect-with-reason! client-key
                               MqttReasonCode/PROTOCOL_ERROR
                               "no local is not allowed on a shared subscription")
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
                   (assoc entry :client-key client-key))))
        (log/trace "subscribers POST ADD:" @*subscriber-trie*)
        (send-buffer [client-key]
                     (MqttSubAck/encode
                      (cond-> {:packet-type       :SUBACK
                               :packet-identifier packet-identifier
                               ;; A filter the broker cannot make sense of is
                               ;; refused on its own line rather than by
                               ;; dropping the connection: the others in the
                               ;; packet may be perfectly good.
                               :response          (mapv #(if (:parsed %)
                                                           (long (:qos %))
                                                           (long MqttReasonCode/TOPIC_FILTER_INVALID))
                                                        parsed)}
                        (>= version 5) (assoc :protocol-version 5 :properties {}))))
        (process-retained-messages client-key replay)))))

(defn unsubscribe
  [{:keys [topics client-key] :as msg}]
  (log/debug "UNSUBSCRIBE:" (dissoc msg :client-key))
  ;(swap! subscribers remove-subsciber (:topics msg) (:client-key msg))
  ;;TODO remove message from outbound messages.. but check if this is really the case.
  (let [version (protocol-version-of client-key)
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
                     (long MqttReasonCode/SUCCESS))
                   (do
                     (log/trace "No such subscription to remove:" topic)
                     (long MqttReasonCode/NO_SUBSCRIPTION_EXISTED)))))]
    (send-buffer [client-key]
                 (MqttUnSubAck/encode
                  (cond-> {:packet-type       :UNSUBACK
                           :packet-identifier (:packet-identifier msg)}
                    (>= version 5) (assoc :protocol-version 5
                                          :properties {}
                                          :response (vec codes)))))
    (log/trace "Unsubscribed trie:" @*subscriber-trie*)
    (log/trace "Unsubscribed clients:" (get-in @*clients* [client-key]))))

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
