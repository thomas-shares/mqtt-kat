(ns mqttkat.sys-test
  "The $SYS hierarchy.

   Publishing is driven by sys/publish-once! rather than sys/start! so these
   tests do not depend on a timer, and so the broker the rest of the suite
   shares is not left publishing into it."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [mqttkat.client :as client]
            [mqttkat.handlers :as h]
            [mqttkat.sys :as sys]
            [mqttkat.test-util :as tu]))

(use-fixtures :once tu/broker-fixture)

(defn- subscribe-msg [topic qos id]
  {:packet-type :SUBSCRIBE :packet-identifier id
   :topics [{:qos qos :topic-filter topic}]})

(deftest stats-cover-the-mosquitto-topics
  (testing "the topics a Mosquitto dashboard would look for are all present"
    (let [topics (set (keys (sys/stats)))]
      (doseq [expected ["$SYS/broker/version"
                        "$SYS/broker/clients/connected"
                        "$SYS/broker/clients/disconnected"
                        "$SYS/broker/clients/total"
                        "$SYS/broker/clients/maximum"
                        "$SYS/broker/clients/expired"
                        "$SYS/broker/connections/socket/count"
                        "$SYS/broker/bytes/received"
                        "$SYS/broker/bytes/sent"
                        "$SYS/broker/messages/received"
                        "$SYS/broker/messages/sent"
                        "$SYS/broker/publish/messages/dropped"
                        "$SYS/broker/packet/out/count"
                        "$SYS/broker/packet/out/bytes"
                        "$SYS/broker/retained messages/count"
                        "$SYS/broker/subscriptions/count"
                        "$SYS/broker/store/messages/count"
                        "$SYS/broker/heap/current"
                        "$SYS/broker/heap/maximum"
                        "$SYS/broker/mqtt/connect/received"
                        "$SYS/broker/mqtt/connack/sent"
                        "$SYS/broker/mqtt/publish/received"
                        "$SYS/broker/mqtt/publish/sent"
                        "$SYS/broker/mqtt/subscribe/received"
                        "$SYS/broker/mqtt/suback/sent"
                        "$SYS/broker/mqtt/pingreq/received"
                        "$SYS/broker/mqtt/pingresp/sent"
                        "$SYS/broker/mqtt/disconnect/received"]]
        (is (contains? topics expected) (str expected " should be published"))))))

(deftest counters-move-with-traffic
  (testing "the per-packet-type counters follow what actually happened"
    ;; These are counted in one place each — the framing on the way in and the
    ;; writer on the way out, off the first byte of the packet — so a test that
    ;; sends one of each is worth more than it looks: it checks the type nibble
    ;; is being read correctly, not just that a number went up.
    (let [before (sys/stats)
          topic  (tu/topic "sysc")
          c      (tu/connect! "sys-counters")]
      (client/send-message (:client c) (subscribe-msg topic 1 1))
      (tu/expect! (:ch c) :SUBACK)
      (client/send-message (:client c) {:packet-type :PUBLISH :qos 1 :topic topic
                                        :retain? false :duplicate false
                                        :payload "counted" :packet-identifier 9})
      (tu/expect-eventually! (:ch c) :PUBACK 2000)
      (let [after (sys/stats)
            grew? (fn [k] (> (get after k) (get before k)))]
        (is (grew? "$SYS/broker/mqtt/connect/received") "a CONNECT was received")
        (is (grew? "$SYS/broker/mqtt/connack/sent") "a CONNACK was sent")
        (is (grew? "$SYS/broker/mqtt/subscribe/received") "a SUBSCRIBE was received")
        (is (grew? "$SYS/broker/mqtt/suback/sent") "a SUBACK was sent")
        (is (grew? "$SYS/broker/mqtt/publish/received") "a PUBLISH was received")
        (is (grew? "$SYS/broker/mqtt/puback/sent") "a PUBACK was sent")
        (is (grew? "$SYS/broker/connections/socket/count") "a socket was accepted"))
      (tu/close! c))))

(deftest sys-topics-are-delivered-to-a-sys-subscriber
  (testing "a $SYS/# subscriber gets the values, retained"
    (let [sub (tu/connect! "sys-sub" :ordered? true :buffer 256)]
      (client/send-message (:client sub) (subscribe-msg "$SYS/#" 0 1))
      (tu/expect-eventually! (:ch sub) :SUBACK 2000)
      (sys/publish-once!)
      (let [got    (tu/take-n! (:ch sub) 20 4000)
            topics (map :topic (:PUBLISH got))]
        (is (seq topics) "something should have arrived on $SYS/#")
        (is (every? #(str/starts-with? % "$SYS/") topics)
            (str "everything delivered should be a $SYS topic, got " (pr-str (remove #(str/starts-with? % "$SYS/") topics)))))
      (tu/close! sub))))

(deftest sys-topics-are-retained-for-a-later-subscriber
  (testing "subscribing after the fact still gets the current values"
    ;; Which is how Mosquitto's "static" topics behave, and here it falls out
    ;; of publishing retained rather than needing anything of its own.
    (sys/publish-once!)
    (let [sub (tu/connect! "sys-late" :ordered? true :buffer 256)]
      (client/send-message (:client sub) (subscribe-msg "$SYS/broker/version" 0 1))
      (tu/expect-eventually! (:ch sub) :SUBACK 2000)
      (let [msg (tu/expect-eventually! (:ch sub) :PUBLISH 2000)]
        (is (some? msg) "the retained version should arrive on subscribe")
        (when msg
          (is (= "$SYS/broker/version" (:topic msg)))
          (is (= sys/broker-version (tu/payload-str msg)))))
      (tu/close! sub))))

(deftest a-wildcard-subscriber-gets-none-of-it
  (testing "$SYS stays away from clients that did not ask for it by name"
    ;; §4.7.2 again, and the reason it matters here: without it every client
    ;; subscribed to # would be handed the broker's internals every interval.
    (let [sub (tu/connect! "sys-wildcard" :ordered? true :buffer 256)]
      (client/send-message (:client sub) (subscribe-msg "#" 0 1))
      (tu/expect-eventually! (:ch sub) :SUBACK 2000)
      (sys/publish-once!)
      (let [got    (tu/take-n! (:ch sub) 5 1500)
            topics (filter #(str/starts-with? % "$SYS/") (map :topic (:PUBLISH got)))]
        (is (empty? topics)
            (str "a # subscriber must not receive $SYS, got " (pr-str topics))))
      (tu/close! sub))))

(deftest the-publisher-loop-runs-and-stops
  (testing "start! publishes on its own, and stop! stops it"
    ;; The loop had no test until it misled me: $SYS did not arrive over a real
    ;; socket, so I went looking at the publisher — which was working — instead
    ;; of at my probe, which was calling the wrong callback method.
    (try
      (is (true? (sys/start! 1)) "start! should report that it started")
      (is (nil? (sys/start! 1)) "and be idempotent while running")
      (let [topic    "$SYS/broker/clients/connected"
            deadline (+ (System/currentTimeMillis) 5000)]
        (loop []
          (when (and (not (contains? @h/*retained* topic))
                     (< (System/currentTimeMillis) deadline))
            (Thread/sleep 100)
            (recur)))
        (is (contains? @h/*retained* topic)
            "the loop should have published without being asked again")

        ;; Stop it, then check nothing more is published: the value goes stale
        ;; rather than being refreshed.
        (sys/stop!)
        (Thread/sleep 1500)
        (swap! h/*retained* dissoc topic)
        (Thread/sleep 2500)
        (is (not (contains? @h/*retained* topic))
            "nothing should be published after stop!"))
      (finally
        (sys/stop!)))))
