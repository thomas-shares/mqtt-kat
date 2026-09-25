(ns mqttkat.v5-ack-test
  "MQTT 5.0 PUBACK, PUBREC, PUBREL and PUBCOMP (§3.4 to §3.7).

   Written before the implementation. All four are the same packet with a
   different type nibble — packet identifier, reason code, properties — and
   version 5 changes them all the same way, so they are tested together and
   share one implementation rather than four copies of it.

   The useful behaviour underneath is 0x10 No Matching Subscribers: a QoS 1 or
   2 publisher can finally tell that its message reached nobody. In 3.1.1 a
   PUBACK means only \"I have it\", and there is no way to say more."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [mqttkat.client :as client]
            [mqttkat.test-util :as tu])
  (:import [java.nio ByteBuffer]
           [org.mqttkat MqttReasonCode]
           [org.mqttkat.packages MqttPubAck MqttPubComp MqttPubRec MqttPubRel]))

(use-fixtures :once tu/broker-fixture)

(defn- bytes-of [^ByteBuffer buf]
  (let [arr (byte-array (.remaining buf))]
    (.get (.duplicate buf) arr)
    (mapv #(bit-and (long %) 0xff) arr)))

(defn- body [^ByteBuffer buf]
  (let [arr (byte-array (.remaining buf))]
    (.get (.duplicate buf) arr)
    (java.util.Arrays/copyOfRange arr 2 (alength arr))))

(def ^:private codecs
  "Every acknowledgement, with the first byte it puts on the wire."
  [[MqttPubAck  :PUBACK  0x40]
   [MqttPubRec  :PUBREC  0x50]
   ;; PUBREL carries the reserved 0x02 in its flags nibble, in both versions.
   [MqttPubRel  :PUBREL  0x62]
   [MqttPubComp :PUBCOMP 0x70]])

(defn- encode [cls m]
  (condp = cls
    MqttPubAck  (MqttPubAck/encode m)
    MqttPubRec  (MqttPubRec/encode m)
    MqttPubRel  (MqttPubRel/encode m)
    MqttPubComp (MqttPubComp/encode m)))

(defn- decode [cls data version]
  (condp = cls
    MqttPubAck  (MqttPubAck/decode nil data version)
    MqttPubRec  (MqttPubRec/decode nil data version)
    MqttPubRel  (MqttPubRel/decode nil data version)
    MqttPubComp (MqttPubComp/decode nil data version)))

(deftest a-version-4-acknowledgement-is-four-bytes
  (testing "type, remaining length 2, and the packet identifier"
    (doseq [[cls kind first-byte] codecs]
      (let [buf (encode cls {:packet-type kind :packet-identifier 258})]
        (is (= [first-byte 0x02 0x01 0x02] (bytes-of buf))
            (str kind " should be unchanged from 3.1.1"))
        (let [m (decode cls (body buf) 4)]
          (is (= 258 (:packet-identifier m)))
          (is (not (contains? m :reason-code))
              (str kind " has no reason code in 3.1.1")))))))

(deftest a-successful-version-5-acknowledgement-is-also-four-bytes
  (testing "the reason code and property length are omitted when there is nothing to say"
    ;; §3.4.2.1: with a Success reason and no properties the remaining length
    ;; is 2, which is byte for byte what 3.1.1 sends. Emitting a redundant
    ;; 0x00 and 0x00 would be legal but wasteful on the commonest packet the
    ;; broker writes.
    (doseq [[cls kind first-byte] codecs]
      (let [buf (encode cls {:packet-type kind :packet-identifier 1
                             :protocol-version 5
                             :reason-code MqttReasonCode/SUCCESS})]
        (is (= [first-byte 0x02 0x00 0x01] (bytes-of buf)) (str kind))
        (let [m (decode cls (body buf) 5)]
          (is (= 1 (:packet-identifier m)))
          (is (= 0 (long (:reason-code m))) "an absent code means success")
          (is (= {} (:properties m))))))))

(deftest a-version-5-acknowledgement-may-carry-a-reason-code
  (testing "remaining length 3: the code with no property block"
    (doseq [[cls kind first-byte] codecs]
      (let [buf (encode cls {:packet-type kind :packet-identifier 7
                             :protocol-version 5
                             :reason-code MqttReasonCode/UNSPECIFIED_ERROR})]
        (is (= [first-byte 0x03 0x00 0x07 0x80] (bytes-of buf)) (str kind))
        (let [m (decode cls (body buf) 5)]
          (is (= 0x80 (bit-and (long (:reason-code m)) 0xff)))
          (is (= {} (:properties m)))))))

  (testing "and properties when there is something to explain"
    (doseq [[cls kind _] codecs]
      (let [buf (encode cls {:packet-type kind :packet-identifier 9
                             :protocol-version 5
                             :reason-code MqttReasonCode/QUOTA_EXCEEDED
                             :properties {:reason-string "too many in flight"}})
            m   (decode cls (body buf) 5)]
        (is (= 9 (:packet-identifier m)) (str kind))
        (is (= 0x97 (bit-and (long (:reason-code m)) 0xff)))
        (is (= "too many in flight" (:reason-string (:properties m)))))))

  (testing "the codes each packet is allowed to carry"
    ;; §3.4.2.1 and §3.6.2.1: the release half of QoS 2 has a much shorter
    ;; list than the publish half, and 0x92 is the one that matters — it says
    ;; the identifier being released was never in flight.
    (is (= 0x10 (bit-and MqttReasonCode/NO_MATCHING_SUBSCRIBERS 0xff)))
    (is (not (MqttReasonCode/isError MqttReasonCode/NO_MATCHING_SUBSCRIBERS))
        "reaching nobody is not a failure")
    (is (= 0x92 (bit-and MqttReasonCode/PACKET_IDENTIFIER_NOT_FOUND 0xff)))
    (is (MqttReasonCode/isError MqttReasonCode/PACKET_IDENTIFIER_NOT_FOUND))))

;; ── through the broker ────────────────────────────────────────────────

(defn- publish! [c topic qos id]
  (tu/send-v5! c {:packet-type :PUBLISH :topic topic :qos qos
                  :packet-identifier id
                  :payload (.getBytes "hello" "UTF-8")
                  :retain? false :duplicate? false}))

(deftest ^:portable a-publisher-learns-that-nobody-was-listening
  (testing "QoS 1 to a topic with no subscribers is acknowledged with 0x10"
    ;; §3.4.2.1. The message was accepted — the publisher owes nothing more —
    ;; but it went nowhere, and until version 5 there was no way to say so.
    (let [c (tu/connect-v5! "lonely")]
      (try
        (publish! c (tu/topic "nobody-listening") 1 1)
        (let [ack (tu/expect! (:ch c) :PUBACK 3000)]
          (is (= 1 (:packet-identifier ack)))
          (is (= 0x10 (bit-and (long (:reason-code ack)) 0xff))
              "no matching subscribers"))
        (finally (tu/close! c)))))

  (testing "and with a subscriber it is a plain success"
    (let [topic (tu/topic "somebody-listening")
          sub   (tu/connect-v5! "listener")
          pub   (tu/connect-v5! "talker")]
      (try
        (tu/send-v5! sub {:packet-type :SUBSCRIBE :packet-identifier 1
                          :topics [{:qos 0 :topic-filter topic}]})
        (tu/expect! (:ch sub) :SUBACK)
        (publish! pub topic 1 2)
        (let [ack (tu/expect! (:ch pub) :PUBACK 3000)]
          (is (= 0 (long (:reason-code ack))) "success"))
        (finally (tu/close! sub pub))))))

(deftest ^:portable a-qos-2-publisher-learns-the-same-thing
  (testing "PUBREC carries 0x10 when nothing matched"
    ;; §3.5.2.1. It is reported on the PUBREC, the first answer of the
    ;; handshake, not on the PUBCOMP at the end.
    (let [c (tu/connect-v5! "lonely2")]
      (try
        (publish! c (tu/topic "nobody-listening-2") 2 3)
        (let [rec (tu/expect! (:ch c) :PUBREC 3000)]
          (is (= 3 (:packet-identifier rec)))
          (is (= 0x10 (bit-and (long (:reason-code rec)) 0xff))))
        (finally (tu/close! c))))))

(deftest ^:portable a-version-4-publisher-sees-no-change
  (testing "a 3.1.1 PUBACK is still four bytes with no reason code"
    (let [c (tu/connect! "old-talker")]
      (try
        (client/send-message (:client c)
                             {:packet-type :PUBLISH :topic (tu/topic "v4-lonely")
                              :qos 1 :packet-identifier 4
                              :payload (.getBytes "hello" "UTF-8")
                              :retain? false :duplicate? false})
        (let [ack (tu/expect! (:ch c) :PUBACK 3000)]
          (is (= 4 (:packet-identifier ack)))
          (is (not (contains? ack :reason-code))
              "nothing a 3.1.1 client would not understand"))
        (finally (tu/close! c))))))
