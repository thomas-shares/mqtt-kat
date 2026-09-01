(ns mqttkat.handlers.connack
  (:require [clojure.tools.logging :as log]))

(defn connack [msg]
  (log/debug "CONNACK:" msg))