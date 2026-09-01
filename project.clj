(defproject mqtt-kat "0.0.1"
  :author "Thomas van der Veen"
  :description "High-performance event-driven MQTT broker for Clojure"
  :license {:name "Apache License, Version 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0.html"
            :distribution :repo}
  :min-lein-version "2.8.1"
  :global-vars {*warn-on-reflection* true}

  :dependencies
  [[org.clojure/clojure "1.12.5"]
   [org.clojure/core.async "1.9.865"]
   [org.clojure/spec.alpha "0.6.249"]
   [org.clojure/core.specs.alpha "0.5.81"]
   [org.clojure/test.check "1.1.3"]
   [org.craigandera/causatum "0.3.0"]
   [clojurewerkz/triennium "1.0.0-beta2"]
   [overtone/at-at "1.4.65"]
   ;;[djblue/portal "0.6.1"]
   [io.zalky/cues  "0.2.1"]
   [org.clojure/tools.logging "1.3.0"]
   [org.apache.logging.log4j/log4j-api "2.26.1"]
   [org.apache.logging.log4j/log4j-core "2.26.1"]
   [org.apache.logging.log4j/log4j-slf4j-impl "2.26.1"]]

  :jvm-opts
  ["-Dclojure.compiler.disable-locals-clearing=true"
   "-Xms128m" "-Xmx4G" "-Djdk.attach.allowAttachSelf" "-XX:+UnlockDiagnosticVMOptions" "-XX:+DebugNonSafepoints"]

  ;; --release 17 rather than -source/-target: it pins the platform API too, so
  ;; javac can prove nothing newer than 17 leaks in (and stops warning that it
  ;; cannot). -proc:none because log4j-core ships an annotation processor that
  ;; javac would otherwise discover and run over code that has no log4j plugins.
  :javac-options ["-Xlint:unchecked" "--release" "17" "-g" "-proc:none"]
  :java-source-paths ["src/java"]
  :test-paths ["test"]
  ;; `lein test` runs the unit tests only. The load simulations in
  ;; client-generator{,-2} are tagged ^:performance and run on request with
  ;; `lein test :performance`.
  :test-selectors {:default     (complement :performance)
                   :performance :performance
                   :all         (constantly true)}
  :plugins [[lein-ancient "0.6.15"]
            [lein-auto "0.1.3"]]
  :jar-exclusions [#"^java.*"] ; exclude the java directory in source path
  :main mqttkat.server
  :aot [mqttkat.server]
  :profiles
  {:dev
   {:dependencies [[djblue/portal "0.67.2"]
                   [com.clojure-goes-fast/clj-async-profiler "1.8.0"]
                   [virgil "0.4.0"]]}})
