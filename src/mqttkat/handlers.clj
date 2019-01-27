(ns mqttkat.handlers
  (:use [mqttkat.s :only [server]])
  (:import [org.mqttkat.server MqttServer])
  (:require [mqttkat.spec :as spec]
            [clojurewerkz.triennium.mqtt :as tr]
            [clojure.spec.alpha :as s]))

(defonce clients (atom {}))
(defonce inflight (atom {}))
(defonce sub2 (atom (tr/make-trie)))
(defonce outbound (atom {}))

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
  ;(println "sending message  from  clj " (:packet-type msg) " " (:packet-identifier msg))
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
  ;;(println "respond QOS 0 ")
  (send-message (mapv #(:client-key %) keys)
    {:packet-type :PUBLISH
     :payload (:payload msg)
     :topic topic
     :qos 0
     :retain? false}))

(defn qos-1 [keys topic msg]
  (send-message [(:client-key msg)]
    {:packet-type :PUBACK
     :packet-identifier (:packet-identifier msg)})
  (let [qos-0-keys (filter #(zero? (:qos %)) keys)
        qos-1-keys (filter #(= 1 (:qos %)) keys)]

    (println (count qos-0-keys) " " (count qos-1-keys))

    (when (<  0 (count qos-0-keys))
      (qos-0 qos-0-keys topic msg))
    (when (< 0 (count qos-1-keys))
      (do
        (send-message (mapv #(:client-key %) keys)
              {:packet-type :PUBLISH
               :payload (:payload msg)
               :topic topic
               :qos 1
               :retain? false
               :packet-identifier (:packet-identifier msg)})))))
        ;(doseq [k qos-1-keys]
          ;(println "K " k)
          ;(swap! outbound assoc (:client-key k) (:packet-identifier msg)))))))

(defn qos-2 [keys topic msg]
  ;(println "QOS 2")
  (swap! inflight assoc [(:client-key msg) (:packet-identifier msg)] {:msg msg :topic topic :keys keys})
  (send-message [(:client-key msg)]
    {:packet-type :PUBREC
     :packet-identifier (:packet-identifier msg)}))


(defn publish [msg]
  (println "clj PUBLISH: ")
  ;(println (str "valid publish: " (s/valid? :mqtt/publish msg)))
  ;(s/explain :mqtt/publish msg)
  (let [topic (:topic msg)
        qos (:qos msg)
        ;keys (get @subscribers topic)
        keys (tr/find @sub2 topic)]
        ;_ (println "Keys: " keys " qos: " qos)]
    (when keys
      (condp = qos
        0 (qos-0 keys topic msg)
        1 (qos-1 keys topic msg)
        2 (qos-2 keys topic msg)))))

(defn puback [msg]
  (println "received PUBACK: " msg))
  ;(swap! outbound dissoc [(:client-key msg) (:packet-identifier msg)]))

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
        qos (mapv #(long (:qos %)) topics)]
        ;_ (println qos)]
    (doseq [t topics]
      (swap! subscribers add-subscriber (:topic-filter t) client-key)
      (swap! sub2 tr/insert (:topic-filter t)  {:client-key client-key :qos (:qos t)}))
    ;(println "subscribers: " @sub2)
    (send-message [client-key] {:packet-type :SUBACK
                                :packet-identifier  (:packet-identifier msg)
                                :response qos})))

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
  ;(println "clj DISCONNECT: " msg)
  ;(println "count: " (count (get @subscribers "test")))
  (swap! subscribers remove-client-subscriber  (:client-key msg))
  (swap! clients dissoc (:client-key msg)))
  ;(println "subscribers: " @subscribers)
  ;(println "count: " (count (get @subscribers "test"))))

(defn authenticate [msg]
  (println "AUTHENTICATE: " msg))
