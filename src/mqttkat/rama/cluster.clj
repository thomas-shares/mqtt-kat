(ns mqttkat.rama.cluster
  "The broker's connection to its Rama module, in either of two places.

   The module is the same either way; what differs is where it runs. This
   namespace is the one switch, so nothing else in the broker knows which:

     -Dmqttkat.rama=off          no Rama at all. The default, and what the
                                 test suite's broker runs with.
     -Dmqttkat.rama=in-process   an InProcessCluster inside the broker's own
                                 JVM, with the module launched into it on
                                 start. No cluster to run, nothing to deploy;
                                 `(start)` in the REPL and `lein test` both
                                 work on a clean machine. Its data lives in a
                                 temporary directory and goes with the JVM,
                                 so this is durability across a client's
                                 reconnect, not across a broker restart.
     -Dmqttkat.rama=external     a foreign client of a real cluster, which
                                 must already be running with the module
                                 deployed to it (see the README). The
                                 Conductor is -Dmqttkat.rama.conductor, or
                                 localhost. This is the one whose data
                                 survives a broker restart.

   `connect` returns the same map of handles in both modes, and every read or
   append below goes through those handles, so code above this line cannot
   tell the two apart. That is the point: the module is developed and tested
   in-process and run against the cluster unchanged.

   Two directions. Up: the broker's events — connects, disconnects,
   subscribes, unsubscribes — are appended to the module. Down: the module's
   `$$subscriptions` is watched, one reactive proxy per shard, and every
   change Rama pushes is applied to an in-memory trie here, `:trie` on the
   connection. That trie holds every subscription in the cluster, whichever
   broker took it, and it is the piece that lets several brokers stand in
   front of one Rama: a publish anywhere is matched in memory against
   subscriptions made anywhere, delivered here to this broker's clients, and
   forwarded — over MQTT, see mqttkat.bridge — to the brokers holding the
   rest. The brokers find each other the same way: `$$brokers`, watched
   with one more proxy, is where each announces where it listens."
  (:require [clojure.tools.logging :as log]
            [com.rpl.rama :as r]
            [com.rpl.rama.path :refer [keypath ALL]]
            [com.rpl.rama.test :as rtest]
            [mqttkat.bridge :as bridge]
            [mqttkat.events :as events]
            [mqttkat.handlers :as handlers]
            [mqttkat.rama.module :as module]
            [mqttkat.retained :as retained]
            [mqttkat.trie :as trie])
  (:import [java.net InetAddress]
           [java.util.function BiConsumer]))

(def broker-id
  "This broker's name in the cluster, stamped on every subscription it
   records, so another broker knows whose client it is. -Dmqttkat.brokerId,
   or the host name."
  (or (System/getProperty "mqttkat.brokerId")
      (.getHostName (InetAddress/getLocalHost))))

(def incarnation
  "This run of this broker, as distinct from the last one under the same
   name. Stamped on the announcement and on every connect, so that when the
   announcement arrives the record can tell a client this run accepted from
   one the previous run was still holding — the order the two reach Rama in
   is not promised."
  (str (java.util.UUID/randomUUID)))

(def advertised-host
  "Where the other brokers should connect to reach this one.
   -Dmqttkat.advertise, or the host name."
  (or (System/getProperty "mqttkat.advertise")
      (.getHostName (InetAddress/getLocalHost))))

(defn mode
  "Which of the three, from -Dmqttkat.rama."
  []
  (keyword (or (System/getProperty "mqttkat.rama") "off")))

(def in-process-config
  "How the module is launched into an InProcessCluster.

   Four tasks is enough to make partitioning real — a client id that lands
   on the wrong task is a bug that one task would hide — without the startup
   cost of more. tasks >= threads >= workers is Rama's own rule."
  {:tasks 4 :threads 2 :workers 1})

(defn- in-process []
  (let [ipc (rtest/create-ipc)]
    (rtest/launch-module! ipc module/MqttKatModule in-process-config)
    ipc))

(def conductor-host
  "Where the Conductor of an external cluster is: -Dmqttkat.rama.conductor,
   or localhost. Always given to Rama explicitly: left to itself it looks for
   a rama.yaml on the classpath, and the uberjar has none, which it reports
   as an invalid config rather than a missing file."
  (or (System/getProperty "mqttkat.rama.conductor") "localhost"))

