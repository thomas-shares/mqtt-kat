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
  (let [topic (tu/topic "eo")
        {:keys [pid publishes releases sent released completed]}
        (delivered [(str topic "/elsewhere")] topic)]
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
