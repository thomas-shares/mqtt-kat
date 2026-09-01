(ns mqttkat.client-generator
  "A load simulation, not a unit test: it drives a client through a long run of
   generated packets and checks the replies. Tagged ^:performance, so `lein
   test` skips it (see :test-selectors in project.clj) — run it on purpose with
   `lein test :performance`."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [mqttkat.test-util :as tu]
            [mqttkat.client :as client]
            ;;[mqttkat.spec :as mqtt]
            [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [clojure.core.async :as async]
            [overtone.at-at :as at])
  (:import  [org.mqttkat MqttHandler MqttStat]
            [org.mqttkat.client MqttClient]))

(def subscribe-topics (atom {}))
(def my-pool (at/mk-pool))
;(def channel (async/chan 1))

(defn handler-fn [msg chan]
  ;;(println "Posting on async channel CLIENT: " msg chan)
  ;(clojure.pprint/pprint (dissoc msg :client-key))
  (async/go (async/>! chan msg)))

(def handler (MqttHandler. ^clojure.lang.IFn handler-fn 2))

(use-fixtures :once tu/broker-fixture)

(defn client []
  (client/client tu/host tu/port (MqttHandler. ^clojure.lang.IFn handler-fn 2)))

(defn- recv!
  "Next packet for `client`, or a failure. Every read in this namespace goes
   through here: a bare <!! on the client channel blocks forever when a packet
   goes missing, which used to wedge the whole suite rather than fail one test."
  [^MqttClient client]
  (or (first (async/alts!! [(.getChannel client) (async/timeout 2000)]))
      (throw (ex-info "no packet arrived within 2s" {:client client}))))

(defn connect [client]
  (client/connect client))

(defn connack [client]
  (let [msg (recv! client)]
    (is (= (:packet-type msg) :CONNACK))
    (client/logger "R " msg)))

(defn compare-packet-identifier [p-id-1 p-id-2]
  (is (= p-id-1 p-id-2)))

(defn compare-payload [payload-1 payload-2]
  (is (= (seq payload-1) (seq payload-2))))

(defn qos-zero [client payload]
  (let [msg (recv! client)]
    (compare-payload payload (:payload  msg))
    (is (= 0 (:qos msg)))))

(defn process-qos-one [client msg]
  (when-not (zero? (:qos msg))
    (client/puback client (:packet-identifier msg))))

(defn qos-one [client payload packet-identifier]
  ;(println "QOS1 " packet-identifier)
  (let [first-message (recv! client)
        _ (client/logger  "first: " first-message)
        second-message (recv! client)
        _ (client/logger  "second " second-message)]
    (if (= :PUBACK (:packet-type first-message))
      (do (let [received-packet-identifier (:packet-identifier first-message)]
            (compare-packet-identifier packet-identifier received-packet-identifier)
            (compare-payload payload (:payload second-message))
            (process-qos-one client second-message)))
      (do (let [received-packet-identifier (:packet-identifier second-message)]
            (compare-packet-identifier packet-identifier received-packet-identifier)
            (compare-payload payload (:payload first-message))
            (process-qos-one client first-message))))))

(defn process-return [client msg]
  (condp = (:qos msg)
    0 nil
    1 (process-qos-one client msg)
    2 (do
        (client/pubrec client (:packet-identifier msg))
        (let [pubrel (recv! client)]
          (client/logger  "R " pubrel)
          (is (= :PUBREL (:packet-type pubrel)))
          (client/pubcomp client (:packet-identifier pubrel))))))

(defn qos-two [client payload packet-identifier]
  ;(client/logger  "QOS2 " packet-identifier)
  (let [pubrec (recv! client)]
    (client/logger  "R " pubrec)
    (compare-packet-identifier packet-identifier (:packet-identifier pubrec))
    (client/pubrel client packet-identifier)
    (let [first-message (recv! client)
          second-message (recv! client)]
      (client/logger  "R "first-message)
      (client/logger  "R " second-message)
      (if (= :PUBCOMP (:packet-type first-message))
        (do (let [packet-identifier (:packet-identifier first-message)]
              (compare-packet-identifier packet-identifier (:packet-identifier first-message))
              (compare-payload payload (:payload second-message))
              (process-return client  second-message)))
        (do (let [packet-identifier (:packet-identifier second-message)]
              (compare-packet-identifier packet-identifier (:packet-identifier second-message))
              (compare-payload payload (:payload first-message))
              (process-return client first-message)))))))

(defn filter-to-topic [filter]
  (-> filter
    (clojure.string/replace  #"\+" (gen/generate (s/gen (s/and string? #(<= 2 (count %))))))
    (clojure.string/replace  #"#" (gen/generate (s/gen (s/and string? #(<= 2 (count %))))))))

(def ^:private publishes-per-client
  "How many messages one simulated client sends. This used to recur forever, so
   the load outlived the test that started it and kept hammering the broker for
   the rest of the run."
  50)

(defn publish
  ([client] (publish client publishes-per-client))
  ([client n]
   (when (pos? n)
     (if-let [filters (seq (get @subscribe-topics client))]
       (let [topic (filter-to-topic (rand-nth (vec filters)))
             {payload :payload qos :qos packet-identifier :packet-identifier}
             (client/publish client topic)]
         (condp = qos
           0 (qos-zero client payload)
           1 (qos-one client payload packet-identifier)
           2 (qos-two client payload packet-identifier)))
       (is false "client published before subscribing to anything"))
     (recur client (dec n)))))


(defn disconnect [client]
  (reset! subscribe-topics {})
  (client/disconnect client))

(defn subscribe [client]
  (let [topic-filter (client/subscribe ^MqttClient client)
        topics (map #(:topic-filter % ) (:topics topic-filter))
        c (count topics)]
    (swap! subscribe-topics assoc client topics)
    (let [msg (recv! client)
          ret-count (count (:response msg))]
      (client/logger  "R " msg)
      (is (= c ret-count)))))


(defn start-client [client]
  (connect client)
  (connack client)
  (subscribe client)
  ;(at/interspaced 1 #(publish client) my-pool :initial-delay 1000))
  (at/after 100 #(publish client) my-pool))


(deftest ^:performance multiple-clients
  (let [;;start-time (System/currentTimeMillis)
        clients (into [] (take 1 (repeatedly #(client/client tu/host tu/port handler))))]
    (doseq [client clients]
      ;;(println client)
      (at/after 100 #(start-client client) my-pool))
    (Thread/sleep 5000)
    ;(at/show-schedule my-pool)
    (println "done sleeping....")))
