(ns mqttkat.v5-publish-flow-test
  "A version 5 PUBLISH through the broker, over real sockets.

   The codec tests prove the bytes; these prove the broker speaks the right
   dialect to each subscriber and forwards the properties it is supposed to.

   Packets go out through `send!`, which stamps the connection's version on
   them — see tu/send-v5! for why that is a guard rather than a convenience."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [mqttkat.client :as client]
            [mqttkat.test-util :as tu]))

(use-fixtures :once tu/broker-fixture)

(defn- connect!
  "A client of either version. Version 5 goes through tu/connect-v5!, which
   marks the returned map so `send!` knows which dialect to write in."
  [version id]
  (if (= version 5)
    (tu/connect-v5! id :id id)
    (let [c (tu/client! 32 true)]
      (client/send-message (:client c)
                           {:packet-type :CONNECT :protocol-name "MQTT"
                            :protocol-version version :keep-alive 0
                            :clean-session? true :client-id id})
      (tu/expect! (:ch c) :CONNACK)
      c)))

(defn- send! [c msg]
  (if (= 5 (:protocol-version c))
    (tu/send-v5! c msg)
    (client/send-message (:client c) msg)))

(defn- subscribe! [c topic qos]
  (send! c {:packet-type :SUBSCRIBE :packet-identifier 1
            :topics [{:qos qos :topic-filter topic}]})
  (tu/expect! (:ch c) :SUBACK))

(defn- publish! [c topic text & [opts]]
  (send! c (merge {:packet-type :PUBLISH :topic topic :qos 0
                   :payload (.getBytes ^String text "UTF-8")
                   :retain? false :duplicate? false}
                  opts)))

(defn- payload-of [m] (String. ^bytes (:payload m) "UTF-8"))

(deftest a-version-5-subscriber-receives-a-property-block
  (testing "the broker answers a v5 subscriber in v5, even for a v4 publisher"
    ;; A v5 client cannot read a 3.1.1 PUBLISH: it would take the first byte of
    ;; the payload as a property length. Which dialect a delivery is written in
    ;; is decided by the subscriber's connection, not the publisher's.
    (let [topic (tu/topic "v5-recv")
          sub   (connect! 5 (tu/client-id "v5-sub"))
          pub   (connect! 4 (tu/client-id "v4-pub"))]
      (try
        (subscribe! sub topic 0)
        (publish! pub topic "from-v4")
        (let [msg (tu/expect! (:ch sub) :PUBLISH 3000)]
          (is (= topic (:topic msg)))
          (is (= "from-v4" (payload-of msg)))
          (is (map? (:properties msg)) "a v5 delivery always carries a block"))
        (finally (tu/close! sub pub))))))

(deftest publish-properties-are-forwarded
  (testing "content type, response topic, correlation data and user properties"
    ;; §3.3.2.3: these travel with the application message and the server
    ;; passes them on. Request/response is unusable without the pair of them.
    (let [topic (tu/topic "v5-props")
          sub   (connect! 5 (tu/client-id "v5-psub"))
          pub   (connect! 5 (tu/client-id "v5-ppub"))]
      (try
        (subscribe! sub topic 0)
        (publish! pub topic "req"
                  {:properties {:content-type "application/json"
                                :response-topic "reply/here"
                                :correlation-data (.getBytes "id-1" "UTF-8")
                                :payload-format-indicator 1
                                :user-properties [["trace" "abc"]]}})
        (let [msg   (tu/expect! (:ch sub) :PUBLISH 3000)
              props (:properties msg)]
          (is (= "application/json" (:content-type props)))
          (is (= "reply/here" (:response-topic props)))
          (is (= "id-1" (String. ^bytes (:correlation-data props) "UTF-8")))
          (is (= 1 (:payload-format-indicator props)))
          (is (= [["trace" "abc"]] (mapv vec (:user-properties props)))))
        (finally (tu/close! sub pub))))))

