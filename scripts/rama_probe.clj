;; What Rama holds now for the clients a chaos report says lost messages:
;; where it has each one connected, where it has the subscription that
;; matched, and how much is on its queue. Run against the cluster the run
;; used, before it is reset:
;;
;;   java -cp target/mqtt-kat-0.0.1-standalone.jar clojure.main \
;;     scripts/rama_probe.clj logs/chaos/<run>.edn [conductor-host]
(require '[clojure.edn :as edn]
         '[com.rpl.rama :as r]
         '[com.rpl.rama.path :refer [keypath view]]
         '[mqttkat.rama.module :as m])

(let [[path host] *command-line-args*
      report  (edn/read-string {:default (fn [_ v] v)} (slurp path))
      lost    (filter #(= :lost (:kind %)) (get-in report [:result :violations]))
      by      (vals (group-by :client lost))
      c       (r/open-cluster-manager {"conductor.host" (or host "localhost")})
      mod     (r/get-module-name m/MqttKatModule)
      ps      #(r/foreign-pstate c mod %)
      sess    (ps "$$sessions")
      subs-ps (ps "$$subscriptions")
      queued  (ps "$$queued")]
  (println (count lost) "lost samples over" (count by) "clients")
  (doseq [vs by
          :let [{:keys [client sub-broker session]} (first vs)
                f (:filter session)
                s (r/foreign-select-one (keypath client) sess)]]
    (prn {:client     (subs client (inc (.lastIndexOf ^String client "-")))
          :kept?      (:persistent? session)
          :filter     (subs f (inc (.lastIndexOf ^String f "/")))
          :samples    (count vs)
          :was-on     sub-broker
          :pub-brokers (frequencies (map :pub-broker vs))
          :sent-s     [(quot (long (reduce min (map :sent vs))) 1000000)
                       (quot (long (reduce max (map :sent vs))) 1000000)]
          :rama       (select-keys s [:connected? :broker-id :connections :lost?])
          :rama-subs  (into {} (map (fn [[k e]] [(subs k (inc (.lastIndexOf ^String k "/")))
                                                  (select-keys e [:broker-id :connected?])]))
                            (:subscriptions s))
          :shard-entry (some-> (r/foreign-select-one (keypath (m/shard-of f) f client) subs-ps)
                               (select-keys [:broker-id :connected?]))
          :queued     (r/foreign-select-one [(keypath client) (view count)] queued)}))
  (System/exit 0))
