(ns mqttkat.handlers)

(defn connect [msg]
  (println "CONNECT: " msg)
  {:packet-type :CONNACK
   :session-present false
   :return-code-value 0x00})

(defn connack [msg]
  (println "CONNACK: " msg))

(defn publish [msg]
  (println "clj PUBLISH: " msg))

(defn puback [msg]
  (println "PUBACK: " msg))

(defn pubrec [msg]
  (println "PUBREC: " msg))

(defn pubrel [msg]
  (println "PUBREL: " msg))

(defn pubcomp  [msg]
  (println "PUBCOMP: " msg))

(defn subscribe [msg]
  (println "SUBSCRIBE:" msg)
  {:packet-type :SUBACK
   :packet-identifier  (:packet-identifier msg)
   :payload [0]})

(defn suback [msg]
  (println "SUBACK: " msg))

(defn unsubscribe [msg]
  (println "UNSCUBSCRIBE: " msg))

(defn pingreq [msg]
  (println "PINGREQ: " msg)
  {:packet-type :PINGRESP})

(defn pingresp [msg]
  (println "PINGRESP: " msg))

(defn disconnect [msg]
  (println "DISCONNECT: " msg))

(defn authenticate [msg]
  (println "AUTHENTICATE: " msg))