(def append-flush-millis
  "How long the depot client gathers appends before sending them as one.
   Zero sends each on its own, and each is a durable write on the depot's
   task before the next: a few hundred a second, which two thousand
   subscribers connecting at once turn into a backlog the topology never
   catches up with. A few milliseconds of gathering costs each append a
   few milliseconds and buys an order of magnitude in throughput.
   -Dmqttkat.rama.flushMillis to change it."
  (parse-long (or (System/getProperty "mqttkat.rama.flushMillis") "5")))

(defn- external []
  (log/info "connecting to the Rama conductor at" conductor-host)
  (r/open-cluster-manager {"conductor.host" conductor-host
                           "foreign.depot.flush.delay.millis" append-flush-millis}))

(declare connect-to)

(defn connect
  "Open the module in `mode` (default: what -Dmqttkat.rama says) and return
   its handles:

     :cluster        the InProcessCluster or cluster manager, for close!
     :events         depot client, for the session events
     :sessions       PState client, client-id -> the last CONNECT, whether
                     it is still connected, a count, and its subscriptions
     :subscriptions  PState client, shard -> filter -> client-id -> entry
     :brokers-state  PState client, the registry
     :retained-state PState client, shard -> topic -> retained message
     :queued-state   PState client, client-id -> key -> queued message
     :trie           atom, the in-memory copy of every subscription in the
                     cluster; empty until watch!
     :brokers        atom, the in-memory copy of the registry:
                     broker-id -> {:host :port :at}; empty until watch!
     :settings       atom, the cluster's settings: name -> value
     :proxies        atom, the proxies feeding both

   Blocks until the module is reachable. In-process that means launching it,
   which takes a few seconds."
  ([] (connect (mode)))
  ([mode]
   (let [cluster (case mode
                   :in-process (in-process)
                   :external   (external))]
     (log/info "Rama" (name mode) "- module" (r/get-module-name module/MqttKatModule))
     (assoc (connect-to cluster) :mode mode :owns-cluster? true))))

(defn connect-to
  "Handles on an already open cluster. What a second broker in the same
   JVM — a test — uses to stand next to the first."
  [cluster]
  (let [module-name (r/get-module-name module/MqttKatModule)]
    {:cluster       cluster
     :module-name   module-name
     :events        (r/foreign-depot cluster module-name "*session-events")
     :sessions      (r/foreign-pstate cluster module-name "$$sessions")
     :subscriptions (r/foreign-pstate cluster module-name "$$subscriptions")
     :brokers-state (r/foreign-pstate cluster module-name "$$brokers")
     :settings-state (r/foreign-pstate cluster module-name "$$settings")
     :retained-state (r/foreign-pstate cluster module-name "$$retained")
     :queued-state  (r/foreign-pstate cluster module-name "$$queued")
     :trie          (atom (trie/make-trie))
     :brokers       (atom {})
     :settings      (atom {})
     :proxies       (atom [])}))

(defn unwatch!
  "Close the shard proxies, if open. The trie keeps what it had."
  [{:keys [proxies]}]
  (doseq [p @proxies]
    (try (r/close! p) (catch Exception e (log/debug e "closing a proxy"))))
  (reset! proxies []))

(defn close!
  "Release the connection. In-process, when this connection opened the
   cluster, that shuts it down and deletes its data."
  [{:keys [cluster owns-cluster?] :as conn}]
  (unwatch! conn)
  (when owns-cluster?
    (r/close! cluster)))

;; ── what the broker appends and reads ────────────────────────────────────

(defn ->connect
  "The `*session-events` record for a CONNECT the broker has accepted.

   Takes the broker's own CONNECT map, so this is the whole of the mapping
   between the two: the names are the same, and the two things a version 4
   client does not send default to what §3.1.2.11 says they mean — no
   session expiry interval is 0, session ends with the connection.

   The connect-id is the broker's name for this one connection, minted when
   the session was accepted, and is what lets the topology tell a record it
   is seeing again from a client connecting again: two connects from one
   client are two records with two ids, and one record replayed carries the
   same id both times. It is also what the disconnect will name."
  [{:keys [connect-id client-id protocol-version clean-session? keep-alive properties]}]
  {:event                   :connect
   :connect-id              (or connect-id (str (java.util.UUID/randomUUID)))
   :client-id               client-id
   :broker-id               broker-id
   :incarnation             incarnation
   :protocol-version        (long protocol-version)
   :clean-session?          (boolean clean-session?)
   :keep-alive              (long (or keep-alive 0))
   :session-expiry-interval (long (or (:session-expiry-interval properties) 0))
   :at                      (System/currentTimeMillis)})

