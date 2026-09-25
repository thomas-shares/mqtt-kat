(ns mqttkat.v5-handshake-test
  "A version 5 client connecting to the broker over a real socket.

   The codec tests prove the bytes are right; this proves the broker accepts
   them. Written before the handler change, which at this point refuses any
   version but 4."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [mqttkat.client :as client]
            [mqttkat.test-util :as tu])
  (:import [org.mqttkat MqttReasonCode]))

(use-fixtures :once tu/broker-fixture)

(defn- connect-v5!
  "Open a socket, send a version 5 CONNECT, return the client and its CONNACK."
  [id & {:keys [properties clean-session?] :or {clean-session? true}}]
  (let [c (tu/client!)]
    (client/send-message (:client c)
                         (cond-> {:packet-type :CONNECT :protocol-name "MQTT"
                                  :protocol-version 5 :keep-alive 0
                                  :clean-session? clean-session? :client-id id}
                           properties (assoc :properties properties)))
    (assoc c :connack (tu/expect! (:ch c) :CONNACK))))

(deftest ^:portable a-version-5-client-is-accepted
  (testing "the broker answers a version 5 CONNECT with a version 5 CONNACK"
    ;; Before this, protocol-version-not-valid? was (not= version 4), so a
    ;; version 5 client got return code 0x01 and a closed socket.
    (let [c (connect-v5! (tu/client-id "v5-hello"))]
      (try
        (let [ack (:connack c)]
          (is (= :CONNACK (:packet-type ack)))
          (is (= 0 (long (:reason-code ack)))
              "success, and reported as a reason code rather than a return code")
          (is (not (MqttReasonCode/isError (byte (:reason-code ack)))))
          (is (false? (:session-present? ack)) "a clean session is never present")
          (is (map? (:properties ack)) "a version 5 CONNACK always has a property block"))
        (finally (tu/close! c)))))

  (testing "and the broker states its own limits in the CONNACK properties"
    ;; §3.2.2.3. A client that is not told assumes the defaults — unlimited
    ;; QoS 2, retain available, wildcards available — and a broker that cannot
    ;; do one of those has to say so here rather than fail later.
    (let [c (connect-v5! (tu/client-id "v5-limits"))]
      (try
        (let [props (:properties (:connack c))]
          (is (contains? props :retain-available))
          (is (contains? props :wildcard-subscription-available))
          ;; §3.2.2.3.4: the property says a broker does *less* than QoS 2 and
          ;; may only be 0 or 1; a broker that does QoS 2 leaves it out. Sent
          ;; as 2 it is a Protocol Error, and mosquitto's client refused every
          ;; CONNACK this broker ever sent it.
          (is (not (contains? props :maximum-qos)) "this broker does QoS 2, which is said by silence"))
        (finally (tu/close! c))))))

(deftest ^:portable a-version-5-client-can-send-its-properties
  (testing "a CONNECT property block is accepted and does not disturb the rest"
    (let [id (tu/client-id "v5-props")
          c  (connect-v5! id :properties {:session-expiry-interval 300
                                          :receive-maximum 50
                                          :user-properties [["agent" "test"]]})]
      (try
        (is (= 0 (long (:reason-code (:connack c)))))
        ;; The handshake completing at all is the assertion: a property block
        ;; read at the wrong offset takes the client id with it, and the broker
        ;; would have refused or hung up instead.
        (is (= :CONNACK (:packet-type (:connack c))))
        (finally (tu/close! c))))))

(deftest ^:portable an-unsupported-version-is-refused-with-a-version-5-reason-code
  (testing "version 6 gets 0x84, not 3.1.1's 0x01"
    ;; §3.2.2.2. The client asked in a dialect the broker does not speak, and
    ;; the answer has to be in one it does — which for anything above 5 is the
    ;; version 5 vocabulary.
    (let [c (tu/client!)]
      (try
        (client/send-message (:client c)
                             {:packet-type :CONNECT :protocol-name "MQTT"
                              :protocol-version 6 :keep-alive 0
                              :clean-session? true :client-id "v6"})
        (let [ack (tu/expect! (:ch c) :CONNACK)]
          (is (= 0x84 (bit-and (long (:reason-code ack)) 0xff))
              "unsupported protocol version"))
        (finally (tu/close! c)))))

  (testing "version 3 is still refused the 3.1.1 way"
    ;; A client that never spoke version 5 cannot read a version 5 CONNACK, so
    ;; the refusal has to stay in the dialect it asked in.
    (let [c (tu/client!)]
      (try
        (client/send-message (:client c)
                             {:packet-type :CONNECT :protocol-name "MQTT"
                              :protocol-version 3 :keep-alive 0
                              :clean-session? true :client-id "v3"})
        (let [ack (tu/expect! (:ch c) :CONNACK)]
          (is (= 1 (long (:connect-return-code ack)))
              "0x01, unacceptable protocol version")
          (is (not (contains? ack :properties))
              "and no property block, which a 3.1.1 client could not parse"))
        (finally (tu/close! c))))))

(deftest ^:portable a-version-4-client-still-connects-unchanged
  (testing "the existing handshake is untouched"
    (let [c (tu/connect! (tu/client-id "v4-still"))]
      (try
        (is (= 0 (long (:connect-return-code (:connack c)))))
        (is (not (contains? (:connack c) :properties))
            "a 3.1.1 CONNACK is two bytes and carries none")
        (finally (tu/close! c))))))
