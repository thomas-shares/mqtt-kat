(ns mqttkat.intent-test
  "Judging a copy by the view its sender planned it from. Pure: no broker,
   so nothing here is ^:portable."
  (:require [clojure.test :refer [deftest is testing]]
            [mqttkat.intent :as intent]
            [mqttkat.trie :as trie]))

(defn- entry
  "A cluster entry, as $$subscriptions has it."
  [client-id topic-filter qos broker-id connected? & {:as more}]
  (merge {:client-id client-id :topic-filter topic-filter :filter topic-filter :qos qos
          :broker-id broker-id :connected? connected?}
         more))

(deftest the-senders-index
  (testing "a connected entry is where its broker is, one away is away"
    (is (= {"s" {"a/#" [1 "b"]}}
           (intent/index-change {} nil (entry "s" "a/#" 1 "b" true))))
    (is (= {"s" {"a/#" [2 intent/away]}}
           (intent/index-change {} nil (entry "s" "a/#" 2 "b" false)))))
  (testing "replaced, and removed, client and all when it has nothing left"
    (let [i (intent/index-change {} nil (entry "s" "a/#" 1 "b" true))]
      (is (= {"s" {"a/#" [1 "c"]}}
             (intent/index-change i (entry "s" "a/#" 1 "b" true) (entry "s" "a/#" 1 "c" true))))
      (is (= {} (intent/index-change i (entry "s" "a/#" 1 "b" true) nil)))))
  (testing "a shared subscription is not in it"
    (is (= {} (intent/index-change {} nil (entry "s" "a/#" 1 "b" true :share-group "g")))))
  (testing "what changed, client by client"
    (is (= {"s" {"a" [1 "c"]} "t" nil}
           (intent/changes {"s" {"a" [1 "b"]} "t" {"x" [0 "b"]} "u" {"y" [0 "b"]}}
                           {"s" {"a" [1 "c"]} "u" {"y" [0 "b"]}})))))

(deftest matching-a-filter
  (is (intent/filter-matches? "a/b" "a/b"))
  (is (not (intent/filter-matches? "a/b" "a/c")))
  (is (intent/filter-matches? "a/+" "a/c"))
  (is (not (intent/filter-matches? "a/+" "a/c/d")))
  (is (intent/filter-matches? "a/#" "a/c/d"))
  (is (intent/filter-matches? "a/#" "a") "§4.7.1.2: # covers its parent level")
  (is (intent/filter-matches? "#" "a/b"))
  (is (not (intent/filter-matches? "#" "$SYS/x")) "§4.7.2: not a $ topic")
  (is (not (intent/filter-matches? "+/x" "$SYS/x")))
  (is (intent/filter-matches? "$SYS/#" "$SYS/x"))
  (is (intent/filter-matches? "a//b" "a//b") "an empty level is a level")
  (testing "a topic split once, for every filter it is asked of"
    (let [t (intent/topic "$SYS/x")]
      (is (intent/filter-matches? "$SYS/+" t))
      (is (not (intent/filter-matches? "#" t)))
      (is (not (intent/filter-matches? "$SYS" t))))))

(defn- view
  "B's copy of A's view, from a snapshot at `v` and then each change."
  [v clients & changes]
  (reduce #(intent/view-apply %1 "b" %2)
          (intent/view-apply nil "b" {:v v :snapshot? true :clients clients})
          changes))

