(ns mqttkat.flow-test
  "End-to-end message flows against a broker this suite starts itself.

   Every test uses its own client ids and its own topics: sessions, retained
   messages and subscriptions all outlive the test that created them, so tests
   that share names depend on the order clojure.test happens to run them in."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [mqttkat.client :as client]
            [mqttkat.test-util :as tu]))

;; lein auto test :only mqttkat.flow-test

(use-fixtures :once tu/broker-fixture)

(defn- subscribe-msg [topic qos id]
  {:packet-type :SUBSCRIBE :packet-identifier id
   :topics [{:qos qos :topic-filter topic}]})

(defn- publish-msg [topic payload & {:keys [qos retain? id]
                                     :or   {qos 0 retain? false}}]
  (cond-> {:packet-type :PUBLISH :qos qos :topic topic
           :retain? retain? :duplicate false :payload payload}
    id (assoc :packet-identifier id)))

(deftest connect-test
  (let [c (tu/connect! "connect")]
    (is (= 0 (:connect-return-code (:connack c))))
    (tu/close! c)))

(deftest zero-length-client-id-clean-session-true
  (testing "an empty client id is allowed when the session is clean"
    (let [c (tu/connect! nil :id "" :clean-session? true)]
      (is (= 0x00 (:connect-return-code (:connack c))))
      (tu/close! c))))

(deftest zero-length-client-id-clean-session-false
  (testing "an empty client id with a persistent session is rejected"
    (let [{:keys [ch] :as c} (tu/client!)]
      (client/send-message (:client c) {:packet-type :CONNECT :protocol-name "MQTT"
                                        :protocol-version 4 :keep-alive 100
                                        :clean-session? false :client-id ""})
      (is (= 0x02 (:connect-return-code (tu/expect! ch :CONNACK))))
      (tu/close! c))))

(deftest retain-test
  (testing "a retained message is delivered to a later subscriber"
    (let [topic   (tu/topic "retain")
          payload "this is a retained message"
          {:keys [client ch] :as c} (tu/connect! "retain")]
      (client/send-message client (publish-msg topic payload :retain? true))
      (tu/wait-for-retained! topic payload)
      (client/send-message client (subscribe-msg (tu/wildcard topic) 0 1))
      (let [msg (tu/expect-eventually! ch :PUBLISH)]
        (is (true? (:retain? msg)))
        (is (zero? (:qos msg)))
        (is (= topic (:topic msg)))
        (is (= payload (tu/payload-str msg))))
      (tu/close! c))))

(deftest update-retain-test
  (testing "only the newest retained message on a topic is kept"
    (let [topic (tu/topic "update-retain")
          {:keys [client ch] :as c} (tu/connect! "update-retain")]
      (client/send-message client (publish-msg topic "retained message one" :retain? true))
      (tu/wait-for-retained! topic "retained message one")
      (client/send-message client (publish-msg topic "retained message two" :retain? true))
      (tu/wait-for-retained! topic "retained message two")
      (client/send-message client (subscribe-msg (tu/wildcard topic) 0 1))
      (let [msg (tu/expect-eventually! ch :PUBLISH)]
        (is (= "retained message two" (tu/payload-str msg))))
      (tu/close! c))))

(deftest last-will-test
  (testing "the will is published when a client drops without DISCONNECT"
    (let [topic (tu/topic "will")
          will  "will message"
          a (tu/connect! "will-client" :will {:will-retain false :will-topic topic
                                              :will-message will :will-qos 0})
          b (tu/connect! "will-sub")]
      (client/send-message (:client b) (subscribe-msg topic 0 1))
      (tu/expect! (:ch b) :SUBACK)
      (tu/close! a)
      (let [msg (tu/expect-eventually! (:ch b) :PUBLISH)]
        (is (= topic (:topic msg)))
        (is (= will (tu/payload-str msg)))
        (is (= 0 (:qos msg))))
      (tu/close! b))))

(deftest last-will-test-and-retain
  (testing "a retained will reaches a subscriber that arrives after the client died"
    (let [topic (tu/topic "will-retain")
          will  "will message"
          a (tu/connect! "will-client" :will {:will-retain true :will-topic topic
                                              :will-message will :will-qos 0})]
      (Thread/sleep 50)
      (tu/close! a)
      (let [b (tu/connect! "will-sub")]
        (client/send-message (:client b) (subscribe-msg topic 0 1))
        (let [msg (tu/expect-eventually! (:ch b) :PUBLISH)]
          (is (= topic (:topic msg)))
          (is (true? (:retain? msg)))
          (is (= will (tu/payload-str msg)))
          (is (= 0 (:qos msg))))
        (tu/close! b)))))

