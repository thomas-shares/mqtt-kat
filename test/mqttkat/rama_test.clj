(ns mqttkat.rama-test
  "The Rama module, run in-process, and the broker's hook into it.

   One cluster for the whole namespace: launching a module into an
   InProcessCluster takes seconds, and the scenarios below use different
   client ids, so they do not see each other. The task count is drawn at
   random so that a record landing on the wrong task — the partitioning bug
   that one task would hide and four might — has more than one chance to
   show.

   The topology is a stream topology and every append here asks for :ack, so
   by the time the append's future is realised the session is in the PState
   and assertions can be immediate. Through the broker the append is
   asynchronous, so those assertions wait."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [com.rpl.rama :as r]
            [com.rpl.rama.path :refer [keypath]]
            [mqttkat.bridge :as bridge]
            [mqttkat.client :as client]
            [mqttkat.events :as events]
            [mqttkat.handlers :as h]
            [mqttkat.rama.cluster :as cluster]
            [mqttkat.rama.module :as module]
            [mqttkat.retained :as retained]
            [mqttkat.test-util :as tu]
            [mqttkat.web.console :as console]
            [mqttkat.web.state :as state])
  (:import [org.mqttkat MqttHandler]
           [org.mqttkat.packages MqttPubRec]
           [org.mqttkat.server MqttServer]))

(use-fixtures :once tu/broker-fixture)

(defn- connect-map
  "A CONNECT as the broker's handler sees it."
  [client-id & {:keys [version clean? keep-alive expiry]
                :or   {version 4 clean? true keep-alive 30}}]
  (cond-> {:client-id        client-id
           :protocol-version version
           :clean-session?   clean?
           :keep-alive       keep-alive}
    expiry (assoc :properties {:session-expiry-interval expiry})))

(defn- on-broker
  "A connect record as another broker would have made it."
  [connect broker-id incarnation]
  (assoc connect :broker-id broker-id :incarnation incarnation))

(defn- record! [conn event]
  @(cluster/record! conn event))

(defn- stored
  "The session as stored, minus the two stamps that vary from run to run."
  [conn client-id]
  (dissoc (cluster/session conn client-id) :connected-at :disconnected-at :incarnation))

(defn- entry
  "A subscription entry as the broker stores one."
  [topic-filter qos & {:as opts}]
  (merge {:filter topic-filter :topic-filter topic-filter :qos qos} opts))

(defn- subscribe-msg
  "A SUBSCRIBE, in the dialect of the connection it goes out on."
  [topic qos id & {:keys [version] :or {version 4}}]
  (cond-> {:packet-type :SUBSCRIBE :packet-identifier id
           :topics [{:qos qos :topic-filter topic}]}
    (= 5 version) (assoc :protocol-version 5 :properties {})))

(defn- publish-msg
  "A PUBLISH in the version 5 dialect unless told otherwise — the encoder
   writes a property block for 5 and none for 4, so this has to match the
   connection it goes out on."
  [topic payload qos id & {:keys [properties version] :or {version 5}}]
  (cond-> {:packet-type :PUBLISH :qos qos :topic topic
           :payload (.getBytes ^String payload "UTF-8") :retain? false :duplicate? false}
    (= 5 version) (assoc :protocol-version 5 :properties (or properties {}))
    id            (assoc :packet-identifier id)))

