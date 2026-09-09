(ns mqttkat.v5-assigned-clientid-test
  "Assigned Client Identifier (§3.2.2.3.7).

   A client may send a zero-length client id, meaning \"you name me\". In 3.1.1
   the client then had no way of learning the name, which made it useless for
   anything that needed to be addressed. Version 5 gives the server somewhere
   to put it, and requires it to."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [mqttkat.test-util :as tu]))

(use-fixtures :once tu/broker-fixture)

(deftest a-zero-length-id-is-given-one
  (testing "the CONNACK names the client"
    (let [c (tu/connect-v5! nil :id "")]
      (try
        (let [assigned (:assigned-client-identifier (:properties (:connack c)))]
          (is (zero? (bit-and (long (or (:reason-code (:connack c)) 0)) 0xff))
              "a successful CONNACK")
          (is (string? assigned) "an identifier came back")
          (is (seq assigned) "and it is not itself empty"))
        (finally (tu/close! c))))))

(deftest two-clients-are-given-different-ones
  (testing "an assigned identifier has to be unique or it takes over a session"
    ;; §3.1.4 disconnects an existing connection holding the same id, so two
    ;; anonymous clients handed the same name would knock each other off.
    (let [a (tu/connect-v5! nil :id "")
          b (tu/connect-v5! nil :id "")]
      (try
        (let [ida (:assigned-client-identifier (:properties (:connack a)))
              idb (:assigned-client-identifier (:properties (:connack b)))]
          (is (not= ida idb)))
        ;; Both still connected: neither displaced the other.
        (is (nil? (tu/take! (:ch a) 500)) "the first was not disconnected")
        (finally (tu/close! a b))))))

(deftest a-named-client-is-not-given-one
  (testing "§3.2.2.3.7: only sent when the server assigned the id"
    (let [c (tu/connect-v5! "named")]
      (try
        (is (nil? (:assigned-client-identifier (:properties (:connack c)))))
        (finally (tu/close! c))))))

(deftest an-assigned-client-can-be-published-to
  (testing "the name is real, not decoration"
    (let [c     (tu/connect-v5! nil :id "")
          pub   (tu/connect-v5! "assign-pub")
          topic (tu/topic "assigned")]
      (try
        (tu/send-v5! c {:packet-type :SUBSCRIBE :packet-identifier 1
                        :topics [{:qos 0 :topic-filter topic}]})
        (tu/expect! (:ch c) :SUBACK)
        (tu/send-v5! pub {:packet-type :PUBLISH :topic topic :qos 0
                          :payload (.getBytes "hello" "UTF-8")
                          :retain? false :duplicate? false})
        (is (= "hello" (tu/payload-str (tu/expect-eventually! (:ch c) :PUBLISH 3000))))
        (finally (tu/close! c pub))))))
