(ns mqttkat.chaos.check
  "What a chaos run is judged by: given everything the clients saw, did every
   message arrive as its QoS promised?

   Pure: a map of what happened goes in, a map of what went wrong comes out, so
   the rules can be tested without a broker. Times are microseconds since the
   run started; nil is \"never\".

   The input, as mqttkat.chaos.ledger/snapshot builds it:

     :publishes     {[pub seq] {:topic :qos :sent :acked :pub-broker}}
                    :acked is when the PUBACK or PUBCOMP arrived, nil if it
                    never did (the publisher dropped first, or it was QoS 0).
     :subscriptions {client-id [{:filter :qos :sub-sent :from :to :ended-by}]}
                    One entry per subscription, from the SUBSCRIBE (:sub-sent)
                    and its SUBACK (:from) to the UNSUBACK or the end of the
                    session (:to, nil while it lasts). :ended-by is
                    :unsubscribe, :drop (a clean session's connection went) or
                    :session-lost; :unsub-sent is when the UNSUBSCRIBE went.
     :deliveries    {client-id Delivered}
                    What the client handed on, once per PUBLISH for QoS 0 and
                    1, once per packet identifier for QoS 2 — a QoS 2 PUBLISH
                    resent before its PUBREL is the protocol retrying, not a
                    second delivery. Anything that satisfies Delivered: a
                    plain {[pub seq] [{:at :qos :broker}]} does, and so does
                    the ledger's compact record, which a long run needs — one
                    map entry per delivery ran a ten-minute run out of heap.
     :sessions-lost [{:client :at}]
     :protocol      [{:client :what :at ...}]
     :events        [{:at :type ...}]  the chaos, for putting losses in context

   The rules, per subscription and matching message, at the effective QoS —
   the lower of the publish and the subscription:

     required  QoS 1 or 2, acknowledged to the publisher, published at least
               :subscribe-settle after the SUBACK, and acknowledged at least
               :clean-grace before the UNSUBSCRIBE went: what the broker still
               had queued for the subscription then it may drop (§3.10.4). A
               clean session's subscription likewise needs its connection to
               have outlived the acknowledgement by :clean-grace: what was
               still on its way when a clean session dropped is legitimately
               gone.
     possible  anything published between the SUBSCRIBE going out and the
               UNSUBACK coming back, or acknowledged no more than
               :subscribe-settle before the SUBSCRIBE went: an acknowledgement
               is not the moment the broker matched the message, which is
               after the PUBACK, and on another broker after the copy
               forwarded there arrives. Arriving outside that is :unexpected.

   and what counts as wrong:

     :lost            a required message that never arrived
     :duplicate       QoS 2 more than once, or QoS 0 more than once (at most
                      once is its whole promise). QoS 1 may repeat: counted,
                      not wrong.
     :unexpected      a delivery no subscription of that client could explain
     :qos-upgraded    delivered at a QoS above what publish and subscription
                      allow
     :session-lost    a persistent session the broker no longer had on
                      reconnect
     :protocol        something MQTT forbids whatever the QoS: a PUBLISH
                      before the CONNACK (§3.2.0-1)
     :nothing-checked no message was owed to anyone, so the run proves
                      nothing"
  (:require [clojure.string :as str]))

(defn matches?
  "Whether topic filter `f` matches `topic`, + and # included."
  [^String f ^String topic]
  (loop [[a & fs] (str/split f #"/" -1)
         [b & ts :as all] (str/split topic #"/" -1)]
    (cond
      (= "#" a)                   true
      (and (nil? a) (empty? all)) true
      (or (nil? a) (empty? all))  false
      (or (= "+" a) (= a b))      (recur fs ts)
      :else                       false)))

(def ^:private forever Long/MAX_VALUE)

(defprotocol Delivered
  "What one client was handed."
  (delivered-ids [d] "The [pub seq] of every message it was handed at least once.")
  (delivery [d id]
    "For a message it was handed, {:n :max-qos} — how many times, at most
     which QoS — plus :at and :brokers where they were kept; nil if never."))

(extend-protocol Delivered
  nil
  (delivered-ids [_] nil)
  (delivery [_ _] nil)

  clojure.lang.IPersistentMap
  (delivered-ids [m] (keys m))
  (delivery [m id]
    (when-let [ds (seq (get m id))]
      {:n       (count ds)
       :max-qos (reduce max 0 (map #(long (:qos %)) ds))
       :at      (mapv :at ds)
       :brokers (mapv :broker ds)})))

(defn- effective-qos [m sub] (min (long (:qos m)) (long (:qos sub))))

(defn- required?
  [{:keys [subscribe-settle clean-grace]} m sub]
  (let [{:keys [sent acked]} m
        {:keys [from unsub-sent to ended-by]} sub]
    (and acked
         (pos? (effective-qos m sub))
         from
         (< (+ (long from) (long subscribe-settle)) (long sent))
         (case ended-by
           nil          true
           ;; §3.10.4: on UNSUBSCRIBE the server MAY drop what it has
           ;; buffered for the subscription and not yet begun to send. Under
           ;; load a subscriber's queue runs seconds behind, so what was
           ;; acknowledged just before the UNSUBSCRIBE is not owed either:
           ;; only what had the grace to get out first.
           :unsubscribe (< (+ (long acked) (long clean-grace)) (long (or unsub-sent forever)))
           ;; A clean session's messages die with its connection, and so do a
           ;; lost session's, which is its own violation.
           (:drop :session-lost)
           (< (+ (long acked) (long clean-grace)) (long to))))))

(defn- latest-match
  "The last moment a broker may still have been matching `m` against the
   subscriptions: :subscribe-settle after it was acknowledged."
  ^long [{:keys [subscribe-settle]} m]
  (if-let [acked (:acked m)]
    (+ (long acked) (long subscribe-settle))
    (long forever)))

(defn- possible?
  "Whether `sub` could account for a delivery of `m`: the subscription existed
   at some moment between the publish going out and the brokers being done
   matching it."
  [opts m sub]
  (and (<= (long (:sub-sent sub)) (latest-match opts m))
       (<= (long (:sent m)) (long (or (:to sub) forever)))))

(defn- broker-event? [e]
  (contains? #{:kill-broker :stop-broker :broker-up} (:type e)))

;; ── looking things up by time ──────────────────────────────────────────
;;
;; A long run has over a million publishes and a subscriber resubscribes all
;; run long, so every subscription against every message grows with the
;; square of the run's length: ten minutes of chaos/long.edn took longer to
;; check than to run. Messages and events are sorted by time once, and each
;; subscription or loss looks only at its own stretch of them.

(defn- first-at-or-after
  "The index of the first of the ascending `ts` that is at least `t`."
  ^long [^longs ts ^long t]
  (loop [lo 0 hi (alength ts)]
    (if (< lo hi)
      (let [mid (unsigned-bit-shift-right (+ lo hi) 1)]
        (if (< (aget ts mid) t) (recur (inc mid) hi) (recur lo mid)))
      lo)))

(defn- by-time
  "`xs` sorted by `(t x)`, with the times alongside for first-at-or-after."
  [t xs]
  (let [v (vec (sort-by t xs))]
    {:xs v :ts (long-array (map t v))}))

(defn- events-between
  "What happened to `client`, and to any broker, from `lo` to `hi`."
  [{:keys [xs ^longs ts]} client lo hi]
  (let [n (alength ts)]
    (loop [i (first-at-or-after ts lo) acc (transient [])]
      (if (and (< i n) (<= (aget ts i) (long hi)))
        (let [e (nth xs i)]
          (recur (inc i) (if (or (= client (:client e)) (broker-event? e)) (conj! acc e) acc)))
        (persistent! acc)))))

(defn- outages
  "[{:broker :from :to}]: each stretch a broker was down, from the kill or
   stop to its :broker-up, or to forever when it never came back. A broker
   takes tens of seconds to come back, far longer than the :down-ms it is
   left for, and the events alone mark only the two ends of that."
  [events]
  (let [[open acc]
        (reduce (fn [[open acc] {:keys [type broker at]}]
                  (case type
                    (:kill-broker :stop-broker) [(update open broker #(or % at)) acc]
                    :broker-up (if-let [from (get open broker)]
                                 [(dissoc open broker) (conj acc {:broker broker :from from :to at})]
                                 [open acc])
                    [open acc]))
                [{} []] (:xs events))]
    (into acc (for [[b from] open] {:broker b :from from :to forever}))))

(defn- down-during?
  "Whether one of `brokers` was down at some moment between `lo` and `hi`. A
   run that kills a broker every ten seconds, each away for twenty, has one
   down nearly all the time, so \"some broker was down\" says nothing: only
   the brokers a message and its subscriber were using count."
  [outages brokers lo hi]
  (boolean (some #(and (contains? brokers (:broker %))
                       (<= (long (:from %)) (long hi)) (>= (long (:to %)) (long lo)))
                 outages)))

(defn- brokers-used
  "The brokers `client` touched from its last event before `lo` to `hi`: where
   it was when the window began, and wherever it went in it. `by-client` is
   its :connected and :dropped events by client, in time order."
  [by-client client lo hi]
  (let [evs (get by-client client)
        [before in] (split-with #(< (long (:at %)) (long lo)) evs)]
    (into (if-let [e (peek (vec before))] #{(:broker e)} #{})
          (comp (take-while #(<= (long (:at %)) (long hi))) (map :broker))
          in)))

(defn- near-outage?
  "Whether a broker `m` or `client` was using was down while `m` was on its
   way, from `lo` to `hi`."
  [{:keys [outages by-client]} client m lo hi]
  (and (seq outages)
       (down-during? outages
                     (conj (brokers-used by-client client lo hi) (:pub-broker m))
                     lo hi)))

(defn- context
  "The chaos around a message for `client`: what happened to that client, and
   to any broker, from `before` it was sent until `after` its
   acknowledgement."
  [events client m before after]
  (events-between events client
                  (- (long (:sent m)) (long before))
                  (+ (long (or (:acked m) (:sent m))) (long after))))

(defn- matcher
  "matches?, remembered: a run has a handful of filters and topics."
  []
  (let [seen (java.util.concurrent.ConcurrentHashMap.)]
    (fn [f topic]
      (.computeIfAbsent seen [f topic]
                        (reify java.util.function.Function
                          (apply [_ _] (matches? f topic)))))))

(defn- required-for
  "msg -> {:required q :ended-by} for what one client's subscriptions were
   owed. A message is only owed if it went out after the SUBACK and before the
   subscription ended, so each subscription looks at that stretch alone."
  [opts match? by-topic subs]
  (let [acc (java.util.HashMap.)]
    (doseq [sub subs
            :when (:from sub)
            [topic {:keys [xs ^longs ts]}] by-topic
            :when (match? (:filter sub) topic)]
      (let [;; required? bounds what was sent by when it was acknowledged,
            ;; and that comes after it was sent.
            hi (long (or (case (:ended-by sub)
                           nil          nil
                           :unsubscribe (:unsub-sent sub)
                           (:to sub))
                         forever))
            n  (alength ts)]
        (loop [i (first-at-or-after ts (inc (+ (long (:from sub)) (long (:subscribe-settle opts)))))]
          (when (and (< i n) (<= (aget ts i) hi))
            (let [[id m] (nth xs i)]
              (when (required? opts m sub)
                (let [q (effective-qos m sub)
                      v (.get acc id)]
                  (.put acc id {:required (max q (long (or (:required v) 0)))
                                :ended-by (:ended-by sub)
                                :broker   (:broker sub)}))))
            (recur (inc i))))))
    acc))

(defn- sub-index
  "One client's subscriptions for possible-qos: sorted by when they ended, with
   the earliest SUBSCRIBE among each one and all that ended after it."
  [subs]
  (let [end              #(long (or (:to %) forever))
        {:keys [xs ts]}  (by-time end subs)
        n                (count xs)
        earliest         (long-array n)]
    (loop [i (dec n) lo (long forever)]
      (when (>= i 0)
        (let [lo (min lo (long (:sub-sent (nth xs i))))]
          (aset earliest i lo)
          (recur (dec i) lo))))
    {:xs xs :ends ts :earliest earliest}))

(defn- possible-qos
  "The highest QoS any subscription in `idx` could have handed `m` on at, nil
   if none could have. Only those that ended after it was sent, and only until
   the rest all started after it was acknowledged."
  [opts match? {:keys [xs ^longs ends ^longs earliest]} m]
  (let [sent  (long (:sent m))
        acked (latest-match opts m)
        cap   (long (:qos m))
        n     (alength ends)]
    (loop [i (first-at-or-after ends sent) best nil]
      (if (and (< i n) (<= (aget earliest i) acked))
        (let [sub (nth xs i)]
          (if (and (match? (:filter sub) (:topic m)) (possible? opts m sub))
            (let [q (effective-qos m sub)]
              (if (= q cap) q (recur (inc i) (max q (long (or best 0))))))
            (recur (inc i) best)))
        best))))

(defn- in-flight-at-kill
  "{qos {:in-flight n :acked-after n :never-acked n}}: publishes still
   unacknowledged when the broker they went to was killed or stopped, and
   whether they were acknowledged afterwards, by a retransmission to a
   broker that kept the session. A clean publisher's are :never-acked, which
   promises nothing; a persistent one's that are :acked-after must then
   have arrived, which :lost judges as for any acknowledged message."
  [publishes events]
  (let [downs (->> (:xs events)
                   (filter #(contains? #{:kill-broker :stop-broker} (:type %)))
                   (group-by :broker))
        ;; per broker, the times it went down, ascending
        at    (update-vals downs #(vec (sort (map :at %))))]
    (reduce
     (fn [acc {:keys [qos sent acked pub-broker]}]
       (let [t (when (and (pos? (long qos)) pub-broker)
                 (some #(when (>= (long %) (long sent)) %) (get at pub-broker)))]
         (if (and t (or (nil? acked) (> (long acked) (long t))))
           (-> acc
               (update-in [qos :in-flight] (fnil inc 0))
               (update-in [qos (if acked :acked-after :never-acked)] (fnil inc 0)))
           acc)))
     (sorted-map)
     publishes)))

;; ── the verdict ────────────────────────────────────────────────────────

(defn- check-client
  "One client's share of the verdict: what it was owed and what it was handed.
   Keeps the first `max-violations` of each kind, and counts all of them."
  [{:keys [publishes subscriptions deliveries clients]}
   {:keys [context-window max-violations] :as opts}
   match? by-topic events client]
  (let [[before after] context-window
        ds         (get deliveries client)
        subs       (get subscriptions client)
        ^java.util.HashMap owed (required-for opts match? by-topic subs)
        idx        (sub-index subs)
        stats      (long-array 5)       ; required delivered-required deliveries qos1-repeats optional-delivered
        counts     (java.util.HashMap.)
        violations (java.util.ArrayList.)
        lost-by    (volatile! {})
        lost-route (volatile! {})
        lost-by-session (volatile! {})
        dup-by     (volatile! {})
        dup-resent (volatile! {})
        lost-resent (volatile! 0)
        lost       (volatile! 0)
        lost-sent  (volatile! {})
        lost-span  (volatile! nil)
        add!       (fn [v]
                     (let [n (inc (long (.getOrDefault counts (:kind v) 0)))]
                       (.put counts (:kind v) n)
                       (when (<= n (long max-violations))
                         (.add violations v))))
        bump!      (fn [i n] (aset stats (int i) (+ (aget stats (int i)) (long n))))]
    ;; What it was owed.
    (doseq [^java.util.Map$Entry e owed
            :let [id (.getKey e)
                  {:keys [required ended-by broker]} (.getValue e)]]
      (bump! 0 1)
      (if (delivery ds id)
        (bump! 1 1)
        (let [m     (get publishes id)
              ctx   (context events client m before after)
              near? (or (boolean (some broker-event? ctx))
                        (near-outage? opts client m
                                      (- (long (:sent m)) (long before))
                                      (+ (long (or (:acked m) (:sent m))) (long after))))]
          ;; {1 {:near-broker-chaos 12 :elsewhere 0} 2 {...}}: a loss next to
          ;; a killed broker is a known gap (see thoughts.md); one elsewhere
          ;; is news.
          (vswap! lost-by update-in [required (if near? :near-broker-chaos :elsewhere)] (fnil inc 0))
          ;; Published on one broker, owed by a subscription on another: which
          ;; link a loss is on, when the subscription's broker is known.
          (vswap! lost-route update [(:pub-broker m) broker] (fnil inc 0))
          ;; A kept session moves at the final reconnect, a clean one
          ;; stays: which of the two lost it says much about where.
          (vswap! lost-by-session update (if (:persistent? (get clients client)) :kept :clean) (fnil inc 0))
          (vswap! lost inc)
          (when (pos? (long (:resends m 0))) (vswap! lost-resent inc))
          ;; When the lost ones were sent, by the second, and this client's
          ;; first and last: a loss throughout the run is another bug from one
          ;; around the final reconnect.
          (let [s (quot (long (:sent m)) 1000000)]
            (vswap! lost-sent update s (fnil inc 0))
            (vswap! lost-span (fn [[lo hi]] [(min (long (or lo s)) s) (max (long (or hi s)) s)])))
          (add! {:kind :lost :client client :msg id :qos required
                 :session (select-keys (get clients client) [:persistent? :mqtt5? :filter :sub-qos])
                 :topic (:topic m) :sent (:sent m) :acked (:acked m)
                 :pub-broker (:pub-broker m)
                 :sub-broker broker
                 ;; How the subscription that owed it ended: nil while it
                 ;; lasts.
                 :sub-ended-by ended-by
                 :near-broker-chaos? near?
                 :publisher-resent? (pos? (long (:resends m 0)))
                 :context ctx}))))
    ;; What it was handed.
    (doseq [id (delivered-ids ds)
            :let [{:keys [n max-qos at brokers]} (delivery ds id)
                  m        (get publishes id)
                  required (:required (.get owed id))
                  n        (long n)]]
      (bump! 2 n)
      (when-not required (bump! 4 1))
      (let [possible (when m
                       (if (and required (= (long required) (long (:qos m))))
                         required
                         (possible-qos opts match? idx m)))]
        (cond
          (nil? m)
          (add! {:kind :unexpected :client client :msg id :why "no such publish"})

          (nil? possible)
          (add! {:kind :unexpected :client client :msg id :qos (:qos m) :topic (:topic m)
                 :sent (:sent m) :acked (:acked m) :pub-broker (:pub-broker m) :at at
                 ;; The SUBSCRIBE nearest after it: most of these were
                 ;; published just before one.
                 :next-sub-sent (some #(when (> (long (:sub-sent %)) (long (:sent m))) (:sub-sent %))
                                      (sort-by :sub-sent subs))
                 :context (context events client m before after)})

          :else
          (do
            (when (> (long max-qos) (long possible))
              (add! {:kind :qos-upgraded :client client :msg id :allowed possible
                     :got max-qos}))
            ;; QoS 1 may repeat, and so may a message a publisher sent again
            ;; at QoS 1: the broker cannot tell a resend from a second
            ;; publish, so a subscriber of any QoS may get it twice. A QoS 2
            ;; publish resent is held to its exactly-once promise: the
            ;; broker has its packet identifier on record.
            (case (long (if (and (== 1 (long (:qos m))) (pos? (long (:resends m 0)))) 1 possible))
              1 (when (> n 1) (bump! 3 (dec n)))
              (when (> n 1)
                (vswap! dup-by update possible (fnil inc 0))
                (when (pos? (long (:resends m 0)))
                  (vswap! dup-resent update possible (fnil inc 0)))
                (add! {:kind :duplicate :client client :msg id :qos possible :times n
                       :at at :brokers brokers
                       :session (select-keys (get clients client) [:persistent? :mqtt5? :filter :sub-qos])
                       :sent (:sent m) :acked (:acked m) :pub-broker (:pub-broker m)
                       ;; The publisher sent it twice: the broker that took the
                       ;; second may not have had the first's packet identifier.
                       :publisher-resent? (pos? (long (:resends m 0)))
                       :near-broker-chaos? (or (boolean (some broker-event?
                                                              (context events client m before after)))
                                               (near-outage? opts client m
                                                             (- (long (:sent m)) (long before))
                                                             (+ (long (reduce max (long (:sent m)) (or at [])))
                                                                (long after))))
                       ;; From the publish to the last copy: a second copy
                       ;; can come long after the first, from wherever the
                       ;; client went in between.
                       :context (events-between events client
                                                (- (long (:sent m)) (long before))
                                                (+ (long (reduce max (long (:sent m)) (or at [])))
                                                   (long after)))})))))))
    {:stats      (zipmap [:required :delivered-required :deliveries :qos1-repeats :optional-delivered]
                         (vec stats))
     :counts     (into {} counts)
     :violations (vec violations)
     :lost-by    @lost-by
     :lost-route @lost-route
     :lost-by-session @lost-by-session
     :duplicate-by @dup-by
     :duplicate-resent @dup-resent
     :lost-resent @lost-resent
     :lost       @lost
     :lost-sent  @lost-sent
     :lost-span  @lost-span}))

(defn check
  "The verdict on a run. `opts`: :subscribe-settle and :clean-grace in
   microseconds, :context-window [before after] likewise, and
   :max-violations, how many of each kind are kept with their detail — the
   counts are of all of them. Clients are checked in parallel."
  [{:keys [publishes subscriptions deliveries sessions-lost events protocol] :as run}
   {:keys [subscribe-settle clean-grace context-window max-violations]
    :or   {subscribe-settle 0 clean-grace 0 context-window [2000000 5000000]
           max-violations 1000}
    :as   opts}]
  (let [opts     (assoc opts :subscribe-settle subscribe-settle :clean-grace clean-grace
                        :context-window context-window :max-violations max-violations)
        match?   (matcher)
        by-topic (into {} (for [[topic ms] (group-by (comp :topic val) publishes)]
                            [topic (by-time (comp :sent val) ms)]))
        events   (by-time :at events)
        opts     (assoc opts :outages (outages events)
                        :by-client (group-by :client (filter #(contains? #{:connected :dropped} (:type %))
                                                             (:xs events))))
        clients  (distinct (concat (keys subscriptions) (keys deliveries)))
        per      (pmap #(check-client run opts match? by-topic events %) clients)
        ;; Each client kept its first max-violations of a kind; of those, the
        ;; report keeps the first max-violations over all clients.
        kept     (java.util.HashMap.)
        violations (java.util.ArrayList.)
        keep!    (fn [v]
                   (let [n (inc (long (.getOrDefault kept (:kind v) 0)))]
                     (.put kept (:kind v) n)
                     (when (<= n (long max-violations))
                       (.add violations v))))
        merged   (reduce (fn [acc [client r]]
                           (run! keep! (:violations r))
                           (-> acc
                               (update :stats #(merge-with + % (:stats r)))
                               (update :counts #(merge-with + % (:counts r)))
                               (update :lost-by #(merge-with (partial merge-with +) % (:lost-by r)))
                               (update :lost-route #(merge-with + % (:lost-route r)))
                               (update :lost-by-session #(merge-with + % (:lost-by-session r)))
                               (update :duplicate-by #(merge-with + % (:duplicate-by r)))
                               (update :duplicate-resent #(merge-with + % (:duplicate-resent r)))
                               (update :lost-resent + (:lost-resent r))
                               (update :lost-sent #(merge-with + % (:lost-sent r)))
                               (cond-> (pos? (long (:lost r)))
                                 (-> (assoc-in [:lost-clients client] (:lost r))
                                     (assoc-in [:lost-spans client] (:lost-span r))))))
                         {:stats {:required 0 :delivered-required 0 :deliveries 0
                                  :qos1-repeats 0 :optional-delivered 0}
                          :counts {} :lost-by (sorted-map) :lost-route {} :lost-clients {}
                          :lost-by-session {} :duplicate-by (sorted-map) :duplicate-resent (sorted-map) :lost-resent 0 :lost-sent {}
                          :lost-spans {}}
                         (map vector clients per))
        counts   (atom (:counts merged))
        add!     (fn [v]
                   (swap! counts update (:kind v) (fnil inc 0))
                   (keep! v))
        stats    (:stats merged)]
    (doseq [{:keys [client at]} sessions-lost]
      (add! {:kind :session-lost :client client :at at}))
    (doseq [p protocol]
      (add! (assoc p :kind :protocol)))
    ;; A run in which nothing was owed proves nothing, and must not pass: the
    ;; brokers were never reached, or nobody stayed subscribed long enough.
    (when (zero? (long (:required stats)))
      (add! {:kind :nothing-checked
             :why  "no message was owed to any subscriber - were the brokers reachable?"}))
    {:ok?        (empty? @counts)
     :stats      (assoc stats
                        :published (count publishes)
                        :acked (count (filter (comp :acked val) publishes))
                        :subscribers (count subscriptions))
     :counts     @counts
     :lost-by    (:lost-by merged)
     ;; [publisher's broker, subscriber's broker] -> how many were lost.
     :lost-route (into (sorted-map-by #(compare (str %1) (str %2))) (:lost-route merged))
     ;; {:kept n :clean n}: lost by sessions that are kept, and clean ones.
     :lost-by-session (:lost-by-session merged)
     ;; {qos n}: second deliveries by QoS — 2 or 0, as 1 may repeat.
     :duplicate-by (:duplicate-by merged)
     ;; Of those, the ones whose publisher had sent the message twice.
     :duplicate-resent (:duplicate-resent merged)
     ;; How many of the lost were of a message its publisher sent twice.
     :lost-resent (:lost-resent merged)
     ;; The ten clients that lost the most: the report keeps only the first
     ;; :max-violations of each kind, all of which may be one client's.
     :lost-by-client (into {} (take 10 (sort-by (comp - val) (:lost-clients merged))))
     ;; {client [first last]}: the seconds into the run the ten clients
     ;; above sent the first and the last of what they lost.
     :lost-span  (into {} (for [[c _] (take 10 (sort-by (comp - val) (:lost-clients merged)))]
                            [c (get-in merged [:lost-spans c])]))
     ;; {10 n, 20 n, ...}: lost, by the ten seconds into the run they were
     ;; sent in.
     :lost-by-sent (into (sorted-map)
                         (reduce-kv (fn [m s n] (update m (* 10 (quot (long s) 10)) (fnil + 0) n))
                                    {} (:lost-sent merged)))
     ;; {qos {:in-flight :acked-after :never-acked}}: publishes a broker's
     ;; death caught unacknowledged, and how they ended.
     ;; [{:broker :from :to}] in microseconds into the run: when each broker
     ;; was really down, kill to listening again.
     :outages (:outages opts)
     :in-flight-at-kill (in-flight-at-kill (map val publishes) events)
     ;; {qos {:published :acked :resent}}: what the publishers got out of the
     ;; brokers, by the QoS they published at.
     :published-by-qos (reduce (fn [acc {:keys [qos acked resends]}]
                                 (cond-> (update-in acc [qos :published] (fnil inc 0))
                                   acked    (update-in [qos :acked] (fnil inc 0))
                                   resends  (update-in [qos :resent] (fnil inc 0))))
                               (sorted-map) (map val publishes))
     :violations (vec violations)}))
