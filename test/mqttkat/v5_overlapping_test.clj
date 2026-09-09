(ns mqttkat.v5-overlapping-test
  "Overlapping subscriptions, and the identifiers that come back on them.

   §3.3.4 lets a server answer a publish matching several of one client's
   subscriptions either way: one copy per subscription, each carrying its own
   Subscription Identifier, or a single copy carrying all of them. This broker
   sends one copy — fewer packets, and it is the shape the identifier feature
   exists to make useful."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [mqttkat.test-util :as tu]))

(use-fixtures :once tu/broker-fixture)

(defn- subscribe! [c filter id qos]
  (tu/send-v5! c (cond-> {:packet-type :SUBSCRIBE :packet-identifier (long (rand-int 30000))
                          :topics [{:qos qos :topic-filter filter}]}
                   id (assoc :properties {:subscription-identifiers [id]})))
  (tu/expect! (:ch c) :SUBACK))

(defn- publish! [c topic text qos]
  (tu/send-v5! c (cond-> {:packet-type :PUBLISH :topic topic :qos qos
                          :payload (.getBytes ^String text "UTF-8")
                          :retain? false :duplicate? false}
                   (pos? qos) (assoc :packet-identifier 1))))

(deftest two-matching-subscriptions-deliver-once
  (testing "§3.3.4: one copy carrying every matching Subscription Identifier"
    ;; `a/#` matches `a` as well as `a/b` (§4.7.1.2), so these two filters both
    ;; match the parent topic. The broker used to send one delivery per
    ;; matching subscription, which is legal but doubles the traffic and makes
    ;; the identifiers useless for telling *why* a message arrived.
    (let [topic (tu/topic "overlap")
          sub   (tu/connect-v5! "overlap-sub")
          pub   (tu/connect-v5! "overlap-pub")]
      (try
        (subscribe! sub topic 2 2)
        (subscribe! sub (str topic "/#") 3 2)
        (publish! pub topic "once" 1)
        (let [m (tu/expect-eventually! (:ch sub) :PUBLISH 3000)]
          (is (= "once" (tu/payload-str m)))
          (is (= #{2 3} (set (map long (:subscription-identifiers (:properties m)))))
              "both identifiers on the one delivery")
          (tu/send-v5! sub {:packet-type :PUBACK
                            :packet-identifier (:packet-identifier m)}))
        ;; And nothing else turns up behind it.
        (is (nil? (tu/take! (:ch sub) 700)) "exactly one delivery")
        (finally (tu/close! sub pub))))))

(deftest the-delivery-takes-the-highest-matching-qos
  (testing "§3.3.5-1: the maximum QoS of the matching subscriptions"
    ;; One copy has to pick a QoS, and dropping to the lower of the two would
    ;; quietly downgrade a subscription the client asked for at QoS 1.
    (let [topic (tu/topic "overlap-qos")
          sub   (tu/connect-v5! "overlap-qos-sub")
          pub   (tu/connect-v5! "overlap-qos-pub")]
      (try
        (subscribe! sub topic nil 0)
        (subscribe! sub (str topic "/#") nil 1)
        (publish! pub topic "hi" 1)
        (let [m (tu/expect-eventually! (:ch sub) :PUBLISH 3000)]
          (is (= 1 (long (:qos m))) "delivered at the higher of the two")
          (tu/send-v5! sub {:packet-type :PUBACK
                            :packet-identifier (:packet-identifier m)}))
        (is (nil? (tu/take! (:ch sub) 700)) "exactly one delivery")
        (finally (tu/close! sub pub))))))

(deftest one-subscription-still-carries-its-own-identifier
  (testing "the ordinary case is unchanged"
    (let [topic (tu/topic "single-id")
          sub   (tu/connect-v5! "single-id-sub")
          pub   (tu/connect-v5! "single-id-pub")]
      (try
        (subscribe! sub topic 456789 2)
        (publish! pub topic "solo" 0)
        (let [m (tu/expect-eventually! (:ch sub) :PUBLISH 3000)]
          (is (= [456789] (mapv long (:subscription-identifiers (:properties m))))))
        (finally (tu/close! sub pub))))))

(deftest a-subscription-without-an-identifier-adds-none
  (testing "a filter subscribed without one contributes nothing to the list"
    ;; §3.3.4: only subscriptions that *have* an identifier put one on the
    ;; delivery. A client mixing the two must not see a phantom.
    (let [topic (tu/topic "mixed-id")
          sub   (tu/connect-v5! "mixed-id-sub")
          pub   (tu/connect-v5! "mixed-id-pub")]
      (try
        (subscribe! sub topic 7 0)
        (subscribe! sub (str topic "/#") nil 0)
        (publish! pub topic "mixed" 0)
        (let [m (tu/expect-eventually! (:ch sub) :PUBLISH 3000)]
          (is (= [7] (mapv long (:subscription-identifiers (:properties m))))))
        (is (nil? (tu/take! (:ch sub) 700)) "exactly one delivery")
        (finally (tu/close! sub pub))))))

(deftest a-shared-and-an-ordinary-subscription-still-deliver-twice
  (testing "they are independent subscriptions, not overlapping ones (§4.8.2)"
    ;; Deliberately not coalesced: a client subscribed both ways has asked for
    ;; the message once as itself and once as a member of the group.
    (let [topic (tu/topic "shared-plus")
          sub   (tu/connect-v5! "shared-plus-sub")
          pub   (tu/connect-v5! "shared-plus-pub")]
      (try
        (subscribe! sub topic nil 0)
        (subscribe! sub (str "$share/g/" topic) nil 0)
        (publish! pub topic "twice" 0)
        (let [a (tu/expect-eventually! (:ch sub) :PUBLISH 3000)
              b (tu/expect-eventually! (:ch sub) :PUBLISH 3000)]
          (is (= "twice" (tu/payload-str a)))
          (is (= "twice" (tu/payload-str b))))
        (finally (tu/close! sub pub))))))
