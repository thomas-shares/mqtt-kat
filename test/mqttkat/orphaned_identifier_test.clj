(ns mqttkat.orphaned-identifier-test
  "A packet identifier orphaned by a broker that died: found by the
   simulation (thoughts.md, 20261020), confirmed here against the code.

   A broker sends a kept session a QoS 2 message under identifier 1 and
   dies before the PUBREL. The client holds 1 unreleased, as it must
   (§4.3.3), and only the dead broker knew. The session resumed here, and
   the next QoS 2 message for it was given an identifier by this broker's
   counter, from 1. The client took it for the one it held and dropped it.

   Now identifiers are leased (mqttkat.handlers/lease-identifiers!): the
   dead broker had leased block 0 before it gave out 1, so the broker the
   session resumes on releases that block with PUBRELs, and gives out
   identifiers from a block of its own.

   The dead broker is only a record in the cluster, its connection and its
   lease, listed at a port that refuses, as a killed broker is; the suite's
   broker is the one the session resumes on. The client comes in holding
   identifier 1 as the dead broker left it. Not portable: it stands in for
   the other broker through the cluster."
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
  ([held received seen] (client held received seen (atom [])))
  ([held received seen released]
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
                    :PUBREL  (do (swap! released conj (:packet-identifier msg))
                                 (swap! held disj (:packet-identifier msg))
                                 (send! {:packet-type :PUBCOMP :packet-identifier (:packet-identifier msg)}))
                    nil))]
    (reset! c (client/client tu/host tu/port (MqttHandler. ^clojure.lang.IFn on 1)))
    {:client @c :connack connack :suback suback :send! send!})))

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
  (let [connect-id (str (java.util.UUID/randomUUID))]
    @(cluster/record! *conn* (assoc (cluster/->connect {:connect-id connect-id :client-id id
                                                        :protocol-version 4
                                                        :clean-session? false :keep-alive 100})
                                    :broker-id dead :incarnation (str dead "-run")))
    ;; And the block it gave identifier 1 out of, as a broker leases it.
    @(cluster/record! *conn* (assoc (cluster/->lease id connect-id 0 [0])
                                    :broker-id dead :incarnation (str dead "-run"))))
  (is (tu/wait-until #(= dead (:broker-id (cluster/session *conn* id))) 10000))
  (is (= dead (:broker-id (cluster/lease *conn* id)))))

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
          seen     (atom [])
          released (atom [])]
      (on-dead-broker! id topic dead)
      (let [c (connect! (client [1] received seen released) id false)]
        (try
          (is (true? (:session-present? (deref (:connack c) 15000 nil))))
          (publish-qos-2! topic "after")
          (tu/wait-until #(seq @seen) 10000)
          (Thread/sleep 1000)
          ;; Released without a message: not the PUBREL of one it was sent.
          {:received @received :seen @seen
           :released (remove (set (map first @seen)) @released)}
          (finally (close! c)))))))

(deftest a-message-after-a-resume-is-not-sent-under-an-identifier-the-client-holds
  (testing "an ordinary topic"
    (let [topic (tu/topic "orphan")
          {:keys [received seen released]} (resumed-holding-1 topic)]
      (is (= ["after"] received)
          (str "sent as " (pr-str seen) "; the client holds identifier 1"))
      (is (= (range 1 (inc h/lease-block)) released) "the dead broker's block, released")
      (is (> (long (ffirst seen)) (long h/lease-block)) "and a block of this broker's own")))
  (testing "an exactly-once topic: the claim takes the identifier the client holds"
    (let [topic (tu/topic "orphan-eo")]
      (with-redefs [h/exactly-once-filters (delay [topic])]
        (let [{:keys [received seen]} (resumed-holding-1 topic)]
          (is (= ["after"] received)
              (str "sent as " (pr-str seen) "; the client holds identifier 1")))))))

(deftest a-session-that-left-with-a-hand-over-is-released-nothing
  ;; Its connection ended here and handed over what it had: the lease says
  ;; so, and the next connection, here or anywhere, has nothing to release.
  (let [id       (tu/client-id "lease-clean")
        topic    (tu/topic "lease-clean")
        released (atom [])
        first-c  (connect! (client [] (atom []) (atom []) released) id false)]
    (is (some? (deref (:connack first-c) 5000 nil)))
    (client/send-message (:client first-c) {:packet-type :SUBSCRIBE :packet-identifier 1
                                            :topics [{:qos 2 :topic-filter topic}]})
    (is (some? (deref (:suback first-c) 5000 nil)))
    (is (tu/wait-until #(some? (:block (cluster/lease *conn* id))) 10000) "leased")
    (close! first-c)
    (is (tu/wait-until #(:released? (cluster/lease *conn* id)) 10000) "released when it left")
    (let [received (atom [])
          seen     (atom [])
          again    (connect! (client [] received seen released) id false)]
      (try
        (is (true? (:session-present? (deref (:connack again) 10000 nil))))
        (publish-qos-2! topic "next")
        (is (tu/wait-until #(= ["next"] @received) 10000))
        (is (= [] (remove (set (map first @seen)) @released))
            "no PUBREL for identifiers nobody holds")
        (finally (close! again))))))
