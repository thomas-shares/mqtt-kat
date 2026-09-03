(ns mqttkat.sys
  "The $SYS hierarchy: what the broker knows about itself, published as MQTT.

   Modelled on Mosquitto's, so that anything already pointed at a Mosquitto
   broker — a dashboard, `mosquitto_sub -t '$SYS/#'` — reads the same topics
   here. Values are published retained, which is what makes both halves of
   Mosquitto's behaviour fall out for free: a subscriber gets the current value
   the moment it subscribes, and the updates after that. Mosquitto calls some
   topics static and sends them once; retained gives that without a separate
   mechanism.

   Note that §4.7.2 keeps these away from clients that did not ask for them by
   name: a subscription to `#` does not match a topic starting with $, so only
   `$SYS/#` and friends see any of this.

   Not implemented, and why:

     - `$SYS/broker/load/...`, the 1/5/15-minute moving averages. They need
       their own sampling and an average per metric rather than a counter read,
       which is a different piece of work from the rest of this.
     - `$SYS/broker/connection/#`, which is about bridges. There are none.
     - `shared_subscriptions/count`: shared subscriptions are MQTT 5.
     - `publish/bytes/received` and `/sent`: receivedBytes and writtenBytes
       count whole packets, not payloads, so there is no honest number to
       publish. Better absent than wrong."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [mqttkat.handlers :as h]
            [mqttkat.util :as util])
  (:import [java.util.concurrent.atomic LongAdder]
           [org.mqttkat MqttStat]))

(def broker-version
  "Reported at $SYS/broker/version. Tracks project.clj by hand."
  "mqtt-kat 0.0.1")

(def sys-interval
  "Seconds between updates. 0 disables them, as Mosquitto's sys_interval does.
   -Dmqttkat.sysInterval=N."
  (if-let [p (System/getProperty "mqttkat.sysInterval")]
    (Long/parseLong p)
    10))

(def ^:private prefix "$SYS/broker/")

(defonce ^:private high-water
  ;; Sampled at each update rather than tracked on every connect: the counting
  ;; would have to happen on the connect path, and a peak between two samples
  ;; is not worth putting work there for.
  (atom {:clients 0 :heap 0}))

(defn- sum ^long [^LongAdder a] (.sum a))

(def ^:private mqtt-packet-topics
  "Per-packet-type counts, following Mosquitto in publishing one direction for
   packets that only travel one way and both for the acknowledgements that go
   each way. Emitting all thirty combinations would mean half of them were
   structurally zero — a broker never sends a CONNECT."
  [["mqtt/connect/received"     :received  1]
   ["mqtt/connack/sent"         :sent      2]
   ["mqtt/publish/received"     :received  3]
   ["mqtt/publish/sent"         :sent      3]
   ["mqtt/puback/received"      :received  4]
   ["mqtt/puback/sent"          :sent      4]
   ["mqtt/pubrec/received"      :received  5]
   ["mqtt/pubrec/sent"          :sent      5]
   ["mqtt/pubrel/received"      :received  6]
   ["mqtt/pubrel/sent"          :sent      6]
   ["mqtt/pubcomp/received"     :received  7]
   ["mqtt/pubcomp/sent"         :sent      7]
   ["mqtt/subscribe/received"   :received  8]
   ["mqtt/suback/sent"          :sent      9]
   ["mqtt/unsubscribe/received" :received 10]
   ["mqtt/unsuback/sent"        :sent     11]
   ["mqtt/pingreq/received"     :received 12]
   ["mqtt/pingresp/sent"        :sent     13]
   ["mqtt/disconnect/received"  :received 14]
   ["mqtt/disconnect/sent"      :sent     14]
   ["mqtt/auth/received"        :received 15]
   ["mqtt/auth/sent"            :sent     15]])

(defn- sys-topic? [^String topic]
  (str/starts-with? topic prefix))

