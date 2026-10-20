(ns mqttkat.sim.rama
  "Rama as the simulation sees it: depots whose appends are processed in
   order, late by however far behind the topology is, into one PState map
   that reads see as processed so far. It never loses what it acknowledged
   and never dies (doc/guarantees.md takes that as given).

   An append is answered at the level it asks for, as the broker's are:
   :append-ack once the depot has it, :ack once it is processed, nil never.
   The answer is an event for the node that appended, dropped if that node
   died meanwhile. What processing does is the model's: `:apply-op` in the
   model, (fn [w op] w), reading and writing (pstate w).

   How far behind processing is: `:lag` in the Rama node, set by the
   faults (mqttkat.sim.faults), plus a few ms per append. Each depot keeps
   its own order: an append is processed no sooner than the one before it."
  (:require [mqttkat.sim.core :as sim]))

(def node-id :rama)

(defn state [] {:pstate {} :busy {} :lag 0})

(defn pstate [w] (get-in w [:nodes node-id :pstate]))
(defn update-pstate [w f & args] (apply update-in w [:nodes node-id :pstate] f args))

(defn append
  "`from` appends `op` to `depot`. `reply`, an event for `from`, is sent
   back at `ack-level`."
  [w from depot op ack-level reply]
  (let [{:keys [busy lag]} (sim/node w node-id)
        [lo hi]  (get-in w [:config :rama :process-ms] [1 5])
        [dlo dhi] (get-in w [:config :rama :depot-ms] [1 3])
        depot-at (+ (sim/now w) (sim/rand-long w dlo dhi))
        done-at  (max (+ depot-at (long lag) (sim/rand-long w lo hi))
                      (long (get busy depot 0)))
        reply    (when reply (assoc reply :to from :epoch (sim/epoch w from)))]
    (cond-> (-> w
                (assoc-in [:nodes node-id :busy depot] done-at)
                (sim/schedule done-at {:to node-id :type :rama/process :op op
                                       :reply (when (= :ack ack-level) reply)}))
      (and reply (= :append-ack ack-level)) (sim/schedule depot-at reply))))

(defn query
  "Read the PState now, (f pstate), and give `from` the answer as `reply`
   with :result after a read's delay: what it acts on is what was there
   when the read was made."
  [w from f reply]
  (let [[lo hi] (get-in w [:config :rama :read-ms] [1 10])]
    (sim/after w (sim/rand-long w lo hi)
               (assoc reply :to from :epoch (sim/epoch w from) :result (f (pstate w))))))

(defn handle
  "Rama's own events."
  [w {:keys [type op reply]}]
  (case type
    :rama/process (cond-> ((get-in w [:model :apply-op]) w op)
                    reply (sim/schedule (sim/now w) reply))
    :rama/lag     w))

(defn set-lag [w ms] (assoc-in w [:nodes node-id :lag] (long ms)))
