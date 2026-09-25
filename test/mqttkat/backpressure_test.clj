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
           [java.nio.channels Selector SocketChannel]
           [java.util.concurrent CountDownLatch TimeUnit]
           [org.mqttkat MqttStat]
           [org.mqttkat.server Connection]
           [org.mqttkat.packages MqttConnect MqttSubscribe]))

(use-fixtures :once tu/broker-fixture)

(def ^:private limit
  "Small enough that a handful of publishes reaches the drop path. The
   production default is thousands; burying a client under that many messages
   would make this a load test rather than a unit test."
  20)

(defn- ^"[B" ->bytes [^ByteBuffer buf]
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
      (.write out (->bytes (MqttConnect/encode
                            {:packet-type :CONNECT :protocol-name "MQTT"
                             :protocol-version 4 :keep-alive 100
                             :clean-session? true
                             :client-id (tu/client-id "deaf")})))
      (.write out (->bytes (MqttSubscribe/encode
                            {:packet-type :SUBSCRIBE :packet-identifier 1
                             :topics [{:qos qos :topic-filter topic}]})))
      (.flush out))
    sock)))

(defn- subscribe!
  ([c topic] (subscribe! c topic 0))
  ([{:keys [client ch]} topic qos]
  (client/send-message client {:packet-type :SUBSCRIBE :packet-identifier 1
                               :topics [{:qos qos :topic-filter topic}]})
  ;; expect-eventually!, not expect!: the SUBACK is queued by the subscribe
  ;; handler while a publisher's fan-out thread may be queueing a PUBLISH to
  ;; the same connection, so the two can arrive in either order.
  (tu/expect-eventually! ch :SUBACK 2000)))

