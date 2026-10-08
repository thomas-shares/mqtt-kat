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
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [mqttkat.client :as client])
  (:import [java.io IOException]
           [java.util Set]
           [java.util.concurrent ConcurrentHashMap LinkedBlockingDeque Semaphore TimeUnit]
           [java.util.concurrent.atomic AtomicBoolean AtomicInteger LongAdder]
           [org.mqttkat MqttHandler MqttStat]
           [org.mqttkat.server Connection]))

(def client-id-prefix
  "What a bridge connection calls itself, followed by the broker it comes
   from. The receiving broker recognises it by this."
  "mqttkat-bridge/")

(defn bridge?
  "Whether `client-id` is another broker's bridge connection."
  [client-id]
  (boolean (and client-id (.startsWith ^String client-id client-id-prefix))))

(defn origin
  "The broker a bridge connection called `client-id` comes from, or nil."
  [client-id]
  (when (bridge? client-id)
    (subs client-id (count client-id-prefix))))

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
  "Where a publish on `topic` has to go besides here, or nil. With
   `{:away-only? true}`, only whom to queue it for — :queue and :leaving —
   for a copy that came from another broker and goes no further."
  ([topic]
   (when-let [f @planner]
     (f topic)))
  ([topic opts]
   (when-let [f @planner]
     (f topic opts))))

;; ── shared groups on the wire ────────────────────────────────────────────

(def share-property
  "The user property a forwarded publish carries once per shared group the
   receiving broker is to serve: its value is `group/topic-filter`. A group
   name may not contain a slash (§4.8.2), so the first one is the split. A
   forwarded publish carrying none serves no shared group at the other end —
   the choice of which broker serves a group is made where the whole group
   is visible, on the publisher's broker, and every other broker is told."
  "mqttkat-share")

(def groups-only-property
  "The user property, value \"1\", on a copy sent only for the shared groups
   it names: a group whose chosen broker could not be reached, served by
   another. That broker's ordinary subscribers had their own copy already,
   when the message was first forwarded, and must not have it twice."
  "mqttkat-groups-only")

(def msg-key-property
  "The user property carrying a forwarded publish's message key: the name
   it was given where it entered the cluster, under which every broker that
   queues it queues it, so that it is queued once — see
   mqttkat.handlers/new-message-key."
  "mqttkat-msg")

(def view-v-property
  "The user property carrying the version of its sender's view a copy was
   planned at — see mqttkat.intent."
  "mqttkat-v")

(def not-property
  "A user property naming, once each, a client the sender's view had on the
   receiving broker but the sender delivered the copy to itself: it was
   there after all, and the receiver neither delivers it nor queues it."
  "mqttkat-not")

(defn msg-key
  "The message key a bridged publish's `properties` carry, or nil."
  [properties]
  (some (fn [[k v]] (when (= msg-key-property k) v)) (:user-properties properties)))

(defn view-v
  "The view version a bridged publish's `properties` carry, or nil."
  [properties]
  (some (fn [[k v]] (when (= view-v-property k) (parse-long v))) (:user-properties properties)))

(defn not-served
  "The clients a bridged publish's `properties` say its sender served."
  [properties]
  (into #{} (keep (fn [[k v]] (when (= not-property k) v))) (:user-properties properties)))

(defn groups-only?
  "Whether a bridged publish's `properties` say it is for its groups only."
  [properties]
  (boolean (some (fn [[k _]] (= groups-only-property k)) (:user-properties properties))))

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
        rest   (remove (fn [[k _]] (contains? #{share-property groups-only-property msg-key-property
                                                   view-v-property not-property} k)) ups)]
    [shares (if (seq rest)
              (assoc properties :user-properties (vec rest))
              (dissoc properties :user-properties))]))

;; ── the sender's view, down every link ───────────────────────────────────
;;
;; See mqttkat.intent. Whoever keeps this broker's copy of the cluster's
;; subscriptions sends what changed in it to every link with
;; `broadcast-view!` before the change is used to plan anything, under
;; `view-lock`; and every new link starts with a snapshot of the whole,
;; taken under the same lock, from `view-source`. So on each link a copy
;; planned at a version comes after everything up to that version, and a
;; link that drops and comes back starts again from a snapshot.

(def control-prefix
  "Where an instruction to the other broker goes: a publish on a topic
   under this, over the bridge, is for the broker, not its subscribers."
  "$mqttkat/")

(def view-topic
  "Where the changes to a broker's view go, and its snapshots."
  (str control-prefix "view"))

(defonce view-lock
  ;; Held while the view changes and while a link is started, so that a
  ;; new link's snapshot and the changes sent to existing ones are one
  ;; sequence. Never by a publish.
  (Object.))

(defonce view-source
  ;; (fn [] -> the payload of a snapshot of the view as it stands), or nil.
  ;; Installed by mqttkat.rama.cluster; called with view-lock held.
  (atom nil))

(defn- view-item
  "A queue item carrying a change to the view, or a snapshot of it. QoS 0,
   in order with the copies on one TCP connection: if the link goes, so does
   every copy behind it, and the next link starts from a snapshot."
  [^bytes payload]
  {:qos    0
   :view?  true
   :packet {:packet-type      :PUBLISH
            :protocol-version 5
            :topic            view-topic
            :qos              0
            :payload          payload
            :retain?          false
            :duplicate?       false
            :properties       {}}})

(declare connections)

