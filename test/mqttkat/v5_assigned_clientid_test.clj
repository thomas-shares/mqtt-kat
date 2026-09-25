(ns mqttkat.v5-assigned-clientid-test
  "Assigned Client Identifier (§3.2.2.3.7).

   A client may send a zero-length client id, meaning \"you name me\". In 3.1.1
   the client then had no way of learning the name, which made it useless for
   anything that needed to be addressed. Version 5 gives the server somewhere
   to put it, and requires it to."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [mqttkat.client :as client]
            [mqttkat.test-util :as tu]))

(use-fixtures :once tu/broker-fixture)

(deftest ^:portable a-zero-length-id-is-given-one
  (testing "the CONNACK names the client"
    (let [c (tu/connect-v5! nil :id "")]
      (try
        (let [assigned (:assigned-client-identifier (:properties (:connack c)))]
          (is (zero? (bit-and (long (or (:reason-code (:connack c)) 0)) 0xff))
              "a successful CONNACK")
          (is (string? assigned) "an identifier came back")
          (is (seq assigned) "and it is not itself empty"))
        (finally (tu/close! c))))))

(deftest ^:portable two-clients-are-given-different-ones
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

(deftest ^:portable a-named-client-is-not-given-one
  (testing "§3.2.2.3.7: only sent when the server assigned the id"
    (let [c (tu/connect-v5! "named")]
      (try
        (is (nil? (:assigned-client-identifier (:properties (:connack c)))))
        (finally (tu/close! c))))))

(deftest ^:portable an-assigned-client-can-be-published-to
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

(deftest ^:portable a-zero-length-id-may-resume-a-session-in-version-5
  (testing "§3.1.3.1: version 5 dropped 3.1.1's rejection of that combination"
    ;; 3.1.1 §3.1.3.1 required a zero-length client id to come with CleanSession
    ;; 1, and rejected it otherwise with return code 0x02 — there was no way to
    ;; tell the client what it had been named, so a session stored under that
    ;; name was unreachable. Version 5 has Assigned Client Identifier, so the
    ;; restriction went: the server names the client, tells it, and the session
    ;; is addressable. The broker was still applying the old rule to version 5.
    (let [c (tu/connect-v5! nil :id "" :clean-session? false
                            :properties {:session-expiry-interval 60})]
      (try
        (is (zero? (bit-and (long (or (:reason-code (:connack c)) 0)) 0xff))
            "accepted, not refused")
        (is (seq (:assigned-client-identifier (:properties (:connack c))))
            "and told the name it was given, which is what makes it resumable")
        (finally (tu/close! c))))))

(deftest ^:portable a-zero-length-id-still-needs-a-clean-session-in-version-4
  (testing "3.1.1 §3.1.3.1: rejected, because there is nowhere to put the name"
    (let [{:keys [client ch]} (tu/client! 16 false)]
      (try
        (client/send-message client {:packet-type      :CONNECT
                                     :protocol-name    "MQTT"
                                     :protocol-version 4
                                     :keep-alive       0
                                     :clean-session?   false
                                     :client-id        ""})
        (let [ack (tu/expect! ch :CONNACK 3000)]
          (is (= 0x02 (long (:connect-return-code ack)))
              "identifier rejected"))
        (finally (try (.close ^org.mqttkat.client.MqttClient client) (catch Exception _ nil)))))))
