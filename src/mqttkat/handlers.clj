(ns mqttkat.handlers
  (:require [mqttkat.s :refer [*server*]]
            [overtone.at-at :as at]
            [clojurewerkz.triennium.mqtt :as tr]
            [clojure.core.async :as async])
  (:import [org.mqttkat.server MqttServer]
           [org.mqttkat.packages MqttPublish
            MqttPubRel MqttPubAck MqttPubRec
            MqttPubComp MqttSubAck MqttPingResp]))
(def o Object)

(defn logger [msg & args]
  (when true
    (locking o
      (println msg args))))

(def packet-identifier-queue-size 1024)
(def ^:dynamic *clients* (atom {}))
(def ^:dynamic *inflight* (atom {}))
(def ^:dynamic *subscriber-trie* (atom (tr/make-trie)))
(def ^:dynamic *outbound* (atom {}))
(def ^:dynamic *retained* (atom {}))
(def packet-identifiers (async/chan packet-identifier-queue-size))

(def my-pool (at/mk-pool))
(declare qos-0)
(declare qos-1-send)
(declare qos-2-send)

(defn publish-will [{:keys [topic qos retain] :as msg}]
  (logger "Sending will messag on topic: " topic)
  (when-let [keys (tr/matching-vals @*subscriber-trie* topic)]
    (do
      (case (long qos)
        0 (qos-0 keys topic msg retain)
        1 (qos-1-send  keys topic msg)
        2 (qos-2-send keys topic msg)))))

(defn handle-will-if-present [key]
  (when (contains? (get @*clients* key) :will)
    (let [will-topic (get-in @*clients* [key :will :will-topic])
          will-qos   (get-in @*clients* [key :will :will-qos])
          will-message   (get-in @*clients* [key :will :will-message])
          will-retain (get-in @*clients* [key :will :will-retain])]
      (publish-will {:topic will-topic :qos will-qos :payload will-message :retain will-retain}))))

(defn check-timer [key time-out]
  (let [current-time (System/currentTimeMillis)
        last-active  @(:last-active (get @*clients* key))]
    ;(println "timer fired: " time-out (- current-time last-active))
    (when (some-> (* 0.9 last-active) (<= (- current-time time-out)))
      (logger "Timer fired for client: " key)
      (handle-will-if-present key)
      ;; TODO 
      ;; Remove Timer!!!
      ;; once we have sent the will message remove the will from the client,
      ;; so that it won't get send again.
      #_(swap! *clients* assoc-in [key] dissoc :will)
      (logger "about to close")
      (.closeConnection ^MqttServer @*server* key)
      (logger "closed...."))))

(defn add-timer!
  [key time]
  (logger "adding client to timer" time)
  (let [time-out (* 1500 time)
        timer    (at/every time-out #(check-timer key time-out) my-pool :initial-delay time-out)]
    (swap! *clients* assoc-in [key :last-active] (volatile! (System/currentTimeMillis)))
    (swap! *clients* assoc-in [key :timer] timer))
  (logger @*clients*))

(defn remove-timer! [key]
  (when-let [timer (get-in @*clients* [key :timer])]
    (at/kill timer)
    (swap! *clients* assoc-in [key :timer] nil)))

(defn add-client! [{:keys [client-key] :as msg}]
  (swap! *clients* assoc client-key (dissoc msg :packet-type)))

(defn remove-client! [key]
  (swap! *clients* dissoc key))

;; pre-load queue
(doseq [i (range 1 (inc packet-identifier-queue-size))]
  (async/>!! packet-identifiers i))

(defn get-packet-identifier []
  (async/<!! packet-identifiers))

(defn put-packet-identifier [p]
  ;;(logger "put " p)
  (async/>!! packet-identifiers p))

#_(defn send-message [keys msg]
    (logger "sending message  from  clj " (:packet-type msg) " " (:packet-identifier msg))
    ;;(logger (class  keys))
    (let [s (:server (meta @*server*))]))
;  (.sendMessage ^MqttServer s keys msg)))

