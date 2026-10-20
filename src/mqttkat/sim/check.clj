(ns mqttkat.sim.check
  "A finished run judged by doc/guarantees.md, from what the clients saw.

   Owed: every message whose publisher had it accepted (PUBACK or PUBREC),
   to every subscriber whose filters match its topic. Every subscription
   in the simulation is QoS 2 and made before the first publish, and every
   session is kept, so nothing is in the grey zone of
   mqttkat.chaos.check.

   What goes wrong, by rule:

     :eo-lost, :eo-duplicate  R3: an exactly-once topic's message lost,
                              or handed to the application twice
     :lost                    R1/R2: lost, and not in a crash window
     :crash-window            R1 open: lost, and in the memory of a broker
                              when it died. A bug if a crash may cost
                              nothing, allowed if it may cost its window
     :duplicate               R2: QoS 2 twice, with no broker killed in
                              the run
     :duplicate-after-crash   R2: the same in a run that killed one; allowed
     :stuck                   R5: lost, and still on the subscriber's queue
                              on Rama when the run ended
     :identifier-reused       R6: a PUBLISH under an identifier the client
                              held for another message, which it dropped"
  (:require [mqttkat.chaos.check :refer [matches?]]))

(def violations
  "What makes a run fail, whatever is decided about crash windows."
  [:eo-lost :eo-duplicate :lost :duplicate :identifier-reused :stuck])

(defn check
  "`w`, a finished world: its ledger and its subscribers. `queued`, (fn [w
   client]) the client's queue on Rama, {key {:m ...}}."
  [w queued]
  (let [{:keys [published delivered crash-held identifier-reused kills]} (:ledger w)
        eo-filters (get-in w [:config :model :eo-filters])
        eo?        (fn [topic] (boolean (some #(matches? % topic) eo-filters)))
        subs       (for [[id n] (sort-by (comp str key) (:nodes w))
                         :when (= :subscriber (:role n))]
                     [id (:filters n)])
        crashed    (into #{} (map (juxt :msg :client)) crash-held)
        in-crash?  (fn [m s] (or (crashed [m s]) (crashed [m nil])))
        on-queue   (fn [s]
                     (into #{} (keep (comp :id :m val)) (queued w s)))
        on-queue   (memoize on-queue)
        rows       (for [[mid {:keys [topic qos accepted]}] (sort-by key published)
                         :when accepted
                         [s filters] subs
                         :when (some #(matches? % topic) filters)]
                     (let [n (count (get-in delivered [s mid]))]
                       {:msg mid :client s :topic topic :qos qos :eo? (and (= 2 qos) (eo? topic))
                        :n n}))
        found      (fn [pred] (vec (filter pred rows)))
        lost       (fn [r] (zero? (:n r)))
        dup        (fn [r] (and (= 2 (:qos r)) (> (:n r) 1)))
        result
        {:eo-lost               (found #(and (:eo? %) (lost %)))
         :eo-duplicate          (found #(and (:eo? %) (dup %)))
         :lost                  (found #(and (not (:eo? %)) (lost %) (not (in-crash? (:msg %) (:client %)))))
         :crash-window          (found #(and (not (:eo? %)) (lost %) (in-crash? (:msg %) (:client %))))
         :duplicate             (if (seq kills) [] (found #(and (not (:eo? %)) (dup %))))
         :duplicate-after-crash (if (seq kills) (found #(and (not (:eo? %)) (dup %))) [])
         :stuck                 (found #(and (lost %) (contains? (on-queue (:client %)) (:msg %))))
         :identifier-reused     (vec identifier-reused)}]
    (assoc result
           :owed (count rows)
           :published (count published)
           :accepted (count (filter (comp :accepted val) published))
           :ok? (every? #(empty? (get result %)) violations))))

(defn summary
  "The counts of `result`, without the rows."
  [result]
  (into {:ok? (:ok? result) :owed (:owed result)
         :published (:published result) :accepted (:accepted result)}
        (for [k (concat violations [:crash-window :duplicate-after-crash])
              :let [n (count (get result k))]
              :when (pos? n)]
          [k n])))
