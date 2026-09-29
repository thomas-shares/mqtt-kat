#!/usr/bin/env bb
;; A chaos run from start to finish: the Rama cluster, the brokers, the load
;; and the chaos, and the check that every message arrived as its QoS
;; promised. Everything comes from EDN files under chaos/, merged left to
;; right over the runner's defaults (mqttkat.chaos.runner/defaults).
;;
;;   bb scripts/chaos.bb chaos/three-brokers.edn
;;   bb scripts/chaos.bb chaos/three-brokers.edn chaos/long.edn   the second adjusts the first
;;   bb scripts/chaos.bb --keep chaos/single-broker.edn           leave the brokers running
;;
;; In order:
;;
;;   build    `lein uberjar` if target/mqtt-kat-0.0.1-standalone.jar is missing
;;            (or always, with :setup {:build :always})
;;   rama     `bb scripts/rama.bb start`, when :setup :rama :start? and the
;;            Conductor is not already up; then, for brokers on an external
;;            cluster, `bb scripts/rama.bb wait` until the Conductor listens
;;            and the module is RUNNING, however the cluster was started
;;   brokers  `bb scripts/brokers.bb start N` with :setup :brokers
;;   run      mqttkat.chaos.runner, which kills and restarts brokers through
;;            brokers.bb as the :chaos section says
;;   teardown `brokers.bb stop`, and `rama.bb stop` if this started Rama and
;;            :setup :rama :stop? — unless --keep
;;
;; Exits with the runner's status: 0 when every QoS promise held, 1 when one
;; did not (the report under logs/chaos/ says which), 2 when setup failed.
(require '[babashka.fs :as fs]
         '[babashka.process :as p]
         '[clojure.edn :as edn]
         '[clojure.string :as str])

(def root (str (fs/parent (fs/parent *file*))))
(def jar (str (fs/path root "target" "mqtt-kat-0.0.1-standalone.jar")))

(defn deep-merge [& ms]
  (apply merge-with (fn [a b] (if (and (map? a) (map? b)) (deep-merge a b) b)) ms))

;; Only what setup needs; the runner has the rest of the defaults.
(def setup-defaults
  {:setup {:build   :if-missing
           :brokers {:count 1 :host "127.0.0.1" :port 1885 :http 8085
                     :heap "1g" :rama "external"}
           ;; Started when not up: the brokers default to an external
           ;; cluster, and a scenario that does not say otherwise (an
           ;; overlay like chaos/long.edn, run on its own) had brokers
           ;; dying on a Conductor that was never there.
           :rama    {:start? true :stop? false}}})

(defn listening? [port]
  (try (with-open [_ (java.net.Socket. "localhost" (int port))] true)
       (catch java.io.IOException _ false)))

(defn sh!
  "Run a command from the repository root with its output on ours; its exit
   status."
  [& argv]
  (println "$" (str/join " " argv))
  (:exit @(apply p/process {:dir root :inherit true} argv)))

(defn fail! [& msg]
  (apply println "chaos:" msg)
  (System/exit 2))

(defn rama-flags [rama]
  (when (:dir rama) ["--rama-dir" (:dir rama)]))

(defn broker-flags [{:keys [port http heap rama conductor advertise]}]
  (cond-> ["--port" (str port) "--http" (str http) "--heap" (str heap) "--rama" (name rama)]
    conductor (into ["--conductor" conductor])
    advertise (into ["--advertise" advertise])))

(defn -main [& args]
  (let [keep?  (some #{"--keep"} args)
        paths  (remove #{"--keep"} args)
        _      (when (empty? paths)
                 (println "usage: bb scripts/chaos.bb [--keep] chaos/<scenario>.edn [more.edn ...]")
                 (System/exit 2))
        cfg    (apply deep-merge setup-defaults (map (comp edn/read-string slurp) paths))
        {:keys [build brokers rama]} (:setup cfg)
        started-rama? (atom false)]
    ;; build
    (when (or (= :always build) (not (fs/exists? jar)))
      (when-not (zero? (sh! "lein" "uberjar"))
        (fail! "lein uberjar failed")))
    ;; rama
    (when (:start? rama)
      (if (listening? 1973)
        (println "Rama's Conductor is already up on :1973, using it")
        (do (when-not (zero? (apply sh! "bb" "scripts/rama.bb" "start" (rama-flags rama)))
              (fail! "Rama did not start"))
            (reset! started-rama? true))))
    ;; Brokers on a cluster of their own need it up before they start, not
    ;; merely started: a broker that finds no Conductor exits. Not for one
    ;; named elsewhere with :conductor, which this machine cannot look at.
    (when (and (= "external" (name (:rama brokers))) (not (:conductor brokers)))
      (when-not (zero? (apply sh! "bb" "scripts/rama.bb" "wait" (rama-flags rama)))
        (fail! "the Rama cluster is not ready for the brokers; start it with"
               "`bb scripts/rama.bb start` (RAMA_HOME, or :setup :rama :dir), or"
               "run a scenario that does not need it: chaos/single-broker.edn")))
    ;; System/exit skips finally blocks, so the status is carried out of the
    ;; try and the process exits after the teardown.
    (let [status
          (try
            ;; brokers
            (apply sh! "bb" "scripts/brokers.bb" "start" (str (:count brokers)) (broker-flags brokers))
            (let [down (remove #(listening? (+ (:port brokers) (dec %))) (range 1 (inc (:count brokers))))]
              (if (seq down)
                (do (println "chaos: brokers" (str/join ", " down) "did not come up; see logs/brokers/"
                             (if (= "external" (name (:rama brokers)))
                               "- is the module deployed? (README, \"External cluster\")"
                               ""))
                    2)
                ;; run
                (let [status (apply sh! "java" "--add-opens" "java.base/java.lang=ALL-UNNAMED"
                                    "--enable-native-access=ALL-UNNAMED"
                                    "-cp" jar "clojure.main" "-m" "mqttkat.chaos.runner" paths)]
                  (println (case status
                             0 "chaos: every QoS promise held"
                             1 "chaos: QoS promises broken, see the report"
                             "chaos: the run itself failed"))
                  status)))
            (finally
              (when-not keep?
                (sh! "bb" "scripts/brokers.bb" "stop")
                (when (and @started-rama? (:stop? rama))
                  (sh! "bb" "scripts/rama.bb" "stop")))))]
      (System/exit status))))

(apply -main *command-line-args*)
