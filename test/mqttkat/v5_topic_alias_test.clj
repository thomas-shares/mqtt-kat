(ns mqttkat.v5-topic-alias-test
  "MQTT 5.0 topic aliases, both directions (§3.3.2.3.4).

   An alias replaces a topic name with a two-byte integer for the rest of a
   connection — worth having when the same long topic is published repeatedly,
   which is most of what a broker carries.

   Two independent mappings, one per direction, each bounded by what the
   *receiver* said it would accept. The broker's Topic Alias Maximum in the
   CONNACK bounds what a client may send it; the client's, in the CONNECT,
   bounds what the broker may send back. Neither side may use an alias the
   other did not agree to.

   Written before the implementation. The broker accepted aliases inbound and
   never validated them, and never assigned any outbound."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [mqttkat.client :as client]
            [mqttkat.test-util :as tu]))

(use-fixtures :once tu/broker-fixture)

(defn- publish! [c topic text & [props]]
  (tu/send-v5! c (cond-> {:packet-type :PUBLISH :topic topic :qos 0
                          :payload (.getBytes ^String text "UTF-8")
                          :retain? false :duplicate? false}
                   props (assoc :properties props))))

(defn- disconnected-with!
  "Assert the broker closed the connection with `code`. Checks the packet
   arrived before reading its reason, so a broker that says nothing at all
   fails as a missing DISCONNECT rather than as a nil dereference."
  [c code]
  (let [msg (tu/take! (:ch c) 3000)]
    (is (= :DISCONNECT (:packet-type msg))
        (str "expected a DISCONNECT, got " (pr-str msg)))
    (when (= :DISCONNECT (:packet-type msg))
      (is (= code (bit-and (long (or (:reason-code msg) 0)) 0xff))))))

(defn- subscribe! [c topic]
  (tu/send-v5! c {:packet-type :SUBSCRIBE :packet-identifier 1
                  :topics [{:qos 0 :topic-filter topic}]})
  (tu/expect! (:ch c) :SUBACK))

;; ── what a client may send us ─────────────────────────────────────────

;; Not ^:portable: pins mqtt-kat's choice. 0x94 where 0x82 Protocol Error is as good; Mosquitto sends 0x82.
(deftest alias-zero-is-refused
  (testing "§3.3.2.3.4: a topic alias of 0 is a protocol error"
    ;; Zero is not a small alias, it is not an alias — and storing it as one
    ;; would give the client a mapping it can never legally use again.
    (let [c (tu/connect-v5! "alias-zero")]
      (try
        (publish! c (tu/topic "alias-zero") "no" {:topic-alias 0})
        (disconnected-with! c 0x94)
        (finally (tu/close! c))))))

(deftest ^:portable an-alias-above-what-the-broker-allows-is-refused
  (testing "the CONNACK's Topic Alias Maximum is a limit, not a suggestion"
    (let [c   (tu/connect-v5! "alias-high")
          max (:topic-alias-maximum (:properties (:connack c)))]
      (try
        (is (pos? max))
        (publish! c (tu/topic "alias-high") "no" {:topic-alias (inc (long max))})
        (disconnected-with! c 0x94)
        (finally (tu/close! c))))))

;; Not ^:portable: pins mqtt-kat's choice. 0x94 where 0x82 Protocol Error is as good; Mosquitto sends 0x82.
(deftest aliases-do-not-survive-the-connection
  (testing "a resumed session keeps its subscriptions and loses its aliases"
    ;; §3.3.2.3.4: the mapping belongs to the network connection, not to the
    ;; session. A resumed session that inherited its predecessor's aliases
    ;; would resolve them to topics the new connection never named.
    (let [id    (tu/client-id "alias-reset")
          topic (tu/topic "alias-reset")
          a     (tu/connect-v5! nil :id id :clean-session? false
                                :properties {:session-expiry-interval 60})]
      (subscribe! a topic)
      (publish! a topic "declared" {:topic-alias 1})
      (tu/expect! (:ch a) :PUBLISH 3000)
      (tu/send-v5! a {:packet-type :DISCONNECT})
      (Thread/sleep 300)
      (let [b (tu/connect-v5! nil :id id :clean-session? false
                              :properties {:session-expiry-interval 60})]
        (try
          (is (true? (:session-present? (:connack b))) "the session came back")
          ;; ...but alias 1 means nothing on this connection.
          (publish! b "" "orphan" {:topic-alias 1})
          (disconnected-with! b 0x94)
          (finally (tu/close! a b)))))))

;; ── what the broker may send a client ─────────────────────────────────

