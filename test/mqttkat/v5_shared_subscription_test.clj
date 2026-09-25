(ns mqttkat.v5-shared-subscription-test
  "MQTT 5.0 shared subscriptions (§4.8.2).

   `$share/{group}/{filter}` puts several clients behind one subscription: a
   message matching the filter goes to exactly one member of the group, not to
   all of them. It is how you put a queue of workers behind a topic without
   each of them seeing every message.

   Written before the implementation, and checked against the Paho
   interoperability suite's own expectations: it groups by the whole
   `$share/name/filter` string and asserts that publishing once produces
   exactly one delivery across the group."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [mqttkat.test-util :as tu]))

(use-fixtures :once tu/broker-fixture)

(defn- subscribe! [c filter]
  (tu/send-v5! c {:packet-type :SUBSCRIBE :packet-identifier 1
                  :topics [{:qos 0 :topic-filter filter}]})
  (tu/expect! (:ch c) :SUBACK))

(defn- publish! [c topic text]
  (tu/send-v5! c {:packet-type :PUBLISH :topic topic :qos 0
                  :payload (.getBytes ^String text "UTF-8")
                  :retain? false :duplicate? false}))

(defn- delivered
  "How many PUBLISHes reached a client within a quiet window."
  [ch]
  (loop [n 0]
    (if-let [msg (tu/take! ch 600)]
      (recur (if (= :PUBLISH (:packet-type msg)) (inc n) n))
      n)))

(deftest ^:portable a-message-reaches-exactly-one-member-of-a-group
  (testing "two clients sharing one subscription split the traffic"
    ;; §4.8.2. This is the whole feature: without it both would get every
    ;; message, which is an ordinary subscription.
    (let [topic  (tu/topic "shared-basic")
          shared (str "$share/workers/" topic)
          a      (tu/connect-v5! "sh-a")
          b      (tu/connect-v5! "sh-b")
          pub    (tu/connect-v5! "sh-pub")]
      (try
        (subscribe! a shared)
        (subscribe! b shared)
        (publish! pub topic "one")
        (let [total (+ (delivered (:ch a)) (delivered (:ch b)))]
          (is (= 1 total) "one message, one delivery"))
        (finally (tu/close! a b pub)))))

  (testing "and several messages are spread across the group"
    ;; Not a fairness assertion — the specification leaves the choice to the
    ;; implementation — but every message must be delivered exactly once, so
    ;; the total is what is pinned.
    (let [topic  (tu/topic "shared-spread")
          shared (str "$share/workers/" topic)
          a      (tu/connect-v5! "sp-a")
          b      (tu/connect-v5! "sp-b")
          pub    (tu/connect-v5! "sp-pub")]
      (try
        (subscribe! a shared)
        (subscribe! b shared)
        (dotimes [i 6] (publish! pub topic (str "m" i)))
        (let [got-a (delivered (:ch a))
              got-b (delivered (:ch b))]
          (is (= 6 (+ got-a got-b)) "every message delivered exactly once")
          (is (pos? got-a) "and both members were used")
          (is (pos? got-b)))
        (finally (tu/close! a b pub))))))

(deftest ^:portable different-groups-each-get-their-own-copy
  (testing "two groups on the same filter are two subscriptions"
    ;; The group name is part of the identity, so a message goes once to each
    ;; group — that is how two independent worker pools consume one topic.
    (let [topic (tu/topic "shared-groups")
          a     (tu/connect-v5! "g1")
          b     (tu/connect-v5! "g2")
          pub   (tu/connect-v5! "g-pub")]
      (try
        (subscribe! a (str "$share/groupone/" topic))
        (subscribe! b (str "$share/grouptwo/" topic))
        (publish! pub topic "each")
        (is (= 1 (delivered (:ch a))))
        (is (= 1 (delivered (:ch b))))
        (finally (tu/close! a b pub))))))

(deftest ^:portable a-shared-and-an-ordinary-subscription-both-fire
  (testing "a client subscribed both ways gets the message twice"
    ;; What the Paho suite asserts: the two subscriptions are independent, and
    ;; the shared one does not suppress the plain one.
    (let [topic (tu/topic "shared-and-plain")
          c     (tu/connect-v5! "both")
          pub   (tu/connect-v5! "both-pub")]
      (try
        (subscribe! c topic)
        (tu/send-v5! c {:packet-type :SUBSCRIBE :packet-identifier 2
                        :topics [{:qos 0 :topic-filter (str "$share/w/" topic)}]})
        (tu/expect! (:ch c) :SUBACK)
        (publish! pub topic "twice")
        (is (= 2 (delivered (:ch c)))
            "once for the ordinary subscription, once for the shared one")
        (finally (tu/close! c pub))))))

(deftest ^:portable a-wildcard-inside-a-shared-filter-still-matches
  (testing "the filter after the group name is an ordinary topic filter"
    (let [prefix (tu/topic "shared-wild")
          a      (tu/connect-v5! "wild-a")
          pub    (tu/connect-v5! "wild-pub")]
      (try
        (subscribe! a (str "$share/w/" prefix "/#"))
        (publish! pub (str prefix "/deep/leaf") "matched")
        (is (= 1 (delivered (:ch a))))
        (finally (tu/close! a pub))))))

(deftest ^:portable leaving-a-group-stops-delivery-to-that-client
  (testing "unsubscribing uses the filter the client sent, $share and all"
    (let [topic  (tu/topic "shared-unsub")
          shared (str "$share/workers/" topic)
          a      (tu/connect-v5! "un-a")
          pub    (tu/connect-v5! "un-pub")]
      (try
        (subscribe! a shared)
        (publish! pub topic "before")
        (is (= 1 (delivered (:ch a))))
        (tu/send-v5! a {:packet-type :UNSUBSCRIBE :packet-identifier 3
                        :topics [shared]})
        (let [ack (tu/expect! (:ch a) :UNSUBACK 3000)]
          (is (= [0x00] (vec (:response ack))) "the subscription was there to remove"))
        (publish! pub topic "after")
        (is (= 0 (delivered (:ch a))))
        (finally (tu/close! a pub))))))

(deftest ^:portable no-local-on-a-shared-subscription-is-a-protocol-error
  (testing "§3.8.3.1 forbids it outright"
    ;; No Local asks not to be sent your own messages; on a shared
    ;; subscription there is no single publisher it could mean, so the
    ;; specification makes setting it an error rather than defining a
    ;; behaviour for it.
    (let [c (tu/connect-v5! "nl-shared")]
      (try
        (tu/send-v5! c {:packet-type :SUBSCRIBE :packet-identifier 1
                        :topics [{:qos 0 :no-local? true
                                  :topic-filter (str "$share/w/" (tu/topic "nl"))}]})
        (let [reply (tu/take! (:ch c) 2000)]
          (is (= :DISCONNECT (:packet-type reply)))
          (is (= 0x82 (bit-and (long (:reason-code reply)) 0xff))
              "protocol error"))
        (finally (tu/close! c))))))

(deftest ^:portable a-malformed-share-filter-is-refused
  (testing "the group name may not be empty or contain wildcards or a slash"
    ;; §4.8.2. Reported per filter on the SUBACK rather than by closing the
    ;; connection: the client asked for something impossible, but the rest of
    ;; the packet may be fine.
    (doseq [bad ["$share/" "$share//topic" "$share/a+b/topic" "$share/a#b/topic"
                 "$share/group"]]
      (let [c (tu/connect-v5! "bad-share")]
        (try
          (tu/send-v5! c {:packet-type :SUBSCRIBE :packet-identifier 1
                          :topics [{:qos 0 :topic-filter bad}]})
          (let [ack (tu/expect! (:ch c) :SUBACK 3000)]
            (is (= [0x8F] (vec (:response ack)))
                (str bad " should be refused as an invalid topic filter")))
          (finally (tu/close! c)))))))

(deftest ^:portable the-broker-now-advertises-shared-subscriptions
  (testing "the CONNACK no longer denies them"
    (let [c (tu/connect-v5! "shared-advert")]
      (try
        (is (true? (:shared-subscription-available (:properties (:connack c)))))
        (finally (tu/close! c))))))
