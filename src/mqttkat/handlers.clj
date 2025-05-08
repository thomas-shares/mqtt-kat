(ns mqttkat.handlers
  (:require [mqttkat.s :refer [*server*]]
            [overtone.at-at :as at]
            [clojurewerkz.triennium.mqtt :as tr]
            [clojure.core.async :as async])
  (:import [org.mqttkat.server MqttServer]
           [org.mqttkat.packages MqttPublish
            MqttPubRel MqttPubAck MqttPubRec
            MqttPubComp MqttSubAck MqttPingResp MqttUnSubAck]))

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
(def ^:dynamic *retained* (atom {}))  ;; {:topic {:qos qos :payload payload}})
(def packet-identifiers (async/chan packet-identifier-queue-size))

(def my-pool (at/mk-pool))
(declare qos-0)
(declare qos-1-send)
(declare qos-2-send)

(defn publish-will [{:keys [topic qos retain payload]}]
  ;;(logger "Sending will message on topic: " payload)
  (when-let [keys (tr/matching-vals @*subscriber-trie* topic)]
    ;;(logger "Will keys: " keys)
    (case (long qos)
      0 (qos-0 keys topic {:payload payload} retain)
      1 (qos-1-send  keys topic {:payload payload})
      2 (qos-2-send keys topic {:payload payload}))))

(defn handle-will-if-present [key]
  (when (contains? (get @*clients* key) :will)
    (let [will-topic (get-in @*clients* [key :will :will-topic])
          will-qos   (get-in @*clients* [key :will :will-qos])
          will-message   (get-in @*clients* [key :will :will-message])
          will-retain (get-in @*clients* [key :will :will-retain])]
      (publish-will {:topic will-topic :qos will-qos :payload will-message :retain will-retain}))))

(defn check-timer [key time-out]
  (when (contains? @*clients* key)
    (let [current-time (System/currentTimeMillis)
          last-active  @(:last-active (get @*clients* key))]
      (logger "timer fired: " time-out (- current-time last-active))
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
        (logger "closed....")))))

