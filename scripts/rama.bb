#!/usr/bin/env bb
;; Start, stop and look at the local Rama development cluster the brokers run
;; against: a dev ZooKeeper, a Conductor and a Supervisor, and the worker JVMs
;; the Supervisor launches for the module.
;;
;;   bb scripts/rama.bb start       start all three, wait until each is listening,
;;                                  then until the mqtt-kat module is RUNNING
;;   bb scripts/rama.bb stop        every Rama process on this machine, however it
;;                                  was started: SIGTERM, then SIGKILL after --grace s
;;   bb scripts/rama.bb kill        the same, SIGKILL at once
;;   bb scripts/rama.bb restart     stop, then start
;;   bb scripts/rama.bb status      what is running, and on which ports
;;
;; The Rama distribution is --rama-dir, or $RAMA_HOME, or ~/projects/rama. The
;; daemons are started with setsid, so closing the terminal does not take them
;; down (started from a shell by hand, they die with it). Their console output
;; goes to logs/rama/<daemon>.out here; Rama's own logs stay in <rama-dir>/logs.
;;
;; Stopping keeps the cluster's data (<rama-dir>/local-rama-data, local-zk); the
;; module is still deployed when it comes back.
(require '[babashka.cli :as cli]
         '[babashka.fs :as fs]
         '[babashka.process :as p]
         '[clojure.string :as str])

(def root (str (fs/parent (fs/parent *file*))))
(def out-dir (fs/path root "logs" "rama"))

(def module "mqttkat.rama.module/MqttKatModule")

(def spec
  {:rama-dir {:default (or (System/getenv "RAMA_HOME")
                           (str (fs/path (System/getProperty "user.home") "projects" "rama")))
              :desc "the Rama distribution: rama, rama.yaml, local-rama-data"}
   :grace    {:default 15 :coerce :long :desc "seconds between SIGTERM and SIGKILL on stop"}})

;; ── finding Rama ─────────────────────────────────────────────────────────

(def roles
  "Main class -> what it is. A process is Rama's when one of these is an
   argument on its command line in its own right: the brokers mention the
   Conductor too, but only inside -Dmqttkat.rama.conductor=…, which this does
   not match, and this script's own shell never has them at all."
  {"rpl.rama.distributed.daemon.worker"         "worker"
   "rpl.rama.distributed.daemon.supervisor"     "supervisor"
   "rpl.rama.distributed.daemon.conductor"      "conductor"
   "rpl.rama.distributed.command.dev_zookeeper" "zookeeper"})

(def stop-order
  "Workers first, so they are not left looking for a Supervisor; ZooKeeper
   last, as everything else keeps its state there."
  ["worker" "supervisor" "conductor" "zookeeper"])

(defn- cmdline [pid]
  (try (str/split (slurp (str "/proc/" pid "/cmdline")) #"\u0000")
       (catch Exception _ nil)))

(defn rama-processes
  "[{:pid :role}] for every Rama JVM this user can see."
  []
  (->> (fs/list-dir "/proc")
       (keep (fn [d] (parse-long (str (fs/file-name d)))))
       (keep (fn [pid]
               (let [argv (cmdline pid)]
                 (when (and (seq argv) (str/ends-with? (first argv) "java"))
                   (when-let [role (some roles argv)]
                     {:pid pid :role role})))))
       (sort-by (juxt #(.indexOf ^java.util.List stop-order (:role %)) :pid))))

(defn- alive? [pid] (fs/exists? (str "/proc/" pid)))

;; ── ports ────────────────────────────────────────────────────────────────

(defn- supervisor-port
  "The first port of supervisor.port.range in rama.yaml, which is the one the
   Supervisor itself listens on; Rama's default range starts at 3000."
  [rama-dir]
  (let [yaml (str (fs/path rama-dir "rama.yaml"))]
    (or (when (fs/exists? yaml)
          (some-> (re-find #"supervisor\.port\.range:\s*\[\s*(\d+)" (slurp yaml)) second parse-long))
        3000)))

(defn- daemons
  "What start launches, in order, and the port that says each is up."
  [rama-dir]
  [{:role "zookeeper"  :command "devZookeeper" :port 2000}
   {:role "conductor"  :command "conductor"    :port 1973}
   {:role "supervisor" :command "supervisor"   :port (supervisor-port rama-dir)}])

(defn- listening? [port]
  (try (with-open [_ (java.net.Socket. "localhost" (int port))] true)
       (catch java.io.IOException _ false)))

(defn- wait-for [pred seconds]
  (let [deadline (+ (System/currentTimeMillis) (* 1000 seconds))]
    (loop []
      (cond (pred) true
            (> (System/currentTimeMillis) deadline) false
            :else (do (Thread/sleep 250) (recur))))))

;; ── the module ───────────────────────────────────────────────────────────

(defn- module-state
  "The mqtt-kat module's state as the Conductor reports it — \"RUNNING\" and
   the like — :not-deployed, or nil if the Conductor could not say."
  [rama-dir]
  (let [{:keys [out err]} (p/sh {:dir rama-dir :continue true} "./rama" "moduleStatus" module)
        text (str out err)]
    (cond
      (re-find #"\"moduleState\":\"([A-Z_]+)\"" text) (second (re-find #"\"moduleState\":\"([A-Z_]+)\"" text))
      (re-find #"(?i)does not exist|not found|unknown module" text) :not-deployed
      :else nil)))

;; ── commands ─────────────────────────────────────────────────────────────

(defn status! [{:keys [rama-dir]}]
  (let [procs (rama-processes)]
    (if (empty? procs)
      (println "no Rama processes running")
      (doseq [{:keys [pid role]} procs]
        (println (format "%-10s pid %d" role pid))))
    (doseq [{:keys [role port]} (daemons rama-dir)]
      (println (format "%-10s :%d %s" role port (if (listening? port) "listening" "-"))))
    (println (format "UI         http://localhost:8888/ %s" (if (listening? 8888) "listening" "-")))))

(defn stop! [{:keys [grace]} signal]
  (let [procs (rama-processes)]
    (if (empty? procs)
      (println "no Rama processes running")
      (do
        (doseq [{:keys [pid role]} procs]
          (p/shell {:continue true} "kill" (str "-" signal) (str pid))
          (println (format "%-10s pid %d: %s" role pid signal)))
        (when (= "TERM" signal)
          (when-not (wait-for #(not-any? (comp alive? :pid) procs) grace)
            (doseq [{:keys [pid role]} (filter (comp alive? :pid) procs)]
              (p/shell {:continue true} "kill" "-KILL" (str pid))
              (println (format "%-10s pid %d: still there after %d s, KILL" role pid grace)))))
        (if (wait-for #(empty? (rama-processes)) 10)
          (println "all Rama processes gone")
          (do (println "still running:" (str/join ", " (map :pid (rama-processes))))
              (System/exit 1)))))))

(defn start! [{:keys [rama-dir]}]
  (let [rama (fs/path rama-dir "rama")]
    (when-not (fs/exists? rama)
      (println "no Rama at" rama-dir "- give --rama-dir, or set RAMA_HOME")
      (System/exit 1))
    (when-let [running (seq (rama-processes))]
      (println "Rama is already running:" (str/join ", " (map #(str (:role %) " " (:pid %)) running)))
      (println "use `bb scripts/rama.bb restart`, or stop it first")
      (System/exit 1))
    (when-let [taken (seq (filter (comp listening? :port) (daemons rama-dir)))]
      (println "port" (str/join ", " (map :port taken)) "already in use by something that is not Rama"
               (str "(`ss -ltnp | grep " (:port (first taken)) "`)"))
      (System/exit 1))
    (fs/create-dirs out-dir)
    (doseq [{:keys [role command port]} (daemons rama-dir)]
      (let [out  (fs/file out-dir (str role ".out"))
            proc (p/process {:dir rama-dir :out :write :out-file out :err :write :err-file out}
                            "setsid" "./rama" command)]
        (if (wait-for #(listening? port) 120)
          (println (format "%-10s :%d up" role port))
          (do (println (format "%-10s not listening on :%d after 120 s - see %s" role port out))
              (when-not (.isAlive ^Process (:proc proc))
                (println (str/trim (slurp out))))
              (System/exit 1)))))
    (println "UI         http://localhost:8888/")
    ;; A restarted cluster brings its module back by itself, which takes a
    ;; while; the brokers cannot connect until it has.
    (print (str "module     " module " ")) (flush)
    (let [deadline (+ (System/currentTimeMillis) 180000)]
      (loop [last-state nil]
        (let [state (module-state rama-dir)]
          (cond
            (= "RUNNING" state)
            (println "RUNNING")

            (= :not-deployed state)
            (println (str "not deployed - see the README: `lein jar`, then `./rama deploy --action launch`"))

            (> (System/currentTimeMillis) deadline)
            (println (str "still " (or state "unreachable") " after 180 s - see " rama-dir "/logs"))

            :else
            (do (when (and state (not= state last-state)) (print (str state " ")) (flush))
                (Thread/sleep 3000)
                (recur state))))))))

(defn -main [& args]
  (let [{:keys [args opts]} (cli/parse-args args {:spec spec})]
    (case (first args)
      "start"   (start! opts)
      "stop"    (stop! opts "TERM")
      "kill"    (stop! opts "KILL")
      "restart" (do (stop! opts "TERM") (start! opts))
      "status"  (status! opts)
      (do (println "usage: bb scripts/rama.bb start | stop | kill | restart | status [--rama-dir DIR] [--grace 15]")
          (System/exit 2)))))

(apply -main *command-line-args*)
