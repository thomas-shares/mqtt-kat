(ns mqttkat.handlers
  (:require [clojure.tools.logging :as log]
            [mqttkat.s :refer [*server*]]
            [overtone.at-at :as at]
            [clojurewerkz.triennium.mqtt :as tr]
            [clojure.core.async :as async])
  (:import [org.mqttkat.server MqttServer]
           [org.mqttkat.packages MqttPublish
            MqttPubRel MqttPubAck MqttPubRec
            MqttPubComp MqttSubAck MqttPingResp MqttUnSubAck]))

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
(declare remove-client!)

(defn publish-will [{:keys [topic qos retain payload]}]
  (log/trace "Sending will message on topic:" payload)
  (when-let [keys (tr/matching-vals @*subscriber-trie* topic)]
    (log/trace "Will keys:" keys)
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

(defn check-timer
  "Drop `key` if nothing has been received from it for `time-out` ms.

   MQTT 3.1.1 §3.1.2.10: the server disconnects a client it has not heard from
   for one and a half times the Keep Alive interval, which is what add-timer!
   passes as `time-out`."
  [key time-out]
  (when-let [last-active (get-in @*clients* [key :last-active])]
    (let [idle (- (System/currentTimeMillis) @last-active)]
      (log/debug "timer fired:" time-out idle)
      (when (<= time-out idle)
        (log/debug "Timer fired for client:" key)
        (handle-will-if-present key)
        ;; TODO 
        ;; Remove Timer!!!
        ;; once we have sent the will message remove the will from the client,
        ;; so that it won't get send again.
        #_(swap! *clients* assoc-in [key] dissoc :will)
        (remove-client! key)
        (log/debug "about to close")
        ;; *server* holds the stop-server closure; the MqttServer itself lives
        ;; in its metadata, the same way send-buffer reaches it. The close is
        ;; guarded so a socket that has already gone cannot kill the timer.
        (try
          (.closeConnection ^MqttServer (:server (meta @*server*)) key)
          (catch Exception e
            (log/warn e "closing the connection of a timed-out client failed")))
        (log/debug "closed....")))))

(defn add-timer!
  [key time]
  (log/trace "adding client to timer" time " and key:   "key)
  (let [time-out (* 1500 time)]
    ;; Stamp liveness BEFORE scheduling. The job's initial delay starts running
    ;; the moment at/every is called, so a stamp taken afterwards leaves the
    ;; first tick measuring fractionally less than time-out of idleness — the
    ;; client then survives that cycle and is only reaped on the next one.
    (swap! *clients* assoc-in [key :last-active] (volatile! (System/currentTimeMillis)))
    (swap! *clients* assoc-in [key :timer]
           (at/every time-out #(check-timer key time-out) my-pool :initial-delay time-out)))
  (log/trace @*clients*))

(defn remove-timer! [key]
  (when-let [timer (get-in @*clients* [key :timer])]
    (at/kill timer)
    (swap! *clients* assoc-in [key :timer] nil)))

(defn add-client! [{:keys [client-key client-id clean-session?] :as msg}]
  (if (and (false? clean-session?)
           (contains? @*clients* client-id))
    (let [client (get @*clients* client-id)]
      (log/trace "client-id already exists:" client-id)
      (let [subscriptions (get-in client [:subscribed-topics])]
        (log/trace "subscriptions:" subscriptions)
        (doseq [topic subscriptions]
          (log/trace "Adding to sub-trie for topic:" (:topic-filter topic)  "   qos: " (:qos topic))
          (swap! *subscriber-trie* tr/insert (:topic-filter topic) {:client-key client-key :qos (:qos topic)})))
      (log/trace "client-id:" client-id)
      (swap! *clients* assoc client-key client)
      (swap! *clients* dissoc client-id))
    (let [client (dissoc msg :packet-type :client-key)
          client-added (update-in client [:subscribed-topics] (fnil conj #{}) )]
      (swap! *clients* assoc client-key client-added)))
 (log/trace "ADD: Subscriber trie POST:" @*subscriber-trie*)
 (log/trace "ADD: Clients:" @*clients*))


(defn remove-client! [key]
  (remove-timer! key)
  (log/trace "REMOVE: clean session?" (get-in @*clients* [key :clean-session?] true))
  (log/trace "key:" key)
  (if (get-in @*clients* [key :clean-session?] true)
    (swap! *clients* dissoc key)
    (let [client (get @*clients* key)
          client-id (:client-id client)
          subscribed-topics (:subscribed-topics client)]
      (log/trace "Removing subscribed topics: :" subscribed-topics)
      (doseq [topic subscribed-topics]
        (log/trace "Removing from sub-trie for topic:"  (:topic-filter topic)  "   qos: " (:qos topic))
        (swap! *subscriber-trie* tr/delete (:topic-filter  topic) {:client-key key :qos (:qos topic)}))
      ;; Parking the session under its client-id happens once, not once per
      ;; subscribed topic: these two were inside the doseq above, so a
      ;; persistent session with no subscriptions was dropped instead of kept,
      ;; and CONNACK then reported session-present? false on the reconnect.
      (swap! *clients* dissoc key)
      (swap! *clients* assoc client-id client)))
  (log/trace "REMOVE: Subscriber trie POST:" @*subscriber-trie*)
  (log/trace "REMOVE: Clients:" @*clients*))

;; pre-load queue
(doseq [i (range 1 (inc packet-identifier-queue-size))]
  (async/>!! packet-identifiers i))

(defn get-packet-identifier []
  (async/<!! packet-identifiers))

(defn put-packet-identifier [p]
  (log/trace "put" p)
  (async/>!! packet-identifiers p))

#_(defn send-message [keys msg]
    (log/debug "sending message  from  clj" (:packet-type msg) " " (:packet-identifier msg))
    (log/trace (class  keys))
    (let [s (:server (meta @*server*))]))
;  (.sendMessage ^MqttServer s keys msg)))

(defn update-timestamps
  "Mark these clients as alive.

   One read of *clients* per client, not two: this used to check that
   :last-active was present and then look it up again to write it, and the
   client could disconnect in between — the second lookup then returned nil and
   vreset! threw. A client going away while a packet of its own is still being
   handled is ordinary, so it is skipped rather than reported."
  [client-keys]
  (doseq [client-key client-keys]
    (when-let [last-active (get-in @*clients* [client-key :last-active])]
      (vreset! last-active (System/currentTimeMillis)))))

(defn send-buffer [keys buf]
  (log/trace "sending buffer from clj")
  (log/trace  keys)
  ;; No update-timestamps here on purpose: keep alive measures the time since
  ;; a packet was RECEIVED from the client, so writing to it proves nothing.
  ;; mqttkat.server/default-handler-fn marks liveness on the inbound path.
  (let [{s :server} (meta @*server*)]
    (.sendMessageBuffer ^MqttServer s keys buf)))

(defn qos-0 [keys topic {:keys [payload]} retain]
  (log/trace "--> respond QOS 0 topic:" topic " retained: " retain  " payload: " payload  " count keys: " (count keys))
  (send-buffer (mapv :client-key keys)
               (MqttPublish/encode {:packet-type :PUBLISH
                                    :payload     payload
                                    :topic       topic
                                    :qos         0
                                    :retain?     retain})))

(defn qos-1-send [keys topic {:keys [payload]}]
  (log/trace "respond qos 1:" (count keys) )
  (doseq [key (mapv :client-key keys)]
    (let [packet-identifier (get-packet-identifier)
          client-id (:client-id (get @*clients* key))]
      (swap! *outbound* update client-id assoc packet-identifier {:topic topic :payload payload :qos 1})
      (log/trace "qos 1 send:" @*outbound*)
      (log/trace "qos 1 send key:" key)
      (log/trace "qos 1 send packet-identifier:" packet-identifier)
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
  (log/trace  "qos 1 received... " (count keys))
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
;    (log/trace "K" k)
;    (swap! outbound assoc (:client-key k) (:packet-identifier msg))))))

(defn qos-2 [keys topic {:keys [client-key packet-identifier] :as recv-msg}]
  (log/trace "QOS 2")
  (swap! *inflight* assoc [client-key packet-identifier] {:msg recv-msg :topic topic :keys keys})
  (send-buffer [client-key]
               (MqttPubRec/encode {:packet-type       :PUBREC
                                   :packet-identifier packet-identifier})))

(defn publish [{:keys [topic qos retain? payload] :as msg}]
  (log/debug "PUBLISH:" (dissoc msg :client-key))
  (log/trace "Matched Keys:" (tr/matching-vals @*subscriber-trie* topic))
  ;(log/trace (str "valid publish: " (s/valid? :mqtt/publish msg)))
  ;(s/explain :mqtt/publish msg)
  (when retain?
    (log/trace "publish with retain:" topic qos (empty? payload))
    (if (empty? payload)
      (swap! *retained* dissoc topic)
      (swap! *retained* assoc topic {:qos qos :payload payload})))
  (when-let [keys (tr/matching-vals @*subscriber-trie* topic)]
    (case (long qos)
      0 (qos-0 keys topic msg false)
      1 (qos-1 keys topic msg)
      2 (qos-2 keys topic msg))))

(defn puback [{:keys [packet-identifier client-key]}]
  (log/debug "PUBACK:" packet-identifier)
  (put-packet-identifier packet-identifier)
  (let [client-id (:client-id (get @*clients* client-key ))]
    (log/trace "client-id:" client-id)
    (swap! *outbound* update client-id dissoc packet-identifier)
    (log/trace "outbound:" @*outbound*)))

(defn pubrec [{:keys [client-key packet-identifier]}]
  (log/debug "PUBREC:" packet-identifier)
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
  (log/debug "received (PUBREL:" packet-identifier)
  (send-buffer [client-key]
               (MqttPubComp/encode {:packet-type       :PUBCOMP
                                    :packet-identifier packet-identifier}))
  (let [{:keys [keys topic msg]} (get @*inflight* [client-key packet-identifier])]
    (qos-2-send keys topic msg)
    (swap! *inflight* dissoc [client-key packet-identifier])))

(defn pubcomp [{:keys [packet-identifier] :as msg}]
  (log/debug "received PUBCOMP:" (dissoc msg :client-key))
  (put-packet-identifier packet-identifier))

(defn add-subscriber [subscribers topic key]
  (if (contains? subscribers topic)
    (update-in subscribers [topic] conj key)
    (assoc subscribers topic [key])))

(defn process-retained-messages [key]
  (log/trace "key:" key)
  (log/trace "subscribers:" @*subscriber-trie*)
  (log/trace "retained topics:"  (keys @*retained*))
  (doseq [retained-topic (keys @*retained*)]
    (log/trace "Total subscribed:"   @*subscriber-trie*)
    (log/trace "subscribed  -->:" (tr/matching-vals @*subscriber-trie* retained-topic))
    (let [keys (set (mapv #(:client-key %) (tr/matching-vals @*subscriber-trie* retained-topic)))]
      (log/trace "Yes there are keys subscribed to this topic:" (> (count keys) 0))
      (log/trace "key is contained:" (contains? keys key))
      (log/trace "retained topic:" (get-in @*retained* [retained-topic]))
      (when (contains? keys key)
        (when-let [payload (get-in @*retained* [retained-topic :payload])]
          (log/trace "retained payload:" payload)
          (case (long (get-in @*retained* [retained-topic :qos]))
            0 (qos-0 [{:client-key key}] retained-topic {:payload payload} true)
            1 (qos-1-send [{:client-key key}] retained-topic {:payload payload})
            2 (qos-2-send [{:client-key key}] retained-topic {:payload payload})))))))

(defn subscribe [{:keys [client-key topics packet-identifier] :as msg}]
  (log/debug "SUBSCRIBE:" (dissoc msg :client-key))
  (log/trace "Subscribed PRE ADD:" @*subscriber-trie*)
  (doseq [{:keys [topic-filter qos]} topics]
    ;(swap! subscribers add-subscriber (:topic-filter t) client-key)
    (swap! *clients* update-in [client-key :subscribed-topics] conj {:topic-filter topic-filter :qos qos})
    (swap! *subscriber-trie* tr/insert topic-filter {:client-key client-key :qos qos}))
  (log/trace "subscribers POST ADD:" @*subscriber-trie*)
  (send-buffer [client-key]
               (MqttSubAck/encode
                {:packet-type       :SUBACK
                 :packet-identifier packet-identifier
                 :response          (mapv #(long (:qos %)) topics)}))
  (process-retained-messages client-key))

(defn unsubscribe
  [{:keys [topics client-key] :as msg}]
  (log/debug "UNSUBSCRIBE:" (dissoc msg :client-key))
  ;(swap! subscribers remove-subsciber (:topics msg) (:client-key msg))
  ;;TODO remove message from outbound messages.. but check if this is really the case.
  (doseq [topic topics]
    (let [qos (:qos (first (filter #(= topic (:topic-filter %))  (get-in @*clients* [client-key :subscribed-topics]))))]
      (log/trace "Unsubscribing from topic:" topic qos)
      (swap! *clients* update-in [client-key :subscribed-topics] disj {:topic-filter topic :qos qos})
      (log/trace "Unsubscribing from trie :" topic " client-key: " client-key)
      (swap! *subscriber-trie* tr/delete topic {:client-key client-key :qos qos})))
  (send-buffer [client-key]
               (MqttUnSubAck/encode
                {:packet-type       :UNSUBACK
                 :packet-identifier (:packet-identifier msg)}))
  (log/trace "Unsubscribed trie:" @*subscriber-trie*)
  (log/trace "Unsubscribed clients:" (get-in @*clients* [client-key])))

(defn pingreq [{:keys [client-key] :as msg}]
  (log/debug "PINGREQ:" (dissoc msg :client-key))
  (send-buffer [client-key] (MqttPingResp/encode {:packet-type :PINGRESP})))

(defn pingresp [msg]
  (log/debug "PINGRESP:" (dissoc msg :client-key)))

(comment
  (defn remove-subsciber [m [topic] key]
    (update m topic (fn [v] (filterv #(not= key %) v))))

  (defn remove-client-subscriber [m val]
    (into {} (map (fn [[k v]] (let [nv (filterv #(not= val %) v)] {k nv})) m))))

(defn authenticate [msg]
  (log/debug "AUTHENTICATE:" msg))
