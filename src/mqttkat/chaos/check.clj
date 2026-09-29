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

(defn- context
  "The chaos around a message for `client`: what happened to that client, and
   to any broker, from `before` it was sent until `after` its
   acknowledgement."
  [{:keys [xs ^longs ts]} client m before after]
  (let [lo (- (long (:sent m)) (long before))
        hi (+ (long (or (:acked m) (:sent m))) (long after))
        n  (alength ts)]
    (loop [i (first-at-or-after ts lo) acc (transient [])]
      (if (and (< i n) (<= (aget ts i) hi))
        (let [e (nth xs i)]
          (recur (inc i) (if (or (= client (:client e)) (broker-event? e)) (conj! acc e) acc)))
        (persistent! acc)))))

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
                                :ended-by (:ended-by sub)}))))
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
        lost       (volatile! 0)
        add!       (fn [v]
                     (let [n (inc (long (.getOrDefault counts (:kind v) 0)))]
                       (.put counts (:kind v) n)
                       (when (<= n (long max-violations))
                         (.add violations v))))
        bump!      (fn [i n] (aset stats (int i) (+ (aget stats (int i)) (long n))))]
    ;; What it was owed.
    (doseq [^java.util.Map$Entry e owed
            :let [id (.getKey e)
                  {:keys [required ended-by]} (.getValue e)]]
      (bump! 0 1)
      (if (delivery ds id)
        (bump! 1 1)
        (let [m     (get publishes id)
              ctx   (context events client m before after)
              near? (boolean (some broker-event? ctx))]
          ;; {1 {:near-broker-chaos 12 :elsewhere 0} 2 {...}}: a loss next to
          ;; a killed broker is a known gap (see thoughts.md); one elsewhere
          ;; is news.
          (vswap! lost-by update-in [required (if near? :near-broker-chaos :elsewhere)] (fnil inc 0))
          (vswap! lost inc)
          (add! {:kind :lost :client client :msg id :qos required
                 :session (select-keys (get clients client) [:persistent? :mqtt5? :filter :sub-qos])
                 :topic (:topic m) :sent (:sent m) :acked (:acked m)
                 :pub-broker (:pub-broker m)
                 ;; How the subscription that owed it ended: nil while it
                 ;; lasts.
                 :sub-ended-by ended-by
                 :near-broker-chaos? near?
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
            (case (long possible)
              1 (when (> n 1) (bump! 3 (dec n)))
              (when (> n 1)
                (add! {:kind :duplicate :client client :msg id :qos possible :times n
                       :at at :brokers brokers
                       :near-broker-chaos? (boolean (some broker-event?
                                                          (context events client m before after)))})))))))
    {:stats      (zipmap [:required :delivered-required :deliveries :qos1-repeats :optional-delivered]
                         (vec stats))
     :counts     (into {} counts)
     :violations (vec violations)
     :lost-by    @lost-by
     :lost       @lost}))

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
                               (cond-> (pos? (long (:lost r)))
                                 (assoc-in [:lost-clients client] (:lost r)))))
                         {:stats {:required 0 :delivered-required 0 :deliveries 0
                                  :qos1-repeats 0 :optional-delivered 0}
                          :counts {} :lost-by (sorted-map) :lost-clients {}}
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
     ;; The ten clients that lost the most: the report keeps only the first
     ;; :max-violations of each kind, all of which may be one client's.
     :lost-by-client (into {} (take 10 (sort-by (comp - val) (:lost-clients merged))))
     :violations (vec violations)}))
