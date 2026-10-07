(ns mqttkat.util-test
  "The stats loop's stall watchdog. Not broker-facing: it reads counters
   handed to it and dumps this JVM's own threads."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [mqttkat.util :as util]))

(defn- snap [writes received queued written]
  {:writes writes :received received :queued queued :written written :discarded 0})

(deftest a-broker-that-takes-packets-in-and-writes-none-has-stalled
  (testing "packets in, none out, clients connected"
    (is (util/stalled? (snap 10 5 0 0) (snap 10 9 0 0) 669)))
  (testing "nothing in, but a backlog waiting to be written"
    (is (util/stalled? (snap 10 5 8 2) (snap 10 5 8 2) 669)))
  (testing "a quiet broker is not stalled"
    (is (not (util/stalled? (snap 10 5 0 0) (snap 10 5 0 0) 669))))
  (testing "one that writes is not stalled"
    (is (not (util/stalled? (snap 10 5 0 0) (snap 11 9 0 0) 669))))
  (testing "one with no clients has nobody to write to"
    (is (not (util/stalled? (snap 10 5 0 0) (snap 10 9 0 0) 0)))))

(deftest a-stall-dumps-every-thread-once-per-call
  (let [dir (io/file (System/getProperty "java.io.tmpdir")
                     (str "mqttkat-dumps-" (System/nanoTime)))]
    (try
      (System/setProperty "mqttkat.threadDumps" (str dir))
      (reset! @#'util/thread-dumps-left 1)
      (#'util/dump-threads!)
      (let [files (seq (.listFiles dir))]
        (is (= 1 (count files)))
        (is (str/includes? (slurp (first files)) "main")
            "the dump names this JVM's threads"))
      (#'util/dump-threads!)
      (is (= 1 (count (.listFiles dir))) "and stops once its allowance is spent")
      (finally
        (System/clearProperty "mqttkat.threadDumps")
        (doseq [f (.listFiles dir)] (io/delete-file f true))
        (io/delete-file dir true)))))
