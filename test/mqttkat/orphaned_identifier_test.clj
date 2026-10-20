(ns mqttkat.orphaned-identifier-test
  "A packet identifier orphaned by a broker that died: found by the
   simulation (thoughts.md, 20261020), confirmed here against the code.

   A broker sends a kept session a QoS 2 message under identifier 1 and
   dies before the PUBREL. The client holds 1 unreleased, as it must
   (§4.3.3), and only the dead broker knew. The session resumes here, and
   the next QoS 2 message for it is given an identifier by this broker's
   counter, from 1. The client takes it for the one it holds and drops it.

   The dead broker is only a record in the cluster, listed at a port that
   refuses, as a killed broker is; the suite's broker is the one the
   session resumes on. The client comes in holding identifier 1 as the
   dead broker left it. Not portable: it stands in for the other broker
   through the cluster.

   Tagged :known-failure, left out of `lein test` until it is fixed: run it
   with `lein test :known-failure`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [mqttkat.bridge :as bridge]
            [mqttkat.client :as client]
            [mqttkat.handlers :as h]
            [mqttkat.rama.cluster :as cluster]
            [mqttkat.test-util :as tu])
  (:import [org.mqttkat MqttHandler]))

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
  "A raw client that holds the QoS 2 identifiers in `held` unreleased, as
   it would after receiving them and before their PUBREL, and hands a QoS 2
   message to `received` once per identifier it does not hold, as §4.3.3
   has it. Every PUBLISH it is sent goes to `seen`, [identifier payload]."
  [held received seen]
  (let [connack (promise)
        suback  (promise)
        held    (atom (set held))
        c       (atom nil)
        send!   #(client/send-message @c %)
        on      (fn [msg _]
                  (case (:packet-type msg)
                    :CONNACK (deliver connack msg)
                    :SUBACK  (deliver suback msg)
                    :PUBLISH (let [pid (:packet-identifier msg)
                                   p   (tu/payload-str msg)]
                               (swap! seen conj [pid p])
                               (case (long (:qos msg 0))
                                 1 (do (swap! received conj p)
                                       (send! {:packet-type :PUBACK :packet-identifier pid}))
                                 2 (do (when-not (contains? @held pid)
                                         (swap! held conj pid)
                                         (swap! received conj p))
                                       (send! {:packet-type :PUBREC :packet-identifier pid}))
                                 nil))
                    :PUBREC  (send! {:packet-type :PUBREL :packet-identifier (:packet-identifier msg)})
                    :PUBREL  (do (swap! held disj (:packet-identifier msg))
                                 (send! {:packet-type :PUBCOMP :packet-identifier (:packet-identifier msg)}))
                    nil))]
    (reset! c (client/client tu/host tu/port (MqttHandler. ^clojure.lang.IFn on 1)))
    {:client @c :connack connack :suback suback :send! send!}))

(defn- connect! [c id clean?]
  (client/send-message (:client c) {:packet-type :CONNECT :protocol-name "MQTT" :protocol-version 4
                                    :keep-alive 100 :clean-session? clean? :client-id id})
  c)

(defn- close! [c] (try (client/close (:client c)) (catch Exception _ nil)))

(defn- on-dead-broker!
  "`id`, a kept session subscribed here to `topic` at QoS 2, recorded as
   connected on `dead`, a broker listed at a port that refuses."
  [id topic dead]
  (let [c (connect! (client [] (atom []) (atom [])) id false)]
    (is (some? (deref (:connack c) 5000 nil)))
    (client/send-message (:client c) {:packet-type :SUBSCRIBE :packet-identifier 1
                                      :topics [{:qos 2 :topic-filter topic}]})
    (is (some? (deref (:suback c) 5000 nil)))
    (is (tu/wait-until #(seq (cluster/matching-subscriptions *conn* topic)) 10000))
    (close! c)
    (is (tu/wait-until #(false? (cluster/connected? *conn* id)) 10000)))
  (let [port (with-open [s (java.net.ServerSocket. 0)] (.getLocalPort s))]
    @(cluster/record! *conn* {:event :broker-up :broker-id dead :incarnation (str dead "-run")
                              :host "127.0.0.1" :port port :at (System/currentTimeMillis)})
    (is (tu/wait-until #(contains? (cluster/brokers) dead) 10000)))
  @(cluster/record! *conn* (assoc (cluster/->connect {:client-id id :protocol-version 4
                                                      :clean-session? false :keep-alive 100})
                                  :broker-id dead :incarnation (str dead "-run")))
  (is (tu/wait-until #(= dead (:broker-id (cluster/session *conn* id))) 10000)))

(defn- publish-qos-2! [topic payload]
  (let [pub (connect! (client [] (atom []) (atom [])) (tu/client-id "orphan-pub") true)]
    (try
      (is (some? (deref (:connack pub) 5000 nil)))
      (client/send-message (:client pub) {:packet-type :PUBLISH :topic topic :qos 2
                                          :packet-identifier 1
                                          :payload (.getBytes ^String payload "UTF-8")
                                          :retain? false :duplicate? false})
      (Thread/sleep 1000)
      (finally (close! pub)))))

(defn- resumed-holding-1
  "What a kept session on `topic` is handed of a QoS 2 message published
   after it resumed here, holding identifier 1 from a broker that died."
  [topic]
  (with-redefs [bridge/refused-for-gone-ms 300]
    (let [id       (tu/client-id "orphan-sub")
          dead     (tu/client-id "peer-dead")
          received (atom [])
          seen     (atom [])]
      (on-dead-broker! id topic dead)
      (let [c (connect! (client [1] received seen) id false)]
        (try
          (is (true? (:session-present? (deref (:connack c) 15000 nil))))
          (publish-qos-2! topic "after")
          (tu/wait-until #(seq @seen) 10000)
          (Thread/sleep 1000)
          {:received @received :seen @seen}
          (finally (close! c)))))))

(deftest ^:known-failure a-message-after-a-resume-is-not-sent-under-an-identifier-the-client-holds
  (testing "an ordinary topic"
    (let [topic (tu/topic "orphan")
          {:keys [received seen]} (resumed-holding-1 topic)]
      (is (= ["after"] received)
          (str "sent as " (pr-str seen) "; the client holds identifier 1"))))
  (testing "an exactly-once topic: the claim takes the identifier the client holds"
    (let [topic (tu/topic "orphan-eo")]
      (with-redefs [h/exactly-once-filters (delay [topic])]
        (let [{:keys [received seen]} (resumed-holding-1 topic)]
          (is (= ["after"] received)
              (str "sent as " (pr-str seen) "; the client holds identifier 1")))))))
