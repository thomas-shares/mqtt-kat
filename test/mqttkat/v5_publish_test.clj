(ns mqttkat.v5-publish-test
  "MQTT 5.0 PUBLISH (§3.3).

   Written before the implementation. A v5 PUBLISH is not distinguishable from
   a 3.1.1 one by shape — same topic, same optional packet identifier, same
   payload — so unlike CONNACK the decoder cannot guess and has to be told
   which version the connection negotiated. That is the thing these tests pin
   down first."
  (:require [clojure.test :refer [deftest is testing]])
  (:import [java.nio ByteBuffer]
           [org.mqttkat MqttProtocolError]
           [org.mqttkat.packages MqttPublish]))

(def ^:private v4 4)
(def ^:private v5 5)

(defn- body
  "What follows the fixed header, which is what decode is handed."
  [^ByteBuffer buf]
  (let [arr (byte-array (.remaining buf))]
    (.get (.duplicate buf) arr)
    (let [start (loop [i 1]
                  (if (zero? (bit-and (aget arr i) 0x80)) (inc i) (recur (inc i))))]
      (java.util.Arrays/copyOfRange arr start (alength arr)))))

(defn- flags-of [^ByteBuffer buf]
  (let [arr (byte-array (.remaining buf))]
    (.get (.duplicate buf) arr)
    (byte (bit-and (aget arr 0) 0x0f))))

(defn- round-trip [message version]
  (let [buf (MqttPublish/encode message)]
    (MqttPublish/decode nil (flags-of buf) (body buf) version)))

(defn- payload-of [m] (String. ^bytes (:payload m) "UTF-8"))

(def ^:private base
  {:packet-type :PUBLISH :topic "sensors/temp"
   :payload (.getBytes "21.5" "UTF-8") :retain? false :duplicate? false})

;; ── 3.1.1 stays exactly as it was ─────────────────────────────────────

(deftest a-version-4-publish-is-unchanged
  (testing "QoS 0: topic then payload, no property block"
    ;; The regression that matters: a property block written into a 3.1.1
    ;; PUBLISH is read as the first byte of the payload by every existing
    ;; subscriber.
    (let [m (round-trip (assoc base :qos 0) v4)]
      (is (= "sensors/temp" (:topic m)))
      (is (= "21.5" (payload-of m)))
      (is (= 0 (long (:qos m))))
      (is (not (contains? m :properties)))))

  (testing "QoS 1: topic, packet identifier, payload"
    (let [m (round-trip (assoc base :qos 1 :packet-identifier 42) v4)]
      (is (= "sensors/temp" (:topic m)))
      (is (= 42 (:packet-identifier m)))
      (is (= "21.5" (payload-of m)))
      (is (not (contains? m :properties)))))

  (testing "retain and duplicate flags survive"
    (let [m (round-trip (assoc base :qos 1 :packet-identifier 7
                               :retain? true :duplicate? true) v4)]
      (is (true? (:retain? m)))
      (is (true? (:duplicate? m))))))

;; ── version 5 ─────────────────────────────────────────────────────────

(deftest a-version-5-publish-carries-properties
  (testing "QoS 0: topic, properties, payload"
    ;; §3.3.2.3. With no packet identifier the property block follows the topic
    ;; directly, and the payload is whatever is left after it.
    (let [m (round-trip (assoc base :qos 0 :protocol-version v5
                               :properties {:content-type "text/plain"
                                            :message-expiry-interval 60})
                        v5)]
      (is (= "sensors/temp" (:topic m)))
      (is (= "21.5" (payload-of m)) "the payload starts after the properties")
      (is (= "text/plain" (:content-type (:properties m))))
      (is (= 60 (:message-expiry-interval (:properties m))))))

  (testing "QoS 1: topic, packet identifier, properties, payload"
    ;; The order matters — properties come after the identifier, not before.
    (let [m (round-trip (assoc base :qos 1 :packet-identifier 99 :protocol-version v5
                               :properties {:response-topic "reply/here"})
                        v5)]
      (is (= 99 (:packet-identifier m)))
      (is (= "reply/here" (:response-topic (:properties m))))
      (is (= "21.5" (payload-of m)))))

  (testing "a version 5 publish with no properties still carries the empty block"
    (let [m (round-trip (assoc base :qos 0 :protocol-version v5) v5)]
      (is (= "21.5" (payload-of m)))
      (is (= {} (:properties m)) "present and empty, not absent")))

  (testing "request/response and correlation data survive"
    ;; §3.3.2.3.5 and §3.3.2.3.6 — the pair that makes request/response work.
    (let [correlation (byte-array [(byte 1) (byte 2) (byte 3)])
          m (round-trip (assoc base :qos 0 :protocol-version v5
                               :properties {:response-topic "reply/to/me"
                                            :correlation-data correlation
                                            :payload-format-indicator 1
                                            :user-properties [["trace" "abc"]]})
                        v5)]
      (is (= "reply/to/me" (:response-topic (:properties m))))
      (is (= [1 2 3] (mapv #(bit-and (long %) 0xff) (:correlation-data (:properties m)))))
      (is (= 1 (:payload-format-indicator (:properties m))))
      (is (= [["trace" "abc"]] (mapv vec (:user-properties (:properties m)))))))

  (testing "an empty payload is still an empty payload"
    ;; A zero-length payload is how a retained message is cleared, and with a
    ;; property block in front of it the arithmetic is easy to get wrong.
    (let [m (round-trip (assoc base :qos 0 :protocol-version v5
                               :payload (byte-array 0)
                               :properties {:content-type "none"})
                        v5)]
      (is (= 0 (alength ^bytes (:payload m))))
      (is (= "none" (:content-type (:properties m)))))))

(deftest a-topic-alias-may-replace-the-topic-name
  (testing "an alias with an empty topic name"
    ;; §3.3.2.3.4: after a client has published a topic once with an alias, it
    ;; may send the alias alone. The codec's job is only to carry both; which
    ;; topic the alias stands for is connection state and belongs to the broker.
    (let [m (round-trip (assoc base :qos 0 :protocol-version v5
                               :topic ""
                               :properties {:topic-alias 5})
                        v5)]
      (is (= "" (:topic m)))
      (is (= 5 (:topic-alias (:properties m))))))

  (testing "and an alias alongside a topic name, which is how one is declared"
    (let [m (round-trip (assoc base :qos 0 :protocol-version v5
                               :properties {:topic-alias 5})
                        v5)]
      (is (= "sensors/temp" (:topic m)))
      (is (= 5 (:topic-alias (:properties m)))))))

(deftest subscription-identifiers-ride-along-to-the-subscriber
  (testing "one publish may carry several"
    ;; §3.3.4: when a message matches more than one of a client's
    ;; subscriptions, the server sends every matching identifier.
    (let [m (round-trip (assoc base :qos 0 :protocol-version v5
                               :properties {:subscription-identifiers [1 268435455]})
                        v5)]
      (is (= [1 268435455] (vec (:subscription-identifiers (:properties m))))))))

(deftest a-malformed-version-5-publish-is-refused
  (testing "a property block claiming more than the packet holds"
    ;; Otherwise the payload silently becomes whatever follows in the buffer.
    (let [buf (MqttPublish/encode (assoc base :qos 0 :protocol-version v5
                                         :properties {:content-type "text/plain"}))
          good (body buf)
          cut  (java.util.Arrays/copyOfRange good 0 (- (alength good) 6))]
      (is (thrown? MqttProtocolError
                   (MqttPublish/decode nil (flags-of buf) cut v5))))))
