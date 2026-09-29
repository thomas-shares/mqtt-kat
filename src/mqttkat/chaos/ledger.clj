(ns mqttkat.chaos.ledger
  "Everything a chaos run's clients saw, written as it happens and read once
   at the end by mqttkat.chaos.check.

   Times are microseconds since the run started, from one monotonic clock: the
   publishers, the subscribers and the chaos are all in this JVM, so \"the
   SUBACK came before the PUBLISH went\" is a comparison of two numbers here,
   not a guess across machines."
  (:import [java.util.concurrent ConcurrentHashMap ConcurrentLinkedQueue]))

(set! *warn-on-reflection* true)

(defn ledger []
  {:t0            (System/nanoTime)
   ;; [pub seq] -> {:topic :qos :sent :acked :pub-broker}
   :publishes     (ConcurrentHashMap.)
   ;; client-id -> [subscription ...], the last one open while :to is nil
   :subscriptions (atom {})
   ;; client-id -> queue of [msg-id at qos broker]
   :deliveries    (ConcurrentHashMap.)
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
  (let [t (now ledger)]
    (.computeIfPresent ^ConcurrentHashMap (:publishes ledger) id
                       (reify java.util.function.BiFunction
                         (apply [_ _ m] (assoc m :acked t))))))

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

(defn delivered! [ledger client id qos broker]
  (let [q (.computeIfAbsent ^ConcurrentHashMap (:deliveries ledger) client
                            (reify java.util.function.Function
                              (apply [_ _] (ConcurrentLinkedQueue.))))]
    (.add ^ConcurrentLinkedQueue q [id (now ledger) qos broker])))

(defn delivery-count ^long [ledger]
  (reduce + 0 (map #(.size ^ConcurrentLinkedQueue %)
                   (.values ^ConcurrentHashMap (:deliveries ledger)))))

;; ── reading it back ────────────────────────────────────────────────────

(defn snapshot
  "The ledger as the plain data mqttkat.chaos.check/check takes."
  [ledger]
  {:publishes     (into {} (:publishes ledger))
   :subscriptions @(:subscriptions ledger)
   :deliveries    (into {}
                        (for [[client q] (:deliveries ledger)]
                          [client (reduce (fn [acc [id at qos broker]]
                                            (update acc id (fnil conj [])
                                                    {:at at :qos qos :broker broker}))
                                          {}
                                          q)]))
   :clients       @(:clients ledger)
   :sessions-lost @(:sessions-lost ledger)
   :protocol      @(:protocol ledger)
   :events        (vec (sort-by :at @(:events ledger)))})
