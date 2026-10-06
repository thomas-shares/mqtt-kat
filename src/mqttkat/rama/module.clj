(ns mqttkat.rama.module
  "The broker's Rama module: sessions, subscriptions, retained messages, the
   brokers, and what is queued for a session that is away.

   Everything arrives on one depot, `*session-events`, and one stream
   topology keeps every PState from it:

   `$$sessions` — one record per client id: what it asked for the last time
   it connected, whether that connection is still up and on which broker,
   how many times it has connected, and its subscriptions. This is the
   session MQTT says must outlive a connection (§3.1.2.4): in atoms it
   outlived the connection; here it outlives the broker too.

   `$$subscriptions` — the same subscriptions again, arranged for the
   brokers rather than for the session: shard → filter → client id → entry.
   A broker keeps an in-memory trie of every subscription in the cluster and
   Rama feeds it, through a reactive proxy on each shard, with a diff every
   time an entry changes — from this broker or from any other. Each entry
   says which broker holds the client and whether it is connected, which is
   what a publish anywhere needs: deliver, forward, or queue.

   `$$retained` — the retained messages (§3.3.1.3), shard → topic →
   message, watched the same way.

   `$$queued` — for each persistent session that is away, the QoS 1 and 2
   messages that matched it while it was (§4.1), keyed so they come back in
   order. Queued by whichever broker saw the publish, delivered by whichever
   broker the client comes back to.

   `$$brokers` — where each broker listens, for the others to forward to,
   and how each is doing: every broker reports a few figures every few
   seconds, and since every broker watches the registry, every broker's
   console can show every broker.

   `$$broker-detail` and `$$broker-history` — what each broker's console
   shows about it, for every other broker's console to show too: the
   latest of its readings, its busiest topics, clients and events under
   its id, and a second-by-second history of the figures its charts plot,
   kept for `broker-history-millis`. Brokers can run on machines that
   cannot see each other's memory; this is where the console on any one of
   them reads the rest. Both go when the broker leaves the registry.

   `$$broker->clients` — which clients are connected on each run of each
   broker, keyed by [broker-id incarnation]: what a broker coming back needs
   to know about the run it replaced. `$$dead-runs` is the runs that have
   been replaced and not yet cleared; the sweep lets their clients go a few
   hundred at a time, because a run that held thousands cannot be let go in
   one event — Rama gives an event five seconds, and one that overruns is
   retried until it does not, which it never would.

   `$$nudges` — for each broker, the clients connected there that have had
   something put on their queue, and the key of the last of it: the broker
   watches its own with proxies and reads the client's queue on each change
   (handlers/nudged!). A queue write that lands after the client resumed
   elsewhere is otherwise read only if a catch-up read happens to come
   after it. Keyed by nudge-key, the broker and a shard of the client, so
   one proxy per shard watches a small map.

   `$$settings` — what the operator has set for the whole cluster, under
   one key so one proxy watches it: for now the connection redirect policy.

   `$$expiring` — when each parked session is due to be forgotten
   (§3.1.2.11.2), kept on the session's own task and swept by a tick, so a
   session expires on the cluster's clock whether or not the broker that
   parked it is still there.

   `$$counts` — what the module holds and has been through, per task: how
   many sessions, connected, parked, subscriptions, retained messages and
   queued messages, and how many events of each kind it has processed. Kept
   on the task that makes each change, beside it, so counting costs a read
   and a write and no hop. `$$rama-stats` gathers them under one key, once a
   second, for the console's proxy — see `stats-sample-millis`.

   The sharding is for the proxies: a proxied value is read, sent and
   rewritten whole, so it must stay small, and Rama cannot proxy the root of
   a partition or a subindexed structure — only a plain value under a key.
   Sixty-four keys, each a slice of the table, is the compromise.

   Kept free of every other mqttkat namespace on purpose. When the module is
   deployed to a real cluster the jar is loaded by Rama's workers, which have
   Rama and Clojure and nothing else of this project's dependencies.

   The records on `*session-events`, named as the broker's own maps name
   things so the hooks are a select-keys:

     {:event :connect      :connect-id :client-id :broker-id :incarnation
                           :protocol-version :clean-session? :keep-alive
                           :session-expiry-interval :at}
     {:event :disconnect   :connect-id :client-id :at
                           [:session-expiry-interval]}   ; a DISCONNECT may change it
     {:event :subscribe    :connect-id :client-id :filter :entry :at}
     {:event :unsubscribe  :connect-id :client-id :filter :at}
     {:event :enqueue      :client-id :key :message :at
                           [:if-kept?]}                 ; only for a session that is kept
     {:event :enqueue      :client-id :messages :at     ; [[key message] ...], in order
                           [:if-kept?]}
     {:event :dequeue      :client-id :keys :at}
     {:event :retain       :topic :message :at}
     {:event :unretain     :topic :at}
     {:event :broker-up    :broker-id :incarnation :host :port :at}
     {:event :broker-stats :broker-id :incarnation :stats :at
                           [:detail :samples]}          ; for the consoles
     {:event :broker-down  :broker-id :at}
     {:event :setting      :key :value :at}
     {:event :redirected   :client-id :to :at}
     {:event :lost         :client-id :broker-id :incarnation :at}
     {:event :still-connected
                           :connect-id :client-id :broker-id :incarnation
                           :protocol-version :clean-session? :keep-alive
                           :session-expiry-interval :subscriptions :at}

   `:filter` is the filter as the client sent it — `$share/g/a/#` for a
   shared subscription — and is unique per client. `:entry` is the broker's
   subscription entry: `:filter`, `:topic-filter` (what matches topics, `a/#`
   for that share), `:qos`, and the version 5 options when present. `:lost`
   is never appended by a broker: the topology appends it to itself, one per
   client a broker held when it was replaced (see :broker-up).
   `:still-connected` is a broker saying, after it has announced itself
   again, that a connection the sweep took for lost is in fact still up:
   the CONNECT's terms, as `:connect` has them, and the subscriptions the
   broker holds for it, filter -> entry.

   The connect-id ties every event about a connection to that connection,
   and the incarnation ties every connection to one run of a broker. Both
   are what make each event safe to run twice. A stream topology is
   at-least-once — a record can be run again after its writes committed —
   so every write here gives the same answer the second time: a connect the
   record already carries is not counted again; a disconnect, subscribe or
   unsubscribe naming any connection but the one on record is about a
   connection that is over and changes nothing; a queued message is keyed
   by a name the broker gave it; and every write is a replace or a delete."
  (:require [com.rpl.rama :refer :all]
            [com.rpl.rama.ops :as ops]
            [com.rpl.rama.path :refer :all]))

