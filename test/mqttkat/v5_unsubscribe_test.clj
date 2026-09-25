(ns mqttkat.v5-unsubscribe-test
  "MQTT 5.0 UNSUBSCRIBE (§3.10) and UNSUBACK (§3.11).

   Written before the implementation. The interesting one is UNSUBACK: in
   3.1.1 it is the packet identifier and nothing else — no payload at all —
   while version 5 adds a property block *and* one reason code per filter. So
   unlike SUBACK, where the granted-QoS byte quietly became a reason code, here
   there is a whole payload that did not exist before."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [mqttkat.client :as client]
            [mqttkat.test-util :as tu])
  (:import [java.nio ByteBuffer]
           [org.mqttkat MqttReasonCode]
           [org.mqttkat.packages MqttUnSubAck MqttUnsubscribe]))

(def ^:private v4 4)
(def ^:private v5 5)

(defn- ^"[B" body [^ByteBuffer buf]
  (let [arr (byte-array (.remaining buf))]
    (.get (.duplicate buf) arr)
    (let [start (loop [i 1]
                  (if (zero? (bit-and (aget arr i) 0x80)) (inc i) (recur (inc i))))]
      ;; (int start) because a loop that recurs returns Object, so the compiler
      ;; cannot see the primitive and falls back to reflection on copyOfRange.
      (java.util.Arrays/copyOfRange arr (int start) (alength arr)))))

;; ── codec ─────────────────────────────────────────────────────────────

(deftest a-version-4-unsubscribe-is-unchanged
  (testing "packet identifier then bare topic filters"
    (let [m (MqttUnsubscribe/decode
             nil (body (MqttUnsubscribe/encode {:packet-type :UNSUBSCRIBE
                                                :packet-identifier 5
                                                :topics ["a/b" "c/#"]}))
             v4)]
      (is (= 5 (:packet-identifier m)))
      (is (= ["a/b" "c/#"] (vec (:topics m))))
      (is (not (contains? m :properties)))))

  (testing "and a 3.1.1 UNSUBACK is two bytes with no payload"
    (let [buf (MqttUnSubAck/encode {:packet-type :UNSUBACK :packet-identifier 5})
          arr (byte-array (.remaining buf))]
      (.get buf arr)
      (is (= 4 (alength arr)) "two header bytes and the identifier")
      (is (= 2 (aget arr 1)) "remaining length is 2")
      (let [m (MqttUnSubAck/decode nil (java.util.Arrays/copyOfRange arr 2 4) v4)]
        (is (= 5 (:packet-identifier m)))
        (is (not (contains? m :response)) "3.1.1 has no reason codes to report")))))

(deftest a-version-5-unsubscribe-carries-properties
  (testing "the property block sits between the identifier and the filters"
    (let [m (MqttUnsubscribe/decode
             nil (body (MqttUnsubscribe/encode {:packet-type :UNSUBSCRIBE
                                                :packet-identifier 11
                                                :protocol-version v5
                                                :properties {:user-properties [["why" "done"]]}
                                                :topics ["a/b" "c/#"]}))
             v5)]
      (is (= 11 (:packet-identifier m)))
      (is (= [["why" "done"]] (mapv vec (:user-properties (:properties m)))))
      (is (= ["a/b" "c/#"] (vec (:topics m)))
          "and the filters still read correctly after it")))

  (testing "with no properties the block is still there"
    (let [m (MqttUnsubscribe/decode
             nil (body (MqttUnsubscribe/encode {:packet-type :UNSUBSCRIBE
                                                :packet-identifier 1
                                                :protocol-version v5
                                                :topics ["x"]}))
             v5)]
      (is (= ["x"] (vec (:topics m))))
      (is (= {} (:properties m))))))

(deftest a-version-5-unsuback-has-a-reason-code-per-filter
  (testing "properties, then one code for each filter that was asked about"
    ;; §3.11.3. This payload does not exist in 3.1.1 at all, so a client
    ;; reading it as a 3.1.1 UNSUBACK sees trailing bytes it cannot explain.
    (let [buf (MqttUnSubAck/encode {:packet-type :UNSUBACK :packet-identifier 8
                                    :protocol-version v5
                                    :properties {:reason-string "partly"}
                                    :response [0x00 0x11]})
          m   (MqttUnSubAck/decode nil (body buf) v5)]
      (is (= 8 (:packet-identifier m)))
      (is (= "partly" (:reason-string (:properties m))))
      (is (= [0x00 0x11] (vec (:response m))))))

  (testing "no subscription existed is a success, not a failure"
    ;; §3.11.3: 0x11 is below 0x80, so unsubscribing from something you were
    ;; not subscribed to is reported and is not an error.
    (is (not (MqttReasonCode/isError MqttReasonCode/NO_SUBSCRIPTION_EXISTED)))
    (is (= 0x11 (bit-and MqttReasonCode/NO_SUBSCRIPTION_EXISTED 0xff))))

  (testing "and an empty property block when there is nothing to say"
    (let [buf (MqttUnSubAck/encode {:packet-type :UNSUBACK :packet-identifier 2
                                    :protocol-version v5 :response [0x00]})
          m   (MqttUnSubAck/decode nil (body buf) v5)]
      (is (= {} (:properties m)))
      (is (= [0x00] (vec (:response m)))))))