(defn update-timestamps [client-keys]
  (doseq [client-key client-keys]
    (when (contains? (get-in @*clients* [client-key]) :last-active)
      ;;(swap! *clients* assoc-in [client-key :last-active] (System/currentTimeMillis))))
      (vreset! (get-in @*clients* [client-key :last-active]) (System/currentTimeMillis)))))

(defn send-buffer [keys buf]
  ;;(logger "sending buffer from clj")
  ;;(logger (class  keys))
  (update-timestamps keys)
  (let [{s :server} (meta @*server*)]
    (.sendMessageBuffer ^MqttServer s keys buf)))

(defn qos-0 [keys topic {:keys [payload]} retain]
  (logger "respond QOS 0 " topic)
  (send-buffer (mapv :client-key keys)
               (MqttPublish/encode {:packet-type :PUBLISH
                                    :payload     payload
                                    :topic       topic
                                    :qos         0
                                    :retain?     retain})))

(defn qos-1-send [keys topic {:keys [payload]}]
  (logger "respond qos 1")
  (doseq [key (mapv :client-key keys)]
    (send-buffer [key]
                 (MqttPublish/encode {:packet-type       :PUBLISH
                                      :payload           payload
                                      :topic             topic
                                      :qos               1
                                      :retain?           false
                                      :packet-identifier (get-packet-identifier)}))))

(defn qos-n? [num {:keys [qos] :as m}]
  (when (= num qos) m))

(defn qos-0? [m]
  (qos-n? 0 m))

(defn qos-1? [m]
  (qos-n? 1 m))

(defn qos-2? [m]
  (qos-n? 2 m))

(defn qos-1-or-2? [m]
  ((some-fn qos-1? qos-2?) m))

(defn qos-1 [keys topic {:keys [client-key packet-identifier] :as msg}]
  ;;(logger  "qos 1 received..." keys)
  (send-buffer [client-key]
               (MqttPubAck/encode {:packet-type       :PUBACK
                                   :packet-identifier packet-identifier}))
  (some-> (filter qos-0? keys)
          (seq)
          (qos-0 topic msg))
  (some-> (filter qos-1-or-2? keys)
          (seq)
          (qos-1-send topic msg)))

;  (doseq [k qos-1-keys]
;    (logger "K " k)
;    (swap! outbound assoc (:client-key k) (:packet-identifier msg))))))

(defn qos-2 [keys topic {:keys [client-key packet-identifier] :as recv-msg}]
  ;;(logger "QOS 2")
  (swap! *inflight* assoc [client-key packet-identifier] {:msg recv-msg :topic topic :keys keys})
  (send-buffer [client-key]
               (MqttPubRec/encode {:packet-type       :PUBREC
                                   :packet-identifier packet-identifier})))

(defn publish [{:keys [topic qos retain? payload] :as msg}]
  (logger "clj PUBLISH: " msg)
  ;(logger (str "valid publish: " (s/valid? :mqtt/publish msg)))
  ;(s/explain :mqtt/publish msg)
  (when retain?
    (logger "publish with retain: " topic qos (empty? payload))
    (if (empty? payload)
      (swap! *retained* dissoc topic)
      (swap! *retained* assoc topic {:qos qos :payload payload})))
  (when-let [keys (tr/matching-vals @*subscriber-trie* topic)]
    (do
      (case (long qos)
        0 (qos-0 keys topic msg false)
        1 (qos-1 keys topic msg)
        2 (qos-2 keys topic msg)))))

(defn puback [{:keys [packet-identifier]}]
  (logger "received PUBACK: " packet-identifier)
  (put-packet-identifier packet-identifier))
;(swap! outbound dissoc [(:client-key msg) (:packet-identifier msg)]))

(defn pubrec [{:keys [client-key packet-identifier] :as msg}]
  (logger "received PUBREC: " msg)
  (send-buffer [client-key]
               (MqttPubRel/encode
                {:packet-type :PUBREL :packet-identifier packet-identifier})))

