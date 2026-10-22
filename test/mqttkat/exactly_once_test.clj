(ns mqttkat.exactly-once-test
  "QoS 2 delivered exactly once on the topics named for it
   (mqttkat.handlers/exactly-once-filters): each delivery to a kept session
   claimed on the cluster, under its packet identifier, before it is sent;
   released there on the PUBREC, before the PUBREL; off the queue on the
   PUBCOMP. Not portable: what it asserts is the cluster's record, which
   another broker does not have, and the choice to keep one is this
   broker's."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
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

(deftest the-topics-delivered-exactly-once-are-the-filters-named
  (is (= ["a/#" "b/+/c"] (h/parse-topic-filters " a/# , ,b/+/c,")))
  (is (nil? (h/parse-topic-filters nil)))
  (is (nil? (h/parse-topic-filters " , ")))
  (let [fs ["a/#" "b/+/c"]]
    (is (h/exactly-once-topic? fs "a"))
    (is (h/exactly-once-topic? fs "a/x/y"))
    (is (h/exactly-once-topic? fs "b/x/c"))
    (is (not (h/exactly-once-topic? fs "b/x/d")))
    (is (not (h/exactly-once-topic? fs "c")))
    (is (not (h/exactly-once-topic? nil "a")))))

(defn- entry [client-id k] (cluster/queued-entry *conn* client-id k))

(deftest a-claim-is-taken-only-for-the-identifier-it-was-first-taken-for
  (let [c   (tu/client-id "claim")
        k   (h/new-message-key)
        msg {:topic "eo/x" :qos 2 :payload (.getBytes "p" "UTF-8")}]
    @(cluster/claim! *conn* c k (assoc msg :packet-identifier 5))
    (is (= 5 (:packet-identifier (entry c k))) "taken")
    @(cluster/claim! *conn* c k (assoc msg :packet-identifier 6))
    (is (= 5 (:packet-identifier (entry c k))) "another identifier is refused")
    @(cluster/claim! *conn* c k (assoc msg :packet-identifier 5 :released? true))
    (is (:released? (entry c k)) "the same identifier, released, is taken")
    @(cluster/claim! *conn* c k (assoc msg :packet-identifier 5))
    (is (:released? (entry c k)) "and not undone")
    (testing "a message queued under the key does not replace the one in flight"
      @(cluster/enqueue! *conn* c msg k)
      (is (= 5 (:packet-identifier (entry c k))))
      (is (:released? (entry c k))))
    @(cluster/dequeue! *conn* c [k])
    (is (nil? (entry c k)))
    @(cluster/claim! *conn* c k (assoc msg :packet-identifier 7))
    (is (nil? (entry c k)) "nor taken once the client has completed it")))

(deftest a-hand-over-does-not-move-a-claimed-delivery-to-another-identifier
  ;; A race the simulation found (thoughts.md, 20261020): a delivery
  ;; claimed under 207 is sent from pending again and claimed under 267,
  ;; which the cluster refuses; the client drops before the refusal comes
  ;; back, and the hand-over put the message on the queue under 267. That
  ;; replaced 207, under which the client had it, and the next resume sent
  ;; it again under 267, a new message to the client.
  (let [c   (tu/client-id "claim-moved")
        k   (h/new-message-key)
        msg {:topic "eo/x" :qos 2 :payload (.getBytes "p" "UTF-8")}]
    @(cluster/claim! *conn* c k (assoc msg :packet-identifier 207))
    @(cluster/claim! *conn* c k (assoc msg :packet-identifier 267))
    @(cluster/enqueue! *conn* c (assoc msg :packet-identifier 267) k)
    (is (= 207 (:packet-identifier (entry c k))) "kept under the identifier claimed")
    (testing "the hand-over of the same identifier, released, still goes on"
      @(cluster/enqueue! *conn* c (assoc msg :packet-identifier 207 :released? true) k)
      (is (:released? (entry c k)))
      @(cluster/enqueue! *conn* c (assoc msg :packet-identifier 300 :released? true) k)
      (is (= 207 (:packet-identifier (entry c k))) "and stays claimed"))))

(deftest a-delivery-no-claim-put-on-the-queue-may-move-to-another-identifier
  ;; A topic not delivered exactly once: restored under an identifier in
  ;; use here, a message is sent under a new one, which the client then
  ;; holds; its hand-over under that one replaces the old.
  (let [c   (tu/client-id "handed-moved")
        k   (h/new-message-key)
        msg {:topic "t/x" :qos 2 :payload (.getBytes "p" "UTF-8")}]
    @(cluster/enqueue! *conn* c (assoc msg :packet-identifier 5) k)
    @(cluster/enqueue! *conn* c (assoc msg :packet-identifier 6) k)
    (is (= 6 (:packet-identifier (entry c k))))))

(defn- subscriber
  "A raw client that keeps every PUBLISH it is sent in `publishes` and every
   PUBREL in `releases`, and answers nothing by itself."
  [publishes releases]
  (let [connack (promise)
        suback  (promise)
        c       (atom nil)
        on      (fn [msg _]
                  (case (:packet-type msg)
                    :CONNACK (deliver connack msg)
                    :SUBACK  (deliver suback msg)
                    :PUBLISH (swap! publishes conj msg)
                    :PUBREL  (swap! releases conj (:packet-identifier msg))
                    nil))]
    (reset! c (client/client tu/host tu/port (MqttHandler. ^clojure.lang.IFn on 1)))
    {:client @c :connack connack :suback suback}))

(defn- publish-qos-2! [topic payload]
  (let [pubcomp (promise)
        c       (atom nil)
        on      (fn [msg _]
                  (case (:packet-type msg)
                    :PUBREC  (client/send-message @c {:packet-type :PUBREL
                                                      :packet-identifier (:packet-identifier msg)})
                    :PUBCOMP (deliver pubcomp msg)
                    nil))]
    (reset! c (client/client tu/host tu/port (MqttHandler. ^clojure.lang.IFn on 1)))
    (try
      (client/send-message @c {:packet-type :CONNECT :protocol-name "MQTT" :protocol-version 4
                               :keep-alive 100 :clean-session? true
                               :client-id (tu/client-id "eo-pub")})
      (client/send-message @c {:packet-type :PUBLISH :topic topic :qos 2 :packet-identifier 1
                               :payload (.getBytes ^String payload "UTF-8")
                               :retain? false :duplicate? false})
      (deref pubcomp 10000 nil)
      (finally (try (client/close @c) (catch Exception _ nil))))))

(defn- delivered
  "Deliver one QoS 2 message on `topic`, matched by `filters`, to a kept
   session that answers it by hand; what the cluster's queue held for it at
   each step."
  [filters topic]
  (with-redefs [h/exactly-once-filters (delay filters)]
    (let [id        (tu/client-id "eo-sub")
          publishes (atom [])
          releases  (atom [])
          sub       (subscriber publishes releases)
          send!     #(client/send-message (:client sub) %)
          queue     #(cluster/queued *conn* id)]
      (try
        (send! {:packet-type :CONNECT :protocol-name "MQTT" :protocol-version 4
                :keep-alive 100 :clean-session? false :client-id id})
        (is (some? (deref (:connack sub) 5000 nil)))
        (send! {:packet-type :SUBSCRIBE :packet-identifier 1 :topics [{:qos 2 :topic-filter topic}]})
        (is (some? (deref (:suback sub) 5000 nil)))
        (is (tu/wait-until #(seq (cluster/matching-subscriptions *conn* topic)) 10000))
        (is (some? (publish-qos-2! topic "once")))
        (is (tu/wait-until #(seq @publishes) 10000) "the subscriber is sent it")
        (let [pid      (:packet-identifier (first @publishes))
              sent     (queue)
              _        (send! {:packet-type :PUBREC :packet-identifier pid})
              _        (tu/wait-until #(seq @releases) 10000)
              released (queue)
              _        (send! {:packet-type :PUBCOMP :packet-identifier pid})
              _        (Thread/sleep 1500)]
          {:pid pid :publishes @publishes :releases @releases
           :sent sent :released released :completed (queue)})
        (finally (try (client/close (:client sub)) (catch Exception _ nil)))))))

(deftest a-delivery-on-a-topic-delivered-exactly-once-is-claimed-first
  (let [topic (tu/topic "eo")
        {:keys [pid publishes releases sent released completed]} (delivered [topic] topic)]
    (is (= 1 (count publishes)))
    (is (= [pid] releases))
    (testing "in flight, the cluster has it under its identifier"
      (is (= 1 (count sent)))
      (is (= pid (:packet-identifier (second (first sent)))))
      (is (not (:released? (second (first sent))))))
    (testing "the PUBREL goes once the cluster has the PUBREC"
      (is (= 1 (count released)))
      (is (= pid (:packet-identifier (second (first released)))))
      (is (:released? (second (first released)))))
    (testing "and the PUBCOMP takes it off"
      (is (empty? completed)))))

(deftest a-claim-or-release-that-fails-is-made-again-before-anything-is-sent
  ;; The first claim and the first release fail, as an append does that
  ;; times out with Rama behind: neither the message nor the PUBREL may go
  ;; until the one made again has landed.
  (let [topic    (tu/topic "eo")
        real     (:claim! @h/session-source)
        failures (atom {:claim 1 :release 1})
        attempts (atom [])]
    (swap! h/session-source assoc :claim!
           (fn [client-id k msg]
             (let [kind (if (:released? msg) :release :claim)]
               (swap! attempts conj kind)
               (if (pos? (long (get @failures kind)))
                 (do (swap! failures update kind dec)
                     (java.util.concurrent.CompletableFuture/failedFuture
                      (ex-info "ack-timeout" {})))
                 (real client-id k msg)))))
    (try
      (let [{:keys [pid publishes releases sent released completed]} (delivered [topic] topic)]
        (is (= [:claim :claim :release :release] @attempts))
        (is (= 1 (count publishes)))
        (is (= [pid] releases))
        (is (= pid (:packet-identifier (second (first sent)))))
        (is (:released? (second (first released))))
        (is (empty? completed)))
      (finally (swap! h/session-source assoc :claim! real)))))

(deftest a-delivery-on-any-other-topic-is-not
  ;; Not claimed. Unanswered past hand-off-millis, it would be on the
  ;; queue all the same, from the hand-off (mqttkat.hand-off-test), which
  ;; is off here: this is about the claim.
  (let [topic (tu/topic "eo")
        {:keys [pid publishes releases sent released completed]}
        (with-redefs [h/hand-off? (delay false)]
          (delivered [(str topic "/elsewhere")] topic))]
    (is (= 1 (count publishes)))
    (is (= [pid] releases))
    (is (empty? sent))
    (is (empty? released))
    (is (empty? completed))))

(defn- answering
  "A raw client that completes every QoS 2 exchange it is sent, keeping each
   PUBLISH in `publishes`."
  [publishes]
  (let [connack (promise)
        suback  (promise)
        c       (atom nil)
        send!   #(client/send-message @c %)
        on      (fn [msg _]
                  (case (:packet-type msg)
                    :CONNACK (deliver connack msg)
                    :SUBACK  (deliver suback msg)
                    :PUBLISH (do (swap! publishes conj msg)
                                 (send! {:packet-type :PUBREC :packet-identifier (:packet-identifier msg)}))
                    :PUBREL  (send! {:packet-type :PUBCOMP :packet-identifier (:packet-identifier msg)})
                    nil))]
    (reset! c (client/client tu/host tu/port (MqttHandler. ^clojure.lang.IFn on 1)))
    {:client @c :connack connack :suback suback :send! send!}))

(deftest a-message-in-flight-under-an-identifier-goes-out-under-no-other
  ;; Two left on the queue under the same identifier, as two brokers' would
  ;; be: the second cannot be restored under it while the first has it, and
  ;; sent under a new one, its claim is refused. It waits for the identifier
  ;; and goes out under it, once.
  (let [topic     (tu/topic "eo")
        id        (tu/client-id "eo-sub")
        publishes (atom [])
        msg       #(hash-map :topic topic :qos 2 :packet-identifier 9
                             :payload (.getBytes ^String % "UTF-8"))]
    (with-redefs [h/exactly-once-filters (delay [topic])]
      (let [first (answering (atom []))]
        ((:send! first) {:packet-type :CONNECT :protocol-name "MQTT" :protocol-version 4
                         :keep-alive 100 :clean-session? false :client-id id})
        (is (some? (deref (:connack first) 5000 nil)))
        ((:send! first) {:packet-type :SUBSCRIBE :packet-identifier 1
                         :topics [{:qos 2 :topic-filter topic}]})
        (is (some? (deref (:suback first) 5000 nil)))
        (client/close (:client first))
        (is (tu/wait-until #(false? (cluster/connected? *conn* id)) 10000)))
      @(cluster/enqueue! *conn* id (msg "a") (h/new-message-key))
      @(cluster/enqueue! *conn* id (msg "b") (h/new-message-key))
      (let [again (answering publishes)]
        (try
          ((:send! again) {:packet-type :CONNECT :protocol-name "MQTT" :protocol-version 4
                           :keep-alive 100 :clean-session? false :client-id id})
          (is (some? (deref (:connack again) 5000 nil)))
          (is (tu/wait-until #(= #{"a" "b"} (set (map tu/payload-str @publishes))) 15000)
              "both are delivered")
          (Thread/sleep 1000)
          (is (= #{9} (set (map :packet-identifier @publishes)))
              "under the identifier they were in flight under")
          (is (= 1 (count (filter #(= "b" (tu/payload-str %)) @publishes))))
          (is (tu/wait-until #(empty? (cluster/queued *conn* id)) 5000) "and both are done")
          (finally (try (client/close (:client again)) (catch Exception _ nil))))))))

(deftest the-publisher-is-told-only-once-the-claim-has-landed
  ;; The claim is a hand-off: a broker that died with it still on its way
  ;; had the message in its memory alone, and the publisher was told it was
  ;; safe and its hold let go.
  (let [topic (tu/topic "eo")
        id    (tu/client-id "eo-sub")
        real  (:claim! @h/session-source)
        go    (java.util.concurrent.CompletableFuture.)
        sub   (answering (atom []))]
    (with-redefs [h/exactly-once-filters (delay [topic])]
      (swap! h/session-source assoc :claim!
             (fn [client-id k msg]
               (if (:released? msg)
                 (real client-id k msg)
                 (.thenCompose go (reify java.util.function.Function
                                    (apply [_ _] (real client-id k msg)))))))
      (try
        ((:send! sub) {:packet-type :CONNECT :protocol-name "MQTT" :protocol-version 4
                       :keep-alive 100 :clean-session? false :client-id id})
        (is (some? (deref (:connack sub) 5000 nil)))
        ((:send! sub) {:packet-type :SUBSCRIBE :packet-identifier 1 :topics [{:qos 2 :topic-filter topic}]})
        (is (some? (deref (:suback sub) 5000 nil)))
        (is (tu/wait-until #(seq (cluster/matching-subscriptions *conn* topic)) 10000))
        (let [done (future (publish-qos-2! topic "held"))]
          (Thread/sleep 1500)
          (is (not (realized? done)) "no PUBCOMP while the claim is on its way")
          (.complete go nil)
          (is (some? (deref done 10000 nil)) "and one once it has landed"))
        (finally
          (swap! h/session-source assoc :claim! real)
          (try (client/close (:client sub)) (catch Exception _ nil)))))))

(deftest an-exactly-once-message-with-no-slot-waits-on-the-cluster
  ;; Not in this broker's memory, where a broker that died took it.
  (let [topic     (tu/topic "eo")
        id        (tu/client-id "eo-sub")
        got       (atom [])
        acking?   (atom false)
        unacked   (atom [])
        c         (atom nil)
        connack   (promise)
        suback    (promise)
        send!     #(client/send-message @c (assoc % :protocol-version 5))
        on        (fn [msg _]
                    (case (:packet-type msg)
                      :CONNACK (deliver connack msg)
                      :SUBACK  (deliver suback msg)
                      :PUBLISH (do (swap! got conj (tu/payload-str msg))
                                   (if @acking?
                                     (send! {:packet-type :PUBREC :packet-identifier (:packet-identifier msg)})
                                     (swap! unacked conj (:packet-identifier msg))))
                      :PUBREL  (send! {:packet-type :PUBCOMP :packet-identifier (:packet-identifier msg)})
                      nil))]
    (reset! c (client/client tu/host tu/port (MqttHandler. ^clojure.lang.IFn on 1)))
    (with-redefs [h/exactly-once-filters (delay [topic])]
      (try
        (send! {:packet-type :CONNECT :protocol-name "MQTT" :keep-alive 100 :clean-session? false
                :client-id id :properties {:session-expiry-interval 3600 :receive-maximum 1}})
        (is (some? (deref connack 5000 nil)))
        (send! {:packet-type :SUBSCRIBE :packet-identifier 1 :topics [{:qos 2 :topic-filter topic}]})
        (is (some? (deref suback 5000 nil)))
        (is (tu/wait-until #(seq (cluster/matching-subscriptions *conn* topic)) 10000))
        (doseq [p ["a" "b" "c"]] (is (some? (publish-qos-2! topic p))))
        (is (tu/wait-until #(= 1 (count @got)) 5000) "one sent: the window")
        (let [queued (cluster/queued *conn* id)]
          (is (= 3 (count queued)) "all three on the cluster")
          (is (= 2 (count (remove (comp :packet-identifier second) queued)))
              "two of them waiting, the third in flight under its identifier"))
        (reset! acking? true)
        (run! #(send! {:packet-type :PUBREC :packet-identifier %}) @unacked)
        (is (tu/wait-until #(= #{"a" "b" "c"} (set @got)) 15000) "all sent once there is room")
        (Thread/sleep 1000)
        (is (= 3 (count @got)) "once each")
        (is (tu/wait-until #(empty? (cluster/queued *conn* id)) 10000) "and none left")
        (finally (try (client/close @c) (catch Exception _ nil)))))))

(deftest a-stale-read-does-not-send-again-what-the-client-has-completed
  ;; A read of the queue made before the release landed has the message in
  ;; flight, unreleased, under an identifier the client has since completed
  ;; and let go: acted on after the PUBCOMP, it went out again under it, a
  ;; second delivery with nothing killed. The take-off is held back here,
  ;; so that only what the PUBCOMP notes itself keeps it out.
  (let [topic     (tu/topic "eo")
        id        (tu/client-id "eo-sub")
        publishes (atom [])
        releases  (atom [])
        sub       (subscriber publishes releases)
        send!     #(client/send-message (:client sub) %)
        real      (:dequeue! @h/session-source)]
    ;; Connected long enough ago that the live delivery is not reconciled
    ;; with the queue, and so not noted as had when it is sent.
    (with-redefs [h/exactly-once-filters   (delay [topic])
                  h/reconcile-window-millis 0]
      (swap! h/session-source assoc :dequeue! (fn [_ _] (java.util.concurrent.CompletableFuture.)))
      (try
        (send! {:packet-type :CONNECT :protocol-name "MQTT" :protocol-version 4
                :keep-alive 100 :clean-session? false :client-id id})
        (is (some? (deref (:connack sub) 5000 nil)))
        (send! {:packet-type :SUBSCRIBE :packet-identifier 1 :topics [{:qos 2 :topic-filter topic}]})
        (is (some? (deref (:suback sub) 5000 nil)))
        (is (tu/wait-until #(seq (cluster/matching-subscriptions *conn* topic)) 10000))
        (is (some? (publish-qos-2! topic "once")))
        (is (tu/wait-until #(seq @publishes) 10000))
        (let [pid   (:packet-identifier (first @publishes))
              [k e] (first (cluster/queued *conn* id))
              stale (dissoc e :released?)]
          (is (= pid (:packet-identifier e)))
          (send! {:packet-type :PUBREC :packet-identifier pid})
          (is (tu/wait-until #(seq @releases) 10000))
          (send! {:packet-type :PUBCOMP :packet-identifier pid})
          (Thread/sleep 300)
          (let [key        (h/live-connection id)
                connect-id (get-in @h/*clients* [key :connect-id])]
            (h/deliver-queued! id connect-id [[k stale]] #{}))
          (Thread/sleep 1000)
          (is (= 1 (count @publishes)) "not sent again"))
        (finally
          (swap! h/session-source assoc :dequeue! real)
          (try (client/close (:client sub)) (catch Exception _ nil)))))))
