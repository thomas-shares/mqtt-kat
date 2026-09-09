(ns mqttkat.v5-subscribe-test
  "MQTT 5.0 SUBSCRIBE (§3.8) and SUBACK (§3.9).

   Written before the implementation. Version 5 turns the QoS byte after each
   topic filter into a subscription options byte — QoS in the bottom two bits,
   then No Local, Retain As Published and Retain Handling — and turns SUBACK's
   granted-QoS byte into a reason code. Both are the same byte in the same
   place as 3.1.1, which is what makes them easy to get subtly wrong."
  (:require [clojure.test :refer [deftest is testing]])
  (:import [java.nio ByteBuffer]
           [org.mqttkat MqttProtocolError MqttReasonCode]
           [org.mqttkat.packages MqttSubAck MqttSubscribe]))

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

(defn- round-trip [message version]
  (MqttSubscribe/decode nil (body (MqttSubscribe/encode message)) version))

;; ── SUBSCRIBE ─────────────────────────────────────────────────────────

(deftest a-version-4-subscribe-is-unchanged
  (testing "topic filter then a bare QoS byte, no properties"
    (let [m (round-trip {:packet-type :SUBSCRIBE :packet-identifier 1
                         :topics [{:qos 1 :topic-filter "a/b"}
                                  {:qos 2 :topic-filter "c/#"}]}
                        v4)]
      (is (= 1 (:packet-identifier m)))
      (is (= ["a/b" "c/#"] (mapv :topic-filter (:topics m))))
      (is (= [1 2] (mapv #(long (:qos %)) (:topics m))))
      (is (not (contains? m :properties))))))

(deftest a-version-5-subscribe-carries-properties-and-options
  (testing "the property block sits after the packet identifier"
    (let [m (round-trip {:packet-type :SUBSCRIBE :packet-identifier 7
                         :protocol-version v5
                         :properties {:subscription-identifiers [42]
                                      :user-properties [["why" "because"]]}
                         :topics [{:qos 1 :topic-filter "a/b"}]}
                        v5)]
      (is (= 7 (:packet-identifier m)))
      (is (= [42] (vec (:subscription-identifiers (:properties m)))))
      (is (= [["why" "because"]] (mapv vec (:user-properties (:properties m)))))
      (is (= "a/b" (:topic-filter (first (:topics m))))
          "and the filters still read correctly after it")))

  (testing "subscription options decode into named flags"
    ;; §3.8.3.1: QoS in bits 0-1, No Local in bit 2, Retain As Published in
    ;; bit 3, Retain Handling in bits 4-5.
    (let [m (round-trip {:packet-type :SUBSCRIBE :packet-identifier 1
                         :protocol-version v5
                         :topics [{:qos 2 :topic-filter "x"
                                   :no-local? true
                                   :retain-as-published? true
                                   :retain-handling 2}]}
                        v5)
          t (first (:topics m))]
      (is (= 2 (long (:qos t))))
      (is (true? (:no-local? t)))
      (is (true? (:retain-as-published? t)))
      (is (= 2 (long (:retain-handling t))))))

  (testing "the defaults are all off"
    ;; A version 5 client that sets nothing gets 3.1.1 behaviour, which is what
    ;; every one of these flags defaulting to zero means.
    (let [t (first (:topics (round-trip {:packet-type :SUBSCRIBE :packet-identifier 1
                                         :protocol-version v5
                                         :topics [{:qos 0 :topic-filter "x"}]}
                                        v5)))]
      (is (= 0 (long (:qos t))))
      (is (false? (:no-local? t)))
      (is (false? (:retain-as-published? t)))
      (is (= 0 (long (:retain-handling t))))))

  (testing "each filter carries its own options"
    (let [m (round-trip {:packet-type :SUBSCRIBE :packet-identifier 1
                         :protocol-version v5
                         :topics [{:qos 0 :topic-filter "plain"}
                                  {:qos 1 :topic-filter "nolocal" :no-local? true}
                                  {:qos 2 :topic-filter "rh" :retain-handling 1}]}
                        v5)
          [a b c] (:topics m)]
      (is (false? (:no-local? a)))
      (is (true? (:no-local? b)))
      (is (= 0 (long (:retain-handling b))))
      (is (= 1 (long (:retain-handling c)))))))

(deftest a-subscribe-with-reserved-bits-set-is-refused
  (testing "bits 6 and 7 of the options byte must be zero"
    ;; §3.8.3.1 calls it a Malformed Packet. Ignoring them would let a client
    ;; think it had asked for something this broker silently did not do.
    (let [good (body (MqttSubscribe/encode {:packet-type :SUBSCRIBE :packet-identifier 1
                                            :protocol-version v5
                                            :topics [{:qos 0 :topic-filter "x"}]}))
          bad  (aclone good)]
      ;; the options byte is the last one
      (aset-byte bad (dec (alength bad)) (unchecked-byte 0xC0))
      (is (thrown? MqttProtocolError (MqttSubscribe/decode nil bad v5)))))

  (testing "and retain handling 3 is not a value"
    (let [good (body (MqttSubscribe/encode {:packet-type :SUBSCRIBE :packet-identifier 1
                                            :protocol-version v5
                                            :topics [{:qos 0 :topic-filter "x"}]}))
          bad  (aclone good)]
      (aset-byte bad (dec (alength bad)) (unchecked-byte 0x30))
      (is (thrown? MqttProtocolError (MqttSubscribe/decode nil bad v5))))))

;; ── SUBACK ────────────────────────────────────────────────────────────

(deftest a-version-4-suback-is-unchanged
  (testing "packet identifier then one granted-QoS byte per filter"
    (let [buf (MqttSubAck/encode {:packet-type :SUBACK :packet-identifier 3
                                  :response [0 1 2]})
          m   (MqttSubAck/decode nil (body buf) v4)]
      (is (= 3 (:packet-identifier m)))
      (is (= [0 1 2] (vec (:response m))))
      (is (not (contains? m :properties))))))

(deftest a-version-5-suback-carries-reason-codes
  (testing "properties, then one reason code per filter"
    ;; §3.9.3: granted QoS 0/1/2 are 0x00/0x01/0x02, so a success looks the
    ;; same as 3.1.1 — it is the failures that gain a vocabulary.
    (let [buf (MqttSubAck/encode {:packet-type :SUBACK :packet-identifier 9
                                  :protocol-version v5
                                  :properties {:reason-string "partly"}
                                  :response [0 2 (bit-and MqttReasonCode/TOPIC_FILTER_INVALID 0xff)]})
          m   (MqttSubAck/decode nil (body buf) v5)]
      (is (= 9 (:packet-identifier m)))
      (is (= "partly" (:reason-string (:properties m))))
      (is (= [0 2 0x8F] (vec (:response m))))))

  (testing "a refusal is distinguishable from a grant"
    ;; The whole point of the top bit: a client can tell whether it is
    ;; subscribed without a table of which codes mean what.
    (let [buf (MqttSubAck/encode {:packet-type :SUBACK :packet-identifier 1
                                  :protocol-version v5
                                  :response [(bit-and MqttReasonCode/NOT_AUTHORIZED 0xff)]})
          m   (MqttSubAck/decode nil (body buf) v5)]
      (is (MqttReasonCode/isError (unchecked-byte (first (:response m)))))))

  (testing "and an empty property block when there is nothing to say"
    (let [buf (MqttSubAck/encode {:packet-type :SUBACK :packet-identifier 1
                                  :protocol-version v5 :response [1]})
          m   (MqttSubAck/decode nil (body buf) v5)]
      (is (= {} (:properties m)))
      (is (= [1] (vec (:response m)))))))
