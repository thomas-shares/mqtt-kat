(ns mqttkat.profiling
  "Optional CPU profiling, off unless asked for.

   `-Dmqttkat.profile=cpu` starts async-profiler at boot and writes a flamegraph
   when the broker exits. The point is to profile a broker doing real work: start
   it, run a load test against it, stop it, open the flamegraph.

   clj-async-profiler lives in the :dev profile, so the uberjar does not carry
   it. Everything here is resolved at run time rather than required, which is
   what lets that stay true — a hard :require would put a profiler in the
   shipped artifact and break the uberjar build the moment the dependency moved.
   Without it on the classpath this says so once and the broker carries on.

   Needs -Djdk.attach.allowAttachSelf, which project.clj already sets for lein
   tasks. Running the uberjar by hand needs it on the command line, along with
   the dependency."
  (:require [clojure.tools.logging :as log]))

(def event
  "What to profile, from -Dmqttkat.profile. nil when not asked for.

   `cpu` is the default and what `true` means. `alloc` profiles allocation and
   `wall` wall-clock time, which is the one that shows blocking rather than
   burning — worth knowing about, since a broker that is waiting looks idle to a
   CPU profile."
  (when-let [v (System/getProperty "mqttkat.profile")]
    (case v
      ("true" "" "cpu") :cpu
      "alloc"           :alloc
      "wall"            :wall
      "itimer"          :itimer
      (keyword v))))

(def ui-port
  "-Dmqttkat.profileUi=PORT serves the flamegraph browser on that port."
  (when-let [p (System/getProperty "mqttkat.profileUi")]
    (Long/parseLong p)))

(defn- resolve-fn
  "The profiler's `name`, or nil when the dependency is not on the classpath."
  [name]
  (try
    (requiring-resolve (symbol "clj-async-profiler.core" name))
    (catch Throwable _ nil)))

(defn stop!
  "Stop profiling and write the flamegraph. Returns its file, or nil.

   Safe to call when nothing is running: async-profiler throws in that case and
   there is nothing useful to do about it beyond saying so quietly."
  []
  (when-let [stop (resolve-fn "stop")]
    (try
      (let [f (stop {})]
        ;; println as well as the log: this runs from a shutdown hook, and
        ;; log4j registers its own hook that has usually already torn the
        ;; appenders down by now — the first run of this printed "Unable to
        ;; register Log4j shutdown hook" instead of the path, which is the one
        ;; thing the hook exists to tell you.
        (println "profile written to" (str f))
        (log/info "profile written to" (str f))
        f)
      (catch Throwable t
        (log/debug t "stopping the profiler failed")
        nil))))

(defn start!
  "Begin profiling if asked to, and arrange for the flamegraph to be written on
   exit. Returns true when profiling actually started.

   The shutdown hook is what makes this usable: a broker under test is stopped
   by Ctrl-C or a kill, and a profile that is only written on a clean return
   from -main would never be written at all."
  []
  (boolean
   (when event
     (if-let [start (resolve-fn "start")]
       (try
         (start {:event event})
         (.addShutdownHook (Runtime/getRuntime)
                           (Thread. ^Runnable (fn [] (stop!)) "profiler-stop"))
         (when ui-port
           (when-let [serve (resolve-fn "serve-ui")]
             (serve ui-port)
             (log/info "profiler UI on port" ui-port)))
         (log/info "profiling" (name event) "- flamegraph written on exit")
         true
         (catch Throwable t
           ;; Most often -Djdk.attach.allowAttachSelf is missing, which
           ;; async-profiler reports as a failure to attach to self.
           (log/warn t "could not start the profiler; the broker is unaffected")
           false))
       (do (log/warn "-Dmqttkat.profile is set but clj-async-profiler is not on"
                     "the classpath - it lives in the :dev profile, so run with"
                     "lein rather than from the uberjar")
           false)))))
