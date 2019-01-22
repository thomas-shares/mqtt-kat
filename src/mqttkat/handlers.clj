(ns mqttkat.handlers
  (:use [mqttkat.s :only [server]])
  (:import [org.mqttkat.server MqttServer])
  (:require [mqttkat.spec :as spec]
            [clojure.spec.alpha :as s]))

(defonce clients (atom {}))

;;  example
;; {"topic" [key_of_client1, key_of_client1, ..]
;;  "other_topic" [key_of_clien3]}
(defonce subscribers (atom {}))

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
    (.sendMessage ^MqttServer s keys msg)))


(defn connect [msg]
  (println "clj CONNECT: " msg)
  ;(println (str "valid connect: " (s/valid? :mqtt/connect msg)))
  ;(s/explain :mqtt/connect msg)
  (add-client msg)
  (swap! clients assoc (:client-key msg) (dissoc msg  :packet-type))
  (send-message [(:client-key msg)]
                {:packet-type :CONNACK
                 :session-present? false
                 :connect-return-code 0x00}))

(defn connack [msg]
  (println "CONNACK: " msg))


(defn publish [msg]
  (println "clj PUBLISH: ")
  ;(println (str "valid publish: " (s/valid? :mqtt/publish msg)))
  ;(s/explain :mqtt/publish msg)
  (let [payload (:payload msg)
        topic (:topic msg)
        keys (get @subscribers topic)]
        ;_ (println "Keys: " keys " " @subscribers)]
    (when keys
      (send-message keys
        {:packet-type :PUBLISH
         :payload payload
         :topic topic
         :qos 0
         :retain? false}))))

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
  ;(println "clj SUBSCRIBE:" msg)
  (let [client-key (:client-key msg)
        topics (:topics msg)
        c (count topics)
        filters (mapv #(:topic-filter %) topics)]
        ;_ (println c)]
    (doseq [f filters]
      (swap! subscribers add-subscriber f client-key))
    ;(println "subscribers: " @subscribers)
    (send-message [client-key] {:packet-type :SUBACK
                                :packet-identifier  (:packet-identifier msg)
                                :response (into [] (take c (repeat 0)))})))
 
(defn remove-subsciber [m [topic] key]
  (update m topic (fn [v] (filterv #(not= key %) v))))

(defn unsubscribe [msg]
  (println "clj UNSCUBSCRIBE: " msg)
  (swap! subscribers remove-subsciber (:topics msg) (:client-key msg)))

(defn pingreq [msg]
  (println "clj PINGREQ: " msg)
  (send-message [(:client-key msg)] {:packet-type :PINGRESP}))

(defn pingresp [msg]
  (println "clj PINGRESP: " msg))


(defn remove-client-subscriber [m val]
  (into {} (map (fn [[k v]] (let [nv (filterv #(not= val %) v)] {k nv}))  m)))

(defn disconnect [msg]
  (println "clj DISCONNECT: " msg)
  ;(println "count: " (count (get @subscribers "test")))
  (swap! subscribers remove-client-subscriber  (:client-key msg))
  (swap! clients dissoc (:client-key msg)))
  ;(println "subscribers: " @subscribers)
  ;(println "count: " (count (get @subscribers "test"))))

(defn authenticate [msg]
  (println "AUTHENTICATE: " msg))