(def shard-count
  "How many keys `$$subscriptions` and `$$retained` are spread over, and so
   how many proxies a broker opens on each. Fixed for the life of the
   PStates: changing it moves every entry, which is a migration, not an
   edit."
  64)

(defn shard-of
  "The shard a filter or topic lives in. Deterministic across JVMs — `hash`
   on a string is Murmur3 of its contents — because the topology computes it
   on a worker and nothing else may disagree."
  [^String s]
  (mod (hash s) shard-count))

(def REPLACE-TICK-DEPOT
  "Tests set this true, with-redefs, before launching: the expiry sweep and
   the stats roll-up then run off ordinary depots the test appends
   `{:now millis}` to, instead of tick depots on a timer. Read at launch."
  false)

(def stats-sample-millis
  "How often each task copies its `$$counts` to `$$rama-stats`, where the
   console's proxy sees them. A second, the console's own interval: one
   write per task per second, and the proxy is sent the diff."
  1000)

(def stats-key
  "The one key `$$rama-stats` uses: task -> that task's counts, plus \"at\",
   when the task last copied them. One key, so one proxy sees the lot."
  "stats")

(defn add-counts
  "`counts` with `deltas` added, key by key. A delta of zero changes
   nothing and adds no key, so an event that moved no gauge writes back
   what it read."
  [counts deltas]
  (reduce-kv (fn [m k d]
               (if (zero? (long d))
                 m
                 (assoc m k (+ (long (get m k 0)) (long d)))))
             (or counts {})
             deltas))

(defn event-count
  "The delta for having processed one `event`: the per-kind count, under
   \"event/<kind>\"."
  [event]
  {(str "event/" (name event)) 1})

(defn presence-delta
  "How gauge `k` moves when a value goes from `before` to `after`: up one
   for a value that appeared, down one for one that went, else nothing. Read
   before and after the write, never worked out from the event: a record
   run a second time finds the write already made, and counts nothing."
  [k before after]
  {k (- (if (some? after) 1 0) (if (some? before) 1 0))})

(defn with-at
  "A task's counts as `$$rama-stats` holds them: stamped with when they were
   copied, so the console can work out a rate over the time that really
   passed."
  [counts at]
  (assoc (or counts {}) "at" (long at)))

