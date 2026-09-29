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
               UNSUBACK coming back. Arriving outside that is :unexpected.

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

(defn- possible?
  "Whether `sub` could account for a delivery of `m`: the subscription existed
   at some moment between the publish going out and being acknowledged."
  [m sub]
  (and (<= (long (:sub-sent sub)) (long (or (:acked m) forever)))
       (<= (long (:sent m)) (long (or (:to sub) forever)))))

(defn- broker-event? [e]
  (contains? #{:kill-broker :stop-broker :broker-up} (:type e)))

(defn- context
  "The chaos around a message for `client`: what happened to that client, and
   to any broker, from `before` it was sent until `after` its
   acknowledgement."
  [events client m before after]
  (let [lo (- (long (:sent m)) (long before))
        hi (+ (long (or (:acked m) (:sent m))) (long after))]
    (filterv #(and (<= lo (long (:at %)) hi)
                   (or (= client (:client %)) (broker-event? %)))
             events)))

(defn- verdicts-for
  "msg -> {:required q-or-nil :possible max-q} for one client's
   subscriptions. One client at a time: across every client at once this was
   a map entry per subscriber per message, which a long run cannot hold."
  [opts by-topic topics subs]
  (let [acc (java.util.HashMap.)]
    (doseq [sub subs
            topic topics
            :when (matches? (:filter sub) topic)
            [id m] (get by-topic topic)]
      (let [q    (effective-qos m sub)
            req? (required? opts m sub)
            can? (possible? m sub)]
        (when (or req? can?)
          (let [v (.get acc id)]
            (.put acc id (cond-> (or v {})
                           req? (-> (update :required (fnil max 0) q)
                                    (assoc :ended-by (:ended-by sub)))
                           can? (update :possible (fnil max 0) q)))))))
    acc))

(defn check
  "The verdict on a run. `opts`: :subscribe-settle and :clean-grace in
   microseconds, :context-window [before after] likewise, and
   :max-violations, how many of each kind are kept with their detail — the
   counts are of all of them."
  [{:keys [publishes subscriptions deliveries sessions-lost events clients protocol]}
   {:keys [subscribe-settle clean-grace context-window max-violations]
    :or   {subscribe-settle 0 clean-grace 0 context-window [2000000 5000000]
           max-violations 1000}
    :as   opts}]
  (let [opts           (assoc opts :subscribe-settle subscribe-settle :clean-grace clean-grace)
        [before after] context-window
        by-topic       (group-by (comp :topic val) publishes)
        topics         (keys by-topic)
        violations     (atom [])
        counts         (atom {})
        lost-by        (atom (sorted-map))
        lost-clients   (atom {})
        stats          (atom {:required 0 :delivered-required 0 :deliveries 0
                              :qos1-repeats 0 :optional-delivered 0})
        add!           (fn [v]
                         (swap! counts update (:kind v) (fnil inc 0))
                         (when (< (long (get @counts (:kind v))) (long (inc max-violations)))
                           (swap! violations conj v)))]
    (doseq [client (distinct (concat (keys subscriptions) (keys deliveries)))
            :let [ds       (get deliveries client)
                  verdicts (verdicts-for opts by-topic topics (get subscriptions client))]]
      ;; What it was owed.
      (doseq [[id {:keys [required ended-by]}] verdicts
              :when required]
        (swap! stats update :required inc)
        (if (delivery ds id)
          (swap! stats update :delivered-required inc)
          (let [m   (get publishes id)
                ctx (context events client m before after)
                near? (boolean (some broker-event? ctx))]
            ;; {1 {:near-broker-chaos 12 :elsewhere 0} 2 {...}}: a loss next to
            ;; a killed broker is a known gap (see thoughts.md); one elsewhere
            ;; is news.
            (swap! lost-by update-in [required (if near? :near-broker-chaos :elsewhere)] (fnil inc 0))
            (swap! lost-clients update client (fnil inc 0))
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
                    m (get publishes id)
                    {:keys [required possible]} (.get ^java.util.HashMap verdicts id)
                    n (long n)]]
        (swap! stats update :deliveries + n)
        (when-not required (swap! stats update :optional-delivered inc))
        (cond
          (nil? m)
          (add! {:kind :unexpected :client client :msg id :why "no such publish"})

          (nil? possible)
          (add! {:kind :unexpected :client client :msg id :qos (:qos m) :topic (:topic m)
                 :sent (:sent m) :at at})

          :else
          (do
            (when (> (long max-qos) (long possible))
              (add! {:kind :qos-upgraded :client client :msg id :allowed possible
                     :got max-qos}))
            (case (long possible)
              1 (when (> n 1) (swap! stats update :qos1-repeats + (dec n)))
              (when (> n 1)
                (add! {:kind :duplicate :client client :msg id :qos possible :times n
                       :at at :brokers brokers
                       :near-broker-chaos? (boolean (some broker-event?
                                                          (context events client m before after)))})))))))
    (doseq [{:keys [client at]} sessions-lost]
      (add! {:kind :session-lost :client client :at at}))
    (doseq [p protocol]
      (add! (assoc p :kind :protocol)))
    ;; A run in which nothing was owed proves nothing, and must not pass: the
    ;; brokers were never reached, or nobody stayed subscribed long enough.
    (when (zero? (long (:required @stats)))
      (add! {:kind :nothing-checked
             :why  "no message was owed to any subscriber - were the brokers reachable?"}))
    {:ok?        (empty? @counts)
     :stats      (assoc @stats
                        :published (count publishes)
                        :acked (count (filter (comp :acked val) publishes))
                        :subscribers (count subscriptions))
     :counts     @counts
     :lost-by    @lost-by
     ;; The ten clients that lost the most: the report keeps only the first
     ;; :max-violations of each kind, all of which may be one client's.
     :lost-by-client (into {} (take 10 (sort-by (comp - val) @lost-clients)))
     :violations @violations}))
