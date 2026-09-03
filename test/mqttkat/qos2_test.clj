(ns mqttkat.qos2-test
  "The QoS 2 exchange, end to end against a real broker.

   Nothing in the default suite reached these paths before: `qos-2`,
   `qos-2-send`, `pubrec`, `pubrel` and `pubcomp` had only their defn lines
   executed, so the whole four-packet handshake and the *inflight* map it turns
   on were running for the first time in production. The wire format was
   covered — core-test round-trips every packet through encode and decode — but
   the format is not the protocol."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [mqttkat.client :as client]
            [mqttkat.handlers :as h]
            [mqttkat.test-util :as tu]))

(use-fixtures :once tu/broker-fixture)

(defn- subscribe-msg [topic qos id]
  {:packet-type :SUBSCRIBE :packet-identifier id
   :topics [{:qos qos :topic-filter topic}]})

(defn- publish-msg [topic payload qos id]
  {:packet-type :PUBLISH :qos qos :topic topic :retain? false
   :duplicate false :payload payload :packet-identifier id})

(deftest qos-2-publish-completes-its-handshake
  (testing "PUBLISH -> PUBREC -> PUBREL -> PUBCOMP, and only then delivery"
    (let [topic   (tu/topic "qos2")
          payload "a qos 2 message"
          id      4242
          sub     (tu/connect! "qos2-sub")
          pub     (tu/connect! "qos2-pub")]
      (client/send-message (:client sub) (subscribe-msg topic 2 1))
      (tu/expect! (:ch sub) :SUBACK)

      (client/send-message (:client pub) (publish-msg topic payload 2 id))
      (let [rec (tu/expect-eventually! (:ch pub) :PUBREC 2000)]
        (is (= id (:packet-identifier rec))
            "PUBREC must carry the identifier the publish was sent with"))

      ;; MQTT 3.1.1 §4.3.3: the broker owns the message once it has sent
      ;; PUBREC, but must not deliver it until the sender releases it. This
      ;; broker holds it in *inflight* and fans out from the pubrel handler.
      (is (nil? (tu/take! (:ch sub) 300))
          "nothing should reach the subscriber before PUBREL")

      (client/pubrel (:client pub) id)
      (let [comp (tu/expect-eventually! (:ch pub) :PUBCOMP 2000)]
        (is (= id (:packet-identifier comp))))

      (let [msg (tu/expect-eventually! (:ch sub) :PUBLISH 2000)]
        (is (= topic (:topic msg)))
        (is (= 2 (:qos msg)) "a subscriber that asked for QoS 2 should get QoS 2")
        (is (= payload (tu/payload-str msg)))

        ;; The delivery half: the broker is now the sender and owes the
        ;; subscriber the other three packets.
        (client/pubrec (:client sub) (:packet-identifier msg))
        (let [rel (tu/expect-eventually! (:ch sub) :PUBREL 2000)]
          (is (= (:packet-identifier msg) (:packet-identifier rel))
              "PUBREL must answer the identifier the broker delivered under")
          (client/pubcomp (:client sub) (:packet-identifier rel))))

      (tu/close! sub)
      (tu/close! pub))))

(deftest qos-2-releases-its-packet-identifier
  (testing "a completed QoS 2 delivery leaves nothing outstanding"
    ;; The identifier the broker spends delivering at QoS 2 comes back on
    ;; PUBCOMP, the same way a QoS 1 one comes back on PUBACK. If it did not,
    ;; the in-flight window would close by one message per delivery.
    (let [topic   (tu/topic "qos2-ids")
          ;; The client id is passed in rather than read back off the CONNACK:
          ;; a client decodes its CONNACK with no SelectionKey, so :client-key
          ;; is nil there and looking the session up by it silently found
          ;; nothing — which would have skipped the assertion below instead of
          ;; failing it.
          sub-id  (tu/client-id "qos2-id-sub")
          sub     (tu/connect! nil :id sub-id)
          pub     (tu/connect! "qos2-id-pub")]
      (client/send-message (:client sub) (subscribe-msg topic 2 1))
      (tu/expect! (:ch sub) :SUBACK)

      (dotimes [i 5]
        (let [id (+ 700 i)]
          (client/send-message (:client pub) (publish-msg topic (str "m" i) 2 id))
          (tu/expect-eventually! (:ch pub) :PUBREC 2000)
          (client/pubrel (:client pub) id)
          (tu/expect-eventually! (:ch pub) :PUBCOMP 2000)
          (let [msg (tu/expect-eventually! (:ch sub) :PUBLISH 2000)]
            (client/pubrec (:client sub) (:packet-identifier msg))
            (let [rel (tu/expect-eventually! (:ch sub) :PUBREL 2000)]
              (client/pubcomp (:client sub) (:packet-identifier rel))))))

      ;; Give the last PUBCOMP a moment to be handled before looking.
      (let [deadline (+ (System/currentTimeMillis) 2000)]
        (loop []
          (when (and (pos? (h/inflight-count sub-id))
                     (< (System/currentTimeMillis) deadline))
            (Thread/sleep 25)
            (recur))))
      (is (zero? (h/inflight-count sub-id))
          "every identifier spent on a QoS 2 delivery should have come back")

      (tu/close! sub)
      (tu/close! pub))))

