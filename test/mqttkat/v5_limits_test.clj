(ns mqttkat.v5-limits-test
  "The two limits a version 5 client and server negotiate about size and time:
   Maximum Packet Size (§3.1.2.11.4) and Server Keep Alive (§3.2.2.3.5)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [mqttkat.test-util :as tu]))

(use-fixtures :once tu/broker-fixture)

(defn- subscribe! [c topic]
  (tu/send-v5! c {:packet-type :SUBSCRIBE :packet-identifier 1
                  :topics [{:qos 2 :topic-filter topic}]})
  (tu/expect! (:ch c) :SUBACK))

(defn- publish! [c topic payload qos]
  (tu/send-v5! c (cond-> {:packet-type :PUBLISH :topic topic :qos qos
                          :payload payload :retain? false :duplicate? false}
                   (pos? qos) (assoc :packet-identifier 9))))

;; ── Maximum Packet Size ──────────────────────────────────────────────────

(deftest a-packet-over-the-clients-maximum-is-not-sent
  (testing "§3.1.2.11.4: the server discards it rather than sending it"
    ;; \"Where a Packet is too large to send, the Server MUST discard it and
    ;; behave as if it had completed delivery of the message.\" The client asked
    ;; not to be sent anything bigger; sending it anyway is a protocol error the
    ;; client would have to disconnect over, so silence is the required answer.
    (let [topic (tu/topic "maxsize")
          sub   (tu/connect-v5! "maxsize-sub" :properties {:maximum-packet-size 128})
          pub   (tu/connect-v5! "maxsize-pub")]
      (try
        (subscribe! sub topic)
        (doseq [qos [0 1]]
          (publish! pub topic (.getBytes "small" "UTF-8") qos)
          (let [m (tu/expect-eventually! (:ch sub) :PUBLISH 3000)]
            (is (= "small" (tu/payload-str m)) (str "the small one arrives, qos " qos))
            (when (pos? (long (:qos m)))
              (tu/send-v5! sub {:packet-type :PUBACK
                                :packet-identifier (:packet-identifier m)})))
          (publish! pub topic (byte-array 400 (byte 46)) qos)
          (is (nil? (tu/take! (:ch sub) 900))
              (str "the large one is discarded, qos " qos)))
        (finally (tu/close! sub pub))))))

(deftest the-publisher-is-still-acknowledged
  (testing "a discarded delivery is not the publisher's problem"
    ;; §3.1.2.11.4 says to behave as if delivery had completed, and §4.3.2 makes
    ;; the PUBACK the receiver's answer for the packet rather than a report on
    ;; delivery. A publisher left waiting would retry for ever.
    (let [topic (tu/topic "maxsize-ack")
          sub   (tu/connect-v5! "maxsize-ack-sub" :properties {:maximum-packet-size 128})
          pub   (tu/connect-v5! "maxsize-ack-pub")]
      (try
        (subscribe! sub topic)
        (publish! pub topic (byte-array 400 (byte 46)) 1)
        (is (= :PUBACK (:packet-type (tu/expect-eventually! (:ch pub) :PUBACK 3000))))
        (finally (tu/close! sub pub))))))

(deftest discarding-does-not-consume-the-window
  (testing "the packet identifier comes back"
    ;; The identifier is reserved before the packet is built. Dropping the send
    ;; without releasing it would leak one per oversized message, and after
    ;; enough of them that subscriber's window would be full of messages that
    ;; were never sent.
    (let [topic (tu/topic "maxsize-window")
          sub   (tu/connect-v5! "maxsize-window-sub"
                                :properties {:maximum-packet-size 128 :receive-maximum 2})
          pub   (tu/connect-v5! "maxsize-window-pub")]
      (try
        (subscribe! sub topic)
        ;; More oversized messages than the window could hold if they leaked.
        (dotimes [_ 6]
          (publish! pub topic (byte-array 400 (byte 46)) 1)
          (tu/expect-eventually! (:ch pub) :PUBACK 3000))
        ;; A small one still gets through, which it could not if the window
        ;; were full of phantoms.
        (publish! pub topic (.getBytes "after" "UTF-8") 1)
        (let [m (tu/expect-eventually! (:ch sub) :PUBLISH 3000)]
          (is (= "after" (tu/payload-str m))))
        (finally (tu/close! sub pub))))))

(deftest a-client-that-sets-no-maximum-gets-everything
  (testing "the limit is absent by default"
    (let [topic (tu/topic "nomax")
          sub   (tu/connect-v5! "nomax-sub")
          pub   (tu/connect-v5! "nomax-pub")]
      (try
        (subscribe! sub topic)
        (publish! pub topic (byte-array 4000 (byte 46)) 0)
        (let [m (tu/expect-eventually! (:ch sub) :PUBLISH 3000)]
          (is (= 4000 (alength ^bytes (:payload m)))))
        (finally (tu/close! sub pub))))))

;; ── Server Keep Alive ────────────────────────────────────────────────────

(deftest a-long-keep-alive-is-brought-down
  (testing "§3.2.2.3.5: the server's number wins, and it says so"
    (let [c (tu/connect-v5! "ka-long" :keep-alive 120)]
      (try
        (is (= 60 (long (:server-keep-alive (:properties (:connack c)))))
            "the client must use this instead of its own")
        (finally (tu/close! c))))))

(deftest a-short-keep-alive-is-left-alone
  (testing "the client asked for something the server can live with"
    ;; Only sent when the server is overriding. A client told its own number
    ;; back learns nothing, and §3.2.2.3.5 has it use its own when absent.
    (let [c (tu/connect-v5! "ka-short" :keep-alive 5)]
      (try
        (is (nil? (:server-keep-alive (:properties (:connack c)))))
        (finally (tu/close! c))))))

(deftest a-version-4-client-is-told-nothing
  (testing "3.1.1 has nowhere to put it"
    (let [c (tu/connect! "ka-v4" :keep-alive 120)]
      (try
        (is (not (contains? (:connack c) :properties)))
        (finally (tu/close! c))))))
