(def project 'mqtt-kit)
(def version "0.1.0-SNAPSHOT")

(set-env! :resource-paths #{"resources" "src"}
          :source-paths   #{"test" "src"}
          :dependencies   '[[org.clojure/clojure "1.10.0"]
                            [proto-repl "0.3.1"]
                            [proto-repl-charts "0.3.1"]
                            [adzerk/boot-test "RELEASE" :scope "test"]])

(task-options!
 pom {:project     project
      :version     version
      :description "FIXME: write description"
      :url         "http://example/FIXME"
      :scm         {:url "https://github.com/yourname/mqtt-kit"}
      :license     {"Eclipse Public License"
                    "http://www.eclipse.org/legal/epl-v10.html"}})

(deftask build
  "Build and install the project locally."
  []
  (comp (pom) (jar) (install)))

(deftask dev
  ""
  []
  (comp (watch) (javac)))

(require '[adzerk.boot-test :refer [test]])
