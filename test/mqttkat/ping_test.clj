(ns mqttkat.ping-test
  "PINGREQ/PINGRESP, and what keep-alive currently does.

   The MQTT keep-alive contract (3.1.1 §3.1.2.10) — drop a client you have
   heard nothing from for 1.5 x Keep Alive, and never drop one you have — is
   NOT tested here, because the broker does not implement it yet. As of now:

     * handlers/add-timer! writes :timer and :last-active under the client key,
       and handle-success then calls add-client!, which replaces that whole
       entry — so both keys are gone by the time the timer first fires;
     * check-timer therefore derefs a nil :last-active and throws, once per
       1.5 x keep-alive, per connected client;
     * its comparison, (<= (* 0.9 last-active) (- now time-out)), compares nine
       tenths of an epoch millisecond count against a timestamp, so it is true
       for any clock reading after 1970 regardless of activity;
     * update-timestamps is only ever called when the broker sends to a client,
       so nothing a client sends — PINGREQ included — marks it as alive.

   Fix those and the two disconnect tests this namespace should have become
   straightforward to write."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [mqttkat.client :as client]
            [mqttkat.test-util :as tu]))

;; lein auto test :only mqttkat.ping-test

(use-fixtures :once tu/broker-fixture)

(deftest pingreq-is-answered
  (testing "the broker answers a PINGREQ with a PINGRESP"
    (let [{:keys [client ch] :as c} (tu/connect! "ping" :keep-alive 0)]
      (client/pingreq client)
      (is (some? (tu/expect! ch :PINGRESP)))
      (tu/close! c))))

(deftest ping-does-not-disturb-the-session
  (testing "a ping leaves the connection usable"
    (let [topic (tu/topic "ping")
          {:keys [client ch] :as c} (tu/connect! "ping" :keep-alive 0)]
      (client/send-message client {:packet-type :SUBSCRIBE :packet-identifier 1
                                   :topics [{:qos 0 :topic-filter topic}]})
      (tu/expect! ch :SUBACK)
      (client/pingreq client)
      (tu/expect! ch :PINGRESP)
      (client/send-message client {:packet-type :PUBLISH :qos 0 :topic topic
                                   :retain? false :duplicate false :payload "after the ping"})
      (is (= "after the ping" (tu/payload-str (tu/expect-eventually! ch :PUBLISH))))
      (tu/close! c))))

(deftest zero-keep-alive-never-times-out
  (testing "keep alive 0 switches the timeout off"
    (let [{:keys [client] :as c} (tu/connect! "no-keep-alive" :keep-alive 0)]
      (Thread/sleep 2500)
      (is (true? (client/connected? client)))
      (tu/close! c))))
