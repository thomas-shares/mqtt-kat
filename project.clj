(defproject mqtt-kat "0.0.1"
  :author "Thomas van der Veen"
  :description "High-performance event-driven MQTT broker for Clojure"
  :license {:name "Apache License, Version 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0.html"
            :distribution :repo}
  :min-lein-version "2.8.1"
  :global-vars {*warn-on-reflection* true}

  :dependencies
  ;; Pinned by Rama: it checks the exact Clojure version at load time and
  ;; refuses any other, 1.12.5 included. Move these together.
  [[org.clojure/clojure "1.12.4"]
   [org.clojure/core.async "1.9.865"]
   [org.clojure/spec.alpha "0.6.249"]
   [org.clojure/core.specs.alpha "0.5.81"]
   [org.clojure/test.check "1.1.3"]
   [clojurewerkz/triennium "1.0.0-beta2"]
   [overtone/at-at "1.4.65"]
   [http-kit "2.8.1"]
   [hiccup "2.0.0"]
   ;; ring-core only: wrap-resource, wrap-content-type and wrap-not-modified,
   ;; which serve the console's stylesheets out of resources/public. http-kit
   ;; is the adapter, so none of ring's own server is wanted.
   [ring/ring-core "1.13.0"]
   ;; The websocket payload carries a history array now, which is past what a
   ;; hand-rolled writer should be asked to do.
   [cheshire "5.13.0"]
   ;;[djblue/portal "0.6.1"]
   ;[io.zalky/cues  "0.2.1"]
   [org.clojure/tools.logging "1.3.0"]
   [org.apache.logging.log4j/log4j-api "2.26.1"]
   [org.apache.logging.log4j/log4j-core "2.26.1"]
   [org.apache.logging.log4j/log4j-slf4j-impl "2.26.1"]
   ;; Rama, the durable side of the broker (see mqttkat.rama.*). A plain
   ;; dependency rather than :provided, which is what a module-only project
   ;; would use: the broker embeds an InProcessCluster in dev and is a foreign
   ;; client of a real cluster in production, and both need Rama on the
   ;; broker's own classpath. The jar that goes to a cluster with
   ;; `rama deploy` is the thin `lein jar`, which carries no dependencies.
   ;; Rama binds slf4j 2 to log4j; this project binds slf4j 1.7 to the same
   ;; log4j, and one binding is enough — the broker's stays.
   [com.rpl/rama "1.9.0" :exclusions [org.apache.logging.log4j/log4j-slf4j2-impl]]]

  :repositories [["rpl-releases" {:url "https://nexus.redplanetlabs.com/repository/maven-public-releases"}]]

  :jvm-opts
  ["-Dclojure.compiler.disable-locals-clearing=true"
   "-Xms128m" "-Xmx4G" "-Djdk.attach.allowAttachSelf" "-XX:+UnlockDiagnosticVMOptions" "-XX:+DebugNonSafepoints"
   ;; Rama's RocksDB loads a native library; on 21 the JVM warns about it on
   ;; every start unless told these are expected.
   "--add-opens" "java.base/java.lang=ALL-UNNAMED" "--enable-native-access=ALL-UNNAMED"]

  ;; --release 21, not -source/-target: it pins the platform API too, so javac
  ;; can prove nothing newer leaks in (and stops warning that it cannot). 21 is
  ;; the floor for virtual threads, which the connection handling relies on.
  ;; -proc:none because log4j-core ships an annotation processor that javac
  ;; would otherwise discover and run over code that has no log4j plugins.
  :javac-options ["-Xlint:unchecked" "--release" "21" "-g" "-proc:none"]
  :java-source-paths ["src/java"]
  :test-paths ["test"]
  ;; `lein test` runs the unit tests only. The load simulations in
  ;; client-generator{,-2} are tagged ^:performance and run on request with
  ;; `lein test :performance`.
  :test-selectors {:default     (complement :performance)
                   :performance :performance
                   :all         (constantly true)}
  ;; mqttkat.rama.module is not instrumented. `defmodule` expands its whole
  ;; body — every depot, PState and dataflow form of the topology — into one
  ;; `reify` method, and cloverage's instrumentation wraps each form in
  ;; tracking code: together they overflow the JVM's 64 KB limit on a single
  ;; method, and the run dies with "Method code too large!" before any test
  ;; has run. Nothing is lost by leaving it out: the forms in a topology are
  ;; not executed as Clojure — Rama compiles them into a dataflow graph — so
  ;; a line count over them would measure nothing. The namespace is still
  ;; loaded and still exercised by mqttkat.rama-test.
  :cloverage {:ns-exclude-regex [#"mqttkat\.rama\.module"]}
  :plugins [[lein-ancient "0.6.15"]
            [lein-auto "0.1.3"]
            [lein-cloverage "1.2.4"]]
  :jar-exclusions [#"^java.*"] ; exclude the java directory in source path
  ;; ^:skip-aot: without it `lein jar` and `lein run` compile the main
  ;; namespace and everything it reaches, and the thin jar came out holding
  ;; two hundred compiled Rama classes. That jar is what `rama deploy` ships
  ;; to a cluster, whose workers have Rama already; theirs must be the only
  ;; copy. The uberjar profile below still compiles it for its Main-Class.
  :main ^:skip-aot mqttkat.server
  :profiles
  {:dev
   {:dependencies [[djblue/portal "0.67.2"]
                   [org.craigandera/causatum "0.3.0"]
                   [com.clojure-goes-fast/clj-async-profiler "1.8.0"]
                   [virgil "0.4.0"]]}
   ;; AOT only where it is actually needed — the uberjar, which needs a
   ;; compiled Main-Class. At the top level it compiled every namespace
   ;; mqttkat.server requires on every task, and the resulting stale classes
   ;; shadow newer sources until someone thinks to run `lein clean`.
   ;; And into a directory of its own: compiled into target/classes, those
   ;; classes — every namespace, and every dependency's — outlive the build
   ;; and are picked up by the next `lein test`, where a class compiled from
   ;; yesterday's source meets today's, and a fn's inner class is not found.
   ;; The jar still lands in target/; only the class files move.
   :uberjar {:aot          [mqttkat.server]
             :compile-path "target/uberjar-classes"}})
