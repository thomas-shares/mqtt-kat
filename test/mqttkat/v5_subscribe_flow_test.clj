(ns mqttkat.v5-subscribe-flow-test
  "The MQTT 5 subscription options doing something, over real sockets.

   The codec tests prove the bits are read; these prove the broker acts on
   them. Every one of these flags is a promise a client can act on, and a
   broker that parses them and then ignores them is worse than one that
   refuses them — the client has no way to tell."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [mqttkat.client :as client]
            [mqttkat.test-util :as tu]))

(use-fixtures :once tu/broker-fixture)

(defn- connect! [version id]
  (tu/connect-v5! id :id id))

(defn- subscribe! [c topic opts]
  ;; tu/send-v5! stamps the version, so no call site here can forget it.
  (tu/send-v5! c {:packet-type :SUBSCRIBE :packet-identifier 1
                  :topics [(merge {:qos 0 :topic-filter topic} opts)]})
  (tu/expect! (:ch c) :SUBACK))

(defn- publish! [c topic text & [opts]]
  (tu/send-v5! c (merge {:packet-type :PUBLISH :topic topic :qos 0
                         :payload (.getBytes ^String text "UTF-8")
                         :retain? false :duplicate? false}
                        opts)))

(defn- payload-of [m] (String. ^bytes (:payload m) "UTF-8"))

(deftest ^:portable a-version-5-subscriber-gets-a-version-5-suback
  (testing "reason codes and a property block"
    (let [c (connect! 5 (tu/client-id "sub-ack"))]
      (try
        (let [ack (subscribe! c (tu/topic "suback") {:qos 2})]
          (is (= [2] (vec (:response ack))) "granted QoS 2")
          (is (map? (:properties ack)) "a v5 SUBACK always carries a block"))
        (finally (tu/close! c))))))

(deftest ^:portable no-local-keeps-a-client-from-hearing-itself
  (testing "a subscriber that publishes to its own topic is not sent it back"
    ;; §3.8.3.1. Without this a client bridging two topics feeds itself.
    (let [topic (tu/topic "nl")
          me    (connect! 5 (tu/client-id "nl-self"))
          other (connect! 5 (tu/client-id "nl-other"))]
      (try
        (subscribe! me topic {:no-local? true})
        (subscribe! other topic {})
        (publish! me topic "mine")
        (is (= "mine" (payload-of (tu/expect! (:ch other) :PUBLISH 3000)))
            "the other subscriber still gets it")
        (is (nil? (tu/take! (:ch me) 700))
            "but the publisher does not hear its own message")
        (finally (tu/close! me other)))))

  (testing "and without the flag it does hear itself"
    ;; The default, and 3.1.1's only behaviour.
    (let [topic (tu/topic "nl-off")
          me    (connect! 5 (tu/client-id "nl-off-self"))]
      (try
        (subscribe! me topic {})
        (publish! me topic "echo")
        (is (= "echo" (payload-of (tu/expect! (:ch me) :PUBLISH 3000))))
        (finally (tu/close! me))))))

(deftest ^:portable retain-as-published-keeps-the-publishers-flag
  (testing "with the flag, a retained publish arrives still marked retained"
    ;; §3.8.3.1. A bridge needs this: forwarding a retained message that has
    ;; lost its flag turns it into an ordinary one on the far side.
    (let [topic (tu/topic "rap")
          sub   (connect! 5 (tu/client-id "rap-sub"))
          pub   (connect! 5 (tu/client-id "rap-pub"))]
      (try
        (subscribe! sub topic {:retain-as-published? true})
        (publish! pub topic "kept" {:retain? true})
        (is (true? (:retain? (tu/expect! (:ch sub) :PUBLISH 3000))))
        (finally (tu/close! sub pub)))))

  (testing "without it the flag is cleared, as 3.1.1 always did"
    ;; §3.3.1.3: a message forwarded to an existing subscriber has RETAIN 0,
    ;; so the subscriber can tell a live message from a replayed one.
    (let [topic (tu/topic "rap-off")
          sub   (connect! 5 (tu/client-id "rap-off-sub"))
          pub   (connect! 5 (tu/client-id "rap-off-pub"))]
      (try
        (subscribe! sub topic {})
        (publish! pub topic "cleared" {:retain? true})
        (is (false? (:retain? (tu/expect! (:ch sub) :PUBLISH 3000))))
        (finally (tu/close! sub pub))))))

