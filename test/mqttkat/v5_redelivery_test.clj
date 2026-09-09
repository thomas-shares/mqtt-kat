(ns mqttkat.v5-redelivery-test
  "Redelivery on reconnect (§4.4).

   A QoS 1 or 2 exchange that did not finish before the connection dropped is
   the session's unfinished business: the server has taken responsibility for
   the message and has not been told it arrived, so it must send it again when
   the client comes back."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [mqttkat.test-util :as tu]))

(use-fixtures :once tu/broker-fixture)

(defn- session [id]
  (tu/connect-v5! nil :id id :clean-session? false
                  :properties {:session-expiry-interval 300}))

(deftest an-unacknowledged-message-comes-back
  (testing "§4.4: resent on the new connection, marked as a duplicate"
    (let [id    (tu/client-id "redeliver")
          topic (tu/topic "redeliver")
          a     (session id)
          pub   (tu/connect-v5! "redeliver-pub")]
      (try
        (tu/send-v5! a {:packet-type :SUBSCRIBE :packet-identifier 1
                        :topics [{:qos 1 :topic-filter topic}]})
        (tu/expect! (:ch a) :SUBACK)
        (tu/send-v5! pub {:packet-type :PUBLISH :topic topic :qos 1
                          :packet-identifier 7
                          :payload (.getBytes "unacked" "UTF-8")
                          :retain? false :duplicate? false
                          :properties {:content-type "text/plain"}})
        ;; Received and deliberately never acknowledged.
        (let [m (tu/expect-eventually! (:ch a) :PUBLISH 3000)]
          (is (= "unacked" (tu/payload-str m)))
          (is (false? (boolean (:duplicate? m))) "the first time is not a duplicate"))
        (tu/close! a)
        (tu/wait-for-parked-session! id)
        (let [b (session id)]
          (try
            (let [m (tu/expect-eventually! (:ch b) :PUBLISH 3000)]
              (is (= "unacked" (tu/payload-str m)) "sent again")
              (is (true? (boolean (:duplicate? m)))
                  "§3.3.1.1: DUP set, so the client knows it may have seen it")
              (is (= "text/plain" (:content-type (:properties m)))
                  "and it is still the same message")
              (tu/send-v5! b {:packet-type :PUBACK
                              :packet-identifier (:packet-identifier m)}))
            (finally (tu/close! b))))
        (finally (tu/close! pub))))))

(deftest a-redelivery-is-a-well-formed-version-5-packet
  (testing "the redelivered PUBLISH is in the connection's own dialect"
    ;; This is what actually broke. The redelivery loop built the packet by
    ;; hand and never set the protocol version, so no property block was
    ;; written — and a version 5 client reads the byte where that block should
    ;; have been as the start of the payload. The packet is malformed, the
    ;; client's decoder runs off the end, and nothing arrives at all.
    (let [id    (tu/client-id "redeliver-shape")
          topic (tu/topic "redeliver-shape")
          a     (session id)
          pub   (tu/connect-v5! "redeliver-shape-pub")]
      (try
        (tu/send-v5! a {:packet-type :SUBSCRIBE :packet-identifier 1
                        :topics [{:qos 1 :topic-filter topic}]
                        :properties {:subscription-identifiers [88]}})
        (tu/expect! (:ch a) :SUBACK)
        (tu/send-v5! pub {:packet-type :PUBLISH :topic topic :qos 1
                          :packet-identifier 8
                          :payload (.getBytes "shape" "UTF-8")
                          :retain? false :duplicate? false
                          :properties {:user-properties [["a" "2"]]}})
        (tu/expect-eventually! (:ch a) :PUBLISH 3000)
        (tu/close! a)
        (tu/wait-for-parked-session! id)
        (let [b (session id)]
          (try
            (let [m (tu/expect-eventually! (:ch b) :PUBLISH 3000)
                  p (:properties m)]
              (is (= "shape" (tu/payload-str m))
                  "decoded cleanly, which a missing property block prevents")
              (is (= [["a" "2"]] (mapv vec (:user-properties p))))
              (is (= [88] (mapv long (:subscription-identifiers p)))
                  "§3.3.4: the identifier is owed on a redelivery too")
              (tu/send-v5! b {:packet-type :PUBACK
                              :packet-identifier (:packet-identifier m)}))
            (finally (tu/close! b))))
        (finally (tu/close! pub))))))

(deftest an-acknowledged-message-is-not-sent-again
  (testing "the session has no unfinished business"
    (let [id    (tu/client-id "no-redeliver")
          topic (tu/topic "no-redeliver")
          a     (session id)
          pub   (tu/connect-v5! "no-redeliver-pub")]
      (try
        (tu/send-v5! a {:packet-type :SUBSCRIBE :packet-identifier 1
                        :topics [{:qos 1 :topic-filter topic}]})
        (tu/expect! (:ch a) :SUBACK)
        (tu/send-v5! pub {:packet-type :PUBLISH :topic topic :qos 1
                          :packet-identifier 9
                          :payload (.getBytes "acked" "UTF-8")
                          :retain? false :duplicate? false})
        (let [m (tu/expect-eventually! (:ch a) :PUBLISH 3000)]
          (tu/send-v5! a {:packet-type :PUBACK
                          :packet-identifier (:packet-identifier m)}))
        (Thread/sleep 300)
        (tu/close! a)
        (tu/wait-for-parked-session! id)
        (let [b (session id)]
          (try
            (is (nil? (tu/take! (:ch b) 1000)) "nothing owed, nothing sent")
            (finally (tu/close! b))))
        (finally (tu/close! pub))))))
