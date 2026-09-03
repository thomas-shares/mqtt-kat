(ns mqttkat.handlers
  (:require [clojure.tools.logging :as log]
            [mqttkat.s :refer [*server*]]
            [overtone.at-at :as at]
            [clojurewerkz.triennium.mqtt :as tr])
  (:import [java.util.concurrent.atomic LongAdder]
           [org.mqttkat MqttStat]
           [java.nio.channels SelectionKey]
           [org.mqttkat.server Connection MqttServer]
           [org.mqttkat.packages MqttPublish
            MqttPubRel MqttPubAck MqttPubRec
            MqttPubComp MqttSubAck MqttPingResp MqttUnSubAck]))

(def max-packet-identifier
  "MQTT 3.1.1 §2.3.1: identifiers run 1..65535, and 0 is not one."
  65535)

(def inflight-window
  "How many unacknowledged QoS 1/2 messages the broker will hold for one
   client before it stops accepting more for that client.

   This is the resource that is actually scarce — identifiers are not, there
   are 65535 of them per connection. Keeping the window well under that is
   also what lets the counter below wrap without ever colliding with a live
   identifier. Mosquitto's equivalent, max_inflight_messages, defaults to 20."
  128)

(def pending-limit
  "How many QoS 1/2 messages will wait for a window slot before the broker
   gives up on them.

   The window alone is not a policy: something has to happen to the message
   that cannot have an identifier yet. Blocking the fan-out thread is what the
   old pool did, and it deadlocks a client that both publishes and subscribes.
   Disconnecting the subscriber, which is what this first tried, turns a busy
   moment into 408 dropped connections under load. So it waits here instead,
   and is refused only once this is full too — the same shape as the QoS 0
   queue limit, and what Mosquitto does with max_queued_messages."
  4096)

(def pause-threshold
  "Pending depth at which the broker stops reading from a publisher feeding
   this subscriber.

   Well below pending-limit on purpose. Clearing OP_READ stops new bytes
   arriving, but the publisher's reader thread still has whatever was already
   framed to work through, and every one of those publishes fans out. The gap
   between this and pending-limit is the headroom for that overshoot."
  512)

(def resume-threshold
  "Pending depth at which those publishers are read again. Hysteresis: resuming
   at the same depth that paused would flap the interest ops once per packet."
  128)

(def ^:dynamic *clients* (atom {}))
(def ^:dynamic *inflight* (atom {}))
(def ^:dynamic *subscriber-trie* (atom (tr/make-trie)))
(def ^:dynamic *outbound* (atom {}))
(def ^:dynamic *retained* (atom {}))  ;; {:topic {:qos qos :payload payload}})

(def my-pool (at/mk-pool))
(declare qos-0)
(declare qos-1-send)
(declare qos-2-send)
(declare remove-client!)

(defn publish-will [{:keys [topic qos retain payload]}]
  (log/trace "Sending will message on topic:" payload)
  (when-let [keys (tr/matching-vals @*subscriber-trie* topic)]
    (log/trace "Will keys:" keys)
    (case (long qos)
      0 (qos-0 keys topic {:payload payload} retain)
      1 (qos-1-send  keys topic {:payload payload})
      2 (qos-2-send keys topic {:payload payload}))))

(defn handle-will-if-present [key]
  (when (contains? (get @*clients* key) :will)
    (let [will-topic (get-in @*clients* [key :will :will-topic])
          will-qos   (get-in @*clients* [key :will :will-qos])
          will-message   (get-in @*clients* [key :will :will-message])
          will-retain (get-in @*clients* [key :will :will-retain])]
      (publish-will {:topic will-topic :qos will-qos :payload will-message :retain will-retain}))))

(defn check-timer
  "Drop `key` if nothing has been received from it for `time-out` ms.

   MQTT 3.1.1 §3.1.2.10: the server disconnects a client it has not heard from
   for one and a half times the Keep Alive interval, which is what add-timer!
   passes as `time-out`."
  [key time-out]
  (when-let [last-active (get-in @*clients* [key :last-active])]
    (let [idle (- (System/currentTimeMillis) @last-active)]
      (log/debug "timer fired:" time-out idle)
      (when (<= time-out idle)
        (log/debug "Timer fired for client:" key)
        (handle-will-if-present key)
        ;; TODO 
        ;; Remove Timer!!!
        ;; once we have sent the will message remove the will from the client,
        ;; so that it won't get send again.
        #_(swap! *clients* assoc-in [key] dissoc :will)
        (remove-client! key)
        (log/debug "about to close")
        ;; *server* holds the stop-server closure; the MqttServer itself lives
        ;; in its metadata, the same way send-buffer reaches it. The close is
        ;; guarded so a socket that has already gone cannot kill the timer.
        (try
          (.closeConnection ^MqttServer (:server (meta @*server*)) key)
          (catch Exception e
            (log/warn e "closing the connection of a timed-out client failed")))
        (log/debug "closed....")))))

