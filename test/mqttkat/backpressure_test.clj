(ns mqttkat.backpressure-test
  "What the broker does when a subscriber stops keeping up.

   QoS 0 is at-most-once, so a broker is allowed to drop rather than buffer
   without limit — and it has to be, or one subscriber that stops reading is
   charged to the broker's heap. Two properties matter, and they pull in
   opposite directions:

     - the queue for a slow subscriber is bounded, so publishes to it are
       eventually dropped rather than accumulated;
     - the drop is that subscriber's alone, and a healthy subscriber on the
       same topic still gets everything."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [mqttkat.client :as client]
            [mqttkat.test-util :as tu])
  (:import [java.net Socket]
           [java.nio ByteBuffer]
           [org.mqttkat MqttStat]
           [org.mqttkat.server Connection]
           [org.mqttkat.packages MqttConnect MqttSubscribe]))

(use-fixtures :once tu/broker-fixture)

(def ^:private limit
  "Small enough that a handful of publishes reaches the drop path. The
   production default is thousands; burying a client under that many messages
   would make this a load test rather than a unit test."
  20)

(defn- ->bytes [^ByteBuffer buf]
  (let [a (byte-array (.remaining buf))]
    (.get (.duplicate buf) a)
    a))

(def ^:private payload
  "Big enough that a few hundred of these overrun the broker's socket send
   buffer. That buffer is local and around 2.5 MB, so it — not the stalled
   client's receive window — is what has to fill before writes start failing
   and the outbound queue begins to build. Small messages never get there:
   2000 of them are 70 KB, and every write succeeds.

   Kept under 4 KB because MqttPublish/encode writes into a fixed 4096-byte
   array and throws past it."
  (apply str (repeat 2000 \x)))

(defn- deaf-subscriber!
  "A raw socket that subscribes and then never reads again."
  [^String topic]
  (let [sock (doto (Socket.)
               (.setReceiveBufferSize 512))]
    (.connect sock (java.net.InetSocketAddress. ^String tu/host ^int (int tu/port)))
    (let [^java.io.OutputStream out (.getOutputStream sock)]
      (.write out ^bytes (->bytes (MqttConnect/encode
                            {:packet-type :CONNECT :protocol-name "MQTT"
                             :protocol-version 4 :keep-alive 100
                             :clean-session? true
                             :client-id (tu/client-id "deaf")})))
      (.write out ^bytes (->bytes (MqttSubscribe/encode
                            {:packet-type :SUBSCRIBE :packet-identifier 1
                             :topics [{:qos 0 :topic-filter topic}]})))
      (.flush out))
    sock))

(deftest qos-0-to-a-stalled-subscriber-is-dropped-not-queued
  (let [was (Connection/getMaxQueued)]
    (try
      (Connection/setMaxQueued limit)
      (let [topic  (tu/topic "backpressure")
            deaf   (deaf-subscriber! topic)
            _      (Thread/sleep 200)            ; let the SUBSCRIBE be handled
            healthy (tu/connect! "healthy-sub")
            pub     (tu/connect! "backpressure-pub")]
        (client/send-message (:client healthy)
                             {:packet-type :SUBSCRIBE :packet-identifier 1
                              :topics [{:qos 0 :topic-filter topic}]})
        (tu/expect! (:ch healthy) :SUBACK)

        (let [before (.sum MqttStat/droppedMessages)
              n      2000]
          (dotimes [i n]
            (client/send-message (:client pub)
                                 {:packet-type :PUBLISH :qos 0 :retain? false
                                  :topic topic :payload (str i "-" payload)}))

          (testing "the stalled subscriber's queue is bounded, so publishes to it are dropped"
            (let [deadline (+ (System/currentTimeMillis) 3000)]
              (loop []
                (when (and (= before (.sum MqttStat/droppedMessages))
                           (< (System/currentTimeMillis) deadline))
                  (Thread/sleep 20)
                  (recur))))
            (is (> (.sum MqttStat/droppedMessages) before)
                "expected QoS 0 publishes to a subscriber that stopped reading to be dropped"))

          (testing "a healthy subscriber on the same topic keeps its feed"
            ;; Not an exact count on purpose. The limit is global and set very
            ;; low here, so a subscriber that pauses for a moment mid-burst can
            ;; cross it too — at 20 queued packets, briefly, anything can. The
            ;; property under test is that a subscriber which is still reading
            ;; keeps getting the feed while the stalled one is cut off, not
            ;; that QoS 0 became reliable.
            (let [got  (tu/take-n! (:ch healthy) n 8000)
                  pubs (count (:PUBLISH got))]
              (is (>= pubs (* 0.9 n))
                  (str "a reading subscriber should have kept up; got " pubs " of " n)))))

        (.close ^Socket deaf))
      (finally
        (Connection/setMaxQueued was)))))
