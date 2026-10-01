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
            [com.rpl.rama.path :refer [keypath ALL sorted-map-range sorted-map-range-from-start]]
            [com.rpl.rama.test :as rtest]
            [mqttkat.bridge :as bridge]
            [mqttkat.events :as events]
            [mqttkat.handlers :as handlers]
            [mqttkat.rama.module :as module]
            [mqttkat.retained :as retained]
            [mqttkat.trie :as trie])
  (:import [com.rpl.rama ProxyState ProxyState$Status]
           [java.net InetAddress]
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

(declare connect-to queue-writer)

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
     :detail-state   PState client, broker-id -> what that broker's console
                     shows: `$$broker-detail`
     :history-state  PState client, broker-id -> millis -> chart point:
                     `$$broker-history`
     :stats-state    PState client, the module's counts: `$$rama-stats`
     :stats          atom, the in-memory copy of them:
                     task -> {\"sessions\" n … \"at\" millis}
     :proxies        atom, the proxies feeding the atoms

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
     :stats-state   (r/foreign-pstate cluster module-name "$$rama-stats")
     :detail-state  (r/foreign-pstate cluster module-name "$$broker-detail")
     :history-state (r/foreign-pstate cluster module-name "$$broker-history")
     :queue-writer  (queue-writer)
     :trie          (atom (trie/make-trie))
     :brokers       (atom {})
     :settings      (atom {})
     :stats         (atom {})
     :proxies       (atom [])}))

(def proxy-close-wait-millis
  "How long unwatch! waits for its proxies to finish closing."
  5000)

(defn- closed? [^ProxyState p]
  (not= ProxyState$Status/ACTIVE (.status p)))

(defn unwatch!
  "Close the shard proxies, if open, and wait until they have. The trie
   keeps what it had.

   Waited for because a proxy's close is not done when it returns: Rama
   hands the teardown, a round trip to the cluster, to the cluster
   manager's executor, and marks the proxy closed when it is done. close!
   shuts that executor down next, and a teardown still queued behind it
   then fails, each one logging \"Executor pool is shut down\" with a stack
   trace: a screenful on every Ctrl-C against a real cluster."
  [{:keys [proxies]}]
  (let [ps       @proxies
        deadline (+ (System/currentTimeMillis) proxy-close-wait-millis)]
    (doseq [p ps]
      (try (r/close! p) (catch Exception e (log/debug e "closing a proxy"))))
    (loop []
      (when-not (every? closed? ps)
        (if (< (System/currentTimeMillis) deadline)
          (do (Thread/sleep 10) (recur))
          (log/warn (count (remove closed? ps)) "Rama proxies still closing after"
                    proxy-close-wait-millis "ms")))))
  (reset! proxies []))

(defn close!
  "Release the connection. In-process, when this connection opened the
   cluster, that shuts it down and deletes its data."
  [{:keys [cluster owns-cluster? queue-writer] :as conn}]
  (some-> ^java.util.concurrent.atomic.AtomicBoolean (:stopped? queue-writer) (.set true))
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

(defn ->still-connected
  "The record saying a connection this broker holds is still up: the
   CONNECT's terms as ->connect has them, and every subscription the
   connection holds, filter -> entry. Takes the broker's own client map,
   which has both. See restate-clients!."
  [{:keys [subscribed-topics] :as client}]
  (assoc (->connect client)
         :event :still-connected
         :subscriptions (into {} (map (fn [e] [(or (:filter e) (:topic-filter e)) e]))
                              subscribed-topics)))

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
   orders the queue and names the message: `key`, the name the message was
   given where it entered the cluster, so that every broker queuing it for
   the client queues the one entry — or a new name when it has none. Either
   way the time comes first, so a resume gets them back in order; see
   mqttkat.handlers/new-message-key."
  ([client-id message] (->enqueue client-id message nil))
  ([client-id message key]
   (let [now (System/currentTimeMillis)]
     {:event     :enqueue
      :client-id client-id
      :key       (or key (handlers/new-message-key))
      :message   (assoc message :queued-at now)
      :at        now})))

