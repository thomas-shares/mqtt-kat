(ns mqttkat.handlers.connack
  (:require [mqttkat.handlers :refer [logger]]))

(defn connack [msg]
  (logger "CONNACK: " msg))