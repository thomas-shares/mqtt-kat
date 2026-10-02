(ns mqttkat.chaos.client
  "One client of a chaos run: a publisher or a subscriber that can be killed,
   reconnected to another broker, and made to subscribe and unsubscribe, and
   that writes all of it to the ledger.

   Unlike mqttkat.load.client, which measures, this one keeps what MQTT says a
   client keeps. A persistent subscriber reconnects with Clean Session 0 (in
   version 5, Clean Start 0 and a Session Expiry Interval) and expects the
   broker to have its session. A QoS 2 subscriber remembers, across
   reconnects, the packet identifiers it has PUBRECed and not yet seen a
   PUBREL for, so a PUBLISH the broker resends is recognised as a resend and
   not counted twice (§4.3.3, method B). A subscribe or unsubscribe the
   connection dropped under is sent again on the next one.

   Publishers use clean sessions and do not resend: a publish whose PUBACK or
   PUBCOMP never came is not a promise the broker made, and the checker treats
   it as one that may or may not arrive."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [mqttkat.chaos.ledger :as ledger])
  (:import [java.nio ByteBuffer]
           [java.nio.charset StandardCharsets]
           [java.util.concurrent ConcurrentHashMap ExecutorService Semaphore TimeUnit]
           [java.util.concurrent.atomic AtomicInteger AtomicLong LongAdder]
           [org.mqttkat MqttHandler]
           [org.mqttkat.client MqttClient]
           [org.mqttkat.packages MqttConnect MqttDisconnect MqttPubAck MqttPubComp
            MqttPublish MqttPubRec MqttPubRel MqttSubscribe MqttUnsubscribe]))

(set! *warn-on-reflection* true)

(defn make
  "A client that is not connected yet. `opts`: :id :kind (:pub or :sub) :idx
   :mqtt5? :persistent? :filter :sub-qos :window :session-expiry-s
   :follow-redirects?."
  [ledger {:keys [window] :or {window 32} :as opts}]
  (merge opts
         {:ledger       ledger
          :lock         (Object.)
          :conn         (atom nil)
          :epoch        (AtomicLong.)
          :down-until   (atom 0)
          :had-session? (atom false)
          :last-drop    (atom nil)
          :want-sub?    (atom (= :sub (:kind opts)))
          ;; "host:port" a broker sent this client on to (§4.13), for the
          ;; next connect; see take-redirect!.
          :redirect-to  (atom nil)
          :sub          (atom {:state :none})
          :awaiting-rel (ConcurrentHashMap/newKeySet)
          :inflight     (ConcurrentHashMap.)
          :window       (Semaphore. (int window))
          :next-id      (AtomicInteger.)
          :seq          (AtomicLong.)
          :counters     {:unparseable (LongAdder.)
                         :refused     (LongAdder.)
                         :drops       (LongAdder.)
                         :connects    (LongAdder.)
                         :redirected  (LongAdder.)}}))

(defn- bump! [c k] (.increment ^LongAdder (get-in c [:counters k])))

(defn- next-id ^long [c]
  (inc (mod (.getAndIncrement ^AtomicInteger (:next-id c)) 65535)))

(defn- v5 [c m]
  (if (:mqtt5? c) (assoc m :protocol-version 5 :properties (:properties m {})) m))

(defn connected?
  "Connected, and the CONNACK is in."
  [c]
  (boolean (:connected? @(:conn c))))

(defn broker-of [c] (:broker @(:conn c)))

(defn- send!
  [c ^ByteBuffer buf]
  (if-let [^MqttClient s (:socket @(:conn c))]
    (try (.sendMessage s buf) true
         (catch Exception e
           (log/debug e "send failed on" (:id c))
           false))
    false))

(defn- close-socket! [c]
  (when-let [^MqttClient s (:socket @(:conn c))]
    (try (.close s) (catch Exception _ nil))))

;; ── subscription state ────────────────────────────────────────────────
;;
;; :none, {:state :subscribing :pid :first-sent}, :subscribed, or
;; {:state :unsubscribing :pid}. Changed only under the client's lock.

