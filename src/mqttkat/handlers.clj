(ns mqttkat.handlers
  (:use [mqttkat.s :only [server]]))

(def clients (atom {}))


(def subscribers (atom {}))

(defn add-client [msg]
  (let [client-id (:client-id msg)
        ;_ (println client-id)
        x (some #(and (= (:client-id (second %)) client-id ) %)  @clients)]
        ;_ (println "x: " x)]
    x))


(defn connect [msg]
  (println "CONNECT: " msg)
  (add-client msg)
  (swap! clients assoc (:client-key msg) (dissoc msg  :packet-type))
  {:packet-type :CONNACK
   :session-present false
   :return-code-value 0x00})

(defn connack [msg]
  (println "CONNACK: " msg))

(defn send-message [key msg]
  (println "sending message  from  clj")
  (let [s (:server (meta @server))]
    (.sendMessage s key msg)))

(defn publish [msg]
  (println "clj PUBLISH: " msg)
  (let [payload (:payload msg)
        topic (:topic msg)
        key (get @subscribers topic)
        _ (println key " " @subscribers)]
    (send-message key {:packet-type :PUBLISH :payload payload :topic "test"})))

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
  (let [client-key (:client-key msg)
        topics (:topics msg)
        _ (println "topics: " topics " key:" client-key)]
    (swap! subscribers assoc (:topic (first topics)) client-key)
    (println "subscribers: " @subscribers))
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
  (println "DISCONNECT: " msg)
  {:packet-type :DISCONNECT})

(defn authenticate [msg]
  (println "AUTHENTICATE: " msg))
