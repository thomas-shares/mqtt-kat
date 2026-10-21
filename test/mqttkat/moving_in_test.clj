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

(deftest a-pubrel-for-a-hold-the-cluster-has-not-processed-yet-waits-for-it
  ;; A race the simulation found (thoughts.md, 20261020). The broker that
  ;; took a QoS 2 publish held it at :append-ack, sent the PUBREC, and
  ;; died; the PUBREL came here, and the reads (held-read-millis) found
  ;; nothing, the topology being further behind than they waited. Answered
  ;; with a PUBCOMP alone, the message was published nowhere. Here the
  ;; topology is stood in for as behind: the hold is not read until a
  ;; record appended to the publisher's partition after it has landed,
  ;; which is what Rama's order on a partition promises.
  (let [topic    (tu/topic "pubrel-behind")
        received (atom [])
        sub      (connect! (client received) (tu/client-id "behind-sub") true)
        pub-id   (tu/client-id "behind-pub")
        raw      (tu/client! 16 true)
        caught-up (atom false)
        source   @h/session-source]
    (try
      (deref (:connack sub) 5000 nil)
      (client/send-message (:client sub) {:packet-type :SUBSCRIBE :packet-identifier 1
                                          :topics [{:qos 2 :topic-filter topic}]})
      (deref (:suback sub) 5000 nil)
      (client/send-message (:client raw) {:packet-type :CONNECT :protocol-name "MQTT" :protocol-version 4
                                          :keep-alive 100 :clean-session? false :client-id pub-id})
      (tu/expect! (:ch raw) :CONNACK 5000)
      (client/send-message (:client raw) {:packet-type :PUBLISH :topic topic :qos 2 :packet-identifier 7
                                          :payload (.getBytes "held, not yet seen" "UTF-8")
                                          :retain? false :duplicate? false})
      (tu/expect! (:ch raw) :PUBREC 5000)
      (is (tu/wait-until #(some? (cluster/held *conn* pub-id 7)) 5000))
      ;; The broker that took it is gone; the topology, behind, does not
      ;; show its hold until whatever is appended after it has landed.
      (swap! h/*inflight* dissoc [pub-id 7])
      (reset! h/session-source
              (cond-> (assoc source :held (fn [c pid] (when @caught-up ((:held source) c pid))))
                (:barrier! source)
                (assoc :barrier! (fn [c]
                                   (doto ^java.util.concurrent.CompletableFuture ((:barrier! source) c)
                                     (.thenRun ^Runnable (fn [] (reset! caught-up true))))))))
      (with-redefs [h/held-read-millis [0 100]]
        (client/send-message (:client raw) {:packet-type :PUBREL :packet-identifier 7})
        (tu/expect! (:ch raw) :PUBCOMP 10000))
      (is (tu/wait-until #(= ["held, not yet seen"] @received) 5000)
          "acknowledged with a PUBCOMP, so it must have been published")
      (finally
        (reset! h/session-source source)
        (close! sub)
        (try (client/close (:client raw)) (catch Exception _ nil))))))

(deftest a-pubrel-for-a-publish-another-broker-took-delivers-it
  ;; A QoS 2 publish is held where it was received until its PUBREL
  ;; (§4.3.3). When that broker goes after its PUBREC, the publisher sends
  ;; only the PUBREL again (§4.4), to whichever broker it reconnects to. The
  ;; chaos runs lost each such message for every subscriber: acknowledged
  ;; with a PUBCOMP, published nowhere. Here the broker that took it is
  ;; stood in for by forgetting what this one holds for the publisher.
  (let [topic    (tu/topic "pubrel-elsewhere")
        received (atom [])
        sub      (connect! (client received) (tu/client-id "rel-sub") true)
        pub-id   (tu/client-id "rel-pub")
        raw      (tu/client! 16 true)]
    (try
      (deref (:connack sub) 5000 nil)
      (client/send-message (:client sub) {:packet-type :SUBSCRIBE :packet-identifier 1
                                          :topics [{:qos 2 :topic-filter topic}]})
      (deref (:suback sub) 5000 nil)
      (client/send-message (:client raw) {:packet-type :CONNECT :protocol-name "MQTT" :protocol-version 4
                                          :keep-alive 100 :clean-session? false :client-id pub-id})
      (tu/expect! (:ch raw) :CONNACK 5000)
      (client/send-message (:client raw) {:packet-type :PUBLISH :topic topic :qos 2 :packet-identifier 7
                                          :payload (.getBytes "taken elsewhere" "UTF-8")
                                          :retain? false :duplicate? false})
      (tu/expect! (:ch raw) :PUBREC 5000)
      ;; The broker that took it is gone, and what it held with it.
      (swap! h/*inflight* dissoc [pub-id 7])
      (client/send-message (:client raw) {:packet-type :PUBREL :packet-identifier 7})
      (tu/expect! (:ch raw) :PUBCOMP 5000)
      (is (tu/wait-until #(= ["taken elsewhere"] @received) 5000)
          "acknowledged with a PUBCOMP, so it must have been published")
      (is (tu/wait-until #(nil? (cluster/held *conn* pub-id 7)) 5000)
          "and the cluster holds it no longer")
      (testing "a PUBLISH sent again to a broker that never had it is that message, not a second"
        (client/send-message (:client raw) {:packet-type :PUBLISH :topic topic :qos 2 :packet-identifier 8
                                            :payload (.getBytes "sent twice" "UTF-8")
                                            :retain? false :duplicate? false})
        (tu/expect! (:ch raw) :PUBREC 5000)
        (is (tu/wait-until #(some? (cluster/held *conn* pub-id 8)) 5000))
        (swap! h/*inflight* dissoc [pub-id 8])
        (client/send-message (:client raw) {:packet-type :PUBLISH :topic topic :qos 2 :packet-identifier 8
                                            :payload (.getBytes "sent twice" "UTF-8")
                                            :retain? false :duplicate? true})
        (tu/expect! (:ch raw) :PUBREC 5000)
        (client/send-message (:client raw) {:packet-type :PUBREL :packet-identifier 8})
        (tu/expect! (:ch raw) :PUBCOMP 5000)
        (testing "and a PUBREL sent again for it is answered, not published again"
          (client/send-message (:client raw) {:packet-type :PUBREL :packet-identifier 8})
          (tu/expect! (:ch raw) :PUBCOMP 6000))
        (Thread/sleep 500)
        (is (= ["taken elsewhere" "sent twice"] @received) "published once")
        (is (tu/wait-until #(nil? (cluster/held *conn* pub-id 8)) 5000)))
      (testing "a PUBREL for one already being released is answered at once"
        ;; The first release may be waiting on a write that is slow to land;
        ;; the publisher's PUBREL sent again is not left unanswered meanwhile.
        (swap! h/*inflight* assoc [pub-id 10] {::h/releasing? true})
        (try
          (client/send-message (:client raw) {:packet-type :PUBREL :packet-identifier 10})
          (tu/expect! (:ch raw) :PUBCOMP 1000)
          (finally (swap! h/*inflight* dissoc [pub-id 10]))))
      (testing "a session that starts afresh lets go of what it held"
        (client/send-message (:client raw) {:packet-type :PUBLISH :topic topic :qos 2 :packet-identifier 9
                                            :payload (.getBytes "never released" "UTF-8")
                                            :retain? false :duplicate? false})
        (tu/expect! (:ch raw) :PUBREC 5000)
        (is (tu/wait-until #(some? (cluster/held *conn* pub-id 9)) 5000))
        (tu/close! raw)
        (let [clean (tu/connect! "rel-pub" :id pub-id :clean-session? true)]
          (try
            (is (tu/wait-until #(nil? (cluster/held *conn* pub-id 9)) 5000))
            (finally (tu/close! clean)))))
      (finally
        (tu/close! raw)
        (close! sub)))))

(deftest a-subscriber-behind-does-not-hold-up-the-bridge
  ;; A kept session that falls behind on copies from another broker: past
  ;; pause-threshold they go to its queue on the cluster rather than
  ;; pausing the bridge, which would hold back every subscriber behind it
  ;; and leave the backlog on the other broker (handlers/overflow?). Read
  ;; back once it has caught up.
  (with-redefs [h/pause-threshold 3 h/resume-threshold 1]
    (let [id       (tu/client-id "behind")
          topic    (tu/topic "behind")
          n        20
          got      (atom [])
          acking?  (atom false)
          sub-c    (atom nil)
          ack!     #(client/send-message @sub-c {:packet-type :PUBACK :packet-identifier %
                                                 :protocol-version 5})
          unacked  (atom [])
          connack  (promise)
          suback   (promise)
          _        (reset! sub-c (client/client
                                  tu/host tu/port
                                  (MqttHandler.
                                   ^clojure.lang.IFn
                                   (fn [msg _]
                                     (case (:packet-type msg)
                                       :CONNACK (deliver connack msg)
                                       :SUBACK  (deliver suback msg)
                                       :PUBLISH (do (swap! got conj (tu/payload-str msg))
                                                    (if @acking?
                                                      (ack! (:packet-identifier msg))
                                                      (swap! unacked conj (:packet-identifier msg))))
                                       nil))
                                   1)))
          pubacks  (atom 0)
          y-c      (atom nil)
          y-ack    (promise)]
      (try
        (client/send-message @sub-c {:packet-type :CONNECT :protocol-name "MQTT" :protocol-version 5
                                     :keep-alive 100 :clean-session? false :client-id id
                                     :properties {:session-expiry-interval 3600 :receive-maximum 1}})
        (is (some? (deref connack 5000 nil)))
        (client/send-message @sub-c {:packet-type :SUBSCRIBE :packet-identifier 1 :protocol-version 5
                                     :topics [{:qos 1 :topic-filter topic}]})
        (is (some? (deref suback 5000 nil)))
        (reset! y-c (client/client
                     tu/host tu/port
                     (MqttHandler.
                      ^clojure.lang.IFn
                      (fn [msg _]
                        (case (:packet-type msg)
                          :CONNACK (deliver y-ack msg)
                          :PUBACK  (swap! pubacks inc)
                          nil))
                      1)))
        (client/send-message @y-c {:packet-type :CONNECT :protocol-name "MQTT" :protocol-version 5
                                   :keep-alive 100 :clean-session? true
                                   :client-id (str bridge/client-id-prefix (tu/client-id "peer-o"))})
        (is (some? (deref y-ack 5000 nil)))
        (dotimes [i n]
          (client/send-message @y-c {:packet-type :PUBLISH :protocol-version 5 :topic topic :qos 1
                                     :packet-identifier (inc i) :retain? false :duplicate? false
                                     :payload (.getBytes (str i) "UTF-8")
                                     :properties {:user-properties
                                                  [[bridge/msg-key-property (h/new-message-key)]]}}))
        (is (tu/wait-until #(= n @pubacks) 10000)
            "every copy is taken: the bridge is not held up by the one subscriber")
        (is (= 1 (count @got)) "which has had one, its window")
        ;; One more, now that the subscriber is behind: a bridge paused for
        ;; it would not be read, and this would never be taken.
        (client/send-message @y-c {:packet-type :PUBLISH :protocol-version 5 :topic topic :qos 1
                                   :packet-identifier (inc n) :retain? false :duplicate? false
                                   :payload (.getBytes (str n) "UTF-8")
                                   :properties {:user-properties
                                                [[bridge/msg-key-property (h/new-message-key)]]}})
        (is (tu/wait-until #(= (inc n) @pubacks) 5000)
            "and a copy after it is read and taken as well")
        (is (tu/wait-until #(<= (- n 1 3) (count (cluster/queued *conn* id))) 10000)
            "and has the rest past the threshold on its queue in the cluster")
        (reset! acking? true)
        (run! ack! @unacked)
        (is (tu/wait-until #(= (set (map str (range (inc n)))) (set @got)) 15000)
            "all of which it is sent once it catches up")
        (finally
          (try (client/close @sub-c) (catch Exception _ nil))
          (try (client/close @y-c) (catch Exception _ nil)))))))

(deftest a-reconnect-during-a-read-does-not-stop-the-reads-after-it
  ;; A read under way, or scheduled by a nudge, for the connection before
  ;; this one: it is never ended or begun as that connection's, and the
  ;; reads for this one waited on it for ever. A message put on the queue
  ;; now must still be read and sent.
  (let [id       (tu/client-id "rewired")
        topic    (tu/topic "rewired")
        received (atom [])
        c        (connect! (client received) id false)]
    (try
      (is (some? (deref (:connack c) 5000 nil)))
      ((:send! c) {:packet-type :SUBSCRIBE :packet-identifier 1 :topics [{:qos 1 :topic-filter topic}]})
      (is (some? (deref (:suback c) 5000 nil)))
      (let [connect-id (get-in @h/*clients* [(h/live-connection id) :connect-id])]
        (swap! @#'h/catching-up assoc id
               {:connect-id "the-connection-before" :had {} :reading? true :scheduled? true})
        (h/catch-up! id connect-id))
      @(cluster/enqueue! *conn* id {:topic topic :qos 1 :payload (.getBytes "queued" "UTF-8")}
                         (h/new-message-key))
      (is (tu/wait-until #(= ["queued"] @received) 10000) "read and sent")
      (finally (close! c)))))

(deftest a-message-completed-from-the-queue-is-not-sent-again-on-a-quick-resume
  ;; Load run 113118 delivered 80 QoS 2 messages twice to one kept session
  ;; at the run's end, nothing killed. A message from the cluster's queue
  ;; comes off it when the client completes it, and the take-off can take
  ;; seconds to land with Rama behind. The connection keeps the keys
  ;; whose take-off has not landed (`had` in catching-up), so its reads
  ;; leave them alone; the next connection, here at once, did not, and the
  ;; resume read the queue with the message still on it. Here the take-off
  ;; never lands, which is what a slow one looks like until it does.
  (let [id       (tu/client-id "quick-resume")
        topic    (tu/topic "quick-resume")
        received (atom [])]
    (let [c (connect! (client (atom [])) id false)]
      (deref (:connack c) 5000 nil)
      (client/send-message (:client c) {:packet-type :SUBSCRIBE :packet-identifier 1
                                        :topics [{:qos 2 :topic-filter topic}]})
      (is (some? (deref (:suback c) 5000 nil)))
      (close! c)
      (is (tu/wait-until #(false? (cluster/connected? *conn* id)) 10000)))
    (stream! topic 2 1 0 (constantly nil))
    (is (tu/wait-until #(= 1 (count (cluster/queued *conn* id))) 10000) "queued while away")
    (with-redefs [cluster/dequeue! (fn [& _] (java.util.concurrent.CompletableFuture.))]
      (let [c (connect! (client received) id false)]
        (is (true? (:session-present? (deref (:connack c) 10000 nil))))
        (is (tu/wait-until #(= ["0"] @received) 10000) "delivered, and completed")
        (Thread/sleep 500)
        (close! c))
      (is (tu/wait-until #(false? (cluster/connected? *conn* id)) 10000))
      ;; A new client object: it holds no identifier, having completed it.
      (let [c (connect! (client received) id false)]
        (try
          (is (true? (:session-present? (deref (:connack c) 10000 nil))))
          (Thread/sleep 3000)
          (is (= ["0"] @received) "not delivered again")
          (finally (close! c)))))))

(deftest an-exactly-once-message-before-the-connack-waits-on-the-cluster
  ;; The simulation found it (thoughts.md, 20261021). Between a kept
  ;; session's CONNECT and its CONNACK, which waits for the cluster to have
  ;; it connected and so for as long as Rama is behind, its messages wait
  ;; in this broker's memory. An exactly-once one waited there with its
  ;; publisher told it was done, and a broker that died before the CONNACK
  ;; took it with it. Here the connect is held from landing, and the
  ;; message published meanwhile must be on the cluster's queue.
  (let [id       (tu/client-id "before-connack")
        topic    (tu/topic "before-connack")
        received (atom [])
        entered  (promise)
        release  (java.util.concurrent.CompletableFuture.)
        record   cluster/record-until-landed!]
    (with-redefs [h/exactly-once-filters (delay [topic])]
      (let [c (connect! (client (atom [])) id false)]
        (deref (:connack c) 5000 nil)
        (client/send-message (:client c) {:packet-type :SUBSCRIBE :packet-identifier 1
                                          :topics [{:qos 2 :topic-filter topic}]})
        (is (some? (deref (:suback c) 5000 nil)))
        (close! c)
        (is (tu/wait-until #(false? (cluster/connected? *conn* id)) 10000)))
      (with-redefs [cluster/record-until-landed!
                    (fn [conn rec opts]
                      (let [^java.util.concurrent.CompletableFuture f (record conn rec opts)]
                        (if (and (= :connect (:event rec)) (= id (:client-id rec)))
                          (do (deliver entered true)
                              (.thenCombine f release (reify java.util.function.BiFunction
                                                        (apply [_ a _] a))))
                          f)))]
        (let [c (connect! (client received) id false)]
          (try
            (is (deref entered 5000 false) "the connect is being recorded")
            (stream! topic 2 1 0 (constantly nil))
            (is (= 0 (h/pending-count id)) "not waiting in this broker's memory")
            (is (= ["0"] (map (comp tu/payload-str second) (cluster/queued *conn* id)))
                "on the cluster's queue")
            (.complete release nil)
            (is (true? (:session-present? (deref (:connack c) 10000 nil))))
            (is (tu/wait-until #(= ["0"] @received) 10000) "and delivered once the session is here")
            (Thread/sleep 1000)
            (is (= ["0"] @received) "once")
            (finally
              (.complete release nil)
              (close! c))))))))

(deftest a-queue-is-read-even-when-no-nudge-comes
  ;; Load run 155447 ended with 78,458 messages on the queues of clients
  ;; that were connected. Their reads had stopped: the catch-up reads after
  ;; a resume end after a minute, and after that only a nudge reads the
  ;; queue, which comes when a write lands for a client the cluster has
  ;; connected here. With Rama behind, writes landed minutes late, and a
  ;; connect that never landed left nudges going nowhere. Here the nudge is
  ;; lost outright, after the catch-up reads are over.
  (with-redefs [h/safety-read-millis   1000
                h/catch-up-reads-millis [100]
                h/nudged!              (fn [_] nil)]
    (let [id       (tu/client-id "no-nudge")
          topic    (tu/topic "no-nudge")
          received (atom [])
          c        (connect! (client received) id false)]
      (try
        (deref (:connack c) 5000 nil)
        (client/send-message (:client c) {:packet-type :SUBSCRIBE :packet-identifier 1
                                          :topics [{:qos 1 :topic-filter topic}]})
        (is (some? (deref (:suback c) 5000 nil)))
        (Thread/sleep 1000)
        ;; Queued as a broker that took it for away queues it.
        @(cluster/record! *conn* (cluster/->enqueue id {:topic topic :qos 1
                                                        :payload (.getBytes "late" "UTF-8")}
                                                    (h/new-message-key)))
        (is (tu/wait-until #(= ["late"] @received) 10000) "read without a nudge")
        (finally (close! c))))))

(deftest reads-that-take-nothing-are-said-once-they-go-on
  ;; One read that takes nothing is a take-off not landed yet; five in a
  ;; row is what load runs 155447 and 165325 ended with, unexplained.
  (let [id    (tu/client-id "left-alone")
        state #(get @@#'h/left-alone id)]
    (dotimes [_ (dec h/left-alone-reads)] (#'h/left-alone! id {:had 3}))
    (is (nil? (:logged-at (state))) "not yet")
    (#'h/left-alone! id {:had 3})
    (is (some? (:logged-at (state))) "said")
    (#'h/left-alone! id nil)
    (is (nil? (state)) "and forgotten once a read takes some")))