(defn- send-subscribe! [c]
  (let [pid   (next-id c)
        first (or (:first-sent @(:sub c)) (ledger/now (:ledger c)))]
    (reset! (:sub c) {:state :subscribing :pid pid :first-sent first})
    (send! c (MqttSubscribe/encode
              (v5 c {:packet-type :SUBSCRIBE :packet-identifier pid
                     :topics [{:qos (:sub-qos c) :topic-filter (:filter c)}]})))))

(defn- send-unsubscribe! [c]
  (let [pid (next-id c)]
    (reset! (:sub c) {:state :unsubscribing :pid pid})
    (ledger/unsubscribe-sent! (:ledger c) (:id c) (ledger/now (:ledger c)))
    (send! c (MqttUnsubscribe/encode
              (v5 c {:packet-type :UNSUBSCRIBE :packet-identifier pid
                     :topics [(:filter c)]})))))

(defn- sync-subscription!
  "Make the broker's idea of the subscription match `want-sub?`, resending
   whatever was in flight when the last connection dropped."
  [c]
  (when (and (= :sub (:kind c)) (connected? c))
    (case (:state @(:sub c))
      :none          (when @(:want-sub? c) (send-subscribe! c))
      :subscribed    (when-not @(:want-sub? c) (send-unsubscribe! c))
      :subscribing   (send-subscribe! c)
      :unsubscribing (send-unsubscribe! c))))

(defn toggle-subscription!
  "Subscribe if unsubscribed and the other way round. Only from a settled
   state, so the ledger sees one SUBSCRIBE or UNSUBSCRIBE at a time. Returns
   :subscribe, :unsubscribe or nil."
  [c]
  (locking (:lock c)
    (when (connected? c)
      (case (:state @(:sub c))
        :none       (do (reset! (:want-sub? c) true) (send-subscribe! c) :subscribe)
        :subscribed (do (reset! (:want-sub? c) false) (send-unsubscribe! c) :unsubscribe)
        nil))))

(defn subscribed? [c] (= :subscribed (:state @(:sub c))))

;; ── losing a connection ───────────────────────────────────────────────

(defn- release-inflight!
  "A clean publisher's unacknowledged publishes went with its session; give
   their window slots back. They stay unacknowledged in the ledger."
  [c]
  (doseq [k (vec (.keySet ^ConcurrentHashMap (:inflight c)))]
    (when (.remove ^ConcurrentHashMap (:inflight c) k)
      (.release ^Semaphore (:window c)))))

(defn on-drop!
  "The connection is gone, at `at`. Whatever a clean session had, it no longer
   has: its subscription ends here."
  [c at]
  (locking (:lock c)
    (when @(:conn c)
      (close-socket! c)
      (reset! (:conn c) nil)
      (reset! (:last-drop c) at)
      (bump! c :drops)
      (case (:kind c)
        :pub (release-inflight! c)
        :sub (when-not (:persistent? c)
               (ledger/ended! (:ledger c) (:id c) :drop at)
               (reset! (:sub c) {:state :none})
               (.clear ^java.util.Set (:awaiting-rel c)))))))

(defn kill!
  "Take the client down for `down-ms`: gracefully (a DISCONNECT first, as a
   client going away on purpose would) or not (the socket just closes, as
   when a client crashes)."
  [c down-ms graceful?]
  (locking (:lock c)
    (when @(:conn c)
      (reset! (:down-until c) (+ (ledger/now (:ledger c)) (* 1000 (long down-ms))))
      (when graceful?
        (send! c (MqttDisconnect/encode
                  (v5 c {:packet-type :DISCONNECT :reason-code 0}))))
      (on-drop! c (ledger/now (:ledger c)))
      true)))

;; ── receiving ─────────────────────────────────────────────────────────

