(ns mqttkat.sim-test
  "The simulation itself: that a seed replays, that a design with nothing
   going wrong passes, and that the checker counts by doc/guarantees.md.
   And, tagged :sim, the sweep a design has to pass. No broker, so not
   portable: nothing in it reaches a socket."
  (:require [clojure.test :refer [deftest is testing]]
            [mqttkat.sim.check :as check]
            [mqttkat.sim.core :as sim]
            [mqttkat.sim.current :as current]
            [mqttkat.sim.runner :as r]))

(def ^:private short-run
  (r/config {:publish-ms 5000 :drain-ms 60000
             :faults {:kill-every [1500 3000] :down-ms [500 2000]
                      :drop-every [300 1000] :lag-every [500 1500]
                      :until 5000}}))

(deftest a-seed-replays-event-for-event
  (let [a (r/run 7 short-run)
        b (r/run 7 short-run)]
    (is (pos? (:steps a)))
    (is (= (:steps a) (:steps b)))
    (is (= (:ledger a) (:ledger b)))
    (is (= (:trace a) (:trace b)))
    (testing "and another seed is another run"
      (is (not= (:ledger a) (:ledger (r/run 8 short-run)))))))

(deftest with-nothing-going-wrong-everything-arrives-once
  (let [{:keys [result] :as w} (r/run 3 (r/config short-run r/calm))]
    (is (:ok? result))
    (is (pos? (:owed result)))
    (is (= (:published result) (:accepted result)))
    (is (empty? (:crash-window result)))
    (is (empty? (:duplicate result)))
    (is (empty? (get-in w [:ledger :kills])))))

(deftest a-killed-broker-is-in-the-run-and-what-it-held-is-noted
  (let [w (r/run 7 short-run)]
    (is (seq (get-in w [:ledger :kills])))
    (is (seq (get-in w [:ledger :drops])))
    (is (seq (get-in w [:ledger :crash-held])))))

(defn- judged
  "check/check over a hand-made ledger: subscriber s1 on eo/#, s2 on t/#."
  [ledger queued]
  (check/check {:config {:model {:eo-filters ["eo/#"]}}
                :nodes {"s1" {:role :subscriber :filters ["eo/#"]}
                        "s2" {:role :subscriber :filters ["t/#"]}}
                :ledger ledger}
               (fn [_ c] (get queued c))))

(deftest the-checker-sorts-what-went-wrong-by-rule
  (let [pub (fn [topic qos] {:topic topic :qos qos :accepted 1})
        published {"a" (pub "eo/x" 2) "b" (pub "eo/x" 2) "c" (pub "t/x" 2)
                   "d" (pub "t/x" 2) "e" (pub "t/x" 1) "f" (pub "t/x" 2)
                   "g" {:topic "t/x" :qos 2}}
        delivered {"s1" {"b" [[1 1 :b1] [2 2 :b2]]}
                   "s2" {"d" [[1 1 :b1] [2 2 :b2]] "e" [[1 1 :b1] [2 2 :b1]] "f" [[1 1 :b1]]}}
        ledger    {:published published :delivered delivered
                   :crash-held [{:msg "c" :client "s2"}]}]
    (let [r (judged ledger {})]
      (is (= ["a"] (map :msg (:eo-lost r))) "R3: lost")
      (is (= ["b"] (map :msg (:eo-duplicate r))) "R3: twice")
      (is (= ["c"] (map :msg (:crash-window r))) "R1: in a crash window")
      (is (= [] (:lost r)))
      (is (= ["d"] (map :msg (:duplicate r))) "R2: twice, no broker killed; QoS 1 may repeat")
      (is (= 6 (:owed r)) "never accepted, never owed")
      (is (not (:ok? r))))
    (testing "after a kill, a QoS 2 duplicate elsewhere is allowed"
      (let [r (judged (assoc ledger :kills [{:broker :b1 :at 0}]) {})]
        (is (= [] (:duplicate r)))
        (is (= ["d"] (map :msg (:duplicate-after-crash r))))))
    (testing "R5: lost, and still on the queue"
      (let [r (judged ledger {"s1" {"k" {:m {:id "a"}}}})]
        (is (= ["a"] (map :msg (:stuck r))))))))

(deftest a-copy-for-a-broker-that-is-down-comes-back
  (let [w (-> (sim/world 1 {:net {:link [1 1]}} {:handle (fn [w ev] (sim/note w :got ev))})
              (sim/add-node :a {})
              (sim/add-node :b {}))
        w (-> w
              (sim/kill :b {} [0 0])
              (sim/send-link :a :b {:x 1})
              (sim/run 100))]
    (is (= [{:to :a :type :undelivered :peer :b :msg {:x 1} :epoch 0}]
           (get-in w [:ledger :got])))))