(deftest session-test
  (testing "reconnecting with the same id resumes the session"
    (let [id (tu/client-id "session")
          a  (tu/connect! nil :id id :clean-session? false)]
      (is (= 0x00 (:connect-return-code (:connack a))))
      (tu/close! a)
      (tu/wait-for-parked-session! id)
      (let [b (tu/connect! nil :id id :clean-session? false)]
        (is (= 0x00 (:connect-return-code (:connack b))))
        (is (true? (:session-present? (:connack b))))
        (tu/close! b)))))

(deftest sub-unsub-test
  (testing "a subscription delivers, and stops delivering once unsubscribed"
    (let [topic (tu/topic "sub-unsub")
          {:keys [client ch] :as c} (tu/connect! "sub-unsub")]
      (client/send-message client (subscribe-msg topic 0 123))
      (is (= [0] (:response (tu/expect! ch :SUBACK))))

      (client/send-message client (publish-msg topic "this is a message"))
      (let [msg (tu/expect-eventually! ch :PUBLISH)]
        (is (= 0 (:qos msg)))
        (is (= topic (:topic msg)))
        (is (= "this is a message" (tu/payload-str msg))))

      (client/send-message client {:packet-type :UNSUBSCRIBE :topics [topic] :packet-identifier 124})
      (let [msg (tu/expect! ch :UNSUBACK)]
        (is (= 124 (:packet-identifier msg))))

      (client/send-message client (publish-msg topic "should not arrive"))
      (is (nil? (tu/take! ch 300)) "nothing may be delivered after UNSUBSCRIBE")
      (tu/close! c))))

(deftest subscribe-test
  (testing "a subscription made in one session still delivers in the next"
    (let [id      (tu/client-id "subscribe")
          topic   (tu/topic "qos-0")
          payload "this is a message"
          a       (tu/connect! nil :id id :clean-session? false)]
      (client/send-message (:client a) (subscribe-msg topic 1 123))
      (is (= [1] (:response (tu/expect! (:ch a) :SUBACK))))
      (client/send-message (:client a) (publish-msg topic payload))
      (let [msg (tu/expect-eventually! (:ch a) :PUBLISH)]
        (is (= 0 (:qos msg)))
        (is (= topic (:topic msg)))
        (is (= payload (tu/payload-str msg))))
      (tu/close! a)
      (tu/wait-for-parked-session! id)

      (let [b (tu/connect! nil :id id :clean-session? false)]
        (is (true? (:session-present? (:connack b))))
        (client/send-message (:client b) (publish-msg topic payload))
        (let [msg (tu/expect-eventually! (:ch b) :PUBLISH)]
          (is (= 0 (:qos msg)))
          (is (= topic (:topic msg)))
          (is (= payload (tu/payload-str msg))))
        (tu/close! b)))))

(deftest publish-with-an-explicit-payload
  (testing "client/publish delivers a caller-supplied payload"
    ;; Also keeps the four-argument arity exercised: it spent its life
    ;; uncalled, and uncalled means unverified.
    (let [topic (tu/topic "explicit")
          {:keys [client ch] :as c} (tu/connect! "explicit")]
      (client/send-message client (subscribe-msg topic 0 1))
      (tu/expect! ch :SUBACK)
      (client/publish client topic "an explicit payload" 0)
      (let [msg (tu/expect-eventually! ch :PUBLISH)]
        (is (= topic (:topic msg)))
        (is (= 0 (:qos msg)))
        (is (= "an explicit payload" (tu/payload-str msg))))
      (tu/close! c))))

(deftest publish-larger-than-the-encoder-scratch-buffer
  (testing "a payload well past 4 KB survives the round trip intact"
    ;; The encoders built every packet into a fixed 4096-byte array with no
    ;; bounds check, so anything larger threw ArrayIndexOutOfBoundsException
    ;; out of MqttPublish/encode. On the broker that exception is caught by
    ;; Connection.dispatch, which made it silent data loss: the publisher was
    ;; told nothing and the subscriber simply never heard. MQTT allows a body
    ;; of 268,435,455 bytes; 100 KB is enough to prove the array grows and
    ;; that the two- and three-byte remaining lengths encode correctly.
    (doseq [size [4096 4097 16384 100000]]
      (let [topic   (tu/topic "big")
            payload (apply str (repeat size \p))
            {:keys [client ch] :as c} (tu/connect! "big")]
        (client/send-message client (subscribe-msg topic 0 1))
        (tu/expect! ch :SUBACK)
        (client/publish client topic payload 0)
        (let [msg (tu/expect-eventually! ch :PUBLISH 5000)]
          (is (= topic (:topic msg)))
          (is (= size (count (tu/payload-str msg)))
              (str "payload of " size " bytes came back truncated"))
          (is (= payload (tu/payload-str msg))
              (str "payload of " size " bytes came back altered")))
        (tu/close! c)))))