(defn- retained-count
  "Retained messages, not counting our own. Publishing forty of these every
   interval and then reporting them as retained data would say more about this
   namespace than about the broker."
  []
  (count (remove (comp sys-topic? key) @h/*retained*)))

(defn- subscription-count
  "Subscriptions held, across connected clients and parked sessions alike."
  []
  (reduce + (map #(count (:subscribed-topics %)) (vals @h/*clients*))))

(defn- queued-count
  "Messages held for clients: in flight awaiting acknowledgement, plus those
   waiting for a window slot."
  []
  (reduce + (map (fn [[_ state]]
                   (+ (count (:inflight state)) (count (:pending state))))
                 @h/*outbound*)))

(defn stats
  "Every $SYS topic and its value right now, as strings.

   Pure, so it can be read in a test or at a REPL without publishing anything."
  []
  (let [{:keys [connected parked-sessions]} (util/client-counts)
        rt      (Runtime/getRuntime)
        heap    (- (.totalMemory rt) (.freeMemory rt))
        peaks   (swap! high-water #(-> %
                                       (update :clients max connected)
                                       (update :heap max heap)))
        queued  (max 0 (- (sum MqttStat/sentMessages)
                          (sum MqttStat/writtenMessages)
                          (sum MqttStat/discardedMessages)))
        retained (retained-count)]
    (into
     (sorted-map)
     (concat
      [[(str prefix "version") broker-version]
       [(str prefix "clients/connected") connected]
       ;; Mosquitto's "disconnected" is persistent sessions registered but not
       ;; currently connected, which is exactly a parked session here.
       [(str prefix "clients/disconnected") parked-sessions]
       [(str prefix "clients/total") (+ connected parked-sessions)]
       [(str prefix "clients/maximum") (:clients peaks)]
       ;; Always 0: nothing expires a persistent session yet, so there is no
       ;; persistent_client_expiration to have removed anything.
       [(str prefix "clients/expired") 0]
       [(str prefix "connections/socket/count") (sum MqttStat/socketConnections)]
       [(str prefix "bytes/received") (sum MqttStat/receivedBytes)]
       [(str prefix "bytes/sent") (sum MqttStat/writtenBytes)]
       [(str prefix "messages/received") (sum MqttStat/receivedMessages)]
       [(str prefix "messages/sent") (sum MqttStat/writtenMessages)]
       ;; Refused before being queued, plus queued and then abandoned when the
       ;; connection went. Mosquitto's single "dropped" covers both.
       [(str prefix "publish/messages/dropped") (+ (sum MqttStat/droppedMessages)
                                                   (sum MqttStat/discardedMessages))]
       ;; Promised and not yet delivered — the same number the stats line calls
       ;; backlog, and the one that says the broker is behind.
       [(str prefix "packet/out/count") queued]
       [(str prefix "packet/out/bytes") (max 0 (- (sum MqttStat/sentBytes)
                                                  (sum MqttStat/writtenBytes)))]
       [(str prefix "retained messages/count") retained]
       [(str prefix "subscriptions/count") (subscription-count)]
       [(str prefix "store/messages/count") (+ retained (queued-count))]
       [(str prefix "heap/current") heap]
       [(str prefix "heap/maximum") (:heap peaks)]]
      (for [[topic direction type] mqtt-packet-topics]
        [(str prefix topic)
         (sum (aget ^"[Ljava.util.concurrent.atomic.LongAdder;"
                    (if (= :received direction)
                      MqttStat/receivedByType
                      MqttStat/sentByType)
                    ^int (int type)))])))))

(defn publish-once!
  "Publish the current values. Retained and at QoS 0, like Mosquitto's."
  []
  (doseq [[topic value] (stats)]
    (h/publish {:packet-type :PUBLISH
                :topic       topic
                :qos         0
                :retain?     true
                :payload     (str value)
                :client-key  nil})))

(defonce ^:private running (atom false))

(defn start!
  "Begin publishing every `seconds`, `sys-interval` by default. A no-op when
   that is 0, and idempotent.

   The interval is an argument as well as a property so this loop can be
   tested at all: reading it only from a system property meant a test of
   start! had to wait ten seconds, so there wasn't one — and the first time
   $SYS did not turn up over a socket I went looking at the publisher, which
   was fine, rather than at my probe, which was calling the wrong callback."
  ([] (start! sys-interval))
  ([seconds]
   (when (and (pos? (long seconds)) (compare-and-set! running false true))
     (log/info "publishing $SYS every" seconds "seconds")
     (.start
      (Thread/ofVirtual)
      ^Runnable (fn []
                  (while @running
                    (try
                      (publish-once!)
                      (catch Throwable t
                        ;; Reporting on the broker must never stop it.
                        (log/error t "publishing $SYS failed")))
                    (Thread/sleep (long (* 1000 (long seconds)))))))
     true)))

(defn stop! []
  (reset! running false))