(deftest judging-a-copy
  (let [v (view 10 {"here"  {"t/#" [1 "b"]}
                    "there" {"t/#" [1 "c"]}
                    "away"  {"t/#" [1 intent/away]}
                    "mixed" {"t/#" [1 "b"] "t/x" [2 "c"]}}
                {:v 11 :clients {"there" {"t/#" [1 "b"]}}}
                {:v 12 :clients {"here" nil}})]
    (testing "a copy planned before the snapshot is not judged"
      (is (not (intent/covers? v 9)))
      (is (intent/covers? v 10))
      (is (not (intent/covers? nil 10))))

    (testing "each client's state at the version the copy was planned at"
      (is (= {"t/#" [1 "c"]} (intent/state-at v "there" 10)))
      (is (= {"t/#" [1 "b"]} (intent/state-at v "there" 11)))
      (is (= {"t/#" [1 "b"]} (intent/state-at v "there" 50)) "and on, until it changes")
      (is (= {} (intent/state-at v "here" 12)) "gone from the view: nothing")
      (is (= {} (intent/state-at v "stranger" 11)) "never in it: nothing"))

    (testing "delivered here: had here, had nowhere, or matching nothing it had"
      (is (not (intent/withhold? v "b" "here" "t/1" 10 #{})))
      (is (not (intent/withhold? v "b" "here" "t/1" 12 #{})) "the sender no longer has it: nobody is serving it")
      (is (not (intent/withhold? v "b" "stranger" "t/1" 10 #{})))
      (is (not (intent/withhold? v "b" "there" "u/1" 10 #{})) "nothing it had matches"))

    (testing "withheld: had elsewhere, or away, or partly elsewhere, or served by the sender"
      (is (intent/withhold? v "b" "there" "t/1" 10 #{}) "on c when this was planned")
      (is (not (intent/withhold? v "b" "there" "t/1" 11 #{})) "on b by the next version")
      (is (intent/withhold? v "b" "away" "t/1" 10 #{}) "the sender queued it")
      (is (intent/withhold? v "b" "mixed" "t/x" 10 #{}))
      (is (not (intent/withhold? v "b" "mixed" "t/y" 10 #{})) "only the filter here matches")
      (is (intent/withhold? v "b" "here" "t/1" 10 #{"here"})))

    (testing "at QoS 0 alike: at most once, so only where the sender had it"
      (let [w (intent/view-apply v "b" {:v 13 :clients {"there" {"t/#" [0 "c"]}}})]
        (is (intent/withhold? w "b" "there" "t/1" 13 #{}) "a QoS 0 subscription there")
        (is (not (intent/withhold? w "b" "here" "t/1" 13 #{})))))

    (testing "owed: every client with a matching filter here, at its highest QoS here"
      (is (= #{{:client-id "here" :qos 1} {:client-id "mixed" :qos 1}}
             (set (intent/owed v "b" "t/x" 10 #{}))))
      (is (= #{{:client-id "here" :qos 1} {:client-id "mixed" :qos 1} {:client-id "there" :qos 1}}
             (set (intent/owed v "b" "t/x" 11 #{}))))
      (is (= #{{:client-id "mixed" :qos 1} {:client-id "there" :qos 1}}
             (set (intent/owed v "b" "t/x" 12 #{}))))
      (is (= #{{:client-id "there" :qos 1}}
             (set (intent/owed v "b" "t/x" 12 #{"mixed"})))
          "not one the sender served")
      (is (empty? (intent/owed v "b" "u/x" 12 #{}))))))

(deftest keeping-the-view
  (testing "a change at or before the version held changes nothing"
    (let [v (view 5 {"s" {"a" [1 "b"]}} {:v 6 :clients {"s" {"a" [1 "c"]}}})]
      (is (= v (intent/view-apply v "b" {:v 6 :clients {"s" nil}})))
      (is (= v (intent/view-apply v "b" {:v 3 :clients {"s" nil}})))))
  (testing "no snapshot, no view"
    (is (nil? (intent/view-apply nil "b" {:v 6 :clients {"s" nil}}))))
  (testing "a snapshot replaces it"
    (let [v (view 5 {"s" {"a" [1 "b"]}} {:v 6 :clients {"s" {"a" [1 "c"]}}})
          w (intent/view-apply v "b" {:v 2 :snapshot? true :clients {"t" {"x" [0 "b"]}}})]
      (is (= 2 (:base w)))
      (is (= {} (intent/state-at w "s" 6)))
      (is (= [{:client-id "t" :qos 0}] (intent/owed w "b" "x" 2 #{})))))
  (testing "a client's history is kept to its last states; older is unknown"
    (let [n (+ 5 (long intent/history))
          v (apply view 0 {"s" {"a" [1 "b"]}}
                   (for [i (range 1 n)]
                     {:v i :clients {"s" {"a" [1 (if (odd? i) "c" "b")]}}}))]
      (is (= (long intent/history) (count (get-in v [:clients "s" :states]))))
      (is (= ::intent/unknown (intent/state-at v "s" 1)))
      (is (intent/may-be-unknown? v 1))
      (is (not (intent/may-be-unknown? v (dec n))) "nobody is unknown that late")
      (is (not (intent/withhold? v "b" "s" "a" 1 #{})) "unknown: delivered, as before")
      (is (empty? (intent/owed v "b" "a" 1 #{})) "and not queued")
      (is (= {"a" [1 (if (odd? (dec n)) "c" "b")]} (intent/state-at v "s" (dec n))))))
  (testing "a client with no subscriptions left is forgotten, in time"
    (let [v (view 0 {"s" {"a" [1 "b"]}} {:v 1 :clients {"s" nil}})
          w (intent/view-apply v "b" {:v (+ 2 (long intent/forget-after)) :clients {"t" {"x" [0 "b"]}}})]
      (is (contains? (:clients v) "s"))
      (is (not (intent/may-be-unknown? v 0)) "every client known from the snapshot on")
      (is (not (contains? (:clients w) "s")))
      (is (empty? (trie/trie-matching-vals (:here w) "a")))))
  (testing "the filters here follow every state kept, and only those"
    (let [v (view 0 {"s" {"a" [1 "b"]}}
                  {:v 1 :clients {"s" {"a" [1 "c"]}}})]
      (is (= [{:client-id "s" :qos 1}] (intent/owed v "b" "a" 0 #{})) "still here at 0")
      (is (empty? (intent/owed v "b" "a" 1 #{})))
      (let [w (reduce #(intent/view-apply %1 "b" %2) v
                      (for [i (range 2 (+ 3 (long intent/history)))]
                        {:v i :clients {"s" {"a" [1 "c"]}}}))]
        (is (empty? (intent/owed w "b" "a" (+ 2 (long intent/history)) #{})))
        (is (empty? (trie/trie-matching-vals (:here w) "a"))
            "and gone from those here once no state kept has it here")))))
