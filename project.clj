(defproject mqtt-kat "0.0.1"
  :author "Thomas van der Veen"
  :description "High-performance event-driven MQTT broker for Clojure"
  :license {:name "Apache License, Version 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0.html"
            :distribution :repo}
  :min-lein-version "2.8.1"
  :global-vars {*warn-on-reflection* true}

  :dependencies
  [[org.clojure/clojure "1.10.1"]
   [org.clojure/core.async "1.1.587"]
   [org.clojure/spec.alpha "0.2.187"]
   [org.clojure/core.specs.alpha "0.2.44"]
   [org.clojure/test.check "1.0.0"]
   [org.craigandera/causatum "0.3.0"]
   [clojurewerkz/triennium "1.0.0-beta2"]
   [overtone/at-at "1.2.0"]]

  :jvm-opts
  ["-Dclojure.compiler.disable-locals-clearing=true"
   "-Xms512m" "-Xmx4G" "-Djdk.attach.allowAttachSelf" "-XX:+UnlockDiagnosticVMOptions" "-XX:+DebugNonSafepoints"]

  :javac-options ["-source" "1.8" "-target" "1.8" "-g"]
  :java-source-paths ["src/java"]
  :test-paths ["test"]
  :jar-exclusions [#"^java.*"] ; exclude the java directory in source path
  :main mqttkat.server
  :aot [mqttkat.server])
