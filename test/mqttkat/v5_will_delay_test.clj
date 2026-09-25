(ns mqttkat.v5-will-delay-test
  "MQTT 5.0 Will Delay Interval (§3.1.3.2.2).

   The will is held back rather than published the instant the connection ends.
   §3.1.2.5 gives the rule that makes it useful: if the client reconnects under
   the same id before the delay elapses, the will is deleted and never
   published at all — a client that drops and comes straight back has not
   really gone, and its subscribers should not be told it has.

   The delay is bounded by the session: the will goes out when the delay
   elapses *or the session ends, whichever is first*. A session expiry of 0 —
   the default, and what every 3.1.1 client effectively has — means the session
   ends at once, so the will is immediate however long a delay was asked for.

   Written before the implementation. Will Delay was one of the properties the
   broker decoded and then ignored."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [mqttkat.test-util :as tu]))

(use-fixtures :once tu/broker-fixture)

(defn- watcher
  "A subscriber on `topic`, ready to receive a will."
  [topic]
  (let [c (tu/connect-v5! "will-watch")]
    (tu/send-v5! c {:packet-type :SUBSCRIBE :packet-identifier 1
                    :topics [{:qos 0 :topic-filter topic}]})
    (tu/expect! (:ch c) :SUBACK)
    c))

(defn- dying-client
  "A client with a will, its delay and session expiry as given."
  [topic delay expiry]
  (tu/connect-v5! "will-dies"
                  :properties {:session-expiry-interval expiry}
                  :will {:will-topic topic :will-message "gone"
                         :will-qos 0 :will-retain false
                         :properties {:will-delay-interval delay}}))

(deftest ^:portable a-will-delay-holds-the-will-back
  (testing "delay 2, session expiry 10: the will arrives after the delay"
    (let [topic (tu/topic "will-delay")
          sub   (watcher topic)
          dying (dying-client topic 2 10)]
      (try
        (tu/close! dying)
        (let [start (System/currentTimeMillis)]
          (is (nil? (tu/take! (:ch sub) 900))
              "nothing in the first second — the will is being held")
          (let [msg     (tu/expect! (:ch sub) :PUBLISH 6000)
                elapsed (- (System/currentTimeMillis) start)]
            (is (= "gone" (tu/payload-str msg)))
            (is (<= 1500 elapsed 5000)
                (str "the will should arrive around the delay, took " elapsed "ms"))))
        (finally (tu/close! sub))))))

(deftest ^{:portable true
           :diverges-on-mosquitto "Mosquitto holds the will for the full delay although the session ended (§3.1.3.2.2)"}
  the-session-ending-cuts-the-delay-short
  (testing "delay 5, session expiry 0: the will is immediate"
    ;; §3.1.3.2.2 — the delay is an upper bound, not a promise to wait. With a
    ;; session that ends at once there is nothing left to come back to, so
    ;; holding the will for five seconds would help nobody.
    (let [topic (tu/topic "will-nodelay")
          sub   (watcher topic)
          dying (dying-client topic 5 0)]
      (try
        (tu/close! dying)
        (let [start   (System/currentTimeMillis)
              msg     (tu/expect! (:ch sub) :PUBLISH 3000)
              elapsed (- (System/currentTimeMillis) start)]
          (is (= "gone" (tu/payload-str msg)))
          (is (< elapsed 2000)
              (str "should not have waited for the delay, took " elapsed "ms")))
        (finally (tu/close! sub))))))

(deftest ^:portable coming-back-in-time-deletes-the-will
  (testing "reconnecting under the same id before the delay elapses"
    ;; §3.1.2.5. This is the whole point of the delay: a client that drops and
    ;; reconnects within it has not really gone away, and announcing its death
    ;; to every subscriber would be wrong.
    (let [topic (tu/topic "will-cancel")
          id    (tu/client-id "will-returner")
          sub   (watcher topic)
          dying (tu/connect-v5! nil :id id
                                :properties {:session-expiry-interval 30}
                                :will {:will-topic topic :will-message "should not appear"
                                       :will-qos 0 :will-retain false
                                       :properties {:will-delay-interval 3}})]
      (try
        (tu/close! dying)
        (Thread/sleep 400)
        ;; Back before the three seconds are up.
        (let [back (tu/connect-v5! nil :id id)]
          (try
            (is (nil? (tu/take! (:ch sub) 4500))
                "the will was deleted when the client came back")
            (finally (tu/close! back))))
        (finally (tu/close! sub))))))

(deftest ^:portable a-version-4-will-is-still-immediate
  (testing "3.1.1 has no delay, so nothing changes for it"
    (let [topic (tu/topic "will-v4")
          sub   (watcher topic)
          dying (tu/connect! "v4-dies" :will {:will-topic topic :will-message "bye"
                                              :will-qos 0 :will-retain false})]
      (try
        (tu/close! dying)
        (let [start   (System/currentTimeMillis)
              msg     (tu/expect! (:ch sub) :PUBLISH 3000)
              elapsed (- (System/currentTimeMillis) start)]
          (is (= "bye" (tu/payload-str msg)))
          (is (< elapsed 2000) (str "took " elapsed "ms")))
        (finally (tu/close! sub))))))

(deftest ^:portable a-will-carries-its-properties
  (testing "§3.1.3.2: the Will Properties travel with the will message"
    ;; The will was rebuilt by hand as topic, QoS, payload and retain, so
    ;; everything the client attached to it — content type, response topic,
    ;; correlation data, user properties — was dropped on the way out. A
    ;; subscriber saw the payload and nothing else.
    (let [topic (tu/topic "will-props")
          sub   (tu/connect-v5! "will-props-sub")
          dying (tu/connect-v5! "will-props-client"
                                :will {:will-topic   topic
                                       :will-qos     0
                                       :will-retain  false
                                       :will-message "gone"
                                       :properties   {:will-delay-interval 0
                                                      :content-type        "text/plain"
                                                      :user-properties     [["a" "2"] ["c" "3"]]}})]
      (try
        (tu/send-v5! sub {:packet-type :SUBSCRIBE :packet-identifier 1
                          :topics [{:qos 0 :topic-filter topic}]})
        (tu/expect! (:ch sub) :SUBACK)
        ;; Dropped, not disconnected: §3.14.4 discards the will on a polite
        ;; goodbye, so only a lost connection publishes it.
        (tu/close! dying)
        (let [m (tu/expect-eventually! (:ch sub) :PUBLISH 5000)
              p (:properties m)]
          (is (= "gone" (tu/payload-str m)))
          (is (= "text/plain" (:content-type p)))
          (is (= [["a" "2"] ["c" "3"]] (mapv vec (:user-properties p))))
          (is (nil? (:will-delay-interval p))
              "the delay is the broker's instruction, not the subscriber's"))
        (finally (tu/close! sub))))))