(defn add-timer!
  [key time]
  (log/trace "adding client to timer" time " and key:   "key)
  (let [time-out (* 1500 time)]
    ;; Stamp liveness BEFORE scheduling. The job's initial delay starts running
    ;; the moment at/every is called, so a stamp taken afterwards leaves the
    ;; first tick measuring fractionally less than time-out of idleness — the
    ;; client then survives that cycle and is only reaped on the next one.
    (swap! *clients* assoc-in [key :last-active] (volatile! (System/currentTimeMillis)))
    (swap! *clients* assoc-in [key :timer]
           (at/every time-out #(check-timer key time-out) my-pool :initial-delay time-out)))
  (log/trace @*clients*))

(defn remove-timer! [key]
  (when-let [timer (get-in @*clients* [key :timer])]
    (at/kill timer)
    (swap! *clients* assoc-in [key :timer] nil)))

(defn add-client! [{:keys [client-key client-id clean-session?] :as msg}]
  (if (and (false? clean-session?)
           (contains? @*clients* client-id))
    (let [client (get @*clients* client-id)]
      (log/trace "client-id already exists:" client-id)
      (let [subscriptions (get-in client [:subscribed-topics])]
        (log/trace "subscriptions:" subscriptions)
        (doseq [topic subscriptions]
          (log/trace "Adding to sub-trie for topic:" (:topic-filter topic)  "   qos: " (:qos topic))
          (swap! *subscriber-trie* tr/insert (:topic-filter topic) {:client-key client-key :qos (:qos topic)})))
      (log/trace "client-id:" client-id)
      (swap! *clients* assoc client-key client)
      (swap! *clients* dissoc client-id))
    (let [client (dissoc msg :packet-type :client-key)
          client-added (update-in client [:subscribed-topics] (fnil conj #{}) )]
      (swap! *clients* assoc client-key client-added)))
 (log/trace "ADD: Subscriber trie POST:" @*subscriber-trie*)
 (log/trace "ADD: Clients:" @*clients*))


(defn remove-client! [key]
  (remove-timer! key)
  (log/trace "REMOVE: clean session?" (get-in @*clients* [key :clean-session?] true))
  (log/trace "key:" key)
  (if (get-in @*clients* [key :clean-session?] true)
    (let [client-id (get-in @*clients* [key :client-id])]
      ;; A clean session keeps nothing. Its in-flight records would otherwise
      ;; sit in *outbound* and *inflight* for the life of the process, since
      ;; only a reconnect under the same client-id ever reads them again.
      (when client-id
        (swap! *outbound* dissoc client-id)
        (swap! *inflight* #(into {} (remove (fn [[[id _] _]] (= id client-id))) %)))
      (swap! *clients* dissoc key))
    (let [client (get @*clients* key)
          client-id (:client-id client)
          subscribed-topics (:subscribed-topics client)]
      (log/trace "Removing subscribed topics: :" subscribed-topics)
      (doseq [topic subscribed-topics]
        (log/trace "Removing from sub-trie for topic:"  (:topic-filter topic)  "   qos: " (:qos topic))
        (swap! *subscriber-trie* tr/delete (:topic-filter  topic) {:client-key key :qos (:qos topic)}))
      ;; Parking the session under its client-id happens once, not once per
      ;; subscribed topic: these two were inside the doseq above, so a
      ;; persistent session with no subscriptions was dropped instead of kept,
      ;; and CONNACK then reported session-present? false on the reconnect.
      (swap! *clients* dissoc key)
      (swap! *clients* assoc client-id client)))
  (log/trace "REMOVE: Subscriber trie POST:" @*subscriber-trie*)
  (log/trace "REMOVE: Clients:" @*clients*))

;; ── packet identifiers ───────────────────────────────────────────────────
;;
;; These used to come from one global core.async channel holding 1024 values,
;; taken with a blocking <!!. That had four problems, two of them permanent
;; hangs rather than slowdowns:
;;
;;   * it leaked. Identifiers came back only via PUBACK/PUBCOMP, so every
;;     client that disconnected with unacknowledged messages burned its
;;     identifiers for good. After 1024 of those the take blocked forever and
;;     QoS 1/2 delivery stopped broker-wide, silently.
;;   * any client could break it. PUBACK returned whatever identifier the
;;     client sent, unchecked: an unsolicited one overfilled a channel sized
;;     exactly 1024 and blocked that connection's reader thread forever, and a
;;     duplicate put a live identifier back into circulation so the next
;;     delivery reused it.
;;   * it was global, where §2.3.1 scopes identifiers to a connection — 1024
;;     shared out among every client instead of 65535 each.
;;   * the take blocked the publisher's fan-out thread, which cost about 21%
;;     under load and deadlocks outright if a client that both publishes and
;;     subscribes ends up waiting on an identifier its own unread PUBACKs
;;     would have released.
;;
;; *outbound* already records what is in flight for a client, keyed by
;; client-id and deliberately outliving the connection so a persistent session
;; can be redelivered on reconnect. So it is the allocator: one place that
;; knows what is outstanding, rather than a pool that has to be kept in step
;; with it.

(defn- next-identifier
  "The next free identifier for this client, or nil if there is none.

   A plain wrapping counter is enough because `inflight-window` is far below
   65535: the counter cannot lap a live identifier. The containment check is
   belt and braces for a window raised carelessly."
  [{:keys [next-id inflight] :or {next-id 0}}]
  (loop [candidate (inc (mod next-id max-packet-identifier))
         tried     0]
    (cond
      (>= tried max-packet-identifier)  nil
      (contains? inflight candidate)    (recur (inc (mod candidate max-packet-identifier)) (inc tried))
      :else                             candidate)))

(defn- reserve
  "Record `msg` against a fresh identifier, or leave the state alone when it
   cannot be sent yet.

   It cannot be sent when the in-flight window is full — and also when anything
   is already waiting, unless this message is the head of that queue. MQTT
   3.1.1 §4.6 requires a client's messages to be delivered in the order they
   were published, and without the second check a fresh publish could take a
   slot the moment an acknowledgement freed one, overtaking everything queued
   behind it. That showed up as a handful of messages arriving early out of two
   hundred: the fan-out thread and the thread draining the queue on each
   acknowledgement were competing for the same slots."
  ([state msg] (reserve state msg false))
  ([state msg from-pending?]
   (let [inflight (:inflight state {})]
     (if (or (>= (count inflight) inflight-window)
             (and (not from-pending?) (seq (:pending state))))
       state
       (if-let [id (next-identifier state)]
         (assoc state :next-id id :inflight (assoc inflight id msg))
         state)))))

(defn acquire-packet-identifier!
  "Reserve an identifier for `client-id` and record `msg` against it.

   Returns the identifier, or nil when the client already has
   `inflight-window` messages outstanding. Never blocks: a client that has
   stopped acknowledging is the caller's problem to handle, not a reason to
   park the thread doing the fan-out."
  [client-id msg]
  (let [[before after] (swap-vals! *outbound* update client-id reserve msg)]
    (when (> (count (get-in after [client-id :inflight]))
             (count (get-in before [client-id :inflight])))
      (get-in after [client-id :next-id]))))

(defn release-packet-identifier!
  "Retire `id` for `client-id`.

   Returns the message that was in flight under it, or nil if this identifier
   was never issued — an unsolicited or duplicate acknowledgement, which is
   then ignored rather than acted on. That check is the whole defence against
   a client corrupting the identifier space."
  [client-id id]
  (let [[before _] (swap-vals! *outbound* update-in [client-id :inflight] dissoc id)]
    (get-in before [client-id :inflight id])))

(defn inflight-count
  "How many messages are outstanding for `client-id`."
  [client-id]
  (count (get-in @*outbound* [client-id :inflight])))

(defn pending-count
  "How many messages are waiting for a window slot for `client-id`."
  [client-id]
  (count (get-in @*outbound* [client-id :pending])))

(defn queue-pending!
  "Hold `msg` for `client-id` until a window slot frees up.

   Returns true if it was queued, false if this client's pending queue is full
   too — at which point the message is refused, which is the only honest thing
   left: it has not been delivered and nothing is pretending otherwise."
  [client-id msg]
  (let [[before after]
        (swap-vals! *outbound* update client-id
                    (fn [state]
                      (if (>= (count (:pending state)) pending-limit)
                        state
                        ;; PersistentQueue, not a vector: this is a FIFO whose
                        ;; head is removed once per acknowledgement, and
                        ;; dropping the head of a vector copies the whole
                        ;; thing. At a 4096-deep queue that copy, inside a
                        ;; swap! on a contended atom, cost about 6x the
                        ;; broker's publish throughput.
                        (update state :pending
                                (fnil conj clojure.lang.PersistentQueue/EMPTY) msg))))]
    (> (count (get-in after [client-id :pending]))
       (count (get-in before [client-id :pending])))))

(defn take-pending!
  "Reserve an identifier for the next message waiting on `client-id`'s window.

   Returns [identifier msg], or nil when nothing is waiting or the window is
   still full. Called as acknowledgements come back, so the queue drains at
   exactly the rate the client is acknowledging."
  [client-id]
  (let [[before after]
        (swap-vals! *outbound* update client-id
                    (fn [state]
                      (if-let [msg (peek (:pending state))]
                        ;; from-pending?: this message *is* the head, so the
                        ;; queue being non-empty must not block it.
                        (let [reserved (reserve state msg true)]
                          (if (identical? reserved state)
                            state                     ; window still full
                            (update reserved :pending pop)))
                        state)))]
    (when (< (count (get-in after [client-id :pending]))
             (count (get-in before [client-id :pending])))
      [(get-in after [client-id :next-id])
       (peek (get-in before [client-id :pending]))])))

(declare send-buffer)

(defn- send-publish!
  [key {:keys [topic payload qos]} packet-identifier]
  (send-buffer [key]
               (MqttPublish/encode {:packet-type       :PUBLISH
                                    :payload           payload
                                    :topic             topic
                                    :qos               qos
                                    :retain?           false
                                    :duplicate?        false
                                    :packet-identifier packet-identifier})))

(defn- connection-of ^Connection [key]
  (when key
    (.attachment ^SelectionKey key)))

(defn- throttle-publisher!
  "Stop reading from the publisher feeding a subscriber that is filling up, and
   record it so the subscriber releases it once drained."
  [subscriber-key publisher-key]
  (when-let [subscriber (connection-of subscriber-key)]
    (when-let [publisher (connection-of publisher-key)]
      (.pauseUntilDrained subscriber publisher))))

(defn- deliver-or-queue!
  "Send `msg` to a subscriber if its window has room; hold it if not.

   Holding is not enough on its own — an unbounded hold is just the old
   unbounded queue by another name — so once the queue passes `pause-threshold`
   the publisher that is filling it stops being read. That is the whole point:
   QoS 1 is at-least-once, so the pressure has to go back to the source rather
   than be paid for in dropped messages. The refusal below it is a backstop for
   memory, and under back-pressure it should never fire."
  [key client-id msg publisher-key]
  (if-let [packet-identifier (acquire-packet-identifier! client-id msg)]
    (send-publish! key msg packet-identifier)
    (do
      (when-not (queue-pending! client-id msg)
        (.increment ^LongAdder MqttStat/droppedMessages))
      (when (>= (pending-count client-id) pause-threshold)
        (throttle-publisher! key publisher-key)))))

(defn- drain-pending!
  "Send the next message waiting on this client's window, if any, and let any
   throttled publishers go once the queue is comfortably clear.

   The release check runs on every acknowledgement rather than only when the
   queue empties, so a pause that raced a drain is undone on the next ack
   instead of sticking."
  [key client-id]
  (when-let [[packet-identifier msg] (take-pending! client-id)]
    (send-publish! key msg packet-identifier))
  (when (<= (pending-count client-id) resume-threshold)
    (when-let [subscriber (connection-of key)]
      (.drained subscriber))))

#_(defn send-message [keys msg]
    (log/debug "sending message  from  clj" (:packet-type msg) " " (:packet-identifier msg))
    (log/trace (class  keys))
    (let [s (:server (meta @*server*))]))
;  (.sendMessage ^MqttServer s keys msg)))

(defn update-timestamps
  "Mark these clients as alive.

   One read of *clients* per client, not two: this used to check that
   :last-active was present and then look it up again to write it, and the
   client could disconnect in between — the second lookup then returned nil and
   vreset! threw. A client going away while a packet of its own is still being
   handled is ordinary, so it is skipped rather than reported."
  [client-keys]
  (doseq [client-key client-keys]
    (when-let [last-active (get-in @*clients* [client-key :last-active])]
      (vreset! last-active (System/currentTimeMillis)))))

(defn send-buffer [keys buf]
  (log/trace "sending buffer from clj")
  (log/trace  keys)
  ;; No update-timestamps here on purpose: keep alive measures the time since
  ;; a packet was RECEIVED from the client, so writing to it proves nothing.
  ;; mqttkat.server/default-handler-fn marks liveness on the inbound path.
  (let [{s :server} (meta @*server*)]
    (.sendMessageBuffer ^MqttServer s keys buf)))

(defn send-buffer-droppable
  "Fan out a QoS 0 publish.

   Unlike send-buffer, a subscriber that is already far behind may refuse
   these: QoS 0 is at-most-once, so dropping degrades that subscriber's feed
   instead of costing the broker unbounded memory. Refusals show up as
   :dropped in the stats line."
  [keys buf publisher-key]
  (let [{s :server} (meta @*server*)]
    (.sendMessageBuffer ^MqttServer s keys buf true publisher-key)))

(defn qos-0 [keys topic {:keys [payload] publisher-key :client-key} retain]
  (log/trace "--> respond QOS 0 topic:" topic " retained: " retain " payload: " payload " count keys: " (count keys))
  ;; Nothing is owed to the publisher at QoS 0, so with no subscribers there is
  ;; nothing to do — and no reason to encode a packet for nobody.
  (when (seq keys)
    (send-buffer-droppable (mapv :client-key keys)
                           (MqttPublish/encode {:packet-type :PUBLISH
                                                :payload     payload
                                                :topic       topic
                                                :qos         0
                                                :retain?     retain})
                           ;; nil for a will or a replayed retained message:
                           ;; the broker is the publisher there and there is
                           ;; nothing to slow down.
                           publisher-key)))

(defn qos-1-send [keys topic {:keys [payload] publisher-key :client-key}]
  (log/trace "respond qos 1:" (count keys) )
  (doseq [key (mapv :client-key keys)]
    ;; No client-id means the subscriber went away between the trie lookup and
    ;; here, which is ordinary — there is nobody left to deliver to.
    (when-let [client-id (:client-id (get @*clients* key))]
      (deliver-or-queue! key client-id {:topic topic :payload payload :qos 1}
                         publisher-key))))

(defn qos-n? [num {:keys [qos] :as m}]
  (when (= num qos) m))

(defn qos-0? [m]
  (qos-n? 0 m))

(defn qos-1? [m]
  (qos-n? 1 m))

(defn qos-2? [m]
  (qos-n? 2 m))

(defn qos-1-or-2? [m]
  ((some-fn qos-1? qos-2?) m))

(defn qos-1 [keys topic {:keys [client-key packet-identifier] :as msg}]
  (log/trace  "qos 1 received... " (count keys))
  (send-buffer [client-key]
               (MqttPubAck/encode {:packet-type       :PUBACK
                                   :packet-identifier packet-identifier}))
  (some-> (filter qos-0? keys)
          (seq)
          (qos-0 topic msg false))
  (some-> (filter qos-1-or-2? keys)
          (seq)
          (qos-1-send topic msg)))

;  (doseq [k qos-1-keys]
;    (log/trace "K" k)
;    (swap! outbound assoc (:client-key k) (:packet-identifier msg))))))

(defn qos-2 [_keys topic {:keys [client-key packet-identifier] :as recv-msg}]
  (log/trace "QOS 2")
  ;; Keyed by client-id, not by the SelectionKey. A client that disconnects
  ;; between PUBREC and PUBREL comes back on a different key, and the broker
  ;; could then never find the message it had already taken responsibility for:
  ;; it answered the PUBCOMP and dropped the publish, and the entry sat in
  ;; *inflight* for the life of the process.
  ;;
  ;; The matched subscribers are deliberately not stored either. MQTT 3.1.1
  ;; §4.3.3 publishes the message when PUBREL arrives, so the subscribers are
  ;; whoever is subscribed then — and any key captured at PUBLISH time may
  ;; belong to a connection that has since gone.
  (let [client-id (:client-id (get @*clients* client-key))]
    (swap! *inflight* assoc [client-id packet-identifier] {:msg recv-msg :topic topic}))
  (send-buffer [client-key]
               (MqttPubRec/encode {:packet-type       :PUBREC
                                   :packet-identifier packet-identifier})))

(defn publish [{:keys [topic qos retain? payload] :as msg}]
  (log/debug "PUBLISH:" (dissoc msg :client-key))
  (log/trace "Matched Keys:" (tr/matching-vals @*subscriber-trie* topic))
  ;(log/trace (str "valid publish: " (s/valid? :mqtt/publish msg)))
  ;(s/explain :mqtt/publish msg)
  (when retain?
    (log/trace "publish with retain:" topic qos (empty? payload))
    (if (empty? payload)
      (swap! *retained* dissoc topic)
      (swap! *retained* assoc topic {:qos qos :payload payload})))
  ;; `let` rather than `when-let`, which is what this was. The two behave the
  ;; same here only because triennium returns #{} for a topic nobody is
  ;; subscribed to, and an empty set is truthy — so the acknowledgements below
  ;; were always sent. `let` says that on purpose instead of by accident:
  ;; PUBACK and PUBREC are the receiver's answer for the packet, not a report
  ;; on delivery (§4.3.2, §4.3.3), so they must not be conditional on there
  ;; being subscribers. A `matching-vals` that returned nil for no match would
  ;; otherwise have left a QoS 1 publisher retrying for ever.
  (let [keys (tr/matching-vals @*subscriber-trie* topic)]
    (case (long qos)
      0 (qos-0 keys topic msg false)
      1 (qos-1 keys topic msg)
      2 (qos-2 keys topic msg))))

(defn puback [{:keys [packet-identifier client-key]}]
  (log/debug "PUBACK:" packet-identifier)
  (let [client-id (:client-id (get @*clients* client-key))]
    (if (release-packet-identifier! client-id packet-identifier)
      ;; A slot just freed, so let the next message waiting on it through.
      (drain-pending! client-key client-id)
      ;; An acknowledgement for something never sent. Ignoring it is the point:
      ;; acting on it used to put a live identifier back into circulation.
      (log/debug "PUBACK from" client-id "for identifier" packet-identifier
                 "which was never issued to it - ignored"))))

(defn pubrec [{:keys [client-key packet-identifier]}]
  (log/debug "PUBREC:" packet-identifier)
  (send-buffer [client-key]
               (MqttPubRel/encode
                {:packet-type :PUBREL :packet-identifier packet-identifier})))

(defn qos-2-send [keys topic {:keys [payload] publisher-key :client-key :as msg}]
  (some-> (filter qos-0? keys)
          (seq)
          (qos-0 topic msg false))
  (some-> (filter qos-1? keys)
          (seq)
          (qos-1-send topic msg))
  (doseq [key (some->> (filter qos-2? keys)
                       (seq)
                       (mapv :client-key))]
    (when-let [client-id (:client-id (get @*clients* key))]
      (deliver-or-queue! key client-id {:topic topic :payload payload :qos 2}
                         publisher-key))))

;;there is no need to do
(defn pubrel
  [{:keys [packet-identifier client-key]}]
  (log/debug "received (PUBREL:" packet-identifier)
  (send-buffer [client-key]
               (MqttPubComp/encode {:packet-type       :PUBCOMP
                                    :packet-identifier packet-identifier}))
  (let [client-id (:client-id (get @*clients* client-key))
        {:keys [topic msg]} (get @*inflight* [client-id packet-identifier])]
    (when topic
      (qos-2-send (tr/matching-vals @*subscriber-trie* topic) topic msg))
    (swap! *inflight* dissoc [client-id packet-identifier])))

(defn pubcomp [{:keys [packet-identifier client-key] :as msg}]
  (log/debug "received PUBCOMP:" (dissoc msg :client-key))
  (let [client-id (:client-id (get @*clients* client-key))]
    (if (release-packet-identifier! client-id packet-identifier)
      (drain-pending! client-key client-id)
      (log/debug "PUBCOMP from" client-id "for identifier" packet-identifier
                 "which was never issued to it - ignored"))))

(defn add-subscriber [subscribers topic key]
  (if (contains? subscribers topic)
    (update-in subscribers [topic] conj key)
    (assoc subscribers topic [key])))

(defn process-retained-messages [key]
  (log/trace "key:" key)
  (log/trace "subscribers:" @*subscriber-trie*)
  (log/trace "retained topics:"  (keys @*retained*))
  (doseq [retained-topic (keys @*retained*)]
    (log/trace "Total subscribed:"   @*subscriber-trie*)
    (log/trace "subscribed  -->:" (tr/matching-vals @*subscriber-trie* retained-topic))
    (let [keys (set (mapv #(:client-key %) (tr/matching-vals @*subscriber-trie* retained-topic)))]
      (log/trace "Yes there are keys subscribed to this topic:" (> (count keys) 0))
      (log/trace "key is contained:" (contains? keys key))
      (log/trace "retained topic:" (get-in @*retained* [retained-topic]))
      (when (contains? keys key)
        (when-let [payload (get-in @*retained* [retained-topic :payload])]
          (log/trace "retained payload:" payload)
          (case (long (get-in @*retained* [retained-topic :qos]))
            0 (qos-0 [{:client-key key}] retained-topic {:payload payload} true)
            1 (qos-1-send [{:client-key key}] retained-topic {:payload payload})
            2 (qos-2-send [{:client-key key}] retained-topic {:payload payload})))))))

(defn subscribe [{:keys [client-key topics packet-identifier] :as msg}]
  (log/debug "SUBSCRIBE:" (dissoc msg :client-key))
  (log/trace "Subscribed PRE ADD:" @*subscriber-trie*)
  (doseq [{:keys [topic-filter qos]} topics]
    ;(swap! subscribers add-subscriber (:topic-filter t) client-key)
    (swap! *clients* update-in [client-key :subscribed-topics] conj {:topic-filter topic-filter :qos qos})
    (swap! *subscriber-trie* tr/insert topic-filter {:client-key client-key :qos qos}))
  (log/trace "subscribers POST ADD:" @*subscriber-trie*)
  (send-buffer [client-key]
               (MqttSubAck/encode
                {:packet-type       :SUBACK
                 :packet-identifier packet-identifier
                 :response          (mapv #(long (:qos %)) topics)}))
  (process-retained-messages client-key))

(defn unsubscribe
  [{:keys [topics client-key] :as msg}]
  (log/debug "UNSUBSCRIBE:" (dissoc msg :client-key))
  ;(swap! subscribers remove-subsciber (:topics msg) (:client-key msg))
  ;;TODO remove message from outbound messages.. but check if this is really the case.
  (doseq [topic topics]
    (let [qos (:qos (first (filter #(= topic (:topic-filter %))  (get-in @*clients* [client-key :subscribed-topics]))))]
      (log/trace "Unsubscribing from topic:" topic qos)
      (swap! *clients* update-in [client-key :subscribed-topics] disj {:topic-filter topic :qos qos})
      (log/trace "Unsubscribing from trie :" topic " client-key: " client-key)
      (swap! *subscriber-trie* tr/delete topic {:client-key client-key :qos qos})))
  (send-buffer [client-key]
               (MqttUnSubAck/encode
                {:packet-type       :UNSUBACK
                 :packet-identifier (:packet-identifier msg)}))
  (log/trace "Unsubscribed trie:" @*subscriber-trie*)
  (log/trace "Unsubscribed clients:" (get-in @*clients* [client-key])))

(defn pingreq [{:keys [client-key] :as msg}]
  (log/debug "PINGREQ:" (dissoc msg :client-key))
  (send-buffer [client-key] (MqttPingResp/encode {:packet-type :PINGRESP})))

(defn pingresp [msg]
  (log/debug "PINGRESP:" (dissoc msg :client-key)))

(comment
  (defn remove-subsciber [m [topic] key]
    (update m topic (fn [v] (filterv #(not= key %) v))))

  (defn remove-client-subscriber [m val]
    (into {} (map (fn [[k v]] (let [nv (filterv #(not= val %) v)] {k nv})) m))))

(defn authenticate [msg]
  (log/debug "AUTHENTICATE:" msg))
