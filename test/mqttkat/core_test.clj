(ns mqttkat.core-test
  (:require [clojure.test :refer :all]
            [mqttkat.server :refer :all]
            [mqttkat.handlers :refer :all]))

(deftest subscription
  (is (= {"test" [:key]} (add-subscriber {} "test" :key))))