(deftest ^:portable retain-handling-decides-whether-the-backlog-is-replayed
  (testing "0 sends what is retained, which is the 3.1.1 behaviour"
    (let [topic (tu/topic "rh0")
          pub   (connect! 5 (tu/client-id "rh0-pub"))]
      (publish! pub topic "stored" {:retain? true})
      (Thread/sleep 300)
      (let [sub (connect! 5 (tu/client-id "rh0-sub"))]
        (try
          (subscribe! sub topic {:retain-handling 0})
          (is (= "stored" (payload-of (tu/expect! (:ch sub) :PUBLISH 3000))))
          (finally (tu/close! sub pub))))))

  (testing "2 sends nothing at all"
    ;; For a client that wants live traffic only and would otherwise have to
    ;; recognise and discard the backlog itself.
    (let [topic (tu/topic "rh2")
          pub   (connect! 5 (tu/client-id "rh2-pub"))]
      (publish! pub topic "stored" {:retain? true})
      (Thread/sleep 300)
      (let [sub (connect! 5 (tu/client-id "rh2-sub"))]
        (try
          (subscribe! sub topic {:retain-handling 2})
          (is (nil? (tu/take! (:ch sub) 700)) "no retained replay")
          ;; but live traffic still arrives
          (publish! pub topic "live")
          (is (= "live" (payload-of (tu/expect! (:ch sub) :PUBLISH 3000))))
          (finally (tu/close! sub pub))))))

  (testing "1 sends only when the subscription is new"
    ;; §3.8.3.1: re-subscribing to something you already have should not
    ;; replay the backlog a second time.
    (let [topic (tu/topic "rh1")
          pub   (connect! 5 (tu/client-id "rh1-pub"))]
      (publish! pub topic "stored" {:retain? true})
      (Thread/sleep 300)
      (let [sub (connect! 5 (tu/client-id "rh1-sub"))]
        (try
          (subscribe! sub topic {:retain-handling 1})
          (is (= "stored" (payload-of (tu/expect! (:ch sub) :PUBLISH 3000)))
              "first time: the subscription is new, so the backlog comes")
          (subscribe! sub topic {:retain-handling 1})
          (is (nil? (tu/take! (:ch sub) 700))
              "second time: the subscription already existed, so it does not")
          (finally (tu/close! sub pub)))))))

(deftest ^:portable a-subscription-identifier-comes-back-with-the-message
  (testing "the identifier the client chose is on every matching delivery"
    ;; §3.3.4. It is how a client with many subscriptions knows which one a
    ;; message arrived for, without matching the topic against its filters.
    (let [topic (tu/topic "subid")
          sub   (connect! 5 (tu/client-id "subid-sub"))
          pub   (connect! 5 (tu/client-id "subid-pub"))]
      (try
        (tu/send-v5! sub {:packet-type :SUBSCRIBE :packet-identifier 1
                          :properties {:subscription-identifiers [77]}
                          :topics [{:qos 0 :topic-filter topic}]})
        (tu/expect! (:ch sub) :SUBACK)
        (publish! pub topic "tagged")
        (let [msg (tu/expect! (:ch sub) :PUBLISH 3000)]
          (is (= "tagged" (payload-of msg)))
          (is (= [77] (vec (:subscription-identifiers (:properties msg))))))
        (finally (tu/close! sub pub)))))

  (testing "a subscription without one gets no identifier on its deliveries"
    (let [topic (tu/topic "subid-none")
          sub   (connect! 5 (tu/client-id "subid-none-sub"))
          pub   (connect! 5 (tu/client-id "subid-none-pub"))]
      (try
        (subscribe! sub topic {})
        (publish! pub topic "plain")
        (let [msg (tu/expect! (:ch sub) :PUBLISH 3000)]
          (is (not (contains? (:properties msg) :subscription-identifiers))))
        (finally (tu/close! sub pub))))))

(deftest ^:portable the-broker-now-says-identifiers-are-available
  (testing "the CONNACK no longer denies them"
    (is (true? (:subscription-identifier-available
                @(resolve 'mqttkat.handlers.connect/broker-properties))))))

(deftest ^:portable unsubscribing-a-version-5-subscription-still-works
  (testing "the options stored with a subscription do not break its removal"
    ;; The trie matches on the whole stored value, so a subscription carrying
    ;; the new option fields is deleted by a different key than a 3.1.1 one —
    ;; the removal has to use what was stored, not rebuild it from the filter.
    (let [topic (tu/topic "v5-unsub")
          sub   (connect! 5 (tu/client-id "unsub-sub"))
          pub   (connect! 5 (tu/client-id "unsub-pub"))]
      (try
        (subscribe! sub topic {:no-local? true :retain-handling 2})
        (tu/send-v5! sub {:packet-type :UNSUBSCRIBE :packet-identifier 2
                          :topics [topic]})
        (tu/expect! (:ch sub) :UNSUBACK)
        (publish! pub topic "after")
        (is (nil? (tu/take! (:ch sub) 700))
            "nothing arrives once the subscription is gone")
        (finally (tu/close! sub pub))))))
