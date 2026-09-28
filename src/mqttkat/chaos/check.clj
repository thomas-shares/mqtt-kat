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
     :deliveries    {client-id {[pub seq] [{:at :qos :broker}]}}
                    What the client handed on, once per PUBLISH for QoS 0 and
                    1, once per packet identifier for QoS 2 — a QoS 2 PUBLISH
                    resent before its PUBREL is the protocol retrying, not a
                    second delivery.
     :sessions-lost [{:client :at}]
     :protocol      [{:client :what :at ...}]
     :events        [{:at :type ...}]  the chaos, for putting losses in context

   The rules, per subscription and matching message, at the effective QoS —
   the lower of the publish and the subscription:

     required  QoS 1 or 2, acknowledged to the publisher, published at least
               :subscribe-settle after the SUBACK, and acknowledged before the
               UNSUBSCRIBE went. A clean session's subscription also needs its
               connection to have outlived the acknowledgement by :clean-grace:
               what was still on its way when a clean session dropped is
               legitimately gone.
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
           :unsubscribe (< (long acked) (long (or unsub-sent forever)))
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

(defn check
  "The verdict on a run. `opts`: :subscribe-settle and :clean-grace in
   microseconds, :context-window [before after] likewise."
  [{:keys [publishes subscriptions deliveries sessions-lost events clients protocol]}
   {:keys [subscribe-settle clean-grace context-window]
    :or   {subscribe-settle 0 clean-grace 0 context-window [2000000 5000000]}
    :as   opts}]
  (let [opts         (assoc opts :subscribe-settle subscribe-settle :clean-grace clean-grace)
        [before after] context-window
        by-topic     (group-by (comp :topic val) publishes)
        topics       (keys by-topic)
        violations   (atom [])
        stats        (atom {:required 0 :delivered-required 0 :deliveries 0
                            :qos1-repeats 0 :optional-delivered 0})
        add!         (fn [v] (swap! violations conj v))
        ;; client -> msg -> {:required q-or-nil :possible max-q}
        verdicts
        (into {}
              (for [[client subs] subscriptions]
                [client
                 (reduce
                  (fn [acc sub]
                    (reduce
                     (fn [acc [id m]]
                       (let [q    (effective-qos m sub)
                             req? (required? opts m sub)
                             can? (possible? m sub)]
                         (cond-> acc
                           req? (update-in [id :required] (fnil max 0) q)
                           can? (update-in [id :possible] (fnil max 0) q))))
                     acc
                     (mapcat #(get by-topic %) (filter #(matches? (:filter sub) %) topics))))
                  {}
                  subs)]))]
    (doseq [[client ms] verdicts
            [id {:keys [required]}] ms
            :when required
            :let [n (count (get-in deliveries [client id]))]]
      (swap! stats update :required inc)
      (if (zero? n)
        (let [m   (get publishes id)
              ctx (context events client m before after)]
          (add! {:kind :lost :client client :msg id :qos required
                 :session (select-keys (get clients client) [:persistent? :mqtt5? :filter :sub-qos])
                 :topic (:topic m) :sent (:sent m) :acked (:acked m)
                 :pub-broker (:pub-broker m)
                 :near-broker-chaos? (boolean (some broker-event? ctx))
                 :context ctx}))
        (swap! stats update :delivered-required inc)))
    (doseq [[client ms] deliveries
            [id ds] ms
            :let [m (get publishes id)
                  {:keys [required possible]} (get-in verdicts [client id])
                  n (count ds)]]
      (swap! stats update :deliveries + n)
      (when-not required (swap! stats update :optional-delivered inc))
      (cond
        (nil? m)
        (add! {:kind :unexpected :client client :msg id :why "no such publish"})

        (nil? possible)
        (add! {:kind :unexpected :client client :msg id :qos (:qos m) :topic (:topic m)
               :sent (:sent m) :at (mapv :at ds)})

        :else
        (do
          (when-let [over (seq (filter #(> (long (:qos %)) (long possible)) ds))]
            (add! {:kind :qos-upgraded :client client :msg id :allowed possible
                   :got (mapv :qos over)}))
          (case (long possible)
            1 (when (> n 1) (swap! stats update :qos1-repeats + (dec n)))
            (when (> n 1)
              (add! {:kind :duplicate :client client :msg id :qos possible :times n
                     :at (mapv :at ds) :brokers (mapv :broker ds)
                     :near-broker-chaos? (boolean (some broker-event?
                                                        (context events client m before after)))}))))))
    (doseq [{:keys [client at]} sessions-lost]
      (add! {:kind :session-lost :client client :at at}))
    (doseq [p protocol]
      (add! (assoc p :kind :protocol)))
    ;; A run in which nothing was owed proves nothing, and must not pass: the
    ;; brokers were never reached, or nobody stayed subscribed long enough.
    (when (zero? (long (:required @stats)))
      (add! {:kind :nothing-checked
             :why  "no message was owed to any subscriber - were the brokers reachable?"}))
    (let [vs @violations]
      {:ok?        (empty? vs)
       :stats      (assoc @stats
                          :published (count publishes)
                          :acked (count (filter (comp :acked val) publishes))
                          :subscribers (count subscriptions))
       :counts     (frequencies (map :kind vs))
       ;; {1 {:near-broker-chaos 12 :elsewhere 0} 2 {...}}: a loss next to a
       ;; killed broker is a known gap (see thoughts.md); one elsewhere is news.
       :lost-by    (->> vs
                        (filter (comp #{:lost} :kind))
                        (reduce (fn [acc {:keys [qos near-broker-chaos?]}]
                                  (update-in acc [qos (if near-broker-chaos? :near-broker-chaos :elsewhere)]
                                             (fnil inc 0)))
                                (sorted-map)))
       :violations vs})))
