(ns mqttkat.handlers
  (:require [mqttkat.s :refer [*server*]]
            [overtone.at-at :as at]
            [mqttkat.spec :as spec]
            [clojurewerkz.triennium.mqtt :as tr]
            [clojure.spec.alpha :as s]
            [clojure.core.async :as async])
  (:import [org.mqttkat.server MqttServer]
           [org.mqttkat.packages MqttPingReq MqttPublish
                                 MqttSubscribe MqttPubRel MqttPubAck MqttPubRec
                                 MqttPubComp MqttSubAck MqttPingResp]))

(defn logger [msg & args]
  (when true
    (println msg args)))

(def packet-identifier-queue-size 1024)
(def ^:dynamic *clients* (atom {}))
(def ^:dynamic *inflight* (atom {}))
(def ^:dynamic *subscriber-trie* (atom (tr/make-trie)))
(def ^:dynamic *outbound* (atom {}))
(def packet-identifiers (async/chan packet-identifier-queue-size))

(def my-pool (at/mk-pool))

(defn check-timer [key time-out]
  (let [current-time (System/currentTimeMillis)
        last-active (:last-active (get @*clients* key))]
      (when (some-> last-active (< (- current-time time-out)))
        (println "Timer fired for client: " (select-keys (get @*clients* key) [:last-active :client-id])))))

(defn add-timer! [key time]
  (let [time-out (* 1500 time)
        timer (at/every time-out #(check-timer key time-out) my-pool)]
    (swap! *clients* assoc-in [key :timer] timer)))




;; pre-load queue
(doseq [i (range 1 (inc packet-identifier-queue-size))]
  (async/>!! packet-identifiers i))

(defn get-packet-identifier []
  (let [p (async/<!! packet-identifiers)]
    ;(logger "get " p)
   p))

(defn put-packet-identifier [p]
  ;;(logger "put " p)
  (async/>!! packet-identifiers p))

#_(defn send-message [keys msg]
    ;;(logger "sending message  from  clj " (:packet-type msg) " " (:packet-identifier msg))
    ;;(logger (class  keys))
    (let [s (:server (meta @*server*))]))
  ;  (.sendMessage ^MqttServer s keys msg)))

(defn update-timestamps [client-keys]
  (doseq [client-key client-keys]
      (swap! *clients* assoc-in [client-key :last-active] (System/currentTimeMillis))))

(defn send-buffer [keys buf]
  ;;(logger "sending buffer from clj")
  ;;(logger (class  keys))
  (update-timestamps keys)
  (let [s (:server (meta @*server*))]
    (.sendMessageBuffer ^MqttServer s keys buf)))


(defn connack [msg]
  (logger "CONNACK: " msg))

