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
            [mqttkat.handlers :as h]
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
  ([^String topic] (deaf-subscriber! topic 0))
  ([^String topic qos]
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
                             :topics [{:qos qos :topic-filter topic}]})))
      (.flush out))
    sock)))

(defn- subscribe! [{:keys [client ch]} topic]
  (client/send-message client {:packet-type :SUBSCRIBE :packet-identifier 1
                               :topics [{:qos 0 :topic-filter topic}]})
  ;; expect-eventually!, not expect!: the SUBACK is queued by the subscribe
  ;; handler while a publisher's fan-out thread may be queueing a PUBLISH to
  ;; the same connection, so the two can arrive in either order.
  (tu/expect-eventually! ch :SUBACK 2000))

(defn- settle!
  "Wait until the broker stops dropping, i.e. it has worked through the burst,
   so a subscriber joining now sees only what is published after it."
  []
  (let [deadline (+ (System/currentTimeMillis) 15000)]
    (loop [previous -1]
      (let [now (.sum MqttStat/droppedMessages)]
        (when (and (not= now previous) (< (System/currentTimeMillis) deadline))
          (Thread/sleep 250)
          (recur now))))))

(defn- saturate!
  "Bury `topic` under enough volume to overrun the broker's socket send buffer
   for a subscriber that is not reading, and wait until it starts dropping."
  [pub topic]
  (let [before (.sum MqttStat/droppedMessages)]
    (dotimes [i 2000]
      (client/send-message (:client pub)
                           {:packet-type :PUBLISH :qos 0 :retain? false
                            :topic topic :payload (str i "-" payload)}))
    (let [deadline (+ (System/currentTimeMillis) 5000)]
      (loop []
        (when (and (= before (.sum MqttStat/droppedMessages))
                   (< (System/currentTimeMillis) deadline))
          (Thread/sleep 20)
          (recur))))
    before))

(deftest qos-0-to-a-stalled-subscriber-is-dropped-not-queued
  (let [was (Connection/getMaxQueued)]
    (try
      (Connection/setMaxQueued limit)
      (let [topic (tu/topic "backpressure")
            deaf  (deaf-subscriber! topic)
            _     (Thread/sleep 200)             ; let the SUBSCRIBE be handled
            pub   (tu/connect! "backpressure-pub")
            before (saturate! pub topic)]
        (is (> (.sum MqttStat/droppedMessages) before)
            "expected QoS 0 publishes to a subscriber that stopped reading to be dropped")
        (.close ^Socket deaf)
        (tu/close! pub))
      (finally
        (Connection/setMaxQueued was)))))

(deftest a-stalled-subscriber-does-not-starve-a-healthy-one
  (let [was (Connection/getMaxQueued)]
    (try
      (Connection/setMaxQueued limit)
      (let [topic (tu/topic "isolation")
            deaf  (deaf-subscriber! topic)
            _     (Thread/sleep 200)
            pub   (tu/connect! "isolation-pub")]
        (saturate! pub topic)
        (settle!)

        ;; The healthy subscriber joins only now, so it starts with an empty
        ;; queue and never sees the flood that stalled the other one. Then a
        ;; small, paced burst: at this rate a subscriber that is reading cannot
        ;; fall behind, so "all of them" is a real assertion rather than a
        ;; threshold. Asserting a proportion here was flaky — with the limit
        ;; set this low for the test, a healthy subscriber that pauses for a
        ;; moment crosses it too.
        (let [healthy (tu/connect! "healthy-sub")
              n       20
              before  (.sum MqttStat/droppedMessages)]
          (subscribe! healthy topic)
          (dotimes [i n]
            (client/send-message (:client pub)
                                 {:packet-type :PUBLISH :qos 0 :retain? false
                                  :topic topic :payload (str "paced-" i)})
            (Thread/sleep 5))
          (let [got (tu/take-n! (:ch healthy) n 5000)]
            (is (= n (count (:PUBLISH got)))
                "a subscriber that is reading should get every message"))
          (is (> (.sum MqttStat/droppedMessages) before)
              "and the stalled one should still have been dropped throughout"))

        (.close ^Socket deaf)
        (tu/close! pub))
      (finally
        (Connection/setMaxQueued was)))))

;; ── QoS 1: back-pressure instead of dropping ─────────────────────────────

(deftest qos-1-throttles-the-publisher-rather-than-dropping
  (testing "a subscriber that stops reading stops the publisher, losing nothing"
    ;; QoS 1 is at-least-once, so the QoS 0 answer — refuse the message — is
    ;; not available. The pressure goes back to the source instead: the broker
    ;; stops reading the publisher's socket, its receive window closes, and the
    ;; publisher blocks in its own write. Nothing is discarded.
    ;;
    ;; Deliberately small and deliberately tidy. An earlier version published
    ;; 20,000 large messages from a future it then cancelled, which left the
    ;; broker still fanning them out into the next namespace and made
    ;; flow-test's reconnect race intermittently. Enough to cross the pause
    ;; threshold is enough, and small payloads keep the client's own writes
    ;; inside its socket buffer so nothing here blocks.
    (let [topic  (tu/topic "qos1-backpressure")
          deaf   (deaf-subscriber! topic 1)
          _      (Thread/sleep 200)
          pub    (tu/connect! "qos1-pub")
          n      (* 6 h/pause-threshold)
          before-dropped   (.sum MqttStat/droppedMessages)
          before-throttled (.sum MqttStat/publisherPauses)]
      (dotimes [i n]
        (client/send-message (:client pub)
                             {:packet-type :PUBLISH :qos 1
                              :packet-identifier (inc (mod i 60000))
                              :retain? false :topic topic
                              :payload (str "m" i)}))
      (let [deadline (+ (System/currentTimeMillis) 15000)]
        (loop []
          (when (and (= before-throttled (.sum MqttStat/publisherPauses))
                     (< (System/currentTimeMillis) deadline))
            (Thread/sleep 50)
            (recur))))
      (is (> (.sum MqttStat/publisherPauses) before-throttled)
          "the publisher feeding a stalled subscriber should have been paused")
      (is (= before-dropped (.sum MqttStat/droppedMessages))
          "and nothing may be dropped: QoS 1 is at-least-once")

      (.close ^Socket deaf)
      (tu/close! pub)
      ;; Do not hand the next namespace a broker still working through this.
      (settle!))))
