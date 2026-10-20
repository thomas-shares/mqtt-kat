(ns mqttkat.sim.faults
  "The failures a run is put through, drawn from its seed up front: brokers
   killed and brought back empty, client connections dropped, Rama slowed
   down and let catch up again (doc/guarantees.md, Failures). Never every
   broker down at once: that is out of scope.

   Config, under :faults, each an interval [lo hi] in ms between two of
   them, nil for none:

     {:kill-every [5000 15000] :down-ms [1000 8000]
      :drop-every [1000 4000]
      :lag-every  [3000 10000] :lag-ms [0 3000]
      :until      30000}"
  (:require [mqttkat.sim.core :as sim]
            [mqttkat.sim.rama :as rama]))

(defn- times [w [lo hi] until]
  (if-not lo
    [w []]
    (loop [w w at 0 acc []]
      (let [at (+ (long at) (long (sim/rand-long w lo hi)))]
        (if (> at (long until)) [w acc] (recur w at (conj acc at)))))))

(defn plan
  "Schedule every fault of `config` in `w`."
  [w {:keys [kill-every drop-every lag-every until]}]
  (let [[w kills] (times w kill-every until)
        [w drops] (times w drop-every until)
        [w lags]  (times w lag-every until)
        w (reduce #(sim/schedule %1 %2 {:to :world :type :fault/kill}) w kills)
        w (reduce #(sim/schedule %1 %2 {:to :world :type :fault/drop}) w drops)
        w (reduce #(sim/schedule %1 %2 {:to :world :type :fault/lag}) w lags)]
    ;; And Rama caught up for good once the faults stop.
    (sim/schedule w until {:to :world :type :fault/calm})))

(defn handle [w {:keys [type broker]}]
  (let [{:keys [down-ms lag-ms]} (get-in w [:config :faults])
        brokers (get-in w [:config :brokers])
        model   (:model w)]
    (case type
      :fault/kill
      (let [up (filter #(sim/up? w %) brokers)]
        (if (< (count up) 2)
          w
          (let [b (sim/pick w up)]
            (-> ((:on-kill model) w b)
                (sim/note :kills {:broker b :at (sim/now w)})
                (assoc-in [:down-since b] (sim/now w))
                (sim/kill b ((:broker-state model) b) (get-in w [:config :crash-detect-ms] [50 500]))
                (sim/after (apply sim/rand-long w down-ms) {:to :world :type :fault/restart :broker b})))))

      :fault/restart
      (-> (sim/revive w broker) (sim/note :restarts {:broker broker :at (sim/now w)}))

      :fault/drop
      (let [c (sim/pick w (get-in w [:config :client-ids]))]
        (-> (sim/note w :drops {:client c :at (sim/now w)})
            (sim/after 0 {:to c :type :drop})))

      :fault/lag
      (let [ms (apply sim/rand-long w lag-ms)]
        (-> (rama/set-lag w ms) (sim/note :lags {:ms ms :at (sim/now w)})))

      :fault/calm
      (rama/set-lag w 0))))
