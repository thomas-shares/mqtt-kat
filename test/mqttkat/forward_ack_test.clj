(ns mqttkat.forward-ack-test
  "A copy forwarded over a bridge, as the broker it is forwarded to treats
   it, now that the publisher's acknowledgement on the other broker waits
   for it (mqttkat.rama.cluster/forward-ack?): taken on receipt, and the
   bridge never paused by a subscriber here that is behind.

   The simulation (thoughts.md, 20261020) found a copy taken on receipt as
   safe as one taken once this broker's own writes for it have landed, at
   a fraction of the cost: the broker that forwarded it keeps it until
   this one says its subscribers have it. The first runs with the wait on
   stalled every publisher for thirty seconds at a time, a slow
   subscriber here having paused the bridge all copies come in on.

   Not portable: a bridge is this broker's own idea, and what it is
   answered is this broker's choice."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [mqttkat.bridge :as bridge]
            [mqttkat.client :as client]
            [mqttkat.handlers :as h]
            [mqttkat.rama.cluster :as cluster]
            [mqttkat.test-util :as tu])
  (:import [java.util.concurrent CompletableFuture]
           [org.mqttkat MqttHandler]))

(def ^:private ^:dynamic *conn* nil)

(defn- with-cluster [f]
  (tu/ensure-broker!)
  (let [conn (cluster/connect :in-process)]
    (try
      (cluster/attach! conn)
      (binding [*conn* conn] (f))
      (finally
        (cluster/detach!)
        (cluster/close! conn)))))

(use-fixtures :once with-cluster)

(defn- client
  "A raw client that keeps the identifiers of the PUBACKs it is sent in
   `acked`, and of the PUBLISHes in `got`, answering none of them."
  [acked got]
  (let [connack (promise)
        suback  (promise)
        c       (atom nil)
        on      (fn [msg _]
                  (case (:packet-type msg)
                    :CONNACK (deliver connack msg)
                    :SUBACK  (deliver suback msg)
                    :PUBACK  (swap! acked conj (:packet-identifier msg))
                    :PUBLISH (swap! got conj (:packet-identifier msg))
                    nil))]
    (reset! c (client/client tu/host tu/port (MqttHandler. ^clojure.lang.IFn on 1)))
    {:client @c :connack connack :suback suback}))

(defn- connect! [c id clean? version]
  (client/send-message (:client c) (cond-> {:packet-type :CONNECT :protocol-name "MQTT"
                                            :protocol-version version
                                            :keep-alive 100 :clean-session? clean? :client-id id}
                                     (= 5 version) (assoc :properties {:session-expiry-interval
                                                                       (if clean? 0 3600)})))
  (is (some? (deref (:connack c) 5000 nil)))
  c)

(defn- close! [c] (try (client/close (:client c)) (catch Exception _ nil)))

(defn- publish! [c topic i version]
  (client/send-message (:client c) (cond-> {:packet-type :PUBLISH :topic topic :qos 1
                                            :packet-identifier i
                                            :payload (.getBytes (str i) "UTF-8")
                                            :retain? false :duplicate? false}
                                     (= 5 version) (assoc :protocol-version 5 :properties {}))))

(defn- subscribed-away!
  "A kept session subscribed to `topic`, gone: what is published to it
   is queued on the cluster."
  [topic]
  (let [id (tu/client-id "fa-away")
        c  (connect! (client (atom []) (atom [])) id false 4)]
    (client/send-message (:client c) {:packet-type :SUBSCRIBE :packet-identifier 1
                                      :topics [{:qos 1 :topic-filter topic}]})
    (is (some? (deref (:suback c) 5000 nil)))
    (close! c)
    (is (tu/wait-until #(false? (cluster/connected? *conn* id)) 10000))))

(deftest a-copy-from-a-bridge-is-acknowledged-on-receipt
  ;; The queue write for the session that is away never lands. A client's
  ;; PUBACK waits for it; a bridge's does not: the broker that forwarded
  ;; the copy keeps it until this one says its subscribers have it.
  (let [topic (tu/topic "fa-receipt")]
    (subscribed-away! topic)
    (with-redefs [cluster/enqueue! (fn [& _] (CompletableFuture.))]
      (let [acked (atom [])
            plain (connect! (client acked (atom [])) (tu/client-id "fa-pub") true 4)]
        (publish! plain topic 1 4)
        (Thread/sleep 1000)
        (is (= [] @acked) "a client's PUBACK waits for the write")
        (close! plain))
      (let [acked (atom [])
            peer  (connect! (client acked (atom []))
                            (str bridge/client-id-prefix (tu/client-id "fa-peer")) true 5)]
        (publish! peer topic 1 5)
        (is (tu/wait-until #(= [1] @acked) 5000) "a bridge's does not")
        (close! peer)))))

(deftest a-subscriber-behind-does-not-pause-the-bridge
  ;; A clean session here that answers nothing: its window fills, then its
  ;; pending queue past pause-threshold. Copies keep coming in over the
  ;; bridge, and are acknowledged, for every other subscriber's sake.
  (with-redefs [h/pause-threshold 3 h/resume-threshold 1]
    (let [topic (tu/topic "fa-pause")
          slow  (connect! (client (atom []) (atom [])) (tu/client-id "fa-slow") true 4)
          _     (client/send-message (:client slow) {:packet-type :SUBSCRIBE :packet-identifier 1
                                                     :topics [{:qos 1 :topic-filter topic}]})
          _     (is (some? (deref (:suback slow) 5000 nil)))
          acked (atom [])
          peer  (connect! (client acked (atom []))
                          (str bridge/client-id-prefix (tu/client-id "fa-peer")) true 5)
          first (+ h/inflight-window 5)
          n     (+ first 20)]
      (try
        ;; Enough to fill its window and its pending queue past the
        ;; threshold; then, the pause having had time to take, the rest one
        ;; at a time: a bridge paused does not read them.
        (dotimes [i first] (publish! peer topic (inc i) 5))
        (is (tu/wait-until #(= first (count @acked)) 10000))
        (Thread/sleep 500)
        (doseq [i (range first n)]
          (publish! peer topic (inc i) 5)
          (Thread/sleep 20))
        (is (tu/wait-until #(= n (count @acked)) 10000)
            (str (count @acked) " of " n " acknowledged"))
        (finally
          (close! peer)
          (close! slow))))))