(defn broadcast-view!
  "Send `payload`, a change to the view, down every running link. The
   caller holds view-lock."
  [^bytes payload]
  (doseq [[_ {:keys [running ^LinkedBlockingDeque queue]}] @connections
          :when (some-> ^AtomicBoolean running .get)]
    (.put queue (view-item payload))))

;; ── connections to peers ─────────────────────────────────────────────────
;;
;; A link per peer: a queue, and one thread of its own that connects, waits
;; for window slots and writes. The broker's handler threads only enqueue.
;; They used to do all three themselves — open the connection and wait for
;; its CONNACK, wait for a slot in the peer's Receive Maximum, write to a
;; socket the peer may have stopped reading — and with four of them shared
;; by every client, two brokers forwarding QoS 1 to each other filled each
;; other's windows and then sat waiting for acknowledgements the other side's
;; handlers were too busy waiting to send: 6 s median latency at 5,000
;; publishes a second across three brokers, against 29 ms for the same
;; traffic at QoS 0.
;;
;; Back-pressure is what it is everywhere else in the broker: a publisher
;; whose messages have piled up in a link's queue stops being read until
;; the queue has drained.

(defonce connections
  ;; peer broker-id -> a link (see `start-link!`), or {:down-until millis}
  ;; after a failure to connect, so a peer that is not there is tried again
  ;; in a while rather than on every publish.
  (atom {}))

(def retry-after-ms
  "How long a peer that could not be reached is left alone."
  5000)

(def receive-maximum
  "The Receive Maximum a broker grants another broker's bridge (§3.2.2.3.3),
   in place of the one it gives clients. A client's window is kept small
   because the broker holds that many of its messages; a bridge carries
   every publish from one broker to another, and at a client's 128 its
   throughput was 128 per round trip, whatever the brokers could do.

   It was 16,384, and that was a minute of lag. A peer stops reading a
   bridge while a subscriber it feeds is behind (handlers/pause-threshold),
   and at 3,000 publishes a second its wildcard subscribers kept each link
   to some 450 messages a second. The window filled anyway, and the link
   held its publishers only once its queue did behind it, so a copy waited
   behind 18,000 others: 40 to 75 seconds from one broker to the next, the
   whole run. At the final reconnect a kept session had a minute of copies
   still on the way to the broker it left, which queued them on the
   cluster after the client had read its queue elsewhere. With 1024 the
   publishers are held as soon, through a queue a sixteenth as deep, and a
   peer that is keeping up still has the window for 10,000 a second at a
   100 ms round trip."
  1024)

(def queue-pause-at
  "Queue depth at which a link holds the publisher of the message that
   took it there: stops reading its socket, as a subscriber falling behind
   does (see mqttkat.handlers/pause-threshold)."
  2048)

(def queue-resume-at
  "Queue depth at which the publishers a link holds are read again.
   Hysteresis, for the reason resume-threshold has it."
  512)

(def queue-limit
  "The backstop: a message arriving at a link this far behind is dropped
   and counted. Holding publishers keeps a queue well short of this; a
   message with no publisher to hold — a will, a queued session's backlog —
   is what could otherwise grow it without end."
  65536)

(defn- next-packet-id
  "1..65535, wrapping. Safe against reuse because nothing here holds an
   identifier for anywhere near that long."
  ^long [^AtomicInteger ids]
  (inc (mod (.getAndIncrement ids) 65535)))

(def connack-wait-ms
  "How long a new bridge waits for the peer's CONNACK, which carries the
   Receive Maximum the bridge must keep to."
  5000)

(def window-wait-ms
  "How long a publish waits for a slot in the peer's Receive Maximum before
   the peer is taken to have stopped acknowledging, and dropped — unless
   it is still listed in the cluster (see `peer-alive?`)."
  5000)

(defonce peer-alive?
  ;; (fn [peer-id] -> whether the cluster still lists it), or nil.
  ;; Installed by mqttkat.rama.cluster. A peer that stops acknowledging may
  ;; be gone, or may only have stopped reading this link while one of its
  ;; subscribers catches up, as it stops reading any publisher feeding a
  ;; subscriber that has fallen behind (handlers/throttle-publisher!). One
  ;; that is still listed is the second: its link is waited on, as a
  ;; publisher it holds waits, rather than dropped with everything in
  ;; flight handed back. Dropped, that was every message on a busy link,
  ;; and the clients it was for, still connected there, never had it.
  (atom nil))

(defonce peer-address
  ;; (fn [peer-id] -> {:host :port} or nil): where the cluster says a peer
  ;; listens. Installed by mqttkat.rama.cluster next to `peer-alive?`.
  (atom nil))

(def refused-for-gone-ms
  "How long a peer must have refused every connection, probed as often as
   probe-every-ms, before it is taken to be gone though the cluster still
   lists it. A broker that is killed stays listed for ten minutes, and what
   waits on its hand-over (handlers/hand-over-wait-millis) waited it out
   to the end while every client it had sat disconnected. A broker that is
   stopping closes its listener first and hands its sessions over after,
   in a second or two, and is unlisted as soon as it has: so this is
   longer than that, and the wait it replaces ends no later than the
   hand-over's own."
  3000)

(def probe-every-ms
  "How often a peer is probed at most, whoever asks: a hand-over waits in
   a loop, once per client moving."
  250)

(def ^:private probe-timeout-ms 200)

(defonce ^:private probes
  ;; peer-id -> {:at millis it was last probed :since millis it began to
  ;; refuse, or nil}
  (ConcurrentHashMap.))