(defn ->disconnect
  "The `*session-events` record for a connection that has ended, however it
   ended — a DISCONNECT, a dropped socket, a takeover. Names the connection,
   so a disconnect that reaches Rama after the client is already back on a
   new one marks nothing."
  [{:keys [connect-id client-id session-expiry-interval]}]
  (cond-> {:event      :disconnect
           :connect-id connect-id
           :client-id  client-id
           :at         (System/currentTimeMillis)}
    session-expiry-interval (assoc :session-expiry-interval (long session-expiry-interval))))

(defn ->subscribe
  "The record for a subscription the broker has accepted: the filter as the
   client sent it, and the broker's own entry for it."
  [{:keys [connect-id client-id filter entry]}]
  {:event      :subscribe
   :connect-id connect-id
   :client-id  client-id
   :filter     filter
   :entry      entry
   :at         (System/currentTimeMillis)})

(defn ->unsubscribe
  "The record for a subscription the client has given up."
  [{:keys [connect-id client-id filter]}]
  {:event      :unsubscribe
   :connect-id connect-id
   :client-id  client-id
   :filter     filter
   :at         (System/currentTimeMillis)})

(defn ->broker-up
  "The record announcing this broker: where the others can reach it."
  [host port]
  {:event       :broker-up
   :broker-id   broker-id
   :incarnation incarnation
   :host        host
   :port        (long port)
   :at          (System/currentTimeMillis)})

(defn ->enqueue
  "The record queuing `message` for `client-id` while it is away. The key
   orders the queue and names the message: the time first, so a resume gets
   them back in the order they were queued, then enough randomness that two
   queued in the same millisecond, here or on another broker, are two."
  [client-id message]
  (let [now (System/currentTimeMillis)]
    {:event     :enqueue
     :client-id client-id
     :key       (format "%013d-%s" now (subs (str (java.util.UUID/randomUUID)) 0 8))
     :message   (assoc message :queued-at now)
     :at        now}))

(defn ->dequeue
  "The record for messages taken off `client-id`'s queue, by key."
  [client-id keys]
  {:event :dequeue :client-id client-id :keys (vec keys) :at (System/currentTimeMillis)})

(defn ->broker-stats
  "The record of how this broker is doing, for the others' consoles."
  [stats]
  {:event       :broker-stats
   :broker-id   broker-id
   :incarnation incarnation
   :stats       stats
   :at          (System/currentTimeMillis)})

(defn ->setting
  "The record of an operator's choice for the whole cluster."
  [key value]
  {:event :setting :key (name key) :value value :at (System/currentTimeMillis)})

(defn ->redirected
  "The record that `client-id` was sent to broker `to`."
  [client-id to]
  {:event :redirected :client-id client-id :to to :at (System/currentTimeMillis)})

(defn ->broker-down
  "The record withdrawing this broker."
  []
  {:event     :broker-down
   :broker-id broker-id
   :at        (System/currentTimeMillis)})

(defn- payload->text
  "A payload as `$$retained` stores it: Base64, not the bytes. The proxies
   check a diff they applied against a hash of the value on the server, and
   a byte array hashes by identity — every change would fail that check and
   resync the whole shard. Text hashes by content."
  [message]
  (if-let [^bytes p (:payload message)]
    (assoc message :payload (.encodeToString (java.util.Base64/getEncoder) p))
    message))

(defn- text->payload
  "The reverse. Bytes are left as they are: what was recorded before the
   payload became text is still bytes, and is still a payload."
  [message]
  (let [p (:payload message)]
    (if (string? p)
      (assoc message :payload (.decode (java.util.Base64/getDecoder) ^String p))
      message)))

(defn ->retain
  "The record for a message retained on `topic`, or with nil, for the topic
   having none any more."
  [topic message]
  (if message
    {:event :retain :topic topic :message (payload->text message) :at (System/currentTimeMillis)}
    {:event :unretain :topic topic :at (System/currentTimeMillis)}))

