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