(defn- refused?
  "Whether `peer` refused a connection just now: something answered that
   nothing listens there. A peer that does not answer at all is not
   refusing, it may be busy or cut off."
  [{:keys [host port]}]
  (try
    (with-open [sock (java.net.Socket.)]
      (.connect sock (java.net.InetSocketAddress. ^String host (int port)) (int probe-timeout-ms))
      false)
    (catch java.net.ConnectException _ true)
    (catch Exception _ false)))

(defn peer-gone?
  "Whether `peer-id` has left the cluster, or is listed and has refused
   every connection for refused-for-gone-ms: a broker whose process is
   gone. False when this broker has no cluster."
  [peer-id]
  (let [alive? @peer-alive?
        where  @peer-address]
    (cond
      (nil? alive?)             false
      (not (alive? peer-id))    true
      :else
      (if-let [peer (and where (where peer-id))]
        (let [now   (System/currentTimeMillis)
              state (.compute probes peer-id
                              (reify java.util.function.BiFunction
                                (apply [_ _ {:keys [at since] :as old}]
                                  (if (and old (< (- now (long at)) (long probe-every-ms)))
                                    old
                                    {:at now :since (when (refused? peer) (or since now))}))))]
          (boolean (and (:since state)
                        (>= (- now (long (:since state))) (long refused-for-gone-ms)))))
        false))))

(declare lost!)

;; ── delivered, not only taken ────────────────────────────────────────────
;;
;; A PUBACK from the peer says it has the message, not that its subscribers
;; do. Until they have, the message is only in the peer's memory, and a peer
;; that dies then loses it: its persistent subscribers come back on another
;; broker to a session that was never told. So a message forwarded with a
;; message key and an `:on-undelivered` is held here, after the peer takes
;; it, until the peer says every subscriber there that keeps its session
;; has acknowledged it or had it handed to the cluster's queue (`settled!`,
;; `settled-by!`). If the link to the peer goes first, `:on-undelivered`
;; queues it in the cluster for the clients it was for, under the message's
;; key — the same key the peer queues under if it hands a session over, so
;; the two are one entry.

(defonce ^:private awaiting
  ;; peer broker-id -> ConcurrentHashMap of msg-key -> {:on-undelivered :at}
  (ConcurrentHashMap.))

(def awaiting-grace-ms
  "How long after a link goes on its own the peer still has to say what
   its subscribers have: its word on messages acknowledged just before
   comes on its own link to here, which may still be up."
  1000)

(def awaiting-limit-ms
  "How long a message waits for its peer's word before it is taken as
   delivered: a subscriber that holds a message this long unacknowledged,
   or a word that went missing, must not hold memory here for ever."
  60000)

(def awaiting-sweep-ms
  "How often a link looks for messages that have waited too long."
  5000)

(defn- awaiting-for ^ConcurrentHashMap [peer-id]
  (.computeIfAbsent ^ConcurrentHashMap awaiting peer-id
                    (reify java.util.function.Function
                      (apply [_ _] (ConcurrentHashMap.)))))

(defn- await!
  "Hold `hold` — {:msg-key :on-undelivered}, or nil for nothing to hold —
   until `peer-id` says the message is delivered."
  [peer-id {:keys [msg-key on-undelivered] :as hold}]
  (when hold
    (.put (awaiting-for peer-id) msg-key {:on-undelivered on-undelivered
                                          :at             (System/currentTimeMillis)})))

(defn settled-by!
  "`peer-id` says the messages named `msg-keys` have reached its subscribers."
  [peer-id msg-keys]
  (when-let [^ConcurrentHashMap m (.get ^ConcurrentHashMap awaiting peer-id)]
    (doseq [k msg-keys]
      (.remove m k))))

(defn- take-awaiting!
  "Take `peer-id`'s held messages held since before `before` out of
   `awaiting`, and return them."
  [peer-id before]
  (if-let [^ConcurrentHashMap m (.get ^ConcurrentHashMap awaiting peer-id)]
    (into []
          (keep (fn [^java.util.Map$Entry e]
                  (let [entry (.getValue e)]
                    (when (and (<= (long (:at entry)) (long before))
                               (.remove m (.getKey e) entry))
                      entry))))
          (vec (.entrySet m)))
    []))

(defn- give-up-awaiting!
  "The peer is gone: every message it had taken since before `before` and
   not said was delivered goes to the cluster's queue instead."
  [peer-id before]
  (let [held (take-awaiting! peer-id before)]
    (when (seq held)
      (log/warn "bridge to" peer-id "gone with" (count held)
                "messages taken and not yet delivered; queuing them")
      (doseq [{:keys [on-undelivered]} held]
        (lost! peer-id on-undelivered :gone-undelivered)))))

(defn- expire-awaiting!
  "Take `peer-id`'s messages held since before `before` as delivered."
  [peer-id before]
  (let [n (count (take-awaiting! peer-id before))]
    (when (pos? n)
      (log/info "bridge to" peer-id ":" n "messages waited" awaiting-limit-ms
                "ms for word they were delivered; taken as delivered"))))

(def handed-back-log-ms
  "How often a link says how many messages it has handed back, and why."
  5000)

(defonce ^:private handed-back
  ;; peer-id -> {:since millis, reason -> count}: the hand-backs not yet
  ;; logged. One line each handed-back-log-ms, not one per message: a full
  ;; queue hands back thousands a second, and those used to go unlogged.
  (atom {}))

