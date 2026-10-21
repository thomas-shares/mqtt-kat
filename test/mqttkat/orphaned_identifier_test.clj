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

(deftest identifiers-a-broker-alive-may-still-hand-over-are-released-only-once-it-is-gone
  ;; Load run 155447: a session resumed after waiting ten seconds for a
  ;; hand-over that was only slow, Rama being behind, and released the 498
  ;; identifiers the broker it left could still have been handing over;
  ;; a message handed over under one of them would then have been sent
  ;; again, under an identifier the client had just let go. Released only
  ;; once that broker is gone: here, once it refuses every connection.
  (let [gone?    (atom false)
        id       (tu/client-id "orphan-slow")
        topic    (tu/topic "orphan-slow")
        slow     (tu/client-id "peer-slow")
        received (atom [])
        seen     (atom [])
        released (atom [])
        stray    #(remove (set (map first @seen)) @released)]
    (with-redefs [bridge/peer-gone?        (fn [_] @gone?)
                  h/hand-over-wait-millis  500
                  h/identifier-gate-millis 30000]
      (on-dead-broker! id topic slow)
      (let [c (connect! (client [1] received seen released) id false)]
        (try
          (is (true? (:session-present? (deref (:connack c) 15000 nil))))
          (Thread/sleep 2000)
          (is (= [] (stray)) "not while the broker it left may still hand them over")
          (reset! gone? true)
          (is (tu/wait-until #(= h/lease-block (count (stray))) 10000) "released once it is gone")
          (finally (close! c)))))))

(deftest the-next-block-is-leased-before-this-one-runs-out
  ;; Load run 175703: subscribers on `+` used a block of identifiers up in
  ;; well under a second, and then waited, nothing in flight and thousands
  ;; pending, for the next block's lease to land, which with Rama behind
  ;; took up to six seconds. The next is leased once half of this one is
  ;; used, so that it is there when this one runs out.
  (let [id       (tu/client-id "lease-ahead")
        topic    (tu/topic "lease-ahead")
        received (atom [])
        c        (connect! (client [] received (atom [])) id false)
        lease    #(:lease @(@#'h/outbound-atom id))]
    (try
      (is (some? (deref (:connack c) 5000 nil)))
      (client/send-message (:client c) {:packet-type :SUBSCRIBE :packet-identifier 1
                                        :topics [{:qos 1 :topic-filter topic}]})
      (is (some? (deref (:suback c) 5000 nil)))
      (is (tu/wait-until #(some? (:block (lease))) 10000) "leased")
      (let [b    (:block (lease))
            n    (+ (quot h/lease-block 2) 8)
            nxt  (mod (inc (long b)) (quot 65535 (long h/lease-block)))
            pub  (connect! (client [] (atom []) (atom [])) (tu/client-id "lease-ahead-pub") true)]
        (try
          (is (some? (deref (:connack pub) 5000 nil)))
          (dotimes [i n]
            (client/send-message (:client pub) {:packet-type :PUBLISH :topic topic :qos 1
                                                :packet-identifier (inc i)
                                                :payload (.getBytes (str i) "UTF-8")
                                                :retain? false :duplicate? false}))
          (is (tu/wait-until #(= n (count @received)) 10000) "delivered")
          (is (= b (:block (lease))) "still giving out the first block")
          (is (tu/wait-until #(some #{nxt} (concat (:leasing (lease)) (:next (lease)))) 5000)
              (str "the next leased already: " (pr-str (lease))))
          (testing "and given out from once this one is used up"
            (dotimes [i h/lease-block]
              (client/send-message (:client pub) {:packet-type :PUBLISH :topic topic :qos 1
                                                  :packet-identifier (+ n i 1)
                                                  :payload (.getBytes (str (+ n i)) "UTF-8")
                                                  :retain? false :duplicate? false}))
            (is (tu/wait-until #(= (+ n h/lease-block) (count @received)) 10000) "delivered")
            (is (= nxt (:block (lease))) (pr-str (lease))))
          (finally (close! pub))))
      (finally (close! c)))))

(deftest a-subscriber-that-uses-blocks-fast-leases-several-ahead
  ;; Load run 20261021-205056: with the next block leased at half of this
  ;; one, subscribers on `+` still waited for it, nothing in flight and two
  ;; thousand pending. One used a block in about 0.6 s, which left 0.3 s
  ;; for a lease that took up to six. Leased ahead by how fast its blocks
  ;; are used, enough for lease-ahead-millis, in one lease; and every block
  ;; leased ahead is on the cluster's record, for the resume to release.
  (with-redefs [h/lease-ahead-millis 600000]
    (let [id       (tu/client-id "lease-rate")
          topic    (tu/topic "lease-rate")
          received (atom [])
          c        (connect! (client [] received (atom [])) id false)
          lease    #(:lease @(@#'h/outbound-atom id))
          ahead    #(count (concat (:next (lease)) (:leasing (lease))))]
      (try
        (is (some? (deref (:connack c) 5000 nil)))
        (client/send-message (:client c) {:packet-type :SUBSCRIBE :packet-identifier 1
                                          :topics [{:qos 1 :topic-filter topic}]})
        (is (some? (deref (:suback c) 5000 nil)))
        (is (tu/wait-until #(some? (:block (lease))) 10000) "leased")
        (let [n   (* 2 h/lease-block)
              pub (connect! (client [] (atom []) (atom [])) (tu/client-id "lease-rate-pub") true)]
          (try
            (is (some? (deref (:connack pub) 5000 nil)))
            (dotimes [i n]
              (client/send-message (:client pub) {:packet-type :PUBLISH :topic topic :qos 1
                                                  :packet-identifier (inc (mod i 65535))
                                                  :payload (.getBytes (str i) "UTF-8")
                                                  :retain? false :duplicate? false}))
            (is (tu/wait-until #(= n (count @received)) 20000) "delivered")
            (is (tu/wait-until #(= h/max-lease-ahead (ahead)) 5000)
                (str "as many blocks ahead as it may have: " (pr-str (lease))))
            (testing "all on the cluster's record"
              (is (tu/wait-until #(every? (set (:blocks (cluster/lease *conn* id)))
                                          (cons (:block (lease)) (:next (lease))))
                                 10000)
                  (str (pr-str (lease)) " " (pr-str (cluster/lease *conn* id)))))
            (finally (close! pub))))
        (finally (close! c))))))

(deftest a-subscriber-that-uses-blocks-slowly-leases-one-at-a-time
  ;; And one that takes longer over a block than lease-ahead-millis leases
  ;; only the next, as before: a resume after it dies releases fewer.
  (with-redefs [h/lease-ahead-millis 1]
    (let [id       (tu/client-id "lease-slow")
          topic    (tu/topic "lease-slow")
          received (atom [])
          c        (connect! (client [] received (atom [])) id false)
          lease    #(:lease @(@#'h/outbound-atom id))]
      (try
        (is (some? (deref (:connack c) 5000 nil)))
        (client/send-message (:client c) {:packet-type :SUBSCRIBE :packet-identifier 1
                                          :topics [{:qos 1 :topic-filter topic}]})
        (is (some? (deref (:suback c) 5000 nil)))
        (is (tu/wait-until #(some? (:block (lease))) 10000) "leased")
        (let [n   (+ h/lease-block (quot h/lease-block 2) 8)
              pub (connect! (client [] (atom []) (atom [])) (tu/client-id "lease-slow-pub") true)]
          (try
            (is (some? (deref (:connack pub) 5000 nil)))
            (dotimes [i n]
              (client/send-message (:client pub) {:packet-type :PUBLISH :topic topic :qos 1
                                                  :packet-identifier (inc i)
                                                  :payload (.getBytes (str i) "UTF-8")
                                                  :retain? false :duplicate? false}))
            (is (tu/wait-until #(= n (count @received)) 20000) "delivered")
            (is (tu/wait-until #(= 1 (count (concat (:next (lease)) (:leasing (lease))))) 5000)
                (pr-str (lease)))
            (Thread/sleep 500)
            (is (= 1 (count (concat (:next (lease)) (:leasing (lease))))) (pr-str (lease)))
            (finally (close! pub))))
        (finally (close! c))))))
