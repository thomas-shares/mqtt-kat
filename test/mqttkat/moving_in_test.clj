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
   as a killed one is for ten minutes. A second, live broker is played by the
   test over the bridge: sending this one copies planned at its view, or
   taking the copies this one forwards."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [mqttkat.bridge :as bridge]
            [mqttkat.client :as client]
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
   identifier; and, publishing, answers a PUBREC with its PUBREL. Speaks
   version `version`, 4 unless told. Returns {:client :connack :suback :send!},
   the middle two promises."
  ([received] (client received 4))
  ([received version]
  (let [connack  (promise)
        suback   (promise)
        held     (atom #{})
        c        (atom nil)
        send!    #(client/send-message @c (cond-> % (= 5 version) (assoc :protocol-version 5)))
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
    {:client @c :connack connack :suback suback :send! send!})))

(defn- connect!
  ([c id clean?] (connect! c id clean? 4))
  ([c id clean? version]
   (client/send-message (:client c) (cond-> {:packet-type :CONNECT :protocol-name "MQTT"
                                             :protocol-version version
                                             :keep-alive 100 :clean-session? clean? :client-id id}
                                      (= 5 version) (assoc :properties {:session-expiry-interval 3600})))
   c))

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

;; ── two brokers ───────────────────────────────────────────────────────────
;;
;; The broker keeps its state in globals, so a second one cannot run in this
;; JVM. The test plays the other broker instead, over the bridge, as
;; rama-test does: as the one that sends this broker its copies, and as the
;; one a subscriber resumes on while publishes enter here.

(defn- parked-elsewhere!
  "A kept session `id`, subscribed here to `topic` at `qos`, then gone, and
   recorded as connected on `peer-id`, which is listed and refuses."
  [id topic qos peer-id]
  (let [c (connect! (client (atom [])) id false)]
    (deref (:connack c) 5000 nil)
    (client/send-message (:client c) {:packet-type :SUBSCRIBE :packet-identifier 1
                                      :topics [{:qos qos :topic-filter topic}]})
    (is (some? (deref (:suback c) 5000 nil)))
    (is (tu/wait-until #(seq (cluster/matching-subscriptions *conn* topic)) 10000))
    (close! c)
    (is (tu/wait-until #(false? (cluster/connected? *conn* id)) 10000))
    (list-peer! peer-id)
    (on-peer! id peer-id)
    (is (tu/wait-until #(= peer-id (:broker-id (cluster/session *conn* id))) 10000))))

(defn- drain! [received]
  (Thread/sleep 6000)
  (loop [seen -1]
    (let [now (count @received)]
      (when (not= now seen)
        (Thread/sleep 2000)
        (recur now)))))

(deftest copies-from-another-broker-for-a-session-moving-in-here
  ;; The other broker publishes. While its view has the client on the dead
  ;; broker it queues each copy on the cluster itself; once the client's
  ;; CONNECT here reaches its view, it sends the copies here, planned at
  ;; that view, while the client is still waiting on the dead broker's
  ;; hand-over and its session is not live here yet.
  (doseq [qos [1 2]]
    (testing (str "QoS " qos)
      (with-redefs [bridge/refused-for-gone-ms 300]
        (let [id       (tu/client-id "mover-in")
              topic    (tu/topic "copies-in")
              dead     (tu/client-id "peer-dead")
              me       cluster/broker-id
              n        300
              switch   (quot n 3)
              received (atom [])
              _        (parked-elsewhere! id topic qos dead)
              y        (connect! (client (atom []) 5) (str bridge/client-id-prefix (tu/client-id "peer-y")) true 5)
              view!    (fn [v where]
                         ((:send! y) {:packet-type :PUBLISH :topic bridge/view-topic :qos 0
                                      :payload (.getBytes (pr-str {:v v :snapshot? true
                                                                   :clients {id {topic [qos where]}}})
                                                          "UTF-8")
                                      :retain? false :duplicate? false :properties {}}))
              mover    (atom nil)]
          (deref (:connack y) 5000 nil)
          (view! 1 dead)
          (try
            (dotimes [i n]
              (when (= i switch)
                (reset! mover (connect! (client received) id false))
                (view! 2 me))
              (let [k   (str (System/currentTimeMillis) "-" (java.util.UUID/randomUUID))
                    msg {:topic topic :qos qos :payload (.getBytes (str i) "UTF-8")}]
                (if (< i switch)
                  (cluster/record! *conn* (cluster/->enqueue id msg k))
                  ((:send! y) (assoc msg :packet-type :PUBLISH :packet-identifier (inc i)
                                     :retain? false :duplicate? false
                                     :properties {:user-properties [[bridge/msg-key-property k]
                                                                    [bridge/view-v-property "2"]]}))))
              (Thread/sleep 2))
            (is (true? (:session-present? (deref (:connack @mover) 15000 nil))))
            (drain! received)
            (is (= [] (missing @received n)))
            (finally
              (some-> @mover close!)
              (close! y))))))))

(defn- fake-peer
  "A broker this one bridges to, which takes every copy, acknowledges it as
   its QoS asks and keeps its payload: {:port :received :stop}."
  []
  (let [received (atom [])
        server   (atom nil)
        answer   (fn [k m] (.sendMessageBuffer ^org.mqttkat.server.MqttServer @server [k] m))
        handler  (MqttHandler.
                  ^clojure.lang.IFn
                  (fn [{:keys [packet-type client-key packet-identifier qos topic] :as msg} _]
                    (case packet-type
                      :CONNECT (answer client-key (org.mqttkat.packages.MqttConnAck/encode
                                                   {:packet-type :CONNACK :protocol-version 5
                                                    :session-present? false :reason-code 0
                                                    :properties {:receive-maximum 64}}))
                      :PUBLISH (do (when (not= bridge/view-topic topic)
                                     (swap! received conj (tu/payload-str msg)))
                                   (case (long (or qos 0))
                                     1 (answer client-key (org.mqttkat.packages.MqttPubAck/encode
                                                           {:packet-type :PUBACK :protocol-version 5
                                                            :packet-identifier packet-identifier :reason-code 0}))
                                     2 (answer client-key (org.mqttkat.packages.MqttPubRec/encode
                                                           {:packet-type :PUBREC :protocol-version 5
                                                            :packet-identifier packet-identifier :reason-code 0}))
                                     nil))
                      :PUBREL (answer client-key (org.mqttkat.packages.MqttPubComp/encode
                                                  {:packet-type :PUBCOMP :protocol-version 5
                                                   :packet-identifier packet-identifier :reason-code 0}))
                      nil))
                  1)
        s        (doto (org.mqttkat.server.MqttServer. "127.0.0.1" 0 handler) (.start))]
    (reset! server s)
    {:port (.getPort s) :received received :stop #(.stop s 100)}))

(deftest publishes-here-for-a-session-moving-to-another-broker
  ;; Publishes enter here while the client moves from the dead broker to a
  ;; live one. Each must go to the live one or onto the client's queue on
  ;; the cluster, for its next read: anywhere else it is lost.
  (doseq [qos [1 2]]
    (testing (str "QoS " qos)
      (let [id     (tu/client-id "mover-out")
            topic  (tu/topic "copies-out")
            dead   (tu/client-id "peer-dead")
            live   (tu/client-id "peer-live")
            n      300
            peer   (fake-peer)]
        (parked-elsewhere! id topic qos dead)
        @(cluster/record! *conn* {:event :broker-up :broker-id live :incarnation (str live "-run")
                                  :host "127.0.0.1" :port (:port peer) :at (System/currentTimeMillis)})
        (is (tu/wait-until #(contains? (cluster/brokers) live) 10000))
        (try
          (stream! topic qos n 2
                   (fn [i] (when (= i (quot n 3))
                             ;; Recorded as Rama records it: on its way, not waited for.
                             (cluster/record! *conn* (assoc (cluster/->connect {:client-id id :protocol-version 4
                                                                                :clean-session? false
                                                                                :keep-alive 100})
                                                            :broker-id live :incarnation (str live "-run"))))))
          ;; Until the queue writes have landed.
          (Thread/sleep 6000)
          (let [queued (map (comp tu/payload-str second) (cluster/queued *conn* id))
                sent   @(:received peer)]
            (is (= [] (missing (concat queued sent) n))
                (str (count sent) " sent to the live broker, " (count queued) " queued"))
            (is (seq sent) "some went to the live broker")
            (is (seq queued) "and some, from before it had the client, on the queue"))
          (finally
            ((:stop peer))
            (bridge/drop! live)))))))