(defmacro ^:private count>
  "Add `deltas` to this task's `$$counts`. A macro, not a ramaop: a ramaop
   cannot see the topology's PStates. <<atomic keeps the three segments one
   block, and the vars are fresh each time so two uses never meet."
  [deltas]
  (let [task (symbol (str "*count-task" (gensym)))
        old  (symbol (str "*count-old" (gensym)))]
    `(<<atomic
       (ops/current-task-id :> ~task)
       (local-select> [(keypath ~task)] ~'$$counts :> ~old)
       (local-transform> [(keypath ~task) (termval (add-counts ~old ~deltas))] ~'$$counts))))

(def expiry-sweep-millis
  "How often parked sessions are checked for ones whose time has come.
   Nothing depends on this being prompt — a client resuming an expired
   session that has not been swept yet simply gets it back, which §3.1.2.11
   allows the server to do."
  5000)

(def broker-forgotten-after-millis
  "How long a broker may go without reporting before the registry drops it:
   ten minutes, against reports every few seconds. A broker that stops
   without its shutdown hook — killed, crashed, unplugged — never withdraws,
   and would otherwise be listed as stale for ever. One that is merely busy
   for a moment is a hundred reports short of this."
  600000)

(defn broker-silent?
  "Whether registry `entry` has gone without a report for longer than
   broker-forgotten-after-millis: by `now`, the sweep's tick, and by
   `latest`, the newest time stamped on any report the topology has taken
   from any broker (nil before the first).

   The second, because the sweep runs off a tick depot of its own, on time
   whatever the session events are behind on. A load run left the topology
   an hour behind them; against the tick alone, every broker's latest
   report looked ten minutes old, and the sweep forgot all three while they
   reported every few seconds, and let their two thousand clients go. By
   the reports it has taken, a broker is silent only while the others are
   heard from: a topology behind on all of them forgets none."
  [entry now latest]
  (let [heard (max (long (get entry :at 0)) (long (get entry :stats-at 0)))
        upto  (min (long now) (long (or latest now)))]
    (< heard (- upto (long broker-forgotten-after-millis)))))

(defn later-of
  "The later of two times, either of which may be nil."
  [a b]
  (max (long (or a 0)) (long (or b 0))))

(def broker-history-millis
  "How much of each broker's chart history `$$broker-history` keeps: the
   half hour the console charts by default. Older points are dropped as
   new ones arrive."
  (* 30 60 1000))

(defn history-cutoff
  "The time before which a broker's history is dropped, given the samples
   a report brings: `broker-history-millis` before the newest of them, or
   nil when it brings none."
  [samples]
  (when (seq samples)
    (- (long (reduce max (map #(long (get % :t 0)) samples))) broker-history-millis)))

(def never-expires
  "0xFFFFFFFF — §3.1.2.11.2's \"do not expire\", not a very long timer."
  4294967295)

(defn expiry-key
  "How `$$expiring` orders parked sessions: by when they are due, then by
   client id, so a range up to now is exactly the ones whose time has come."
  [expires-at client-id]
  (format "%013d|%s" (long expires-at) client-id))

(defn expiry-key->client-id [^String k]
  (subs k 14))

(defn task-is-partition
  "`$$expiring`'s key partitioner: the key is the task, so it is the
   partition. A top-level function, which is what Rama requires of one."
  [_num-partitions task]
  task)

(defn kept?
  "Whether a session outlives its connection, by the broker's own rule
   (mqttkat.handlers/keep-session?): version 5 decides on the Session
   Expiry Interval, 3.1.1 on CleanSession."
  [protocol-version clean-session? session-expiry-interval]
  (if (>= (long (or protocol-version 4)) 5)
    (pos? (long (or session-expiry-interval 0)))
    (false? clean-session?)))

(defn expires-at
  "When a session parked at `at` is forgotten, or nil for one that never
   is: a 3.1.1 session, or a version 5 one that asked not to be."
  [protocol-version session-expiry-interval at]
  (let [interval (long (or session-expiry-interval 0))]
    (when (and (>= (long (or protocol-version 4)) 5)
               (pos? interval)
               (not= interval never-expires))
      (+ (long at) (* 1000 interval)))))

(defn parked?
  "Whether a `$$sessions` record is a session parked while its client is
   away, rather than a connected one or the history of a clean one."
  [session]
  (boolean (and session
                (not (:connected? session))
                (kept? (:protocol-version session)
                       (:clean-session? session)
                       (:session-expiry-interval session)))))

(defn session-deltas
  "How the session gauges move when a client's record goes from `before` to
   `after`: stored, connected, parked."
  [before after]
  (let [n (fn [pred] (- (if (pred after) 1 0) (if (pred before) 1 0)))]
    {"sessions"  (n some?)
     "connected" (n #(boolean (:connected? %)))
     "parked"    (n parked?)}))

(defn enqueue-allowed?
  "Whether an :enqueue is to be kept. Always, unless it says :if-kept? —
   a message a broker could not get to the broker holding the client, sent
   here for the client to have on its return — and then only if the
   client's session outlives its connection: a clean session on a broker
   that died ended with it, and nothing would ever take its queue."
  [record session]
  (or (not (:if-kept? record))
      (boolean (and session
                    (kept? (:protocol-version session)
                           (:clean-session? session)
                           (:session-expiry-interval session))))))

(defn enqueued
  "The [key message] pairs an :enqueue carries: `:messages`, as a broker
   sends them, gathered per client; or the one `:key` and `:message`."
  [record]
  (or (:messages record) [[(:key record) (:message record)]]))

(def lost-per-sweep
  "How many of a dead run's clients one sweep lets go — and one run per
   sweep. Each is a depot append acknowledged in turn, and Rama gives an
   event five seconds: sixty-four keeps well inside that on a slow disk. A
   run that held six thousand takes some minutes to clear, which is fine —
   nothing waits on it but the sessions' own expiry."
  64)

(defn run-key
  "How `$$broker->clients` is keyed: one set per run of a broker."
  [broker-id incarnation]
  [broker-id (or incarnation "")])

(defn nudge-shard-key [broker-id shard]
  (str broker-id "|" shard))

(defn nudge-key
  "How `$$nudges` is keyed: the broker the client is connected on, and the
   client's shard, so that a broker watches shard-count small maps."
  [broker-id client-id]
  (nudge-shard-key broker-id (shard-of client-id)))

(def queue-limit
  "How many messages are kept for one session that is away before more are
   refused, and counted as \"queue-refused\": something has to happen to a
   message for a client that is not coming back soon, and holding them for
   ever is not it. It was the broker's own pending-limit, 4096, and a
   session that drops while it is behind brings more than that: its pending
   queue, full, what is in flight, and what the bridges hold for it, which
   holding publishers keeps to some thousands per link. A chaos run at
   3,000 publishes a second lost some twenty thousand messages for each
   subscriber to every topic that reconnected at its end, each one past the
   limit. The bridge's own backstop, then, mqttkat.bridge/queue-limit."
  65536)

(def taken-off-kept-millis
  "How long a key taken off a session's queue is remembered, so that the
   same message queued again under it after that is not (see :dequeue in
   the topology). Counted from when the take-off was made, against the
   time in the key, so it does not depend on how far behind the topology
   is. A late write to the queue is a retry, minutes after the first at
   most; an hour leaves room."
  3600000)

(defn taken-off-at
  "When a take-off made `at` is remembered as made: 0 if the record did not
   say."
  ^long [at]
  (long (or at 0)))

(defn taken-off-before
  "The first key not to forget yet, for a take-off made `at`: keys are
   named by their time first (mqttkat.handlers/new-message-key), so those
   before it name messages from longer ago than taken-off-kept-millis.
   None, \"\", when `at` is not known."
  [at]
  (if at
    (format "%013d" (max 0 (- (long at) (long taken-off-kept-millis))))
    ""))

(def session-schema
  "What `$$sessions` holds per client id: the last CONNECT, minus the id that
   is the key, whether it is still connected, how many times it has been,
   and its subscriptions by filter. Every key optional, which is what
   fixed-keys-schema gives. The subscriptions are a plain map: a client has
   a handful of filters, and the record is read whole anyway."
  (fixed-keys-schema {:connect-id              String
                      :broker-id               String
                      :incarnation             String
                      :protocol-version        Long
                      :clean-session?          Boolean
                      :keep-alive              Long
                      :session-expiry-interval Long
                      :connected?              Boolean
                      :connected-at            Long
                      :disconnected-at         Long
                      :connections             Long
                      :lost?                   Boolean
                      :expires-at              Long
                      :sent-to                 String
                      :subscriptions           (map-schema String Object)}))

(defn- cluster-entry
  "The entry as `$$subscriptions` stores it: the broker's, plus who it is
   for, which broker holds the connection, and whether it is up — what
   another broker needs to deliver to it, forward, or queue."
  [entry client-id broker-id connected?]
  (assoc entry :client-id client-id :broker-id broker-id :connected? connected?))

(defn still-connected-session
  "The record for a connection a broker says is still up, from what the
   record `current` had and the `:still-connected` `record`: connected
   again, on the run that says so, with the subscriptions that run holds.
   The CONNECT's terms are the record's own where it still has them — it
   was written from that CONNECT — and the broker's where it has none, a
   session that expired in the meantime."
  [current record]
  (merge (select-keys record [:protocol-version :clean-session? :keep-alive
                              :session-expiry-interval])
         (select-keys current [:protocol-version :clean-session? :keep-alive
                               :session-expiry-interval])
         {:connect-id    (:connect-id record)
          :broker-id     (:broker-id record)
          :incarnation   (:incarnation record)
          :connected?    true
          :connected-at  (or (:connected-at current) (:at record))
          :connections   (or (:connections current) 1)
          :subscriptions (or (:subscriptions record) {})}))

(defn still-connected-shards
  "What `$$subscriptions` has to change for a connection that is still up:
   [shard filter entry] for each subscription the broker holds, as the
   shard stores it, and [shard filter nil] for each the record had and the
   broker does not — one a clean session lost with it, say, or one given
   up while the record was not listening."
  [old-subs new-subs client-id broker-id]
  (vec (for [f (distinct (concat (keys new-subs) (keys old-subs)))]
         [(shard-of f) f (some-> (get new-subs f) (cluster-entry client-id broker-id true))])))

(defn partition-key
  "What a session event is partitioned by: the client it is about; for the
   events about a broker, the broker; for a retained message, its topic."
  [{:keys [client-id broker-id topic key]}]
  (or client-id broker-id topic key))

(def settings-key
  "The one key `$$settings` uses: name -> value, for the cluster."
  "settings")

(def registry-key
  "The one key `$$brokers` uses. A proxy watches a key, so the registry is
   one value: broker-id -> {:host :port :at}, small, rewritten whole on the
   rare occasion a broker comes or goes."
  "brokers")

(defmodule MqttKatModule [setup topologies]
  ;; Partitioned by client id: a record lands on the task that owns its
  ;; client's session, so the topology reads and writes it locally, and one
  ;; client's events arrive in the order they were appended — there is only
  ;; one partition they can go to. That order is what lets a disconnect be
  ;; trusted to come after its connect, and an unsubscribe after its
  ;; subscribe. One depot for every kind of event for that reason: two
  ;; depots would give no order between them.
  (declare-depot setup *session-events (hash-by partition-key))
  (if REPLACE-TICK-DEPOT
    (declare-depot setup *expiry-tick :random {:global? true})
    (declare-tick-depot setup *expiry-tick expiry-sweep-millis))
  (if REPLACE-TICK-DEPOT
    (declare-depot setup *stats-tick :random {:global? true})
    (declare-tick-depot setup *stats-tick stats-sample-millis))

  ;; A stream topology, so a record is in the PStates by the time an append
  ;; with :ack returns. See the namespace doc for what stream's at-least-once
  ;; processing asks of the writes below.
  (let [s (stream-topology topologies "sessions")]
    (declare-pstate s $$sessions {String session-schema})
    (declare-pstate s $$subscriptions {Long (map-schema String (map-schema String Object))})
    (declare-pstate s $$retained {Long (map-schema String Object)})
    (declare-pstate s $$brokers {String (map-schema String Object)})
    ;; registry-key -> the newest time stamped on a broker's report taken
    ;; so far: the registry's own clock, for broker-silent?.
    (declare-pstate s $$registry-clock {String Long})
    (declare-pstate s $$settings {String (map-schema String Object)})
    ;; broker-id -> the broker's latest detail, a plain value, read whole.
    (declare-pstate s $$broker-detail {String Object})
    ;; broker-id -> millis -> one chart point. Subindexed: a point is one
    ;; write, a range is one seek, and trimming is a range too.
    (declare-pstate s $$broker-history {String (map-schema Long Object {:subindex? true})})
    (declare-pstate s $$broker->clients {Object (set-schema String {:subindex? true})})
    (declare-pstate s $$dead-runs {String (set-schema Object {:subindex? true})})
    ;; Subindexed, so a message is one write, a resume is one seek and a
    ;; walk, and the count is free. Keyed by the name the broker gave the
    ;; message — its time and a random suffix — so they come back in the
    ;; order they were queued, and the same message queued twice is once.
    (declare-pstate s $$queued {String (map-schema String Object {:subindex? true})})
    ;; client-id -> key -> when it was taken off its queue: for
    ;; taken-off-kept-millis, a key queued again stays off.
    (declare-pstate s $$taken-off {String (map-schema String Long {:subindex? true})})
    ;; nudge-key -> client-id -> the last key queued for it while it was
    ;; connected there. Not subindexed: a proxy watches each one whole.
    (declare-pstate s $$nudges {String (map-schema String String)})
    ;; One entry per task, keyed by the task itself: the index for the
    ;; sessions on this task lives on this task, next to them, and the sweep
    ;; runs everywhere at once without a hop. Inside, a sorted map of due
    ;; time and client id, so what is due is one range.
    (declare-pstate s $$expiring {Long (map-schema String String {:subindex? true})}
                    {:key-partitioner task-is-partition})
    ;; Keyed by task, like $$expiring and for the same reason: every count is
    ;; kept where the change it counts is made.
    (declare-pstate s $$counts {Long (map-schema String Long)}
                    {:key-partitioner task-is-partition})
    (declare-pstate s $$rama-stats {String (map-schema Object Object)})

    (<<sources s
      ;; ── the counts, gathered for the console ───────────────────────
      ;; Every task copies its own counts under the one key a proxy watches.
      ;; Whole, not added up: the sum is the reader's, and a task's copy is
      ;; right however many seconds it missed.
      (source> *stats-tick :> *stats-tick)
      (<<if (map? *stats-tick)
        (get *stats-tick :now :> *stats-now)
        (else>)
        (System/currentTimeMillis :> *stats-now))
      (|all)
      (ops/current-task-id :> *stats-task)
      (local-select> [(keypath *stats-task)] $$counts :> *mine)
      (identity stats-key :> *stats-key)
      (|hash *stats-key)
      (local-transform> [(keypath *stats-key *stats-task) (termval (with-at *mine *stats-now))]
                        $$rama-stats)

      ;; ── the sweep ──────────────────────────────────────────────────
      (source> *expiry-tick :> *tick)
      (<<if (map? *tick)
        (get *tick :now :> *now)
        (else>)
        (System/currentTimeMillis :> *now))
      ;; Two jobs off one tick. The first, on the registry's own partition:
      ;; forget a broker that has said nothing for long enough. A branch, so
      ;; that the second job below — which is per task, and per due session
      ;; — attaches to the tick rather than to whatever this emits.
      (anchor> <tick>)
      (<<branch <tick>
        (identity registry-key :> *registry)
        (|hash *registry)
        (local-select> [(keypath *registry)] $$registry-clock :> *latest)
        (anchor> <registry>)
        (local-select> [(keypath *registry) ALL] $$brokers :> [*b *entry])
        (<<if (broker-silent? *entry *now *latest)
          (local-transform> [(keypath *registry *b) NONE>] $$brokers)
          ;; And its run is over, as when a new run replaces it: a broker
          ;; that was killed never comes back to say so, and its clients
          ;; would stay connected to it in the record for ever — their
          ;; sessions never parked, so never expired either.
          (local-transform> [(keypath *registry) NONE-ELEM
                             (termval (run-key *b (get *entry :incarnation)))]
                            $$dead-runs)
          ;; And what its console showed, which nobody will show again.
          (|hash *b)
          (local-transform> [(keypath *b) NONE>] $$broker-detail)
          (local-transform> [(keypath *b) NONE>] $$broker-history))
        ;; And the runs that were replaced: a slice of each run's clients is
        ;; told :lost, on the client's own partition, which ends the
        ;; connection there unless the record already shows the client back
        ;; on a newer run. A run with nobody left is forgotten.
        (hook> <registry>)
        (local-select> [(keypath *registry) (sorted-set-range-from "" 1) ALL] $$dead-runs :> *run)
        (|hash *run)
        (local-select> [(keypath *run) (view count)] $$broker->clients :> *left)
        (<<if (zero? *left)
          (local-transform> [(keypath *run) NONE>] $$broker->clients)
          (|hash *registry)
          (local-transform> [(keypath *registry) (set-elem *run) NONE>] $$dead-runs)
          (else>)
          (local-select> [(keypath *run) (sorted-set-range-from "" lost-per-sweep) ALL]
                         $$broker->clients :> *lost-id)
          (local-transform> [(keypath *run) (set-elem *lost-id) NONE>] $$broker->clients)
          (|hash *lost-id)
          (depot-partition-append! *session-events
                                   {:event       :lost
                                    :client-id   *lost-id
                                    :broker-id   (first *run)
                                    :incarnation (second *run)
                                    :at          *now}
                                   :append-ack)))
      (hook> <tick>)
      (|all)
      (ops/current-task-id :> *task)
      (expiry-key *now "" :> *upto)
      (local-select> [(keypath *task) (sorted-map-range-to *upto 256) ALL]
                     $$expiring {:allow-yield? true} :> [*ikey *due-connect-id])
      (expiry-key->client-id *ikey :> *client-id)
      (local-transform> [(keypath *task *ikey) NONE>] $$expiring)
      (local-select> [(keypath *client-id)] $$sessions :> *current)
      ;; Still away, and still the connection that parked it: a client that
      ;; came back, or came and went again, has a later entry of its own.
      (<<if (and> (not (get *current :connected?))
                  (= *due-connect-id (get *current :connect-id)))
        (get *current :subscriptions {} :> *subs)
        (local-select> [(keypath *client-id) (view count)] $$queued :> *dropped)
        (local-transform> [(keypath *client-id) NONE>] $$queued)
        (local-transform> [(keypath *client-id) NONE>] $$taken-off)
        (local-transform> [(keypath *client-id) NONE>] $$sessions)
        (count> (merge (session-deltas *current nil)
                       {"queued" (- *dropped) "expired" 1}))
        (ops/explode (vec (keys *subs)) :> *filter)
        (shard-of *filter :> *shard)
        (|hash *shard)
        (local-select> [(keypath *shard *filter *client-id)] $$subscriptions :> *had)
        (local-transform> [(keypath *shard *filter *client-id) NONE>] $$subscriptions)
        (count> (presence-delta "subscriptions" *had nil))
        (local-transform> [(keypath *shard *filter) (pred empty?) NONE>] $$subscriptions))

      ;; ── the events ─────────────────────────────────────────────────
      (source> *session-events :> {:keys [*event *client-id *connect-id *at] :as *record})
      ;; Counted where it arrives. At least once, like everything here: a
      ;; record run twice is counted twice, which a throughput can bear.
      (count> (event-count *event))
      (<<switch *event

        ;; ── the brokers ────────────────────────────────────────────────
        (case> :broker-up)
        (get *record :broker-id :> *b)
        (identity registry-key :> *registry)
        (|hash *registry)
        (local-select> [(keypath *registry)] $$registry-clock :> *clock)
        (local-transform> [(keypath *registry) (termval (later-of *clock *at))]
                          $$registry-clock)
        (local-select> [(keypath *registry *b)] $$brokers :> *entry)
        (local-transform> [(keypath *registry *b)
                           (termval {:host        (get *record :host)
                                     :port        (get *record :port)
                                     :incarnation (get *record :incarnation)
                                     :at          *at})]
                          $$brokers)
        ;; Whatever the run this one replaces was holding is not held any
        ;; more. Noted here, not walked here: the sweep lets its clients go a
        ;; few hundred at a time, each told to its own partition as a :lost.
        (<<if (and> *entry (not= (get *entry :incarnation) (get *record :incarnation)))
          (local-transform> [(keypath *registry) NONE-ELEM
                             (termval (run-key *b (get *entry :incarnation)))]
                            $$dead-runs))
        ;; And the run announcing itself is not over, whatever the sweep made
        ;; of it: a broker cut off from the cluster for long enough to be
        ;; forgotten says so once it can. The sweep stops letting its clients
        ;; go; the ones already let go the broker states again, as
        ;; :still-connected, once it sees itself listed.
        (local-transform> [(keypath *registry) (set-elem (run-key *b (get *record :incarnation))) NONE>]
                          $$dead-runs)

        ;; Only onto an entry that is there: a report from a broker that has
        ;; withdrawn, or has not announced yet, must not conjure one up
        ;; without an address. Only from the run that is announced: a report
        ;; still in flight from the run before is about a broker that is
        ;; gone.
        (case> :broker-stats)
        (get *record :broker-id :> *b)
        (identity registry-key :> *registry)
        (|hash *registry)
        (local-select> [(keypath *registry)] $$registry-clock :> *clock)
        (local-transform> [(keypath *registry) (termval (later-of *clock *at))]
                          $$registry-clock)
        (local-select> [(keypath *registry *b)] $$brokers :> *entry)
        (<<if (and> *entry (= (get *entry :incarnation) (get *record :incarnation)))
          (local-transform> [(keypath *registry *b)
                             (multi-path [:stats (termval (get *record :stats))]
                                         [:stats-at (termval *at)])]
                            $$brokers)
          ;; What its console shows, for the others' — on the broker's own
          ;; partition, apart from the registry every broker proxies, which
          ;; stays small. Each point is keyed by its time, so a report run
          ;; twice writes the same points twice.
          (get *record :detail :> *detail)
          (get *record :samples [] :> *samples)
          (history-cutoff *samples :> *cutoff)
          (|hash *b)
          (<<if *detail
            (local-transform> [(keypath *b) (termval (assoc *detail :at *at))] $$broker-detail))
          (<<if *cutoff
            (local-transform> [(keypath *b) (sorted-map-range-to *cutoff {:max-amt 256}) MAP-VALS NONE>]
                              $$broker-history))
          (ops/explode *samples :> *point)
          (local-transform> [(keypath *b (long (get *point :t))) (termval *point)] $$broker-history))

        (case> :broker-down)
        (get *record :broker-id :> *b)
        (identity registry-key :> *registry)
        (|hash *registry)
        (local-select> [(keypath *registry *b)] $$brokers :> *entry)
        (local-transform> [(keypath *registry *b) NONE>] $$brokers)
        ;; Whoever it still held is let go by the sweep, as for a broker
        ;; that was forgotten: its shutdown may not have got round to them.
        (<<if *entry
          (local-transform> [(keypath *registry) NONE-ELEM
                             (termval (run-key *b (get *entry :incarnation)))]
                            $$dead-runs))
        (|hash *b)
        (local-transform> [(keypath *b) NONE>] $$broker-detail)
        (local-transform> [(keypath *b) NONE>] $$broker-history)

        ;; ── the settings ───────────────────────────────────────────────
        (case> :setting)
        (identity settings-key :> *settings)
        (|hash *settings)
        (local-transform> [(keypath *settings (get *record :key)) (termval (get *record :value))] $$settings)

        ;; ── the retained messages ──────────────────────────────────────
        ;; A replace and a delete; nothing to check first. Two brokers
        ;; retaining on one topic at once are ordered here, and every
        ;; broker's copy ends on the same one.
        (case> :retain)
        (get *record :topic :> *topic)
        (shard-of *topic :> *shard)
        (|hash *shard)
        (local-select> [(keypath *shard *topic)] $$retained :> *had)
        (local-transform> [(keypath *shard *topic) (termval (get *record :message))] $$retained)
        (count> (presence-delta "retained" *had true))

        (case> :unretain)
        (get *record :topic :> *topic)
        (shard-of *topic :> *shard)
        (|hash *shard)
        (local-select> [(keypath *shard *topic)] $$retained :> *had)
        (local-transform> [(keypath *shard *topic) NONE>] $$retained)
        (count> (presence-delta "retained" *had nil))

        ;; ── what is queued for a session that is away ──────────────────
        (case> :enqueue)
        (local-select> [(keypath *client-id)] $$sessions :> *session)
        (<<if (enqueue-allowed? *record *session)
          (<<atomic
            ;; One at a time, in order, each counted against the limit as
            ;; the one before it left the queue.
            (ops/explode (enqueued *record) :> [*key *message])
            (local-select> [(keypath *client-id) (view count)] $$queued :> *n)
            (local-select> [(keypath *client-id *key)] $$queued :> *had)
            (local-select> [(keypath *client-id *key)] $$taken-off :> *taken)
            (<<cond
              ;; The client has it: a write that reached the depot more than
              ;; once — tried again after a timeout — or late, or another
              ;; broker's, landing after the take-off. Put back, a chaos run
              ;; sent QoS 2 messages again to a client that had completed
              ;; them, some forty times each.
              (case> (some? *taken))
              (count> {"queue-taken-off" 1})

              (case> (or> *had (< *n queue-limit)))
              (local-transform> [(keypath *client-id *key) (termval *message)] $$queued)
              (count> (presence-delta "queued" *had true))

              (default>)
              (count> {"queue-refused" 1})))
          ;; Then, once, the broker it is connected on is told: it has
          ;; read its queue already, on the CONNECT, and this came after.
          (<<if (and> (get *session :connected?) (get *session :broker-id))
            (nudge-key (get *session :broker-id) *client-id :> *nudge)
            (first (last (enqueued *record)) :> *last-key)
            (|hash *nudge)
            (local-transform> [(keypath *nudge *client-id) (termval *last-key)] $$nudges)))

        (case> :dequeue)
        (get *record :at :> *at)
        (taken-off-at *at :> *when)
        ;; First forget what was taken off long enough ago, a few at a time:
        ;; once a record, not once a key. A range read for each of a record's
        ;; 256 keys tripled what a take-off cost, and at 3,000 messages a
        ;; second Rama's batches of them no longer finished in their five
        ;; seconds, and were tried again for ever.
        (taken-off-before *at :> *before)
        (<<atomic
          (local-select> [(keypath *client-id) (sorted-map-range-to *before 16) MAP-KEYS]
                         $$taken-off :> *old)
          (local-transform> [(keypath *client-id *old) NONE>] $$taken-off))
        (ops/explode (get *record :keys) :> *key)
        (local-select> [(keypath *client-id *key)] $$queued :> *had)
        (local-transform> [(keypath *client-id *key) NONE>] $$queued)
        (local-transform> [(keypath *client-id *key) (termval *when)] $$taken-off)
        (count> (presence-delta "queued" *had nil))

        ;; ── the sessions ───────────────────────────────────────────────
        (default>)
        ;; A :lost was decided on a sweep; the run it names may have announced
        ;; itself since, and a run that is back holds its clients. Asked of
        ;; the registry's partition before anything is read here.
        (<<if (= *event :lost)
          (identity registry-key :> *registry)
          (|hash *registry)
          (local-select> [(keypath *registry)
                          (view contains? (run-key (get *record :broker-id) (get *record :incarnation)))]
                         $$dead-runs :> *applies?)
          (|hash *client-id)
          (else>)
          (identity true :> *applies?))
        (filter> *applies?)
        ;; One read for every event: the count and the subscriptions have to
        ;; come from somewhere, and the same read says whether this event is
        ;; about the connection on record. Read and write happen on one task
        ;; in one event, which is single-threaded, so nothing slips in
        ;; between.
        (local-select> [(keypath *client-id)] $$sessions :> *current)
        (get *current :connect-id :> *last-id)
        (get *current :subscriptions {} :> *subs)
        (<<cond

          (case> (= *event :connect))
          (<<if (not= *connect-id *last-id)
            (get *record :clean-session? :> *clean?)
            (get *record :broker-id :> *b)
            (get *current :connections 0 :> *n)
            ;; §3.1.2.4: a persistent session's subscriptions come with it; a
            ;; clean one's are discarded, here and below in the shards.
            (<<if *clean?
              (identity {} :> *kept)
              (else>)
              (identity *subs :> *kept))
            (local-transform> [(keypath *client-id)
                               (termval {:connect-id              *connect-id
                                         :broker-id               *b
                                         :incarnation             (get *record :incarnation)
                                         :protocol-version        (get *record :protocol-version)
                                         :clean-session?          *clean?
                                         :keep-alive              (get *record :keep-alive)
                                         :session-expiry-interval (get *record :session-expiry-interval)
                                         :connected?              true
                                         :connected-at            *at
                                         :connections             (inc *n)
                                         :subscriptions           *kept})]
                              $$sessions)
            ;; Back before its time: no longer due.
            (<<if (get *current :expires-at)
              (ops/current-task-id :> *task)
              (local-transform> [(keypath *task (expiry-key (get *current :expires-at) *client-id)) NONE>]
                                $$expiring))
            (local-select> [(keypath *client-id)] $$sessions :> *after)
            (count> (session-deltas *current *after))
            ;; What was queued before this record and after the broker read
            ;; the queue on the CONNECT: it read before saying so.
            (local-select> [(keypath *client-id) (view count)] $$queued :> *waiting)
            (<<if (pos? *waiting)
              (nudge-key *b *client-id :> *nudge)
              (|hash *nudge)
              (local-transform> [(keypath *nudge *client-id) (termval (str "connect " *connect-id))]
                                $$nudges))
            (run-key *b (get *record :incarnation) :> *run)
            (|hash *run)
            (local-transform> [(keypath *run) NONE-ELEM (termval *client-id)] $$broker->clients)
            (ops/explode (vec (keys *subs)) :> *filter)
            (shard-of *filter :> *shard)
            (|hash *shard)
            (local-select> [(keypath *shard *filter *client-id)] $$subscriptions :> *had)
            (<<if *clean?
              (local-transform> [(keypath *shard *filter *client-id) NONE>] $$subscriptions)
              (local-transform> [(keypath *shard *filter) (pred empty?) NONE>] $$subscriptions)
              (else>)
              ;; Back, and possibly on another broker: the entries say so.
              (local-transform> [(keypath *shard *filter *client-id)
                                 (multi-path [:connected? (termval true)]
                                             [:broker-id (termval *b)])]
                                $$subscriptions))
            (local-select> [(keypath *shard *filter *client-id)] $$subscriptions :> *has)
            (count> (presence-delta "subscriptions" *had *has)))

          ;; A connection ends: the client said so, the socket went, or the
          ;; broker it was on is gone. The last is trusted only if the record
          ;; still shows the client on that broker's previous incarnation —
          ;; anything else means the client has moved on already.
          (case> (or> (= *event :disconnect) (= *event :lost)))
          (<<if (= *event :disconnect)
            (= *connect-id *last-id :> *ends?)
            (else>)
            ;; :lost names the run that is gone: it ends the connection only
            ;; if the record still shows the client on exactly that run.
            (and> (get *current :connected?)
                  (= (get *record :broker-id) (get *current :broker-id))
                  (= (get *record :incarnation) (get *current :incarnation))
                  :> *ends?))
          (<<if *ends?
            (get *current :broker-id :> *b)
            ;; §3.14.2.2.2: a DISCONNECT may change the interval on the way
            ;; out; otherwise the session keeps the one it connected with.
            (get *record :session-expiry-interval (get *current :session-expiry-interval) :> *interval)
            (kept? (get *current :protocol-version) (get *current :clean-session?) *interval :> *kept?)
            (not *kept? :> *clean?)
            (<<if *clean?
              ;; The session ends with the connection: its subscriptions go,
              ;; the record stays as history.
              (local-transform> [(keypath *client-id)
                                 (multi-path [:connected? (termval false)]
                                             [:disconnected-at (termval *at)]
                                             [:session-expiry-interval (termval *interval)]
                                             [:subscriptions (termval {})])]
                                $$sessions)
              (else>)
              ;; Parked: still what the client asked for, just not here
              ;; right now — and, for a version 5 session with an interval,
              ;; due to be forgotten when it passes, which the sweep sees to.
              (expires-at (get *current :protocol-version) *interval *at :> *expires)
              (local-transform> [(keypath *client-id)
                                 (multi-path [:connected? (termval false)]
                                             [:disconnected-at (termval *at)]
                                             [:session-expiry-interval (termval *interval)]
                                             [:expires-at (termval *expires)])]
                                $$sessions)
              (<<if *expires
                (ops/current-task-id :> *task)
                (local-transform> [(keypath *task (expiry-key *expires *client-id)) (termval *connect-id)]
                                  $$expiring)))
            ;; Lost is noted, and only lost: a broker that turns out to be
            ;; alive may take back a connection the sweep let go, never one
            ;; that ended — a DISCONNECT, a dropped socket — however late
            ;; the broker's word arrives.
            (<<if (= *event :lost)
              (local-transform> [(keypath *client-id) :lost? (termval true)] $$sessions)
              (else>)
              (local-transform> [(keypath *client-id) :lost? NONE>] $$sessions))
            (local-select> [(keypath *client-id)] $$sessions :> *after)
            (count> (session-deltas *current *after))
            (nudge-key *b *client-id :> *nudge)
            (|hash *nudge)
            (local-transform> [(keypath *nudge *client-id) NONE>] $$nudges)
            (run-key *b (get *current :incarnation) :> *run)
            (|hash *run)
            (local-transform> [(keypath *run) (set-elem *client-id) NONE>] $$broker->clients)
            (ops/explode (vec (keys *subs)) :> *filter)
            (shard-of *filter :> *shard)
            (|hash *shard)
            (local-select> [(keypath *shard *filter *client-id)] $$subscriptions :> *had)
            (<<if *clean?
              (local-transform> [(keypath *shard *filter *client-id) NONE>] $$subscriptions)
              (local-transform> [(keypath *shard *filter) (pred empty?) NONE>] $$subscriptions)
              (else>)
              ;; Still subscribed, not here: a publish that matches is for
              ;; the queue, not the wire.
              (local-transform> [(keypath *shard *filter *client-id :connected?) (termval false)]
                                $$subscriptions))
            (local-select> [(keypath *shard *filter *client-id)] $$subscriptions :> *has)
            (count> (presence-delta "subscriptions" *had *has)))

          ;; A broker that was forgotten, alive after all, stating a
          ;; connection it still holds. Taken only for the connection the
          ;; sweep let go — the one on record, marked lost — or for a client
          ;; with no record left at all, its session having expired while it
          ;; was taken for away. Anything else is a client that has moved on
          ;; or gone, and the broker's word is late.
          (case> (= *event :still-connected))
          (<<if (or> (nil? *current)
                     (and> (= *connect-id *last-id) (get *current :lost?)))
            (get *record :broker-id :> *b)
            (get *record :subscriptions {} :> *now-subs)
            (local-transform> [(keypath *client-id) (termval (still-connected-session *current *record))]
                              $$sessions)
            (<<if (get *current :expires-at)
              (ops/current-task-id :> *task)
              (local-transform> [(keypath *task (expiry-key (get *current :expires-at) *client-id)) NONE>]
                                $$expiring))
            (local-select> [(keypath *client-id)] $$sessions :> *after)
            (count> (session-deltas *current *after))
            (run-key *b (get *record :incarnation) :> *run)
            (|hash *run)
            (local-transform> [(keypath *run) NONE-ELEM (termval *client-id)] $$broker->clients)
            (ops/explode (still-connected-shards *subs *now-subs *client-id *b) :> [*shard *filter *entry])
            (|hash *shard)
            (local-select> [(keypath *shard *filter *client-id)] $$subscriptions :> *had)
            (<<if *entry
              (local-transform> [(keypath *shard *filter *client-id) (termval *entry)] $$subscriptions)
              (else>)
              (local-transform> [(keypath *shard *filter *client-id) NONE>] $$subscriptions)
              (local-transform> [(keypath *shard *filter) (pred empty?) NONE>] $$subscriptions))
            (count> (presence-delta "subscriptions" *had *entry)))

          ;; Sent to another broker (§4.13): noted on the record so that
          ;; broker takes the client rather than sending it on again. The
          ;; connect that follows replaces the record, note and all.
          (case> (= *event :redirected))
          (local-transform> [(keypath *client-id) :sent-to (termval (get *record :to))] $$sessions)
          ;; Onto a client with no record, this makes one.
          (local-select> [(keypath *client-id)] $$sessions :> *after)
          (count> (session-deltas *current *after))

          (case> (= *event :subscribe))
          (<<if (= *connect-id *last-id)
            (get *record :filter :> *filter)
            (get *record :entry :> *entry)
            (local-transform> [(keypath *client-id) :subscriptions (keypath *filter) (termval *entry)]
                              $$sessions)
            (shard-of *filter :> *shard)
            (|hash *shard)
            (local-select> [(keypath *shard *filter *client-id)] $$subscriptions :> *had)
            (local-transform> [(keypath *shard *filter *client-id)
                               (termval (cluster-entry *entry *client-id (get *current :broker-id) true))]
                              $$subscriptions)
            (count> (presence-delta "subscriptions" *had true)))

          (case> (= *event :unsubscribe))
          (<<if (= *connect-id *last-id)
            (get *record :filter :> *filter)
            (local-transform> [(keypath *client-id) :subscriptions (keypath *filter) NONE>]
                              $$sessions)
            (shard-of *filter :> *shard)
            (|hash *shard)
            (local-select> [(keypath *shard *filter *client-id)] $$subscriptions :> *had)
            (local-transform> [(keypath *shard *filter *client-id) NONE>] $$subscriptions)
            (count> (presence-delta "subscriptions" *had nil))
            ;; A filter nobody holds any more goes too, so the shard does not
            ;; fill with empty maps and a broker's copy does not either.
            (local-transform> [(keypath *shard *filter) (pred empty?) NONE>] $$subscriptions)))))))