(defn add-timer!
  [key time]
  #_(logger "adding client to timer" time " and key:   "key)
  (let [time-out (* 1500 time)
        timer    (at/every time-out #(check-timer key time-out) my-pool :initial-delay time-out)]
    (swap! *clients* assoc-in [key :last-active] (volatile! (System/currentTimeMillis)))
    (swap! *clients* assoc-in [key :timer] timer))
  #_(logger @*clients*))

(defn remove-timer! [key]
  (when-let [timer (get-in @*clients* [key :timer])]
    (at/kill timer)
    (swap! *clients* assoc-in [key :timer] nil)))

(defn add-client! [{:keys [client-key client-id clean-session?] :as msg}]
  (if (and (false? clean-session?)
           (contains? @*clients* client-id))
    (let [client (get @*clients* client-id)]
      #_(logger "client-id already exists: " client-id)
      (let [subscriptions (get-in client [:subscribed-topics])]
        #_(logger "subscriptions: " subscriptions)
        (doseq [topic subscriptions]
          #_(logger "Adding to sub-trie for topic: " (:topic-filter topic)  "   qos: " (:qos topic))
          (swap! *subscriber-trie* tr/insert (:topic-filter topic) {:client-key client-key :qos (:qos topic)})))
      #_(logger "client-id: " client-id)
      (swap! *clients* assoc client-key client)
      (swap! *clients* dissoc client-id))
    (let [client (dissoc msg :packet-type :client-key)
          client-added (update-in client [:subscribed-topics] (fnil conj #{}) )]
      (swap! *clients* assoc client-key client-added)))
 #_(logger "ADD: Subscriber trie POST: " @*subscriber-trie*)
 #_(logger "ADD: Clients: " @*clients*))


(defn remove-client! [key]
  (remove-timer! key)
  ;;(logger "REMOVE: clean session? " (get-in @*clients* [key :clean-session?] true))
  ;;(logger "key: " key)
  (if (get-in @*clients* [key :clean-session?] true)
    (swap! *clients* dissoc key)
    (let [client (get @*clients* key)
          client-id (:client-id client)
          subscribed-topics (:subscribed-topics client)]
      ;;(logger "Removing subscribed topics: : " subscribed-topics)
       (doseq [topic subscribed-topics]
         ;;(logger "Removing from sub-trie for topic: "  (:topic-filter topic)  "   qos: " (:qos topic))
         (swap! *subscriber-trie* tr/delete (:topic-filter  topic) {:client-key key :qos (:qos topic)})
       (swap! *clients* dissoc key)
       (swap! *clients* assoc client-id client)))
  #_(logger "REMOVE: Subscriber trie POST: " @*subscriber-trie*)
  #_(logger "REMOVE: Clients: " @*clients*)))

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
  ;;(logger  keys)
  (update-timestamps keys)
  (let [{s :server} (meta @*server*)]
    (.sendMessageBuffer ^MqttServer s keys buf)))

(defn qos-0 [keys topic {:keys [payload]} retain]
  #_(logger "--> respond QOS 0 topic: " topic " retained: " retain  " payload: " payload  " count keys: " (count keys))
  (send-buffer (mapv :client-key keys)
               (MqttPublish/encode {:packet-type :PUBLISH
                                    :payload     payload
                                    :topic       topic
                                    :qos         0
                                    :retain?     retain})))

(defn qos-1-send [keys topic {:keys [payload]}]
  #_(logger "respond qos 1: " (count keys) )
  (doseq [key (mapv :client-key keys)]
    (let [packet-identifier (get-packet-identifier)
          client-id (:client-id (get @*clients* key))]
      (swap! *outbound* update client-id assoc packet-identifier {:topic topic :payload payload :qos 1})
      #_(logger "qos 1 send: " @*outbound*)
      #_(logger "qos 1 send key: " key)
      #_(logger "qos 1 send packet-identifier: " packet-identifier)
      (send-buffer [key]
                   (MqttPublish/encode {:packet-type       :PUBLISH
                                        :payload           payload
                                        :topic             topic
                                        :qos               1
                                        :retain?           false
                                        :duplicate?        false
                                        :packet-identifier packet-identifier})))))

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
  #_(logger  "qos 1 received... " (count keys))
  (send-buffer [client-key]
               (MqttPubAck/encode {:packet-type       :PUBACK
                                   :packet-identifier packet-identifier}))
  (some-> (filter qos-0? keys)
          (seq)
          (qos-0 topic msg false))
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
  (logger "PUBLISH: ")
  #_(logger "Matched Keys: " (tr/matching-vals @*subscriber-trie* topic))
  ;(logger (str "valid publish: " (s/valid? :mqtt/publish msg)))
  ;(s/explain :mqtt/publish msg)
  (when retain?
    #_(logger "publish with retain: " topic qos (empty? payload))
    (if (empty? payload)
      (swap! *retained* dissoc topic)
      (swap! *retained* assoc topic {:qos qos :payload payload})))
  (when-let [keys (tr/matching-vals @*subscriber-trie* topic)]
    (case (long qos)
      0 (qos-0 keys topic msg false)
      1 (qos-1 keys topic msg)
      2 (qos-2 keys topic msg))))

(defn puback [{:keys [packet-identifier client-key]}]
  (logger "PUBACK: " packet-identifier)
  (put-packet-identifier packet-identifier)
  (let [client-id (:client-id (get @*clients* client-key ))]
    ;;(logger "client-id: " client-id)
    (swap! *outbound* update client-id dissoc packet-identifier)
    #_(logger "outbound: " @*outbound*)))

(defn pubrec [{:keys [client-key packet-identifier]}]
  (logger "PUBREC: " packet-identifier)
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
  [{:keys [packet-identifier client-key]}]
  (logger "received (PUBREL: " packet-identifier)
  (send-buffer [client-key]
               (MqttPubComp/encode {:packet-type       :PUBCOMP
                                    :packet-identifier packet-identifier}))
  (let [{:keys [keys topic msg]} (get @*inflight* [client-key packet-identifier])]
    (qos-2-send keys topic msg)
    (swap! *inflight* dissoc [client-key packet-identifier])))

(defn pubcomp [{:keys [packet-identifier] :as msg}]
  (logger "received PUBCOMP: " (dissoc msg :client-key))
  (put-packet-identifier packet-identifier))

(defn add-subscriber [subscribers topic key]
  (if (contains? subscribers topic)
    (update-in subscribers [topic] conj key)
    (assoc subscribers topic [key])))

(defn process-retained-messages [key]
  ;;(logger "key: " key)
  ;;(logger "subscribers: " @*subscriber-trie*)
  ;;(logger "retained topics: "  (keys @*retained*))
  (doseq [retained-topic (keys @*retained*)]
    ;;(logger "Total subscribed: "   @*subscriber-trie*)
    ;;(logger "subscribed  -->: " (tr/matching-vals @*subscriber-trie* retained-topic))
    (let [keys (set (mapv #(:client-key %) (tr/matching-vals @*subscriber-trie* retained-topic)))]
      ;;(logger "Yes there are keys subscribed to this topic: " (> (count keys) 0))
      ;;(logger "key is contained: " (contains? keys key))
      ;;(logger "retained topic: " (get-in @*retained* [retained-topic]))
      (when (contains? keys key)
        (when-let [payload (get-in @*retained* [retained-topic :payload])]
          ;;(logger "retained payload: " payload)
          (case (long (get-in @*retained* [retained-topic :qos]))
            0 (qos-0 [{:client-key key}] retained-topic {:payload payload} true)
            1 (qos-1-send [{:client-key key}] retained-topic {:payload payload})
            2 (qos-2-send [{:client-key key}] retained-topic {:payload payload})))))))

(defn subscribe [{:keys [client-key topics packet-identifier] :as msg}]
  (logger "SUBSCRIBE:" (dissoc msg :client-key))
  ;;(logger "Subscribed PRE ADD: " @*subscriber-trie*)
  (doseq [{:keys [topic-filter qos]} topics]
    ;(swap! subscribers add-subscriber (:topic-filter t) client-key)
    (swap! *clients* update-in [client-key :subscribed-topics] conj {:topic-filter topic-filter :qos qos})
    (swap! *subscriber-trie* tr/insert topic-filter {:client-key client-key :qos qos}))
  ;;(logger "subscribers POST ADD: " @*subscriber-trie*)
  (send-buffer [client-key]
               (MqttSubAck/encode
                {:packet-type       :SUBACK
                 :packet-identifier packet-identifier
                 :response          (mapv #(long (:qos %)) topics)}))
  (process-retained-messages client-key))

(defn unsubscribe
  [{:keys [topics client-key] :as msg}]
  (logger "UNSUBSCRIBE: " (dissoc msg :client-key))
  ;(swap! subscribers remove-subsciber (:topics msg) (:client-key msg))
  ;;TODO remove message from outbound messages.. but check if this is really the case.
  (doseq [topic topics]
    (let [qos (:qos (first (filter #(= topic (:topic-filter %))  (get-in @*clients* [client-key :subscribed-topics]))))]
      ;;(logger "Unsubscribing from topic: " topic qos)
      (swap! *clients* update-in [client-key :subscribed-topics] disj {:topic-filter topic :qos qos})
      ;;(logger "Unsubscribing from trie : " topic " client-key: " client-key)
      (swap! *subscriber-trie* tr/delete topic {:client-key client-key :qos qos})))
  (send-buffer [client-key]
               (MqttUnSubAck/encode
                {:packet-type       :UNSUBACK
                 :packet-identifier (:packet-identifier msg)}))
  #_(logger "Unsubscribed trie: " @*subscriber-trie*)
  #_(logger "Unsubscribed clients: " (get-in @*clients* [client-key])))

(defn pingreq [{:keys [client-key] :as msg}]
  (logger "PINGREQ: " (dissoc msg :client-key))
  (send-buffer [client-key] (MqttPingResp/encode {:packet-type :PINGRESP})))

(defn pingresp [msg]
  (logger "PINGRESP: " (dissoc msg :client-key)))

(comment
  (defn remove-subsciber [m [topic] key]
    (update m topic (fn [v] (filterv #(not= key %) v))))

  (defn remove-client-subscriber [m val]
    (into {} (map (fn [[k v]] (let [nv (filterv #(not= val %) v)] {k nv})) m))))

(defn authenticate [msg]
  (logger "AUTHENTICATE: " msg))
