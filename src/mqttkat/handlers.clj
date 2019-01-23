(ns mqttkat.handlers
  (:use [mqttkat.s :only [server]])
  (:import [org.mqttkat.server MqttServer])
  (:require [mqttkat.spec :as spec]
            [clojure.spec.alpha :as s]))

(defonce clients (atom {}))
(defonce inflight (atom {}))

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

(defn qos-0 [keys topic msg]
  ;(println "QOS 0")
  (send-message keys
    {:packet-type :PUBLISH
     :payload (:payload msg)
     :topic topic
     :qos 0
     :retain? false}))

(defn qos-1 [keys topic msg]
  ;(println "QOS 1")
  (send-message [(:client-key msg)]
    {:packet-type :PUBACK
     :packet-identifier (:packet-identifier msg)})
  (qos-0 keys topic msg))

(defn qos-2 [keys topic msg]
  ;(println "QOS 2")
  (swap! inflight assoc [(:client-key msg) (:packet-identifier msg)] {:msg msg :topic topic :keys keys})
  (send-message [(:client-key msg)]
    {:packet-type :PUBREC
     :packet-identifier (:packet-identifier msg)}))


(defn publish [msg]
  ;(println "clj PUBLISH: ")
  ;(println (str "valid publish: " (s/valid? :mqtt/publish msg)))
  ;(s/explain :mqtt/publish msg)
  (let [topic (:topic msg)
        qos (:qos msg)
        keys (get @subscribers topic)]
        ;_ (println "Keys: " keys " " @subscribers)]
    (when keys
      (condp = qos
        0 (qos-0 keys topic msg)
        1 (qos-1 keys topic msg)
        2 (qos-2 keys topic msg)))))

(defn puback [msg]
  (println "received PUBACK: " msg))

(defn pubrec [msg]
  (println "received PUBREC: " msg))

(defn pubrel [msg]
  ;(println "received (PUBREL: " msg)
  (let [packet-identifier (:packet-identifier msg)
        client-key (:client-key msg)]
    (send-message [client-key]
      {:packet-type :PUBCOMP
       :packet-identifier packet-identifier})
    (let [m (get @inflight [client-key packet-identifier])]
      (qos-0 (:keys m) (:topic m) (:msg m))
      (swap! inflight dissoc [client-key packet-identifier]))))

(defn pubcomp  [msg]
  (println "received PUBCOMP: " msg))

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
