(ns mqttkat.test-util
  "Scaffolding shared by the test suite.

   Two rules everything here exists to enforce:

     - The suite starts its own broker, so `lein test` works on a clean machine
       instead of silently depending on one someone left running.
     - Every read from a client has a timeout, so a packet that never arrives
       fails the test it belongs to instead of wedging the whole run."
  (:require [clojure.core.async :as async :refer [alts!! chan go timeout >!]]
            [clojure.test :refer [is]]
            [mqttkat.client :as client]
            [mqttkat.handlers :as handlers]
            [mqttkat.server :as server])
  (:import [org.mqttkat MqttHandler]
           [org.mqttkat.client MqttClient]))

(def host "localhost")

(def port
  "Deliberately not 1883. A broker started by hand for REPL work must not
   silently absorb the suite, and the suite must not fail to bind because one
   is already running."
  11883)

(defonce ^:private broker (delay (server/start! "0.0.0.0" port)))

(defn ensure-broker!
  "Start the test broker, once per JVM. It is never stopped: the JVM exit tears
   it down, and stopping between namespaces only creates ways for a later
   namespace to meet a half-torn-down broker."
  []
  @broker)

(defn broker-fixture [f]
  (ensure-broker!)
  (f))

(def ^:private counter (atom 0))

(defn client-id
  "A client id unique within this JVM run. Tests that reuse an id inherit each
   other's session state on the broker, which makes them order-dependent."
  [prefix]
  (str prefix "-" (swap! counter inc)))

(defn client!
  "A client attached to the test broker at the socket level — no CONNECT sent.
   Returns {:client <MqttClient>, :ch <channel of the packets it receives>}."
  ([] (client! 16))
  ([buffer]
   (let [ch (chan buffer)]
     {:client (client/client host port
                             (MqttHandler. ^clojure.lang.IFn (fn [msg _] (go (>! ch msg))) 1))
      :ch     ch})))

(defn take!
  "The next packet from `ch`, or nil when none arrives within `ms`."
  ([ch] (take! ch 1000))
  ([ch ms] (first (alts!! [ch (timeout ms)]))))

(defn expect!
  "Take the next packet and assert its type. Returns the packet."
  ([ch type] (expect! ch type 1000))
  ([ch type ms]
   (let [msg (take! ch ms)]
     (is (= type (:packet-type msg))
         (if msg
           (str "expected " type ", got " (:packet-type msg))
           (str "timed out after " ms "ms waiting for " type)))
     msg)))

(defn expect-eventually!
  "Take until a packet of `type` arrives, ignoring others. Acks and forwarded
   PUBLISHes race with each other, so a test that cares about one of them
   should not assert on arrival order."
  ([ch type] (expect-eventually! ch type 1000))
  ([ch type ms]
   (let [deadline (+ (System/currentTimeMillis) ms)]
     (loop []
       (let [left (- deadline (System/currentTimeMillis))
             msg  (when (pos? left) (take! ch left))]
         (cond
           (nil? msg)                  (do (is false (str "timed out after " ms "ms waiting for " type)) nil)
           (= type (:packet-type msg)) msg
           :else                       (recur)))))))

(defn take-n!
  "Take up to `n` packets within `ms`, grouped by :packet-type. Acks and
   forwarded PUBLISHes race with each other, so a test that expects both should
   assert on what arrived rather than on the order it arrived in."
  ([ch n] (take-n! ch n 1500))
  ([ch n ms]
   (let [deadline (+ (System/currentTimeMillis) ms)]
     (loop [acc []]
       (let [left (- deadline (System/currentTimeMillis))]
         (if (or (= n (count acc)) (not (pos? left)))
           (group-by :packet-type acc)
           (if-let [msg (take! ch left)]
             (recur (conj acc msg))
             (group-by :packet-type acc))))))))

(defn topic
  "A topic unique to this JVM run. Retained messages and subscriptions outlive
   the test that made them, so tests that share a topic depend on their order."
  [prefix]
  (str prefix "-" (swap! counter inc) "/test"))

(defn wait-for-parked-session!
  "Block until the broker has parked `id`'s session under its client id.

   A persistent session is re-keyed from the connection to the client id only
   when the server notices the socket is gone, which races with the next
   CONNECT. The broker runs in this JVM, so the test can watch for it rather
   than sleep and hope."
  ([id] (wait-for-parked-session! id 2000))
  ([id ms]
   (let [deadline (+ (System/currentTimeMillis) ms)]
     (loop []
       (cond
         (contains? @handlers/*clients* id)       true
         (> (System/currentTimeMillis) deadline)  (do (is false (str "session " id " was never parked")) false)
         :else                                    (do (Thread/sleep 10) (recur)))))))

(defn wildcard
  "The `parent/#` filter matching a topic built by `topic`. `#` matches the
   levels below its parent, so the filter has to sit one level up."
  [^String t]
  (str (subs t 0 (.indexOf t "/")) "/#"))

(defn wait-until
  "Poll `pred` every 25ms until it holds, or `ms` elapses. Returns whether it
   held. A deadline rather than a fixed sleep: the assertion then says what must
   become true and by when, instead of depending on the machine keeping pace."
  ([pred] (wait-until pred 4000))
  ([pred ms]
   (let [deadline (+ (System/currentTimeMillis) ms)]
     (loop []
       (cond
         (pred)                                  true
         (> (System/currentTimeMillis) deadline) false
         :else                                   (do (Thread/sleep 25) (recur)))))))

(defn retained-payload
  "What the broker currently has retained on `topic`, as a String."
  [topic]
  (some-> (get-in @handlers/*retained* [topic :payload]) (String. "UTF-8")))

(defn wait-for-retained!
  "Block until `topic` holds `expected` as its retained message.

   A PUBLISH and a following SUBSCRIBE are handled as independent tasks on the
   broker's thread pool, so a subscriber can be registered before the publish
   that precedes it has been processed. A test about retention should not be
   at the mercy of that; one about ordering should be written on purpose."
  ([topic expected] (wait-for-retained! topic expected 2000))
  ([topic expected ms]
   (or (wait-until #(= expected (retained-payload topic)) ms)
       (do (is false (str "nothing retained on " topic " after " ms "ms")) false))))

(defn payload-str
  "The :payload of a packet as a String (it arrives as a byte array)."
  [msg]
  (some-> ^bytes (:payload msg) (String. "UTF-8")))

(defn connect!
  "Create a client and complete the CONNECT/CONNACK handshake.
   Returns {:client :ch :connack}."
  [prefix & {:keys [clean-session? keep-alive will id]
             :or   {clean-session? true keep-alive 100}}]
  (let [{:keys [client ch] :as c} (client!)
        msg (cond-> {:packet-type      :CONNECT
                     :protocol-name    "MQTT"
                     :protocol-version 4
                     :keep-alive       keep-alive
                     :clean-session?   clean-session?
                     :client-id        (or id (client-id prefix))}
              will (assoc :will will))]
    (client/send-message client msg)
    (assoc c :connack (expect! ch :CONNACK))))

(defn close!
  "Close clients, ignoring the usual noise from an already-dead socket."
  [& clients]
  (doseq [c clients :when c]
    (try (.close ^MqttClient (:client c c)) (catch Exception _ nil))))