(defn qos-0 [keys topic msg]
  ;;(logger "respond QOS 0 ")
  (send-buffer (mapv #(:client-key %) keys)
    (MqttPublish/encode {:packet-type :PUBLISH
                         :payload (:payload msg)
                         :topic topic
                         :qos 0
                         :retain? false})))

(defn qos-1-send [keys topic msg]
  ;;(logger "respond qos 1")
  (doseq [key (mapv #(:client-key %) keys)]
      (send-buffer [key]
        (MqttPublish/encode {:packet-type :PUBLISH
                             :payload (:payload msg)
                             :topic topic
                             :qos 1
                             :retain? false
                             :packet-identifier (get-packet-identifier)}))))


(defn qos-1 [keys topic msg]
  ;;(logger  "qos 1 received..." keys)
  (send-buffer [(:client-key msg)]
    (MqttPubAck/encode {:packet-type :PUBACK
                        :packet-identifier (:packet-identifier msg)}))
  (let [qos-0-keys (filter #(zero? (:qos %)) keys)
        qos-1-keys (filter #(or (= 1 (:qos %)) (= 2 (:qos %))) keys)]

    ;;(println (count qos-0-keys) " " (count qos-1-keys))

    (when (not-empty qos-0-keys)
      (qos-0 qos-0-keys topic msg))
    (when (not-empty qos-1-keys)
       (qos-1-send qos-1-keys topic msg))))
      ;  (doseq [k qos-1-keys]
      ;    (logger "K " k)
      ;    (swap! outbound assoc (:client-key k) (:packet-identifier msg))))))

(defn qos-2 [keys topic msg]
  ;;(logger "QOS 2")
  (swap! *inflight* assoc [(:client-key msg) (:packet-identifier msg)] {:msg msg :topic topic :keys keys})
  (send-buffer [(:client-key msg)]
    (MqttPubRec/encode {:packet-type :PUBREC
                        :packet-identifier (:packet-identifier msg)})))

(defn publish [msg]
  ;;(logger "clj PUBLISH: " msg)
  ;(logger (str "valid publish: " (s/valid? :mqtt/publish msg)))
  ;(s/explain :mqtt/publish msg)
  (let [topic (:topic msg)
        qos (:qos msg)
        ;keys (get @subscribers topic)
        keys (tr/matching-vals @*subscriber-trie* topic)
        _ (logger "Keys: " keys " qos: " qos)]
    (when keys
      (condp = qos
        0 (qos-0 keys topic msg)
        1 (qos-1 keys topic msg)
        2 (qos-2 keys topic msg)))))

(defn puback [msg]
  (logger "received PUBACK: " (:packet-identifier msg))
  (put-packet-identifier (:packet-identifier msg)))
  ;(swap! outbound dissoc [(:client-key msg) (:packet-identifier msg)]))

(defn pubrec [msg]
  (logger "received PUBREC: " msg)
  (send-buffer [(:client-key msg)]
     (MqttPubRel/encode
       {:packet-type :PUBREL :packet-identifier (:packet-identifier msg)})))

(defn qos-2-send [keys topic msg]
  (let [qos-0-keys (filter #(zero? (:qos %)) keys)
        qos-1-keys (filter #(= 1 (:qos %)) keys)
        qos-2-keys (filter #(= 2 (:qos %)) keys)]
    (logger (count qos-0-keys) " " (count qos-1-keys) " " (count qos-2-keys))
    (when (not-empty qos-0-keys)
      (qos-0 qos-0-keys topic msg))
    (when (not-empty qos-1-keys)
      (qos-1-send qos-1-keys topic msg))
    (when (not-empty qos-2-keys)
      (doseq [key (mapv #(:client-key %) qos-2-keys)]
        (send-buffer [key]
            (MqttPublish/encode {:packet-type :PUBLISH
                                 :payload (:payload msg)
                                 :topic topic
                                 :qos 2
                                 :retain? false
                                 :packet-identifier (get-packet-identifier)}))))))

(defn pubrel [msg]
  (logger "received (PUBREL: " msg)
  (let [packet-identifier (:packet-identifier msg)
        client-key (:client-key msg)]
    (send-buffer [client-key]
      (MqttPubComp/encode {:packet-type :PUBCOMP
                           :packet-identifier packet-identifier}))
    (let [m (get @*inflight* [client-key packet-identifier])]
      (qos-2-send (:keys m) (:topic m) (:msg m))
      (swap! *inflight* dissoc [client-key packet-identifier]))))

(defn pubcomp  [msg]
  (logger "received PUBCOMP: " msg)
  (put-packet-identifier (:packet-identifier msg)))

(defn add-subscriber [subscribers topic key]
  (if (contains? subscribers topic)
    (update-in subscribers [topic] conj key)
    (assoc subscribers topic [key])))

(defn subscribe [msg]
  (logger "clj SUBSCRIBE:" msg)
  (let [client-key (:client-key msg)
        topics (:topics msg)
        qos (mapv #(long (:qos %)) topics)]
        ;_ (logger qos)]
    (swap! *clients* update-in [client-key :subscribed-topics] conj topics)
    (doseq [t topics]
      ;(swap! subscribers add-subscriber (:topic-filter t) client-key)
      (swap! *subscriber-trie* tr/insert (:topic-filter t) {:client-key client-key :qos (:qos t)}))
    ;(logger "subscribers: " @sub2)
    (send-buffer [client-key] (MqttSubAck/encode
                               {:packet-type :SUBACK
                                :packet-identifier (:packet-identifier msg)
                                :response qos}))))

(defn remove-subsciber [m [topic] key]
  (update m topic (fn [v] (filterv #(not= key %) v))))

(defn unsubscribe [{:keys [topics client-key] :as msg}]
  (logger "clj UNSUBSCRIBE: " msg)
  ;(swap! subscribers remove-subsciber (:topics msg) (:client-key msg))
  (swap! *clients* update-in [client-key :subscribed-topics] disj topics)
  (doseq [topic topics]
    (swap! *subscriber-trie* tr/delete (:topic-filter topic) (:client-key topic))))


(defn pingreq [msg]
  (logger "clj PINGREQ: " msg)
  (send-buffer [(:client-key msg)] (MqttPingResp/encode {:packet-type :PINGRESP})))

(defn pingresp [msg]
  (logger "clj PINGRESP: " msg))


(defn remove-client-subscriber [m val]
  (into {} (map (fn [[k v]] (let [nv (filterv #(not= val %) v)] {k nv}))  m)))



(defn authenticate [msg]
  (println "AUTHENTICATE: " msg))