(defn- note-handed-back! [peer-id reason]
  (let [now (System/currentTimeMillis)
        [before after]
        (swap-vals! handed-back
                    (fn [m]
                      (let [{:keys [since] :as e} (get m peer-id)]
                        (if (and since (>= (- now (long since)) (long handed-back-log-ms)))
                          (assoc m peer-id {:since now})
                          (if e
                            (update-in m [peer-id reason] (fnil inc 0))
                            (assoc m peer-id {:since now}))))))
        e     (get before peer-id)
        fresh (not= (:since e) (:since (get after peer-id)))]
    (when (and e fresh (seq (dissoc e :since)))
      (log/warn "bridge to" peer-id ": handed back" (dissoc e :since)
                "in the" (- now (long (:since e))) "ms before"))
    ;; The first of each window at once: the count above comes only with
    ;; the next hand-back, which after a burst may be a long time coming.
    (when fresh
      (log/warn "bridge to" peer-id ": handing back a message:" (name reason)
                "- counting the rest for" handed-back-log-ms "ms"))))

(defn- lost!
  "Tell a message's sender it did not reach the peer: the cluster queues it
   for the sessions it was for (see mqttkat.rama.cluster/forward-publish!).
   Never throws — this runs on the link's thread and the peer's reader.
   `reason`, a keyword, is for the log."
  ([peer-id on-lost] (lost! peer-id on-lost :link-lost))
  ([peer-id on-lost reason]
   (when on-lost
     (note-handed-back! peer-id reason)
     (try
       (on-lost)
       (catch Throwable t
         (log/warn t "bridge to" peer-id "could not hand back a message it lost"))))))