(defn record!
  "Append `event` — a map as one of the `->` functions builds — and return
   a future that completes once the session record reflects it.

   Asynchronous, because this is called on the connection's own thread, and
   a network round trip to Rama is not something a CONNACK or a close should
   wait for: the broker has the session the moment add-client! returns, and
   Rama's copy catching up a few milliseconds later changes nothing the
   client can see. A failure is logged rather than thrown for the same reason
   — the session exists whether or not the record of it made it."
  [{:keys [events]} {:keys [event client-id broker-id] :as record}]
  (doto (r/foreign-append-async! events record :ack)
    (.whenComplete (reify BiConsumer
                     (accept [_ _ e]
                       (when e
                         (log/warn e "could not record the" (name event) "of"
                                   (or client-id broker-id (:topic record)))))))))

(defn session
  "The record for `client-id`, or nil if it has never connected."
  [{:keys [sessions]} client-id]
  (r/foreign-select-one (keypath client-id) sessions))

(defn connected?
  "Whether `client-id`'s last connection is still up, as far as Rama knows."
  [{:keys [sessions]} client-id]
  (boolean (r/foreign-select-one (keypath client-id :connected?) sessions)))

(defn connections
  "How many times `client-id` has connected; 0 if never."
  [{:keys [sessions]} client-id]
  (or (r/foreign-select-one (keypath client-id :connections) sessions) 0))

(defn subscriptions
  "`client-id`'s subscriptions as the session holds them: filter -> entry."
  [{:keys [sessions]} client-id]
  (or (r/foreign-select-one (keypath client-id :subscriptions) sessions) {}))

(defn queued
  "What is waiting for `client-id`, oldest first: a seq of [key message]."
  [{:keys [queued-state]} client-id]
  (r/foreign-select [(keypath client-id) ALL] queued-state))

(defn resume
  "What a broker needs on `client-id`'s CONNECT: {:session the record,
   :subscriptions filter->entry, :queued [[key msg]…]} — the last two for a
   persistent session, empty for a clean one — or nil when Rama has never
   seen the client. One read, two for a persistent session, on the CONNECT
   path only."
  [conn client-id]
  (when-let [s (session conn client-id)]
    (if (false? (:clean-session? s))
      {:session       s
       :subscriptions (or (:subscriptions s) {})
       :queued        (vec (queued conn client-id))}
      {:session s :subscriptions {} :queued []})))

;; ── the copy of the cluster's subscriptions ──────────────────────────────

(defn- apply-shard-change!
  "Bring `trie` from what shard `old` held to what `new` holds.

   Worked out from the two values rather than from the diff Rama sent with
   them: the diff says the same thing, but reading it means depending on
   its classes, and the values are enough. A ProxyState applies each diff
   to its value structurally, so a filter's map that did not change is the
   same object before and after, and the walk below skips it on identity —
   the cost is the number of filters in the shard, not of entries, and the
   entries touched are exactly the changed ones. The first change and any
   resync arrive with an old of nil, and this then inserts the lot."
  [trie old new]
  (swap! trie
         (fn [t]
           (reduce (fn [t f]
                     (let [ov (get old f) nv (get new f)]
                       (if (identical? ov nv)
                         t
                         (reduce (fn [t c]
                                   (let [oe (get ov c) ne (get nv c)]
                                     (if (= oe ne)
                                       t
                                       (cond-> t
                                         oe (trie/trie-delete (:topic-filter oe) oe)
                                         ne (trie/trie-insert (:topic-filter ne) ne)))))
                                 t
                                 (into (set (keys ov)) (keys nv))))))
                   t
                   (into (set (keys old)) (keys new))))))

(defn- apply-retained-change!
  "Bring the broker's retained messages from what shard `old` held to what
   `new` holds. The same walk as the subscriptions', one level shallower.
   Straight into the store, not through retain! — this is the record
   arriving, not a new write to record."
  [old new]
  (doseq [topic (into (set (keys old)) (keys new))]
    (let [ov (get old topic) nv (get new topic)]
      (when-not (identical? ov nv)
        (retained/sync! topic (some-> nv text->payload))))))