;; ── through the broker ────────────────────────────────────────────────

(use-fixtures :once tu/broker-fixture)

(defn- connect!
  "Version 5 goes through tu/connect-v5!, which marks the map so send! knows
   which dialect to write in; version 4 stays a plain client."
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

(deftest ^:portable the-broker-reports-which-subscriptions-existed
  (testing "one code per filter: success for a real one, 0x11 for a phantom"
    ;; The whole reason this payload was added. A client can now tell that its
    ;; unsubscribe was a no-op, which in 3.1.1 was indistinguishable from
    ;; success.
    (let [real (tu/topic "unsub-real")
          c    (connect! 5 (tu/client-id "unsub-report"))]
      (try
        (send! c {:packet-type :SUBSCRIBE :packet-identifier 1
                  :topics [{:qos 0 :topic-filter real}]})
        (tu/expect! (:ch c) :SUBACK)
        (send! c {:packet-type :UNSUBSCRIBE :packet-identifier 2
                  :topics [real "never/subscribed"]})
        (let [ack (tu/expect! (:ch c) :UNSUBACK 3000)]
          (is (= [0x00 0x11] (vec (:response ack)))
              "the first existed, the second never did")
          (is (map? (:properties ack))))
        (finally (tu/close! c))))))

(deftest ^:portable a-mismatched-dialect-is-refused-which-is-why-send-v5-exists
  (testing "a 3.1.1-shaped SUBSCRIBE on a version 5 connection gets a DISCONNECT, not a SUBACK"
    ;; Not a complaint about the broker: on a version 5 connection that packet
    ;; genuinely is malformed — with no property block, the topic filter's
    ;; length prefix is read as one — and refusing it is correct.
    ;;
    ;; It is here because of how the mistake presents. The client asks for a
    ;; subscription and does not get one; before the DISCONNECT slice it got
    ;; nothing at all, which in a test reads as a hang rather than as a wrong
    ;; packet, and cost a debugging cycle twice. tu/send-v5! stamps the version
    ;; so it cannot be written by accident.
    (let [c (tu/connect-v5! (tu/client-id "mismatch"))]
      (try
        ;; Deliberately client/send-message rather than tu/send-v5!.
        (client/send-message (:client c)
                             {:packet-type :SUBSCRIBE :packet-identifier 1
                              :topics [{:qos 0 :topic-filter (tu/topic "mismatch")}]})
        (let [reply (tu/take! (:ch c) 2000)]
          (is (= :DISCONNECT (:packet-type reply))
              "the broker says why rather than going quiet")
          (is (= 0x81 (bit-and (long (:reason-code reply)) 0xff))
              "malformed packet"))
        (finally (tu/close! c)))))

  (testing "and the same packet through send-v5! is answered"
    ;; The other half: the guard is what makes the difference, not the topic
    ;; or the broker being busy.
    (let [c (tu/connect-v5! (tu/client-id "matched"))]
      (try
        (tu/send-v5! c {:packet-type :SUBSCRIBE :packet-identifier 1
                        :topics [{:qos 0 :topic-filter (tu/topic "matched")}]})
        (is (= :SUBACK (:packet-type (tu/expect! (:ch c) :SUBACK 3000))))
        (finally (tu/close! c))))))

(deftest ^:portable a-version-4-client-still-gets-the-old-unsuback
  (testing "no payload, because a 3.1.1 client cannot read one"
    (let [topic (tu/topic "unsub-v4")
          c     (connect! 4 (tu/client-id "unsub-v4"))]
      (try
        (send! c {:packet-type :SUBSCRIBE :packet-identifier 1
                  :topics [{:qos 0 :topic-filter topic}]})
        (tu/expect! (:ch c) :SUBACK)
        (send! c {:packet-type :UNSUBSCRIBE :packet-identifier 2
                  :topics [topic]})
        (let [ack (tu/expect! (:ch c) :UNSUBACK 3000)]
          (is (= 2 (:packet-identifier ack)))
          (is (not (contains? ack :response))))
        (finally (tu/close! c))))))

(deftest ^:portable unsubscribing-actually-stops-delivery
  (testing "for both versions, the subscription is really gone"
    (doseq [version [4 5]]
      (let [topic (tu/topic (str "unsub-stops-" version))
            sub   (connect! version (tu/client-id (str "stop-sub-" version)))
            pub   (connect! 4 (tu/client-id (str "stop-pub-" version)))]
        (try
          (send! sub {:packet-type :SUBSCRIBE :packet-identifier 1
                      :topics [{:qos 0 :topic-filter topic}]})
          (tu/expect! (:ch sub) :SUBACK)
          (send! sub {:packet-type :UNSUBSCRIBE :packet-identifier 2
                      :topics [topic]})
          (tu/expect! (:ch sub) :UNSUBACK 3000)
          (send! pub {:packet-type :PUBLISH :topic topic :qos 0
                      :payload (.getBytes "after" "UTF-8")
                      :retain? false :duplicate? false})
          (is (nil? (tu/take! (:ch sub) 700))
              (str "version " version " should receive nothing after unsubscribing"))
          (finally (tu/close! sub pub)))))))