(defn ->dequeue
  "The record for messages taken off `client-id`'s queue, by key."
  [client-id keys]
  {:event :dequeue :client-id client-id :keys (vec keys) :at (System/currentTimeMillis)})

(defn ->broker-stats
  "The record of how this broker is doing, for the others' consoles: the
   few figures the registry carries, and — when given — the detail its own
   console shows and the chart points taken since the last report."
  ([stats] (->broker-stats stats nil nil))
  ([stats detail samples]
   (cond-> {:event       :broker-stats
            :broker-id   broker-id
            :incarnation incarnation
            :stats       stats
            :at          (System/currentTimeMillis)}
     detail        (assoc :detail detail)
     (seq samples) (assoc :samples (vec samples)))))

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

;; ── the queues, written in order and until they land ─────────────────────

(def queue-writes-in-flight
  "How many clients' queue writes may be waiting on Rama at once, for this
   broker as a whole. The foreign client refuses an append once ten
   thousand are pending, and its depot buffer once it holds ten thousand;
   a few hundred subscribers dropping together while the broker is behind
   asked for more than that, and every write refused was a message lost or
   a take-off that never happened. Past this, a client's writes wait their
   turn here and gather into one."
  256)

(def queue-retry-millis
  "How long a queue write that failed waits before it is tried again: the
   first wait, and the longest it grows to as it keeps failing."
  [200 5000])

(defonce ^:private queue-executor
  ;; Where a batch's answer is acted on: its futures completed — which
  ;; sends PUBACKs and lets go of bridge copies — and the client's next
  ;; batch appended. Never on the Rama client's own thread, which those
  ;; would hold up, and which an append made from it can wait on.
  (delay (java.util.concurrent.Executors/newFixedThreadPool
          4 (reify java.util.concurrent.ThreadFactory
              (newThread [_ r]
                (doto (Thread. ^Runnable r "rama-queue") (.setDaemon true)))))))

(defonce ^:private retry-timer
  (delay (java.util.concurrent.Executors/newSingleThreadScheduledExecutor
          (reify java.util.concurrent.ThreadFactory
            (newThread [_ r]
              (doto (Thread. ^Runnable r "rama-queue-retry") (.setDaemon true)))))))

(defn queue-writer
  "What `enqueue!` and `dequeue!` write through: one lane per client, which
   has one batch on its way to Rama at a time. What a client's queue is
   asked for while one is out waits in its lane, and goes as the next batch
   — every enqueue in a row one append, every take-off in a row another —
   so a client the broker hands thousands of messages at once costs a few
   appends rather than thousands, and its writes reach Rama in the order
   they were made. A batch that fails is sent again, the same batch in the
   same order, until it lands: an enqueue replaces under its key and a
   take-off deletes, so a batch that landed once and is sent again ends
   where it did."
  []
  {:lanes    (java.util.concurrent.ConcurrentHashMap.)
   :permits  (java.util.concurrent.Semaphore. (int queue-writes-in-flight))
   :waiting  (java.util.concurrent.ConcurrentLinkedQueue.)
   :stopped? (java.util.concurrent.atomic.AtomicBoolean. false)
   :failures (java.util.concurrent.atomic.AtomicLong. 0)
   :warned   (java.util.concurrent.atomic.AtomicLong. 0)})

(defn- queue-records
  "`ops` for `client-id` — [:enqueue key message if-kept?] and [:dequeue
   keys], in the order they were asked for — as the records to append:
   neighbours of a kind together."
  [client-id ops]
  (let [now (System/currentTimeMillis)]
    (reduce (fn [acc [kind a b c]]
              (let [prev (peek acc)]
                (case kind
                  :enqueue (if (and (= :enqueue (:event prev)) (= (boolean c) (boolean (:if-kept? prev))))
                             (conj (pop acc) (update prev :messages conj [a b]))
                             (conj acc (cond-> {:event :enqueue :client-id client-id :messages [[a b]] :at now}
                                         c (assoc :if-kept? true))))
                  :dequeue (if (= :dequeue (:event prev))
                             (conj (pop acc) (update prev :keys into a))
                             (conj acc {:event :dequeue :client-id client-id :keys (vec a) :at now})))))
            [] ops)))

