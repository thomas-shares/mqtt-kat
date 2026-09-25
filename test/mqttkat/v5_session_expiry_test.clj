(ns mqttkat.v5-session-expiry-test
  "MQTT 5.0 Session Expiry Interval (§3.1.2.11.2, §3.14.2.2.2).

   Version 5 splits what 3.1.1 called Clean Session into two decisions. Clean
   Start says whether to *resume* an existing session; Session Expiry says how
   long a session *survives* after the connection ends. A client may therefore
   start fresh and still keep its session for an hour, which 3.1.1 could not
   express at all.

   0 — the default — means the session ends with the connection, which is what
   makes a 3.1.1 client's behaviour fall out unchanged.

   Written before the implementation. Session Expiry was decoded and ignored,
   so sessions persisted on `clean-session?` alone and never expired."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [mqttkat.client :as client]
            [mqttkat.test-util :as tu]))

(use-fixtures :once tu/broker-fixture)

(defn- connect-with
  "A v5 connection with an explicit expiry and clean-start."
  [id expiry clean-start?]
  (tu/connect-v5! nil :id id :clean-session? clean-start?
                  :properties {:session-expiry-interval expiry}))

(defn- subscribe! [c topic]
  (tu/send-v5! c {:packet-type :SUBSCRIBE :packet-identifier 1
                  :topics [{:qos 1 :topic-filter topic}]})
  (tu/expect! (:ch c) :SUBACK))

(defn- disconnect! [c & [expiry]]
  (tu/send-v5! c (cond-> {:packet-type :DISCONNECT}
                   expiry (assoc :properties {:session-expiry-interval expiry})))
  (Thread/sleep 300))

(deftest ^:portable an-expiry-of-zero-ends-the-session-with-the-connection
  (testing "the default, and what a 3.1.1 client effectively has"
    (let [id    (tu/client-id "expiry-zero")
          topic (tu/topic "expiry-zero")
          a     (connect-with id 0 true)]
      (is (false? (:session-present? (:connack a))))
      (subscribe! a topic)
      (disconnect! a)
      (let [b (connect-with id 0 false)]
        (try
          (is (false? (:session-present? (:connack b)))
              "nothing survives an expiry of zero, even asking to resume")
          (finally (tu/close! a b)))))))

(deftest ^:portable a-session-survives-for-as-long-as-it-asked-to
  (testing "clean start with an expiry keeps the session afterwards"
    ;; The combination 3.1.1 cannot express: start fresh, then persist. The
    ;; broker used to key this off clean-session? alone, so a clean start meant
    ;; the session was discarded however long an expiry was asked for.
    (let [id    (tu/client-id "expiry-lives")
          topic (tu/topic "expiry-lives")
          a     (connect-with id 30 true)]
      (is (false? (:session-present? (:connack a))) "clean start: nothing resumed")
      (subscribe! a topic)
      (disconnect! a)
      (let [b (connect-with id 30 false)]
        (try
          (is (true? (:session-present? (:connack b)))
              "the session is still there to resume")
          (finally (tu/close! a b))))))

  (testing "and is gone once the interval has passed"
    (let [id    (tu/client-id "expiry-dies")
          topic (tu/topic "expiry-dies")
          a     (connect-with id 1 true)]
      (subscribe! a topic)
      (disconnect! a)
      (Thread/sleep 2500)
      (let [b (connect-with id 1 false)]
        (try
          (is (false? (:session-present? (:connack b)))
              "one second was one second")
          (finally (tu/close! a b)))))))

(deftest ^:portable a-disconnect-may-change-the-expiry-on-the-way-out
  (testing "the DISCONNECT's interval overrides the CONNECT's"
    ;; §3.14.2.2.2. A client that decides on the way out that it will be back
    ;; can say so, without having planned for it when it connected.
    (let [id    (tu/client-id "expiry-extend")
          topic (tu/topic "expiry-extend")
          a     (connect-with id 1 true)]
      (subscribe! a topic)
      (disconnect! a 30)
      (Thread/sleep 2500)                       ; past the CONNECT's 1 second
      (let [b (connect-with id 30 false)]
        (try
          (is (true? (:session-present? (:connack b)))
              "the DISCONNECT asked for thirty seconds, not one")
          (finally (tu/close! a b))))))

  (testing "and zero ends it at once"
    (let [id    (tu/client-id "expiry-cut")
          topic (tu/topic "expiry-cut")
          a     (connect-with id 30 true)]
      (subscribe! a topic)
      (disconnect! a 0)
      (let [b (connect-with id 30 false)]
        (try
          (is (false? (:session-present? (:connack b)))
              "the client said it was not coming back")
          (finally (tu/close! a b)))))))

(deftest ^:portable reconnecting-cancels-a-pending-expiry
  (testing "a session resumed before it expires is not discarded behind you"
    ;; The expiry is scheduled when the connection ends; coming back has to
    ;; cancel it, or the session is torn out from under the live connection
    ;; when the timer fires.
    (let [id    (tu/client-id "expiry-cancel")
          topic (tu/topic "expiry-cancel")
          a     (connect-with id 1 true)]
      (subscribe! a topic)
      (disconnect! a)
      (let [b (connect-with id 30 false)]
        (try
          (is (true? (:session-present? (:connack b))))
          ;; Well past the original one second.
          (Thread/sleep 2500)
          (tu/send-v5! b {:packet-type :SUBSCRIBE :packet-identifier 2
                          :topics [{:qos 0 :topic-filter topic}]})
          (is (= :SUBACK (:packet-type (tu/expect! (:ch b) :SUBACK 3000)))
              "the connection is still alive and its session intact")
          (finally (tu/close! a b)))))))

(deftest ^:portable a-version-4-session-is-unchanged
  (testing "3.1.1 still decides on clean-session? alone"
    (let [id    (tu/client-id "v4-session")
          topic (tu/topic "v4-session")
          a     (tu/connect! nil :id id :clean-session? false :ordered? true)]
      (client/send-message (:client a)
                           {:packet-type :SUBSCRIBE :packet-identifier 1
                            :topics [{:qos 1 :topic-filter topic}]})
      (tu/expect! (:ch a) :SUBACK)
      (client/send-message (:client a) {:packet-type :DISCONNECT})
      (Thread/sleep 300)
      (let [b (tu/connect! nil :id id :clean-session? false :ordered? true)]
        (try
          (is (true? (:session-present? (:connack b)))
              "a persistent 3.1.1 session has no expiry and does not get one")
          (finally (tu/close! a b))))))

  (testing "and a clean 3.1.1 session still keeps nothing"
    (let [id (tu/client-id "v4-clean")
          a  (tu/connect! nil :id id :clean-session? true :ordered? true)]
      (client/send-message (:client a) {:packet-type :DISCONNECT})
      (Thread/sleep 300)
      (let [b (tu/connect! nil :id id :clean-session? false :ordered? true)]
        (try
          (is (false? (:session-present? (:connack b))))
          (finally (tu/close! a b)))))))
