(ns mqttkat.load.client
  "One client of the load generator, and the QoS state machines it needs.

   Both directions, because a load client is both ends of the protocol: as a
   publisher it owes the broker a PUBREL for every PUBREC, and as a subscriber
   it owes a PUBACK or a PUBREC/PUBCOMP for everything delivered to it. A
   generator that skips the second half does not measure a broker under load,
   it measures a broker talking to a client that has stopped listening."
  (:require [clojure.tools.logging :as log]
            [mqttkat.load.stats :as stats])
  (:import [java.nio ByteBuffer]
           [java.util.concurrent ConcurrentHashMap CountDownLatch Semaphore TimeUnit]
           [java.util.concurrent.atomic AtomicInteger]
           [org.mqttkat MqttHandler]
           [org.mqttkat.client MqttClient]
           [org.mqttkat.packages MqttConnect MqttDisconnect MqttPubAck MqttPubComp
            MqttPublish MqttPubRec MqttPubRel MqttSubscribe]))

(set! *warn-on-reflection* true)

;; ── the payload ───────────────────────────────────────────────────────

(def ^:const header-bytes
  "intended(8) + sent(8) + publisher(4) + sequence(8)."
  28)

(defn- put-header!
  ;; No primitive hints: Clojure only compiles those for fns of four
  ;; parameters or fewer, and this has five.
  [^ByteBuffer b intended sent publisher sequence]
  (doto b
    (.putLong 0 (long intended))
    (.putLong 8 (long sent))
    (.putInt 16 (int publisher))
    (.putLong 20 (long sequence))))

(defn read-header
  "{:intended :sent :publisher :sequence} out of a received payload, or nil if
   it is too short to be one of ours — which happens the moment anything else
   is publishing to the same broker, and is worth counting rather than
   throwing."
  [^bytes payload]
  (when (and payload (>= (alength payload) header-bytes))
    (let [b (ByteBuffer/wrap payload)]
      {:intended  (.getLong b 0)
       :sent      (.getLong b 8)
       :publisher (.getInt b 16)
       :sequence  (.getLong b 20)})))

;; ── acknowledgement bookkeeping ───────────────────────────────────────

(defn- next-packet-id
  "1..65535, wrapping. Safe against reuse because the in-flight window is
   capped far below 65535, so an identifier cannot come round while its first
   use is still outstanding."
  ^long [^AtomicInteger counter]
  (inc (mod (.getAndIncrement counter) 65535)))

(defn- retire!
  "Finish an outstanding publish: record how long the broker took to
   acknowledge it and give the window slot back."
  [client ^long id]
  (when-let [sent (.remove ^ConcurrentHashMap (:inflight client) id)]
    (stats/record! (:ack-latency client)
                   (quot (- (System/nanoTime) (long sent)) 1000))
    (stats/bump! (:counters client) :acked)
    (.release ^Semaphore (:window client))))

;; ── receiving ─────────────────────────────────────────────────────────

(defn- send! [client ^ByteBuffer buf]
  (try
    (.sendMessage ^MqttClient (:mqtt client) buf)
    true
    (catch Exception e
      (log/debug e "send failed on" (:client-id client))
      false)))

(defn- on-publish
  "A delivery. Two latencies come out of it, and the difference between them is
   the whole reason this generator can be trusted about the first one:

     service  — now minus when the publisher actually wrote the packet. What
                the broker did with it.
     response — now minus when the publisher was *scheduled* to write it. What
                a client would have experienced, including any time the
                generator itself was late.

   Reporting only the first is the coordinated-omission mistake: a generator
   that falls behind stops sending during exactly the moments the broker is
   slowest, and then reports the fast messages it did manage."
  [client msg]
  (let [now (System/nanoTime)]
    (if-let [{:keys [intended sent]} (read-header (:payload msg))]
      (do
        (stats/record! (:service-latency client) (quot (- now (long sent)) 1000))
        ;; Clamped at zero rather than left to be dropped as a negative. At
        ;; high rates the publisher parks once per millisecond and sends that
        ;; millisecond's worth in a burst, so a message can go out slightly
        ;; ahead of when a perfectly paced generator would have sent it, and
        ;; arrive before its own intended time. That is a zero, not a bad
        ;; sample: dropping them made the two histograms disagree on n — 484
        ;; of four million — which reads like lost messages and is not.
        (stats/record! (:response-latency client)
                       (max 0 (quot (- now (long intended)) 1000)))
        (stats/bump! (:counters client) :received)
        (when (:duplicate? msg) (stats/bump! (:counters client) :received-dup)))
      (stats/bump! (:counters client) :received-unparseable))
    (case (long (:qos msg 0))
      1 (send! client (MqttPubAck/encode {:packet-type :PUBACK
                                          :packet-identifier (:packet-identifier msg)}))
      2 (send! client (MqttPubRec/encode {:packet-type :PUBREC
                                          :packet-identifier (:packet-identifier msg)}))
      nil)))