(deftest leases-release-the-identifiers-a-dead-broker-left-the-client-holding
  ;; The short runs whose current design, without leases, gave a later
  ;; message an identifier the client still held from a broker that died
  ;; (R6): found rather than pinned, since any change to the model moves
  ;; which seeds they are.
  (let [reused (filter #(seq (get-in (r/run % short-run) [:result :identifier-reused]))
                       (range 30))]
    (is (seq reused) "some short run reuses an identifier without leases")
    (doseq [seed reused]
      (testing (str "seed " seed)
        (is (empty? (get-in (r/run seed (r/config short-run r/leases)) [:result :identifier-reused]))
            "with them")
        (is (:ok? (:result (r/run seed (r/config short-run r/leases r/race-fixes
                                                 (r/forward-ack-as :receipt)))))
            "and with the other fixes, nothing broken")))
    (is (some #(pos? (get-in (r/run % (r/config short-run r/leases)) [:ledger :stray-pubrels] 0))
              reused)
        "released by PUBRELs nobody was owed")))

(deftest with-leases-and-nothing-going-wrong-nothing-is-released
  (let [w (r/run 3 (r/config short-run r/calm r/leases))]
    (is (:ok? (:result w)))
    (is (zero? (get-in w [:ledger :stray-pubrels] 0)))
    (is (pos? (get-in w [:ledger :leases] 0)))))

(def ^:private fixed
  "What the code does now: leases, the race fixes, forward-ack on receipt."
  [r/leases r/race-fixes (r/forward-ack-as :receipt)])

(deftest a-hand-off-closes-the-crash-window
  ;; Chaos run 20261022-133535: a broker killed took the copies for kept
  ;; sessions waiting in its memory, whose publishers had been told they
  ;; were safe (doc/guarantees.md, R1). Acknowledged only once each copy
  ;; has left memory, answered for by the client or after 50 ms put on
  ;; the cluster's queue, none is. Found rather than pinned, as above.
  (let [crashed (filter #(seq (get-in (r/run % (apply r/config short-run fixed)) [:result :crash-window]))
                        (range 10))]
    (is (seq crashed) "some short run loses what a broker held in memory")
    (doseq [seed crashed
            until [:acked :sent]]
      (testing (str "seed " seed ", " until)
        (let [w (r/run seed (apply r/config short-run (conj fixed (r/hand-off 50 until))))]
          (is (:ok? (:result w)) (pr-str (check/summary (:result w))))
          (when (= :acked until)
            (is (empty? (get-in w [:result :crash-window])) "none in memory when it died"))
          (is (zero? (get-in w [:ledger :hand-off-vanished] 0)) "every copy waited on accounted for"))))))

(deftest with-a-hand-off-and-nothing-going-wrong-nothing-is-slower
  ;; With nothing failing, every subscriber answers well within the wait
  ;; or is written to the queue: as many accepted as without.
  (let [accepted #(count (filter (comp :accepted val) (get-in % [:ledger :published])))
        today    (r/run 3 (apply r/config short-run r/calm fixed))
        handed   (r/run 3 (apply r/config short-run r/calm (conj fixed (r/hand-off 50 :acked))))]
    (is (:ok? (:result handed)))
    (is (>= (accepted handed) (* 0.98 (accepted today))) [(accepted today) (accepted handed)])))

(deftest ^:sim the-current-design-keeps-the-guarantees
  ;; It does not, today: a broker killed with a QoS 2 message in flight
  ;; leaves the client holding its identifier, which a later message is
  ;; given and dropped under (doc/guarantees.md, R6, then R3).
  (let [{:keys [failed] :as res} (r/sweep (range 20) (r/config) current/model)]
    (is (empty? failed) (pr-str (dissoc res :failed)))))

(deftest ^:sim the-current-design-with-leases-keeps-the-guarantees
  ;; It does, on 100 seeds (thoughts.md, 20261020). The bridge can still
  ;; lose a copy for a broker that is down when the broker that forwarded
  ;; it dies, which forward-ack closes; this sweep did not hit it.
  (let [{:keys [failed] :as res} (r/sweep (range 20) (r/config r/leases) current/model)]
    (is (empty? failed) (pr-str (dissoc res :failed)))))

(deftest ^:sim the-current-design-with-leases-and-forward-ack-keeps-the-guarantees
  (let [{:keys [failed] :as res} (r/sweep (range 20) (r/config r/leases r/forward-ack) current/model)]
    (is (empty? failed) (pr-str (dissoc res :failed)))))

(deftest ^:sim a-hand-off-keeps-the-guarantees-and-loses-nothing-to-a-crash
  (let [res (r/sweep (range 20) (apply r/config r/harsh (conj fixed (r/hand-off 50 :acked))) current/model)]
    (is (empty? (:failed res)) (pr-str (dissoc res :failed)))
    (is (zero? (get-in res [:counts :crash-window] 0)) (pr-str (:counts res)))))
