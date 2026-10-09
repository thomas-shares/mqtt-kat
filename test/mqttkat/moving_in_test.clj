(ns mqttkat.moving-in-test
  "A kept session moving in from a broker that died, while messages keep
   coming: none of them may be lost.

   What the chaos runs (chaos/broker-kill.edn) lost was of this shape. A
   broker is killed; its clients come back on another; the publishers that
   were on it send again what it never acknowledged, in a burst, at the very
   moment the subscribers are moving in. The subscribers that lost them had
   been connected here for 30 to 180 ms, or were about to connect. Not
   portable: it stands in for the other broker through the cluster.

   One broker here, the suite's own, on an in-process Rama; the broker the
   session was on is only a record in the cluster: unlisted, as a broker the
   cluster has let go is, or listed at an address that refuses connections,
   as a killed one is for ten minutes."
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
  "A raw client of the test broker that answers every PUBLISH as its QoS asks
   and keeps the payloads it was handed in `received`, once per QoS 2 packet
   identifier; and, publishing, answers a PUBREC with its PUBREL. Returns {:client :connack :suback}, the last two promises."
  [received]
  (let [connack  (promise)
        suback   (promise)
        held     (atom #{})
        c        (atom nil)
        send!    #(client/send-message @c %)
        on       (fn [msg _]
                   (case (:packet-type msg)
                     :CONNACK (deliver connack msg)
                     :SUBACK  (deliver suback msg)
                     :PUBLISH (let [pid (:packet-identifier msg)
                                    p   (tu/payload-str msg)]
                                (case (long (:qos msg 0))
                                  0 (swap! received conj p)
                                  1 (do (swap! received conj p)
                                        (send! {:packet-type :PUBACK :packet-identifier pid}))
                                  2 (do (when-not (contains? @held pid)
                                          (swap! held conj pid)
                                          (swap! received conj p))
                                        (send! {:packet-type :PUBREC :packet-identifier pid}))))
                     ;; As a publisher: release what the broker has taken.
                     :PUBREC  (send! {:packet-type :PUBREL :packet-identifier (:packet-identifier msg)})
                     :PUBREL  (do (swap! held disj (:packet-identifier msg))
                                  (send! {:packet-type :PUBCOMP :packet-identifier (:packet-identifier msg)}))
                     nil))]
    (reset! c (client/client tu/host tu/port (MqttHandler. ^clojure.lang.IFn on 1)))
    {:client @c :connack connack :suback suback}))

(defn- connect! [c id clean?]
  (client/send-message (:client c) {:packet-type :CONNECT :protocol-name "MQTT" :protocol-version 4
                                    :keep-alive 100 :clean-session? clean? :client-id id})
  c)

(defn- close! [c]
  (try (client/close (:client c)) (catch Exception _ nil)))

(defn- on-peer!
  "Record `id` as connected on broker `peer-id`, which never says otherwise."
  [id peer-id]
  @(cluster/record! *conn* (assoc (cluster/->connect {:client-id id :protocol-version 4
                                                      :clean-session? false :keep-alive 100})
                                  :broker-id peer-id :incarnation (str peer-id "-run"))))

(defn- list-peer!
  "Put `peer-id` in the registry at a port nothing listens on, as a killed
   broker is for the ten minutes before the cluster lets it go."
  [peer-id]
  (let [port (with-open [s (java.net.ServerSocket. 0)] (.getLocalPort s))]
    @(cluster/record! *conn* {:event :broker-up :broker-id peer-id :incarnation (str peer-id "-run")
                              :host "127.0.0.1" :port port :at (System/currentTimeMillis)})
    (tu/wait-until #(contains? (cluster/brokers) peer-id) 10000)))

(defn- stream!
  "Publish `n` messages \"0\" .. \"n-1\" at `qos` to `topic`, one every
   `every-ms`, a third of them with DUP set as a resend's are. Calls
   (at-i i) before each. Returns once all are sent."
  [topic qos n every-ms at-i]
  (let [pub (connect! (client (atom [])) (tu/client-id "mv-pub") true)]
    (try
      (deref (:connack pub) 5000 nil)
      (dotimes [i n]
        (at-i i)
        (client/send-message (:client pub)
                             {:packet-type :PUBLISH :topic topic :qos qos
                              :packet-identifier (inc i)
                              :payload (.getBytes (str i) "UTF-8")
                              :retain? false :duplicate? (zero? (mod i 3))})
        (Thread/sleep (long every-ms)))
      ;; What was sent is acknowledged before the publisher goes, so none of
      ;; it is still in its socket when the check starts.
      (Thread/sleep 500)
      (finally (close! pub)))))

(defn- moving-in
  "A kept session subscribed to `topic`, last on `peer-id`, connecting here
   while a stream of `n` messages at `qos` goes on; what it was handed."
  [peer-id qos n]
  (let [id       (tu/client-id "mover")
        topic    (tu/topic "moving-in")
        received (atom [])
        first    (connect! (client received) id false)]
    (deref (:connack first) 5000 nil)
    (client/send-message (:client first) {:packet-type :SUBSCRIBE :packet-identifier 1
                                          :topics [{:qos qos :topic-filter topic}]})
    (is (some? (deref (:suback first) 5000 nil)))
    (is (tu/wait-until #(seq (cluster/matching-subscriptions *conn* topic)) 10000)
        "the subscription is in the cluster")
    (close! first)
    (is (tu/wait-until #(false? (cluster/connected? *conn* id)) 10000))
    (on-peer! id peer-id)
    (is (tu/wait-until #(= peer-id (:broker-id (cluster/session *conn* id))) 10000)
        "and the session is on the other broker")
    (let [second (atom nil)]
      (try
        (stream! topic qos n 2
                 (fn [i]
                   ;; A third of the way in, the client comes back here.
                   (when (= i (quot n 3))
                     (reset! second (connect! (client received) id false)))))
        (is (true? (:session-present? (deref (:connack @second) 15000 nil))))
        ;; Past the catch-up reads at 2 and 5 s (handlers/catch-up-reads-millis),
        ;; then until nothing more arrives: what those find is late, not lost.
        (Thread/sleep 6000)
        (loop [seen -1]
          (let [now (count @received)]
            (when (not= now seen)
              (Thread/sleep 2000)
              (recur now))))
        @received
        (finally
          (some-> @second close!))))))

(defn- missing [received n]
  (vec (remove (set received) (map str (range n)))))

(deftest a-session-moving-in-from-a-broker-that-is-gone-loses-nothing
  (doseq [qos [1 2]]
    (testing (str "QoS " qos ", from a broker the cluster has let go")
      (let [n 300
            got (moving-in (tu/client-id "peer-gone") qos n)]
        (is (= [] (missing got n)))))
    (testing (str "QoS " qos ", from a broker still listed but not there")
      (let [peer (tu/client-id "peer-dead")
            n    300]
        (list-peer! peer)
        (with-redefs [bridge/refused-for-gone-ms 300]
          (let [got (moving-in peer qos n)]
            (is (= [] (missing got n)))))))))