(defn- on-packet
  "What the peer sends back. A bridge subscribes to nothing, so this is its
   CONNACK and acknowledgements.

   Each acknowledgement that ends a QoS 1 or 2 flow gives a slot back to the
   window (§4.9): PUBACK, PUBCOMP, or a PUBREC that refuses the message
   (0x80 and up), which ends the flow there. Any other PUBREC is answered
   with the PUBREL the QoS 2 handshake needs, and the slot stays taken until
   the PUBCOMP.

   `inflight` is packet identifier -> {:on-lost :hold :packet}, for the
   messages written and not yet done with. A PUBACK, or a PUBREC accepting
   the message, says the peer has it: from then on it is the peer's to
   deliver, and its :hold, if it has one, waits in `awaiting` until the peer
   says its subscribers have it too. A QoS 2 message stays in `inflight`,
   :released?, until its PUBCOMP, so that a link that comes back to the
   peer's session sends its PUBREL again (see resume-inflight!). A PUBREC
   refusing it says the peer will not, and the message is lost here after
   all."
  [holder window present ^ConcurrentHashMap inflight peer-id
   {:keys [packet-type packet-identifier reason-code properties session-present?] :as msg}]
  (let [release! #(when (realized? window) (.release ^Semaphore @window))
        code     (bit-and 0xFF (long (or reason-code 0)))
        settle!  #(when packet-identifier (.remove inflight (int packet-identifier)))]
    (case packet-type
      :CONNACK (if (>= code 0x80)
                 (log/warn "bridge to" peer-id "refused:" code)
                 (do (log/info "bridge to" peer-id "up" (if session-present? "- its session kept" ""))
                     (reset! present (boolean session-present?))
                     ;; §3.2.2.3.3: absent means 65,535.
                     (deliver window (Semaphore. (int (or (:receive-maximum properties) 65535))))))
      :PUBACK  (do (await! peer-id (:hold (settle!))) (release!))
      :PUBCOMP (do (settle!) (release!))
      :PUBREC  (if (>= code 0x80)
                 (let [entry (settle!)]
                   (release!)
                   (lost! peer-id (:on-lost entry) :refused))
                 (when-let [c @holder]
                   (when-let [entry (when packet-identifier (.get inflight (int packet-identifier)))]
                     (when-not (:released? entry)
                       (.put inflight (int packet-identifier) (assoc entry :released? true))
                       (await! peer-id (:hold entry))))
                   (try
                     (client/send-message c {:packet-type :PUBREL :packet-identifier packet-identifier})
                     (catch IOException e
                       (log/debug "bridge to" peer-id "closed before its PUBREL went:" (.getMessage e))))))
      :DISCONNECT (log/info "bridge to" peer-id "closed by the other end:" (:reason-code msg))
      nil)))

(def session-expiry-secs
  "How long the peer keeps a bridge's session once its connection has gone
   (§3.1.2.11.2): long enough for the link to come back to it."
  300)

(defn- open!
  "Connect to `peer` and introduce this broker, and wait for its CONNACK:
   that is where the peer says how many unacknowledged QoS 1 and 2 publishes
   it will take at once (§3.2.2.3.3), and a bridge that ignores it is
   dropped by the peer for exceeding it — with everything it had in flight.
   That happened at a few thousand QoS 2 messages a second, with the peer's
   window at 128. On the link's own thread, so the wait holds up nothing
   but the link.

   A session the peer keeps for session-expiry-secs, started afresh unless
   `resume?`: a link that comes back to it after its connection dropped
   finishes what it had in flight (resume-inflight!). The first link of
   this run starts a new one, since nothing an earlier run left in it is
   this run's."
  [my-id peer-id {:keys [host port]} inflight resume?]
  (let [holder  (atom nil)
        window  (promise)
        present (atom false)
        handler (MqttHandler. ^clojure.lang.IFn (fn [msg _] (on-packet holder window present inflight peer-id msg)) 1)
        c       (client/client host (int port) handler)]
    (reset! holder c)
    (client/send-message c {:packet-type      :CONNECT
                            :protocol-name    "MQTT"
                            :protocol-version 5
                            :keep-alive       0
                            :clean-session?   (not resume?)
                            :client-id        (str client-id-prefix my-id)
                            :properties       {:session-expiry-interval session-expiry-secs}})
    (if-let [w (try (deref window connack-wait-ms nil)
                    ;; Dropped while waiting: as good as no answer.
                    (catch InterruptedException _ nil))]
      {:client c :window w :session-present? @present}
      (do (try (client/close c) (catch Exception _ nil))
          (throw (java.net.SocketTimeoutException.
                  (str "no CONNACK from " peer-id " within " connack-wait-ms " ms")))))))

(def connack-patience-ms
  "How long a link keeps asking a peer that takes its connection but does
   not answer it, before handing back what it holds. A peer busy with
   thousands of clients connecting at once answered late, and the link
   that gave up on it after one wait handed back what was queued for it —
   delivered nowhere, for a clean session, though the peer was there."
  30000)

(defn- open-patiently!
  "open!, again while the peer takes the connection but sends no CONNACK
   in time, for connack-patience-ms and while the link runs. A peer that
   refuses the connection outright is not waited for."
  [my-id peer-id peer inflight resume? ^AtomicBoolean running]
  (let [deadline (+ (System/currentTimeMillis) (long connack-patience-ms))]
    (loop []
      (let [o (try (open! my-id peer-id peer inflight resume?)
                   (catch java.net.SocketTimeoutException e
                     (if (and (.get running) (< (System/currentTimeMillis) deadline))
                       (do (log/info "bridge to" peer-id "-" (.getMessage e) "; asking again")
                           ::again)
                       (throw e))))]
        (if (= ::again o) (recur) o)))))

;; ── holding publishers ───────────────────────────────────────────────────

(defn- release-holds!
  "Read every publisher this link holds again. Each is taken out of the set
   before it is resumed, so two releases racing resume each one once."
  [{:keys [^Set held]}]
  (when-not (.isEmpty held)
    (doseq [^Connection p (vec held)]
      (when (.remove held p)
        (.resumeReading p)))))

(defn- hold!
  "Stop reading `publisher` until this link's queue has drained. Paused
   first and recorded second, then the queue looked at again: the order the
   subscriber holds settled on (Connection.pauseUntilDrained), so a release
   landing in between cannot miss it. A publisher the link already holds
   is one hold, not two — its pause count is given back at once."
  [{:keys [^Set held ^AtomicBoolean running ^LinkedBlockingDeque queue] :as link} ^Connection publisher]
  (.pauseReading publisher)
  (when-not (.add held publisher)
    (.resumeReading publisher))
  (when (or (not (.get running)) (<= (.size queue) (long queue-resume-at)))
    (release-holds! link)))

;; ── the link ─────────────────────────────────────────────────────────────

(defn- write!
  "Send one queued message: a slot in the peer's window first for QoS 1 and
   2 (§4.9), then the write. A peer that frees no slot for window-wait-ms
   has stopped acknowledging, which ends the link."
  [{:keys [^AtomicInteger ids ^ConcurrentHashMap inflight] :as link} client ^Semaphore window {:keys [qos packet on-lost hold]}]
  (let [qos (long qos)
        id  (when (pos? qos) (next-packet-id ids))]
    ;; Off the queue and not yet written while it waits for a slot: if the
    ;; wait ends the link, this one is handed back here, since neither the
    ;; queue nor the in-flight map has it to hand back later.
    (when id
      (loop [waited 0]
        (let [slot? (try (.tryAcquire window (long window-wait-ms) TimeUnit/MILLISECONDS)
                         (catch InterruptedException e
                           (lost! (:peer-id link) on-lost :link-dropped)
                           (throw e)))
              waited (+ waited (long window-wait-ms))]
          (when-not slot?
            (if (and (client/connected? client)
                     (when-let [alive? @peer-alive?] (alive? (:peer-id link))))
              (do (when (zero? (mod waited (* 6 (long window-wait-ms))))
                    (log/info "bridge to" (:peer-id link) ": nothing acknowledged for" waited
                              "ms; it is still listed, so waiting for it"))
                  ;; Written to, not only asked whether it is open: a load
                  ;; run's links waited eleven minutes on sockets the kernel
                  ;; had already let go, whose channels still said open, and
                  ;; whose readers never woke. A write to one fails, and the
                  ;; link ends as any other lost one does. A peer that has
                  ;; only stopped reading takes the two bytes, or holds the
                  ;; write as it holds the window.
                  (try
                    (client/send-message client {:packet-type :PINGREQ})
                    (catch IOException e
                      (lost! (:peer-id link) on-lost :link-dropped)
                      (throw e)))
                  (recur waited))
              (do (lost! (:peer-id link) on-lost :not-acknowledged)
                  (throw (IOException. (str "nothing acknowledged for " waited " ms")))))))))
    ;; Recorded before the write, so an acknowledgement quicker than the
    ;; line after it still finds it.
    ;; With the packet, for a link that comes back to the peer's session to
    ;; send again.
    (when id
      (.put inflight (int id) {:on-lost on-lost :hold hold :packet (assoc packet :packet-identifier id)}))
    (try
      (client/send-message client (cond-> packet id (assoc :packet-identifier id)))
      (catch IOException e
        (when id
          (.release window)
          (.remove inflight (int id))
          ;; Not written, so not the peer's.
          (lost! (:peer-id link) on-lost :write-failed))
        (throw e)))))

(defn- forget-link!
  "Take `link` out of the connections, if it is still the one there —
   leaving `replacement` in its place, or nothing."
  [peer-id link replacement]
  ;; Told apart by `:running`, which every copy of one link shares: the map
  ;; the link's thread was given is not the one stored, which also has the
  ;; thread on it.
  (swap! connections (fn [m]
                       (if (identical? (:running (get m peer-id)) (:running link))
                         (if replacement (assoc m peer-id replacement) (dissoc m peer-id))
                         m))))

(declare link!)

(defonce ^:private carried
  ;; peer-id -> {:queue :inflight :ids} of a link whose connection dropped
  ;; while its peer was still listed: what the next link to it takes up, on
  ;; the session the peer kept, rather than handing it all back.
  (ConcurrentHashMap.))

(def reconnect-after-ms
  "How long after its connection drops a link to a peer still listed
   connects again."
  1000)

(defn- hand-back!
  "Hand back what `queued` and `inflight` hold that the peer has not taken,
   and count it. A QoS 2 message whose PUBREC came is the peer's, and its
   hold waits in `awaiting`."
  [peer-id queued ^ConcurrentHashMap inflight]
  (let [unacked (vec (remove :released? (.values inflight)))]
    (.clear inflight)
    (when (or (seq queued) (seq unacked))
      (log/warn "bridge to" peer-id "gone with" (count queued) "messages queued and"
                (count unacked) "unacknowledged; handing them back")
      (.add ^LongAdder MqttStat/droppedMessages (+ (count queued) (count unacked))))
    (doseq [{:keys [on-lost]} queued] (lost! peer-id on-lost :link-gone-queued))
    (doseq [{:keys [on-lost]} unacked] (lost! peer-id on-lost :link-gone-unacknowledged))))

(defn- resume-inflight!
  "A link that took up a dropped one's state, now connected: what was in
   flight goes again, in order, a QoS 2 message the peer had taken as its
   PUBREL and the rest as DUP publishes (§4.4) — when the peer kept its
   session. When it did not, it has none of them, and they are handed back
   as if the link had gone, since the peer has lost them: a QoS 2 message
   it took is published on its PUBREL (§4.3.3), which it never had.

   Handed back on every drop, as they once were, the ones it already had
   went out a second time from the cluster's queue, and the QoS 2 ones it
   had taken and not yet released went nowhere."
  [peer-id c ^Semaphore window session-present? ^ConcurrentHashMap inflight]
  (when-not (.isEmpty inflight)
    (if-not session-present?
      (do (log/warn "bridge to" peer-id "came back to no session;" (.size inflight)
                    "messages in flight are handed back")
          (let [all (vec (.values inflight))]
            (.clear inflight)
            (doseq [{:keys [on-lost]} all] (lost! peer-id on-lost :session-lost))))
      (do (log/info "bridge to" peer-id "came back to its session; sending" (.size inflight)
                    "messages in flight again")
          (doseq [id (sort (keys inflight))
                  :let [{:keys [packet released?]} (.get inflight id)]]
            ;; Each takes its slot again, as it did the first time.
            (.tryAcquire window)
            (client/send-message c (if released?
                                     {:packet-type :PUBREL :packet-identifier id}
                                     (assoc packet :duplicate? true))))))))

(defn- run-link!
  "The link's thread: connect, then write whatever is queued, in order,
   until the link is dropped or the peer is lost. Then every message the
   peer had not taken — still queued, or written and not acknowledged — is
   handed back through its on-lost and counted, and every publisher the
   link held is let go.

   Unless the connection dropped while the peer is still listed: then the
   queue and what is in flight are kept for the next link, which connects
   again after reconnect-after-ms to the session the peer kept and goes on
   where this one left off."
  [my-id peer-id peer {:keys [^LinkedBlockingDeque queue ^AtomicBoolean running client
                              ^ConcurrentHashMap inflight ids resume?] :as link}]
  (let [opened  (atom nil)
        lost-at (atom nil)]
    (try
      (let [{c :client window :window :as o} (open-patiently! my-id peer-id peer inflight resume? running)]
        (reset! opened o)
        (deliver client c)
        (resume-inflight! peer-id c window (:session-present? o) inflight)
        (loop [swept (System/currentTimeMillis)]
          (when (.get running)
            ;; A peer that has gone shows here, not only on the next write:
            ;; the client's reader closes the socket when the peer does. With
            ;; nothing to send, that was the only way a dead peer was
            ;; noticed, and what it had taken but not delivered waited on it.
            (when-not (client/connected? c)
              (throw (IOException. "closed by the peer")))
            (when-let [item (.poll queue 200 TimeUnit/MILLISECONDS)]
              (write! link c window item)
              (when (<= (.size queue) (long queue-resume-at))
                (release-holds! link)))
            (let [now (System/currentTimeMillis)]
              (if (> (- now swept) (long awaiting-sweep-ms))
                (do (expire-awaiting! peer-id (- now (long awaiting-limit-ms)))
                    ;; An idle link is written to as well, for the reason
                    ;; write! gives: an open channel is no proof of a peer.
                    (client/send-message c {:packet-type :PINGREQ})
                    (recur now))
                (recur swept))))))
      (catch IOException e
        (cond
          (nil? @opened)
          (do (log/warn "bridge to" peer-id "at" (:host peer) (:port peer) "could not connect:" (.getMessage e))
              (forget-link! peer-id link {:down-until (+ (System/currentTimeMillis) (long retry-after-ms))}))
          (.get running)
          (do (log/warn "bridge to" peer-id "lost:" (or (.getMessage e) (.getName (class e))))
              (reset! lost-at (System/currentTimeMillis)))))
      (catch InterruptedException _ nil)
      (finally
        (.set running false)
        (let [carry? (and @lost-at
                          (when-let [alive? @peer-alive?] (alive? peer-id)))]
          (if carry?
            ;; Kept for the next link before this one is forgotten, so that
            ;; one started meanwhile takes them up.
            (.put ^ConcurrentHashMap carried peer-id {:queue queue :inflight inflight :ids ids})
            (let [queued (java.util.ArrayList.)]
              (.drainTo queue queued)
              (hand-back! peer-id queued inflight)))
          (forget-link! peer-id link nil)
          (release-holds! link)
          (when-let [o @opened]
            (try (client/close (:client o)) (catch Exception _ nil)))
          (cond
            carry?
            (do (log/info "bridge to" peer-id "is still listed; connecting again in" reconnect-after-ms "ms")
                (future
                  (try
                    (Thread/sleep (long reconnect-after-ms))
                    (when (.containsKey ^ConcurrentHashMap carried peer-id)
                      (link! my-id peer-id peer))
                    (catch Throwable t
                      (log/warn t "bridge to" peer-id "could not connect again")))))

            ;; The peer went on its own: what it had taken and not yet said
            ;; its subscribers have is given up on, once it has had a moment
            ;; to say so about what was on its way. Dropped by drop! instead,
            ;; it was given up on there. And a link that took up a dropped
            ;; one's and could not reach the peer again gives up on it too.
            (or @lost-at (and resume? (nil? @opened)))
            (do (try
                  (Thread/sleep (long awaiting-grace-ms))
                  (catch InterruptedException _ nil))
                (give-up-awaiting! peer-id (or @lost-at (System/currentTimeMillis))))))))))

(defn- start-link!
  "A link to `peer-id`, its thread started. `:client` is delivered once the
   peer has accepted the connection."
  [my-id peer-id peer]
  (let [taken (.remove ^ConcurrentHashMap carried peer-id)
        link  {:peer-id  peer-id
               :queue    (or (:queue taken) (LinkedBlockingDeque.))
               :inflight (or (:inflight taken) (ConcurrentHashMap.))
               :held     (ConcurrentHashMap/newKeySet)
               :running  (AtomicBoolean. true)
               :ids      (or (:ids taken) (AtomicInteger. 0))
               :resume?  (boolean taken)
               :client   (promise)}
        t     (doto (Thread. ^Runnable (fn [] (run-link! my-id peer-id peer link))
                            (str "bridge-" peer-id))
               (.setDaemon true))]
    (assoc link :thread t)))

(defn- link!
  "The link to `peer-id`, started if there is none. nil if the peer was
   unreachable recently."
  [my-id peer-id peer]
  (let [{:keys [down-until] :as existing} (get @connections peer-id)]
    (cond
      (some-> ^AtomicBoolean (:running existing) .get)
      existing

      ;; Not even the locks, for a peer that is down: every publish for it
      ;; comes here.
      (and down-until (< (System/currentTimeMillis) (long down-until)))
      nil

      :else
      ;; The view first: a new link starts with a snapshot of it, and no
      ;; change may be sent to the others in between.
      (locking view-lock
        (locking connections
          (let [{:keys [down-until running] :as existing} (get @connections peer-id)]
            (cond
              (some-> ^AtomicBoolean running .get) existing
              (and down-until (< (System/currentTimeMillis) (long down-until))) nil
              :else
              (let [link (start-link! my-id peer-id peer)]
                ;; Ahead of whatever a link it takes up had queued: the
                ;; changes there are older than the snapshot, and the peer
                ;; ignores them (intent/view-apply).
                (when-let [snapshot @view-source]
                  (.putFirst ^LinkedBlockingDeque (:queue link) (view-item (snapshot))))
                (swap! connections assoc peer-id link)
                (.start ^Thread (:thread link))
                link))))))))

(defn- enqueue!
  "Queue `packet` for `peer-id`, holding `publisher` if the queue has grown
   past queue-pause-at. Not sent at all — the peer was unreachable a moment
   ago, or its link is queue-limit behind — it is handed straight back
   through `on-lost`. At the front of the queue rather than the back when
   `front?`."
  ([my-id peer-id peer qos packet publisher on-lost]
   (enqueue! my-id peer-id peer qos packet publisher on-lost nil false))
  ([my-id peer-id peer qos packet publisher on-lost hold]
   (enqueue! my-id peer-id peer qos packet publisher on-lost hold false))
  ([my-id peer-id peer qos packet publisher on-lost hold front?]
   (if-let [{:keys [^LinkedBlockingDeque queue] :as link} (link! my-id peer-id peer)]
     (if (>= (.size queue) (long queue-limit))
       (do (.increment ^LongAdder MqttStat/droppedMessages)
           (lost! peer-id on-lost :queue-full))
       (let [item {:qos qos :packet packet :on-lost on-lost :hold hold}]
         (if front? (.putFirst queue item) (.put queue item))
         ;; The link may have ended between being looked up and this put —
         ;; a peer refusing the connection ends it within a millisecond —
         ;; and its thread has then emptied the queue for the last time.
         ;; Taken back out if it is still there, so it is handed back once:
         ;; here, or by that thread if it got to it first.
         (if (and (not (.get ^AtomicBoolean (:running link))) (.remove queue item))
           (lost! peer-id on-lost :link-ended)
           (when (and publisher (> (.size queue) (long queue-pause-at)))
             (hold! link publisher)))))
     (lost! peer-id on-lost :peer-down))))

(defn drop!
  "Close and forget the connection to `peer-id`, if any: the registry says
   it is gone. Its thread lets go of what it held on the way out, and what
   the peer had taken and not yet delivered is given up on now: a broker
   the registry has dropped is not going to deliver it."
  [peer-id]
  (give-up-awaiting! peer-id Long/MAX_VALUE)
  (when-let [{:keys [^LinkedBlockingDeque queue inflight]} (.remove ^ConcurrentHashMap carried peer-id)]
    (let [queued (java.util.ArrayList.)]
      (.drainTo queue queued)
      (hand-back! peer-id queued inflight)))
  (let [link (get @connections peer-id)]
    (swap! connections dissoc peer-id)
    (when-let [^AtomicBoolean running (:running link)]
      (.set running false)
      (.interrupt ^Thread (:thread link))
      (when (realized? (:client link))
        (try (client/close @(:client link)) (catch Exception _ nil))))))

(defn close-all!
  "Close every link: this broker is leaving the cluster. What its peers
   have taken and not yet delivered is forgotten rather than given up on —
   they are alive, and queuing it too would deliver it twice."
  []
  (.clear ^ConcurrentHashMap awaiting)
  (doseq [peer-id (distinct (concat (keys @connections) (keys carried)))]
    (drop! peer-id)))

(defn send-to!
  "Publish `msg` to `peer-id` at `peer` — {:host :port} — as this broker,
   telling it which shared groups are its to serve. Queued for the link's
   thread; `:publisher` in `msg`, the Connection it came in on, is what is
   held if the link falls behind, and `:on-lost`, a function of no
   arguments, is called if a QoS 1 or 2 message does not reach the peer:
   the peer is unreachable, or goes before acknowledging it.
   `:on-undelivered`, with a `:msg-key`, is called if the peer takes the
   message but goes before saying its subscribers have it (see `awaiting`).
   `:view-v` is the version of this broker's view the copy was planned at,
   and `:not-served` the clients that view has on the peer that this broker
   delivered to itself (see mqttkat.intent).

   Retain is off on the way out: what is retained is recorded once, by the
   publisher's broker, and the other end must not store a copy under its own
   name. Version 5 on the wire whatever the publisher spoke, so the
   properties travel; the receiving broker strips them for its 3.1.1
   subscribers as it does for any publish."
  [my-id peer-id peer group-keys topic {:keys [qos payload properties publisher on-lost on-undelivered groups-only? msg-key
                                               view-v not-served]}]
  (let [qos (long (or qos 0))]
    (enqueue! my-id peer-id peer qos
              {:packet-type      :PUBLISH
               :protocol-version 5
               :topic            topic
               :qos              qos
               :payload          payload
               :retain?          false
               :duplicate?       false
               :properties       (cond-> (with-shares (or properties {}) group-keys)
                                   groups-only? (update :user-properties (fnil conj [])
                                                        [groups-only-property "1"])
                                   msg-key      (update :user-properties (fnil conj [])
                                                        [msg-key-property msg-key])
                                   view-v       (update :user-properties (fnil conj [])
                                                        [view-v-property (str view-v)])
                                   (seq not-served) (update :user-properties (fnil into [])
                                                            (map #(vector not-property %))
                                                            (sort not-served)))}
              publisher
              ;; Only a message the peer acknowledges can be lost: at QoS 0
              ;; there is nothing to hand back.
              (when (pos? qos) on-lost)
              (when (and (pos? qos) msg-key on-undelivered)
                {:msg-key msg-key :on-undelivered on-undelivered}))))

(defn takeover!
  "Tell `peer-id` that `client-id` has connected here, so the connection it
   holds for it — named by its connect-id, so a newer one is left alone —
   is to end (§3.1.4). QoS 0: if the peer is not there to hear it, the
   connection it held is not there either.

   At the front of the link's queue, ahead of the publishes waiting there.
   Behind them, as it once went, it waited as long as they did: a load run
   with its bridges a minute behind had the new broker give up on the old
   one's hand-over (handlers/hand-over-wait-millis) and resume without it.
   What it overtakes for the client reaches the old broker after the client
   has gone from there, and is queued for it on the cluster as for any
   client the sender had there and that did not get it live."
  [my-id peer-id peer client-id connect-id]
  (enqueue! my-id peer-id peer 0
            {:packet-type      :PUBLISH
             :protocol-version 5
             :topic            (str control-prefix "takeover")
             :qos              0
             :payload          (byte-array 0)
             :retain?          false
             :duplicate?       false
             :properties       {:user-properties [["client-id" client-id]
                                                  ["connect-id" (str connect-id)]]}}
            nil nil nil true))

(defn settled!
  "Tell `peer-id` that the messages named `msg-keys`, which it forwarded
   here, have reached every subscriber here that keeps its session — see
   `awaiting`. QoS 0, down the same queue as everything else: if the link
   goes, the peer gives up on what it was waiting for anyway."
  [my-id peer-id peer msg-keys]
  (when (seq msg-keys)
    (enqueue! my-id peer-id peer 0
              {:packet-type      :PUBLISH
               :protocol-version 5
               :topic            (str control-prefix "settled")
               :qos              0
               :payload          (.getBytes ^String (str/join "\n" msg-keys) "UTF-8")
               :retain?          false
               :duplicate?       false
               :properties       {}}
              nil nil)))

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
  (into #{} (keep (fn [[id {:keys [client]}]] (when (and client (realized? client)) id))) @connections))
