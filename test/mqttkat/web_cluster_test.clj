(ns mqttkat.web-cluster-test
  "Adding the brokers up: pure functions of what each broker reported, so
   none of this needs a cluster. mqttkat.rama-test has the one that does —
   reports kept in Rama and read back by another broker's console."
  (:require [clojure.test :refer [deftest is testing]]
            [mqttkat.web.cluster :as cluster]
            [mqttkat.web.state :as state]))

(deftest the-cluster-reading-adds-up-what-is-per-broker
  (let [a {:t 1000 :clients 3 :parked 1 :heap 100 :heap-max 400 :cores 2 :retained 7 :listed 9
           :uptime 50 :client-total 4 :tracked-topics 3
           :rates {"in" 1.5 "out" 3.0 "retained" 0.5} :cpu 0.25}
        b {:t 2000 :clients 5 :parked 0 :heap 200 :heap-max 400 :cores 4 :retained 7 :listed 9
           :uptime 90 :client-total 5 :tracked-topics 2 :topics-truncated true
           :rates {"in" 2.5 "out" 1.0 "retained" 0.5} :cpu nil}
        r (cluster/cluster-reading [a b])]
    (testing "clients, heap and cores add up"
      (is (= 8 (:clients r)))
      (is (= 300 (:heap r)))
      (is (= 800 (:heap-max r)))
      (is (= 6 (:cores r)))
      (is (= 9 (:client-total r))))
    (testing "the retained messages are the cluster's on every broker, so are not"
      (is (= 7 (:retained r)))
      (is (= 9 (:listed r)))
      (is (= 0.5 (get-in r [:rates "retained"]))))
    (testing "rates add up"
      (is (= 4.0 (get-in r [:rates "in"])))
      (is (= 4.0 (get-in r [:rates "out"]))))
    (testing "the CPU of those that know theirs"
      (is (= 0.25 (:cpu r))))
    (testing "the longest uptime, and truncated if any broker's topics are"
      (is (= 90 (:uptime r)))
      (is (true? (:topics-truncated r))))
    (testing "and it reads like any broker's"
      (is (= "8" (get (state/fields r) "m-clients")))
      (is (= "9 listed" (get (state/fields r) "clients-note"))))))

(deftest topics-add-up-across-brokers
  (is (= [{:topic "a" :total 15 :rate 3.0}
          {:topic "c" :total 1 :rate 2.0}
          {:topic "b" :total 4 :rate 1.0}]
         (cluster/merge-topics [[{:topic "a" :total 10 :rate 1.0} {:topic "b" :total 4 :rate 1.0}]
                                [{:topic "a" :total 5 :rate 2.0} {:topic "c" :total 1 :rate 2.0}]]))
      "a topic published to on two brokers is one row with both counts, busiest first"))

(deftest clients-say-which-broker-they-are-on
  (let [{:keys [total rows]}
        (cluster/merge-clients [["b1" {:total 2 :rows [{:id "x" :connected true :inflight 0 :queued 0}
                                                       {:id "moved" :connected false :inflight 0 :queued 0}]}]
                                ["b2" {:total 1 :rows [{:id "moved" :connected true :inflight 2 :queued 0}]}]])]
    (is (= 3 total) "the total is every broker's")
    (is (= [["moved" "b2"] ["x" "b1"]] (mapv (juxt :id :broker) rows))
        "a client parked on one broker and connected to another is shown where it is connected")))

(deftest events-are-merged-newest-first
  (is (= [{:t 3 :subject "c" :broker "b2"} {:t 2 :subject "b" :broker "b1"} {:t 1 :subject "a" :broker "b2"}]
         (cluster/merge-events [["b1" [{:t 2 :subject "b"}]]
                                ["b2" [{:t 3 :subject "c"} {:t 1 :subject "a"}]]])))
  (is (= [{:t 1 :subject "a"}] (cluster/merge-events [["b1" [{:t 1 :subject "a"}]]]))
      "and with one broker, where is not worth saying"))

(deftest the-cluster-chart-is-a-sum-a-second
  (let [p (fn [t n] {:t t :clients n :in n :out n :queued 0 :heap n})]
    (testing "each second is the brokers' points for it, added"
      (is (= [{:t 10000 :clients 3 :in 3 :out 3 :queued 0 :heap 3}
              {:t 11000 :clients 5 :in 5 :out 5 :queued 0 :heap 5}]
             (cluster/combine-series [[(p 10100 1) (p 11050 2)] [(p 10900 2) (p 11900 3)]] 10 11))))
    (testing "a second a broker has no point for takes its last one, for a while"
      (is (= [[10000 3] [11000 3] [12000 3] [14000 1]]
             (mapv (juxt :t :clients)
                   (cluster/combine-series [[(p 10000 1) (p 14000 1)] [(p 10000 2)]] 10 14)))
          "sampled on its own clock, a broker now and then skips a second; after a few, it has stopped"))
    (testing "and a second nobody has a point for is left out, not drawn as zero"
      (is (= [10000] (mapv :t (cluster/combine-series [[(p 10000 1)]] 9 10)))))))
