(ns mqttkat.v5-disconnect-test
  "MQTT 5.0 DISCONNECT (§3.14).

   Written before the implementation. Two things are new and one is a
   long-standing bug this makes visible.

   New: the packet carries a reason code and properties, and the server may now
   send one — 3.1.1 has no server-to-client DISCONNECT at all, so a broker
   refusing something could only hang up and leave the client guessing.

   The bug: §3.14.4 says a server receiving a DISCONNECT must discard the will
   *without publishing it*. This broker publishes it, and no test noticed
   because both existing will tests drop the socket rather than disconnecting
   politely — which is the case where the will *should* be published."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [mqttkat.client :as client]
            [mqttkat.test-util :as tu])
  (:import [java.nio ByteBuffer]
           [org.mqttkat MqttReasonCode]
           [org.mqttkat.packages MqttDisconnect]))

(use-fixtures :once tu/broker-fixture)

(defn- bytes-of [^ByteBuffer buf]
  (let [arr (byte-array (.remaining buf))]
    (.get (.duplicate buf) arr)
    (mapv #(bit-and (long %) 0xff) arr)))

(defn- body [^ByteBuffer buf]
  (let [arr (byte-array (.remaining buf))]
    (.get (.duplicate buf) arr)
    (java.util.Arrays/copyOfRange arr 2 (alength arr))))

;; ── codec ─────────────────────────────────────────────────────────────

(deftest a-normal-disconnect-is-still-two-bytes
  (testing "no reason code, no properties, remaining length zero"
    ;; §3.14.2: both may be omitted when the reason is Normal Disconnection
    ;; and there are no properties. Every 3.1.1 client sends exactly this, and
    ;; a version 5 one is entitled to as well.
    (is (= [0xE0 0x00] (bytes-of (MqttDisconnect/encode))))
    (is (= [0xE0 0x00] (bytes-of (MqttDisconnect/encode {:packet-type :DISCONNECT}))))
    (is (= [0xE0 0x00] (bytes-of (MqttDisconnect/encode
                                  {:packet-type :DISCONNECT
                                   :protocol-version 5
                                   :reason-code MqttReasonCode/NORMAL_DISCONNECTION})))
        "a version 5 normal disconnect with nothing to add is the same two bytes"))

  (testing "and decodes to a normal disconnection"
    (let [m (MqttDisconnect/decode nil (byte-array 0) 5)]
      (is (= 0 (long (:reason-code m))))
      (is (= {} (:properties m))))))

(deftest a-disconnect-may-carry-a-reason-code-alone
  (testing "remaining length 1: the code with no property block"
    ;; §3.14.2.2.1: the property length may be omitted when there are no
    ;; properties, so a one-byte body is legal and has to be read as such.
    (let [buf (MqttDisconnect/encode {:packet-type :DISCONNECT
                                      :protocol-version 5
                                      :reason-code MqttReasonCode/TOPIC_ALIAS_INVALID})]
      (is (= [0xE0 0x01 0x94] (bytes-of buf)))
      (let [m (MqttDisconnect/decode nil (body buf) 5)]
        (is (= 0x94 (bit-and (long (:reason-code m)) 0xff)))
        (is (= {} (:properties m)) "absent, and reported as empty")))))

(deftest a-disconnect-may-carry-properties
  (testing "reason code then the property block"
    (let [buf (MqttDisconnect/encode {:packet-type :DISCONNECT
                                      :protocol-version 5
                                      :reason-code MqttReasonCode/SERVER_SHUTTING_DOWN
                                      :properties {:reason-string "going down"
                                                   :server-reference "other:1883"}})
          m   (MqttDisconnect/decode nil (body buf) 5)]
      (is (= 0x8B (bit-and (long (:reason-code m)) 0xff)))
      (is (= "going down" (:reason-string (:properties m))))
      (is (= "other:1883" (:server-reference (:properties m))))))

  (testing "a client may state a session expiry on the way out"
    ;; §3.14.2.2.2 — the one property that travels client to server.
    (let [buf (MqttDisconnect/encode {:packet-type :DISCONNECT
                                      :protocol-version 5
                                      :reason-code MqttReasonCode/NORMAL_DISCONNECTION
                                      :properties {:session-expiry-interval 300}})
          m   (MqttDisconnect/decode nil (body buf) 5)]
      (is (= 300 (:session-expiry-interval (:properties m)))))))

;; ── the will ──────────────────────────────────────────────────────────

(defn- subscribe-msg [topic]
  {:packet-type :SUBSCRIBE :packet-identifier 1
   :topics [{:qos 0 :topic-filter topic}]})

(deftest ^:portable a-polite-disconnect-discards-the-will
  (testing "3.1.1: a client that says goodbye does not fire its will"
    ;; §3.14.4. The will is for a client that vanished, and publishing it after
    ;; an orderly goodbye tells every subscriber the client crashed when it
    ;; did not. This broker published it, and no test caught it because both
    ;; will tests drop the socket instead of disconnecting.
    (let [topic (tu/topic "will-polite")
          dying (tu/connect! "polite" :will {:will-retain false :will-topic topic
                                             :will-message "should not appear"
                                             :will-qos 0})
          sub   (tu/connect! "polite-sub")]
      (try
        (client/send-message (:client sub) (subscribe-msg topic))
        (tu/expect! (:ch sub) :SUBACK)
        (client/send-message (:client dying) {:packet-type :DISCONNECT})
        (is (nil? (tu/take! (:ch sub) 1200))
            "no will should be published after a DISCONNECT")
        (finally (tu/close! dying sub)))))

  (testing "version 5 the same, by default"
    (let [topic (tu/topic "will-polite-v5")
          dying (tu/connect-v5! "polite5" :will {:will-retain false :will-topic topic
                                                 :will-message "should not appear"
                                                 :will-qos 0})
          sub   (tu/connect-v5! "polite5-sub")]
      (try
        (tu/send-v5! sub (subscribe-msg topic))
        (tu/expect! (:ch sub) :SUBACK)
        (tu/send-v5! dying {:packet-type :DISCONNECT
                            :reason-code MqttReasonCode/NORMAL_DISCONNECTION})
        (is (nil? (tu/take! (:ch sub) 1200)))
        (finally (tu/close! dying sub))))))

(deftest ^:portable disconnect-with-will-message-fires-it
  (testing "reason code 0x04 asks for the will to be published after all"
    ;; §3.14.2.1, and the only way to say it — in 3.1.1 a client that wanted
    ;; its will published had to drop the socket and hope.
    (let [topic (tu/topic "will-0x04")
          dying (tu/connect-v5! "wills" :will {:will-retain false :will-topic topic
                                               :will-message "goodbye"
                                               :will-qos 0})
          sub   (tu/connect-v5! "wills-sub")]
      (try
        (tu/send-v5! sub (subscribe-msg topic))
        (tu/expect! (:ch sub) :SUBACK)
        (tu/send-v5! dying {:packet-type :DISCONNECT
                            :reason-code MqttReasonCode/DISCONNECT_WITH_WILL_MESSAGE})
        (let [msg (tu/expect! (:ch sub) :PUBLISH 3000)]
          (is (= topic (:topic msg)))
          (is (= "goodbye" (tu/payload-str msg))))
        (finally (tu/close! dying sub))))))

;; ── the server saying why ─────────────────────────────────────────────

;; Not ^:portable: pins mqtt-kat's choice. 0x94 where 0x82 Protocol Error is as good; Mosquitto sends 0x82.
(deftest an-undeclared-topic-alias-is-answered-with-a-reason-code
  (testing "0x94, rather than the message being dropped in silence"
    ;; §3.3.2.3.4. This was left open when topic aliases landed: an alias
    ;; nobody declared was logged and discarded, so the publisher had no way
    ;; to learn that its message went nowhere.
    (let [c (tu/connect-v5! "bad-alias")]
      (try
        (tu/send-v5! c {:packet-type :PUBLISH :topic "" :qos 0
                        :payload (.getBytes "orphan" "UTF-8")
                        :retain? false :duplicate? false
                        :properties {:topic-alias 9}})
        (let [msg (tu/expect! (:ch c) :DISCONNECT 3000)]
          (is (= 0x94 (bit-and (long (:reason-code msg)) 0xff))
              "topic alias invalid"))
        (finally (tu/close! c))))))

(deftest ^:portable a-malformed-packet-is-answered-with-a-reason-code
  (testing "0x81, rather than the connection simply going away"
    ;; §4.13. A 3.1.1-shaped SUBSCRIBE on a version 5 connection is malformed —
    ;; the topic filter's length prefix is read as the property block that
    ;; should be there. Until now the broker logged it and dropped the
    ;; connection, and the client saw only a closed socket.
    (let [c (tu/connect-v5! "malformed")]
      (try
        ;; Deliberately not tu/send-v5!.
        (client/send-message (:client c) (subscribe-msg (tu/topic "malformed")))
        (let [msg (tu/expect! (:ch c) :DISCONNECT 3000)]
          (is (= 0x81 (bit-and (long (:reason-code msg)) 0xff))
              "malformed packet"))
        (finally (tu/close! c)))))

  (testing "but a 3.1.1 client is only closed, because it could not read one"
    ;; There is no server-to-client DISCONNECT in 3.1.1, so sending one would
    ;; be a packet the client has no case for.
    ;;
    ;; Raw bytes rather than a message map: the encoders will not produce a
    ;; malformed packet, which is the point of them. This is a PUBLISH whose
    ;; topic claims 255 bytes inside a 4 byte body — exactly the overrun
    ;; decodeUTF8 was taught to refuse — so the broker raises the same
    ;; MqttProtocolError it answers a version 5 client for.
    (let [c (tu/connect! "malformed-v4")]
      (try
        (.sendMessage ^org.mqttkat.client.MqttClient (:client c)
                      (java.nio.ByteBuffer/wrap
                       (byte-array (map unchecked-byte [0x30 0x04 0x00 0xFF 0x41 0x42]))))
        (is (nil? (tu/take! (:ch c) 900))
            "no DISCONNECT packet for a 3.1.1 client")
        (finally (tu/close! c))))))
