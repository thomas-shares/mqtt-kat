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
            [mqttkat.test-util :as tu])
  (:import [org.mqttkat MqttReasonCode]))

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

(deftest what-each-malformed-filter-is-answered-with
  (testing "a well-formed filter, shared or not, has nothing wrong with it"
    (doseq [f ["sport/#" "+" "$SYS/#" "$share/g/sport/#" "$share/g/+"]]
      (is (nil? (h/subscription-filter-error f)) f)))

  (testing "0x81 Malformed Packet: a wildcard out of place, or no filter at all"
    (doseq [f ["" "sport#" "sport/#/tennis" "sp+ort" "##"
               "$share/a+b/topic" "$share/a#b/topic" "$share/g/bad/#/x" "$share/g/sp+ort"]]
      (is (= MqttReasonCode/MALFORMED_PACKET (h/subscription-filter-error f)) f)))

  (testing "0x82 Protocol Error: a share missing its group or its filter"
    (doseq [f ["$share/" "$share/group" "$share//topic" "$share/group/"]]
      (is (= MqttReasonCode/PROTOCOL_ERROR (h/subscription-filter-error f)) f))))

(defn- expect-disconnect!
  "The DISCONNECT a malformed SUBSCRIBE earns, and the socket closed after it.
   Returns its reason code as an unsigned int."
  [c filter-str]
  (let [msg (tu/take! (:ch c) 3000)]
    (is (= :DISCONNECT (:packet-type msg))
        (str filter-str ": expected a DISCONNECT, got " (pr-str (dissoc msg :client-key))))
    (is (tu/wait-until #(not (client/connected? (:client c))) 3000)
        (str filter-str ": and the connection closed"))
    (some-> (:reason-code msg) long (bit-and 0xff))))

(deftest ^:portable a-malformed-filter-disconnects-a-version-5-client
  (testing "§4.7.1: a wildcard out of place is a Malformed Packet (0x81)"
    ;; Not a per-filter 0x8F. That code is for a filter that is "correctly
    ;; formed" but not accepted; these are not correctly formed. Mosquitto
    ;; answers them the same way.
    (doseq [bad ["" "sport#" "sport/#/tennis" "sp+ort"]]
      (let [c (tu/connect-v5! "filter-malformed")]
        (try
          (tu/send-v5! c {:packet-type :SUBSCRIBE :packet-identifier 1
                          :topics [{:qos 0 :topic-filter bad}]})
          (is (= 0x81 (expect-disconnect! c bad)) (pr-str bad))
          (finally (tu/close! c)))))))

(deftest ^:portable one-malformed-filter-ends-the-whole-subscribe
  (testing "the good filters beside it get no SUBACK either"
    (let [good (tu/topic "filter-good")
          c    (tu/connect-v5! "filter-mixed")]
      (try
        (tu/send-v5! c {:packet-type :SUBSCRIBE :packet-identifier 1
                        :topics [{:qos 1 :topic-filter good}
                                 {:qos 1 :topic-filter "bad/#/filter"}
                                 {:qos 0 :topic-filter (str good "/+")}]})
        (is (= 0x81 (expect-disconnect! c "bad/#/filter")))
        (finally (tu/close! c))))))

(deftest ^:portable a-malformed-subscribe-leaves-nothing-subscribed
  (testing "a session that outlives the connection holds none of the packet"
    ;; A persistent session, so a subscription taken from the packet before
    ;; the broker noticed the bad filter would still be there on reconnect,
    ;; delivering to a client that was never told it had it.
    (let [id    (tu/client-id "filter-nothing-kept")
          good  (tu/topic "filter-kept")
          props {:session-expiry-interval 60}
          a     (tu/connect-v5! nil :id id :clean-session? false :properties props)]
      (try
        (tu/send-v5! a {:packet-type :SUBSCRIBE :packet-identifier 1
                        :topics [{:qos 0 :topic-filter good}
                                 {:qos 0 :topic-filter "nope/#/x"}]})
        (expect-disconnect! a "nope/#/x")
        (finally (tu/close! a)))
      (let [b   (tu/connect-v5! nil :id id :clean-session? false :properties props)
            pub (tu/connect-v5! "filter-kept-pub")]
        (try
          (tu/send-v5! pub {:packet-type :PUBLISH :topic good :qos 0
                            :payload (.getBytes "should not arrive" "UTF-8")
                            :retain? false :duplicate? false})
          (is (nil? (tu/take! (:ch b) 1000))
              "the good filter from the malformed packet was not subscribed")
          (finally
            ;; Expiry 0 on the way out, so the session goes with this test.
            (tu/send-v5! b {:packet-type :DISCONNECT
                            :properties {:session-expiry-interval 0}})
            (tu/close! b pub)))))))

(deftest ^:portable a-version-4-client-is-closed-on
  (testing "3.1.1 §4.8: a protocol violation closes the connection"
    ;; There is no DISCONNECT from the server in 3.1.1 to say why, and no
    ;; SUBACK either: 0x80 is for a filter the server refuses, not one that
    ;; breaks the protocol.
    (let [c (tu/connect! "filter-v4" :ordered? true)]
      (try
        (client/send-message
         (:client c) {:packet-type :SUBSCRIBE :packet-identifier 1
                      :topics [{:qos 0 :topic-filter "bad/#/filter"}]})
        (is (nil? (tu/take! (:ch c) 1000)) "no SUBACK, and nothing else")
        (is (tu/wait-until #(not (client/connected? (:client c))) 3000)
            "the connection is closed")
        (finally (tu/close! c))))))
