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

   `$$brokers` — where each broker listens, for the others to forward to.

   `$$broker->clients` — which clients are connected on each broker: what a
   broker coming back needs to know about the one it replaced.

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
     {:event :disconnect   :connect-id :client-id :at}
     {:event :subscribe    :connect-id :client-id :filter :entry :at}
     {:event :unsubscribe  :connect-id :client-id :filter :at}
     {:event :enqueue      :client-id :key :message :at}
     {:event :dequeue      :client-id :keys :at}
     {:event :retain       :topic :message :at}
     {:event :unretain     :topic :at}
     {:event :broker-up    :broker-id :incarnation :host :port :at}
     {:event :broker-down  :broker-id :at}
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
                      :subscriptions           (map-schema String Object)}))

(defn partition-key
  "What a session event is partitioned by: the client it is about; for the
   events about a broker, the broker; for a retained message, its topic."
  [{:keys [client-id broker-id topic]}]
  (or client-id broker-id topic))

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

  ;; A stream topology, so a record is in the PStates by the time an append
  ;; with :ack returns. See the namespace doc for what stream's at-least-once
  ;; processing asks of the writes below.
  (let [s (stream-topology topologies "sessions")]
    (declare-pstate s $$sessions {String session-schema})
    (declare-pstate s $$subscriptions {Long (map-schema String (map-schema String Object))})
    (declare-pstate s $$retained {Long (map-schema String Object)})
    (declare-pstate s $$brokers {String (map-schema String Object)})
    (declare-pstate s $$broker->clients {String (set-schema String {:subindex? true})})
    ;; Subindexed, so a message is one write, a resume is one seek and a
    ;; walk, and the count is free. Keyed by the name the broker gave the
    ;; message — its time and a random suffix — so they come back in the
    ;; order they were queued, and the same message queued twice is once.
    (declare-pstate s $$queued {String (map-schema String Object {:subindex? true})})

    (<<sources s
      (source> *session-events :> {:keys [*event *client-id *connect-id *at] :as *record})
      (<<switch *event

        ;; ── the brokers ────────────────────────────────────────────────
        (case> :broker-up)
        (get *record :broker-id :> *b)
        (identity registry-key :> *registry)
        (|hash *registry)
        (local-transform> [(keypath *registry *b)
                           (termval {:host (get *record :host)
                                     :port (get *record :port)
                                     :at   *at})]
                          $$brokers)
        ;; Whatever the broker this one replaces was holding is not held any
        ;; more: every client it had connected is told to the client's own
        ;; partition as a :lost, which ends the connection there unless the
        ;; record already shows the client back on this incarnation — a
        ;; client that reconnected before the announcement got through.
        (|hash *b)
        (local-select> [(keypath *b) ALL] $$broker->clients {:allow-yield? true} :> *lost-id)
        (|hash *lost-id)
        (depot-partition-append! *session-events
                                 {:event       :lost
                                  :client-id   *lost-id
                                  :broker-id   *b
                                  :incarnation (get *record :incarnation)
                                  :at          *at}
                                 :append-ack)

        (case> :broker-down)
        (identity registry-key :> *registry)
        (|hash *registry)
        (local-transform> [(keypath *registry (get *record :broker-id)) NONE>] $$brokers)

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
            (|hash *b)
            (local-transform> [(keypath *b) NONE-ELEM (termval *client-id)] $$broker->clients)
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
            (and> (get *current :connected?)
                  (= (get *record :broker-id) (get *current :broker-id))
                  (not= (get *record :incarnation) (get *current :incarnation))
                  :> *ends?))
          (<<if *ends?
            (get *current :clean-session? :> *clean?)
            (get *current :broker-id :> *b)
            (<<if *clean?
              ;; The session ends with the connection: its subscriptions go,
              ;; the record stays as history.
              (local-transform> [(keypath *client-id)
                                 (multi-path [:connected? (termval false)]
                                             [:disconnected-at (termval *at)]
                                             [:subscriptions (termval {})])]
                                $$sessions)
              (else>)
              ;; Two fields of the record, the rest untouched: the session is
              ;; still what the client asked for, it is just not here right
              ;; now.
              (local-transform> [(keypath *client-id)
                                 (multi-path [:connected? (termval false)]
                                             [:disconnected-at (termval *at)])]
                                $$sessions))
            (|hash *b)
            (local-transform> [(keypath *b) (set-elem *client-id) NONE>] $$broker->clients)
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