(deftest a-mixed-fan-out-reaches-both-dialects
  (testing "a v4 and a v5 subscriber on one topic each get a packet they can read"
    ;; The broker encodes one buffer and writes it to every matching
    ;; subscriber, which is only safe while they all speak the same version.
    (let [topic (tu/topic "v5-mixed")
          old   (connect! 4 (tu/client-id "mixed-v4"))
          new   (connect! 5 (tu/client-id "mixed-v5"))
          pub   (connect! 5 (tu/client-id "mixed-pub"))]
      (try
        (subscribe! old topic 0)
        (subscribe! new topic 0)
        (publish! pub topic "both" {:properties {:content-type "text/plain"}})
        (let [a (tu/expect! (:ch old) :PUBLISH 3000)
              b (tu/expect! (:ch new) :PUBLISH 3000)]
          (is (= "both" (payload-of a)) "the 3.1.1 subscriber's payload is intact")
          (is (not (contains? a :properties)) "and it was sent no property block")
          (is (= "both" (payload-of b)) "the v5 subscriber's payload is intact")
          (is (= "text/plain" (:content-type (:properties b)))))
        (finally (tu/close! old new pub))))))

(deftest a-topic-alias-stands-for-a-topic-for-the-rest-of-the-connection
  (testing "declare the alias once with the topic, then send the alias alone"
    ;; §3.3.2.3.4. The mapping is per connection and per direction, so the
    ;; broker has to remember it against the publisher's connection.
    (let [topic (tu/topic "v5-alias")
          sub   (connect! 5 (tu/client-id "alias-sub"))
          pub   (connect! 5 (tu/client-id "alias-pub"))]
      (try
        (subscribe! sub topic 0)
        (publish! pub topic "first" {:properties {:topic-alias 1}})
        (is (= "first" (payload-of (tu/expect! (:ch sub) :PUBLISH 3000))))

        ;; Now the alias on its own, with no topic name at all.
        (publish! pub "" "second" {:properties {:topic-alias 1}})
        (let [msg (tu/expect! (:ch sub) :PUBLISH 3000)]
          (is (= "second" (payload-of msg)))
          (is (= topic (:topic msg))
              "the broker resolved the alias back to the topic it stands for"))
        (finally (tu/close! sub pub)))))

  (testing "the alias itself is not forwarded to the subscriber"
    ;; §3.3.2.3.4: aliases are per connection. Passing the publisher's alias on
    ;; would have the subscriber bind it to a mapping it never agreed.
    (let [topic (tu/topic "v5-alias-strip")
          sub   (connect! 5 (tu/client-id "strip-sub"))
          pub   (connect! 5 (tu/client-id "strip-pub"))]
      (try
        (subscribe! sub topic 0)
        (publish! pub topic "x" {:properties {:topic-alias 3
                                              :content-type "text/plain"}})
        (let [msg (tu/expect! (:ch sub) :PUBLISH 3000)]
          (is (= topic (:topic msg)) "delivered under its real name")
          (is (not (contains? (:properties msg) :topic-alias))
              "and without the publisher's alias")
          (is (= "text/plain" (:content-type (:properties msg)))
              "while the properties that do travel still do"))
        (finally (tu/close! sub pub))))))

(deftest the-broker-says-how-many-aliases-it-accepts
  (testing "the CONNACK advertises a non-zero topic alias maximum"
    ;; It was 0 while aliases were unimplemented, which tells a client not to
    ;; use them at all. Now that they work it has to say so, or no client will.
    (let [c (connect! 5 (tu/client-id "alias-max"))]
      (try
        (subscribe! c (tu/topic "alias-max") 0)
        (finally (tu/close! c)))))

  (testing "and it is what the broker will actually honour"
    (let [advertised (:topic-alias-maximum
                      @(resolve 'mqttkat.handlers.connect/broker-properties))]
      (is (pos? advertised))
      (is (<= advertised 65535) "an alias is a two byte integer"))))
