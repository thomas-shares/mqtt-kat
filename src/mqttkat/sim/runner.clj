(ns mqttkat.sim.runner
  "Running the simulation: a world set up from a seed and a config, run to
   the end, judged (mqttkat.sim.check), and explained message by message.

     (run 42)                          one run, the current design
     (sweep (range 100))               many, counted
     (explain (run 42) \"p1:17\")        what happened to one message

   From the command line:

     lein sim [runs] [first-seed]      a sweep, 20 seeds from 0 by default
     lein sim explain <seed>           one run: what broke, message by message

   Every run with the same seed and config is the same run."
  (:require [clojure.pprint :refer [pprint]]
            [mqttkat.sim.check :as check]
            [mqttkat.sim.clients :as clients]
            [mqttkat.sim.core :as sim]
            [mqttkat.sim.current :as current]
            [mqttkat.sim.faults :as faults]
            [mqttkat.sim.rama :as rama]))

(def default-config
  {:brokers      [:b1 :b2 :b3]
   :publishers   {"p1" {:topics ["eo/a" "eo/b" "t/a" "t/b"] :qos [1 2] :rate-ms 20 :max-inflight 10}
                  "p2" {:topics ["eo/a" "t/a"] :qos [2] :rate-ms 30 :max-inflight 10}
                  "p3" {:topics ["eo/b" "t/b"] :qos [1 2] :rate-ms 30 :max-inflight 10}}
   :subscribers  {"s1" {:filters ["eo/#"] :reply-ms [0 5]}
                  "s2" {:filters ["t/#"] :reply-ms [0 5]}
                  "s3" {:filters ["#"] :reply-ms [5 40]}       ; slow, and takes everything
                  "s4" {:filters ["eo/a" "t/a"] :reply-ms [0 10]}}
   :publish-ms   30000
   :drain-ms     120000                                       ; R5's T
   :net          {:client [1 20] :link [1 10]}
   :rama         {:depot-ms [1 3] :process-ms [1 5] :read-ms [1 10]}
   :reconnect-ms [100 1000]
   :drop-detect-ms [10 3000]
   :crash-detect-ms [50 500]
   :faults       {:kill-every [5000 15000] :down-ms [1000 8000]
                  :drop-every [1000 4000]
                  :lag-every  [3000 10000] :lag-ms [0 3000]
                  :until      30000}
   :model        {:eo-filters ["eo/#"]}})

(def calm
  "No faults at all: what any design must get right."
  {:faults {:kill-every nil :drop-every nil :lag-every nil}})

(def leases
  "The current design with identifier leases (mqttkat.sim.current)."
  {:model {:leases? true}})

(def forward-ack
  "Forwarded copies as hand-offs (mqttkat.sim.current)."
  {:model {:forward-ack? true}})

(def race-fixes
  "The races the sweeps found beyond leases and the bridge, fixed
   (mqttkat.sim.current)."
  {:model {:keep-claimed-pid? true :held-read-barrier? true
           :overflow-before-connack? true}})

(def harsh
  "Brokers killed every 2 to 5 s, connections dropped every 0.5 to 2 s,
   Rama up to 5 s behind."
  {:faults {:kill-every [2000 5000] :down-ms [500 3000]
            :drop-every [500 2000] :lag-every [1000 3000] :lag-ms [0 5000]}})

(defn forward-ack-as
  "Forwarded copies as hand-offs of `mode`: :full, :receipt, :eo or
   :eo+receipt (mqttkat.sim.current)."
  [mode]
  {:model {:forward-ack? mode}})

(defn- deep-merge [a b]
  (if (and (map? a) (map? b)) (merge-with deep-merge a b) b))

(defn config [& overrides] (reduce deep-merge default-config overrides))

(defn- dispatch [w {:keys [to] :as ev}]
  (cond
    (= :world to)     (faults/handle w ev)
    (= rama/node-id to) (rama/handle w ev)
    :else (case (:kind (sim/node w to))
            :broker ((get-in w [:model :broker-handle]) w ev)
            :client (clients/handle w ev))))

(defn setup
  "A world ready to run."
  [seed config model]
  (let [{:keys [brokers publishers subscribers publish-ms faults]} config
        config (assoc config :client-ids (vec (sort (concat (keys publishers) (keys subscribers)))))
        w (sim/world seed config (assoc model :handle dispatch))
        w (sim/add-node w rama/node-id (rama/state))
        w (rama/update-pstate w assoc :subs (into (sorted-map)
                                                  (for [[id {:keys [filters]}] subscribers]
                                                    [id filters])))
        w (reduce #(sim/add-node %1 %2 ((:broker-state model) %2)) w brokers)
        w (reduce (fn [w [id p]]
                    (sim/add-node w id (clients/publisher id (assoc p :stop-at publish-ms))))
                  w (sort-by key publishers))
        w (reduce (fn [w [id s]] (sim/add-node w id (clients/subscriber id s)))
                  w (sort-by key subscribers))
        ;; Subscribers first, so that what is published is owed to them.
        w (reduce #(sim/timer %1 %2 (sim/rand-long %1 0 50) {:type :start}) w (sort (keys subscribers)))
        w (reduce #(sim/timer %1 %2 (sim/rand-long %1 1000 1100) {:type :start}) w (sort (keys publishers)))]
    (faults/plan w faults)))

(defn run
  "Run `seed` to the end and judge it. The world, with :result."
  ([seed] (run seed (config)))
  ([seed config] (run seed config current/model))
  ([seed config model]
   (let [w (sim/run (setup seed config model) (+ (long (:publish-ms config)) (long (:drain-ms config))))]
     (assoc w :result (check/check w (:queued model))))))

(defn explain
  "What happened to message `msg-id`, line by line."
  [w msg-id]
  (for [[at node text] (get-in w [:trace msg-id])]
    (format "%8d %-4s %s" at (name node) text)))

(defn first-violation
  "The first row of the first rule `w` broke, if any."
  [w]
  (first (keep #(first (get-in w [:result %])) check/violations)))

(defn sweep
  "Run each of `seeds`; how many of them broke each rule, and the seeds
   that did."
  ([seeds] (sweep seeds (config)))
  ([seeds config] (sweep seeds config current/model))
  ([seeds config model]
   (reduce (fn [acc seed]
             (let [{:keys [result]} (run seed config model)
                   s (check/summary result)]
               (-> acc
                   (update :runs inc)
                   (update :ok (if (:ok? s) inc identity))
                   (update :counts #(merge-with + % (dissoc s :ok? :owed :published :accepted)))
                   (update :owed + (:owed s))
                   (cond-> (not (:ok? s)) (update :failed conj [seed (dissoc s :ok? :published :accepted)])))))
           {:runs 0 :ok 0 :owed 0 :counts {} :failed []}
           seeds)))

(defn- explain-run [seed]
  (let [w   (run seed)
        res (:result w)]
    (pprint (check/summary res))
    (doseq [k check/violations
            row (take 3 (get res k))]
      (println)
      (println k row)
      (doseq [mid (distinct (keep row [:msg :held :got]))]
        (println "  ---" mid)
        (doseq [l (explain w mid)] (println "  " l))))
    (println)
    (println "kills" (mapv (juxt :broker :at) (get-in w [:ledger :kills])))))

(defn -main [& [a b]]
  (let [t0 (System/currentTimeMillis)]
    (if (= "explain" a)
      (explain-run (Long/parseLong b))
      (let [n    (Long/parseLong (or a "20"))
            from (Long/parseLong (or b "0"))]
        (pprint (update (sweep (range from (+ from n))) :failed #(take 20 %)))))
    (println "in" (- (System/currentTimeMillis) t0) "ms")
    (shutdown-agents)))