(defn- settle!
  "Wait until the broker has worked through the burst.

   Keyed on messages written rather than messages dropped: with back-pressure
   on there is nothing to drop, so a drop-based check returns instantly and
   hands the next namespace a broker still fanning out tens of thousands of
   publishes. That is what made flow-test's reconnect race."
  []
  (let [deadline (+ (System/currentTimeMillis) 20000)]
    (loop [previous -1]
      (let [now (.sum MqttStat/writtenMessages)]
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
  (let [was (Connection/getMaxQueued)
        was-bp (Connection/isQos0BackPressure)]
    (try
      ;; Pinned off: this is about what dropping does. Throttling instead is
      ;; the other half of the choice and has a test of its own below.
      (Connection/setQos0BackPressure false)
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
        (Connection/setQos0BackPressure was-bp)
        (Connection/setMaxQueued was)))))

(deftest a-stalled-subscriber-does-not-starve-a-healthy-one
  (let [was (Connection/getMaxQueued)
        was-bp (Connection/isQos0BackPressure)]
    (try
      ;; Only true while QoS 0 drops. This is exactly what dropping is for and
      ;; exactly what throttling gives up: with back-pressure on, the publisher
      ;; is held back for the stalled subscriber and the healthy one gets
      ;; nothing either. Both behaviours are wanted, so both are pinned.
      (Connection/setQos0BackPressure false)
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
        (Connection/setQos0BackPressure was-bp)
        (Connection/setMaxQueued was)))))

(deftest qos-0-throttles-the-publisher-when-back-pressure-is-on
  (testing "with back-pressure on, a stalled QoS 0 subscriber blocks its publisher"
    ;; What is actually guaranteed is that the publisher stops being read, and
    ;; so eventually cannot write. Not that nothing is dropped: when the pause
    ;; lands there are already messages in the socket buffer and the inbound
    ;; queue, and every one of them still fans out. The headroom for that
    ;; overshoot is the gap between the congestion mark and the hard limit —
    ;; half of maxQueued, which is 10 messages at the limit this test uses and
    ;; 5,000 at the default. So the assertions here are that throttling
    ;; happened and that it took effect, not a drop count.
    (let [was    (Connection/getMaxQueued)
          was-bp (Connection/isQos0BackPressure)]
      (try
        (Connection/setQos0BackPressure true)
        (Connection/setMaxQueued limit)
        (let [topic   (tu/topic "qos0-throttle")
              deaf    (deaf-subscriber! topic 0)
              _       (Thread/sleep 200)
              pub     (tu/connect! "qos0-throttle-pub")
              n       4000
              sent    (atom 0)
              ;; 2 KB each, so the total is far more than the socket buffers
              ;; can absorb: without that the publisher never blocks however
              ;; hard the broker pushes back.
              writer  (future
                        ;; Ends either by finishing or by the socket closing
                        ;; under it in the teardown below; both are fine, and
                        ;; neither should throw out of a future nobody derefs
                        ;; for a value.
                        (try
                          (dotimes [i n]
                            (client/send-message (:client pub)
                                                 {:packet-type :PUBLISH :qos 0 :retain? false
                                                  :topic topic :payload (str i "-" payload)})
                            (swap! sent inc))
                          :done
                          (catch Exception _ :stopped)))
              before-throttled (.sum MqttStat/publisherPauses)
              deadline (+ (System/currentTimeMillis) 15000)]
          (loop []
            (when (and (= before-throttled (.sum MqttStat/publisherPauses))
                       (< (System/currentTimeMillis) deadline))
              (Thread/sleep 50)
              (recur)))
          (is (> (.sum MqttStat/publisherPauses) before-throttled)
              "the publisher should have been paused rather than have its messages dropped")
          ;; Whether the publisher actually stalls is not asserted, and was for
          ;; a while: it depends on the socket buffer sizes and on the resume
          ;; threshold, which at the tiny maxQueued this test uses is two
          ;; packets — so the broker pauses and resumes fast enough that the
          ;; writer can still get through all of them. That made the assertion
          ;; fail about two runs in five while the broker was behaving exactly
          ;; as designed. The pause count is the part that is actually promised.

          ;; Teardown, not assertion. Closing the stalled subscriber releases
          ;; the publisher; closing the publisher unblocks its writer whether
          ;; or not that release reached it. Asserting the writer runs to
          ;; completion looked appealing and was simply flaky: how much it gets
          ;; through after the release depends on how much of the burst the
          ;; broker still has queued.
          (.close ^Socket deaf)
          (tu/close! pub)
          (deref writer 20000 :timed-out)
          (settle!))
        (finally
          (Connection/setQos0BackPressure was-bp)
          (Connection/setMaxQueued was))))))

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

;; ── QoS 1: the other half of the window ──────────────────────────────────

(deftest qos-1-pending-messages-drain-as-acknowledgements-arrive
  (testing "messages queued past the in-flight window are released by acks"
    ;; The queueing half was covered; the draining half was not. take-pending!
    ;; — reserving an identifier for a message that had to wait, and popping it
    ;; off the queue — had six lines that no test executed, because the one
    ;; test that filled a window used a subscriber that never acknowledged
    ;; anything, so nothing ever drained.
    (let [topic  (tu/topic "qos1-drain")
          sub-id (tu/client-id "qos1-drain-sub")
          ;; Ordered, with room for the lot: this test asserts a sequence, and
          ;; the default client reports arrival order only approximately.
          sub    (tu/connect! nil :id sub-id :ordered? true :buffer 1024)
          pub    (tu/connect! "qos1-drain-pub")
          n      (+ h/inflight-window 72)]        ; more than one window's worth
      (subscribe! sub topic 1)

      ;; Publish everything before acknowledging anything, so the window fills
      ;; and the remainder has to queue.
      (dotimes [i n]
        (client/send-message (:client pub)
                             {:packet-type :PUBLISH :qos 1 :packet-identifier (inc i)
                              :retain? false :topic topic :payload (str "seq-" i)}))

      (let [deadline (+ (System/currentTimeMillis) 5000)]
        (loop []
          (when (and (zero? (h/pending-count sub-id))
                     (< (System/currentTimeMillis) deadline))
            (Thread/sleep 25)
            (recur))))
      (is (pos? (h/pending-count sub-id))
          "more than a window of messages should have left some of them queued")

      ;; Now acknowledge, which is what releases them one at a time.
      (let [received (loop [acc []]
                       (if (= n (count acc))
                         acc
                         (if-let [msg (tu/take! (:ch sub) 5000)]
                           (if (= :PUBLISH (:packet-type msg))
                             (do (client/puback (:client sub) (:packet-identifier msg))
                                 (recur (conj acc (tu/payload-str msg))))
                             (recur acc))
                           acc)))]
        (is (= n (count received))
            "every message should arrive once the window keeps being released")
        (is (= (map #(str "seq-" %) (range n)) received)
            "and in the order published: the pending queue is FIFO"))

      (let [deadline (+ (System/currentTimeMillis) 5000)]
        (loop []
          (when (and (pos? (+ (h/inflight-count sub-id) (h/pending-count sub-id)))
                     (< (System/currentTimeMillis) deadline))
            (Thread/sleep 25)
            (recur))))
      (is (zero? (h/pending-count sub-id)) "the queue should have emptied")
      (is (zero? (h/inflight-count sub-id)) "and nothing should still be outstanding")

      (tu/close! sub)
      (tu/close! pub)
      (settle!))))

;; ── the hand-off itself ──────────────────────────────────────────────────
;;
;; The tests above drive back-pressure through a live broker. These two go at
;; the bookkeeping directly, because what went wrong in it was a race that no
;; amount of traffic makes reliable — and that the traffic tests could not see,
;; since a wedged publisher just looks like a slow one.
;;
;; A publisher is paused by a subscriber whose outbound queue is deep, and
;; released when that queue drains. The only thing that will ever release it is
;; the subscriber holding it in `waiters`, so the invariant is: a paused
;; publisher must be somebody's waiter.

(defn- field-value [^Connection c ^String n]
  (.get (doto (.getDeclaredField Connection n) (.setAccessible true)) c))

(defn- bare-connection
  "A Connection with a real SelectionKey and deliberately not started: this
   exercises the pause bookkeeping, not the reader and writer threads."
  ^Connection [^Selector selector]
  (let [ch (doto (SocketChannel/open) (.configureBlocking false))]
    (Connection. (.register ch selector 0) ch nil)))

(deftest a-paused-publisher-is-always-somebodys-waiter
  (testing "an add racing a drain must not be dropped"
    ;; drained() used to iterate the waiters and then clear() them. The two are
    ;; not one step, so a pauseUntilDrained landing in between had its
    ;; publisher removed without being resumed — and, no longer a waiter, no
    ;; later drain would find it either. Its socket was never read again: it
    ;; stopped acknowledging, its own window filled, and it went silent for the
    ;; life of the broker. A 400,000 message run at 2,000 subscribers wedged on
    ;; this every time. Against the old code this fails on about 50 rounds in
    ;; 2,000.
    (with-open [selector (Selector/open)]
      (let [subscriber (bare-connection selector)
            publisher  (bare-connection selector)
            ;; A decoy already waiting, so drained() has work to do and reaches
            ;; the point where it used to clear the set. With an empty set it
            ;; returns on the first line and the race cannot happen at all —
            ;; which is how the first draft of this test passed against the
            ;; very bug it was written for.
            decoy      (bare-connection selector)
            ;; Above resumeAt(), so pauseUntilDrained's own re-check does not
            ;; fire and paper over the race the way it does on an idle queue.
            queued     (field-value subscriber "queuedCount")
            rounds     2000
            orphaned   (atom 0)]
        (.set ^java.util.concurrent.atomic.AtomicInteger queued Integer/MAX_VALUE)
        (dotimes [_ rounds]
          (.resumeReading publisher)
          (.pauseUntilDrained subscriber decoy)
          (let [go   (CountDownLatch. 1)
                done (CountDownLatch. 2)]
            (.start (Thread. ^Runnable (fn [] (.await go) (.drained subscriber) (.countDown done))))
            (.start (Thread. ^Runnable (fn [] (.await go) (.pauseUntilDrained subscriber publisher) (.countDown done))))
            (.countDown go)
            (.await done 5 TimeUnit/SECONDS))
          ;; Either the publisher was released, or somebody still holds it and
          ;; will release it later. Anything else is a socket never read again.
          (when (and (.isReadingPaused publisher)
                     (not (contains? (set (field-value subscriber "waiters")) publisher)))
            (swap! orphaned inc))
          (.drained subscriber))
        (is (zero? @orphaned)
            (str @orphaned " of " rounds " rounds left the publisher paused with"
                 " nobody holding it — its socket would never be read again"))))))

(deftest a-drain-landing-inside-the-pause-cannot-orphan-the-publisher
  (testing "drained() at the worst moment: while the publisher is being paused"
    ;; The race above, made deterministic. The publisher's pauseReading() is
    ;; where drained() used to be able to land between "become its waiter"
    ;; and "pause it": it took the publisher out of the set and resumed it,
    ;; then the pause arrived, and the publisher stayed stopped with nobody
    ;; holding it — the stall behind a 1,000,000 message QoS 1 run that never
    ;; finished. Here that drained() runs from inside pauseReading() itself,
    ;; on every call, so the old order fails every time rather than once in
    ;; a hundred full-suite runs.
    (with-open [selector (Selector/open)]
      (let [subscriber (bare-connection selector)
            ch         (doto (SocketChannel/open) (.configureBlocking false))
            ^Connection publisher (proxy [Connection] [(.register ch selector 0) ch nil]
                         (pauseReading []
                           (.drained subscriber)
                           (let [^Connection this this] (proxy-super pauseReading))))
            queued     (field-value subscriber "queuedCount")]
        ;; Busy, so pauseUntilDrained's re-check does not release it.
        (.set ^java.util.concurrent.atomic.AtomicInteger queued Integer/MAX_VALUE)
        (.pauseUntilDrained subscriber publisher)
        (is (.isReadingPaused publisher) "paused, as the subscriber is still busy")
        (is (contains? (set (field-value subscriber "waiters")) publisher)
            "and held by the subscriber, so its drain will let it go")
        (.set ^java.util.concurrent.atomic.AtomicInteger queued 0)
        (.drained subscriber)
        (is (not (.isReadingPaused publisher)) "which it does")))))

(deftest a-publisher-held-by-several-subscribers-waits-for-all-of-them
  (testing "one subscriber draining does not release a publisher another still holds"
    ;; A publisher feeds every subscriber of every topic it publishes to. As a
    ;; flag, the pause was lifted by the first of them to drain, and the
    ;; publisher went on filling the others until they refused QoS 1 messages.
    (with-open [selector (Selector/open)]
      (let [s1        (bare-connection selector)
            s2        (bare-connection selector)
            publisher (bare-connection selector)]
        (doseq [^Connection s [s1 s2]]
          (.set ^java.util.concurrent.atomic.AtomicInteger (field-value s "queuedCount") Integer/MAX_VALUE)
          (.pauseUntilDrained s publisher))
        (is (.isReadingPaused publisher))
        (.set ^java.util.concurrent.atomic.AtomicInteger (field-value s1 "queuedCount") 0)
        (.drained s1)
        (is (.isReadingPaused publisher) "still paused: s2 is still congested")
        (.set ^java.util.concurrent.atomic.AtomicInteger (field-value s2 "queuedCount") 0)
        (.drained s2)
        (is (not (.isReadingPaused publisher)) "released once the last holder drains"))))

  (testing "the same subscriber holding the same publisher twice is one hold"
    (with-open [selector (Selector/open)]
      (let [s         (bare-connection selector)
            publisher (bare-connection selector)
            queued    (field-value s "queuedCount")]
        (.set ^java.util.concurrent.atomic.AtomicInteger queued Integer/MAX_VALUE)
        (.pauseUntilDrained s publisher)
        (.pauseUntilDrained s publisher)
        (.set ^java.util.concurrent.atomic.AtomicInteger queued 0)
        (.drained s)
        (is (not (.isReadingPaused publisher)) "one drain lets it go")))))

(deftest a-qos-1-hold-waits-for-the-pending-queue-not-the-socket
  (testing "a short socket write queue does not release a publisher held for QoS 1"
    ;; A QoS 1 subscriber's socket only ever carries its in-flight window, so its
    ;; write queue is short while hundreds wait in the broker's pending queue.
    ;; Released on the socket's measure, as it was, the QoS 1 throttle let go of
    ;; a publisher the moment it took it, and the pending queue ran up to its
    ;; limit and refused messages.
    (with-open [selector (Selector/open)]
      (let [subscriber (bare-connection selector)
            publisher  (bare-connection selector)]
        (is (zero? (.get ^java.util.concurrent.atomic.AtomicInteger (field-value subscriber "queuedCount")))
            "the socket queue is empty, as for a QoS 1 subscriber with a full window")
        (.pauseUntilAcked subscriber publisher)
        (is (.isReadingPaused publisher) "held, where pauseUntilDrained would have let go at once")
        (.drained subscriber)
        (is (.isReadingPaused publisher) "and the socket-queue signal is not the one that releases it")
        (.ackDrained subscriber)
        (is (not (.isReadingPaused publisher)) "the pending queue draining is"))))

  (testing "a subscriber that closes lets its QoS 1 holds go"
    (with-open [selector (Selector/open)]
      (let [subscriber (bare-connection selector)
            publisher  (bare-connection selector)]
        (.pauseUntilAcked subscriber publisher)
        (.close subscriber)
        (is (not (.isReadingPaused publisher))))))

  (testing "held for QoS 0 and for QoS 1 at once, it waits for both"
    (with-open [selector (Selector/open)]
      (let [subscriber (bare-connection selector)
            publisher  (bare-connection selector)]
        (.set ^java.util.concurrent.atomic.AtomicInteger (field-value subscriber "queuedCount") Integer/MAX_VALUE)
        (.pauseUntilDrained subscriber publisher)
        (.pauseUntilAcked subscriber publisher)
        (.ackDrained subscriber)
        (is (.isReadingPaused publisher) "the socket queue is still full")
        (.set ^java.util.concurrent.atomic.AtomicInteger (field-value subscriber "queuedCount") 0)
        (.drained subscriber)
        (is (not (.isReadingPaused publisher)))))))

(deftest draining-releases-every-waiter
  (testing "the ordinary case still works"
    (with-open [selector (Selector/open)]
      (let [subscriber (bare-connection selector)
            publishers (vec (repeatedly 5 #(bare-connection selector)))]
        (doseq [p publishers] (.pauseUntilDrained subscriber p))
        (.drained subscriber)
        (is (every? #(not (.isReadingPaused ^Connection %)) publishers)
            "every publisher the subscriber stopped should be reading again")
        (is (empty? (field-value subscriber "waiters"))
            "and none should still be recorded as waiting")))))
