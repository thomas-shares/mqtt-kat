(ns mqttkat.v5-qos2-delivery-test
  "Who a QoS 2 message is delivered to, as opposed to how.

   A QoS 2 publish fans out from the PUBREL handler rather than from the
   PUBLISH one (§4.3.3), and for a while that second call site used the raw
   trie match instead of subscribers-for. No Local, shared-subscription
   selection and per-client coalescing were all applied at QoS 0 and 1 and
   silently skipped at QoS 2. qos2-test covers the handshake itself; these pin
   the three delivery rules on the QoS 2 path, so the two call sites cannot
   drift apart again unnoticed."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [mqttkat.test-util :as tu]))

(use-fixtures :once tu/broker-fixture)

(defn- subscribe! [c filter & {:keys [no-local? id]}]
  (tu/send-v5! c (cond-> {:packet-type :SUBSCRIBE :packet-identifier 1
                          :topics [(cond-> {:qos 2 :topic-filter filter}
                                     no-local? (assoc :no-local? true))]}
                   id (assoc :properties {:subscription-identifiers [id]})))
  (tu/expect! (:ch c) :SUBACK))

(defn- publish-qos-2!
  "Publish at QoS 2 and finish the sender's half of the handshake. Nothing is
   delivered until the PUBREL, so stopping at the PUBLISH would make every
   'nothing arrived' assertion below pass for the wrong reason."
  [c topic text id]
  (tu/send-v5! c {:packet-type :PUBLISH :topic topic :qos 2
                  :payload (.getBytes ^String text "UTF-8")
                  :retain? false :duplicate? false :packet-identifier id})
  (is (some? (tu/expect-eventually! (:ch c) :PUBREC 2000)) "PUBREC for the publish")
  (tu/send-v5! c {:packet-type :PUBREL :packet-identifier id})
  (is (some? (tu/expect-eventually! (:ch c) :PUBCOMP 2000)) "PUBCOMP for the release"))

(defn- received
  "The payloads of every PUBLISH reaching `c` within a quiet window, finishing
   the receiver's half of each QoS 2 handshake as it goes so that one
   unacknowledged delivery does not hold back the next."
  [c]
  (loop [got []]
    (if-let [m (tu/take! (:ch c) 600)]
      (case (:packet-type m)
        :PUBLISH (do (when (= 2 (long (:qos m)))
                       (tu/send-v5! c {:packet-type :PUBREC
                                       :packet-identifier (:packet-identifier m)}))
                     (recur (conj got (tu/payload-str m))))
        :PUBREL  (do (tu/send-v5! c {:packet-type :PUBCOMP
                                     :packet-identifier (:packet-identifier m)})
                     (recur got))
        (recur got))
      got)))

(deftest ^:portable no-local-applies-at-qos-2
  (testing "a No Local subscriber is not sent its own QoS 2 message"
    ;; §3.8.3.1. The ordinary subscriber is the control: it shows the message
    ;; really was released and delivered, so the empty result for the
    ;; publisher is No Local at work and not a handshake that never finished.
    (let [topic (tu/topic "qos2-no-local")
          self  (tu/connect-v5! "q2nl-self")
          other (tu/connect-v5! "q2nl-other")]
      (try
        (subscribe! self topic :no-local? true)
        (subscribe! other topic)
        (publish-qos-2! self topic "mine" 21)
        (is (= ["mine"] (received other)) "everyone else still gets it")
        (is (= [] (received self)) "but not the client that published it")
        (finally (tu/close! self other)))))

  (testing "and still receives QoS 2 messages from anyone else"
    ;; No Local filters on the publisher, not on the subscription as a whole.
    (let [topic (tu/topic "qos2-no-local-other")
          self  (tu/connect-v5! "q2nl2-self")
          pub   (tu/connect-v5! "q2nl2-pub")]
      (try
        (subscribe! self topic :no-local? true)
        (publish-qos-2! pub topic "theirs" 22)
        (is (= ["theirs"] (received self)))
        (finally (tu/close! self pub))))))

(deftest ^:portable a-shared-group-gets-one-copy-at-qos-2
  (testing "one QoS 2 message reaches exactly one member of the group"
    ;; §4.8.2. Without select-shared on this path every member got a copy,
    ;; which is an ordinary subscription with a longer name.
    (let [topic  (tu/topic "qos2-shared")
          shared (str "$share/workers/" topic)
          a      (tu/connect-v5! "q2sh-a")
          b      (tu/connect-v5! "q2sh-b")
          pub    (tu/connect-v5! "q2sh-pub")]
      (try
        (subscribe! a shared)
        (subscribe! b shared)
        (publish-qos-2! pub topic "one" 31)
        (is (= 1 (+ (count (received a)) (count (received b))))
            "one message, one delivery")
        (finally (tu/close! a b pub)))))

  (testing "and several are spread across it, each delivered once"
    ;; As in the QoS 0 shared tests, the split itself is the implementation's
    ;; choice; that every message arrives exactly once, and that the group is
    ;; actually shared rather than pinned to one member, is not.
    (let [topic  (tu/topic "qos2-shared-spread")
          shared (str "$share/workers/" topic)
          a      (tu/connect-v5! "q2sp-a")
          b      (tu/connect-v5! "q2sp-b")
          pub    (tu/connect-v5! "q2sp-pub")]
      (try
        (subscribe! a shared)
        (subscribe! b shared)
        (doseq [i (range 6)]
          (publish-qos-2! pub topic (str "m" i) (+ 40 i)))
        (let [got-a (received a)
              got-b (received b)]
          (is (= (set (map #(str "m" %) (range 6))) (set (concat got-a got-b))))
          (is (= 6 (+ (count got-a) (count got-b))) "no message delivered twice")
          (is (seq got-a) "and both members were used")
          (is (seq got-b)))
        (finally (tu/close! a b pub))))))

(deftest ^:portable overlapping-subscriptions-deliver-once-at-qos-2
  (testing "§3.3.4: one copy carrying both Subscription Identifiers"
    ;; The same coalescing v5-overlapping-test pins at QoS 1, through the
    ;; PUBREL path. Two copies would be legal but is not what this broker does
    ;; at any other QoS.
    (let [topic (tu/topic "qos2-overlap")
          sub   (tu/connect-v5! "q2ov-sub")
          pub   (tu/connect-v5! "q2ov-pub")]
      (try
        (subscribe! sub topic :id 2)
        (subscribe! sub (str topic "/#") :id 3)
        (publish-qos-2! pub topic "once" 51)
        (let [m (tu/expect-eventually! (:ch sub) :PUBLISH 3000)]
          (is (= "once" (tu/payload-str m)))
          (is (= #{2 3} (set (map long (:subscription-identifiers (:properties m)))))
              "both identifiers on the one delivery")
          (tu/send-v5! sub {:packet-type :PUBREC :packet-identifier (:packet-identifier m)})
          (tu/expect-eventually! (:ch sub) :PUBREL 2000)
          (tu/send-v5! sub {:packet-type :PUBCOMP :packet-identifier (:packet-identifier m)}))
        (is (= [] (received sub)) "and nothing else behind it")
        (finally (tu/close! sub pub))))))
