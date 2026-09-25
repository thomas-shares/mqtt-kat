(ns mqttkat.v5-inbound-flow-test
  "The other half of Receive Maximum (§4.9): what the broker will accept.

   Written before the implementation. The outbound half — not sending a client
   more than it asked for — went in with the flow control slice. This is the
   half the broker enforces on the client, and its absence is why the Paho
   conformance suite hangs: test_flow_control2 publishes one more QoS 2 message
   than the broker's advertised maximum and then loops until a DISCONNECT
   arrives, which never did.

     while testcallback.disconnects == []:
       receiver.receive(testcallback)

   A missing reason code is usually a client left guessing. Here it is a client
   left waiting for ever, which is worse and is what makes this the first thing
   to fix."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [mqttkat.bridge :as bridge]
            [mqttkat.handlers :as handlers]
            [mqttkat.test-util :as tu])
  (:import [org.mqttkat MqttReasonCode]))

(use-fixtures :once tu/broker-fixture)

(defn- publish-qos2!
  "A QoS 2 publish, and deliberately no PUBREL afterwards: the message stays in
   flight, which is what fills the broker's quota."
  [c id]
  (tu/send-v5! c {:packet-type :PUBLISH :topic (str "flow/" id) :qos 2
                  :packet-identifier id
                  :payload (.getBytes "x" "UTF-8")
                  :retain? false :duplicate? false}))

(defn- await-disconnect
  "The DISCONNECT, skipping the PUBRECs that arrive alongside it."
  [ch]
  (loop [n 0]
    (when (< n 400)
      (let [msg (tu/take! ch 1500)]
        (cond
          (nil? msg) nil
          (= :DISCONNECT (:packet-type msg)) msg
          :else (recur (inc n)))))))

;; Not ^:portable: pins mqtt-kat's choice. §3.3.4 says the server uses 0x93, not that it MUST; Mosquitto carries on.
(deftest exceeding-the-brokers-receive-maximum-is-refused
  (testing "one QoS 2 publish too many gets a DISCONNECT with 0x93"
    ;; §4.9: the receiver's Receive Maximum is a promise the sender must keep,
    ;; and a sender that breaks it is disconnected rather than quietly served.
    (let [c        (tu/connect-v5! "over-quota")
          quota    (:receive-maximum (:properties (:connack c)))]
      (try
        (is (pos? quota) "the broker advertises a maximum to exceed")
        (dotimes [i (inc quota)]
          (publish-qos2! c (inc i)))
        (let [msg (await-disconnect (:ch c))]
          (is (some? msg) "the broker must say something rather than go quiet")
          (is (= 0x93 (bit-and (long (:reason-code msg)) 0xff))
              "receive maximum exceeded"))
        (finally (tu/close! c))))))

(deftest ^:portable staying-inside-the-quota-is-fine
  (testing "exactly the maximum, all acknowledged, and nothing is refused"
    ;; The limit is on messages *in flight*, so completing each handshake frees
    ;; the slot. A broker that counted publishes rather than outstanding ones
    ;; would disconnect a well-behaved client that simply sent a lot.
    (let [c (tu/connect-v5! "within-quota")]
      (try
        (dotimes [i 20]
          (let [id (inc i)]
            (publish-qos2! c id)
            (tu/expect! (:ch c) :PUBREC 3000)
            ;; Completing the handshake gives the slot back.
            (tu/send-v5! c {:packet-type :PUBREL :packet-identifier id})
            (tu/expect! (:ch c) :PUBCOMP 3000)))
        (is true "twenty round trips well inside a quota of 128")
        (finally (tu/close! c))))))

(deftest ^:portable a-version-4-client-is-not-disconnected-for-it
  (testing "3.1.1 has no Receive Maximum, so there is no promise to break"
    ;; The broker still has to protect itself, but a 3.1.1 client was never
    ;; told a limit and cannot be held to one — and there is no DISCONNECT it
    ;; could read anyway.
    (let [c (tu/connect! "v4-quota" :ordered? true :buffer 512)]
      (try
        (dotimes [i 40]
          (mqttkat.client/send-message
           (:client c) {:packet-type :PUBLISH :topic (str "flow4/" i) :qos 2
                        :packet-identifier (inc i)
                        :payload (.getBytes "x" "UTF-8")
                        :retain? false :duplicate? false}))
        (let [msgs (loop [seen []]
                     (if-let [m (tu/take! (:ch c) 700)]
                       (recur (conj seen m))
                       seen))]
          (is (every? #(= :PUBREC (:packet-type %)) msgs)
              "every publish is answered, none refused")
          (is (= 40 (count msgs))))
        (finally (tu/close! c))))))

(deftest the-quota-is-what-the-broker-advertised
  (testing "the CONNACK number and the number enforced are the same"
    ;; A broker that advertises one limit and enforces another is worse than
    ;; one that advertises nothing: the client believes the first.
    (let [c (tu/connect-v5! "quota-match")]
      (try
        (is (= handlers/inflight-window
               (:receive-maximum (:properties (:connack c)))))
        (finally (tu/close! c))))))

(deftest another-brokers-bridge-gets-a-wider-quota
  (testing "advertised to a bridge, and kept to"
    ;; A bridge carries every publish from one broker to another. Held to a
    ;; client's 128 it could have 128 in flight per round trip, and QoS 1
    ;; across three brokers ran at a 6 s median where QoS 0 ran at 29 ms.
    (let [c (tu/connect-v5! "bridge-quota" :id (str bridge/client-id-prefix "quota-peer") :buffer 512)
          n (+ handlers/inflight-window 72)]
      (try
        (is (= bridge/receive-maximum (:receive-maximum (:properties (:connack c)))))
        (dotimes [i n]
          (publish-qos2! c (inc i)))
        (let [msgs (loop [seen []]
                     (if-let [m (tu/take! (:ch c) 700)]
                       (recur (conj seen m))
                       seen))]
          (is (not-any? #(= :DISCONNECT (:packet-type %)) msgs)
              "more than a client's quota in flight, and not refused")
          (is (= n (count (filter #(= :PUBREC (:packet-type %)) msgs)))))
        (finally (tu/close! c))))))