(defn- run-waiting!
  "Start what waits for a permit, while there are permits."
  [{:keys [^java.util.concurrent.Semaphore permits
           ^java.util.concurrent.ConcurrentLinkedQueue waiting]}]
  (loop []
    (when (.tryAcquire permits)
      (if-let [f (.poll waiting)]
        (do (f) (recur))
        (.release permits)))))

(defn- with-permit! [w f]
  (.add ^java.util.concurrent.ConcurrentLinkedQueue (:waiting w) f)
  (run-waiting! w))

(defn- note-queue-failure! [{:keys [^java.util.concurrent.atomic.AtomicLong failures
                                    ^java.util.concurrent.atomic.AtomicLong warned]}
                            client-id ^Throwable e]
  (let [n    (.incrementAndGet failures)
        now  (System/currentTimeMillis)
        last (.get warned)]
    (when (and (> (- now last) 5000) (.compareAndSet warned last now))
      (log/warn "queue writes failing, trying them again -" n "so far; the last for"
                client-id "-" (or (ex-message e) (str e))))))

(declare start-lane!)

(defn- send-batch! [w conn client-id lane records futs delay-ms]
  (with-permit! w
    (fn []
      (let [done (fn [_ e]
                   (.release ^java.util.concurrent.Semaphore (:permits w))
                   (run-waiting! w)
                   (cond
                     (nil? e)
                     (do (run! #(.complete ^java.util.concurrent.CompletableFuture % nil) futs)
                         (start-lane! w conn client-id lane))

                     (.get ^java.util.concurrent.atomic.AtomicBoolean (:stopped? w))
                     (run! #(.completeExceptionally ^java.util.concurrent.CompletableFuture % e) futs)

                     :else
                     (do (note-queue-failure! w client-id e)
                         (let [again (min (* 2 (long delay-ms)) (long (second queue-retry-millis)))
                               retry ^Runnable #(send-batch! w conn client-id lane records futs again)]
                           (.schedule ^java.util.concurrent.ScheduledExecutorService @retry-timer
                                      ^Runnable #(.execute ^java.util.concurrent.Executor @queue-executor retry)
                                      (long delay-ms) java.util.concurrent.TimeUnit/MILLISECONDS)))))]
        (try
          (.whenCompleteAsync (java.util.concurrent.CompletableFuture/allOf
                               (into-array java.util.concurrent.CompletableFuture
                                           (mapv #(r/foreign-append-async! (:events conn) % :ack) records)))
                              (reify BiConsumer (accept [_ v e] (done v e)))
                              ^java.util.concurrent.Executor @queue-executor)
          (catch Throwable e (done nil e)))))))

(defn- start-lane!
  "Send what `lane` has gathered, or let it go idle — and out of the map —
   when it has nothing."
  [w conn client-id lane]
  (let [[before _] (swap-vals! lane (fn [s] (if (seq (:ops s))
                                              (assoc s :ops [] :futs [])
                                              (assoc s :busy? false :gone? true))))]
    (if (seq (:ops before))
      (send-batch! w conn client-id lane (queue-records client-id (:ops before)) (:futs before)
                   (first queue-retry-millis))
      (.remove ^java.util.concurrent.ConcurrentHashMap (:lanes w) client-id lane))))

(defn- queue-op!
  "Put `op` in `client-id`'s lane, and start the lane if it was idle.
   Returns a future that completes once Rama has it."
  [{:keys [queue-writer] :as conn} client-id op]
  (let [w   queue-writer
        fut (java.util.concurrent.CompletableFuture.)]
    (loop []
      (let [lane (.computeIfAbsent ^java.util.concurrent.ConcurrentHashMap (:lanes w) client-id
                                   (reify java.util.function.Function
                                     (apply [_ _] (atom {:ops [] :futs [] :busy? false}))))
            [before after] (swap-vals! lane (fn [s] (if (:gone? s)
                                                      s
                                                      (-> s (update :ops conj op)
                                                          (update :futs conj fut)
                                                          (assoc :busy? true)))))]
        (cond
          ;; Going idle as this arrived: the next lookup finds a new lane.
          (:gone? after)       (do (Thread/onSpinWait) (recur))
          (not (:busy? before)) (start-lane! w conn client-id lane))))
    fut))

(defn enqueue!
  "Queue `message` for `client-id` under `key` (a new one when nil), and
   only if the client's session is kept when `if-kept?`. Returns a future
   that completes once the cluster has it."
  ([conn client-id message key] (enqueue! conn client-id message key false))
  ([conn client-id message key if-kept?]
   (let [{:keys [key message]} (->enqueue client-id message key)]
     (queue-op! conn client-id [:enqueue key message if-kept?]))))

(defn dequeue!
  "Take `keys` off `client-id`'s queue. A future, as enqueue! returns."
  [conn client-id keys]
  (queue-op! conn client-id [:dequeue (vec keys)]))

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
  "What is waiting for `client-id`, oldest first: a seq of [key message].
   The first `limit` of it, when given."
  ([{:keys [queued-state]} client-id]
   (r/foreign-select [(keypath client-id) ALL] queued-state))
  ([{:keys [queued-state]} client-id limit]
   (r/foreign-select [(keypath client-id) (sorted-map-range-from-start limit) ALL] queued-state)))

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
       ;; As much as the broker's own queue holds: the rest is read once
       ;; the client has worked through that (handlers/catch-up!).
       :queued        (vec (queued conn client-id handlers/pending-limit))}
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

(declare ^:dynamic *connection* awaited)

(def restated-settle-millis
  "How long after a restatement the queues of the connections it took back
   are read a second time. The other brokers learn that a client is here
   again through their proxies, a moment after the cluster has it, and a
   publish they matched in that moment is still queued."
  2000)

(defn- taken-back?
  "Whether the cluster took this broker's word that `client`'s connection is
   still up: its record names this connection on this run, connected."
  [conn {:keys [client-id connect-id]}]
  (let [s (session conn client-id)]
    (and (:connected? s)
         (= connect-id (:connect-id s))
         (= broker-id (:broker-id s))
         (= incarnation (:incarnation s)))))

(defn- drain-restated!
  "Deliver what was queued in the cluster for the connections a restatement
   took back, while they were taken for away: read each one's queue once
   the cluster has the restatement, and again restated-settle-millis later
   for what the other brokers queued before their proxies caught up. A
   connection the cluster did not take back — the client has moved on or
   gone — leaves its queue alone, for whichever resume comes next."
  [conn clients]
  (let [back  (filterv #(taken-back? conn %) clients)
        drain (fn [taken]
                (into {}
                      (for [{:keys [client-id connect-id]} back]
                        [client-id (into (get taken client-id #{})
                                         (handlers/deliver-queued! client-id connect-id
                                                                   (queued conn client-id)
                                                                   (get taken client-id)))])))]
    (when (seq back)
      (let [taken (drain {})]
        (Thread/sleep (long restated-settle-millis))
        (drain taken)))))

(defn restate-clients!
  "Tell the cluster about every connection this broker holds, as
   :still-connected. For a broker that was forgotten while it lived — cut
   off from the cluster for longer than broker-forgotten-after-millis — and
   is listed again: the sweep let its clients go meanwhile, and until they
   are recorded as here again the other brokers queue their messages
   instead of forwarding them. The cluster takes each only for a
   connection it let go that way, so stating one it already has costs an
   append and changes nothing."
  [conn]
  (let [clients (filter :connect-id
                        (remove #(bridge/bridge? (:client-id %)) (handlers/live-sessions)))]
    (log/info "listed in the cluster again - restating" (count clients) "connections")
    (let [appends (mapv #(record! conn (->still-connected %)) clients)]
      (future
        (try
          (run! awaited appends)
          (drain-restated! conn clients)
          (catch Throwable t
            (log/warn t "could not deliver what was queued for the restated connections")))))))

(defonce ^:private listing
  ;; Whether this run has seen itself in the registry, and whether it has
  ;; seen itself dropped from it since: what tells a broker listed again
  ;; from one listed for the first time.
  (atom {:listed? false :dropped? false}))

(defn- note-own-listing!
  "Follow this run's own entry through a registry change, and restate this
   broker's connections when it comes back after being dropped. On seeing
   itself listed again rather than on announcing itself: by then the
   cluster has taken the run off its dead runs, so a :lost still on its way
   finds the run live and lets nobody go."
  [conn new]
  (when (identical? conn @*connection*)
    (let [here?          (= incarnation (get-in new [broker-id :incarnation]))
          [before after] (swap-vals! listing
                                     (fn [{:keys [listed? dropped?]}]
                                       (if here?
                                         {:listed? true :dropped? false}
                                         {:listed? listed? :dropped? (or dropped? listed?)})))]
      (when (and here? (:dropped? before) (not (:dropped? after)))
        (restate-clients! conn)))))

(defn- registry-changed!
  "The registry as Rama now has it. A broker that has gone is also a
   connection the bridge should not keep."
  [conn brokers old new]
  (reset! brokers (or new {}))
  (note-own-listing! conn new)
  (doseq [gone (remove #(contains? new %) (keys old))]
    (log/info "broker" gone "left the cluster")
    (bridge/drop! gone))
  ;; A broker back under the same name is a new run at the same address:
  ;; whatever the bridge knew about the old one — its connection, or that
  ;; it could not be reached a moment ago — is about a broker that is gone,
  ;; and would drop the new one's first messages.
  (doseq [[id {:keys [incarnation]}] new
          :let [before (get-in old [id :incarnation])]
          :when (and before (not= before incarnation))]
    (log/info "broker" id "is back as a new run")
    (bridge/drop! id)))

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
  [{:keys [subscriptions brokers-state retained-state settings-state stats-state
           trie brokers settings stats proxies] :as conn}]
  (when (empty? @proxies)
    (reset! proxies
            (doall
             (concat
              [(r/foreign-proxy (keypath module/registry-key) brokers-state
                                {:callback-fn (guarded "the registry"
                                                       (fn [new _diff old]
                                                         (registry-changed! conn brokers old new)))})
               (r/foreign-proxy (keypath module/settings-key) settings-state
                                {:callback-fn (guarded "the settings"
                                                       (fn [new _diff _old]
                                                         (reset! settings (or new {}))))})
               ;; What the module holds and has processed, for the console.
               ;; Pushed, so the console is told rather than asks: the
               ;; running broker's own connection says so on the event bus.
               (r/foreign-proxy (keypath module/stats-key) stats-state
                                {:callback-fn (guarded "the module's counts"
                                                       (fn [new _diff _old]
                                                         (reset! stats (or new {}))
                                                         (when (identical? conn @*connection*)
                                                           (events/emit! {:event :rama-stats}))))})]
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

(defn- per-client
  "One entry per client among `entries`, at the highest QoS of its matching
   subscriptions (§3.3.5-1), as a live delivery would be. A shared group's
   members are left out: a group has other members to take a message, or
   nobody."
  [entries]
  (->> entries
       (remove :share-group)
       (reduce (fn [m {:keys [client-id qos]}]
                 (update m client-id (fnil max 0) (long (or qos 0))))
               {})
       (mapv (fn [[client-id qos]] {:client-id client-id :qos qos}))))

(declare plan-matches)

(defn plan
  "Where a publish on `topic` goes besides this broker — see
   mqttkat.bridge/planner for the shape — or nil when nowhere.

   Ordinary subscriptions send a copy to every other broker holding one.
   A shared group is served by one broker for this publish, chosen here
   where all its members are visible: if that is this broker the group is
   served as usual, otherwise it is skipped here and the chosen broker is
   told to serve it, and the message is sent there whether or not it holds
   anything else. Every other broker that gets a copy for its ordinary
   subscribers is told nothing about the group, and serves none of it.

   With :away-only?, for a copy another broker sent here, only :queue and
   :leaving: the copy goes no further, and its groups were chosen there."
  ([conn topic] (plan conn topic nil))
  ([{:keys [trie]} topic {:keys [away-only?]}]
   (let [matches (trie/sieve-dollar topic (trie/trie-matching-vals @trie topic))]
     (if away-only?
       ;; Only the clients this broker's copy has here, or last had here:
       ;; the sender's copy may not know yet that they left. Those it has
       ;; away from another broker, the sender had away too, and queued.
       (let [here?   #(= broker-id (:broker-id %))
             queue   (per-client (filter #(and (false? (:connected? %)) (here? %)) matches))
             leaving (per-client (filter #(and (true? (:connected? %)) (here? %)) matches))
             ;; And those it has connected elsewhere already, which the
             ;; sender, a step behind, took for here: see handlers/route.
             moved   (per-client (filter #(and (true? (:connected? %)) (not (here? %))) matches))]
         (when (or (seq queue) (seq leaving) (seq moved))
           {:queue queue :leaving leaving :moved moved}))
       (plan-matches matches)))))

(defn- plan-matches
  "plan's work for a publish from this broker's own clients, on the
   subscriptions `matches`."
  [matches]
  (let [
        ;; A subscription whose client is away is nobody's to deliver: it is
        ;; queued, here, whichever broker parked it — see :queue below. Unless
        ;; the client is live on this very broker: the local trie delivers to
        ;; it, and this copy of the table saying otherwise is a diff that has
        ;; not arrived yet.
        {present false} (group-by #(and (false? (:connected? %))
                                                    (nil? (handlers/live-connection (:client-id %))))
                                              matches)
        {shared true ordinary false} (group-by #(some? (:share-group %)) present)
        remote  (into #{} (comp (map :broker-id) (remove #(= broker-id %))) ordinary)
        ;; Each group's brokers — those with a member present — kept with
        ;; the plan: if the one chosen cannot be reached, the group is served
        ;; by another of them instead (see forward-publish!).
        groups  (into {}
                      (map (fn [[gk members]] [gk (vec (distinct (map :broker-id members)))]))
                      (group-by (juxt :share-group :topic-filter) shared))
        chosen  (for [[gk bs] groups]
                  [gk (choose-broker gk bs)])
        skip    (into #{} (comp (remove #(= broker-id (second %))) (map first)) chosen)
        brokers (reduce (fn [m [gk b]]
                          (if (= broker-id b) m (update m b (fnil conj []) gk)))
                        (zipmap remote (repeat []))
                        chosen)
        ;; One entry per client that is away, at the highest QoS of its
        ;; matching subscriptions (§3.3.5-1), as a live delivery would be.
        ;; A shared group's away members are left out: the group has
        ;; present members to take the message, or it has nobody.
        ;;
        ;; Whether a client is away is not decided here, though: this is
        ;; the cluster's word, and it lags the broker's. A client on its way
        ;; in here may be recorded as away and not yet be live; one on its
        ;; way out may be recorded as connected here and be live no longer.
        ;; A publish in either moment reached nobody. So every client the
        ;; cluster has away, and every one it has here, is a candidate, and
        ;; forward-publish! queues those the broker did not deliver to live
        ;; — which the broker decides once, as it delivers.
        queue   (per-client (filter #(false? (:connected? %)) matches))
        leaving (per-client (filter #(and (true? (:connected? %))
                                          (= broker-id (:broker-id %)))
                                    matches))
        ;; And who each other broker is being sent this for, so that if it
        ;; cannot be reached the message is queued for them instead of
        ;; lost: a broker that dies is recorded as holding its clients
        ;; until it is forgotten, and until then every message for them
        ;; went to a broker that was not there.
        holders (into {}
                      (keep (fn [[b entries]]
                              (when-not (= broker-id b) [b (per-client entries)])))
                      (group-by :broker-id ordinary))]
    (when (or (seq brokers) (seq skip) (seq queue) (seq leaving))
      {:brokers brokers :skip skip :queue queue :leaving leaving
       :holders holders :groups groups})))

(defn forward-publish!
  "What the bridge's forwarder does when this broker is attached: `plan`,
   carried out at the addresses the registry has. A broker the registry
   does not know yet gets nothing — its subscriptions arrived before its
   announcement, which the next publish will find."
  [{:keys [brokers] :as conn} plan topic {:keys [qos payload properties msg-key] :as msg}]
  (let [qos       (long (or qos 0))
        queue-for (fn [clients if-kept?]
                    ;; §4.1 keeps QoS 1 and 2 for a session that is away;
                    ;; at-most-once means a message for a client that is not
                    ;; there has already been delivered as well as it is going
                    ;; to be. The QoS kept is the lesser of the publish and
                    ;; the subscription, as it would be on delivery.
                    (doseq [{:keys [client-id] sub-qos :qos} clients
                            :when (pos? (long sub-qos))]
                      ;; Under the message's key, so that a client queued
                      ;; for by more than one broker has it once. Noted as a
                      ;; hand-off, so the publisher's acknowledgement waits
                      ;; for it: see handlers/publish-keyed.
                      (handlers/hand-off!
                       (enqueue! conn client-id {:topic      topic
                                                 :payload    payload
                                                 :properties properties
                                                 :qos        (min qos (long sub-qos))}
                                 msg-key if-kept?))))]
    (letfn [(reroute! [group-keys tried]
              ;; The shared groups a lost copy was to serve, served by another
              ;; of their brokers: this one if it has a member, which takes
              ;; it at once, or the next in the rotation, which gets a copy
              ;; for those groups alone — and the same again if that one
              ;; cannot be reached either. A group with nobody left anywhere
              ;; has nobody to give it to.
              (doseq [gk group-keys]
                (let [candidates (remove tried (get-in plan [:groups gk]))]
                  (if (empty? candidates)
                    (log/info "shared group" gk "has no member left to serve" topic)
                    ;; Not choose-broker: that moves the group's rotation on,
                    ;; and the next publishes would pay for this one's detour.
                    ;; This broker first, if it has a member — its delivery is
                    ;; certain — then the others in a fixed order.
                    (let [b (if (some #{broker-id} candidates) broker-id (first (sort candidates)))]
                      (cond
                        (= broker-id b)
                        (when-not (handlers/serve-groups! topic msg [gk])
                          (reroute! [gk] (conj tried b)))

                        (get @brokers b)
                        (bridge/send-to! broker-id b (get @brokers b) [gk] topic
                                         (assoc msg :groups-only? true
                                                    :on-lost #(reroute! [gk] (conj tried b))))

                        :else (reroute! [gk] (conj tried b))))))))]
      (doseq [[peer-id group-keys] (:brokers plan)]
        (let [holders (get-in plan [:holders peer-id])
              ;; What was for this broker's clients, queued for them if it does
              ;; not get there — only for those whose sessions outlive their
              ;; connection, which the cluster checks against its own record: a
              ;; clean session on a broker that died died with it — and its
              ;; shared groups served elsewhere.
              on-lost (when (and (pos? qos) (or (seq holders) (seq group-keys)))
                        (fn []
                          (queue-for holders true)
                          (reroute! group-keys #{peer-id})))
              ;; And if it gets there but that broker dies before its
              ;; subscribers have it: queued for them as well, under the
              ;; message's key, which is the one that broker would have
              ;; queued it under had it handed their sessions over.
              on-undelivered (when (and (pos? qos) msg-key (seq holders))
                               #(queue-for holders true))]
          (if-let [peer (get @brokers peer-id)]
            (bridge/send-to! broker-id peer-id peer group-keys topic
                             (assoc msg :on-lost on-lost :on-undelivered on-undelivered))
            (do (log/debug "no address for broker" peer-id "- queuing" topic "for its sessions")
                (when on-lost (on-lost)))))))
    (when (pos? qos)
      ;; Not for whoever this broker delivered to live, as the plan says
      ;; above: that client has it, and queuing too would deliver twice on
      ;; its next resume. A client leaving here is queued only if its
      ;; session is kept: a clean one's messages end with its connection.
      (let [delivered (or (:delivered plan) #{})
            away      (fn [clients] (remove #(contains? delivered (:client-id %)) clients))]
        (queue-for (away (:queue plan)) false)
        (queue-for (away (:leaving plan)) true)))))

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

(defn broker-detail
  "What broker `id` last told the cluster its console shows, or nil: not
   attached, not a broker, or one that has not reported yet. Of `conn`
   when given, else of the running broker's connection."
  ([id] (some-> @*connection* (broker-detail id)))
  ([{:keys [detail-state]} id]
   (r/foreign-select-one (keypath id) detail-state)))

(defn broker-history
  "Broker `id`'s chart points after `after` millis (all of them for nil),
   oldest first."
  ([id after] (if-let [c @*connection*] (broker-history c id after) []))
  ([{:keys [history-state]} id after]
   (let [from (if after (inc (long after)) 0)
         upto Long/MAX_VALUE]
     (vec (vals (r/foreign-select-one [(keypath id) (sorted-map-range from upto)]
                                      history-state))))))

(def gauges
  "The counts in `$$counts` that go up and down with what the module
   holds, as against the event counts, which only go up."
  ["sessions" "connected" "parked" "subscriptions" "retained" "queued"])

(defn module-stats
  "What the module holds and has processed, as its proxy last pushed it:
   {:mode :module :tasks :at :counts}, the counts summed over the tasks —
   the gauges, \"expired\", and \"event/<kind>\" per kind of event — and
   :per-task, each task's counts with the \"at\" it copied them. nil when
   not attached. Of `conn`, a connection watch! has been called on, when
   given."
  ([] (some-> @*connection* module-stats))
  ([{:keys [stats mode module-name]}]
   (let [per-task @stats]
     {:mode     mode
      :module   module-name
      :tasks    (count per-task)
      :at       (when (seq per-task) (reduce max (map #(get % "at" 0) (vals per-task))))
      :counts   (apply merge-with + {} (map #(dissoc % "at") (vals per-task)))
      :per-task per-task})))

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
      ;; Counted for this broker too when it keeps the client: otherwise it
      ;; looks as empty as it last reported until it reports again, and
      ;; every client arriving in between stays here.
      (note-sent! id stats-at)
      (when (not= id broker-id)
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
  (when-let [c (when-not (bridge/bridge? (or (:client-id broker-event) (:client-id connect)))
                 @*connection*)]
    ;; Another broker's bridge is not a session and is not recorded as one:
    ;; see handlers/adopt-session! for what recording it did.
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
                             (record! c (->broker-stats (:stats broker-event)
                                                        (:detail broker-event)
                                                        (:samples broker-event))))
      nil)))

(defn attach!
  "Make `conn` the broker's connection: record its session events, watch
   the cluster's subscriptions and brokers, and forward publishes to the
   other brokers. What connect! does once the connection is open, split
   out so a test can hand the broker a connection it opened itself."
  [conn]
  (reset! *connection* conn)
  (reset! listing {:listed? false :dropped? false})
  (events/listen! ::rama on-broker-event)
  (reset! bridge/planner (fn ([topic] (plan conn topic)) ([topic opts] (plan conn topic opts))))
  (reset! bridge/forwarder (fn [plan topic msg] (forward-publish! conn plan topic msg)))
  (reset! retained/sink (fn [topic message] (record! conn (->retain topic message))))
  (reset! handlers/redirector (fn [client-id] (redirect-target client-id)))
  (reset! handlers/session-source
          {:my-broker-id broker-id
           :resume       (fn [client-id] (resume conn client-id))
           :enqueue!     (fn [client-id msg key] (enqueue! conn client-id msg key))
           :dequeue!     (fn [client-id keys] (dequeue! conn client-id keys))
           :queued       (fn [client-id limit] (queued conn client-id limit))
           :settled!     (fn [peer-id msg-keys]
                           (when-let [peer (get @(:brokers conn) peer-id)]
                             (bridge/settled! broker-id peer-id peer msg-keys)))
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
