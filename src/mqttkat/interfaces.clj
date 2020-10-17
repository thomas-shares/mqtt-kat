(ns mqttkat.interfaces)

(defonce ^:dynamic *system* (ref nil))
(defonce ^:dynamic *server* (atom nil))
