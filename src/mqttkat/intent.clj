(ns mqttkat.intent
  "Whom a copy of a publish was meant for, as the broker that sent it saw it.

   A publish entering the cluster is planned once, on its publisher's
   broker, from that broker's copy of the cluster's subscriptions — and
   that copy lags. A client that has just moved from B to C is still on B
   there, so the copy for it goes to B, which no longer has it, and a copy
   that goes to C for C's other subscribers reaches it as well. B cannot
   tell \"was here, and is gone\" from \"was never meant for here\", and C
   cannot tell \"meant for B, who has it\" from \"meant for me\". So each
   guessed: B queued for clients that had left lately, C delivered to
   whoever it had. A load run with no faults at all lost messages and sent
   others twice on exactly that.

   Only the sender knows whom it meant, so the sender says: every copy
   names the version of the sender's view it was planned at, and down the
   same link, ahead of any copy planned at it, goes what changed in that
   view, client by client. The receiver keeps a short history of it, and
   asks of each subscriber what the sender's view had for it at that
   version:

     serve? — deliver to it here: nothing the sender knew of matches, or
              everything that matches was here.
     owed   — the clients whose matching subscriptions the sender had
              here. Each is this broker's to deliver to, or to queue for.

   Exactly one broker owes a client a given copy, because every broker
   asks the same question of the same version.

   Pure: the views are values, and what keeps them and sends them is
   mqttkat.rama.cluster on the sending side and mqttkat.handlers on the
   receiving one."
  (:require [clojure.string :as str]
            [mqttkat.trie :as trie]))

(def away
  "Where a subscription is in a view when its client is not connected."
  "-")

(def history
  "How many states of one client a receiver keeps. A copy planned at a
   version older than the oldest of them is not judged: see state-at."
  32)

;; ── the sender's view ────────────────────────────────────────────────────

(defn location
  "Where a cluster entry says its client is: the broker holding the
   connection, or `away`."
  [{:keys [connected? broker-id]}]
  (if (true? connected?) broker-id away))

(defn index-change
  "`index` — client-id -> topic-filter -> [qos location] — with the cluster
   entry `old` replaced by `new`, either of them nil. A shared
   subscription's entry is left out: the sender chooses a broker for a
   group by name, and says so on the copy."
  [index old new]
  (let [old (when-not (:share-group old) old)
        new (when-not (:share-group new) new)]
    (cond
      new (assoc-in index [(:client-id new) (:topic-filter new)]
                    [(long (or (:qos new) 0)) (location new)])
      old (let [c (:client-id old)
                m (dissoc (get index c) (:topic-filter old))]
            (if (empty? m) (dissoc index c) (assoc index c m)))
      :else index)))

(defn changes
  "client-id -> entries, for every client whose entries differ between two
   indexes; nil for one that has none left."
  [before after]
  (into {}
        (keep (fn [c]
                (let [a (get after c)]
                  (when-not (= (get before c) a)
                    [c a]))))
        (into (set (keys before)) (keys after))))

;; ── matching ─────────────────────────────────────────────────────────────

