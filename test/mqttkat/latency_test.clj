(ns mqttkat.latency-test
  "mqttkat.rama.latency: how long appends to Rama take, by kind. No broker,
   so not portable: nothing in it is MQTT."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [mqttkat.rama.latency :as latency])
  (:import [java.util.concurrent CompletableFuture]))

(use-fixtures :each (fn [f] (latency/clear!) (f) (latency/clear!)))

(deftest times-are-counted-by-kind-into-buckets
  (doseq [ms (concat (repeat 98 3) [40 900])]
    (latency/note! "enqueue@ack" ms))
  (latency/note! "hold@append-ack" 0)
  (let [s (latency/summary)]
    (testing "[count p50 p99 max], each percentile the bound of its bucket"
      (is (= [100 5 50 900] (get s "enqueue@ack")))
      (is (= [1 1 1 0] (get s "hold@append-ack"))))
    (is (= ["enqueue@ack" "hold@append-ack"] (keys s)))))

(deftest a-window-starts-again-after-each-take-and-the-total-does-not
  (latency/note! "probe/queues@ack" 12)
  (is (= {"probe/queues@ack" [1 20 20 12]} (latency/take-window!)))
  (is (= {} (latency/take-window!)))
  (latency/note! "probe/queues@ack" 12)
  (is (= [2 20 20 12] (get (latency/summary) "probe/queues@ack"))))

(deftest beyond-the-last-bound-the-max-is-the-percentile
  (latency/note! "dequeue@ack" 45000)
  (is (= [1 45000 45000 45000] (get (latency/summary) "dequeue@ack"))))

(deftest a-future-is-timed-when-it-completes-and-not-when-it-fails
  (let [ok  (CompletableFuture.)
        bad (CompletableFuture.)]
    (is (identical? ok (latency/timed "claim@ack" ok)))
    (latency/timed "claim@ack" bad)
    (.complete ok nil)
    (.completeExceptionally bad (Exception. "timed out"))
    (is (= 1 (first (get (latency/summary) "claim@ack"))))))

(deftest a-log-line-names-each-kind
  (is (= "a@ack n=2 p50<=5 p99<=10 max=7 | b@append-ack n=1 p50<=1 p99<=1 max=1"
         (latency/log-line {"a@ack" [2 5 10 7] "b@append-ack" [1 1 1 1]}))))
