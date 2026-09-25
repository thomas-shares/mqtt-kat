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

   `$$broker->clients` — which clients are connected on each run of each
   broker, keyed by [broker-id incarnation]: what a broker coming back needs
   to know about the run it replaced. `$$dead-runs` is the runs that have
   been replaced and not yet cleared; the sweep lets their clients go a few
   hundred at a time, because a run that held thousands cannot be let go in
   one event — Rama gives an event five seconds, and one that overruns is
   retried until it does not, which it never would.

   `$$settings` — what the operator has set for the whole cluster, under
   one key so one proxy watches it: for now the connection redirect policy.

   `$$expiring` — when each parked session is due to be forgotten
   (§3.1.2.11.2), kept on the session's own task and swept by a tick, so a
   session expires on the cluster's clock whether or not the broker that
   parked it is still there.

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
     {:event :enqueue      :client-id :key :message :at}
     {:event :dequeue      :client-id :keys :at}
     {:event :retain       :topic :message :at}
     {:event :unretain     :topic :at}
     {:event :broker-up    :broker-id :incarnation :host :port :at}
     {:event :broker-stats :broker-id :incarnation :stats :at}
     {:event :broker-down  :broker-id :at}
     {:event :setting      :key :value :at}
     {:event :redirected   :client-id :to :at}
     {:event :lost         :client-id :broker-id :incarnation :at}

   `:filter` is the filter as the client sent it — `$share/g/a/#` for a
   shared subscription — and is unique per client. `:entry` is the broker's
   subscription entry: `:filter`, `:topic-filter` (what matches topics, `a/#`
   for that share), `:qos`, and the version 5 options when present. `:lost`
   is never appended by a broker: the topology appends it to itself, one per
   client a broker held when it was replaced (see :broker-up).

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
  "Tests set this true, with-redefs, before launching: the expiry sweep then
   runs off an ordinary depot the test appends `{:now millis}` to, instead
   of a tick depot on a timer. Read at launch."
  false)

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

(def queue-limit
  "How many messages are kept for one session that is away before more are
   refused. The broker's own pending-limit, for the same reason: something
   has to happen to a message for a client that is not coming back soon,
   and holding them for ever is not it."
  4096)

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
                      :expires-at              Long
                      :sent-to                 String
                      :subscriptions           (map-schema String Object)}))

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

