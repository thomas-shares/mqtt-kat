(defproject mqtt-kat "0.0.1"
  :author "Thomas van der Veen"
  :description "High-performance event-driven MQTT broker for Clojure"
  :license {:name "Apache License, Version 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0.html"
            :distribution :repo}
  :min-lein-version "2.8.1"
  :global-vars {*warn-on-reflection* true}

  :dependencies
  [[org.clojure/clojure "1.10.0"]
   [org.clojure/core.async "0.4.490"]
   [org.clojure/spec.alpha "0.2.176"]
   [org.clojure/core.specs.alpha "0.2.44"]
   [org.clojure/test.check "0.10.0-alpha3"]
   [proto-repl "0.3.1"]
   [org.craigandera/causatum "0.3.0"]
   [clojurewerkz/triennium "1.0.0-beta2"]
   [overtone/at-at "1.2.0"]
   [com.clojure-goes-fast/clj-async-profiler "0.2.2"]]

  :plugins
  [[lein-swank   "1.4.5"]
   [lein-pprint  "1.1.2"]
   [lein-ancient "0.6.10"]
   [lein-codox   "0.10.3"]]

  :resource-paths ["resources/REBL-0.9.109.jar"]

  :jvm-opts
  ["-Dclojure.compiler.disable-locals-clearing=true"
   "-Xms512m" "-Xmx4G" "-Djdk.attach.allowAttachSelf" "-XX:+UnlockDiagnosticVMOptions" "-XX:+DebugNonSafepoints"]

  :javac-options ["-source" "1.8" "-target" "1.8" "-g"]
  :java-source-paths ["src/java"]
  :test-paths ["test"]
  :jar-exclusions [#"^java.*"] ; exclude the java directory in source path
  :main mqttkat.server
  :aot [mqttkat.server])
