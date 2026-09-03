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

(defn- sum-type ^long [^"[Ljava.util.concurrent.atomic.LongAdder;" counters ^long packet-type]
  (sum (aget counters (int packet-type))))

;; ── load averages ────────────────────────────────────────────────────────

(def load-windows
  "The averaging windows Mosquitto publishes, in minutes, with their topic
   names."
  [[1 "1min"] [5 "5min"] [15 "15min"]])

(defn load-rate
  "One step of Mosquitto's smoothing, and the reason these numbers need
   explaining.

   `delta` is the count since the last sample and `elapsed` the seconds since
   it. That is first scaled to a rate **per minute**, then blended into the
   previous average with exp(-elapsed / (60 * minutes)). What gets published is
   therefore \"how many of these happen in a minute, averaged over N minutes\" —
   not a total, and not a per-second rate. A short window follows a burst
   quickly and a long one barely notices it, which is why there are three."
  ^double [^double previous ^double delta ^double elapsed ^double minutes]
  (let [per-minute (* delta (/ 60.0 elapsed))
        exponent   (Math/exp (- (/ elapsed (* 60.0 minutes))))]
    (+ per-minute (* exponent (- previous per-minute)))))

(def ^:private load-metrics
  "Topic suffix, and how to read the running total it is the rate of."
  [["load/connections"       #(sum-type MqttStat/receivedByType 1)]
   ["load/sockets"           #(sum MqttStat/socketConnections)]
   ["load/bytes/received"    #(sum MqttStat/receivedBytes)]
   ["load/bytes/sent"        #(sum MqttStat/writtenBytes)]
   ["load/messages/received" #(sum MqttStat/receivedMessages)]
   ["load/messages/sent"     #(sum MqttStat/writtenMessages)]
   ["load/publish/received"  #(sum-type MqttStat/receivedByType 3)]
   ["load/publish/sent"      #(sum-type MqttStat/sentByType 3)]
   ["load/publish/dropped"   #(+ (sum MqttStat/droppedMessages)
                                 (sum MqttStat/discardedMessages))]])

(defonce ^:private load-state (atom {:totals {} :averages {}}))

(defn- two-places
  "Locale/ROOT, so a machine set to a comma decimal separator does not publish
   1,50 to a topic every other broker publishes 1.50 on."
  [^double v]
  (String/format java.util.Locale/ROOT "%.2f" (to-array [v])))

(defn advance-load!
  "Move the averages on by `elapsed` seconds and return their topics.

   Takes the elapsed time rather than reading a clock, so it can be driven
   deterministically from a test. Only the publisher calls it, so the
   read-then-write is not guarded: two callers at once would each want a
   different elapsed anyway."
  [^double elapsed]
  (if-not (pos? elapsed)
    {}
    (let [{:keys [totals averages]} @load-state
          now (into {} (map (fn [[suffix f]] [suffix (long (f))])) load-metrics)
          next-averages
          (into {}
                (for [[suffix total] now
                      [minutes label] load-windows]
                  ;; First time out the previous total is taken as this one, so
                  ;; the delta is zero: a broker up for hours before anyone
                  ;; subscribes does not report its whole history as a spike.
                  (let [delta (- total (get totals suffix total))]
                    [[suffix label]
                     (load-rate (get averages [suffix label] 0.0)
                                (double delta) elapsed (double minutes))])))]
      (reset! load-state {:totals now :averages next-averages})
      (into {} (for [[[suffix label] v] next-averages]
                 [(str prefix suffix "/" label) (two-places v)])))))

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

(defonce ^:private last-publish (atom nil))

(defn publish-once!
  "Publish the current values. Retained and at QoS 0, like Mosquitto's.

   The load averages advance by the time actually elapsed since the last call
   rather than by the nominal interval, so a publisher that was held up does
   not overstate the rate."
  []
  (let [now     (System/nanoTime)
        prev    @last-publish
        elapsed (if prev (/ (- now prev) 1e9) (double sys-interval))]
    (reset! last-publish now)
    (doseq [[topic value] (merge (stats) (advance-load! elapsed))]
      (h/publish {:packet-type :PUBLISH
                  :topic       topic
                  :qos         0
                  :retain?     true
                  :payload     (str value)
                  :client-key  nil}))))

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
