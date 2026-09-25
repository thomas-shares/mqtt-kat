(ns mqttkat.v5-flow-control-test
  "MQTT 5.0 Receive Maximum (§3.1.2.11.3, §4.9).

   A client says in its CONNECT how many QoS 1 or 2 messages it is willing to
   have in flight at once, and the server must not exceed it. The broker
   already had a window for this — a fixed 128 for everyone — so what version 5
   adds is that the client chooses the number.

   Written before the implementation."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [mqttkat.test-util :as tu]))

(use-fixtures :once tu/broker-fixture)

(defn- drain
  "Every packet that arrives within `ms` of quiet, so a test can count what the
   broker was willing to send rather than assume a number and wait for it."
  [ch ms]
  (loop [seen []]
    (if-let [msg (tu/take! ch ms)]
      (recur (conj seen msg))
      seen)))

(deftest ^:portable the-broker-honours-a-clients-receive-maximum
  (testing "no more unacknowledged QoS 1 deliveries than the client allowed"
    ;; §4.9. The test subscriber never acknowledges, so the window fills and
    ;; stays full: whatever arrives is exactly what the broker was prepared to
    ;; have outstanding.
    (let [topic (tu/topic "recv-max")
          sub   (tu/connect-v5! "rm-sub" :properties {:receive-maximum 2})
          pub   (tu/connect-v5! "rm-pub")]
      (try
        (tu/send-v5! sub {:packet-type :SUBSCRIBE :packet-identifier 1
                          :topics [{:qos 1 :topic-filter topic}]})
        (tu/expect! (:ch sub) :SUBACK)
        (dotimes [i 6]
          (tu/send-v5! pub {:packet-type :PUBLISH :topic topic :qos 1
                            :packet-identifier (inc i)
                            :payload (.getBytes (str "m" i) "UTF-8")
                            :retain? false :duplicate? false}))
        (let [delivered (filter #(= :PUBLISH (:packet-type %)) (drain (:ch sub) 900))]
          (is (= 2 (count delivered))
              "two in flight, because that is what the client asked for"))
        (finally (tu/close! sub pub)))))

  (testing "and acknowledging one lets the next through"
    ;; The window has to reopen, or a client that set a small maximum would
    ;; simply stop receiving after the first burst.
    (let [topic (tu/topic "recv-max-open")
          sub   (tu/connect-v5! "rmo-sub" :properties {:receive-maximum 1})
          pub   (tu/connect-v5! "rmo-pub")]
      (try
        (tu/send-v5! sub {:packet-type :SUBSCRIBE :packet-identifier 1
                          :topics [{:qos 1 :topic-filter topic}]})
        (tu/expect! (:ch sub) :SUBACK)
        (dotimes [i 3]
          (tu/send-v5! pub {:packet-type :PUBLISH :topic topic :qos 1
                            :packet-identifier (inc i)
                            :payload (.getBytes (str "m" i) "UTF-8")
                            :retain? false :duplicate? false}))
        (let [first-batch (filter #(= :PUBLISH (:packet-type %)) (drain (:ch sub) 700))]
          (is (= 1 (count first-batch)) "one at a time")
          (tu/send-v5! sub {:packet-type :PUBACK
                            :packet-identifier (:packet-identifier (first first-batch))})
          (let [next-batch (filter #(= :PUBLISH (:packet-type %)) (drain (:ch sub) 900))]
            (is (= 1 (count next-batch)) "the window reopened for exactly one more")
            (is (not= (:payload (first first-batch)) (:payload (first next-batch)))
                "and it is the next message, not the same one again")))
        (finally (tu/close! sub pub))))))

(deftest ^:portable a-client-that-asks-for-nothing-gets-the-brokers-default
  (testing "no receive maximum in the CONNECT means the broker's own window"
    ;; §3.1.2.11.3: absent means 65,535. The broker's own window is smaller
    ;; than that and is what actually applies, but the point of this test is
    ;; that a client which says nothing is not limited to some tiny number.
    (let [topic (tu/topic "recv-default")
          sub   (tu/connect-v5! "rd-sub")
          pub   (tu/connect-v5! "rd-pub")]
      (try
        (tu/send-v5! sub {:packet-type :SUBSCRIBE :packet-identifier 1
                          :topics [{:qos 1 :topic-filter topic}]})
        (tu/expect! (:ch sub) :SUBACK)
        (dotimes [i 6]
          (tu/send-v5! pub {:packet-type :PUBLISH :topic topic :qos 1
                            :packet-identifier (inc i)
                            :payload (.getBytes (str "m" i) "UTF-8")
                            :retain? false :duplicate? false}))
        (let [delivered (filter #(= :PUBLISH (:packet-type %)) (drain (:ch sub) 900))]
          (is (= 6 (count delivered))
              "all six, because nothing asked for a smaller window"))
        (finally (tu/close! sub pub))))))

(deftest ^:portable the-broker-states-its-own-receive-maximum
  (testing "the CONNACK says how much the broker will accept in flight"
    ;; §3.2.2.3.3. A client that is not told assumes 65,535 and may flood.
    (let [c (tu/connect-v5! "rm-advert")]
      (try
        (let [props (:properties (:connack c))]
          (is (contains? props :receive-maximum))
          (is (pos? (:receive-maximum props)))
          (is (<= (:receive-maximum props) 65535)))
        (finally (tu/close! c))))))

(deftest ^:portable a-version-4-subscriber-is-unaffected
  (testing "3.1.1 has no receive maximum, so the broker's own window applies"
    (let [topic (tu/topic "recv-v4")
          sub   (tu/connect! "rv4-sub" :ordered? true :buffer 32)
          pub   (tu/connect-v5! "rv4-pub")]
      (try
        (tu/send-v5! pub {:packet-type :SUBSCRIBE :packet-identifier 99
                          :topics [{:qos 0 :topic-filter (tu/topic "unused")}]})
        (tu/expect! (:ch pub) :SUBACK)
        (mqttkat.client/send-message
         (:client sub) {:packet-type :SUBSCRIBE :packet-identifier 1
                        :topics [{:qos 1 :topic-filter topic}]})
        (tu/expect! (:ch sub) :SUBACK)
        (dotimes [i 4]
          (tu/send-v5! pub {:packet-type :PUBLISH :topic topic :qos 1
                            :packet-identifier (+ 10 i)
                            :payload (.getBytes (str "m" i) "UTF-8")
                            :retain? false :duplicate? false}))
        (let [delivered (filter #(= :PUBLISH (:packet-type %)) (drain (:ch sub) 900))]
          (is (= 4 (count delivered))))
        (finally (tu/close! sub pub))))))
