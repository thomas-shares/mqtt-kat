(ns mqttkat.sim.core
  "A deterministic discrete-event simulation: a world of nodes (brokers,
   clients, Rama) on one thread and a virtual clock, every random choice
   from one seed. The same seed and config replay the same run, event for
   event.

   The world is a map, threaded through every handler:

     :now      virtual milliseconds
     :events   {[at seq] event}, run in that order: at the same moment, in
               the order they were scheduled
     :rng      a SplittableRandom; only this thread draws from it
     :nodes    {node-id state}, each node's state, the model's to shape
     :epochs   {node-id n}: a node's incarnation. An event stamped with an
               older one is for a node that has since died, and is dropped
     :conns    {conn-id {:client :broker :open?}}: MQTT connections, FIFO
               each way, gone with either end
     :ledger   what the clients saw, for mqttkat.sim.check
     :trace    {msg-id [[at node text]]}: what happened to each message

   An event is a map with :to, the node it is for, and :type. The model
   (`:model` in the world, a map of functions) handles them; see
   mqttkat.sim.runner for how one is put together."
  (:import [java.util SplittableRandom]))

;; ---------------------------------------------------------------- randomness

(defn rng ^SplittableRandom [w] (:rng w))

(defn rand-long
  "A long in [lo, hi]."
  [w lo hi]
  (let [lo (long lo) hi (long hi)]
    (if (>= lo hi) lo (+ lo (.nextLong (rng w) (inc (- hi lo)))))))

(defn chance? [w p] (< (.nextDouble (rng w)) (double p)))

(defn pick [w coll]
  (let [v (vec coll)]
    (when (seq v) (nth v (rand-long w 0 (dec (count v)))))))

;; ---------------------------------------------------------------- the clock

(defn now ^long [w] (:now w))

(defn epoch [w node] (get-in w [:epochs node] 0))

(defn schedule
  "Run `event` at virtual time `at`, not before now."
  [w at event]
  (let [s (:seq w)]
    (-> w
        (assoc :seq (inc s))
        (assoc-in [:events [(max (long at) (now w)) s]] event))))

(defn after [w ms event] (schedule w (+ (now w) (long ms)) event))

(defn timer
  "`event` for `node`, its present incarnation, after `ms`: dropped if the
   node dies first."
  [w node ms event]
  (after w ms (assoc event :to node :epoch (epoch w node))))

(defn node [w id] (get-in w [:nodes id]))
(defn update-node [w id f & args] (apply update-in w [:nodes id] f args))
(defn up? [w id] (boolean (:up? (node w id))))

;; ---------------------------------------------------------------- ids, trace

(defn fresh
  "A new id, `prefix` and a number, unique in the run."
  [w prefix]
  (let [n (get w :next-id 0)]
    [(assoc w :next-id (inc n)) (str prefix n)]))

(defn trace
  "Note what happened to the message `msg-id` at `node`."
  [w msg-id node & text]
  (if msg-id
    (update-in w [:trace msg-id] (fnil conj []) [(now w) node (apply str (interpose " " text))])
    w))

(defn note
  "Add `x` to the ledger's `k`."
  [w k x]
  (update-in w [:ledger k] (fnil conj []) x))

;; ---------------------------------------------------------------- the network

(defn- net-delay [w kind]
  (let [[lo hi] (get-in w [:config :net kind] [1 20])]
    (rand-long w lo hi)))

(defn connect
  "Open a connection from `client` to `broker`. Returns [w conn-id]."
  [w client broker]
  (let [[w id] (fresh w "c")]
    [(assoc-in w [:conns id] {:client client :broker broker :open? true
                              :epoch (epoch w broker)
                              :last {client 0 broker 0}})
     id]))

(defn conn [w id] (get-in w [:conns id]))
(defn open? [w id] (boolean (get-in w [:conns id :open?])))

(defn- other-end [c from] (if (= from (:client c)) (:broker c) (:client c)))

