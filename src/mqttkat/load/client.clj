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
            MqttPublish MqttPubRec MqttPubRel MqttSubscribe MqttUnsubscribe]))

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

(defn- v5
  "A packet map in the dialect of this client's connection: version 5 gets
   a property block, which 3.1.1 must not have."
  [client m]
  (if (:mqtt5? client)
    (assoc m :protocol-version 5 :properties {})
    m))

(defn- send! [client ^ByteBuffer buf]
  (try
    (.sendMessage ^MqttClient @(:mqtt client) buf)
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

(declare reopen!)

(def ^:private redirect-codes
  "Use another server, Server moved (§4.13): the two CONNACK reason codes
   that come with a Server Reference."
  #{0x9C 0x9D})

(defn- on-connack
  "Connected — or sent elsewhere. A version 5 broker may answer a CONNECT
   with a Server Reference; a client that follows redirects goes there and
   asks again, and is only counted connected when a broker takes it. A few
   hops at most: two brokers each pointing at the other would otherwise
   have this bouncing for ever."
  [client {:keys [reason-code properties]}]
  (let [code (bit-and 0xFF (long (or reason-code 0)))
        ref  (:server-reference properties)]
    (if (and (:follow-redirects? client) (contains? redirect-codes code) ref
             (< (.getAndIncrement ^AtomicInteger (:hops client)) 5))
      (do (stats/bump! (:counters client) :redirected)
          (reopen! client ref))
      (.countDown ^CountDownLatch (:connack client)))))

(defn- handle [client msg]
  (case (:packet-type msg)
    :CONNACK  (on-connack client msg)
    ;; The latch is one-shot, for the subscribe at setup. The counter is what
    ;; the cycling pool reads, and it is bumped on every SUBACK. bump! is
    ;; nil-safe on a missing key, so an ordinary client — whose counters have
    ;; no :subacks — pays nothing for this.
    :SUBACK   (do (.countDown ^CountDownLatch (:suback client))
                  (stats/bump! (:counters client) :subacks))
    :UNSUBACK (stats/bump! (:counters client) :unsubacks)
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
    ;; The other way a broker sends a client elsewhere (§4.13): accepted,
    ;; then told to go. Followed like the CONNACK form, and what this client
    ;; had asked for on the old connection is asked for again on the new.
    :DISCONNECT (let [code (bit-and 0xFF (long (or (:reason-code msg) 0)))
                      ref  (:server-reference (:properties msg))]
                  (when (and (:follow-redirects? client) (contains? redirect-codes code) ref
                             (< (.getAndIncrement ^AtomicInteger (:hops client)) 5))
                    (stats/bump! (:counters client) :redirected)
                    (reopen! client ref)))
    nil))

;; ── lifecycle ─────────────────────────────────────────────────────────

(defn- connect-packet [{:keys [client-id mqtt5?]}]
  (MqttConnect/encode (cond-> {:packet-type :CONNECT :protocol-name "MQTT"
                               :protocol-version (if mqtt5? 5 4) :keep-alive 0
                               :clean-session? true :client-id client-id}
                        mqtt5? (assoc :properties {}))))

(defn- open-socket!
  "A connection to `host`:`port` whose packets go to `handler`."
  [host port source-address handler]
  (MqttClient. ^String host ^int (int port) ^int (int 1) handler nil ^String source-address))

(defn open!
  "Connect a client and send its CONNECT. Does not wait for the CONNACK —
   `await-connack` does, so a caller can open a thousand of these and then wait
   once, rather than paying a round trip per client.

   `mqtt5?` connects in version 5, which is what lets a broker send the
   client elsewhere (§4.13); `follow-redirects?` has it go — a 3.1.1
   client is never sent, so for one it changes nothing."
  [{:keys [host port client-id index window counters mqtt5? follow-redirects?
           service-latency response-latency ack-latency source-address]}]
  (let [client {:client-id         client-id
                :index             index
                :mqtt5?            (boolean mqtt5?)
                :follow-redirects? (boolean follow-redirects?)
                :source-address    source-address
                :hops              (AtomicInteger. 0)
                :landed            (atom (str host ":" port))
                :subscribed        (atom nil)
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
        client (assoc client :handler handler
                      :mqtt (atom (open-socket! host port source-address handler)))]
    (deliver holder client)
    (send! client (connect-packet client))
    client))

(defn- reopen!
  "Go where the broker said: a fresh socket to `server-reference`, the same
   handler, and the CONNECT again. The old socket is closed by the broker
   that sent us on; closing it here as well is harmless."
  [client ^String server-reference]
  (let [[host port] (let [i (.lastIndexOf server-reference ":")]
                      [(subs server-reference 0 i) (parse-long (subs server-reference (inc i)))])
        old  @(:mqtt client)]
    (log/debug (:client-id client) "sent on to" server-reference)
    (try
      (reset! (:mqtt client) (open-socket! host port (:source-address client) (:handler client)))
      (reset! (:landed client) server-reference)
      (send! client (connect-packet client))
      ;; Sent on after subscribing — the DISCONNECT form of a redirect can
      ;; arrive after the SUBSCRIBE went out on the old socket — so ask
      ;; again here; the SUBACK latch is still waiting for the answer.
      (when-let [[topic qos] @(:subscribed client)]
        (send! client (MqttSubscribe/encode (v5 client {:packet-type :SUBSCRIBE
                                                        :packet-identifier 1
                                                        :topics [{:qos qos :topic-filter topic}]}))))
      (catch Exception e
        (log/warn e (:client-id client) "could not follow the redirect to" server-reference)
        ;; Counted connected so the run does not hang on it; it will show up
        ;; as a client that received nothing.
        (.countDown ^CountDownLatch (:connack client))))
    (try (.close ^MqttClient old) (catch Exception _ nil))))

(defn await-connack [client ^long ms]
  (.await ^CountDownLatch (:connack client) ms TimeUnit/MILLISECONDS))

(defn subscribe! [client topic ^long qos]
  ;; Remembered, so a client sent to another broker after subscribing can
  ;; subscribe there too.
  (reset! (:subscribed client) [topic qos])
  (send! client (MqttSubscribe/encode (v5 client {:packet-type :SUBSCRIBE
                                                  :packet-identifier 1
                                                  :topics [{:qos qos :topic-filter topic}]}))))

(defn unsubscribe!
  "Drop `topic`. For the cycling pool, which subscribes and unsubscribes while
   the run is going so the broker is mutating its trie under the fan-out rather
   than only at setup."
  [client topic]
  (reset! (:subscribed client) nil)
  (send! client (MqttUnsubscribe/encode (v5 client {:packet-type :UNSUBSCRIBE
                                                    :packet-identifier 2
                                                    :topics [topic]}))))

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
                            (v5 client
                                (cond-> {:packet-type :PUBLISH :topic topic :qos qos
                                         :payload payload :retain? false :duplicate? false}
                                  id (assoc :packet-identifier id)))))]
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
  (try (.close ^MqttClient @(:mqtt client)) (catch Exception _ nil)))

(defn landed-on
  "Where this client ended up connected, as \"host:port\" — the broker it
   was pointed at, or the one it was sent on to."
  [client]
  @(:landed client))