(deftest non-ascii-topics-and-client-ids
  (testing "UTF-8 topics, filters and client ids survive decoding"
    ;; MQTT strings are UTF-8 (3.1.1 §1.5.3), but every decoder advanced past
    ;; one with String.length() — the character count, not the byte count it
    ;; had actually read. For anything outside ASCII the offset landed short
    ;; and the tail of the string was handed to whatever came next: the front
    ;; of a payload, the QoS byte of the next topic filter, the protocol
    ;; version after the client id. Silent, and only for people whose topics
    ;; are not English.
    (doseq [[label topic] [["ascii"    "plain/ascii"]
                           ["accents"  "café/über"]
                           ["japanese" "日本語/トピック"]
                           ["emoji"    "boot/⛵/status"]]]
      (testing label
        (let [t       (str topic "/" (tu/client-id "u8"))
              payload "the payload must arrive unchanged"
              {:keys [client ch] :as c} (tu/connect! (str "u8-" label))]
          (client/send-message client (subscribe-msg t 0 1))
          (tu/expect! ch :SUBACK)
          (client/publish client t payload 0)
          (let [msg (tu/expect-eventually! ch :PUBLISH 2000)]
            (is (= t (:topic msg)) "the topic came back altered")
            (is (= payload (tu/payload-str msg))
                "the payload was corrupted by the topic offset"))
          (tu/close! c))))))

(deftest non-ascii-multi-topic-subscribe
  (testing "a SUBSCRIBE carrying several UTF-8 filters decodes every one"
    ;; The per-topic offset error compounds inside the SUBSCRIBE loop: one
    ;; short advance and the QoS byte read for the next filter is really a
    ;; topic byte, so everything after the first non-ASCII filter is wrong.
    (let [a  (str "ü/" (tu/client-id "m1"))
          b  (str "日本/" (tu/client-id "m2"))
          c' (str "ascii/" (tu/client-id "m3"))
          {:keys [client ch] :as c} (tu/connect! "u8-multi")]
      (client/send-message client
                           {:packet-type :SUBSCRIBE :packet-identifier 7
                            :topics [{:qos 0 :topic-filter a}
                                     {:qos 0 :topic-filter b}
                                     {:qos 0 :topic-filter c'}]})
      (tu/expect! ch :SUBACK)
      (doseq [t [a b c']]
        (client/publish client t (str "payload for " t) 0)
        (let [msg (tu/expect-eventually! ch :PUBLISH 2000)]
          (is (= t (:topic msg)))
          (is (= (str "payload for " t) (tu/payload-str msg)))))
      (tu/close! c))))

(deftest qos-1-test
  (testing "an unacknowledged QoS 1 message is redelivered on the next session"
    (let [id      (tu/client-id "qos-1")
          topic   (tu/topic "qos-1")
          payload "qos-1 test message"
          a       (tu/connect! nil :id id :clean-session? false)]
      (client/send-message (:client a) (subscribe-msg topic 1 123))
      (tu/expect! (:ch a) :SUBACK)
      (client/send-message (:client a) (publish-msg topic payload :qos 1 :id 666))

      ;; Two packets come back, in either order: the PUBACK for what we
      ;; published, and the copy delivered to us as a subscriber. We
      ;; deliberately never acknowledge the latter.
      (let [{pubacks :PUBACK publishes :PUBLISH} (tu/take-n! (:ch a) 2)]
        (is (= [666] (map :packet-identifier pubacks)))
        (let [msg (first publishes)]
          (is (some? msg) "the subscriber copy should have been delivered")
          (is (= 1 (:qos msg)))
          (is (= topic (:topic msg)))
          (is (= payload (tu/payload-str msg)))))

      (client/send-message (:client a) {:packet-type :DISCONNECT})
      (tu/close! a)
      (tu/wait-for-parked-session! id)

      ;; Same client id, so the broker owes us the message we never acked.
      ;; Ordered: the CONNACK and the redelivery go out back to back, and the
      ;; default client hands each arriving packet to its own go block, so which
      ;; reaches the channel first is a race. That is the harness, not the
      ;; broker — it failed this test about one run in eight.
      (let [b (tu/connect! nil :id id :clean-session? false :ordered? true)]
        (is (true? (:session-present? (:connack b))))
        ;; A generous deadline: the redelivery is triggered by the reconnect,
        ;; and the rest of the suite can be keeping the broker busy. `when msg`
        ;; because a timeout should fail this test, not throw out of the PUBACK
        ;; built from a nil packet identifier.
        (let [msg (tu/expect-eventually! (:ch b) :PUBLISH 5000)]
          (when msg
            (is (= 1 (:qos msg)))
            (is (true? (:duplicate? msg)) "a redelivery is flagged as a duplicate")
            (is (= topic (:topic msg)))
            (is (= payload (tu/payload-str msg)))
            (client/send-message (:client b) {:packet-type :PUBACK
                                              :packet-identifier (:packet-identifier msg)})))
        (tu/close! b)))))
