(ns mqttkat.v5-message-expiry-test
  "Message Expiry Interval (§3.3.2.3.3).

   A publisher can say how long its message is worth delivering for. The
   interval matters to a message that has to *wait* — one queued for a session
   whose client is not connected — and §3.3.2.3.3 asks two things of the
   server: discard it once the interval has passed, and tell the client that
   eventually gets it how much of the interval was spent waiting."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [mqttkat.test-util :as tu]))

(use-fixtures :once tu/broker-fixture)

(defn- session [id]
  (tu/connect-v5! nil :id id :clean-session? false
                  :properties {:session-expiry-interval 300}))

(defn- publish! [c topic text qos expiry pid]
  (tu/send-v5! c (cond-> {:packet-type :PUBLISH :topic topic :qos qos
                          :payload (.getBytes ^String text "UTF-8")
                          :retain? false :duplicate? false
                          :properties {:message-expiry-interval expiry}}
                   (pos? qos) (assoc :packet-identifier pid))))

(deftest a-message-that-outlives-its-interval-is-not-delivered
  (testing "§3.3.2.3.3: discarded rather than handed over late"
    (let [id    (tu/client-id "expiry")
          topic (tu/topic "expiry")
          a     (session id)]
      (tu/send-v5! a {:packet-type :SUBSCRIBE :packet-identifier 1
                      :topics [{:qos 2 :topic-filter topic}]})
      (tu/expect! (:ch a) :SUBACK)
      (tu/send-v5! a {:packet-type :DISCONNECT})
      (tu/close! a)
      (tu/wait-for-parked-session! id)
      (let [pub (tu/connect-v5! "expiry-pub")]
        (try
          ;; One that will not survive the wait, one that will.
          (publish! pub topic "gone" 1 1 11)
          (tu/expect-eventually! (:ch pub) :PUBACK 2000)
          (publish! pub topic "kept" 1 60 12)
          (tu/expect-eventually! (:ch pub) :PUBACK 2000)
          (Thread/sleep 2200)
          (let [b (session id)]
            (try
              (is (true? (:session-present? (:connack b))))
              (let [m (tu/expect-eventually! (:ch b) :PUBLISH 3000)]
                (is (= "kept" (tu/payload-str m))
                    "the expired one was dropped, not merely reordered")
                (tu/send-v5! b {:packet-type :PUBACK
                                :packet-identifier (:packet-identifier m)}))
              (is (nil? (tu/take! (:ch b) 800)) "and nothing follows it")
              (finally (tu/close! b))))
          (finally (tu/close! pub)))))))

(deftest what-arrives-says-how-long-it-waited
  (testing "§3.3.2.3.3: the interval sent on is the value minus the wait"
    ;; Without the subtraction a message queued for an hour would arrive
    ;; claiming its full lifetime ahead of it, and a client forwarding it on
    ;; would keep resetting the clock.
    (let [id    (tu/client-id "expiry-count")
          topic (tu/topic "expiry-count")
          a     (session id)]
      (tu/send-v5! a {:packet-type :SUBSCRIBE :packet-identifier 1
                      :topics [{:qos 1 :topic-filter topic}]})
      (tu/expect! (:ch a) :SUBACK)
      (tu/send-v5! a {:packet-type :DISCONNECT})
      (tu/close! a)
      (tu/wait-for-parked-session! id)
      (let [pub (tu/connect-v5! "expiry-count-pub")]
        (try
          (publish! pub topic "waited" 1 60 21)
          (tu/expect-eventually! (:ch pub) :PUBACK 2000)
          (Thread/sleep 2200)
          (let [b (session id)]
            (try
              (let [m         (tu/expect-eventually! (:ch b) :PUBLISH 3000)
                    remaining (long (or (:message-expiry-interval (:properties m)) 0))]
                (is (= "waited" (tu/payload-str m)))
                (is (< remaining 60) "some of the interval was spent waiting")
                (is (pos? remaining) "but not all of it"))
              (finally (tu/close! b))))
          (finally (tu/close! pub)))))))

(deftest a-message-delivered-at-once-keeps-its-interval
  (testing "nothing was spent, so nothing is subtracted"
    (let [topic (tu/topic "expiry-live")
          sub   (tu/connect-v5! "expiry-live-sub")
          pub   (tu/connect-v5! "expiry-live-pub")]
      (try
        (tu/send-v5! sub {:packet-type :SUBSCRIBE :packet-identifier 1
                          :topics [{:qos 1 :topic-filter topic}]})
        (tu/expect! (:ch sub) :SUBACK)
        (publish! pub topic "now" 1 60 31)
        (let [m (tu/expect-eventually! (:ch sub) :PUBLISH 3000)]
          (is (= 60 (long (:message-expiry-interval (:properties m))))))
        (finally (tu/close! sub pub))))))

(deftest a-queued-message-keeps-its-other-properties
  (testing "the offline queue carried only topic, payload and QoS"
    ;; Everything else was dropped on the way in, so a message that waited
    ;; arrived stripped of its content type and user properties while one
    ;; delivered live kept them.
    (let [id    (tu/client-id "queued-props")
          topic (tu/topic "queued-props")
          a     (session id)]
      (tu/send-v5! a {:packet-type :SUBSCRIBE :packet-identifier 1
                      :topics [{:qos 1 :topic-filter topic}]})
      (tu/expect! (:ch a) :SUBACK)
      (tu/send-v5! a {:packet-type :DISCONNECT})
      (tu/close! a)
      (tu/wait-for-parked-session! id)
      (let [pub (tu/connect-v5! "queued-props-pub")]
        (try
          (tu/send-v5! pub {:packet-type :PUBLISH :topic topic :qos 1
                            :packet-identifier 41
                            :payload (.getBytes "held" "UTF-8")
                            :retain? false :duplicate? false
                            :properties {:content-type    "text/plain"
                                         :user-properties [["a" "2"]]}})
          (tu/expect-eventually! (:ch pub) :PUBACK 2000)
          (let [b (session id)]
            (try
              (let [m (tu/expect-eventually! (:ch b) :PUBLISH 3000)]
                (is (= "held" (tu/payload-str m)))
                (is (= "text/plain" (:content-type (:properties m))))
                (is (= [["a" "2"]] (mapv vec (:user-properties (:properties m))))))
              (finally (tu/close! b))))
          (finally (tu/close! pub)))))))