(defn filter-matches?
  "Whether `topic-filter` matches `topic` (§4.7), including the rule that a
   filter starting with a wildcard does not match a topic starting with $."
  [^String topic-filter ^String topic]
  (and (not (and (.startsWith topic "$") (trie/wildcard-rooted? topic-filter)))
       (loop [fs (str/split topic-filter #"/" -1)
              ts (str/split topic #"/" -1)]
         (cond
           (empty? fs)            (empty? ts)
           (= "#" (first fs))     true
           (empty? ts)            false
           (or (= "+" (first fs))
               (= (first fs) (first ts))) (recur (rest fs) (rest ts))
           :else                  false))))

;; ── the receiver's copy ──────────────────────────────────────────────────
;;
;; {:base v :v v
;;  :clients {client-id {:from v :states [[v entries-or-nil] …]}}
;;  :here trie}
;;
;; :base is the version of the snapshot the link started with: a copy
;; planned before it is not judged. For one client, every version from
;; :from on is known: its state is the last of :states at or before it,
;; or none at all before the first. :here holds every filter the sender
;; placed here in any state still kept, so that the clients owed a copy can
;; be found by its topic rather than by asking of every client.

(defn- here-filters [me states]
  (into #{}
        (comp (mapcat second)
              (keep (fn [[f [_ at]]] (when (= me at) f))))
        states))

(defn- reindex [here me client-id before after]
  (let [old (here-filters me before)
        new (here-filters me after)]
    (as-> here h
      (reduce (fn [h f] (trie/trie-delete h f {:client-id client-id :topic-filter f}))
              h (remove new old))
      (reduce (fn [h f] (trie/trie-insert h f {:client-id client-id :topic-filter f}))
              h (remove old new)))))

(defn- record [view me v client-id entries]
  (let [{:keys [from states]} (get-in view [:clients client-id])
        grown  (conj (or states []) [v (not-empty entries)])
        [states' from'] (if (> (count grown) (long history))
                          (let [kept (subvec grown (- (count grown) (long history)))]
                            [kept (ffirst kept)])
                          [grown (or from (:base view))])]
    (-> view
        (assoc-in [:clients client-id] {:from from' :states states'})
        (update :here reindex me client-id states states'))))

(defn view-apply
  "The receiver's copy `view` of a sender's view, as seen from broker `me`,
   with `change` applied: {:v :snapshot? :clients {client-id entries}}. A
   snapshot replaces whatever was there. A change at or before the version
   already held, or with no snapshot before it, changes nothing."
  [view me {:keys [v snapshot? clients]}]
  (let [v (long v)]
    (cond
      snapshot?
      (reduce-kv (fn [view c entries] (record view me v c entries))
                 {:base v :v v :clients {} :here (trie/make-trie)}
                 clients)

      (or (nil? view) (<= v (long (:v view))))
      view

      :else
      (reduce-kv (fn [view c entries] (record view me v c entries))
                 (assoc view :v v)
                 clients))))

(defn covers?
  "Whether `view` can judge a copy planned at version `v`."
  [view v]
  (boolean (and view v (>= (long v) (long (:base view))))))

(defn state-at
  "`client-id`'s entries in the sender's view at version `v` — {} for none
   — or ::unknown when the history kept does not reach back that far."
  [view client-id v]
  (let [v (long v)]
    (cond
      (< v (long (:base view))) ::unknown
      :else
      (if-let [{:keys [from states]} (get-in view [:clients client-id])]
        (if (< v (long from))
          ::unknown
          (or (some (fn [[sv entries]] (when (<= (long sv) v) (or entries {})))
                    (rseq states))
              {}))
        {}))))

(defn serve?
  "Whether broker `me` delivers a copy on `topic`, planned at `v`, to its
   subscriber `client-id` — its sender did not say it served the client
   itself (`not`), and either knew of nothing of the client's that
   matches, or had everything that matches here. Unknown, it delivers, as
   it did before any of this."
  [view me client-id topic v not-served]
  (and (not (contains? not-served client-id))
       (let [st (state-at view client-id v)]
         (or (= ::unknown st)
             (every? (fn [[f [_ at]]] (or (= me at) (not (filter-matches? f topic))))
                     st)))))

(defn owed
  "The clients a copy on `topic`, planned at `v`, is broker `me`'s to
   deliver or queue: [{:client-id :qos}], each at the highest QoS of its
   matching subscriptions the sender had here."
  [view me topic v not-served]
  (into []
        (keep (fn [c]
                (when-not (contains? not-served c)
                  (let [st (state-at view c v)]
                    (when (map? st)
                      (let [qs (keep (fn [[f [q at]]]
                                       (when (and (= me at) (filter-matches? f topic)) q))
                                     st)]
                        (when (seq qs)
                          {:client-id c :qos (long (apply max qs))})))))))
        (distinct (map :client-id (trie/trie-matching-vals (:here view) topic)))))
