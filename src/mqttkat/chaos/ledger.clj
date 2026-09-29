(ns mqttkat.chaos.ledger
  "Everything a chaos run's clients saw, written as it happens and read once
   at the end by mqttkat.chaos.check.

   Times are microseconds since the run started, from one monotonic clock: the
   publishers, the subscribers and the chaos are all in this JVM, so \"the
   SUBACK came before the PUBLISH went\" is a comparison of two numbers here,
   not a guess across machines.

   Sized for long runs: ten minutes at 2000 publishes a second to 300
   subscribers is over a hundred million deliveries, so a delivery is a few
   bits, not a map entry. Keeping one [msg at qos broker] per delivery ran the
   runner out of heap three minutes into chaos/long.edn."
  (:require [mqttkat.chaos.check :as check])
  (:import [java.util BitSet HashMap]
           [java.util.concurrent ConcurrentHashMap]
           [java.util.concurrent.atomic AtomicLong]))

(set! *warn-on-reflection* true)

(defn ledger []
  {:t0            (System/nanoTime)
   ;; [pub seq] -> {:topic :qos :sent :acked :pub-broker}
   :publishes     (ConcurrentHashMap.)
   ;; client-id -> [subscription ...], the last one open while :to is nil
   :subscriptions (atom {})
   ;; client-id -> HashMap pub -> BitSets by seq, see delivered!
   :deliveries    (ConcurrentHashMap.)
   ;; [client-id msg-id] -> [{:at :qos :broker}], for the second and later
   ;; deliveries of a message only, and only the first `repeats-kept` of
   ;; those: the detail a :duplicate is reported with.
   :repeats       (ConcurrentHashMap.)
   :delivered     (AtomicLong.)
   :acked         (AtomicLong.)
   :clients       (atom {})
   :sessions-lost (atom [])
   ;; Things the broker did that MQTT says it must not, whatever the QoS.
   :protocol      (atom [])
   :events        (atom [])})

(defn now
  "Microseconds since the run started."
  ^long [ledger]
  (quot (- (System/nanoTime) (long (:t0 ledger))) 1000))

(defn event! [ledger m]
  (swap! (:events ledger) conj (assoc m :at (now ledger))))

(defn client! [ledger id m]
  (swap! (:clients ledger) assoc id m))

;; ── publishing ─────────────────────────────────────────────────────────

(defn published! [ledger id m]
  (.put ^ConcurrentHashMap (:publishes ledger) id (assoc m :sent (now ledger))))

(defn acked! [ledger id]
  (let [t      (now ledger)
        first? (volatile! false)]
    (.computeIfPresent ^ConcurrentHashMap (:publishes ledger) id
                       (reify java.util.function.BiFunction
                         (apply [_ _ m]
                           (if (:acked m)
                             m
                             (do (vreset! first? true) (assoc m :acked t))))))
    (when @first?
      (.incrementAndGet ^AtomicLong (:acked ledger)))))

(defn acked-count ^long [ledger]
  (.get ^AtomicLong (:acked ledger)))

;; ── subscribing ────────────────────────────────────────────────────────

(defn subscribed!
  "A SUBACK granting `qos` for a SUBSCRIBE first sent at `sub-sent`."
  [ledger client filter qos sub-sent]
  (swap! (:subscriptions ledger) update client (fnil conj [])
         {:filter filter :qos qos :sub-sent sub-sent :from (now ledger)}))

(defn- close-open [subs f]
  (if-let [open (and (seq subs) (nil? (:to (peek subs))) (peek subs))]
    (conj (pop subs) (f open))
    subs))

