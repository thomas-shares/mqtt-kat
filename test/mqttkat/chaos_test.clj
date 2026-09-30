(ns mqttkat.chaos-test
  "The rules a chaos run is judged by (mqttkat.chaos.check), without a
   broker: each case is a hand-written account of what the clients saw."
  (:require [clojure.test :refer [deftest is testing]]
            [mqttkat.chaos.check :as check]
            [mqttkat.chaos.client :as c]
            [mqttkat.chaos.ledger :as ledger]
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

  (testing "acknowledged after the UNSUBSCRIBE went, or too shortly before: not owed"
    ;; §3.10.4 lets the broker drop what it still had queued for the
    ;; subscription, and under load that queue is seconds long.
    (let [r (run {:sub (assoc sub :unsub-sent 3000 :to 3100 :ended-by :unsubscribe)
                  :publishes {[0 1] (msg 1 2500 3050) [0 2] (msg 1 2500 2900)
                              [0 3] (msg 1 500 600)}
                  :deliveries {[0 3] [{:at 650 :qos 1}]}})]
      (is (:ok? r))
      (is (= 1 (get-in r [:stats :required])) "only the one acked the grace before it"))))

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
    (let [r (run {:sub (assoc sub :unsub-sent 2590 :to 2700 :ended-by :unsubscribe)
                  :publishes {[0 1] (msg 1 500 550) [0 2] (msg 1 2800 2850)}
                  :deliveries {[0 1] [{:at 560 :qos 1}] [0 2] [{:at 2900 :qos 1}]}})]
      (is (= {:unexpected 1} (kinds r)))))

  (testing "acknowledged longer than the settle before the SUBSCRIBE went"
    (let [r (run {:sub (assoc sub :sub-sent 1000 :from 1010)
                  :publishes {[0 1] (msg 1 700 800) [0 2] (msg 1 900 950) [0 3] (msg 1 1500 1600)}
                  :deliveries {[0 1] [{:at 1020 :qos 1}] [0 2] [{:at 1020 :qos 1}]
                               [0 3] [{:at 1610 :qos 1}]}})]
      (is (= {:unexpected 1} (kinds r))
          "the one acknowledged within it may have been matched after the SUBSCRIBE")))

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