;; Not ^:portable: pins mqtt-kat's choice. assigning outbound aliases is optional; Mosquitto never does.
(deftest the-broker-assigns-an-alias-when-the-client-allows-one
  (testing "first delivery carries the topic and the alias, later ones just the alias"
    ;; §3.3.2.3.4. The saving only appears from the second message on, which is
    ;; why the first must carry both — a bare alias the client has never seen
    ;; is meaningless to it.
    (let [topic (tu/topic "srv-alias")
          sub   (tu/connect-v5! "srv-alias-sub" :properties {:topic-alias-maximum 1})
          pub   (tu/connect-v5! "srv-alias-pub")]
      (try
        (subscribe! sub topic)
        (publish! pub topic "one")
        (publish! pub topic "two")
        (publish! pub topic "three")
        (let [a (tu/expect! (:ch sub) :PUBLISH 3000)
              b (tu/expect! (:ch sub) :PUBLISH 3000)
              c (tu/expect! (:ch sub) :PUBLISH 3000)
              alias (:topic-alias (:properties a))]
          (is (= topic (:topic a)) "the first names the topic")
          (is (pos? (long (or alias 0))) "and assigns an alias")
          (is (= "" (:topic b)) "the second sends no topic name")
          (is (= alias (:topic-alias (:properties b))) "just the alias")
          (is (= "" (:topic c)))
          (is (= alias (:topic-alias (:properties c))))
          (is (= ["one" "two" "three"] (mapv tu/payload-str [a b c]))
              "and every payload still arrives"))
        (finally (tu/close! sub pub))))))

(deftest ^:portable a-client-that-allows-no-aliases-is-sent-none
  (testing "Topic Alias Maximum absent means zero (§3.1.2.11.5)"
    ;; The default, and what every subscriber gets unless it asks. It is also
    ;; what keeps the fan-out able to encode one buffer for many subscribers.
    (let [topic (tu/topic "no-srv-alias")
          sub   (tu/connect-v5! "no-alias-sub")
          pub   (tu/connect-v5! "no-alias-pub")]
      (try
        (subscribe! sub topic)
        (publish! pub topic "one")
        (publish! pub topic "two")
        (let [a (tu/expect! (:ch sub) :PUBLISH 3000)
              b (tu/expect! (:ch sub) :PUBLISH 3000)]
          (is (= topic (:topic a)))
          (is (= topic (:topic b)) "the topic is named every time")
          (is (nil? (:topic-alias (:properties a))))
          (is (nil? (:topic-alias (:properties b)))))
        (finally (tu/close! sub pub))))))

;; Not ^:portable: pins mqtt-kat's choice. assigning outbound aliases is optional; Mosquitto never does.
(deftest more-topics-than-aliases-still-all-arrive
  (testing "once the client's allowance is used up, topics are sent in full"
    ;; A broker that ran out of aliases and sent one anyway would be sending a
    ;; number the client cannot resolve.
    (let [prefix (tu/topic "alias-limit")
          sub    (tu/connect-v5! "limit-sub" :properties {:topic-alias-maximum 1})
          pub    (tu/connect-v5! "limit-pub")]
      (try
        (subscribe! sub (str prefix "/#"))
        (publish! pub (str prefix "/a") "first")
        (publish! pub (str prefix "/b") "second")
        (let [a (tu/expect! (:ch sub) :PUBLISH 3000)
              b (tu/expect! (:ch sub) :PUBLISH 3000)]
          (is (= (str prefix "/a") (:topic a)))
          (is (= 1 (some-> (:topic-alias (:properties a)) long)) "the one alias goes to the first topic")
          (is (= (str prefix "/b") (:topic b)) "the second is named in full")
          (is (nil? (:topic-alias (:properties b))) "with no alias, the allowance being spent"))
        (finally (tu/close! sub pub))))))

(deftest ^:portable a-version-4-subscriber-is-unaffected
  (testing "3.1.1 has no aliases and must never be sent one"
    (let [topic (tu/topic "v4-alias")
          sub   (tu/connect! "v4-alias-sub" :ordered? true)
          pub   (tu/connect-v5! "v4-alias-pub")]
      (try
        (client/send-message
         (:client sub) {:packet-type :SUBSCRIBE :packet-identifier 1
                        :topics [{:qos 0 :topic-filter topic}]})
        (tu/expect! (:ch sub) :SUBACK)
        (publish! pub topic "one")
        (publish! pub topic "two")
        (let [a (tu/expect! (:ch sub) :PUBLISH 3000)
              b (tu/expect! (:ch sub) :PUBLISH 3000)]
          (is (= topic (:topic a)))
          (is (= topic (:topic b)))
          (is (not (contains? a :properties))))
        (finally (tu/close! sub pub))))))
