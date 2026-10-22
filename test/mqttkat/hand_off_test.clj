(ns mqttkat.hand-off-test
  "A publish acknowledged only once each copy of it for a kept session has
   left this broker's memory (mqttkat.handlers/hold-copy).

   Chaos run 20261022-133535 lost what a killed broker held for kept
   sessions behind a full window: its publishers had been told it was
   safe, and the copies were only in that broker's memory (doc/guarantees.md,
   R1). The simulation closed the window (thoughts.md, 20261022): the
   PUBACK or PUBCOMP waits until each copy is answered for by its
   subscriber, or, after hand-off-millis, is on the cluster's queue.

   Not portable: when a broker acknowledges a publish is its own choice,
   and these stand in for its cluster."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
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
  "A raw MQTT 5 client. The identifiers of the PUBACKs it is sent go to
   `acked`, the PUBLISHes to `got` as [identifier payload]; it answers
   them only while `answer?` is true."
  [acked got answer?]
  (let [connack (promise)
        suback  (promise)
        c       (atom nil)
        on      (fn [msg _]
                  (case (:packet-type msg)
                    :CONNACK (deliver connack msg)
                    :SUBACK  (deliver suback msg)
                    :PUBACK  (swap! acked conj (:packet-identifier msg))
                    :PUBLISH (do (swap! got conj [(:packet-identifier msg) (tu/payload-str msg)])
                                 (when @answer?
                                   (client/send-message @c {:packet-type :PUBACK :protocol-version 5
                                                            :packet-identifier (:packet-identifier msg)
                                                            :properties {}})))
                    nil))]
    (reset! c (client/client tu/host tu/port (MqttHandler. ^clojure.lang.IFn on 1)))
    {:client @c :connack connack :suback suback}))

(defn- connect! [c id kept? receive-maximum]
  (client/send-message (:client c) {:packet-type :CONNECT :protocol-name "MQTT" :protocol-version 5
                                    :keep-alive 100 :clean-session? (not kept?) :client-id id
                                    :properties (cond-> {:session-expiry-interval (if kept? 3600 0)}
                                                  receive-maximum (assoc :receive-maximum receive-maximum))})
  (is (some? (deref (:connack c) 5000 nil)))
  c)

(defn- subscribe! [c topic]
  (client/send-message (:client c) {:packet-type :SUBSCRIBE :protocol-version 5 :packet-identifier 1
                                    :properties {} :topics [{:qos 1 :topic-filter topic}]})
  (is (some? (deref (:suback c) 5000 nil))))

(defn- publish! [c topic i]
  (client/send-message (:client c) {:packet-type :PUBLISH :protocol-version 5 :topic topic :qos 1
                                    :packet-identifier i :properties {}
                                    :payload (.getBytes (str i) "UTF-8")
                                    :retain? false :duplicate? false}))

(defn- close! [c] (try (client/close (:client c)) (catch Exception _ nil)))