(defn- registry-changed!
  "The registry as Rama now has it. A broker that has gone is also a
   connection the bridge should not keep."
  [brokers old new]
  (reset! brokers (or new {}))
  (doseq [gone (remove #(contains? new %) (keys old))]
    (log/info "broker" gone "left the cluster")
    (bridge/drop! gone)))

(defn- guarded
  "A proxy callback that cannot take the broker down. An exception thrown
   from a callback comes back out of foreign-proxy on the first call and
   terminates the proxy on a later one; one bad entry must do neither."
  [what f]
  (fn [new diff old]
    (try
      (f new diff old)
      (catch Throwable t
        (log/error t "applying a change to" what "failed")))))

(defn watch!
  "Open a proxy on every shard of `$$subscriptions` and keep `:trie` fed
   from them, and one on the registry for `:brokers`. The first callback of
   each carries the value as it stands, so this is also how both are built
   on start: no scan, no separate load, the same code path as any later
   change. Idempotent."
  [{:keys [subscriptions brokers-state retained-state settings-state trie brokers settings proxies] :as conn}]
  (when (empty? @proxies)
    (reset! proxies
            (doall
             (concat
              [(r/foreign-proxy (keypath module/registry-key) brokers-state
                                {:callback-fn (guarded "the registry"
                                                       (fn [new _diff old]
                                                         (registry-changed! brokers old new)))})
               (r/foreign-proxy (keypath module/settings-key) settings-state
                                {:callback-fn (guarded "the settings"
                                                       (fn [new _diff _old]
                                                         (reset! settings (or new {}))))})]
              (for [shard (range module/shard-count)]
                (r/foreign-proxy (keypath shard) subscriptions
                                 {:callback-fn (guarded (str "subscriptions shard " shard)
                                                        (fn [new _diff old]
                                                          (apply-shard-change! trie old new)))}))
              (for [shard (range module/shard-count)]
                (r/foreign-proxy (keypath shard) retained-state
                                 {:callback-fn (guarded (str "retained shard " shard)
                                                        (fn [new _diff old]
                                                          (apply-retained-change! old new)))}))))))
  conn)

(defn matching-subscriptions
  "Every subscription in the cluster whose filter matches `topic`, as the
   trie has it now: the broker's entry plus `:client-id` and `:broker-id`.
   Raw matches — the $-topic rule and the shared-group choice are the
   caller's, as they are for the broker's own tries."
  [{:keys [trie]} topic]
  (trie/trie-matching-vals @trie topic))

(defn remote-brokers
  "The other brokers holding a subscription that matches `topic` — each
   once, however many of its clients match: it fans out to them itself.
   The $-topic rule applies here as it does to the broker's own matching."
  [{:keys [trie]} topic]
  (into #{}
        (comp (map :broker-id)
              (remove #(= broker-id %)))
        (trie/sieve-dollar topic (trie/trie-matching-vals @trie topic))))

(defonce ^:private share-cursor
  ;; group-key -> how many times a broker has been chosen for it here, so
  ;; the choice rotates over the brokers holding members. Each broker
  ;; rotates on its own; over many publishes from many places the load
  ;; still spreads.
  (atom {}))

(defn- choose-broker
  "Which broker serves a shared group for this publish: rotating over the
   brokers that hold a member, in a fixed order so the rotation is one."
  [group-key broker-ids]
  (let [ordered (vec (sort broker-ids))
        i       (get (swap! share-cursor update group-key (fnil inc -1)) group-key)]
    (nth ordered (mod i (count ordered)))))

(defn plan
  "Where a publish on `topic` goes besides this broker — see
   mqttkat.bridge/planner for the shape — or nil when nowhere.

   Ordinary subscriptions send a copy to every other broker holding one.
   A shared group is served by one broker for this publish, chosen here
   where all its members are visible: if that is this broker the group is
   served as usual, otherwise it is skipped here and the chosen broker is
   told to serve it, and the message is sent there whether or not it holds
   anything else. Every other broker that gets a copy for its ordinary
   subscribers is told nothing about the group, and serves none of it."
  [{:keys [trie]} topic]
  (let [matches (trie/sieve-dollar topic (trie/trie-matching-vals @trie topic))
        ;; A subscription whose client is away is nobody's to deliver: it is
        ;; queued, here, whichever broker parked it — see :queue below. Unless
        ;; the client is live on this very broker: the local trie delivers to
        ;; it, and this copy of the table saying otherwise is a diff that has
        ;; not arrived yet. Queuing as well would deliver twice on its next
        ;; resume.
        {parked true present false} (group-by #(and (false? (:connected? %))
                                                    (nil? (handlers/live-connection (:client-id %))))
                                              matches)
        {shared true ordinary false} (group-by #(some? (:share-group %)) present)
        remote  (into #{} (comp (map :broker-id) (remove #(= broker-id %))) ordinary)
        chosen  (for [[gk members] (group-by (juxt :share-group :topic-filter) shared)]
                  [gk (choose-broker gk (distinct (map :broker-id members)))])
        skip    (into #{} (comp (remove #(= broker-id (second %))) (map first)) chosen)
        brokers (reduce (fn [m [gk b]]
                          (if (= broker-id b) m (update m b (fnil conj []) gk)))
                        (zipmap remote (repeat []))
                        chosen)
        ;; One entry per client that is away, at the highest QoS of its
        ;; matching subscriptions (§3.3.5-1), as a live delivery would be.
        ;; A shared group's away members are left out: the group has
        ;; present members to take the message, or it has nobody.
        queue   (->> parked
                     (remove :share-group)
                     (reduce (fn [m {:keys [client-id qos]}]
                               (update m client-id (fnil max 0) (long (or qos 0))))
                             {})
                     (mapv (fn [[client-id qos]] {:client-id client-id :qos qos})))]
    (when (or (seq brokers) (seq skip) (seq queue))
      {:brokers brokers :skip skip :queue queue})))

(defn forward-publish!
  "What the bridge's forwarder does when this broker is attached: `plan`,
   carried out at the addresses the registry has. A broker the registry
   does not know yet gets nothing — its subscriptions arrived before its
   announcement, which the next publish will find."
  [{:keys [brokers] :as conn} plan topic {:keys [qos payload properties] :as msg}]
  (doseq [[peer-id group-keys] (:brokers plan)]
    (if-let [peer (get @brokers peer-id)]
      (bridge/send-to! broker-id peer-id peer group-keys topic msg)
      (log/debug "no address for broker" peer-id "- not forwarding" topic)))
  ;; §4.1 keeps QoS 1 and 2 for a session that is away; at-most-once means a
  ;; message for a client that is not there has already been delivered as
  ;; well as it is going to be. The QoS kept is the lesser of the publish
  ;; and the subscription, as it would be on delivery.
  (when (pos? (long (or qos 0)))
    (doseq [{:keys [client-id] sub-qos :qos} (:queue plan)
            :when (pos? (long sub-qos))]
      (record! conn (->enqueue client-id {:topic      topic
                                         :payload    payload
                                         :properties properties
                                         :qos        (min (long qos) (long sub-qos))})))))

;; ── the running broker's connection ──────────────────────────────────────

(defonce ^{:dynamic true
           :doc "The connection the running broker holds, when -Dmqttkat.rama
                 is not off. One per JVM, like the server itself."}
  *connection* (atom nil))

(def ack-wait-millis
  "How long a SUBSCRIBE or UNSUBSCRIBE waits for the cluster to have it
   before being acknowledged anyway. Long enough for a busy cluster, short
   enough that a client is not left hanging by one that is down."
  5000)

(defn- awaited
  "Wait for `future`, and no longer than ack-wait-millis: the acknowledgement
   still goes out if the cluster is slow, and the failure is logged where
   record! logs it."
  [future]
  (try
    (deref future ack-wait-millis nil)
    (catch Exception _ nil)))

(defn brokers
  "Every broker in the cluster as the registry has it, this one included:
   broker-id -> {:host :port :at :incarnation :stats :stats-at}. Empty when
   not attached."
  []
  (if-let [c @*connection*]
    @(:brokers c)
    {}))

(defn attached?
  "Whether the running broker has a cluster."
  []
  (some? @*connection*))

;; ── redirecting connections ──────────────────────────────────────────────

(def redirect-policies
  "How a broker answers a version 5 CONNECT (§4.13, reason 0x9C Use another
   server, with a Server Reference):

     :off          it takes every client itself
     :round-robin  it takes its turn and sends the rest on, one to each
                   other broker in turn — every broker does, so together
                   they spread arrivals evenly wherever they came in
     :load         it sends the client to whichever broker has the fewest
                   clients, as they last reported, itself included

   A setting for the whole cluster, kept in $$settings; 3.1.1 clients, which
   have no way to be told, are always taken."
  [:off :round-robin :load])

(def redirect-setting "redirect")

(def redirect-via-setting "redirect-via")

(def redirect-vias
  "How a client sent elsewhere is told (§4.13):

     :disconnect  it is accepted — CONNACK Success, with Session Present as
                  the cluster knows it — and at once sent DISCONNECT Use
                  another server with the Server Reference. The default:
                  a DISCONNECT with a reference is what most client
                  libraries act on.
     :connack     CONNACK Use another server with the Server Reference,
                  and the close §3.2.2.2 requires after it."
  [:disconnect :connack])

(defn redirect-via
  "The way in force, :disconnect when none is set or there is no cluster."
  []
  (if-let [c @*connection*]
    (let [v (get @(:settings c) redirect-via-setting)]
      (or (some #{(keyword (str v))} redirect-vias) :disconnect))
    :disconnect))

(defn redirect-policy
  "The policy in force, :off when none is set or there is no cluster."
  []
  (if-let [c @*connection*]
    (let [v (get @(:settings c) redirect-setting)]
      (or (some #{(keyword (str v))} redirect-policies) :off))
    :off))

(defn setting!
  "Set `key` to `value` for the whole cluster, and wait for the cluster to
   have it, so the page that asked shows it on its next load."
  [key value]
  (when-let [c @*connection*]
    (awaited (record! c (->setting key value)))))

(defonce ^:private redirect-cursor (atom -1))

(defonce ^:private sent-since-report
  ;; broker-id -> {:stats-at t :sent n}: how many clients this broker has
  ;; sent to each other broker since that broker last reported. Reports
  ;; come every few seconds and sixty clients can arrive in one; judged on
  ;; the report alone they would all go to the same, briefly emptiest,
  ;; broker.
  (atom {}))

(defn- estimated-clients
  "A broker's client count as last reported, plus what has been sent there
   since."
  [id {:keys [stats stats-at]}]
  (let [{:keys [sent] :as seen} (get @sent-since-report id)]
    (+ (long (or (:clients stats) 0))
       (if (and seen (= stats-at (:stats-at seen))) (long sent) 0))))

(defn- note-sent! [id stats-at]
  (swap! sent-since-report update id
         (fn [{:keys [sent] :as seen}]
           (if (and seen (= stats-at (:stats-at seen)))
             {:stats-at stats-at :sent (inc (long sent))}
             {:stats-at stats-at :sent 1}))))

(defn- candidates
  "The brokers a client could be sent to: those the registry has an address
   for and has heard from lately, this one included, in a fixed order."
  []
  (let [now (System/currentTimeMillis)]
    (->> (brokers)
         (filter (fn [[id {:keys [host port stats-at at]}]]
                   (and host port
                        (or (= id broker-id)
                            (< (- now (long (max (or stats-at 0) (or at 0))))
                               (* 3 60000))))))
         (sort-by key)
         vec)))

(defn redirect-target
  "Where to send `client-id`, connecting in version 5 — {:server-reference
   \"host:port\" :via :disconnect|:connack :session-present? bool} — or nil
   to take it here. Never the broker itself, never anywhere when there is
   nowhere else (a cluster of one takes everything), and never a client
   that was sent here: the broker that sent it noted so on its session
   record, and a client passed on from one broker to the next would
   otherwise never land. The note is written, and waited for, before the
   client is answered, so it is there when the client arrives.

   Session Present travels with the answer for the CONNACK that accepts the
   client before it is sent on: a persistent client told 0 discards its own
   session state (§3.2.2.1.1), and that is the cluster's to say, not this
   broker's parked copies'."
  [client-id]
  (let [policy (redirect-policy)
        cs     (when (not= :off policy) (candidates))
        record (when (next cs) (some-> @*connection* (session client-id)))
        chosen (when (and (next cs) (not= (:sent-to record) broker-id))
                 (case policy
                   :round-robin (nth cs (mod (swap! redirect-cursor inc) (count cs)))
                   :load        (first (sort-by (fn [[id entry]] [(estimated-clients id entry) id])
                                                cs))))]
    (when-let [[id {:keys [host port stats-at]}] chosen]
      (when (not= id broker-id)
        (note-sent! id stats-at)
        (awaited (record! @*connection* (->redirected client-id id)))
        {:server-reference (str host ":" port)
         :via              (redirect-via)
         :session-present? (boolean (and record
                                         (module/kept? (:protocol-version record)
                                                       (:clean-session? record)
                                                       (:session-expiry-interval record))))}))))

(defonce ^:private announced-port
  ;; The port this broker announced itself on, so it can announce itself
  ;; again: an announcement lost to a timeout — a cluster still digesting a
  ;; backlog, say — would otherwise leave the broker unregistered for the
  ;; rest of its life, forwarding to everyone and reached by nobody.
  (atom nil))

(defn- on-broker-event
  "What the broker tells its listeners, turned into a session event. The
   four about a session, and the console's sample of this broker's figures;
   the broker says other things too."
  [{:keys [event connect] :as broker-event}]
  (when-let [c @*connection*]
    (case event
      ;; Waited for too, for the same reason as the subscribe below: it is
      ;; emitted before the CONNACK goes out, so the client cannot act — nor
      ;; another broker's copy be consulted about it — until the cluster has
      ;; it connected.
      :client-connected    (awaited (record! c (->connect connect)))
      :client-disconnected (record! c (->disconnect broker-event))
      ;; Waited for, these two: the handler emits them before it sends the
      ;; SUBACK or UNSUBACK, so waiting here is what makes the acknowledgement
      ;; mean the cluster has the change — with :ack, the topology has
      ;; written it, and every other broker's copy is being pushed the diff
      ;; as this returns. Unwaited, a subscriber could be acknowledged and a
      ;; publish on another broker miss it for the next hundred milliseconds
      ;; or so, which a load test sees as lost messages. The thread this runs
      ;; on is the connection's own, and is virtual; a few milliseconds
      ;; blocked cost it nothing.
      :client-subscribed   (awaited (record! c (->subscribe broker-event)))
      :client-unsubscribed (awaited (record! c (->unsubscribe broker-event)))
      :broker-sample       (do
                             ;; Not in the registry, as far as this broker
                             ;; can see, though it announced itself: say so
                             ;; again. Harmless when it merely has not been
                             ;; pushed back yet — the same announcement twice
                             ;; is one entry.
                             (when-let [port @announced-port]
                               (when-not (get @(:brokers c) broker-id)
                                 (record! c (->broker-up advertised-host port))))
                             (record! c (->broker-stats (:stats broker-event))))
      nil)))

(defn attach!
  "Make `conn` the broker's connection: record its session events, watch
   the cluster's subscriptions and brokers, and forward publishes to the
   other brokers. What connect! does once the connection is open, split
   out so a test can hand the broker a connection it opened itself."
  [conn]
  (reset! *connection* conn)
  (events/listen! ::rama on-broker-event)
  (reset! bridge/planner (fn [topic] (plan conn topic)))
  (reset! bridge/forwarder (fn [plan topic msg] (forward-publish! conn plan topic msg)))
  (reset! retained/sink (fn [topic message] (record! conn (->retain topic message))))
  (reset! handlers/redirector (fn [client-id] (redirect-target client-id)))
  (reset! handlers/session-source
          {:my-broker-id broker-id
           :resume       (fn [client-id] (resume conn client-id))
           :enqueue!     (fn [client-id msg] (record! conn (->enqueue client-id msg)))
           :dequeue!     (fn [client-id keys] (record! conn (->dequeue client-id keys)))
           :takeover!    (fn [peer-id client-id connect-id]
                           (if-let [peer (get @(:brokers conn) peer-id)]
                             (bridge/takeover! broker-id peer-id peer client-id connect-id)
                             (log/warn "no address for broker" peer-id
                                       "- cannot tell it to drop" client-id)))})
  (watch! conn))

(defn detach!
  "Stop recording and forwarding, and forget the connection, without
   closing it. The proxies stay open until close!."
  []
  (reset! bridge/planner nil)
  (reset! bridge/forwarder nil)
  (reset! retained/sink nil)
  (reset! handlers/redirector nil)
  (reset! handlers/session-source nil)
  (bridge/close-all!)
  (events/forget! ::rama)
  (reset! *connection* nil))

(defn register!
  "Announce this broker at `port` to the others. Called once the broker is
   listening, and so after connect!; and again by every report, for as
   long as this broker's own copy of the registry does not show it."
  [port]
  (reset! announced-port port)
  (when-let [c @*connection*]
    (record! c (->broker-up advertised-host port))))

(defn deregister!
  "Withdraw this broker. Waits for it, unlike the other appends: the
   connection is about to be closed, and an announcement left behind would
   have the others trying to reach a broker that is gone."
  []
  (when-let [c @*connection*]
    (try
      (deref (record! c (->broker-down)) 5000 nil)
      (catch Exception e
        (log/warn e "could not withdraw broker" broker-id)))))

(defn connect!
  "Open the connection the broker will use, unless the mode is off."
  []
  (let [m (mode)]
    (if (= m :off)
      (log/info "Rama off (set -Dmqttkat.rama=in-process or external)")
      (attach! (connect m)))))

(defn disconnect!
  "Withdraw, close and forget the broker's connection, if there is one."
  []
  (when-let [c @*connection*]
    (deregister!)
    (detach!)
    (close! c)))