(defn- parse-id
  "[publisher seq] out of a payload \"<publisher>:<seq>|padding\"."
  [^bytes payload]
  (when payload
    (let [s (String. payload StandardCharsets/US_ASCII)
          i (str/index-of s "|")
          [p q] (when i (str/split (subs s 0 i) #":"))]
      (when (and p q)
        (try [(Long/parseLong p) (Long/parseLong q)] (catch Exception _ nil))))))

(def ^:private redirect-codes
  "Use another server, Server moved (§4.13): the two reason codes that come
   with a Server Reference, on a CONNACK or a DISCONNECT."
  #{0x9C 0x9D})

(defn- redirected!
  "Sent elsewhere by `msg`, a CONNACK or DISCONNECT: when this client follows
   redirects and the broker named a server, note it for the next connect and
   let this connection go. Returns whether it did."
  [c msg]
  (let [code (bit-and 0xFF (long (or (:reason-code msg) 0)))
        ref  (:server-reference (:properties msg))]
    (when (and (:follow-redirects? c) (contains? redirect-codes code) ref)
      (reset! (:redirect-to c) ref)
      ;; A DISCONNECT sending the client on follows a CONNACK that accepted
      ;; it without making it a session: the broker touched nothing. So the
      ;; client knows of a session only if it did before that CONNACK, or
      ;; the next broker's Session Present 0 would read as a lost one.
      (when-some [before (:had-session-before @(:conn c))]
        (reset! (:had-session? c) before))
      (bump! c :redirected)
      (on-drop! c (ledger/now (:ledger c)))
      true)))

(defn take-redirect!
  "The \"host:port\" the last broker sent this client on to, once."
  [c]
  (first (reset-vals! (:redirect-to c) nil)))

(defn- on-connack [c msg]
  (locking (:lock c)
    (let [code (bit-and 0xFF (long (or (:reason-code msg) (:connect-return-code msg) 0)))
          lg   (:ledger c)]
      (if (pos? code)
        (when-not (redirected! c msg)
          (bump! c :refused)
          (log/debug (:id c) "refused with" code)
          (on-drop! c (ledger/now lg)))
        (let [present? (boolean (:session-present? msg))]
          (swap! (:conn c) assoc :connected? true :had-session-before @(:had-session? c))
          (bump! c :connects)
          (when (and (:persistent? c) @(:had-session? c) (not present?))
            (let [at (or @(:last-drop c) (ledger/now lg))]
              (ledger/session-lost! lg (:id c) (ledger/now lg))
              (ledger/ended! lg (:id c) :session-lost at)
              (reset! (:sub c) {:state :none})
              (.clear ^java.util.Set (:awaiting-rel c))))
          (when (:persistent? c) (reset! (:had-session? c) true))
          (sync-subscription! c))))))

(defn- on-suback [c msg]
  (locking (:lock c)
    (let [{:keys [state pid first-sent]} @(:sub c)]
      (when (and (= :subscribing state) (= pid (:packet-identifier msg)))
        (let [granted (long (first (:response msg)))]
          (if (< granted 0x80)
            (do (ledger/subscribed! (:ledger c) (:id c) (:filter c) granted first-sent (broker-of c))
                (reset! (:sub c) {:state :subscribed})
                (sync-subscription! c))
            (do (bump! c :refused)
                (reset! (:sub c) {:state :none}))))))))

(defn- on-unsuback [c msg]
  (locking (:lock c)
    (let [{:keys [state pid]} @(:sub c)]
      (when (and (= :unsubscribing state) (= pid (:packet-identifier msg)))
        (ledger/ended! (:ledger c) (:id c) :unsubscribe (ledger/now (:ledger c)))
        (reset! (:sub c) {:state :none})
        (sync-subscription! c)))))

(defn- skip-properties
  "The payload of a version 5 PUBLISH that was decoded as 3.1.1, which
   happens to one that arrives before the CONNACK: the socket only learns it
   speaks 5 from the CONNACK's property block. What was read as the payload
   starts with the PUBLISH's own property block, a variable byte length and
   that many bytes."
  ^bytes [^bytes payload]
  (loop [i 0 len 0 shift 0]
    (when (< i (min 4 (alength payload)))
      (let [b (bit-and 0xFF (aget payload i))
            len (bit-or len (bit-shift-left (bit-and b 0x7F) shift))]
        (if (zero? (bit-and b 0x80))
          (let [from (+ i 1 len)]
            (when (<= from (alength payload))
              (java.util.Arrays/copyOfRange payload (int from) (alength payload))))
          (recur (inc i) len (+ shift 7)))))))

(defn- on-publish [c msg]
  (let [early? (not (connected? c))
        _      (when early?
                 ;; §3.2.0-1: the first packet from the server is the CONNACK.
                 (ledger/protocol-error! (:ledger c) (:id c) :publish-before-connack
                                         {:topic (:topic msg) :qos (:qos msg)}))
        id  (or (parse-id (:payload msg))
                (when (and early? (:mqtt5? c))
                  (some-> (:payload msg) skip-properties parse-id)))
        qos (long (:qos msg 0))
        pid (:packet-identifier msg)
        hand-on! #(if id
                    (ledger/delivered! (:ledger c) (:id c) id qos (broker-of c))
                    ;; Not one of ours, or ours mangled: a payload in the wrong
                    ;; protocol dialect has its property block in front.
                    (do (bump! c :unparseable)
                        (ledger/event! (:ledger c)
                                       {:type :unparseable :client (:id c) :qos qos
                                        :topic (:topic msg) :dup? (:duplicate? msg)
                                        :head (let [^bytes p (:payload msg)]
                                                (vec (take 12 (or p []))))})))]
    (case qos
      0 (hand-on!)
      1 (do (hand-on!)
            (send! c (MqttPubAck/encode (v5 c {:packet-type :PUBACK :packet-identifier pid}))))
      2 (do (when (.add ^java.util.Set (:awaiting-rel c) pid)
              (hand-on!))
            (send! c (MqttPubRec/encode (v5 c {:packet-type :PUBREC :packet-identifier pid})))))))

(defn- retire! [c pid ok?]
  (when-let [id (.remove ^ConcurrentHashMap (:inflight c) pid)]
    (when ok? (ledger/acked! (:ledger c) id))
    (.release ^Semaphore (:window c))))

(defn- failed? [msg] (>= (bit-and 0xFF (long (or (:reason-code msg) 0))) 0x80))

(defn- handle [c epoch msg]
  ;; A packet from a connection this client has already given up on — read
  ;; off the old socket after a reconnect — belongs to nothing any more.
  (when (= epoch (:epoch @(:conn c)))
    (case (:packet-type msg)
      :CONNACK  (on-connack c msg)
      :SUBACK   (on-suback c msg)
      :UNSUBACK (on-unsuback c msg)
      :PUBLISH  (on-publish c msg)
      :PUBREL   (do (.remove ^java.util.Set (:awaiting-rel c) (:packet-identifier msg))
                    (send! c (MqttPubComp/encode (v5 c {:packet-type :PUBCOMP
                                                        :packet-identifier (:packet-identifier msg)}))))
      :PUBACK   (retire! c (:packet-identifier msg) (not (failed? msg)))
      :PUBREC   (if (failed? msg)
                  (retire! c (:packet-identifier msg) false)
                  (send! c (MqttPubRel/encode (v5 c {:packet-type :PUBREL
                                                     :packet-identifier (:packet-identifier msg)}))))
      :PUBCOMP  (retire! c (:packet-identifier msg) true)
      ;; The other form of a redirect: accepted, then told to go.
      :DISCONNECT (locking (:lock c) (redirected! c msg))
      nil)))

;; ── connecting ────────────────────────────────────────────────────────

(defn- connect-packet [{:keys [id mqtt5? persistent? session-expiry-s]}]
  (MqttConnect/encode
   (cond-> {:packet-type :CONNECT :protocol-name "MQTT"
            :protocol-version (if mqtt5? 5 4) :keep-alive 0
            :clean-session? (not persistent?) :client-id id}
     mqtt5? (assoc :properties (if persistent?
                                 {:session-expiry-interval (long (or session-expiry-s 3600))}
                                 {})))))

(defn connect!
  "Open a connection to `broker` ({:n :host :port}) and send the CONNECT. The
   CONNACK arrives on the socket's own thread. False if the socket could not
   even be opened — a broker that is down."
  [c broker]
  (locking (:lock c)
    (when-not @(:conn c)
      (let [epoch   (.incrementAndGet ^AtomicLong (:epoch c))
            handler (MqttHandler. ^clojure.lang.IFn (fn [msg _] (handle c epoch msg))
                                  ^ExecutorService (identity nil))]
        (try
          (let [s (MqttClient. ^String (:host broker) (int (:port broker)) (int 1) handler nil)]
            (reset! (:conn c) {:socket s :broker (:n broker) :epoch epoch
                               :opened (ledger/now (:ledger c))})
            (send! c (connect-packet c))
            true)
          (catch java.io.IOException _
            false))))))

(defn check-connection!
  "Notice a connection that died under the client (its broker was killed,
   or closed it), or one whose CONNACK never came."
  [c connack-timeout-ms]
  (when-let [{:keys [^MqttClient socket connected? opened]} @(:conn c)]
    (let [now (ledger/now (:ledger c))]
      (when (or (not (.isConnected socket))
                (and (not connected?)
                     (> (- now (long opened)) (* 1000 (long connack-timeout-ms)))))
        (on-drop! c now)
        true))))

(defn wants-connection? [c]
  (and (nil? @(:conn c))
       (>= (ledger/now (:ledger c)) (long @(:down-until c)))))

(defn back-off! [c ms]
  (reset! (:down-until c) (+ (ledger/now (:ledger c)) (* 1000 (long ms)))))

(defn wake! [c] (reset! (:down-until c) 0))

;; ── publishing ────────────────────────────────────────────────────────

(defn payload ^bytes [pub seq size]
  (let [head (.getBytes (str pub ":" seq "|") StandardCharsets/US_ASCII)
        out  (byte-array (max (long size) (alength head)) (byte (int \.)))]
    (System/arraycopy head 0 out 0 (alength head))
    out))

(defn publish!
  "One message, if the publisher is connected and — above QoS 0 — has a free
   slot in its window within `wait-ms`. Returns :sent, :skipped or :failed."
  [c topic qos size wait-ms]
  (let [qos (long qos)
        {:keys [epoch broker connected?]} @(:conn c)]
    (cond
      (not connected?) :skipped
      (and (pos? qos)
           (not (.tryAcquire ^Semaphore (:window c) (long wait-ms) TimeUnit/MILLISECONDS))) :skipped
      :else
      (let [seq (.incrementAndGet ^AtomicLong (:seq c))
            id  [(:idx c) seq]
            pid (when (pos? qos) (next-id c))]
        (ledger/published! (:ledger c) id {:topic topic :qos qos :pub-broker broker})
        (when pid
          (.put ^ConcurrentHashMap (:inflight c) pid id)
          ;; Dropped between the check above and here: on-drop! has already
          ;; emptied the window, so this slot is ours to give back.
          (when-not (= epoch (:epoch @(:conn c)))
            (retire! c pid false)))
        (if (send! c (MqttPublish/encode
                      (v5 c (cond-> {:packet-type :PUBLISH :topic topic :qos qos
                                     :payload (payload (:idx c) seq size)
                                     :retain? false :duplicate? false}
                              pid (assoc :packet-identifier pid)))))
          :sent
          (do (when pid (retire! c pid false)) :failed))))))

(defn close! [c]
  (locking (:lock c)
    (when @(:conn c)
      (send! c (MqttDisconnect/encode (v5 c {:packet-type :DISCONNECT :reason-code 0})))
      (close-socket! c)
      (reset! (:conn c) nil))))

(defn counters [c]
  (into {} (map (fn [[k ^LongAdder a]] [k (.sum a)])) (:counters c)))