(defn- cluster-entry
  "The entry as `$$subscriptions` stores it: the broker's, plus who it is
   for, which broker holds the connection, and whether it is up — what
   another broker needs to deliver to it, forward, or queue."
  [entry client-id broker-id connected?]
  (assoc entry :client-id client-id :broker-id broker-id :connected? connected?))

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

  ;; A stream topology, so a record is in the PStates by the time an append
  ;; with :ack returns. See the namespace doc for what stream's at-least-once
  ;; processing asks of the writes below.
  (let [s (stream-topology topologies "sessions")]
    (declare-pstate s $$sessions {String session-schema})
    (declare-pstate s $$subscriptions {Long (map-schema String (map-schema String Object))})
    (declare-pstate s $$retained {Long (map-schema String Object)})
    (declare-pstate s $$brokers {String (map-schema String Object)})
    (declare-pstate s $$settings {String (map-schema String Object)})
    (declare-pstate s $$broker->clients {Object (set-schema String {:subindex? true})})
    (declare-pstate s $$dead-runs {String (set-schema Object {:subindex? true})})
    ;; Subindexed, so a message is one write, a resume is one seek and a
    ;; walk, and the count is free. Keyed by the name the broker gave the
    ;; message — its time and a random suffix — so they come back in the
    ;; order they were queued, and the same message queued twice is once.
    (declare-pstate s $$queued {String (map-schema String Object {:subindex? true})})
    ;; One entry per task, keyed by the task itself: the index for the
    ;; sessions on this task lives on this task, next to them, and the sweep
    ;; runs everywhere at once without a hop. Inside, a sorted map of due
    ;; time and client id, so what is due is one range.
    (declare-pstate s $$expiring {Long (map-schema String String {:subindex? true})}
                    {:key-partitioner task-is-partition})

    (<<sources s
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
        (anchor> <registry>)
        (local-select> [(keypath *registry) ALL] $$brokers :> [*b *entry])
        (max (get *entry :at 0) (get *entry :stats-at 0) :> *heard)
        (<<if (< *heard (- *now broker-forgotten-after-millis))
          (local-transform> [(keypath *registry *b) NONE>] $$brokers)
          ;; And its run is over, as when a new run replaces it: a broker
          ;; that was killed never comes back to say so, and its clients
          ;; would stay connected to it in the record for ever — their
          ;; sessions never parked, so never expired either.
          (local-transform> [(keypath *registry) NONE-ELEM
                             (termval (run-key *b (get *entry :incarnation)))]
                            $$dead-runs))
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
        (local-transform> [(keypath *client-id) NONE>] $$queued)
        (local-transform> [(keypath *client-id) NONE>] $$sessions)
        (ops/explode (vec (keys *subs)) :> *filter)
        (shard-of *filter :> *shard)
        (|hash *shard)
        (local-transform> [(keypath *shard *filter *client-id) NONE>] $$subscriptions)
        (local-transform> [(keypath *shard *filter) (pred empty?) NONE>] $$subscriptions))

      ;; ── the events ─────────────────────────────────────────────────
      (source> *session-events :> {:keys [*event *client-id *connect-id *at] :as *record})
      (<<switch *event

        ;; ── the brokers ────────────────────────────────────────────────
        (case> :broker-up)
        (get *record :broker-id :> *b)
        (identity registry-key :> *registry)
        (|hash *registry)
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

        ;; Only onto an entry that is there: a report from a broker that has
        ;; withdrawn, or has not announced yet, must not conjure one up
        ;; without an address. Only from the run that is announced: a report
        ;; still in flight from the run before is about a broker that is
        ;; gone.
        (case> :broker-stats)
        (get *record :broker-id :> *b)
        (identity registry-key :> *registry)
        (|hash *registry)
        (local-select> [(keypath *registry *b)] $$brokers :> *entry)
        (<<if (and> *entry (= (get *entry :incarnation) (get *record :incarnation)))
          (local-transform> [(keypath *registry *b)
                             (multi-path [:stats (termval (get *record :stats))]
                                         [:stats-at (termval *at)])]
                            $$brokers))

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
        (local-transform> [(keypath *shard *topic) (termval (get *record :message))] $$retained)

        (case> :unretain)
        (get *record :topic :> *topic)
        (shard-of *topic :> *shard)
        (|hash *shard)
        (local-transform> [(keypath *shard *topic) NONE>] $$retained)

        ;; ── what is queued for a session that is away ──────────────────
        (case> :enqueue)
        (local-select> [(keypath *client-id) (view count)] $$queued :> *n)
        (<<if (< *n queue-limit)
          (local-transform> [(keypath *client-id (get *record :key)) (termval (get *record :message))]
                            $$queued))

        (case> :dequeue)
        (ops/explode (get *record :keys) :> *key)
        (local-transform> [(keypath *client-id *key) NONE>] $$queued)

        ;; ── the sessions ───────────────────────────────────────────────
        (default>)
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
            (run-key *b (get *record :incarnation) :> *run)
            (|hash *run)
            (local-transform> [(keypath *run) NONE-ELEM (termval *client-id)] $$broker->clients)
            (ops/explode (vec (keys *subs)) :> *filter)
            (shard-of *filter :> *shard)
            (|hash *shard)
            (<<if *clean?
              (local-transform> [(keypath *shard *filter *client-id) NONE>] $$subscriptions)
              (local-transform> [(keypath *shard *filter) (pred empty?) NONE>] $$subscriptions)
              (else>)
              ;; Back, and possibly on another broker: the entries say so.
              (local-transform> [(keypath *shard *filter *client-id)
                                 (multi-path [:connected? (termval true)]
                                             [:broker-id (termval *b)])]
                                $$subscriptions)))

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
            (run-key *b (get *current :incarnation) :> *run)
            (|hash *run)
            (local-transform> [(keypath *run) (set-elem *client-id) NONE>] $$broker->clients)
            (ops/explode (vec (keys *subs)) :> *filter)
            (shard-of *filter :> *shard)
            (|hash *shard)
            (<<if *clean?
              (local-transform> [(keypath *shard *filter *client-id) NONE>] $$subscriptions)
              (local-transform> [(keypath *shard *filter) (pred empty?) NONE>] $$subscriptions)
              (else>)
              ;; Still subscribed, not here: a publish that matches is for
              ;; the queue, not the wire.
              (local-transform> [(keypath *shard *filter *client-id :connected?) (termval false)]
                                $$subscriptions)))

          ;; Sent to another broker (§4.13): noted on the record so that
          ;; broker takes the client rather than sending it on again. The
          ;; connect that follows replaces the record, note and all.
          (case> (= *event :redirected))
          (local-transform> [(keypath *client-id) :sent-to (termval (get *record :to))] $$sessions)

          (case> (= *event :subscribe))
          (<<if (= *connect-id *last-id)
            (get *record :filter :> *filter)
            (get *record :entry :> *entry)
            (local-transform> [(keypath *client-id) :subscriptions (keypath *filter) (termval *entry)]
                              $$sessions)
            (shard-of *filter :> *shard)
            (|hash *shard)
            (local-transform> [(keypath *shard *filter *client-id)
                               (termval (cluster-entry *entry *client-id (get *current :broker-id) true))]
                              $$subscriptions))

          (case> (= *event :unsubscribe))
          (<<if (= *connect-id *last-id)
            (get *record :filter :> *filter)
            (local-transform> [(keypath *client-id) :subscriptions (keypath *filter) NONE>]
                              $$sessions)
            (shard-of *filter :> *shard)
            (|hash *shard)
            (local-transform> [(keypath *shard *filter *client-id) NONE>] $$subscriptions)
            ;; A filter nobody holds any more goes too, so the shard does not
            ;; fill with empty maps and a broker's copy does not either.
            (local-transform> [(keypath *shard *filter) (pred empty?) NONE>] $$subscriptions)))))))