(deftest qos-2-survives-a-reconnect-before-pubrel
  (testing "a persistent session finishes a QoS 2 flow left half-done"
    ;; MQTT 3.1.1 §4.4: a client that disconnects between PUBREC and PUBREL
    ;; must resend the PUBREL when it comes back, and the broker has to
    ;; complete the flow — the message is the broker's responsibility from the
    ;; moment it answered PUBREC.
    (let [topic   (tu/topic "qos2-resume")
          payload "survives the reconnect"
          id      777
          pub-id  (tu/client-id "qos2-resume-pub")
          sub     (tu/connect! "qos2-resume-sub")]
      (client/send-message (:client sub) (subscribe-msg topic 2 1))
      (tu/expect! (:ch sub) :SUBACK)

      ;; Publish, take the PUBREC, then vanish without releasing it.
      (let [a (tu/connect! nil :id pub-id :clean-session? false)]
        (client/send-message (:client a) (publish-msg topic payload 2 id))
        (is (= id (:packet-identifier (tu/expect-eventually! (:ch a) :PUBREC 2000))))
        (client/send-message (:client a) {:packet-type :DISCONNECT})
        (tu/close! a))
      (tu/wait-for-parked-session! pub-id)

      ;; Same session, and the release it never sent.
      (let [b (tu/connect! nil :id pub-id :clean-session? false)]
        (is (true? (:session-present? (:connack b)))
            "the half-finished flow means there is a session to resume")
        (client/pubrel (:client b) id)
        (is (= id (:packet-identifier (tu/expect-eventually! (:ch b) :PUBCOMP 2000)))
            "the broker owes a PUBCOMP for the release")

        (let [msg (tu/expect-eventually! (:ch sub) :PUBLISH 3000)]
          (is (some? msg) "the message the broker took responsibility for must still be delivered")
          (when msg
            (is (= topic (:topic msg)))
            (is (= payload (tu/payload-str msg)))
            (client/pubrec (:client sub) (:packet-identifier msg))
            (let [rel (tu/expect-eventually! (:ch sub) :PUBREL 2000)]
              (client/pubcomp (:client sub) (:packet-identifier rel)))))
        (tu/close! b))
      (tu/close! sub))))

(deftest acknowledgements-do-not-depend-on-there-being-subscribers
  (testing "QoS 1 and QoS 2 are answered on a topic nobody is subscribed to"
    ;; PUBACK and PUBREC are the receiver's answer for the packet, not a report
    ;; on delivery (§4.3.2, §4.3.3), so neither may depend on anyone being
    ;; subscribed. Nothing covered this before, and the dispatch only survived
    ;; its `when-let` on the matched subscribers because triennium returns an
    ;; empty *set* for no match and an empty set is truthy. A subscriber lookup
    ;; that returned nil, or a seq check added in passing, would have turned a
    ;; QoS 1 publish to an unsubscribed topic into a client retrying for ever.
    (let [pub (tu/connect! "no-subs-pub")]
      (testing "QoS 1"
        (let [topic (tu/topic "no-subs-1")]
          (client/send-message (:client pub) (publish-msg topic "unheard" 1 111))
          (let [ack (tu/expect-eventually! (:ch pub) :PUBACK 2000)]
            (is (some? ack) "a QoS 1 publish must be acknowledged with no subscribers")
            (is (= 111 (:packet-identifier ack))))))

      (testing "QoS 2"
        (let [topic (tu/topic "no-subs-2")]
          (client/send-message (:client pub) (publish-msg topic "unheard" 2 222))
          (let [rec (tu/expect-eventually! (:ch pub) :PUBREC 2000)]
            (is (some? rec) "a QoS 2 publish must be answered with no subscribers")
            (is (= 222 (:packet-identifier rec))))
          (client/pubrel (:client pub) 222)
          (let [comp (tu/expect-eventually! (:ch pub) :PUBCOMP 2000)]
            (is (some? comp) "and the release completed")
            (is (= 222 (:packet-identifier comp))))))

      (testing "a subscriber arriving later is unaffected"
        ;; The retained path is separate from the dispatch that was guarded, so
        ;; check it still works now the guard has gone.
        (let [topic (tu/topic "no-subs-retained")
              ;; Ordered: the SUBACK and the retained replay are queued back to
              ;; back by the subscribe handler, so the default client's go
              ;; blocks can surface them either way round.
              sub   (tu/connect! "no-subs-late-sub" :ordered? true)]
          (client/send-message (:client pub)
                               (assoc (publish-msg topic "kept" 1 333) :retain? true))
          (tu/expect-eventually! (:ch pub) :PUBACK 2000)
          (client/send-message (:client sub) (subscribe-msg topic 0 1))
          (tu/expect! (:ch sub) :SUBACK)
          (let [msg (tu/expect-eventually! (:ch sub) :PUBLISH 2000)]
            (is (some? msg) "a retained message published to nobody is still kept")
            (is (= "kept" (tu/payload-str msg)))
            ;; The replay goes out at the QoS it was published with rather than
            ;; the one subscribed for, so acknowledge it and leave nothing
            ;; outstanding behind this test.
            (when (= 1 (:qos msg))
              (client/puback (:client sub) (:packet-identifier msg))))
          (tu/close! sub)))

      (tu/close! pub))))
