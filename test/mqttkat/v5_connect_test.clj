(ns mqttkat.v5-connect-test
  "MQTT 5.0 CONNECT (§3.1) and CONNACK (§3.2).

   Written before the implementation. The point of this slice is that a version
   5 client can complete a handshake and that a version 4 one is untouched by
   it — the second half matters as much as the first, because CONNECT is the
   packet every existing client sends first."
  (:require [clojure.test :refer [deftest is testing]])
  (:import [java.nio ByteBuffer]
           [org.mqttkat MqttProtocolError MqttReasonCode]
           [org.mqttkat.packages MqttConnAck MqttConnect]))

(defn- body
  "The variable header and payload of an encoded packet.

   decode is handed what follows the fixed header, so a round-trip test has to
   strip it: one type byte, then the remaining-length bytes, which are a
   variable byte integer and so are found by their continuation bit."
  [^ByteBuffer buf]
  (let [arr (byte-array (.remaining buf))]
    (.get (.duplicate buf) arr)
    (let [start (loop [i 1]
                  (if (zero? (bit-and (aget arr i) 0x80)) (inc i) (recur (inc i))))]
      (java.util.Arrays/copyOfRange arr start (alength arr)))))

(defn- round-trip [message]
  (MqttConnect/decode nil (byte 0) (body (MqttConnect/encode message))))

(def ^:private base
  {:packet-type :CONNECT :protocol-name "MQTT" :keep-alive 60
   :clean-session? true :client-id "v5-client"})

;; ── CONNECT ───────────────────────────────────────────────────────────

(deftest a-version-4-connect-is-unchanged
  (testing "the 3.1.1 packet still round trips, with no property block"
    ;; The regression that matters most in this whole slice: every existing
    ;; client sends this packet, and a property block written into a version 4
    ;; CONNECT would desynchronise the stream at the client id.
    (let [m (round-trip (assoc base :protocol-version 4))]
      (is (= "MQTT" (:protocol-name m)))
      (is (= 4 (long (:protocol-version m))))
      (is (= "v5-client" (:client-id m)))
      (is (true? (:clean-session? m)))
      (is (= 60 (:keep-alive m)))
      (is (not (contains? m :properties))
          "a version 4 CONNECT has no properties to report")))

  (testing "and a version 4 will still round trips"
    (let [m (round-trip (assoc base :protocol-version 4
                               :will {:will-topic "gone" :will-message "bye"
                                      :will-qos 1 :will-retain true}))]
      (is (= "gone" (get-in m [:will :will-topic])))
      (is (= "bye" (get-in m [:will :will-message])))
      (is (= 1 (long (get-in m [:will :will-qos]))))
      (is (true? (get-in m [:will :will-retain]))))))

(deftest a-version-5-connect-carries-properties
  (testing "the property block sits between keep alive and the client id"
    ;; §3.1.2.11. Its position is the whole difference in the variable header,
    ;; and everything after it reads at the wrong offset if it is misplaced.
    (let [m (round-trip (assoc base :protocol-version 5
                               :properties {:session-expiry-interval 3600
                                            :receive-maximum 100
                                            :maximum-packet-size 1048576
                                            :topic-alias-maximum 10
                                            :user-properties [["client" "mqtt-kat"]]}))]
      (is (= 5 (long (:protocol-version m))))
      (is (= "v5-client" (:client-id m)) "the client id still reads correctly after it")
      (is (= 3600 (:session-expiry-interval (:properties m))))
      (is (= 100 (:receive-maximum (:properties m))))
      (is (= 1048576 (:maximum-packet-size (:properties m))))
      (is (= 10 (:topic-alias-maximum (:properties m))))
      (is (= [["client" "mqtt-kat"]] (mapv vec (:user-properties (:properties m)))))))

  (testing "a version 5 CONNECT with no properties still has the empty block"
    ;; §2.2.2.1: the length byte is not optional. Omitting it entirely is the
    ;; quickest way to read the client id as garbage.
    (let [m (round-trip (assoc base :protocol-version 5))]
      (is (= "v5-client" (:client-id m)))
      (is (= {} (:properties m)) "present and empty, not absent")))

  (testing "a zero-length client id is allowed"
    ;; §3.1.3.1: the server assigns one and returns it in the CONNACK.
    (let [m (round-trip (assoc base :protocol-version 5 :client-id ""))]
      (is (= "" (:client-id m))))))

