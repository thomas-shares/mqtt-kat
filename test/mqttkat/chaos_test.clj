(ns mqttkat.chaos-test
  "The rules a chaos run is judged by (mqttkat.chaos.check), without a
   broker: each case is a hand-written account of what the clients saw."
  (:require [clojure.test :refer [deftest is testing]]
            [mqttkat.chaos.check :as check]
            [mqttkat.chaos.runner :as runner]))

(defn- run
  "The verdict on one subscription of client \"s\" to `filter` and the
   publishes and deliveries given. Times are in microseconds."
  [{:keys [sub publishes deliveries persistent? sessions-lost]
    :or   {persistent? true}}]
  (check/check {:publishes     publishes
                :subscriptions {"s" (if (map? sub) [sub] sub)}
                :clients       {"s" {:persistent? persistent?}}
                :deliveries    {"s" deliveries}
                :sessions-lost (or sessions-lost [])
                :events        []}
               {:subscribe-settle 100 :clean-grace 1000}))

(def ^:private sub {:filter "t/1" :qos 2 :sub-sent 0 :from 10})

(defn- msg [qos sent acked] {:topic "t/1" :qos qos :sent sent :acked acked})

(defn- kinds [result] (frequencies (map :kind (:violations result))))

(deftest topic-filters
  (is (check/matches? "t/1" "t/1"))
  (is (not (check/matches? "t/1" "t/2")))
  (is (check/matches? "t/+" "t/2"))
  (is (not (check/matches? "t/+" "t/2/x")))
  (is (check/matches? "t/#" "t/2/x"))
  (is (check/matches? "#" "t"))
  (is (not (check/matches? "t/1/x" "t/1"))))

(deftest what-a-subscriber-is-owed
  (testing "QoS 1 and 2, acknowledged, published after the SUBACK: owed once"
    (let [r (run {:sub sub
                  :publishes {[0 1] (msg 1 500 600) [0 2] (msg 2 700 800)}
                  :deliveries {[0 1] [{:at 650 :qos 1}] [0 2] [{:at 900 :qos 2}]}})]
      (is (:ok? r))
      (is (= 2 (get-in r [:stats :required])))))

  (testing "and missing, it is lost"
    (let [r (run {:sub sub :publishes {[0 1] (msg 1 500 600)} :deliveries {}})]
      (is (= {:lost 1} (kinds r)))
      (is (= {1 {:elsewhere 1}} (:lost-by r)))))

  (testing "the lower QoS decides: a QoS 2 publish to a QoS 0 subscription is owed nothing"
    (let [r (run {:sub (assoc sub :qos 0) :publishes {[0 1] (msg 2 500 600)}
                  :deliveries {}})]
      (is (= 0 (get-in r [:stats :required])))
      (is (= {:nothing-checked 1} (kinds r)) "and a run that owed nothing proves nothing")))

  (testing "not acknowledged to the publisher: may arrive, not owed"
    (let [r (run {:sub sub :publishes {[0 1] (msg 1 500 nil) [0 2] (msg 1 510 600)}
                  :deliveries {[0 2] [{:at 650 :qos 1}]}})]
      (is (:ok? r))
      (is (= 1 (get-in r [:stats :required])))))

  (testing "published before the subscription had settled: may arrive, not owed"
    (let [r (run {:sub sub :publishes {[0 1] (msg 1 50 60) [0 2] (msg 1 500 600)}
                  :deliveries {[0 1] [{:at 70 :qos 1}] [0 2] [{:at 650 :qos 1}]}})]
      (is (:ok? r))
      (is (= 1 (get-in r [:stats :required])))))

  (testing "acknowledged after the UNSUBSCRIBE went: not owed"
    (let [r (run {:sub (assoc sub :unsub-sent 590 :to 700 :ended-by :unsubscribe)
                  :publishes {[0 1] (msg 1 500 600) [0 2] (msg 1 520 580)}
                  :deliveries {[0 2] [{:at 585 :qos 1}]}})]
      (is (:ok? r))
      (is (= 1 (get-in r [:stats :required]))))))

