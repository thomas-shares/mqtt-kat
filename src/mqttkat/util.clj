(ns mqttkat.util
  (:require [mqttkat.handlers :as handlers]))



(defn info []
  (println (count @handlers/clients))
  (Thread/sleep 15000)
  (recur))