(defn- subscriber!
  "A subscriber on `topic` taking `window` messages at a time, one unless
   given: `answer?` says whether it answers them."
  [id topic kept? answer? got & [window]]
  (let [c (connect! (client (atom []) got answer?) id kept? (or window 1))]
    (subscribe! c topic)
    (is (tu/wait-until #(seq (cluster/matching-subscriptions *conn* topic)) 10000))
    c))

(deftest a-copy-held-here-holds-the-publish-until-the-cluster-has-it
  ;; The first copy is in the subscriber's window, unanswered; the second
  ;; waits behind it. Neither is the cluster's until it is written there,
  ;; and the PUBACK waits for that: here, never.
  (let [topic (tu/topic "ho-held")
        id    (tu/client-id "ho-sub")
        got   (atom [])
        sub   (subscriber! id topic true (atom false) got)
        acked (atom [])
        pub   (connect! (client acked (atom []) (atom true)) (tu/client-id "ho-pub") false nil)]
    (try
      (testing "while the write to the cluster does not land"
        (with-redefs [cluster/enqueue! (fn [& _] (CompletableFuture.))]
          (publish! pub topic 1)
          (publish! pub topic 2)
          (is (tu/wait-until #(= 1 (count @got)) 5000) "the first sent")
          (Thread/sleep 1000)
          (is (= [] @acked) "neither acknowledged")))
      (finally (close! pub) (close! sub)))))

(deftest copies-held-here-are-written-to-the-cluster-and-taken-off-once-answered
  (let [topic   (tu/topic "ho-written")
        id      (tu/client-id "ho-sub")
        got     (atom [])
        answer? (atom false)
        sub     (subscriber! id topic true answer? got)
        acked   (atom [])
        pub     (connect! (client acked (atom []) (atom true)) (tu/client-id "ho-pub") false nil)]
    (try
      (publish! pub topic 1)
      (publish! pub topic 2)
      (is (tu/wait-until #(= #{1 2} (set @acked)) 10000) "acknowledged once written")
      (let [queued (into {} (map (fn [[_ m]] [(String. ^bytes (:payload m) "UTF-8") m]))
                         (cluster/queued *conn* id))]
        (is (= #{"1" "2"} (set (keys queued))) "both on the cluster's queue")
        (is (= (ffirst @got) (:packet-identifier (get queued "1")))
            "the one in flight under its identifier, for a resume to send again under it")
        (is (nil? (:packet-identifier (get queued "2"))) "the one waiting without"))
      (testing "and taken off there once the subscriber has them"
        (reset! answer? true)
        (client/send-message (:client sub) {:packet-type :PUBACK :protocol-version 5
                                            :packet-identifier (ffirst @got) :properties {}})
        (is (tu/wait-until #(= 2 (count @got)) 5000) "the second sent")
        (is (tu/wait-until #(empty? (cluster/queued *conn* id)) 10000)
            (pr-str (cluster/queued *conn* id))))
      (finally (close! pub) (close! sub)))))

(deftest a-copy-answered-in-time-is-written-nowhere
  ;; A subscriber that keeps up costs the cluster nothing: its PUBACK
  ;; lets the publish go well within hand-off-millis.
  (let [topic  (tu/topic "ho-quick")
        id     (tu/client-id "ho-sub")
        writes (atom 0)
        enq    cluster/enqueue!]
    (with-redefs [cluster/enqueue! (fn [conn client-id & more]
                                     (when (= id client-id) (swap! writes inc))
                                     (apply enq conn client-id more))]
      (let [got   (atom [])
            sub   (subscriber! id topic true (atom true) got 100)
            acked (atom [])
            pub   (connect! (client acked (atom []) (atom true)) (tu/client-id "ho-pub") false nil)]
        (try
          (dotimes [i 20] (publish! pub topic (inc i)))
          (is (tu/wait-until #(= 20 (count @acked)) 10000))
          (is (tu/wait-until #(= 20 (count @got)) 10000))
          (Thread/sleep 300)
          (is (= 0 @writes) "nothing written for it")
          (finally (close! pub) (close! sub)))))))

(deftest a-clean-session-holds-up-nothing
  ;; It has nothing on the cluster to be written to: as before.
  (let [topic (tu/topic "ho-clean")
        got   (atom [])
        sub   (subscriber! (tu/client-id "ho-clean") topic false (atom false) got)
        acked (atom [])
        pub   (connect! (client acked (atom []) (atom true)) (tu/client-id "ho-pub") false nil)]
    (try
      (with-redefs [cluster/enqueue! (fn [& _] (CompletableFuture.))]
        (publish! pub topic 1)
        (publish! pub topic 2)
        (is (tu/wait-until #(= #{1 2} (set @acked)) 5000)))
      (finally (close! pub) (close! sub)))))

(deftest a-subscriber-one-at-a-time-is-sent-everything-once
  ;; Behind, it has its copies written to the cluster's queue as it goes,
  ;; and taken off there as it answers: each is sent once, and none is
  ;; left there.
  (let [topic (tu/topic "ho-slow")
        id    (tu/client-id "ho-sub")
        got   (atom [])
        sub   (subscriber! id topic true (atom true) got)
        acked (atom [])
        pub   (connect! (client acked (atom []) (atom true)) (tu/client-id "ho-pub") false nil)
        n     30]
    (try
      (dotimes [i n] (publish! pub topic (inc i)))
      (is (tu/wait-until #(= n (count @acked)) 10000))
      (is (tu/wait-until #(= n (count @got)) 10000) (pr-str (sort (map second @got))))
      (is (= (map str (range 1 (inc n))) (map second @got)) "each once, in order")
      (is (tu/wait-until #(empty? (cluster/queued *conn* id)) 10000)
          (pr-str (map first (cluster/queued *conn* id))))
      (finally (close! pub) (close! sub)))))