(defn- handle [client msg]
  (case (:packet-type msg)
    :CONNACK  (.countDown ^CountDownLatch (:connack client))
    :SUBACK   (.countDown ^CountDownLatch (:suback client))
    :PUBLISH  (on-publish client msg)
    ;; Subscriber side of QoS 2: the broker's PUBREL closes it out.
    :PUBREL   (send! client (MqttPubComp/encode {:packet-type :PUBCOMP
                                                 :packet-identifier (:packet-identifier msg)}))
    ;; Publisher side.
    :PUBACK   (retire! client (:packet-identifier msg))
    :PUBREC   (send! client (MqttPubRel/encode {:packet-type :PUBREL
                                                :packet-identifier (:packet-identifier msg)}))
    :PUBCOMP  (retire! client (:packet-identifier msg))
    :PINGRESP nil
    nil))

;; ── lifecycle ─────────────────────────────────────────────────────────

(defn open!
  "Connect a client and send its CONNECT. Does not wait for the CONNACK —
   `await-connack` does, so a caller can open a thousand of these and then wait
   once, rather than paying a round trip per client."
  [{:keys [host port client-id index window counters
           service-latency response-latency ack-latency source-address]}]
  (let [client {:client-id         client-id
                :index             index
                :connack           (CountDownLatch. 1)
                :suback            (CountDownLatch. 1)
                :next-id           (AtomicInteger. 0)
                :inflight          (ConcurrentHashMap.)
                :window            (Semaphore. (int window))
                :counters          counters
                :service-latency   service-latency
                :response-latency  response-latency
                :ack-latency       ack-latency}
        holder (promise)
        handler (MqttHandler. ^clojure.lang.IFn (fn [msg _] (handle @holder msg)) 1)
        mqtt (MqttClient. ^String host ^int (int port) ^int (int 1) handler nil
                          ^String source-address)
        client (assoc client :mqtt mqtt)]
    (deliver holder client)
    (send! client (MqttConnect/encode {:packet-type :CONNECT :protocol-name "MQTT"
                                       :protocol-version 4 :keep-alive 0
                                       :clean-session? true :client-id client-id}))
    client))

(defn await-connack [client ^long ms]
  (.await ^CountDownLatch (:connack client) ms TimeUnit/MILLISECONDS))

(defn subscribe! [client topic ^long qos]
  (send! client (MqttSubscribe/encode {:packet-type :SUBSCRIBE
                                       :packet-identifier 1
                                       :topics [{:qos qos :topic-filter topic}]})))

(defn await-suback [client ^long ms]
  (.await ^CountDownLatch (:suback client) ms TimeUnit/MILLISECONDS))

(defn publish!
  "One message. `intended` is when the schedule wanted it sent; the gap to now
   is the generator's own lateness and travels in the payload so the subscriber
   can report both latencies.

   For QoS above 0 this first takes a slot in the in-flight window, and that
   wait is the generator noticing the broker has stopped acknowledging.

   Returns {:blocked-ns n :sent? bool}. Both matter to the caller: the wait has
   to be counted rather than disappear into the send rate, and whether the
   packet actually went is what decides if a delivery should be expected for
   it."
  ;; Unhinted for the same reason as put-header!: six parameters is past what
  ;; a primitive-taking fn can have.
  [client topic qos intended sequence size]
  (stats/bump! (:counters client) :attempted)
  (let [qos     (long qos)
        size    (long size)
        blocked (if (pos? qos)
                  (let [t0 (System/nanoTime)]
                    (.acquire ^Semaphore (:window client))
                    (- (System/nanoTime) t0))
                  0)
        id      (when (pos? qos) (next-packet-id (:next-id client)))
        payload (byte-array (max size header-bytes))
        now     (System/nanoTime)]
    (put-header! (ByteBuffer/wrap payload) (long intended) now (:index client) (long sequence))
    (when id
      (.put ^ConcurrentHashMap (:inflight client) id now))
    (let [ok (send! client (MqttPublish/encode
                            (cond-> {:packet-type :PUBLISH :topic topic :qos qos
                                     :payload payload :retain? false :duplicate? false}
                              id (assoc :packet-identifier id))))]
      (if ok
        (stats/bump! (:counters client) :published)
        (do (stats/bump! (:counters client) :failed)
            (when id
              (.remove ^ConcurrentHashMap (:inflight client) id)
              (.release ^Semaphore (:window client)))))
      {:blocked-ns blocked :sent? ok})))

(defn outstanding
  "Publishes sent and never acknowledged. Non-zero at the end of a run means
   the broker never finished with them, which a delivery count alone hides."
  ^long [client]
  (.size ^ConcurrentHashMap (:inflight client)))

(defn close! [client]
  (try (send! client (MqttDisconnect/encode)) (catch Exception _ nil))
  (try (.close ^MqttClient (:mqtt client)) (catch Exception _ nil)))
