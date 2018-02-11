(ns mqttkat.core-test
  (:require [clojure.test :refer :all]
            [mqttkat.server :refer :all]
            [mqttkat.handlers :refer :all]))

(deftest a-test
  (testing "FIXME, I fail."
    (is (= 1 1))))

(deftest subscription
  (is (= {"test" [:key]} (add-subscriber {} "test" :key))))
