(defproject mqtt-kat "0.0.1"
  :author "Thomas van der Veen"
  :description "High-performance event-driven MQTT broker for Clojure"
  :license {:name "Apache License, Version 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0.html"
            :distribution :repo}
  :min-lein-version "2.8.1"
  :global-vars {*warn-on-reflection* true}

  :dependencies
  [[org.clojure/clojure "1.9.0"]
   [org.clojure/spec.alpha "0.1.143"]
   [org.clojure/core.specs.alpha "0.1.24"]]

  :plugins
  [[lein-swank   "1.4.5"]
   [lein-pprint  "1.1.2"]
   [lein-ancient "0.6.10"]
   [lein-codox   "0.10.3"]]

  :jvm-opts
  ["-Dclojure.compiler.disable-locals-clearing=true"
   "-Xms1g" "-Xmx1g"]

  :javac-options ["-source" "1.8" "-target" "1.8" "-g"]
  :java-source-paths ["src/java"]
  :test-paths ["test"]
  :jar-exclusions [#"^java.*"]) ; exclude the java directory in source path
  ;:main mqttkat.server
  ;:aot [mqttkat.server])