(deftest the-ledger-keeps-what-the-check-needs
  (testing "a delivery is a few bits, and the verdict is the same as from the plain record"
    ;; One map entry per delivery ran chaos/long.edn out of heap: a hundred
    ;; million of them. The ledger keeps bits per publisher and sequence.
    (let [lg (ledger/ledger)]
      (ledger/subscribed! lg "s" "t/1" 2 (ledger/now lg))
      (Thread/sleep 2)
      (doseq [[sq qos] [[1 1] [2 2] [3 2] [4 1]]]
        (ledger/published! lg [7 sq] {:topic "t/1" :qos qos :pub-broker 1})
        (ledger/acked! lg [7 sq]))
      (ledger/delivered! lg "s" [7 1] 1 1)
      (ledger/delivered! lg "s" [7 1] 1 1)           ; QoS 1 may repeat
      (ledger/delivered! lg "s" [7 2] 2 1)
      (ledger/delivered! lg "s" [7 2] 2 1)           ; QoS 2 may not
      (ledger/delivered! lg "s" [7 3] 2 1)           ; [7 4] never arrives
      (is (= 5 (ledger/delivery-count lg)))
      (is (= 4 (ledger/acked-count lg)))
      (let [r (check/check (ledger/snapshot lg) {})]
        (is (= {:duplicate 1 :lost 1} (:counts r)))
        (is (= [7 4] (:msg (first (filter (comp #{:lost} :kind) (:violations r))))))
        (is (= 2 (:times (first (filter (comp #{:duplicate} :kind) (:violations r))))))
        (is (= 1 (get-in r [:stats :qos1-repeats])))
        (is (= 5 (get-in r [:stats :deliveries])))))))

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

;; A redirect is followed, in either of its forms (§4.13), and nothing else
;; is taken for one.
(deftest a-client-goes-where-it-is-sent
  (let [handle  @#'c/handle
        client  (fn [follow?]
                  (doto (c/make (ledger/ledger) {:id "s" :kind :sub :mqtt5? true :persistent? true
                                                 :filter "t" :sub-qos 1 :follow-redirects? follow?})
                    (-> :conn (reset! {:epoch 1}))))
        sent-on (fn [packet-type code]
                  {:packet-type packet-type :reason-code code
                   :properties {:server-reference "127.0.0.1:1886"}})]
    (testing "a CONNACK Use another server"
      (let [cl (client true)]
        (handle cl 1 (sent-on :CONNACK 0x9C))
        (is (nil? @(:conn cl)) "the connection is let go")
        (is (= "127.0.0.1:1886" (c/take-redirect! cl)))
        (is (nil? (c/take-redirect! cl)) "once")))
    (testing "a DISCONNECT Server moved"
      (let [cl (client true)]
        (handle cl 1 (sent-on :DISCONNECT 0x9D))
        (is (= "127.0.0.1:1886" (c/take-redirect! cl)))))
    (testing "not by a client that does not follow them"
      (let [cl (client false)]
        (handle cl 1 (sent-on :CONNACK 0x9C))
        (is (nil? (c/take-redirect! cl)))))
    (testing "nor any other refusal"
      (let [cl (client true)]
        (handle cl 1 (sent-on :CONNACK 0x87))
        (is (nil? (c/take-redirect! cl)))
        (is (nil? @(:conn cl)))))
    (testing "and a policy is what turns following on"
      (is (not (runner/redirecting? (runner/config []))))
      (is (runner/redirecting? (runner/config ["chaos/redirect.edn"])))
      (is (runner/redirecting? (runner/config ["chaos/load.edn"]))))))

;; The check looks each subscription's messages up by time rather than trying
;; every message against every subscription, which a long run could not
;; afford. Against the rules applied the slow way, on random subscriptions and
;; messages, it must find the same.
(deftest the-check-finds-by-time-what-the-rules-say
  (let [rnd       (java.util.Random. 11)
        r         #(.nextInt rnd (int %))
        required? @#'check/required?
        possible? @#'check/possible?
        eff       @#'check/effective-qos
        by-time   @#'check/by-time
        slowly    (fn [ok? subs m]
                    (reduce (fn [best s]
                              (if (and (check/matches? (:filter s) (:topic m)) (ok? m s))
                                (max (long (or best 0)) (long (eff m s)))
                                best))
                            nil subs))]
    (dotimes [_ 2000]
      (let [opts {:subscribe-settle (r 5) :clean-grace (r 20)}
            subs (vec (for [_ (range (r 5))]
                        (let [ss (r 100)
                              to (when (pos? (r 3)) (+ ss (r 50)))]
                          (cond-> {:filter (rand-nth ["a/+" "a/1" "a/2"]) :qos (r 3) :sub-sent ss}
                            (pos? (r 5))          (assoc :from (inc ss))
                            to                    (assoc :to to :ended-by (rand-nth [:unsubscribe :drop :session-lost]))
                            (and to (pos? (r 2))) (assoc :unsub-sent (- to (r 3)))))))
            pubs (into {} (for [i (range (r 40))
                                :let [sent (r 150)]]
                            [[1 i] {:topic (rand-nth ["a/1" "a/2"]) :qos (r 3) :sent sent
                                    :acked (when (pos? (r 3)) (+ sent (r 30)))}]))
            by-topic (into {} (for [[t ms] (group-by (comp :topic val) pubs)]
                                [t (by-time (comp :sent val) ms)]))
            owed (#'check/required-for opts check/matches? by-topic subs)
            idx  (#'check/sub-index subs)]
        (doseq [[id m] pubs]
          (is (= (slowly #(required? opts %1 %2) subs m) (:required (get owed id))))
          (is (= (slowly #(possible? opts %1 %2) subs m) (#'check/possible-qos opts check/matches? idx m))))))))
