(defproject mqtt-kat "0.0.1"
  :author "Thomas van der Veen"
  :description "High-performance event-driven MQTT broker for Clojure"
  :license {:name "Apache License, Version 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0.html"
            :distribution :repo}
  :min-lein-version "2.8.1"
  :global-vars {*warn-on-reflection* true}

  :dependencies
  [[org.clojure/clojure "1.11.3"]
   [org.clojure/core.async "1.6.681"]
   [org.clojure/spec.alpha "0.5.238"]
   [org.clojure/core.specs.alpha "0.4.74"]
   [org.clojure/test.check "1.1.1"]
   [org.craigandera/causatum "0.3.0"]
   [clojurewerkz/triennium "1.0.0-beta2"]
   [overtone/at-at "1.3.58"]
   ;;[djblue/portal "0.6.1"]
   [io.zalky/cues  "0.2.1"]
   ]

  :jvm-opts
  ["-Dclojure.compiler.disable-locals-clearing=true"
   "-Xms128m" "-Xmx4G" "-Djdk.attach.allowAttachSelf" "-XX:+UnlockDiagnosticVMOptions" "-XX:+DebugNonSafepoints"]

  :javac-options ["-Xlint:unchecked" "-source" "1.8" "-target" "1.8" "-g"]
  :java-source-paths ["src/java"]
  :test-paths ["test"]
  :plugins [[lein-ancient "0.6.15"]
            [lein-auto "0.1.3"]]
  :jar-exclusions [#"^java.*"] ; exclude the java directory in source path
  :main mqttkat.server
  :aot [mqttkat.server]
  :profiles 
    {:dev 
      {:dependencies [[djblue/portal "0.55.1"]
                      [com.clojure-goes-fast/clj-async-profiler "1.2.2"]
                      [virgil "0.3.0"]]}}
  
  )
