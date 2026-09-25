(ns mqttkat.bridge
  "Broker to broker, in MQTT.

   With several brokers in front of one Rama, a publish on this broker may
   match subscriptions held by clients of another. Rama says which brokers
   those are — every broker's copy of the cluster's subscriptions names the
   broker on each entry — and this is how the message gets there: this broker
   is a client of that one, and publishes it on. One connection per peer,
   opened on first use, in the protocol both ends already speak, with the
   QoS 1 and 2 flows the client side of it owes.

   The receiving broker delivers to its own subscribers and no further. It
   knows a bridge by its client id, `mqttkat-bridge/<broker-id>`, and a
   publish arriving on one is never forwarded again — that is the whole of
   the loop prevention, and it is enough because every entry is held by
   exactly one broker: a message goes from the publisher's broker straight
   to each holder, one hop, never through a third.

   Not through Rama, on purpose. Rama holds the state the brokers share;
   the traffic between them is one TCP hop, and a depot append, a topology,
   a PState write and a proxy push per message would be several
   milliseconds and a disk write where a socket write will do.

   This namespace knows nothing about Rama or the broker. `forwarder` is the
   seam: whoever knows the other brokers installs a function there, and the
   publish path calls `forward!`."
  (:require [clojure.tools.logging :as log]
            [mqttkat.client :as client])
  (:import [java.io IOException]
           [java.util Set]
           [java.util.concurrent ConcurrentHashMap LinkedBlockingQueue Semaphore TimeUnit]
           [java.util.concurrent.atomic AtomicBoolean AtomicInteger LongAdder]
           [org.mqttkat MqttHandler MqttStat]
           [org.mqttkat.server Connection]))

(def client-id-prefix
  "What a bridge connection calls itself, followed by the broker it comes
   from. The receiving broker recognises it by this."
  "mqttkat-bridge/")

(defn bridge?
  "Whether `client-id` is another broker's bridge connection."
  [client-id]
  (boolean (and client-id (.startsWith ^String client-id client-id-prefix))))

;; ── the seam ─────────────────────────────────────────────────────────────

