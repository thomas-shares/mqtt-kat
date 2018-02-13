(ns mqttkat.handlers
  (:use [mqttkat.s :only [server]]))

(def clients (atom {}))

;;  example
;; {"topic" [key_of_client1, key_of_client1, ..]
;;  "other_topic" [key_of_clien3]}
(def subscribers (atom {}))

(defn add-client [msg]
  (let [client-id (:client-id msg)
        ;_ (println client-id)
        x (some #(and (= (:client-id (second %)) client-id ) %)  @clients)]
        ;_ (println "x: " x)]
    x))

(defn send-message [keys msg]
  ;;(println "sending message  from  clj")
  ;;(println (class  keys))
  (let [s (:server (meta @server))]
    (.sendMessage s keys msg)))


(defn connect [msg]
  ;;(println "CONNECT: " msg)
  (add-client msg)
  (swap! clients assoc (:client-key msg) (dissoc msg  :packet-type))
  (send-message [(:client-key msg)]
                {:packet-type :CONNACK
                 :session-present false
                 :return-code-value 0x00}))

(defn connack [msg]
  (println "CONNACK: " msg))


(defn publish [msg]
  ;(println "clj PUBLISH: " msg)
  (let [payload (:payload msg)
        topic (:topic msg)
        keys (get @subscribers topic)]
        ;_ (println "Keys: " keys)]
    (when keys
      (send-message keys
        {:packet-type :PUBLISH
         :payload payload
         :topic topic}))))

(defn puback [msg]
  (println "PUBACK: " msg))

(defn pubrec [msg]
  (println "PUBREC: " msg))

(defn pubrel [msg]
  (println "PUBREL: " msg))

(defn pubcomp  [msg]
  (println "PUBCOMP: " msg))

(defn add-subscriber [subscribers topic key]
  (if (contains? subscribers topic)
    (update-in subscribers [topic] conj key)
    (assoc subscribers topic [key])))

(defn subscribe [msg]
  ;(println "SUBSCRIBE:" msg)
  (let [client-key (:client-key msg)
        topics (:topics msg)]
        ;;_ (println "topics: " topics " key:" client-key)]
    ;(swap! subscribers assoc (:topic (first topics)) client-key)
    (swap! subscribers add-subscriber (:topic (first topics)) client-key)
    ;(println "subscribers: " @subscribers)
    (send-message [client-key] {:packet-type :SUBACK
                                :packet-identifier  (:packet-identifier msg)
                                :payload [0]})))

(defn suback [msg]
  (println "SUBACK: " msg))

(defn unsubscribe [msg]
  (println "UNSCUBSCRIBE: " msg))

(defn pingreq [msg]
  ;;(println "PINGREQ: " msg)
  (send-message [(:client-key msg)] {:packet-type :PINGRESP}))

(defn pingresp [msg]
  (println "PINGRESP: " msg))

(defn disconnect [msg]
  ;;(println "DISCONNECT: " msg)
  (send-message [(:client-key msg)] {:packet-type :DISCONNECT}))

(defn authenticate [msg]
  (println "AUTHENTICATE: " msg))
