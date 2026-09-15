(ns mqttkat.trie
  "The subscription trie: triennium's layout, with the three operations the
   broker needed to get right on top of it.

   Nested maps keyed by topic level, with the values stored at a node under
   :values. The same structure serves the broker's own tries in
   mqttkat.handlers and the copy of the cluster's subscriptions that
   mqttkat.rama.cluster keeps fed from Rama, which is why it lives apart from
   both: the Rama side must not pull in the broker, and the broker must not
   care where else a trie is kept."
  (:require [clojurewerkz.triennium.mqtt :as tr]))

(def matches-one "+")
(def matches-none-or-many "#")

(defn make-trie [] (tr/make-trie))

;; ── insert, match, delete ────────────────────────────────────────────────
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

(defn- matching-values
  "Every value stored under a filter matching `segments`, from `node` down.

   Three branches at each level, which is the whole of §4.7.1: the literal
   segment, `+` standing for exactly one, and `#` standing for this level and
   all below it — so `#` contributes wherever it is found and does not recurse.

   The empty-segments case is the one triennium got wrong. When the topic runs
   out, this node's own values match, *and so does a `#` directly beneath it*:
   §4.7.1.2 makes the multi-level wildcard cover the parent level too, so
   `sport/#` matches `sport` and not only `sport/tennis`. triennium consulted
   `#` only at levels it passed through, never at the one it stopped on, so
   that subscription missed every message published to the parent itself."
  [node segments]
  (if (empty? segments)
    (into (set (:values node)) (:values (get node matches-none-or-many)))
    (let [s     (first segments)
          more  (rest segments)
          exact (get node s)
          any   (get node matches-one)]
      ;; Only into branches that exist. Recursing into a missing one looks
      ;; harmless — nil has no children, so it finds nothing — but each nil
      ;; node spawns two more nil recursions, one per branch, and the cost is
      ;; 2^levels-remaining. It measured 1.8 seconds for a 22-level topic
      ;; against a trie holding one short filter, and a publish is what triggers
      ;; it: any client could hang a broker thread with a deep enough topic.
      (cond-> (set (:values (get node matches-none-or-many)))
        exact (into (matching-values exact more))
        any   (into (matching-values any more))))))

(defn trie-matching-vals
  "The subscriptions matching `topic`."
  [trie ^String topic]
  (matching-values trie (tr/split-topic topic)))

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

(defn wildcard-rooted?
  "Whether a topic filter begins with a wildcard level."
  [^String topic-filter]
  (and topic-filter
       (or (.startsWith topic-filter "#")
           (.startsWith topic-filter "+"))))

(defn sieve-dollar
  "Drop wildcard-rooted filters when the topic name begins with $.

   MQTT 3.1.1 §4.7.2: a topic filter beginning with a wildcard must not match
   a topic name beginning with $. Those names are the server's own — $SYS and
   the like — and a client subscribing to `#` is asking for the application's
   traffic, not the broker's internals. A filter that names the $ level
   itself, `$SYS/#`, still matches, so the rule is about the first level of
   the filter rather than about $ appearing anywhere. The trie does not know
   this rule, so the filter each subscription was made with is kept alongside
   it and the matches are sieved here."
  [^String topic matched]
  (if (and topic (.startsWith topic "$"))
    (into #{} (remove (comp wildcard-rooted? :topic-filter)) matched)
    matched))
