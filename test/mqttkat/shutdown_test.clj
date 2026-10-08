(ns mqttkat.shutdown-test
  "What a stopping broker sends before it leaves the cluster.

   The shutdown of the handlers' timer pool cancels the timers that send the
   settle words and take-offs batched a moment before. A stop that did not send
   them itself lost them: the message's origin kept it, and queued a copy for
   clients that had it already. Each test here pins one part of what the stop
   does instead."
  (:require [clojure.test :refer [deftest is]]
            [com.rpl.rama :as r]
            [mqttkat.bridge :as bridge]
            [mqttkat.handlers :as h]
            [mqttkat.rama.cluster :as cluster]
            [mqttkat.s :as s]
            [mqttkat.server :as server]
            [overtone.at-at :as at]))

(deftest a-batch-the-shutdown-cancelled-is-sent-by-the-stop
  (let [was    @h/session-source
        sent   (atom [])
        waited (atom nil)]
    (reset! h/session-source
            {:settled!      (fn [origin ks] (swap! sent conj [:settled origin ks]))
             :dequeue!      (fn [client-id ks]
                              (swap! sent conj [:dequeued client-id (vec ks)])
                              (java.util.concurrent.CompletableFuture/completedFuture nil))
             :drain-writes! (fn [_] (reset! waited (count @sent)) true)})
    (try
      ;; The timers the shutdown cancels never run.
      (with-redefs [at/after      (fn [& _] nil)
                    bridge/drain! (fn [_] true)]
        (#'h/settle-soon! "origin-1" "k1")
        (#'h/dequeue-soon! "client-1" "q1" true)
        (is (empty? @sent) "nothing is sent by a timer that never runs")
        (is (true? (h/flush-batches! 3000)))
        (is (= [[:dequeued "client-1" ["q1"]] [:settled "origin-1" #{"k1"}]] @sent)
            "the stop sends both")
        (is (= 2 @waited) "and waits for the queue writes only once both are sent"))
      (finally (reset! h/session-source was)))))

(deftest a-stop-waits-for-the-queue-writes-in-flight
  (let [landed (java.util.concurrent.CompletableFuture.)
        conn   {:events :stand-in :queue-writer (cluster/queue-writer)}]
    (with-redefs [r/foreign-append-async! (fn [_ _ _] landed)]
      (cluster/enqueue! conn "x" {:topic "t" :payload (.getBytes "p") :qos 1} nil)
      (is (false? (cluster/drain-queue-writes! conn 200)) "a write still out: not drained")
      (.complete landed nil)
      (is (true? (cluster/drain-queue-writes! conn 5000)) "landed: drained"))))

(deftest stopping-sends-what-the-shutdown-cancelled-before-leaving-the-cluster
  (let [was   @s/*server*
        steps (atom [])]
    (try
      (reset! s/*server* (fn [& _] (swap! steps conj :clients-stopped)))
      (with-redefs [at/stop-and-reset-pool! (fn [& _] (swap! steps conj :pool-stopped))
                    h/flush-batches!        (fn [_] (swap! steps conj :batches-flushed) true)
                    cluster/disconnect!     (fn [] (swap! steps conj :left-cluster))]
        (server/stop!))
      (is (= [:pool-stopped :clients-stopped :batches-flushed :left-cluster] @steps))
      (finally (reset! s/*server* was)))))
