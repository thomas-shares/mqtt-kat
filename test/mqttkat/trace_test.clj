(ns mqttkat.trace-test
  (:require [clojure.test :refer [deftest is testing]]
            [mqttkat.trace :as trace]))

(deftest a-traced-message-is-named-by-its-payload
  (testing "a chaos client's payload: up to the first |"
    (is (= "142:781" (trace/label {:payload (.getBytes "142:781|xxxxxxxx")}))))
  (testing "any other: by its key in the cluster"
    (is (= "k-1" (trace/label {:payload (.getBytes "no bar here") :msg-key "k-1"})))
    (is (= "k-2" (trace/label {:mqttkat.handlers/cluster-key "k-2"}))))
  (testing "and nothing to go by"
    (is (= "?" (trace/label {})))))

(deftest nothing-is-traced-unless-asked
  (is (false? (trace/on? "anyone" "a/topic")))
  (is (false? (trace/topic? "a/topic")))
  (is (false? (trace/following? "a/topic"))))

(deftest only-the-steps-asked-for-are-traced
  (with-redefs [trace/steps (delay #"handed over|cluster's queue")]
    (.clear ^java.util.concurrent.ConcurrentHashMap @#'trace/step-followed)
    (try
      (is (true? (#'trace/step? "handed over to the cluster's queue")))
      (is (true? (#'trace/step? "left on the cluster's queue: had here")))
      (is (false? (#'trace/step? "sent")) "not a live send")
      (is (false? (#'trace/step? "sent")) "asked again, from what was found the first time")
      (finally
        (.clear ^java.util.concurrent.ConcurrentHashMap @#'trace/step-followed))))
  (testing "every step when none is named"
    (is (true? (#'trace/step? "sent")))))