(defn send-on
  "Send `msg` on connection `id` from `from` to whoever is at the other
   end. In order: never before what was sent on it before. Lost if the
   connection closes first."
  [w id from msg]
  (let [c (conn w id)]
    (if-not (:open? c)
      w
      (let [to   (other-end c from)
            at   (max (+ (now w) (long (net-delay w :client)))
                      (long (get-in c [:last to] 0)))]
        (-> w
            (assoc-in [:conns id :last to] at)
            (schedule at {:to to :type :packet :conn id :msg msg}))))))

(defn close
  "Close connection `id`. Each end that is still up learns of it after a
   delay of its own: `client-ms` and `broker-ms`, [lo hi]."
  [w id client-ms broker-ms]
  (let [c (conn w id)]
    (if-not (:open? c)
      w
      (cond-> (assoc-in w [:conns id :open?] false)
        true (after (apply rand-long w client-ms)
                    {:to (:client c) :type :closed :conn id})
        true (after (apply rand-long w broker-ms)
                    {:to (:broker c) :type :closed :conn id :epoch (:epoch c)})))))

(defn conns-of
  "The open connections to `broker`."
  [w broker]
  (for [[id c] (sort-by key (:conns w))
        :when (and (:open? c) (= broker (:broker c)))]
    id))

(defn send-link
  "Send `msg` from broker `from` to broker `to`, in order on that link.
   Arrives as :link; if `to` is down then, `from` gets :undelivered back,
   if it is still the same incarnation."
  [w from to msg]
  (let [k  [from to]
        at (max (+ (now w) (long (net-delay w :link)))
                (long (get-in w [:links k] 0)))]
    (-> w
        (assoc-in [:links k] at)
        (schedule at {:to to :type :link :from from :msg msg
                      :from-epoch (epoch w from) :to-epoch (epoch w to)}))))

;; ---------------------------------------------------------------- the loop

(defn- deliverable?
  "Whether `event` still reaches its node: up, the incarnation it was meant
   for, on a connection still open."
  [w {:keys [to epoch type conn] :as ev}]
  (and (or (nil? epoch) (= epoch (get-in w [:epochs to] 0)))
       (or (not= :packet type) (open? w conn))
       (or (= :world to) (up? w to))))

(defn step
  "Run the next event. Nil when there is none."
  [w]
  (when-let [[k ev] (first (:events w))]
    (let [w (-> w (update :events dissoc k) (assoc :now (first k)))]
      (cond
        ;; A copy for a broker that is down bounces back to its sender.
        (and (= :link (:type ev)) (not (and (up? w (:to ev))
                                            (= (:to-epoch ev) (epoch w (:to ev))))))
        (if (= (:from-epoch ev) (epoch w (:from ev)))
          (after w (net-delay w :link)
                 {:to (:from ev) :type :undelivered :peer (:to ev) :msg (:msg ev)
                  :epoch (:from-epoch ev)})
          w)

        (deliverable? w ev)
        (update ((get-in w [:model :handle]) w ev) :steps (fnil inc 0))

        :else w))))

(defn run
  "Run `w` until no events are left or the clock passes `until`."
  [w until]
  (loop [w w]
    (let [nxt (ffirst (:events w))]
      (if (or (nil? nxt) (> (long (first nxt)) (long until)))
        (assoc w :now (max (now w) (long until)))
        (recur (step w))))))

(defn world
  "A world for `config`, drawing from `seed`, with no nodes yet."
  [seed config model]
  {:now 0 :seq 0 :events (sorted-map)
   :rng (SplittableRandom. (long seed))
   :seed seed :config config :model model
   :nodes {} :epochs {} :conns (sorted-map) :links {}
   :ledger {} :trace {}})

(defn add-node [w id state] (assoc-in w [:nodes id] (assoc state :up? true)))

(defn kill
  "`id` dies: its memory is replaced with `state`, down, and every
   connection to it closes. Events for the old incarnation are dropped."
  [w id state client-ms]
  (let [w (-> w
              (update-in [:epochs id] (fnil inc 0))
              (assoc-in [:nodes id] (assoc state :up? false)))]
    (reduce (fn [w cid] (close w cid client-ms [0 0])) w (conns-of w id))))

(defn revive [w id] (assoc-in w [:nodes id :up?] true))