(defonce planner
  ;; (fn [topic] -> plan) or nil. Installed by mqttkat.rama.cluster when the
  ;; broker is attached to a cluster; nil means a broker on its own, which
  ;; forwards nothing and pays nothing for it.
  ;;
  ;; A plan is {:brokers {peer-id [group-key ...]} :skip #{group-key ...}}:
  ;; the other brokers to send this publish to, each with the shared groups
  ;; it is to serve, and the shared groups this broker must leave alone
  ;; because another broker serves them. A group-key is [group topic-filter],
  ;; which together are the identity of a share (§4.8.2).
  (atom nil))

(defn plan
  "Where a publish on `topic` has to go besides here, or nil."
  [topic]
  (when-let [f @planner]
    (f topic)))

;; ── shared groups on the wire ────────────────────────────────────────────

(def share-property
  "The user property a forwarded publish carries once per shared group the
   receiving broker is to serve: its value is `group/topic-filter`. A group
   name may not contain a slash (§4.8.2), so the first one is the split. A
   forwarded publish carrying none serves no shared group at the other end —
   the choice of which broker serves a group is made where the whole group
   is visible, on the publisher's broker, and every other broker is told."
  "mqttkat-share")

(defn- group-key->string [[group topic-filter]]
  (str group "/" topic-filter))

(defn- string->group-key [^String s]
  (let [slash (.indexOf s "/")]
    (when (pos? slash)
      [(subs s 0 slash) (subs s (inc slash))])))

(defn with-shares
  "`properties` with the share property for each of `group-keys`."
  [properties group-keys]
  (if (empty? group-keys)
    properties
    (update properties :user-properties
            (fn [ups] (into (vec ups) (map #(vector share-property (group-key->string %))) group-keys)))))

(defn take-shares
  "Split a bridged publish's `properties` into [group-keys properties']:
   the shared groups this broker is to serve, and the properties with those
   instructions removed — they were for this broker, not its subscribers."
  [properties]
  (let [ups    (:user-properties properties)
        shares (into #{} (keep (fn [[k v]] (when (= share-property k) (string->group-key v)))) ups)
        rest   (remove (fn [[k _]] (= share-property k)) ups)]
    [shares (if (seq rest)
              (assoc properties :user-properties (vec rest))
              (dissoc properties :user-properties))]))

;; ── connections to peers ─────────────────────────────────────────────────
;;
;; A link per peer: a queue, and one thread of its own that connects, waits
;; for window slots and writes. The broker's handler threads only enqueue.
;; They used to do all three themselves — open the connection and wait for
;; its CONNACK, wait for a slot in the peer's Receive Maximum, write to a
;; socket the peer may have stopped reading — and with four of them shared
;; by every client, two brokers forwarding QoS 1 to each other filled each
;; other's windows and then sat waiting for acknowledgements the other side's
;; handlers were too busy waiting to send: 6 s median latency at 5,000
;; publishes a second across three brokers, against 29 ms for the same
;; traffic at QoS 0.
;;
;; Back-pressure is what it is everywhere else in the broker: a publisher
;; whose messages have piled up in a link's queue stops being read until
;; the queue has drained.

(defonce ^:private connections
  ;; peer broker-id -> a link (see `start-link!`), or {:down-until millis}
  ;; after a failure to connect, so a peer that is not there is tried again
  ;; in a while rather than on every publish.
  (atom {}))

(def retry-after-ms
  "How long a peer that could not be reached is left alone."
  5000)

(def receive-maximum
  "The Receive Maximum a broker grants another broker's bridge (§3.2.2.3.3),
   in place of the one it gives clients. A client's window is kept small
   because the broker holds that many of its messages; a bridge carries
   every publish from one broker to another, and at a client's 128 its
   throughput was 128 per round trip, whatever the brokers could do. A
   quarter of the identifier space, not all of it: identifiers wrap at
   65,535, and a window as wide as that would reuse one still in flight."
  16384)

(def queue-pause-at
  "Queue depth at which a link holds the publisher of the message that
   took it there: stops reading its socket, as a subscriber falling behind
   does (see mqttkat.handlers/pause-threshold)."
  2048)

(def queue-resume-at
  "Queue depth at which the publishers a link holds are read again.
   Hysteresis, for the reason resume-threshold has it."
  512)

(def queue-limit
  "The backstop: a message arriving at a link this far behind is dropped
   and counted. Holding publishers keeps a queue well short of this; a
   message with no publisher to hold — a will, a queued session's backlog —
   is what could otherwise grow it without end."
  65536)

(defn- next-packet-id
  "1..65535, wrapping. Safe against reuse because nothing here holds an
   identifier for anywhere near that long."
  ^long [^AtomicInteger ids]
  (inc (mod (.getAndIncrement ids) 65535)))

(def connack-wait-ms
  "How long a new bridge waits for the peer's CONNACK, which carries the
   Receive Maximum the bridge must keep to."
  5000)

(def window-wait-ms
  "How long a publish waits for a slot in the peer's Receive Maximum before
   the peer is taken to have stopped acknowledging, and dropped."
  5000)

(defn- on-packet
  "What the peer sends back. A bridge subscribes to nothing, so this is its
   CONNACK and acknowledgements.

   Each acknowledgement that ends a QoS 1 or 2 flow gives a slot back to the
   window (§4.9): PUBACK, PUBCOMP, or a PUBREC that refuses the message
   (0x80 and up), which ends the flow there. Any other PUBREC is answered
   with the PUBREL the QoS 2 handshake needs, and the slot stays taken until
   the PUBCOMP."
  [holder window peer-id {:keys [packet-type packet-identifier reason-code properties] :as msg}]
  (let [release! #(when (realized? window) (.release ^Semaphore @window))
        code     (bit-and 0xFF (long (or reason-code 0)))]
    (case packet-type
      :CONNACK (if (>= code 0x80)
                 (log/warn "bridge to" peer-id "refused:" code)
                 (do (log/info "bridge to" peer-id "up")
                     ;; §3.2.2.3.3: absent means 65,535.
                     (deliver window (Semaphore. (int (or (:receive-maximum properties) 65535))))))
      :PUBACK  (release!)
      :PUBCOMP (release!)
      :PUBREC  (if (>= code 0x80)
                 (release!)
                 (when-let [c @holder]
                   (try
                     (client/send-message c {:packet-type :PUBREL :packet-identifier packet-identifier})
                     (catch IOException e
                       (log/debug "bridge to" peer-id "closed before its PUBREL went:" (.getMessage e))))))
      :DISCONNECT (log/info "bridge to" peer-id "closed by the other end:" (:reason-code msg))
      nil)))

(defn- open!
  "Connect to `peer` and introduce this broker, and wait for its CONNACK:
   that is where the peer says how many unacknowledged QoS 1 and 2 publishes
   it will take at once (§3.2.2.3.3), and a bridge that ignores it is
   dropped by the peer for exceeding it — with everything it had in flight.
   That happened at a few thousand QoS 2 messages a second, with the peer's
   window at 128. On the link's own thread, so the wait holds up nothing
   but the link."
  [my-id peer-id {:keys [host port]}]
  (let [holder  (atom nil)
        window  (promise)
        handler (MqttHandler. ^clojure.lang.IFn (fn [msg _] (on-packet holder window peer-id msg)) 1)
        c       (client/client host (int port) handler)]
    (reset! holder c)
    (client/send-message c {:packet-type      :CONNECT
                            :protocol-name    "MQTT"
                            :protocol-version 5
                            :keep-alive       0
                            :clean-session?   true
                            :client-id        (str client-id-prefix my-id)})
    (if-let [w (try (deref window connack-wait-ms nil)
                    ;; Dropped while waiting: as good as no answer.
                    (catch InterruptedException _ nil))]
      {:client c :window w}
      (do (try (client/close c) (catch Exception _ nil))
          (throw (IOException. (str "no CONNACK from " peer-id " within " connack-wait-ms " ms")))))))

;; ── holding publishers ───────────────────────────────────────────────────

(defn- release-holds!
  "Read every publisher this link holds again. Each is taken out of the set
   before it is resumed, so two releases racing resume each one once."
  [{:keys [^Set held]}]
  (when-not (.isEmpty held)
    (doseq [^Connection p (vec held)]
      (when (.remove held p)
        (.resumeReading p)))))

(defn- hold!
  "Stop reading `publisher` until this link's queue has drained. Paused
   first and recorded second, then the queue looked at again: the order the
   subscriber holds settled on (Connection.pauseUntilDrained), so a release
   landing in between cannot miss it. A publisher the link already holds
   is one hold, not two — its pause count is given back at once."
  [{:keys [^Set held ^AtomicBoolean running ^LinkedBlockingQueue queue] :as link} ^Connection publisher]
  (.pauseReading publisher)
  (when-not (.add held publisher)
    (.resumeReading publisher))
  (when (or (not (.get running)) (<= (.size queue) (long queue-resume-at)))
    (release-holds! link)))

;; ── the link ─────────────────────────────────────────────────────────────

(defn- write!
  "Send one queued message: a slot in the peer's window first for QoS 1 and
   2 (§4.9), then the write. A peer that frees no slot for window-wait-ms
   has stopped acknowledging, which ends the link."
  [{:keys [^AtomicInteger ids]} client ^Semaphore window {:keys [qos packet]}]
  (let [qos (long qos)]
    (when (and (pos? qos) (not (.tryAcquire window (long window-wait-ms) TimeUnit/MILLISECONDS)))
      (throw (IOException. (str "nothing acknowledged for " window-wait-ms " ms"))))
    (try
      (client/send-message client (cond-> packet (pos? qos) (assoc :packet-identifier (next-packet-id ids))))
      (catch IOException e
        (when (pos? qos) (.release window))
        (throw e)))))

(defn- forget-link!
  "Take `link` out of the connections, if it is still the one there —
   leaving `replacement` in its place, or nothing."
  [peer-id link replacement]
  (swap! connections (fn [m]
                       (if (identical? (get m peer-id) link)
                         (if replacement (assoc m peer-id replacement) (dissoc m peer-id))
                         m))))

(defn- run-link!
  "The link's thread: connect, then write whatever is queued, in order,
   until the link is dropped or the peer is lost. Whatever is still queued
   then is lost with it and counted, and every publisher it held is let go."
  [my-id peer-id peer {:keys [^LinkedBlockingQueue queue ^AtomicBoolean running client] :as link}]
  (let [opened (atom nil)]
    (try
      (let [{c :client window :window :as o} (open! my-id peer-id peer)]
        (reset! opened o)
        (deliver client c)
        (loop []
          (when (.get running)
            (when-let [item (.poll queue 200 TimeUnit/MILLISECONDS)]
              (write! link c window item)
              (when (<= (.size queue) (long queue-resume-at))
                (release-holds! link)))
            (recur))))
      (catch IOException e
        (cond
          (nil? @opened)
          (do (log/warn "bridge to" peer-id "at" (:host peer) (:port peer) "could not connect:" (.getMessage e))
              (forget-link! peer-id link {:down-until (+ (System/currentTimeMillis) (long retry-after-ms))}))
          (.get running)
          (log/warn "bridge to" peer-id "lost:" (.getMessage e))))
      (catch InterruptedException _ nil)
      (finally
        (.set running false)
        (forget-link! peer-id link nil)
        (let [lost (.size queue)]
          (.clear queue)
          (when (pos? lost)
            (.add ^LongAdder MqttStat/droppedMessages lost)
            (log/warn "bridge to" peer-id "gone with" lost "messages still queued for it")))
        (release-holds! link)
        (when-let [o @opened]
          (try (client/close (:client o)) (catch Exception _ nil)))))))

(defn- start-link!
  "A link to `peer-id`, its thread started. `:client` is delivered once the
   peer has accepted the connection."
  [my-id peer-id peer]
  (let [link {:queue   (LinkedBlockingQueue.)
              :held    (ConcurrentHashMap/newKeySet)
              :running (AtomicBoolean. true)
              :ids     (AtomicInteger. 0)
              :client  (promise)}
        t    (doto (Thread. ^Runnable (fn [] (run-link! my-id peer-id peer link))
                            (str "bridge-" peer-id))
               (.setDaemon true))]
    (assoc link :thread t)))

(defn- link!
  "The link to `peer-id`, started if there is none. nil if the peer was
   unreachable recently."
  [my-id peer-id peer]
  (let [existing (get @connections peer-id)]
    (if (some-> ^AtomicBoolean (:running existing) .get)
      existing
      (locking connections
        (let [{:keys [down-until running] :as existing} (get @connections peer-id)]
          (cond
            (some-> ^AtomicBoolean running .get) existing
            (and down-until (< (System/currentTimeMillis) (long down-until))) nil
            :else
            (let [link (start-link! my-id peer-id peer)]
              (swap! connections assoc peer-id link)
              (.start ^Thread (:thread link))
              link)))))))

(defn- enqueue!
  "Queue `packet` for `peer-id`, holding `publisher` if the queue has grown
   past queue-pause-at. Refused and counted past queue-limit."
  [my-id peer-id peer qos packet publisher]
  (when-let [{:keys [^LinkedBlockingQueue queue] :as link} (link! my-id peer-id peer)]
    (if (>= (.size queue) (long queue-limit))
      (do (.increment ^LongAdder MqttStat/droppedMessages)
          (log/debug "bridge to" peer-id "is" queue-limit "behind; dropping a publish on" (:topic packet)))
      (do (.put queue {:qos qos :packet packet})
          (when (and publisher (> (.size queue) (long queue-pause-at)))
            (hold! link publisher))))))

(defn drop!
  "Close and forget the connection to `peer-id`, if any: the registry says
   it is gone. Its thread lets go of what it held on the way out."
  [peer-id]
  (let [link (get @connections peer-id)]
    (swap! connections dissoc peer-id)
    (when-let [^AtomicBoolean running (:running link)]
      (.set running false)
      (.interrupt ^Thread (:thread link))
      (when (realized? (:client link))
        (try (client/close @(:client link)) (catch Exception _ nil))))))

(defn close-all! []
  (doseq [peer-id (keys @connections)]
    (drop! peer-id)))

(defn send-to!
  "Publish `msg` to `peer-id` at `peer` — {:host :port} — as this broker,
   telling it which shared groups are its to serve. Queued for the link's
   thread; `:publisher` in `msg`, the Connection it came in on, is what is
   held if the link falls behind.

   Retain is off on the way out: what is retained is recorded once, by the
   publisher's broker, and the other end must not store a copy under its own
   name. Version 5 on the wire whatever the publisher spoke, so the
   properties travel; the receiving broker strips them for its 3.1.1
   subscribers as it does for any publish."
  [my-id peer-id peer group-keys topic {:keys [qos payload properties publisher]}]
  (let [qos (long (or qos 0))]
    (enqueue! my-id peer-id peer qos
              {:packet-type      :PUBLISH
               :protocol-version 5
               :topic            topic
               :qos              qos
               :payload          payload
               :retain?          false
               :duplicate?       false
               :properties       (with-shares (or properties {}) group-keys)}
              publisher)))

(def control-prefix
  "Where an instruction to the other broker goes: a publish on a topic
   under this, over the bridge, is for the broker, not its subscribers."
  "$mqttkat/")

(defn takeover!
  "Tell `peer-id` that `client-id` has connected here, so the connection it
   holds for it — named by its connect-id, so a newer one is left alone —
   is to end (§3.1.4). QoS 0: if the peer is not there to hear it, the
   connection it held is not there either. Down the same queue as the
   publishes, so it is not overtaken by, and does not overtake, them."
  [my-id peer-id peer client-id connect-id]
  (enqueue! my-id peer-id peer 0
            {:packet-type      :PUBLISH
             :protocol-version 5
             :topic            (str control-prefix "takeover")
             :qos              0
             :payload          (byte-array 0)
             :retain?          false
             :duplicate?       false
             :properties       {:user-properties [["client-id" client-id]
                                                  ["connect-id" (str connect-id)]]}}
            nil))

(defonce forwarder
  ;; (fn [plan topic msg]) or nil, installed alongside `planner`: it knows
  ;; the peers' addresses and this broker's name, which this namespace does
  ;; not.
  (atom nil))

(defn forward!
  "Carry out `plan` for a publish of `msg` — {:qos :payload :properties} —
   on `topic`: one copy to each broker named, with its groups, and whatever
   else the planner put in the plan for the forwarder to do."
  [plan topic msg]
  (when-let [f @forwarder]
    (when plan
      (f plan topic msg))))

(defn peers
  "The peers this broker currently has a connection to."
  []
  (into #{} (keep (fn [[id {:keys [client]}]] (when (and client (realized? client)) id))) @connections))
