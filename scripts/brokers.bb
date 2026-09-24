#!/usr/bin/env bb
;; Start, stop and look at N brokers in front of the Rama cluster on this
;; machine — one JVM each, each with its own MQTT and HTTP port, all
;; connected to the same Conductor. For trying the cluster out without typing
;; six java command lines.
;;
;;   bb scripts/brokers.bb start 3            three brokers: 1885/8085, 1886/8086, 1887/8087
;;   bb scripts/brokers.bb start 3 --port 2000 --http 9000 --conductor rama-host
;;   bb scripts/brokers.bb status
;;   bb scripts/brokers.bb stop               all of them, politely (SIGTERM: the shutdown hook
;;                                            withdraws the broker from the cluster)
;;   bb scripts/brokers.bb stop 2             just broker-2
;;   bb scripts/brokers.bb kill 2             broker-2 with SIGKILL, to see the others cope
;;   bb scripts/brokers.bb logs 2             tail broker-2's log
;;
;; Each broker is broker-<n>, advertises 127.0.0.1 with its own port, logs to
;; logs/brokers/broker-<n>.log and leaves its pid in logs/brokers/broker-<n>.pid.
;; The jar is target/mqtt-kat-0.0.1-standalone.jar: run `lein uberjar` first.
(require '[babashka.cli :as cli]
         '[babashka.fs :as fs]
         '[babashka.process :as p]
         '[clojure.string :as str])

(def root (str (fs/parent (fs/parent *file*))))
(def run-dir (fs/path root "logs" "brokers"))
(def jar (str (fs/path root "target" "mqtt-kat-0.0.1-standalone.jar")))

(def spec
  {:port      {:default 1885 :coerce :long :desc "MQTT port of broker-1; each next one is +1"}
   :http      {:default 8085 :coerce :long :desc "HTTP (console) port of broker-1; each next one is +1"}
   :conductor {:default "localhost" :desc "the Rama Conductor"}
   :advertise {:default "127.0.0.1" :desc "the address the other brokers reach these at"}
   :heap      {:default "4g" :desc "-Xmx for each broker"}})

(defn pid-file [n] (fs/path run-dir (str "broker-" n ".pid")))
(defn log-file [n] (fs/path run-dir (str "broker-" n ".log")))

(defn pid-of
  "The pid in broker-n's pid file, if the process is still there."
  [n]
  (when (fs/exists? (pid-file n))
    (let [pid (parse-long (str/trim (slurp (str (pid-file n)))))]
      (when (and pid (fs/exists? (str "/proc/" pid)))
        pid))))

(defn running
  "[n pid] for every broker whose pid file names a live process."
  []
  (->> (when (fs/exists? run-dir) (fs/glob run-dir "broker-*.pid"))
       (keep (fn [f]
               (let [n (parse-long (second (re-find #"broker-(\d+)\.pid" (str (fs/file-name f)))))]
                 (when-let [pid (pid-of n)] [n pid]))))
       (sort-by first)))

(defn port-free?
  "Whether nothing listens on `port` here. Checked before a broker is
   started on it: a broker that cannot bind dies on the way up, and the
   JVM it leaves behind, with a Rama client and no listener, is a zombie
   that looks alive to `status`."
  [port]
  (try (with-open [_ (java.net.ServerSocket. (int port))] true)
       (catch java.io.IOException _ false)))

(defn start-one! [n {:keys [port http conductor advertise heap]}]
  (let [mqtt  (+ port (dec n))
        web   (+ http (dec n))
        log   (str (log-file n))
        taken (remove port-free? [mqtt web])]
    (cond
      (pid-of n)
      (println (format "broker-%d already running (pid %d)" n (pid-of n)))

      (seq taken)
      (println (format "broker-%d not started: port %s is in use — something else, or a broker this script does not know about (`ss -ltnp | grep %d`)"
                       n (str/join " and " taken) (first taken)))

      :else
      (let [_    (fs/delete-if-exists log)
            proc (p/process {:out :write :out-file (fs/file log)
                             :err :write :err-file (fs/file log)}
                            "java"
                            "--add-opens" "java.base/java.lang=ALL-UNNAMED"
                            "--enable-native-access=ALL-UNNAMED"
                            (str "-Xmx" heap)
                            "-Dmqttkat.rama=external"
                            (str "-Dmqttkat.rama.conductor=" conductor)
                            (str "-Dmqttkat.brokerId=broker-" n)
                            (str "-Dmqttkat.advertise=" advertise)
                            "-Dmqttkat.sysInterval=60"
                            "-jar" jar (str mqtt) (str web))
            pid  (.pid (:proc proc))]
        (spit (str (pid-file n)) (str pid))
        ;; Up when the console is listening: that is the last thing -main starts.
        (let [deadline (+ (System/currentTimeMillis) 60000)]
          (loop []
            (let [text (if (fs/exists? log) (slurp log) "")]
              (cond
                (not (.isAlive (:proc proc)))
                (println (format "broker-%d died on start - see %s" n log))

                (str/includes? text "http status page")
                (println (format "broker-%d  mqtt :%d  console http://%s:%d/  pid %d"
                                 n mqtt advertise web pid))

                ;; A broker that cannot bind does not exit: its main thread
                ;; dies and the rest lives on, listening on nothing.
                (str/includes? text "Address already in use")
                (do (p/shell {:continue true} "kill" "-9" (str pid))
                    (fs/delete-if-exists (pid-file n))
                    (println (format "broker-%d could not bind - see %s" n log)))

                (> (System/currentTimeMillis) deadline)
                (println (format "broker-%d not up after 60 s - see %s" n log))

                :else (do (Thread/sleep 500) (recur))))))))))

(defn start! [n opts]
  (when-not (fs/exists? jar)
    (println "no" jar "- run `lein uberjar` first")
    (System/exit 1))
  (fs/create-dirs run-dir)
  (doseq [i (range 1 (inc n))]
    (start-one! i opts)))

(defn signal! [n signal]
  (if-let [pid (pid-of n)]
    (do (p/shell "kill" (str "-" signal) (str pid))
        (println (format "broker-%d pid %d: %s" n pid signal)))
    (println (format "broker-%d is not running" n)))
  (fs/delete-if-exists (pid-file n)))

(defn stop! [ns signal]
  (let [ns (or (seq ns) (map first (running)))]
    (if (empty? ns)
      (println "no brokers running")
      (doseq [n ns] (signal! n signal)))))

(defn status! []
  (let [up (running)]
    (if (empty? up)
      (println "no brokers running")
      (doseq [[n pid] up]
        (let [log (slurp (str (log-file n)))
              port (second (re-find #"Server starting on port (\d+)" log))
              web  (second (re-find #"http status page on port (\d+)" log))]
          (println (format "broker-%d  pid %d  mqtt :%s  console :%s" n pid (or port "?") (or web "?"))))))))

(defn logs! [n]
  (if (fs/exists? (log-file n))
    (p/shell "tail" "-n" "40" "-f" (str (log-file n)))
    (println "no log for broker-" n)))

(defn -main [& args]
  (let [{:keys [args opts]} (cli/parse-args args {:spec spec})
        [command & more] args
        n-of (fn [] (parse-long (or (first more) "1")))]
    (case command
      "start"  (start! (n-of) opts)
      "stop"   (stop! (map parse-long more) "TERM")
      "kill"   (stop! (map parse-long more) "KILL")
      "status" (status!)
      "logs"   (logs! (n-of))
      (do (println "usage: bb scripts/brokers.bb start N [--port 1885] [--http 8085] [--conductor localhost] [--advertise 127.0.0.1] [--heap 1g]")
          (println "       bb scripts/brokers.bb stop [N ...] | kill [N ...] | status | logs N")
          (System/exit 2)))))

(apply -main *command-line-args*)