(deftest what-a-clean-session-is-owed
  (testing "a clean session's messages die with its connection"
    (let [r (run {:sub (assoc sub :to 1200 :ended-by :drop) :persistent? false
                  :publishes {[0 1] (msg 1 150 180) [0 2] (msg 1 500 600)}
                  :deliveries {[0 1] [{:at 190 :qos 1}]}})]
      (is (:ok? r) "the second was acknowledged less than the grace before the drop")
      (is (= 1 (get-in r [:stats :required])))))
  (testing "but one it outlived by the grace was owed"
    (let [r (run {:sub (assoc sub :to 5000 :ended-by :drop) :persistent? false
                  :publishes {[0 1] (msg 1 500 600)} :deliveries {}})]
      (is (= {:lost 1} (kinds r))))))

(deftest how-often
  (testing "QoS 1 may repeat: counted, not wrong"
    (let [r (run {:sub sub :publishes {[0 1] (msg 1 500 600)}
                  :deliveries {[0 1] [{:at 650 :qos 1} {:at 900 :qos 1}]}})]
      (is (:ok? r))
      (is (= 1 (get-in r [:stats :qos1-repeats])))))

  (testing "QoS 2 is exactly once"
    (let [r (run {:sub sub :publishes {[0 1] (msg 2 500 600)}
                  :deliveries {[0 1] [{:at 650 :qos 2} {:at 900 :qos 2}]}})]
      (is (= {:duplicate 1} (kinds r)))))

  (testing "QoS 0 is at most once"
    (let [r (run {:sub sub :publishes {[0 1] (msg 0 500 nil) [0 2] (msg 1 500 600)}
                  :deliveries {[0 1] [{:at 650 :qos 0} {:at 900 :qos 0}]
                               [0 2] [{:at 650 :qos 1}]}})]
      (is (= {:duplicate 1} (kinds r))))))

(deftest what-should-not-arrive
  (testing "published after the UNSUBACK"
    (let [r (run {:sub (assoc sub :unsub-sent 590 :to 700 :ended-by :unsubscribe)
                  :publishes {[0 1] (msg 1 500 550) [0 2] (msg 1 800 850)}
                  :deliveries {[0 1] [{:at 560 :qos 1}] [0 2] [{:at 900 :qos 1}]}})]
      (is (= {:unexpected 1} (kinds r)))))

  (testing "a topic the subscription does not match"
    (let [r (run {:sub sub
                  :publishes {[0 1] (msg 1 500 550) [0 2] (assoc (msg 1 500 550) :topic "t/2")}
                  :deliveries {[0 1] [{:at 560 :qos 1}] [0 2] [{:at 560 :qos 1}]}})]
      (is (= {:unexpected 1} (kinds r)))))

  (testing "at a QoS above the subscription's"
    (let [r (run {:sub (assoc sub :qos 1) :publishes {[0 1] (msg 2 500 550)}
                  :deliveries {[0 1] [{:at 560 :qos 2}]}})]
      (is (= {:qos-upgraded 1} (kinds r))))))

(deftest a-lost-session
  (let [r (run {:sub (assoc sub :to 2000 :ended-by :session-lost)
                :publishes {[0 1] (msg 1 500 600)}
                :deliveries {[0 1] [{:at 650 :qos 1}]}
                :sessions-lost [{:client "s" :at 2500}]})]
    (is (= {:session-lost 1} (kinds r)))))

(deftest configuration
  (testing "files merge over the defaults, maps deeply, and nil takes an action out"
    (let [a (java.io.File/createTempFile "chaos" ".edn")
          b (java.io.File/createTempFile "chaos" ".edn")]
      (spit a (pr-str {:load  {:rate 7}
                       :chaos {:kill-broker {:every-ms [1 2]} :kill-client {:every-ms [3 4]}}}))
      (spit b (pr-str {:chaos {:kill-broker nil}}))
      (let [cfg (runner/config [(str a) (str b)])]
        (is (= 7 (get-in cfg [:load :rate])))
        (is (= 10 (get-in cfg [:load :publishers])) "the rest of :load from the defaults")
        (is (nil? (get-in cfg [:chaos :kill-broker])))
        (is (= {:every-ms [3 4]} (get-in cfg [:chaos :kill-client]))))))
  (testing "broker n is on :port + n - 1"
    (is (= [{:n 1 :host "h" :port 1885} {:n 2 :host "h" :port 1886}]
           (runner/brokers {:setup {:brokers {:count 2 :host "h" :port 1885}}}))))
  (testing "the scenarios in chaos/ read"
    (doseq [f (.listFiles (java.io.File. "chaos"))]
      (is (map? (runner/config [(str f)])) (str f)))))
