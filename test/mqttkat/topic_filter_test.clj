(ns mqttkat.topic-filter-test
  "Topic filter validity (§4.7.1).

   The two wildcards each take a whole level. `sport/#` is a filter; `sport#`
   is not, and neither is `sport/#/tennis` — `#` matches the rest of the topic,
   so there is nothing a level after it could mean. A server that accepts them
   anyway has taken on a subscription it can never match correctly, and told
   the client it succeeded."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [mqttkat.client :as client]
            [mqttkat.handlers :as h]
            [mqttkat.test-util :as tu]))

(use-fixtures :once tu/broker-fixture)

(deftest what-is-a-valid-filter
  (testing "ordinary filters and both wildcards used properly"
    (doseq [f ["sport" "sport/tennis" "sport/#" "#" "+" "sport/+" "+/tennis"
               "sport/+/player1" "/" "/finance" "sport/tennis/+/#"
               "$SYS/broker/uptime"]]
      (is (h/valid-topic-filter? f) f)))

  (testing "# must be the last level, and a level of its own"
    (doseq [f ["sport#" "sport/#/tennis" "#/sport" "sport/te#nnis" "##"]]
      (is (not (h/valid-topic-filter? f)) f)))

  (testing "+ must be a level of its own"
    (doseq [f ["sport+" "sp+ort" "sport/tennis+" "++"]]
      (is (not (h/valid-topic-filter? f)) f)))

  (testing "and a filter must be at least one character (§4.7.3)"
    (is (not (h/valid-topic-filter? "")))
    (is (not (h/valid-topic-filter? nil)))))

(deftest ^{:portable true
           :diverges-on-mosquitto "Mosquitto disconnects with 0x81 Malformed Packet instead of a per-filter 0x8F"}
  a-bad-filter-is-refused-on-its-own-line
  (testing "§3.9.3: one reason code per filter, the good ones still granted"
    ;; Not a disconnect: the other filters in the packet may be perfectly good,
    ;; and the SUBACK has a place to say so for each of them.
    (let [good (tu/topic "filter-good")
          c    (tu/connect-v5! "filter-sub")]
      (try
        (tu/send-v5! c {:packet-type :SUBSCRIBE :packet-identifier 1
                        :topics [{:qos 1 :topic-filter good}
                                 {:qos 1 :topic-filter "bad/#/filter"}
                                 {:qos 0 :topic-filter (str good "/+")}]})
        (let [ack (tu/expect! (:ch c) :SUBACK)
              rcs (mapv #(bit-and (long %) 0xff) (:response ack))]
          (is (= 1 (nth rcs 0)) "granted at the QoS asked for")
          (is (= 0x8F (nth rcs 1)) "topic filter invalid")
          (is (= 0 (nth rcs 2)) "and the one after it is unaffected"))
        (finally (tu/close! c))))))

(deftest a-refused-filter-is-not-subscribed
  (testing "the reason code is not the only thing that has to be right"
    ;; A filter refused in the SUBACK but inserted into the trie anyway would
    ;; deliver messages for a subscription the client was told it did not have.
    (let [c (tu/connect-v5! "filter-not-added")]
      (try
        (tu/send-v5! c {:packet-type :SUBSCRIBE :packet-identifier 1
                        :topics [{:qos 0 :topic-filter "nope/#/x"}]})
        (tu/expect! (:ch c) :SUBACK)
        (is (empty? (filter #(= "nope/#/x" (:filter %))
                            (get-in @h/*clients* [(:client-key (:connack c))
                                                  :subscribed-topics])))
            "nothing was stored for it")
        (finally (tu/close! c))))))

(deftest ^{:portable true
           :diverges-on-mosquitto "Mosquitto closes the connection instead of answering 0x80"}
  a-version-4-client-gets-the-3-1-1-failure-code
  (testing "§3.9.3: 3.1.1 has one failure code, 0x80"
    ;; 0x8F means nothing to a 3.1.1 client — the only value it knows for
    ;; failure is 0x80, and anything else is a return code it cannot read.
    (let [c (tu/connect! "filter-v4" :ordered? true)]
      (try
        (client/send-message
         (:client c) {:packet-type :SUBSCRIBE :packet-identifier 1
                      :topics [{:qos 0 :topic-filter "bad/#/filter"}]})
        (let [ack (tu/expect! (:ch c) :SUBACK)]
          (is (= 0x80 (bit-and (long (first (:response ack))) 0xff))))
        (finally (tu/close! c))))))