(deftest a-version-5-will-carries-its-own-properties
  (testing "will properties come before the will topic"
    ;; §3.1.3.2, and they are a separate block from the CONNECT's own. Reading
    ;; the will topic without consuming them first gets the property length as
    ;; a string length.
    (let [m (round-trip (assoc base :protocol-version 5
                               :properties {:session-expiry-interval 60}
                               :will {:will-topic "last/words" :will-message "goodbye"
                                      :will-qos 2 :will-retain false
                                      :properties {:will-delay-interval 30
                                                   :content-type "text/plain"
                                                   :payload-format-indicator 1}}))]
      (is (= "last/words" (get-in m [:will :will-topic])))
      (is (= "goodbye" (get-in m [:will :will-message])))
      (is (= 2 (long (get-in m [:will :will-qos]))))
      (is (= 30 (:will-delay-interval (get-in m [:will :properties]))))
      (is (= "text/plain" (:content-type (get-in m [:will :properties]))))
      (is (= 60 (:session-expiry-interval (:properties m)))
          "and the connect's own properties are still intact"))))

(deftest a-truncated-version-5-connect-is-refused
  (testing "a property block claiming more than the packet holds"
    ;; Rather than reading whatever follows in the buffer as a client id.
    (let [good (body (MqttConnect/encode (assoc base :protocol-version 5
                                                :properties {:reason-string "xxxxxxxxxx"})))
          cut  (java.util.Arrays/copyOfRange good 0 (- (alength good) 8))]
      (is (thrown? MqttProtocolError (MqttConnect/decode nil (byte 0) cut))))))

;; ── CONNACK ───────────────────────────────────────────────────────────

(deftest a-version-3-connack-is-two-bytes
  (testing "unchanged: flags and return code, nothing else"
    ;; §3.2 of 3.1.1. Two bytes exactly, which is also what tells the client
    ;; decoder that this is not a version 5 packet.
    (let [buf (MqttConnAck/encode {:packet-type :CONNACK
                                   :session-present? false
                                   :connect-return-code 0})
          arr (byte-array (.remaining buf))]
      (.get buf arr)
      (is (= 4 (alength arr)) "two header bytes and two body bytes")
      (is (= 0x20 (bit-and (aget arr 0) 0xff)))
      (is (= 2 (aget arr 1)) "remaining length is 2"))))

(deftest a-version-5-connack-carries-a-reason-code-and-properties
  (testing "session present, reason code, then the property block"
    (let [buf (MqttConnAck/encode {:packet-type :CONNACK
                                   :protocol-version 5
                                   :session-present? true
                                   :reason-code MqttReasonCode/SUCCESS
                                   :properties {:assigned-client-identifier "auto-42"
                                                :server-keep-alive 120
                                                :maximum-qos 2
                                                :retain-available true
                                                :shared-subscription-available false}})
          arr (byte-array (.remaining buf))]
      (.get buf arr)
      (let [m (MqttConnAck/decode nil (java.util.Arrays/copyOfRange arr 2 (alength arr)))]
        (is (true? (:session-present? m)))
        (is (= 0 (long (:reason-code m))))
        (is (= "auto-42" (:assigned-client-identifier (:properties m))))
        (is (= 120 (:server-keep-alive (:properties m))))
        (is (= 2 (:maximum-qos (:properties m))))
        (is (true? (:retain-available (:properties m))))
        (is (false? (:shared-subscription-available (:properties m)))))))

  (testing "a refusal carries the reason code that says why"
    ;; §3.2.2.2 replaces 3.1.1's five return codes with the shared vocabulary,
    ;; so "unsupported protocol version" is 0x84 here and 0x01 there.
    (let [buf (MqttConnAck/encode {:packet-type :CONNACK
                                   :protocol-version 5
                                   :session-present? false
                                   :reason-code MqttReasonCode/UNSUPPORTED_PROTOCOL_VERSION})
          arr (byte-array (.remaining buf))]
      (.get buf arr)
      (let [m (MqttConnAck/decode nil (java.util.Arrays/copyOfRange arr 2 (alength arr)))]
        (is (false? (:session-present? m)))
        (is (= 0x84 (bit-and (long (:reason-code m)) 0xff)))
        (is (MqttReasonCode/isError (byte (:reason-code m))))
        (is (= {} (:properties m)) "and an empty property block, not a missing one")))))