(defn- in-rama
  "What Rama holds as retained on `topic`, read straight from the PState,
   with the payload back as bytes — it is stored as text."
  [conn topic]
  (some-> (r/foreign-select-one (keypath (module/shard-of topic) topic) (:retained-state conn))
          (update :payload #(.decode (java.util.Base64/getDecoder) ^String %))))

(defn- peer-broker
  "Another broker, as far as the bridge can tell: a listener that keeps every
   packet it is sent and answers a QoS 2 publish with the PUBREC the bridge
   has to complete. Returns {:server :port :received}."
  []
  (let [received (atom [])
        server   (atom nil)
        handler  (MqttHandler.
                  ^clojure.lang.IFn
                  (fn [{:keys [packet-type qos client-key packet-identifier] :as msg} _]
                    (swap! received conj (dissoc msg :client-key))
                    (when (and (= :PUBLISH packet-type) (= 2 (long (or qos 0))))
                      (.sendMessageBuffer ^MqttServer @server [client-key]
                                          (MqttPubRec/encode {:packet-type       :PUBREC
                                                              :packet-identifier packet-identifier}))))
                  1)
        s        (doto (MqttServer. "127.0.0.1" 0 handler) (.start))]
    (reset! server s)
    {:server s :port (.getPort s) :received received}))

(defn- matches
  "What `conn`'s copy of the cluster's subscriptions has for `topic`, as
   client-id -> the entry without the two ids, so tests can say what they
   expect without repeating them."
  [conn topic]
  (into {} (map (fn [e] [(:client-id e) (dissoc e :client-id :broker-id :connected?)]))
        (cluster/matching-subscriptions conn topic)))

(defn- present-in-trie?
  "Whether `conn`'s copy says `client-id`'s matching subscription on `topic`
   belongs to a connected client."
  [conn topic client-id]
  (some (fn [e] (and (= client-id (:client-id e)) (:connected? e)))
        (cluster/matching-subscriptions conn topic)))

(deftest sessions-module
  (with-redefs [cluster/in-process-config {:tasks (rand-nth [2 4 8]) :threads 2 :workers 1}
                module/REPLACE-TICK-DEPOT true]
    (let [conn (cluster/connect :in-process)
          ;; Another broker, as far as Rama can tell: its own handles on the
          ;; same cluster, watching the same subscriptions, recording nothing.
          peer (cluster/watch! (cluster/connect-to (:cluster conn)))
          both-see (fn [topic expected & [why]]
                     ;; The proxies are asynchronous, so first wait for it,
                     ;; then say what it should be — the wait alone would
                     ;; pass on a timeout.
                     (tu/wait-until #(and (= expected (matches conn topic))
                                          (= expected (matches peer topic))))
                     (is (= expected (matches conn topic)) (str "this broker's copy" (some->> why (str ": "))))
                     (is (= expected (matches peer topic)) (str "the other broker's copy" (some->> why (str ": ")))))]
      (cluster/watch! conn)
      (try
        (testing "the record is the broker's CONNECT map, renamed for nothing"
          (let [c (cluster/->connect (connect-map "c" :version 5 :clean? false
                                                 :keep-alive 60 :expiry 3600))]
            (is (= {:event                   :connect
                    :client-id               "c"
                    :broker-id               cluster/broker-id
                    :incarnation             cluster/incarnation
                    :protocol-version        5
                    :clean-session?          false
                    :keep-alive              60
                    :session-expiry-interval 3600}
                   (dissoc c :at :connect-id)))
            (is (string? (:connect-id c)))
            (is (not= (:connect-id c) (:connect-id (cluster/->connect (connect-map "c"))))
                "a CONNECT that arrives without a name is given one")
            (is (= "given" (:connect-id (cluster/->connect (assoc (connect-map "c") :connect-id "given"))))
                "and one that has a name keeps it")
            (is (= {:event :disconnect :connect-id "given" :client-id "c"}
                   (dissoc (cluster/->disconnect {:connect-id "given" :client-id "c"}) :at))))
          (testing "and a 3.1.1 client, which has no properties, expires at 0"
            (is (= 0 (:session-expiry-interval (cluster/->connect (connect-map "c")))))))

        (testing "a client that has never connected"
          (is (nil? (cluster/session conn "nobody")))
          (is (false? (cluster/connected? conn "nobody")))
          (is (= 0 (cluster/connections conn "nobody")))
          (testing "cannot disconnect either"
            (record! conn (cluster/->disconnect {:connect-id "x" :client-id "nobody"}))
            (is (nil? (cluster/session conn "nobody")))))

        (testing "one connect: the session is what it asked for"
          (let [c (cluster/->connect (connect-map "sensor-7" :version 5 :clean? false
                                                 :keep-alive 45 :expiry 600))]
            (record! conn c)
            (is (= {:connect-id              (:connect-id c)
                    :broker-id               cluster/broker-id
                    :protocol-version        5
                    :clean-session?          false
                    :keep-alive              45
                    :session-expiry-interval 600
                    :connected?              true
                    :connected-at            (:at c)
                    :connections             1
                    :subscriptions           {}}
                   (dissoc (cluster/session conn "sensor-7") :incarnation)))
            (is (true? (cluster/connected? conn "sensor-7")))
            (is (= 1 (cluster/connections conn "sensor-7")))

            (testing "the same record again is the same connect, not another"
              ;; A stream topology may run a record twice; this is that case
              ;; made deliberate, and the count must not move.
              (record! conn c)
              (is (= 1 (cluster/connections conn "sensor-7"))))

            (testing "the connection ends: marked, and the rest is kept"
              (let [d (cluster/->disconnect c)]
                (record! conn d)
                (is (= {:connect-id              (:connect-id c)
                        :broker-id               cluster/broker-id
                        :protocol-version        5
                        :clean-session?          false
                        :keep-alive              45
                        :session-expiry-interval 600
                        :connected?              false
                        :connected-at            (:at c)
                        :disconnected-at         (:at d)
                        :expires-at              (+ (:at d) 600000)
                        :connections             1
                        :subscriptions           {}}
                       (dissoc (cluster/session conn "sensor-7") :incarnation)))
                (is (false? (cluster/connected? conn "sensor-7")))

                (testing "and the same disconnect again changes nothing"
                  (record! conn d)
                  (is (= (:at d) (:disconnected-at (cluster/session conn "sensor-7")))))))))

        (testing "reconnecting replaces the session and counts"
          (let [c (cluster/->connect (connect-map "sensor-7" :version 4 :clean? true))]
            (record! conn c)
            (is (= {:connect-id              (:connect-id c)
                    :broker-id               cluster/broker-id
                    :protocol-version        4
                    :clean-session?          true
                    :keep-alive              30
                    :session-expiry-interval 0
                    :connected?              true
                    :connections             2
                    :subscriptions           {}}
                   (stored conn "sensor-7"))
                "the whole record is replaced, nothing from the last one lingers")
            (is (nil? (:disconnected-at (cluster/session conn "sensor-7"))))

            (testing "a disconnect for the connection before this one is not this one's"
              ;; A takeover reports the displaced connection gone after the
              ;; replacement is in; whichever order the two reach Rama, the
              ;; client is connected.
              (record! conn (cluster/->disconnect {:connect-id "the-one-before" :client-id "sensor-7"}))
              (is (true? (cluster/connected? conn "sensor-7")))
              (is (= 2 (cluster/connections conn "sensor-7"))))))

        (testing "many clients, many connects, each counted under its own id"
          (let [ids (map #(str "client-" %) (range 20))]
            (doseq [id ids, _ (range 3)]
              (record! conn (cluster/->connect (connect-map id))))
            (is (every? #(= 3 (cluster/connections conn %)) ids))
            (is (every? #(= 4 (:protocol-version (cluster/session conn %))) ids))
            (is (= 2 (cluster/connections conn "sensor-7")) "and nobody else's")))

        (testing "subscriptions"
          (let [c (cluster/->connect (connect-map "sub-1" :clean? false))
                s (cluster/->subscribe {:connect-id (:connect-id c) :client-id "sub-1"
                                        :filter "sport/#" :entry (entry "sport/#" 1)})]
            (record! conn c)
            (record! conn s)
            (is (= {"sport/#" (entry "sport/#" 1)} (cluster/subscriptions conn "sub-1"))
                "the session holds it")
            (both-see "sport/tennis" {"sub-1" (entry "sport/#" 1)})
            (is (= cluster/broker-id
                   (:broker-id (first (cluster/matching-subscriptions conn "sport/tennis"))))
                "stamped with the broker that took it")

            (testing "the same subscribe again changes nothing"
              (record! conn s)
              (both-see "sport/tennis" {"sub-1" (entry "sport/#" 1)}))

            (testing "a subscribe naming another connection is not this session's"
              (record! conn (cluster/->subscribe {:connect-id "stale" :client-id "sub-1"
                                                  :filter "other/#" :entry (entry "other/#" 0)}))
              (is (= {"sport/#" (entry "sport/#" 1)} (cluster/subscriptions conn "sub-1")))
              (both-see "other/x" {}))

            (testing "subscribing again to the same filter replaces the entry"
              (record! conn (cluster/->subscribe {:connect-id (:connect-id c) :client-id "sub-1"
                                                  :filter "sport/#" :entry (entry "sport/#" 2 :no-local? true)}))
              (both-see "sport/tennis" {"sub-1" (entry "sport/#" 2 :no-local? true)}))

            (testing "a shared subscription is keyed by what the client sent and matches by the inner filter"
              (record! conn (cluster/->subscribe {:connect-id (:connect-id c) :client-id "sub-1"
                                                  :filter "$share/g/golf/+"
                                                  :entry (entry "$share/g/golf/+" 0
                                                                :topic-filter "golf/+" :share-group "g")}))
              (is (= #{"sport/#" "$share/g/golf/+"} (set (keys (cluster/subscriptions conn "sub-1")))))
              (both-see "golf/hole-1" {"sub-1" (entry "$share/g/golf/+" 0 :topic-filter "golf/+" :share-group "g")})
              (both-see "$share/g/golf/hole-1" {} "the share prefix is not a topic level"))

            (testing "unsubscribe"
              (record! conn (cluster/->unsubscribe {:connect-id (:connect-id c) :client-id "sub-1"
                                                    :filter "$share/g/golf/+"}))
              (both-see "golf/hole-1" {})
              (is (= #{"sport/#"} (set (keys (cluster/subscriptions conn "sub-1")))))
              (testing "and again, for a filter that is already gone"
                (record! conn (cluster/->unsubscribe {:connect-id (:connect-id c) :client-id "sub-1"
                                                      :filter "$share/g/golf/+"}))
                (both-see "golf/hole-1" {})))

            (testing "a persistent session keeps its subscriptions across a disconnect"
              (record! conn (cluster/->disconnect c))
              (is (false? (cluster/connected? conn "sub-1")))
              (is (= {"sport/#" (entry "sport/#" 2 :no-local? true)} (cluster/subscriptions conn "sub-1")))
              (both-see "sport/tennis" {"sub-1" (entry "sport/#" 2 :no-local? true)}))

            (testing "and across a persistent reconnect"
              (record! conn (cluster/->connect (connect-map "sub-1" :clean? false)))
              (is (= {"sport/#" (entry "sport/#" 2 :no-local? true)} (cluster/subscriptions conn "sub-1")))
              (both-see "sport/tennis" {"sub-1" (entry "sport/#" 2 :no-local? true)}))

            (testing "a clean-session connect discards them — everywhere"
              (record! conn (cluster/->connect (connect-map "sub-1" :clean? true)))
              (is (= {} (cluster/subscriptions conn "sub-1")))
              (both-see "sport/tennis" {}))

            (testing "and a clean session's disconnect discards them too"
              (let [c2 (cluster/->connect (connect-map "sub-2" :clean? true))]
                (record! conn c2)
                (record! conn (cluster/->subscribe {:connect-id (:connect-id c2) :client-id "sub-2"
                                                    :filter "a/b" :entry (entry "a/b" 0)}))
                (both-see "a/b" {"sub-2" (entry "a/b" 0)})
                (record! conn (cluster/->disconnect c2))
                (is (= {} (cluster/subscriptions conn "sub-2")))
                (both-see "a/b" {})))

            (testing "many clients on one filter, and one client on many"
              (let [c3 (cluster/->connect (connect-map "sub-3"))]
                (record! conn c3)
                (doseq [i (range 20)]
                  (record! conn (cluster/->subscribe {:connect-id (:connect-id c3) :client-id "sub-3"
                                                      :filter (str "many/" i) :entry (entry (str "many/" i) 0)})))
                (doseq [i (range 20)]
                  (let [id (str "fan-" i) cc (cluster/->connect (connect-map id))]
                    (record! conn cc)
                    (record! conn (cluster/->subscribe {:connect-id (:connect-id cc) :client-id id
                                                        :filter "fan/#" :entry (entry "fan/#" 1)}))))
                (is (tu/wait-until #(= 20 (count (matches peer "fan/out")))))
                (is (= 20 (count (matches peer "fan/out"))))
                (is (every? #(= (entry "fan/#" 1) %) (vals (matches peer "fan/out"))))
                (is (= {"sub-3" (entry "many/7" 0)} (matches peer "many/7")))
                (is (= 20 (count (cluster/subscriptions conn "sub-3"))))))))

        (testing "retained messages"
          (let [msg {:qos 1 :payload (.getBytes "kept" "UTF-8") :properties {:content-type "text/plain"}
                     :stored-at 1000}]
            (testing "recorded, and pushed to every broker's store"
              (record! conn (cluster/->retain "retained/a" msg))
              (is (tu/wait-until #(= 1000 (:stored-at (get @retained/store "retained/a")))))
              (let [got (get @retained/store "retained/a")]
                (is (= "kept" (String. ^bytes (:payload got) "UTF-8")))
                (is (= {:content-type "text/plain"} (:properties got)))
                (is (= 1 (:qos got)))))
            (testing "replaced by the next"
              (record! conn (cluster/->retain "retained/a" (assoc msg :stored-at 2000)))
              (is (tu/wait-until #(= 2000 (:stored-at (get @retained/store "retained/a"))))))
            (testing "and cleared"
              (record! conn (cluster/->retain "retained/a" nil))
              (is (tu/wait-until #(nil? (get @retained/store "retained/a"))))
              (is (nil? (in-rama conn "retained/a"))))
            (testing "a broker that starts later gets what is retained from the record alone"
              (record! conn (cluster/->retain "retained/b" msg))
              (is (tu/wait-until #(some? (get @retained/store "retained/b"))))
              ;; As if this broker had never seen it, then a fresh watcher.
              (swap! retained/store dissoc "retained/b")
              (let [late (cluster/watch! (cluster/connect-to (:cluster conn)))]
                (try
                  (is (tu/wait-until #(= 1000 (:stored-at (get @retained/store "retained/b")))))
                  (finally
                    (cluster/close! late)))))))

        (testing "a broker that comes back: what its previous run held is let go"
          (let [v1 (on-broker (cluster/->connect (connect-map "victim-1" :clean? false)) "peer-x" "run-1")
                v2 (on-broker (cluster/->connect (connect-map "victim-2" :clean? true)) "peer-x" "run-1")
                sv (on-broker (cluster/->connect (connect-map "survivor" :clean? false)) "peer-x" "run-2")]
            (doseq [c [v1 v2 sv]]
              (record! conn c)
              (record! conn (cluster/->subscribe {:connect-id (:connect-id c) :client-id (:client-id c)
                                                  :filter "lost/#" :entry (entry "lost/#" 1)})))
            (is (tu/wait-until #(= 3 (count (matches peer "lost/t")))))
            (is (every? #(present-in-trie? peer "lost/t" %) ["victim-1" "victim-2" "survivor"]))

            (record! conn {:event :broker-up :broker-id "peer-x" :incarnation "run-2"
                           :host "127.0.0.1" :port 1 :at 5})
            (is (tu/wait-until #(and (false? (cluster/connected? conn "victim-1"))
                                     (false? (cluster/connected? conn "victim-2")))))
            (testing "a persistent session is parked, subscriptions and all"
              (is (= {"lost/#" (entry "lost/#" 1)} (cluster/subscriptions conn "victim-1")))
              (tu/wait-until #(not (present-in-trie? peer "lost/t" "victim-1")))
              (is (= [{:client-id "victim-1" :connected? false}]
                     (filterv #(= "victim-1" (:client-id %))
                              (map #(select-keys % [:client-id :connected?])
                                   (cluster/matching-subscriptions peer "lost/t")))))
              (is (contains? (matches peer "lost/t") "victim-1")))
            (testing "a clean session is gone, subscriptions and all"
              (is (= {} (cluster/subscriptions conn "victim-2")))
              (is (tu/wait-until #(not (contains? (matches peer "lost/t") "victim-2")))))
            (testing "a client already back on the new run is left alone"
              (is (true? (cluster/connected? conn "survivor")))
              (is (present-in-trie? peer "lost/t" "survivor")))
            (testing "and the announcement again changes nothing more"
              (record! conn {:event :broker-up :broker-id "peer-x" :incarnation "run-2"
                             :host "127.0.0.1" :port 1 :at 6})
              (Thread/sleep 300)
              (is (true? (cluster/connected? conn "survivor")))
              (is (false? (cluster/connected? conn "victim-1"))))
            (record! conn {:event :broker-down :broker-id "peer-x" :at 7})))

        (testing "a session that is away: its subscriptions say so, and publishes are queued"
          (let [c (cluster/->connect (connect-map "away-1" :clean? false))]
            (record! conn c)
            (record! conn (cluster/->subscribe {:connect-id (:connect-id c) :client-id "away-1"
                                                :filter "away/#" :entry (entry "away/#" 1)}))
            (is (tu/wait-until #(present-in-trie? conn "away/t" "away-1")))
            (is (nil? (:queue (cluster/plan conn "away/t"))) "present: nothing to queue")
            (record! conn (cluster/->disconnect c))
            (is (tu/wait-until #(and (contains? (matches conn "away/t") "away-1")
                                     (not (present-in-trie? conn "away/t" "away-1")))))
            (is (= {:brokers {} :skip #{} :queue [{:client-id "away-1" :qos 1}]}
                   (cluster/plan conn "away/t"))
                "away: queued here, forwarded nowhere")

            (record! conn (cluster/->enqueue "away-1" {:topic "away/t" :payload (.getBytes "first") :qos 1}))
            (Thread/sleep 2)
            (record! conn (cluster/->enqueue "away-1" {:topic "away/t" :payload (.getBytes "second") :qos 1}))
            (let [q (cluster/queued conn "away-1")]
              (is (= 2 (count q)))
              (is (= ["first" "second"] (mapv #(String. ^bytes (:payload (second %))) q)) "oldest first")
              (is (every? #(number? (:queued-at (second %))) q))
              (testing "resume hands both back, then they are taken off"
                (let [{:keys [subscriptions queued]} (cluster/resume conn "away-1")]
                  (is (= {"away/#" (entry "away/#" 1)} subscriptions))
                  (is (= (map first q) (map first queued)))
                  (is (= ["first" "second"] (mapv #(String. ^bytes (:payload (second %))) queued))))
                (record! conn (cluster/->dequeue "away-1" (map first q)))
                (is (empty? (cluster/queued conn "away-1")))
                (is (= [] (:queued (cluster/resume conn "away-1"))))))
            (is (nil? (cluster/resume conn "never-seen")))
            (record! conn (cluster/->connect (connect-map "clean-1" :clean? true)))
            (let [r (cluster/resume conn "clean-1")]
              (is (true? (:clean-session? (:session r))) "a clean session is known, for the takeover")
              (is (= {:subscriptions {} :queued []} (dissoc r :session)) "but nothing is resumed"))))

        (testing "session expiry, on the cluster's clock"
          (let [tick   (r/foreign-depot (:cluster conn) (:module-name conn) "*expiry-tick")
                sweep! (fn [now] @(r/foreign-append-async! tick {:now now} :ack))
                t0     1000000000000]
            (testing "a version 5 session with an interval is forgotten when it passes"
              (let [c (assoc (cluster/->connect (connect-map "expiring" :version 5 :clean? false :expiry 60))
                             :at t0)]
                (record! conn c)
                (record! conn (cluster/->subscribe {:connect-id (:connect-id c) :client-id "expiring"
                                                    :filter "exp/#" :entry (entry "exp/#" 1)}))
                (record! conn (cluster/->enqueue "expiring" {:topic "exp/t" :payload (.getBytes "q") :qos 1}))
                (record! conn (assoc (cluster/->disconnect c) :at t0))
                (is (= (+ t0 60000) (:expires-at (cluster/session conn "expiring"))))
                (is (tu/wait-until #(contains? (matches conn "exp/t") "expiring")))
                (sweep! (+ t0 59000))
                (is (some? (cluster/session conn "expiring")) "not yet")
                (sweep! (+ t0 61000))
                (is (nil? (cluster/session conn "expiring")) "gone: the record")
                (is (empty? (cluster/queued conn "expiring")) "the queue")
                (is (tu/wait-until #(not (contains? (matches conn "exp/t") "expiring"))) "and the subscriptions")
                (is (nil? (cluster/resume conn "expiring")))))

            (testing "coming back in time cancels it; going away again sets a new one"
              (let [c1 (assoc (cluster/->connect (connect-map "returner" :version 5 :clean? false :expiry 60)) :at t0)
                    _  (record! conn c1)
                    _  (record! conn (assoc (cluster/->disconnect c1) :at t0))
                    c2 (assoc (cluster/->connect (connect-map "returner" :version 5 :clean? false :expiry 60))
                              :at (+ t0 30000))]
                (record! conn c2)
                (is (nil? (:expires-at (cluster/session conn "returner"))))
                (sweep! (+ t0 61000))
                (is (true? (cluster/connected? conn "returner")) "the first due time no longer counts")
                (record! conn (assoc (cluster/->disconnect c2) :at (+ t0 40000)))
                (is (= (+ t0 100000) (:expires-at (cluster/session conn "returner"))))
                (sweep! (+ t0 61000))
                (is (some? (cluster/session conn "returner")))
                (sweep! (+ t0 100001))
                (is (nil? (cluster/session conn "returner")))))

            (testing "a DISCONNECT can shorten the interval on the way out"
              (let [c (assoc (cluster/->connect (connect-map "shortener" :version 5 :clean? false :expiry 3600)) :at t0)]
                (record! conn c)
                (record! conn (assoc (cluster/->disconnect (assoc c :session-expiry-interval 5)) :at t0))
                (is (= (+ t0 5000) (:expires-at (cluster/session conn "shortener"))))
                (is (= 5 (:session-expiry-interval (cluster/session conn "shortener"))))
                (sweep! (+ t0 5001))
                (is (nil? (cluster/session conn "shortener")))))

            (testing "a version 5 session with interval 0 ends with the connection, clean start or not"
              (let [c (cluster/->connect (connect-map "brief" :version 5 :clean? false))]
                (record! conn c)
                (record! conn (cluster/->subscribe {:connect-id (:connect-id c) :client-id "brief"
                                                    :filter "brief/#" :entry (entry "brief/#" 0)}))
                (record! conn (cluster/->disconnect c))
                (is (= {} (cluster/subscriptions conn "brief")))
                (is (nil? (:expires-at (cluster/session conn "brief"))))
                (is (tu/wait-until #(not (contains? (matches conn "brief/t") "brief"))))))

            (testing "a version 5 clean start with an interval is kept all the same"
              (let [c (cluster/->connect (connect-map "kept-clean" :version 5 :clean? true :expiry 60))]
                (record! conn c)
                (record! conn (cluster/->subscribe {:connect-id (:connect-id c) :client-id "kept-clean"
                                                    :filter "kc/#" :entry (entry "kc/#" 0)}))
                (record! conn (cluster/->disconnect c))
                (is (= {"kc/#" (entry "kc/#" 0)} (cluster/subscriptions conn "kept-clean")))
                (is (some? (:expires-at (cluster/session conn "kept-clean"))))))

            (testing "a 3.1.1 persistent session never expires, and 0xFFFFFFFF means never too"
              (let [v4 (assoc (cluster/->connect (connect-map "forever-4" :clean? false)) :at t0)
                    v5 (assoc (cluster/->connect (connect-map "forever-5" :version 5 :clean? false :expiry 4294967295)) :at t0)]
                (doseq [c [v4 v5]]
                  (record! conn c)
                  (record! conn (assoc (cluster/->disconnect c) :at t0)))
                (is (nil? (:expires-at (cluster/session conn "forever-4"))))
                (is (nil? (:expires-at (cluster/session conn "forever-5"))))
                (sweep! (+ t0 1000000000000))
                (is (some? (cluster/session conn "forever-4")))
                (is (some? (cluster/session conn "forever-5")))))))

        (testing "a broker that starts later sees everything that is already there"
          (let [late (cluster/watch! (cluster/connect-to (:cluster conn)))]
            (try
              (is (tu/wait-until #(= 20 (count (matches late "fan/out")))))
              (is (= (matches peer "many/7") (matches late "many/7")))
              (finally
                (cluster/close! late)))))

        (testing "through the broker: an accepted CONNECT ends up in the PState"
          ;; The test broker runs with Rama off; attaching this connection is
          ;; what -main does when -Dmqttkat.rama is set.
          (cluster/attach! conn)
          (try
            (let [id (tu/client-id "rama")
                  c  (tu/connect! "rama" :id id :keep-alive 20 :clean-session? false)]
              (try
                (is (= 0x00 (:connect-return-code (:connack c))))
                ;; The append is asynchronous, so the CONNACK can arrive first.
                (is (tu/wait-until #(= 1 (cluster/connections conn id))))
                (is (= {:protocol-version        4
                        :clean-session?          false
                        :keep-alive              20
                        :session-expiry-interval 0
                        :connected?              true
                        :connections             1}
                       (dissoc (stored conn id) :connect-id :broker-id :subscriptions)))
                (is (= cluster/broker-id (:broker-id (stored conn id))))
                (finally
                  (tu/close! c)))

              (testing "and dropping the socket marks it disconnected"
                (is (tu/wait-until #(false? (cluster/connected? conn id))))
                (is (= 1 (cluster/connections conn id)))
                (is (number? (:disconnected-at (cluster/session conn id))))))

            (testing "a version 5 client, with the keep-alive the broker negotiated"
              (let [id (tu/client-id "rama5")
                    c  (tu/connect-v5! "rama5" :id id :keep-alive 3600
                                       :properties {:session-expiry-interval 120})]
                (try
                  (is (tu/wait-until #(= 1 (cluster/connections conn id))))
                  (is (= {:protocol-version        5
                          :clean-session?          true
                          ;; §3.2.2.3.5: the broker's cap, which is what both
                          ;; ends use, not the hour that was asked for.
                          :keep-alive              60
                          :session-expiry-interval 120
                          :connected?              true
                          :connections             1}
                         (dissoc (stored conn id) :connect-id :broker-id :subscriptions)))
                  (finally
                    (tu/close! c)))))

            (testing "a polite DISCONNECT marks it too"
              (let [id (tu/client-id "polite")
                    c  (tu/connect! "polite" :id id)]
                (is (tu/wait-until #(true? (cluster/connected? conn id))))
                (client/send-message (:client c) {:packet-type :DISCONNECT})
                (is (tu/wait-until #(false? (cluster/connected? conn id))))
                (tu/close! c)))

            (testing "a takeover: the newcomer is connected, the displaced one is not it"
              (let [id     (tu/client-id "takeover")
                    first  (tu/connect! "takeover" :id id :clean-session? false)
                    _      (is (tu/wait-until #(= 1 (cluster/connections conn id))))
                    first-connect-id (:connect-id (cluster/session conn id))
                    second (tu/connect! "takeover" :id id :clean-session? false)]
                (try
                  ;; The displaced connection is reported gone before the
                  ;; replacement announces itself, on the same thread, so by
                  ;; the time the second connect is counted its disconnect
                  ;; has been and gone — and named the old connection.
                  (is (tu/wait-until #(= 2 (cluster/connections conn id))))
                  (is (true? (cluster/connected? conn id)))
                  (is (not= first-connect-id (:connect-id (cluster/session conn id))))
                  (finally
                    (tu/close! first second)))))

            (testing "a SUBSCRIBE on this broker reaches the other broker's copy"
              (let [id    (tu/client-id "subscriber")
                    c     (tu/connect! "subscriber" :id id :clean-session? false)
                    topic (tu/topic "rama")]
                (try
                  (client/send-message (:client c) (subscribe-msg (str topic "/#") 1 7))
                  (tu/expect! (:ch c) :SUBACK)
                  (both-see (str topic "/a") {id (entry (str topic "/#") 1)})
                  (is (= cluster/broker-id
                         (:broker-id (first (cluster/matching-subscriptions peer (str topic "/a"))))))
                  (client/send-message (:client c) {:packet-type :UNSUBSCRIBE :packet-identifier 8
                                                    :topics [(str topic "/#")]})
                  (tu/expect! (:ch c) :UNSUBACK)
                  (both-see (str topic "/a") {})
                  (finally
                    (tu/close! c)))))

            (testing "forwarding to another broker"
              (let [{:keys [server port received]} (peer-broker)
                    of-type   (fn [t] (filterv #(= t (:packet-type %)) @received))
                    publishes #(of-type :PUBLISH)
                    peer-addr {:host "127.0.0.1" :port port :at 0 :incarnation "peer-run"}]
                (try
                  (testing "the peer announces itself and every broker sees it"
                    (record! conn {:event :broker-up :broker-id "peer-x" :incarnation "peer-run"
                                   :host "127.0.0.1" :port port :at 0})
                    (is (tu/wait-until #(and (= peer-addr (get @(:brokers conn) "peer-x"))
                                             (= peer-addr (get @(:brokers peer) "peer-x")))))
                    (is (= peer-addr (get @(:brokers peer) "peer-x"))))

                  (testing "a subscription the peer holds makes it a destination"
                    (let [c (assoc (cluster/->connect (connect-map "remote-sub" :clean? false))
                                   :broker-id "peer-x")]
                      (record! conn c)
                      (record! conn (cluster/->subscribe {:connect-id (:connect-id c) :client-id "remote-sub"
                                                          :filter "bridge/#" :entry (entry "bridge/#" 2)})))
                    (is (tu/wait-until #(= #{"peer-x"} (cluster/remote-brokers conn "bridge/t"))))
                    (is (= #{} (cluster/remote-brokers conn "elsewhere/t")))
                    (is (= #{} (cluster/remote-brokers conn "$SYS/x")) "wildcard-rooted filters skip $ topics"))

                  (let [pub   (tu/connect-v5! "publisher")
                        local (tu/connect! "local-sub")]
                    (try
                      (client/send-message (:client local) (subscribe-msg "bridge/#" 1 1))
                      (tu/expect! (:ch local) :SUBACK)

                      (testing "QoS 0: one copy to the peer, over a bridge connection in its name"
                        (client/send-message (:client pub) (publish-msg "bridge/t" "zero" 0 nil
                                                                        :properties {:content-type "text/plain"}))
                        (is (tu/wait-until #(= 1 (count (publishes)))))
                        (let [connect (first (of-type :CONNECT))
                              p       (first (publishes))]
                          (is (= (str bridge/client-id-prefix cluster/broker-id) (:client-id connect)))
                          (is (= 5 (:protocol-version connect)))
                          (is (= "bridge/t" (:topic p)))
                          (is (= "zero" (tu/payload-str p)))
                          (is (= 0 (:qos p)))
                          (is (false? (:retain? p)) "never retained on the other side")
                          (is (= "text/plain" (get-in p [:properties :content-type])) "the properties travel"))
                        (is (= "zero" (tu/payload-str (tu/expect-eventually! (:ch local) :PUBLISH)))
                            "and the local subscriber is served as before"))

                      (testing "QoS 1"
                        (client/send-message (:client pub) (publish-msg "bridge/t" "one" 1 11))
                        (tu/expect-eventually! (:ch pub) :PUBACK)
                        (is (tu/wait-until #(= 2 (count (publishes)))))
                        (is (= 1 (:qos (second (publishes)))))
                        (is (some? (:packet-identifier (second (publishes)))))
                        (is (= "one" (tu/payload-str (tu/expect-eventually! (:ch local) :PUBLISH)))))

                      (testing "QoS 2: forwarded on the PUBREL, and the handshake is completed"
                        (client/send-message (:client pub) (publish-msg "bridge/t" "two" 2 12))
                        (tu/expect-eventually! (:ch pub) :PUBREC)
                        (Thread/sleep 100)
                        (is (= 2 (count (publishes))) "not before the PUBREL")
                        (client/send-message (:client pub) {:packet-type :PUBREL :packet-identifier 12})
                        (tu/expect-eventually! (:ch pub) :PUBCOMP)
                        (is (tu/wait-until #(= 3 (count (publishes)))))
                        (is (= 2 (:qos (nth (publishes) 2))))
                        (is (tu/wait-until #(= 1 (count (of-type :PUBREL))))
                            "the bridge answers the peer's PUBREC with a PUBREL")
                        (is (= "two" (tu/payload-str (tu/expect-eventually! (:ch local) :PUBLISH)))))

                      (testing "a publish that arrived over a bridge goes no further"
                        (let [b (tu/connect! "bridge" :id (str bridge/client-id-prefix "peer-y"))]
                          (client/send-message (:client b) (publish-msg "bridge/t" "loop" 0 nil :version 4))
                          (is (= "loop" (tu/payload-str (tu/expect-eventually! (:ch local) :PUBLISH)))
                              "delivered here")
                          (Thread/sleep 200)
                          (is (= 3 (count (publishes))) "and not forwarded on")
                          (tu/close! b)))

                      (testing "a topic nobody remote holds is not forwarded"
                        (client/send-message (:client pub) (publish-msg "elsewhere/t" "none" 0 nil))
                        (Thread/sleep 200)
                        (is (= 3 (count (publishes)))))
                      (finally
                        (tu/close! pub local))))

                  (testing "a retained publish here is retained in Rama, an expiry clears it there too"
                    (let [pub   (tu/connect-v5! "retainer")
                          topic (tu/topic "retained")]
                      (try
                        (client/send-message (:client pub) (assoc (publish-msg topic "state" 1 21) :retain? true))
                        (tu/expect-eventually! (:ch pub) :PUBACK)
                        (is (tu/wait-until #(some? (in-rama conn topic))))
                        (is (= "state" (String. ^bytes (:payload (in-rama conn topic)) "UTF-8")))
                        (is (= 1 (:qos (in-rama conn topic))))

                        (client/send-message (:client pub) (assoc (publish-msg topic "" 0 nil) :retain? true))
                        (is (tu/wait-until #(nil? (in-rama conn topic))) "an empty retained publish clears it")

                        (client/send-message (:client pub)
                                             (assoc (publish-msg topic "brief" 0 nil
                                                                 :properties {:message-expiry-interval 1})
                                                    :retain? true))
                        (is (tu/wait-until #(some? (in-rama conn topic))))
                        (Thread/sleep 1100)
                        (h/sweep-retained!)
                        (is (tu/wait-until #(nil? (in-rama conn topic))) "the sweep clears the record as well")

                        (retained/retain! "$SYS/broker/version" {:qos 0 :payload (.getBytes "x") :stored-at 1})
                        (Thread/sleep 300)
                        (is (nil? (in-rama conn "$SYS/broker/version")) "$-topics stay this broker's own")
                        (finally
                          (retained/clear! "$SYS/broker/version")
                          (tu/close! pub)))))

                  (testing "a shared group with a member here and one on the peer"
                    (let [remote (assoc (cluster/->connect (connect-map "remote-member" :clean? false))
                                        :broker-id "peer-x")
                          local  (tu/connect-v5! "local-member")
                          plain  (tu/connect! "plain-sub")
                          pub    (tu/connect! "share-pub")
                          before (count (publishes))
                          shared (fn []
                                   (filterv (fn [p] (some (fn [[k _]] (= bridge/share-property k))
                                                          (get-in p [:properties :user-properties])))
                                            (drop before (publishes))))]
                      (try
                        (record! conn remote)
                        (record! conn (cluster/->subscribe {:connect-id (:connect-id remote) :client-id "remote-member"
                                                            :filter "$share/g/shared/#"
                                                            :entry (entry "$share/g/shared/#" 0
                                                                          :topic-filter "shared/#" :share-group "g")}))
                        (client/send-message (:client local) (subscribe-msg "$share/g/shared/#" 0 1 :version 5))
                        (tu/expect! (:ch local) :SUBACK)
                        (client/send-message (:client plain) (subscribe-msg "shared/#" 0 1))
                        (tu/expect! (:ch plain) :SUBACK)
                        (is (tu/wait-until (fn [] (= 2 (count (filter #(= "g" (:share-group %))
                                                                      (cluster/matching-subscriptions conn "shared/t")))))))

                        (testing "the choice of broker rotates, and the chosen one is told"
                          (dotimes [i 4]
                            (client/send-message (:client pub) (publish-msg "shared/t" (str "m" i) 0 nil :version 4)))
                          (is (tu/wait-until #(= 2 (count (shared)))))
                          (Thread/sleep 300)
                          (is (= 2 (count (shared))) "two of four to the peer, for its member")
                          (is (every? #(= [[bridge/share-property "g/shared/#"]]
                                          (mapv vec (get-in % [:properties :user-properties])))
                                      (shared)))
                          (is (= 2 (count (:PUBLISH (tu/take-n! (:ch local) 4 700))))
                              "the other two to the member here")
                          (is (= 4 (count (:PUBLISH (tu/take-n! (:ch plain) 4 700))))
                              "an ordinary subscriber gets them all, as ever"))

                        (testing "arriving over a bridge, a publish serves only the groups it names"
                          (let [b (tu/connect-v5! "bridge" :id (str bridge/client-id-prefix "peer-y"))]
                            (client/send-message (:client b) (publish-msg "shared/t" "named" 0 nil
                                                                          :properties {:user-properties
                                                                                       [["mqttkat-share" "g/shared/#"]
                                                                                        ["keep" "me"]]}))
                            (let [got (tu/expect-eventually! (:ch local) :PUBLISH)]
                              (is (= "named" (tu/payload-str got)))
                              (is (= [["keep" "me"]] (mapv vec (get-in got [:properties :user-properties])))
                                  "the instruction is taken off, the rest stays"))
                            (is (= "named" (tu/payload-str (tu/expect-eventually! (:ch plain) :PUBLISH))))

                            (client/send-message (:client b) (publish-msg "shared/t" "unnamed" 0 nil))
                            (is (= "unnamed" (tu/payload-str (tu/expect-eventually! (:ch plain) :PUBLISH))))
                            (is (nil? (tu/take! (:ch local) 300)) "and none for a group it does not name")
                            (tu/close! b)))
                        (finally
                          (tu/close! local plain pub)))))

                  (testing "a publish here for a session that is away is queued in the cluster"
                    (let [c   (on-broker (cluster/->connect (connect-map "parked-elsewhere" :clean? false))
                                         "peer-x" "run-9")
                          pub (tu/connect! "queue-pub")]
                      (try
                        (record! conn c)
                        (record! conn (cluster/->subscribe {:connect-id (:connect-id c) :client-id "parked-elsewhere"
                                                            :filter "parked/#" :entry (entry "parked/#" 2)}))
                        (record! conn (cluster/->disconnect c))
                        (is (tu/wait-until #(and (contains? (matches conn "parked/t") "parked-elsewhere")
                                                 (not (present-in-trie? conn "parked/t" "parked-elsewhere")))))
                        (let [before (count (publishes))]
                          (client/send-message (:client pub) (publish-msg "parked/t" "for later" 1 31 :version 4))
                          (tu/expect-eventually! (:ch pub) :PUBACK)
                          (is (tu/wait-until #(= 1 (count (cluster/queued conn "parked-elsewhere")))))
                          (let [[_ m] (first (cluster/queued conn "parked-elsewhere"))]
                            (is (= "for later" (String. ^bytes (:payload m))))
                            (is (= "parked/t" (:topic m)))
                            (is (= 1 (:qos m)) "the lesser of the publish's and the subscription's"))
                          (client/send-message (:client pub) (publish-msg "parked/t" "gone" 0 nil :version 4))
                          (Thread/sleep 300)
                          (is (= 1 (count (cluster/queued conn "parked-elsewhere"))) "QoS 0 is not kept")
                          (is (= before (count (publishes))) "and nothing went to the peer: nobody there is present"))

                        (testing "the client comes back — here, though it was never here — and gets it"
                          (let [c2 (tu/connect! "back" :id "parked-elsewhere" :clean-session? false :ordered? true)]
                            (try
                              (is (true? (:session-present? (:connack c2))))
                              (let [got (tu/expect-eventually! (:ch c2) :PUBLISH)]
                                (is (= "for later" (tu/payload-str got)))
                                (is (= 1 (:qos got)))
                                (Thread/sleep 200)
                                (is (= 1 (count (cluster/queued conn "parked-elsewhere")))
                                    "sent, not acknowledged: still on the cluster's queue")
                                (client/send-message (:client c2) {:packet-type :PUBACK
                                                                   :packet-identifier (:packet-identifier got)}))
                              (is (tu/wait-until #(empty? (cluster/queued conn "parked-elsewhere")))
                                  "acknowledged: off it")
                              (is (tu/wait-until #(present-in-trie? conn "parked/t" "parked-elsewhere")))
                              (is (= cluster/broker-id (:broker-id (cluster/session conn "parked-elsewhere"))))
                              (testing "with its subscription live again"
                                (client/send-message (:client pub) (publish-msg "parked/t" "live" 0 nil :version 4))
                                (is (= "live" (tu/payload-str (tu/expect-eventually! (:ch c2) :PUBLISH)))))
                              (finally
                                (tu/close! c2)))))
                        (finally
                          (tu/close! pub)))))

                  (testing "a session that went away from this broker is queued for in the cluster, not here"
                    (let [id  (tu/client-id "leaver")
                          c1  (tu/connect! "leaver" :id id :clean-session? false)
                          pub (tu/connect! "leaver-pub")]
                      (try
                        (client/send-message (:client c1) (subscribe-msg "leave/#" 1 1))
                        (tu/expect! (:ch c1) :SUBACK)
                        (is (tu/wait-until #(present-in-trie? conn "leave/t" id)))
                        (tu/close! c1)
                        (is (tu/wait-until #(and (contains? (matches conn "leave/t") id)
                                                 (not (present-in-trie? conn "leave/t" id)))))
                        (client/send-message (:client pub) (publish-msg "leave/t" "while away" 1 41 :version 4))
                        (tu/expect-eventually! (:ch pub) :PUBACK)
                        (is (tu/wait-until #(= 1 (count (cluster/queued conn id)))))
                        (let [c2 (tu/connect! "leaver" :id id :clean-session? false :ordered? true)]
                          (try
                            (is (true? (:session-present? (:connack c2))))
                            (let [got (tu/expect-eventually! (:ch c2) :PUBLISH)]
                              (is (= "while away" (tu/payload-str got)))
                              (is (nil? (tu/take! (:ch c2) 300)) "once, not once from here and once from the cluster")
                              (client/send-message (:client c2) {:packet-type :PUBACK
                                                                 :packet-identifier (:packet-identifier got)}))
                            (is (tu/wait-until #(empty? (cluster/queued conn id))))
                            (finally
                              (tu/close! c2))))

                        (testing "delivered live but not acknowledged when it left: kept for it on the cluster"
                          (let [c3 (tu/connect! "leaver" :id id :clean-session? false :ordered? true)]
                            (client/send-message (:client pub) (publish-msg "leave/t" "unacked" 1 42 :version 4))
                            (tu/expect-eventually! (:ch pub) :PUBACK)
                            (is (= "unacked" (tu/payload-str (tu/expect-eventually! (:ch c3) :PUBLISH))))
                            (is (empty? (cluster/queued conn id)) "delivered live: not queued")
                            ;; Gone without a PUBACK: the socket, not a DISCONNECT.
                            (tu/close! c3)
                            (is (tu/wait-until #(= 1 (count (cluster/queued conn id))))
                                "handed over on the way out")
                            (is (= "unacked" (String. ^bytes (:payload (second (first (cluster/queued conn id)))))))
                            (let [c4 (tu/connect! "leaver" :id id :clean-session? false :ordered? true)]
                              (try
                                (let [got (tu/expect-eventually! (:ch c4) :PUBLISH)]
                                  (is (= "unacked" (tu/payload-str got)) "and sent again on resume")
                                  (is (nil? (tu/take! (:ch c4) 300)) "once")
                                  (client/send-message (:client c4) {:packet-type :PUBACK
                                                                     :packet-identifier (:packet-identifier got)}))
                                (is (tu/wait-until #(empty? (cluster/queued conn id))))
                                (finally
                                  (tu/close! c4))))))
                        (finally
                          (tu/close! pub)))))

                  (testing "a client connected on the peer connects here: the peer is told to drop it"
                    (let [c (on-broker (cluster/->connect (connect-map "wanderer" :clean? true)) "peer-x" "run-9")]
                      (record! conn c)
                      (let [before (count (of-type :PUBLISH))
                            here   (tu/connect! "wanderer" :id "wanderer")]
                        (try
                          (is (tu/wait-until #(> (count (of-type :PUBLISH)) before)))
                          (let [p (last (of-type :PUBLISH))]
                            (is (= "$mqttkat/takeover" (:topic p)))
                            (is (= {"client-id" "wanderer" "connect-id" (:connect-id c)}
                                   (into {} (map vec) (get-in p [:properties :user-properties])))
                                "naming the connection the peer held, not the new one"))
                          (is (tu/wait-until #(= cluster/broker-id (:broker-id (cluster/session conn "wanderer")))))
                          (finally
                            (tu/close! here))))))

                  (testing "told by another broker to drop a client, this broker does — if it still holds that connection"
                    (let [victim (tu/connect-v5! "victim" :id "victim-here")
                          _      (is (tu/wait-until #(= cluster/broker-id (:broker-id (cluster/session conn "victim-here")))))
                          cid    (:connect-id (cluster/session conn "victim-here"))
                          b      (tu/connect-v5! "bridge" :id (str bridge/client-id-prefix "peer-y"))
                          order  (fn [connect-id]
                                   (client/send-message (:client b)
                                                        (publish-msg "$mqttkat/takeover" "" 0 nil
                                                                     :properties {:user-properties
                                                                                  [["client-id" "victim-here"]
                                                                                   ["connect-id" connect-id]]})))]
                      (try
                        (order "some-other-connection")
                        (is (nil? (tu/take! (:ch victim) 300)) "a connection that is not the one named stays")
                        (order cid)
                        (let [d (tu/expect-eventually! (:ch victim) :DISCONNECT)]
                          (is (= 0x8E (bit-and 0xFF (long (:reason-code d)))) "Session taken over"))
                        (is (tu/wait-until #(false? (cluster/connected? conn "victim-here"))))
                        (finally
                          (tu/close! b victim)))))

                  (testing "the peer withdraws: forgotten, and its connection dropped"
                    (is (contains? (bridge/peers) "peer-x"))
                    (record! conn {:event :broker-down :broker-id "peer-x" :at 1})
                    (is (tu/wait-until #(and (nil? (get @(:brokers conn) "peer-x"))
                                             (nil? (get @(:brokers peer) "peer-x"))
                                             (not (contains? (bridge/peers) "peer-x")))))
                    (is (nil? (get @(:brokers peer) "peer-x"))))

                  (testing "this broker announces itself the same way"
                    @(cluster/record! conn (cluster/->broker-up "here" 1883))
                    (is (tu/wait-until #(= "here" (:host (get @(:brokers peer) cluster/broker-id)))))
                    (is (= 1883 (:port (get @(:brokers peer) cluster/broker-id))))
                    (is (= cluster/incarnation (:incarnation (get @(:brokers peer) cluster/broker-id))))

                    (testing "and reports how it is doing, for every broker's console"
                      (events/emit! {:event :broker-sample :stats {:clients 3 :in 10 :out 20 :version "v"}})
                      (is (tu/wait-until #(= 3 (get-in @(:brokers peer) [cluster/broker-id :stats :clients]))))
                      (is (number? (get-in @(:brokers peer) [cluster/broker-id :stats-at])))
                      (let [rows (state/broker-rows)
                            me   (first rows)]
                        (is (true? (:self me)) "this broker first")
                        (is (= cluster/broker-id (:id me)))
                        (is (= "here:1883" (:address me)))
                        (is (false? (:stale me)))
                        (is (= {:clients 3 :in 10 :out 20 :version "v"} (:stats me)))
                        (is (str/includes? (console/brokers-page) cluster/broker-id)
                            "and the page shows it"))
                      (testing "a report from a run that is not the announced one is ignored"
                        (record! conn (assoc (cluster/->broker-stats {:clients 99}) :incarnation "old-run"))
                        (Thread/sleep 200)
                        (is (= 3 (get-in @(:brokers peer) [cluster/broker-id :stats :clients]))))
                      (testing "a report from a broker that never announced conjures nothing"
                        (record! conn (assoc (cluster/->broker-stats {:clients 5}) :broker-id "ghost"))
                        (Thread/sleep 200)
                        (is (nil? (get @(:brokers peer) "ghost")))))

                    @(cluster/record! conn (cluster/->broker-down))
                    (is (tu/wait-until #(nil? (get @(:brokers peer) cluster/broker-id)))))
                  (finally
                    (.stop server 100)))))

            (testing "an anonymous client is recorded under the id it was given"
              (let [c   (tu/connect-v5! "anon" :id "")
                    id  (get-in c [:connack :properties :assigned-client-identifier])]
                (try
                  (is (string? id))
                  (is (tu/wait-until #(= 1 (cluster/connections conn id))))
                  (finally
                    (tu/close! c)))))
            (finally
              (cluster/detach!))))

        (finally
          (cluster/close! peer)
          (cluster/close! conn))))))