(defn qos-2-send [keys topic {:keys [payload] :as msg}]
  (some-> (filter qos-0? keys)
          (seq)
          (qos-0 topic msg false))
  (some-> (filter qos-1? keys)
          (seq)
          (qos-1-send topic msg))
  (doseq [key (some->> (filter qos-2? keys)
                       (seq)
                       (mapv :client-key))]
    (send-buffer [key] (MqttPublish/encode {:packet-type       :PUBLISH
                                            :payload           payload
                                            :topic             topic
                                            :qos               2
                                            :retain?           false
                                            :packet-identifier (get-packet-identifier)}))))

;;there is no need to do
(defn pubrel
  [{:keys [packet-identifier client-key] :as received-msg}]
  (logger "received (PUBREL: " received-msg)
  (send-buffer [client-key]
               (MqttPubComp/encode {:packet-type       :PUBCOMP
                                    :packet-identifier packet-identifier}))
  (let [{:keys [keys topic msg]} (get @*inflight* [client-key packet-identifier])]
    (qos-2-send keys topic msg)
    (swap! *inflight* dissoc [client-key packet-identifier])))

(defn pubcomp [{:keys [packet-identifier] :as msg}]
  (logger "received PUBCOMP: " msg)
  (put-packet-identifier packet-identifier))

(defn add-subscriber [subscribers topic key]
  (if (contains? subscribers topic)
    (update-in subscribers [topic] conj key)
    (assoc subscribers topic [key])))

(defn process-retained-messages [key]
  (logger "process retained!")
  (when-let [topics (keys @*retained*)]
    (logger "topics that have been retained: " topics)
    (doseq [topic topics]
      (when-let [keys (tr/matching-vals @*subscriber-trie* topic)]
        (let [payload (get-in @*retained* [topic :payload])]
          (case (long (get-in @*retained* [topic :qos]))
            0 (qos-0 keys topic payload true)
            1 (qos-1-send keys topic payload )
            2 (qos-2-send keys topic payload )))))))

(defn subscribe [{:keys [client-key topics packet-identifier] :as msg}]
  (logger "clj SUBSCRIBE:" msg)
  (logger "Subscribe retain : " @*retained*)
  (do
    (swap! *clients* update-in [client-key :subscribed-topics] conj topics)
    (doseq [{:keys [topic-filter qos]} topics]
      ;(swap! subscribers add-subscriber (:topic-filter t) client-key)
      (swap! *subscriber-trie* tr/insert topic-filter {:client-key client-key :qos qos}))
    ;(logger "subscribers: " @sub2)
    (send-buffer [client-key]
                 (MqttSubAck/encode
                  {:packet-type       :SUBACK
                   :packet-identifier packet-identifier
                   :response          (mapv #(long (:qos %)) topics)}))
    (process-retained-messages client-key)))

(defn unsubscribe
  [{:keys [topics client-key] :as msg}]
  (logger "clj UNSUBSCRIBE: " msg)
  ;(swap! subscribers remove-subsciber (:topics msg) (:client-key msg))
  (swap! *clients* update-in [client-key :subscribed-topics] disj topics)
  (doseq [{:keys [topic-filter client-key]} topics]
    (swap! *subscriber-trie* tr/delete topic-filter client-key)))

(defn pingreq [{:keys [client-key] :as msg}]
  (logger "clj PINGREQ: " msg)
  (send-buffer [client-key] (MqttPingResp/encode {:packet-type :PINGRESP})))

(defn pingresp [msg]
  (logger "clj PINGRESP: " msg))

(comment
  (defn remove-subsciber [m [topic] key]
    (update m topic (fn [v] (filterv #(not= key %) v))))

  (defn remove-client-subscriber [m val]
    (into {} (map (fn [[k v]] (let [nv (filterv #(not= val %) v)] {k nv})) m))))

(defn authenticate [msg]
  (logger "AUTHENTICATE: " msg))
