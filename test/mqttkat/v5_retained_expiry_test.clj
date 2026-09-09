(ns mqttkat.v5-retained-expiry-test
  "Retained messages that outlive their Message Expiry Interval.

   §3.3.1.3: \"If the current retained message for a Topic expires, it is
   discarded and there will be no retained message for that topic.\" A retained
   message is the one thing in the broker that is *meant* to sit there
   indefinitely, so an interval on one is a publisher saying how long its
   answer stays true — and a broker that hands out a stale answer for ever is
   worse than one that has none."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [mqttkat.handlers :as h]
            [mqttkat.test-util :as tu]))

(use-fixtures :once tu/broker-fixture)

(defn- retain! [c topic text qos expiry]
  (tu/send-v5! c (cond-> {:packet-type :PUBLISH :topic topic :qos qos
                          :payload (.getBytes ^String text "UTF-8")
                          :retain? true :duplicate? false}
                   expiry (assoc :properties {:message-expiry-interval expiry})
                   (pos? qos) (assoc :packet-identifier 5)))
  (Thread/sleep 250))

(defn- subscribe-and-take [prefix topic ms]
  (let [sub (tu/connect-v5! prefix)]
    (try
      (tu/send-v5! sub {:packet-type :SUBSCRIBE :packet-identifier 1
                        :topics [{:qos 0 :topic-filter topic}]})
      (tu/expect! (:ch sub) :SUBACK)
      (tu/take! (:ch sub) ms)
      (finally (tu/close! sub)))))

(deftest an-expired-retained-message-is-not-replayed
  (testing "§3.3.1.3: discarded, so a later subscriber gets nothing"
    (let [topic (tu/topic "ret-expire")
          pub   (tu/connect-v5! "ret-expire-pub")]
      (try
        (retain! pub topic "stale" 0 1)
        ;; A subscriber arriving inside the interval still gets it...
        (is (some? (subscribe-and-take "ret-expire-early" topic 1200))
            "not expired yet")
        (Thread/sleep 1600)
        ;; ...and one arriving after it does not.
        (is (nil? (subscribe-and-take "ret-expire-late" topic 1200))
            "the interval passed, so there is no retained message")
        (finally (tu/close! pub))))))

(deftest an-expired-retained-message-is-thrown-away
  (testing "not merely withheld — §3.3.1.3 says discarded"
    ;; Withholding it would leave the broker holding a message it will never
    ;; deliver, counted in $SYS and shown on the console, for as long as it
    ;; runs.
    (let [topic (tu/topic "ret-discard")
          pub   (tu/connect-v5! "ret-discard-pub")]
      (try
        (retain! pub topic "stale" 0 1)
        (is (contains? @h/*retained* topic) "stored to begin with")
        (Thread/sleep 1600)
        (h/sweep-retained!)
        (is (not (contains? @h/*retained* topic)) "and then gone")
        (finally (tu/close! pub))))))

(deftest what-is-replayed-says-how-long-it-has-been-retained
  (testing "§3.3.2.3.3: the value minus the time it has been waiting"
    ;; The same rule as a queued message. Without it a retained message
    ;; published with a ten minute life still claims ten minutes an hour later,
    ;; and anything bridging it onward keeps resetting the clock.
    (let [topic (tu/topic "ret-countdown")
          pub   (tu/connect-v5! "ret-countdown-pub")]
      (try
        (retain! pub topic "ticking" 0 60)
        (Thread/sleep 2000)
        (let [m         (subscribe-and-take "ret-countdown-sub" topic 2000)
              remaining (long (or (:message-expiry-interval (:properties m)) 0))]
          (is (= "ticking" (tu/payload-str m)))
          (is (< remaining 60) "some of the interval has gone")
          (is (pos? remaining) "but not all of it"))
        (finally (tu/close! pub))))))

(deftest a-retained-message-with-no-interval-never-expires
  (testing "§3.3.2.3.3: absent means it does not expire"
    (let [topic (tu/topic "ret-forever")
          pub   (tu/connect-v5! "ret-forever-pub")]
      (try
        (retain! pub topic "permanent" 0 nil)
        (Thread/sleep 1600)
        (h/sweep-retained!)
        (let [m (subscribe-and-take "ret-forever-sub" topic 2000)]
          (is (= "permanent" (tu/payload-str m)))
          (is (nil? (:message-expiry-interval (:properties m)))
              "and nothing invented for it"))
        (finally
          (tu/send-v5! pub {:packet-type :PUBLISH :topic topic :qos 0
                            :payload (byte-array 0) :retain? true :duplicate? false})
          (tu/close! pub))))))

(deftest replacing-a-retained-message-restarts-its-clock
  (testing "the new message is a new message"
    (let [topic (tu/topic "ret-replace")
          pub   (tu/connect-v5! "ret-replace-pub")]
      (try
        (retain! pub topic "first" 0 1)
        (Thread/sleep 900)
        (retain! pub topic "second" 0 60)
        (Thread/sleep 900)
        (let [m (subscribe-and-take "ret-replace-sub" topic 2000)]
          (is (= "second" (tu/payload-str m))
              "the replacement did not inherit the first one's age"))
        (finally
          (tu/send-v5! pub {:packet-type :PUBLISH :topic topic :qos 0
                            :payload (byte-array 0) :retain? true :duplicate? false})
          (tu/close! pub))))))

(deftest expiry-applies-at-every-stored-qos
  (testing "the replay dispatches on the stored QoS, so all three paths need it"
    ;; process-retained-messages picks its branch from the QoS the message was
    ;; published at, and only one of those branches goes through send-publish!.
    (doseq [qos [0 1 2]]
      (let [topic (tu/topic (str "ret-qos-" qos))
            pub   (tu/connect-v5! (str "ret-qos-pub-" qos))]
        (try
          (retain! pub topic "stale" qos 1)
          (when (= 2 qos)
            (tu/expect-eventually! (:ch pub) :PUBREC 2000)
            (tu/send-v5! pub {:packet-type :PUBREL :packet-identifier 5})
            (Thread/sleep 200))
          (Thread/sleep 1600)
          (is (nil? (subscribe-and-take (str "ret-qos-sub-" qos) topic 1200))
              (str "expired, stored at qos " qos))
          (finally (tu/close! pub)))))))