(defn unsubscribe-sent!
  "The UNSUBSCRIBE went. The subscription is not over until the UNSUBACK, but
   from here on the broker is entitled to stop sending."
  [ledger client at]
  (swap! (:subscriptions ledger) update client close-open
         #(if (:unsub-sent %) % (assoc % :unsub-sent at))))

(defn ended!
  "The open subscription of `client` is over: :unsubscribe (at its UNSUBACK),
   :drop (a clean session's connection went) or :session-lost, at `at`."
  [ledger client ended-by at]
  (swap! (:subscriptions ledger) update client close-open
         #(assoc % :to at :ended-by ended-by)))

(defn protocol-error! [ledger client what m]
  (swap! (:protocol ledger) conj (assoc m :client client :what what :at (now ledger))))

(defn session-lost! [ledger client at]
  (swap! (:sessions-lost ledger) conj {:client client :at at}))

;; ── receiving ──────────────────────────────────────────────────────────
;;
;; Per client, per publisher, four BitSets indexed by the message's sequence
;; number (a publisher numbers its messages 1, 2, 3...): handed on once,
;; more than once, at QoS 1 or above, at QoS 2. That is all the check needs
;; of a delivery — whether, how often, and at most which QoS — in four bits.

(def ^:private repeats-kept 10000)

(defn- bits-of ^objects [^HashMap by-pub pub]
  (or (.get by-pub pub)
      (let [a (object-array [(BitSet.) (BitSet.) (BitSet.) (BitSet.)])]
        (.put by-pub pub a)
        a)))

(defn delivered! [ledger client [pub sq :as id] qos broker]
  (let [^HashMap by-pub (.computeIfAbsent ^ConcurrentHashMap (:deliveries ledger) client
                                          (reify java.util.function.Function
                                            (apply [_ _] (HashMap.))))
        i       (int sq)
        qos     (long qos)
        repeat? (locking by-pub
                  (let [a (bits-of by-pub pub)
                        ^BitSet once (aget a 0)
                        again? (.get once i)]
                    (if again?
                      (.set ^BitSet (aget a 1) i)
                      (.set once i))
                    (when (>= qos 1) (.set ^BitSet (aget a 2) i))
                    (when (= qos 2) (.set ^BitSet (aget a 3) i))
                    again?))]
    (.incrementAndGet ^AtomicLong (:delivered ledger))
    (when repeat?
      (let [^ConcurrentHashMap repeats (:repeats ledger)
            k [client id]]
        (when (or (.containsKey repeats k) (< (.size repeats) (int repeats-kept)))
          (.merge repeats k [{:at (now ledger) :qos qos :broker broker}]
                  (reify java.util.function.BiFunction
                    (apply [_ a b] (into a b)))))))))

(defn delivery-count ^long [ledger]
  (.get ^AtomicLong (:delivered ledger)))

(deftype ClientDeliveries [^HashMap by-pub ^ConcurrentHashMap repeats client]
  check/Delivered
  (delivered-ids [_]
    (locking by-pub
      (vec (for [[pub ^objects a] by-pub
                 :let [^BitSet once (aget a 0)]
                 sq (take-while #(>= (long %) 0)
                                (iterate #(.nextSetBit once (int (inc (long %))))
                                         (.nextSetBit once 0)))]
             [pub (long sq)]))))
  (delivery [_ [pub sq :as id]]
    (let [i (int sq)]
      (locking by-pub
        (when-let [^objects a (.get by-pub pub)]
          (when (.get ^BitSet (aget a 0) i)
            (let [extra (.get repeats [client id])]
              {:n       (cond extra                          (inc (count extra))
                              (.get ^BitSet (aget a 1) i)     2
                              :else                           1)
               :max-qos (cond (.get ^BitSet (aget a 3) i) 2
                              (.get ^BitSet (aget a 2) i) 1
                              :else                       0)
               ;; Of the repeats only: the first delivery's moment is not kept.
               :at      (mapv :at extra)
               :brokers (mapv :broker extra)})))))))

;; ── reading it back ────────────────────────────────────────────────────

(defn snapshot
  "The ledger as the plain data mqttkat.chaos.check/check takes."
  [ledger]
  {;; The map itself, not a copy: a long run has over a million publishes.
   :publishes     (:publishes ledger)
   :subscriptions @(:subscriptions ledger)
   :deliveries    (into {}
                        (for [[client by-pub] (:deliveries ledger)]
                          [client (->ClientDeliveries by-pub (:repeats ledger) client)]))
   :clients       @(:clients ledger)
   :sessions-lost @(:sessions-lost ledger)
   :protocol      @(:protocol ledger)
   :events        (vec (sort-by :at @(:events ledger)))})
