(ns mqttkat.v5-properties-test
  "MQTT 5.0 §2.2.2 — the property codec, and the variable byte integer it is
   framed with.

   Written before the implementation, so these are the specification restated
   as assertions rather than a description of whatever the code turned out to
   do. Section numbers are from the OASIS MQTT 5.0 standard.

   This is the foundation the rest of MQTT 5 sits on: every packet type gains a
   property block, so a mistake here is a mistake in all of them at once."
  (:require [clojure.test :refer [deftest is testing]])
  (:import [org.mqttkat.packages MqttProperties]
           [org.mqttkat MqttProtocolError MqttReasonCode]))

(defn- bytes-of
  "A byte array from ints, so a test can write the wire bytes out literally."
  [& xs]
  (byte-array (map unchecked-byte xs)))

(defn- ->ints
  "A byte array back as unsigned ints, which is how the spec writes them."
  [^bytes b]
  (mapv #(bit-and (long %) 0xff) b))

;; ── variable byte integer (§1.5.5) ────────────────────────────────────

(deftest a-variable-byte-integer-round-trips-at-every-boundary
  (testing "the four size boundaries the encoding is built around"
    ;; §1.5.5 gives these exact ranges: 1 byte to 127, 2 to 16,383, 3 to
    ;; 2,097,151, 4 to 268,435,455. The boundaries are where an off-by-one in
    ;; the continuation bit shows up.
    (doseq [[value width] [[0 1] [127 1] [128 2] [16383 2]
                           [16384 3] [2097151 3]
                           [2097152 4] [268435455 4]]]
      (let [encoded (MqttProperties/encodeVariableByteInteger value)]
        (is (= width (alength encoded))
            (str value " should encode in " width " byte(s)"))
        (is (= value (MqttProperties/decodeVariableByteInteger encoded 0))
            (str value " should survive the round trip"))
        (is (= width (MqttProperties/variableByteIntegerLength encoded 0))
            (str value " should report its own width")))))

  (testing "the worked example from the specification"
    ;; §1.5.5 spells out 128 as 0x80 0x01 and 16,383 as 0xFF 0x7F.
    (is (= [0x80 0x01] (->ints (MqttProperties/encodeVariableByteInteger 128))))
    (is (= [0xFF 0x7F] (->ints (MqttProperties/encodeVariableByteInteger 16383))))
    (is (= [0xFF 0xFF 0xFF 0x7F]
           (->ints (MqttProperties/encodeVariableByteInteger 268435455)))))

  (testing "decoding starts where it is told to"
    ;; Every caller decodes one out of the middle of a packet, never from zero.
    (let [buf (bytes-of 0xAA 0xBB 0x80 0x01 0xCC)]
      (is (= 128 (MqttProperties/decodeVariableByteInteger buf 2)))
      (is (= 2 (MqttProperties/variableByteIntegerLength buf 2))))))

(deftest a-malformed-variable-byte-integer-is-refused
  (testing "a fifth continuation byte is a malformed packet"
    ;; §1.5.5: four bytes is the maximum. Without this check the decoder walks
    ;; off the end of the buffer, and on a socket that is a desynchronised
    ;; stream rather than one bad packet.
    (let [buf (bytes-of 0xFF 0xFF 0xFF 0xFF 0x7F)]
      (is (thrown? MqttProtocolError (MqttProperties/decodeVariableByteInteger buf 0)))))

  (testing "a value above the four byte maximum cannot be encoded"
    (is (thrown? IllegalArgumentException
                 (MqttProperties/encodeVariableByteInteger 268435456))))

  (testing "a truncated integer is refused rather than read past the end"
    (let [buf (bytes-of 0x80)]                     ; continuation set, nothing follows
      (is (thrown? MqttProtocolError (MqttProperties/decodeVariableByteInteger buf 0))))))

;; ── the property block (§2.2.2) ───────────────────────────────────────

(deftest an-absent-property-block-is-a-single-zero-byte
  (testing "no properties encodes as one zero byte"
    ;; §2.2.2.1: the property length is always present, even when there are
    ;; none. Omitting it entirely is the easiest way to desynchronise a stream.
    (is (= [0x00] (->ints (MqttProperties/encode nil))))
    (is (= [0x00] (->ints (MqttProperties/encode {})))))

  (testing "and decodes back to nothing, consuming exactly one byte"
    (let [buf (bytes-of 0x00 0x99)]
      (is (= {} (MqttProperties/decode buf 0)))
      (is (= 1 (MqttProperties/blockLength buf 0))))))

(deftest every-property-type-round-trips
  (testing "byte, two byte, four byte, variable byte, string, binary and pair"
    ;; One property of each of the seven encodings in §2.2.2.2, so a mistake in
    ;; any single reader shows up here rather than in whichever packet happens
    ;; to carry it.
    (doseq [[k v] {:payload-format-indicator 1          ; byte
                   :maximum-qos              1          ; byte
                   :server-keep-alive        300        ; two byte
                   :receive-maximum          65535      ; two byte
                   :session-expiry-interval  4294967295 ; four byte, unsigned
                   :message-expiry-interval  60         ; four byte
                   :content-type             "application/json"  ; utf-8
                   :response-topic           "reply/to/me"       ; utf-8
                   :reason-string            "because"}]
      (let [encoded (MqttProperties/encode {k v})
            decoded (MqttProperties/decode encoded 0)]
        (is (= v (get decoded k)) (str k " should survive the round trip"))
        (is (= (alength encoded) (MqttProperties/blockLength encoded 0))
            (str k " should report the length it actually occupies")))))

  (testing "binary data keeps its bytes"
    (let [payload  (bytes-of 0x00 0x01 0xFE 0xFF)
          decoded  (MqttProperties/decode (MqttProperties/encode {:correlation-data payload}) 0)]
      (is (= (->ints payload) (->ints (:correlation-data decoded)))
          "correlation data is opaque bytes, not a string")))

  (testing "an empty string and empty binary data are legal"
    ;; Both are zero-length-prefixed, and both have been known to trip a
    ;; decoder that assumes at least one byte follows the length.
    (let [decoded (MqttProperties/decode
                   (MqttProperties/encode {:reason-string ""
                                           :authentication-data (byte-array 0)}) 0)]
      (is (= "" (:reason-string decoded)))
      (is (= [] (->ints (:authentication-data decoded))))))

  (testing "a subscription identifier is a variable byte integer, not four bytes"
    ;; §3.3.2.3.8. It is the only property with this encoding, and getting it
    ;; wrong is invisible until an identifier passes 127.
    (let [decoded (MqttProperties/decode
                   (MqttProperties/encode {:subscription-identifiers [268435455]}) 0)]
      (is (= [268435455] (vec (:subscription-identifiers decoded)))))))

(deftest the-availability-flags-decode-as-booleans
  (testing "0 and 1 become false and true"
    ;; Deliberately not left as the raw 0 or 1. In Clojure 0 is truthy, so a
    ;; server answering `retain-available 0` would read as "retain is
    ;; available" at every call site that asks the obvious question.
    (doseq [k [:retain-available :wildcard-subscription-available
               :subscription-identifier-available :shared-subscription-available
               :request-problem-information :request-response-information]]
      (is (false? (get (MqttProperties/decode (MqttProperties/encode {k false}) 0) k))
          (str k " false should survive as false"))
      (is (true? (get (MqttProperties/decode (MqttProperties/encode {k true}) 0) k))
          (str k " true should survive as true"))))

  (testing "but payload format indicator and maximum qos stay numbers"
    ;; Both are Byte on the wire but neither is a yes/no: the indicator says
    ;; which format, maximum qos says which level.
    (let [decoded (MqttProperties/decode
                   (MqttProperties/encode {:payload-format-indicator 1 :maximum-qos 2}) 0)]
      (is (= 1 (:payload-format-indicator decoded)))
      (is (= 2 (:maximum-qos decoded))))))

(deftest user-properties-repeat-and-keep-their-order
  (testing "a user property may appear many times, and order is significant"
    ;; §3.1.2.11.8: "The Server MUST maintain the order of User Properties".
    ;; A map would silently lose both the duplicates and the order, which is
    ;; why these are a vector of pairs.
    (let [props   {:user-properties [["a" "1"] ["b" "2"] ["a" "3"]]}
          decoded (MqttProperties/decode (MqttProperties/encode props) 0)]
      (is (= [["a" "1"] ["b" "2"] ["a" "3"]]
             (mapv vec (:user-properties decoded))))))

  (testing "no user properties at all leaves the key out"
    (is (not (contains? (MqttProperties/decode (MqttProperties/encode {:reason-string "x"}) 0)
                        :user-properties)))))

(deftest several-properties-in-one-block
  (testing "a realistic CONNECT property block"
    (let [props {:session-expiry-interval 3600
                 :receive-maximum 100
                 :maximum-packet-size 1048576
                 :topic-alias-maximum 10
                 :request-problem-information true
                 :user-properties [["client" "mqtt-kat"]]}
          decoded (MqttProperties/decode (MqttProperties/encode props) 0)]
      (is (= 3600 (:session-expiry-interval decoded)))
      (is (= 100 (:receive-maximum decoded)))
      (is (= 1048576 (:maximum-packet-size decoded)))
      (is (= 10 (:topic-alias-maximum decoded)))
      (is (true? (:request-problem-information decoded)))
      (is (= [["client" "mqtt-kat"]] (mapv vec (:user-properties decoded))))))

  (testing "a block is decoded from an offset, and reports its own length"
    ;; Every real caller has a packet header in front of the properties.
    (let [block (MqttProperties/encode {:reason-string "hello"})
          buf   (byte-array (concat (bytes-of 0x01 0x02 0x03) block (bytes-of 0xEE)))]
      (is (= "hello" (:reason-string (MqttProperties/decode buf 3))))
      (is (= (alength block) (MqttProperties/blockLength buf 3))))))

;; ── protocol errors (§4.13) ───────────────────────────────────────────

(deftest a-bad-property-block-is-refused-with-a-reason-code
  (testing "an unknown property identifier is a malformed packet"
    ;; §2.2.2.2. 0x07 is not assigned; a decoder that skips what it does not
    ;; know cannot tell where the next property starts.
    (let [buf (bytes-of 0x02 0x07 0x01)]
      (is (thrown? MqttProtocolError (MqttProperties/decode buf 0)))))

  (testing "a property that may not repeat is refused when it does"
    ;; §2.2.2.2: only User Property and Subscription Identifier may appear more
    ;; than once. Taking the last would silently discard what the peer said.
    ;;
    ;; The length has to be right for this to test what it says. Written first
    ;; as 0x08 for ten bytes of properties, it threw — but from the bounds
    ;; check, not the duplicate check, so it passed while proving nothing.
    (let [buf (bytes-of 0x0A 0x11 0x00 0x00 0x00 0x01 0x11 0x00 0x00 0x00 0x02)
          thrown (try (MqttProperties/decode buf 0) nil
                      (catch MqttProtocolError e e))]
      (is (some? thrown))
      (is (= MqttReasonCode/PROTOCOL_ERROR (.reasonCode ^MqttProtocolError thrown))
          "a repeat is a protocol error, not a malformed packet")))

  (testing "but the two that may repeat are accepted twice over"
    ;; The same shape, with an identifier that is allowed to recur.
    (let [buf (bytes-of 0x04 0x0B 0x01 0x0B 0x02)]
      (is (= [1 2] (vec (:subscription-identifiers (MqttProperties/decode buf 0)))))))

  (testing "a block whose length runs past the buffer is refused"
    (let [buf (bytes-of 0x20 0x1F 0x00 0x03)]      ; claims 32 bytes, has 3
      (is (thrown? MqttProtocolError (MqttProperties/decode buf 0)))))

  (testing "a property whose value overruns the block is refused"
    ;; Length says 3 bytes of properties; the string inside claims 9.
    (let [buf (bytes-of 0x03 0x1F 0x00 0x09)]
      (is (thrown? MqttProtocolError (MqttProperties/decode buf 0)))))

  (testing "the error carries the reason code that goes back to the client"
    ;; MQTT 5 answers a protocol error with a DISCONNECT or CONNACK carrying a
    ;; reason code, so the exception has to know which one rather than leaving
    ;; every catch site to guess.
    (let [thrown (try (MqttProperties/decode (bytes-of 0x02 0x07 0x01) 0)
                      nil
                      (catch MqttProtocolError e e))]
      (is (some? thrown))
      (is (= MqttReasonCode/MALFORMED_PACKET (.reasonCode ^MqttProtocolError thrown))))))

;; ── reason codes (§2.4) ───────────────────────────────────────────────

(deftest reason-codes-are-named
  (testing "the values from the specification"
    ;; Spot checks across the range, because these are the numbers that go on
    ;; the wire and a transposed digit is a different error entirely.
    (is (= 0x00 (bit-and MqttReasonCode/SUCCESS 0xff)))
    (is (= 0x00 (bit-and MqttReasonCode/NORMAL_DISCONNECTION 0xff)))
    (is (= 0x10 (bit-and MqttReasonCode/NO_MATCHING_SUBSCRIBERS 0xff)))
    (is (= 0x80 (bit-and MqttReasonCode/UNSPECIFIED_ERROR 0xff)))
    (is (= 0x81 (bit-and MqttReasonCode/MALFORMED_PACKET 0xff)))
    (is (= 0x82 (bit-and MqttReasonCode/PROTOCOL_ERROR 0xff)))
    (is (= 0x84 (bit-and MqttReasonCode/UNSUPPORTED_PROTOCOL_VERSION 0xff)))
    (is (= 0x85 (bit-and MqttReasonCode/CLIENT_IDENTIFIER_NOT_VALID 0xff)))
    (is (= 0x87 (bit-and MqttReasonCode/NOT_AUTHORIZED 0xff)))
    (is (= 0x8F (bit-and MqttReasonCode/TOPIC_FILTER_INVALID 0xff)))
    (is (= 0x90 (bit-and MqttReasonCode/TOPIC_NAME_INVALID 0xff)))
    (is (= 0x93 (bit-and MqttReasonCode/RECEIVE_MAXIMUM_EXCEEDED 0xff)))
    (is (= 0x94 (bit-and MqttReasonCode/TOPIC_ALIAS_INVALID 0xff)))
    (is (= 0x9E (bit-and MqttReasonCode/SHARED_SUBSCRIPTIONS_NOT_SUPPORTED 0xff)))
    (is (= 0xA2 (bit-and MqttReasonCode/WILDCARD_SUBSCRIPTIONS_NOT_SUPPORTED 0xff))))

  (testing "anything at or above 0x80 is a failure"
    ;; §2.4: the top bit is the whole convention, and it is what decides
    ;; whether an acknowledgement means yes.
    (is (not (MqttReasonCode/isError MqttReasonCode/SUCCESS)))
    (is (not (MqttReasonCode/isError (unchecked-byte 0x02))))
    (is (MqttReasonCode/isError MqttReasonCode/UNSPECIFIED_ERROR))
    (is (MqttReasonCode/isError MqttReasonCode/MALFORMED_PACKET)))

  (testing "a code can be named, for a log line that means something"
    (is (= "MALFORMED_PACKET" (MqttReasonCode/name MqttReasonCode/MALFORMED_PACKET)))
    (is (= "SUCCESS" (MqttReasonCode/name MqttReasonCode/SUCCESS)))
    (is (some? (MqttReasonCode/name (unchecked-byte 0x7F)))
        "an unassigned code still names itself rather than throwing")))
