(ns mqttkat.trie-test
  "The subscription tries.

   These go through mqttkat.handlers rather than through triennium directly,
   because triennium's own insert corrupts a node it did not create and the
   broker cannot use it as it stands."
  (:require [clojure.test :refer [deftest is testing]]
            [clojurewerkz.triennium.mqtt :as tr]
            [mqttkat.handlers :as h]))

(deftest a-filter-that-is-a-prefix-of-another
  (testing "inserting under an existing parent node keeps the values a set"
    ;; `sport/#` alongside `sport/tennis/#` is an ordinary pair of
    ;; subscriptions, and the second one inserted lands on a node the first
    ;; already created. triennium's insert conjes onto the node's nil :values
    ;; and stores a list; the delete that follows calls disj on it and throws.
    (let [t (-> (tr/make-trie)
                (h/trie-insert "sport/tennis" {:id 1})
                (h/trie-insert "sport" {:id 2}))]
      (is (set? (tr/find t "sport")))
      (is (= #{{:id 2}} (tr/find t "sport")))
      (is (= #{{:id 1}} (tr/find t "sport/tennis"))
          "the child the parent was created for is untouched")))

  (testing "and the entry can then be deleted again"
    (let [t (-> (tr/make-trie)
                (h/trie-insert "sport/tennis" {:id 1})
                (h/trie-insert "sport" {:id 2})
                (h/trie-delete "sport" {:id 2}))]
      (is (= #{} (tr/find t "sport")))
      (is (= #{{:id 1}} (tr/find t "sport/tennis"))))))

(deftest deleting-matches-the-whole-value
  (testing "an entry differing in any field is a different subscription"
    ;; What stops an MQTT 5 unsubscribe from removing a subscription that
    ;; shares a filter and QoS but differs in its options.
    (let [entry {:client-key :k :qos 1 :no-local? true}
          t     (h/trie-insert (tr/make-trie) "a/b" entry)]
      (is (= #{entry} (tr/find (h/trie-delete t "a/b" (dissoc entry :no-local?)) "a/b"))
          "a near miss removes nothing")
      (is (= #{} (tr/find (h/trie-delete t "a/b" entry) "a/b"))))))

(deftest several-subscribers-share-a-filter
  (testing "values accumulate rather than replace"
    (let [t (-> (tr/make-trie)
                (h/trie-insert "a/b" {:id 1})
                (h/trie-insert "a/b" {:id 2}))]
      (is (= #{{:id 1} {:id 2}} (tr/find t "a/b")))
      (is (= #{{:id 1}} (tr/find (h/trie-delete t "a/b" {:id 2}) "a/b"))))))

(deftest deleting-what-was-never-there
  (testing "leaves the trie alone rather than throwing"
    (let [t (h/trie-insert (tr/make-trie) "a/b" {:id 1})]
      (is (= t (h/trie-delete t "x/y" {:id 1})))
      (is (= #{{:id 1}} (tr/find (h/trie-delete t "a/b" {:id 99}) "a/b"))))))

;; ── matching (§4.7.1.2) ──────────────────────────────────────────────────

(defn- matches [filters topic]
  (set (h/trie-matching-vals
        (reduce (fn [t f] (h/trie-insert t f f)) (tr/make-trie) filters)
        topic)))

(deftest the-multi-level-wildcard-includes-its-parent
  (testing "§4.7.1.2: `sport/#` matches `sport` as well as `sport/tennis`"
    ;; \"the multi-level wildcard represents the parent and any number of child
    ;; levels\". triennium's matcher never looked at the `#` child of the node
    ;; the topic ended on, so a subscription to `sport/#` silently missed every
    ;; message published to `sport` itself.
    (is (= #{"sport/#"} (matches ["sport/#"] "sport")))
    (is (= #{"sport/#"} (matches ["sport/#"] "sport/tennis")))
    (is (= #{"sport/#"} (matches ["sport/#"] "sport/tennis/player1"))))

  (testing "alongside the exact filter, both match"
    (is (= #{"sport" "sport/#"} (matches ["sport" "sport/#"] "sport"))))

  (testing "and through a single-level wildcard"
    ;; `sport/+/#` is `sport`, one level, then zero or more — so it matches a
    ;; two-segment topic. Nothing but the recursion gets this right.
    (is (= #{"sport/+/#"} (matches ["sport/+/#"] "sport/tennis")))
    (is (= #{"sport/+/#"} (matches ["sport/+/#"] "sport/tennis/player1")))
    (is (= #{} (matches ["sport/+/#"] "sport")))))

(deftest the-ordinary-matches-still-hold
  (testing "exact, single-level and multi-level, as before"
    (let [fs ["a/b" "a/+" "a/#" "#" "+/b" "a/b/c"]]
      (is (= #{"a/b" "a/+" "a/#" "#" "+/b"} (matches fs "a/b")))
      (is (= #{"a/#" "#" "a/b/c"} (matches fs "a/b/c")))
      (is (= #{"a/#" "#"} (matches fs "a")))
      (is (= #{"#"} (matches fs "z")))))

  (testing "a filter that matches nothing published"
    (is (= #{} (matches ["a/b"] "a")))
    (is (= #{} (matches ["a/b"] "a/c")))
    (is (= #{} (matches ["a/+"] "a/b/c"))))

  (testing "several subscribers on one filter all come back"
    (let [t (-> (tr/make-trie)
                (h/trie-insert "a/#" {:id 1})
                (h/trie-insert "a/#" {:id 2}))]
      (is (= #{{:id 1} {:id 2}} (set (h/trie-matching-vals t "a")))))))
