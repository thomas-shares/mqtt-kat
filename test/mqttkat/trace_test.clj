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

(deftest only-the-messages-asked-for-are-traced
  ;; The last minute of a 3,000/s chaos run is sequence 450 and on.
  (with-redefs [trace/messages (delay #":(4[5-9]\d|[5-9]\d\d)$")]
    (is (true? (trace/message? {:payload (.getBytes "71:493|x")})))
    (is (false? (trace/message? {:payload (.getBytes "71:93|x")})) "an earlier one")
    (testing "and with no topics asked for, a publish where it enters"
      (is (true? (trace/publishes? "chaos/r/t5")))))
  (testing "every message when none is named"
    (is (true? (trace/message? {:payload (.getBytes "71:93|x")})))
    (is (false? (trace/publishes? "chaos/r/t5")) "and no publish unless a topic is")))
