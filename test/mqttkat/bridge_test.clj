(ns mqttkat.bridge-test
  "The bridge keeps to the peer's Receive Maximum (§4.9).

   A stand-in peer: a listener that answers CONNECT with a CONNACK carrying a
   Receive Maximum of two, keeps every packet it is sent, and acknowledges
   only when the test says so — which is what lets the window be watched
   filling and emptying."
  (:require [clojure.test :refer [deftest is testing]]
            [mqttkat.bridge :as bridge]
            [mqttkat.test-util :as tu])
  (:import [java.nio.channels Selector SocketChannel]
           [org.mqttkat MqttHandler]
           [org.mqttkat.packages MqttConnAck MqttPubAck MqttPubComp MqttPubRec]
           [org.mqttkat.server Connection MqttServer]))

(defn- peer
  "{:server :port :received (atom [msg]) :key (atom client-key)}, answering
   CONNECT with `connack` — nil to answer nothing."
  [connack]
  (let [received (atom [])
        server   (atom nil)
        key      (atom nil)
        handler  (MqttHandler.
                  ^clojure.lang.IFn
                  (fn [{:keys [packet-type client-key] :as msg} _]
                    (reset! key client-key)
                    (swap! received conj (dissoc msg :client-key))
                    (when (and connack (= :CONNECT packet-type))
                      (.sendMessageBuffer ^MqttServer @server [client-key] (MqttConnAck/encode connack))))
                  1)
        s        (doto (MqttServer. "127.0.0.1" 0 handler) (.start))]
    (reset! server s)
    {:server s :port (.getPort s) :received received :key key}))

(defn- answer! [{:keys [server key]} buf]
  (.sendMessageBuffer ^MqttServer server [@key] buf))

(defn- publishes [{:keys [received]}]
  (filterv #(= :PUBLISH (:packet-type %)) @received))

(defn- of-type [{:keys [received]} t]
  (filterv #(= t (:packet-type %)) @received))

(def connack-2
  {:packet-type :CONNACK :protocol-version 5 :session-present? false :reason-code 0
   :properties {:receive-maximum 2}})

(defn- send! [p id topic qos]
  (future (bridge/send-to! "me" id {:host "127.0.0.1" :port (:port p)} [] topic
                           {:qos qos :payload (.getBytes "x") :properties {}})))

(deftest the-bridge-keeps-to-the-peers-receive-maximum
  (testing "QoS 1: two in flight, and the third only once one is acknowledged"
    (let [p (peer connack-2)]
      (try
        (let [sent (doall (for [i (range 3)] (send! p "peer-1" (str "t/" i) 1)))]
          (is (tu/wait-until #(= 2 (count (publishes p)))))
          (Thread/sleep 300)
          (is (= 2 (count (publishes p))) "not a third while two are unacknowledged")
          (answer! p (MqttPubAck/encode {:packet-type :PUBACK :protocol-version 5
                                         :packet-identifier (:packet-identifier (first (publishes p)))
                                         :reason-code 0}))
          (is (tu/wait-until #(= 3 (count (publishes p)))) "the third goes once a slot is free")
          (run! deref sent))
        (finally (bridge/drop! "peer-1") (.stop ^MqttServer (:server p) 100)))))

  (testing "QoS 2: a PUBREC is answered with a PUBREL, and the slot comes back on the PUBCOMP"
    (let [p (peer connack-2)]
      (try
        (let [sent (doall (for [i (range 3)] (send! p "peer-2" (str "t/" i) 2)))]
          (is (tu/wait-until #(= 2 (count (publishes p)))))
          (let [id (:packet-identifier (first (publishes p)))]
            (answer! p (MqttPubRec/encode {:packet-type :PUBREC :protocol-version 5 :packet-identifier id :reason-code 0}))
            (is (tu/wait-until #(= 1 (count (of-type p :PUBREL)))) "the handshake goes on")
            (Thread/sleep 300)
            (is (= 2 (count (publishes p))) "a PUBREC alone frees nothing")
            (answer! p (MqttPubComp/encode {:packet-type :PUBCOMP :protocol-version 5 :packet-identifier id :reason-code 0}))
            (is (tu/wait-until #(= 3 (count (publishes p)))) "the PUBCOMP does"))
          (run! deref sent))
        (finally (bridge/drop! "peer-2") (.stop ^MqttServer (:server p) 100)))))

  (testing "a PUBREC refusing the message ends the flow and frees the slot"
    (let [p (peer connack-2)]
      (try
        (let [sent (doall (for [i (range 3)] (send! p "peer-3" (str "t/" i) 2)))]
          (is (tu/wait-until #(= 2 (count (publishes p)))))
          (answer! p (MqttPubRec/encode {:packet-type :PUBREC :protocol-version 5
                                         :packet-identifier (:packet-identifier (first (publishes p)))
                                         :reason-code 0x80}))
          (is (tu/wait-until #(= 3 (count (publishes p)))))
          (is (empty? (of-type p :PUBREL)) "and no PUBREL for a refused message")
          (run! deref sent))
        (finally (bridge/drop! "peer-3") (.stop ^MqttServer (:server p) 100)))))

  (testing "QoS 0 is not held back"
    (let [p (peer connack-2)]
      (try
        (run! deref (doall (for [i (range 5)] (send! p "peer-4" (str "t/" i) 0))))
        (is (tu/wait-until #(= 5 (count (publishes p)))))
        (finally (bridge/drop! "peer-4") (.stop ^MqttServer (:server p) 100)))))

  (testing "a peer that never answers the CONNECT is not used"
    (let [p (peer nil)]
      (try
        @(send! p "peer-5" "t/x" 1)
        (is (empty? (publishes p)) "nothing sent into a connection that was never accepted")
        (is (not (contains? (bridge/peers) "peer-5")))
        (finally (bridge/drop! "peer-5") (.stop ^MqttServer (:server p) 100))))))

(defn- bare-connection
  "A Connection standing in for a publisher: a real SelectionKey, never
   started, so only its pause bookkeeping is exercised."
  ^Connection [^Selector selector]
  (let [ch (doto (SocketChannel/open) (.configureBlocking false))]
    (Connection. (.register ch selector 0) ch nil)))

(defn- puback! [p id]
  (answer! p (MqttPubAck/encode {:packet-type :PUBACK :protocol-version 5
                                 :packet-identifier id :reason-code 0})))

(deftest a-full-window-does-not-hold-up-the-caller
  (testing "send-to! queues and returns; the link's own thread does the waiting"
    ;; It used to wait for the slot itself — on one of the broker's four
    ;; handler threads, which every client shares. Two brokers doing that to
    ;; each other stalled both.
    (let [p (peer connack-2)]
      (try
        (let [started (System/nanoTime)]
          (dotimes [i 10]
            (bridge/send-to! "me" "peer-6" {:host "127.0.0.1" :port (:port p)} [] (str "t/" i)
                             {:qos 1 :payload (.getBytes "x") :properties {}}))
          (is (< (/ (- (System/nanoTime) started) 1e6) 1000.0)
              "ten sends into a window of two, none of them waiting"))
        (is (tu/wait-until #(= 2 (count (publishes p)))))
        (doseq [{:keys [packet-identifier]} (publishes p)] (puback! p packet-identifier))
        (is (tu/wait-until #(= 4 (count (publishes p)))) "and the rest follow as slots come back")
        (finally (bridge/drop! "peer-6") (.stop ^MqttServer (:server p) 100))))))

(deftest a-publisher-is-held-while-the-link-is-behind
  (testing "past queue-pause-at its publisher stops being read, and is read again once the queue drains"
    (with-open [selector (Selector/open)]
      (let [p         (peer connack-2)
            publisher (bare-connection selector)]
        (try
          (with-redefs [bridge/queue-pause-at  4
                        bridge/queue-resume-at 1]
            (dotimes [i 10]
              (bridge/send-to! "me" "peer-7" {:host "127.0.0.1" :port (:port p)} [] (str "t/" i)
                               {:qos 1 :payload (.getBytes "x") :properties {} :publisher publisher}))
            (is (.isReadingPaused publisher) "held: the peer has acknowledged nothing")
            ;; Acknowledge whatever has been sent, until everything has.
            (let [acked (atom #{})]
              (is (tu/wait-until
                   (fn []
                     (doseq [{:keys [packet-identifier]} (publishes p)
                             :when (not (@acked packet-identifier))]
                       (swap! acked conj packet-identifier)
                       (puback! p packet-identifier))
                     (= 10 (count (publishes p))))
                   5000)))
            (is (tu/wait-until #(not (.isReadingPaused publisher)))
                "let go once the queue is down to queue-resume-at"))
          (finally (bridge/drop! "peer-7") (.stop ^MqttServer (:server p) 100))))))

  (testing "a link that is dropped lets go of what it held"
    (with-open [selector (Selector/open)]
      (let [p         (peer connack-2)
            publisher (bare-connection selector)]
        (try
          (with-redefs [bridge/queue-pause-at  2
                        bridge/queue-resume-at 0]
            (dotimes [i 6]
              (bridge/send-to! "me" "peer-8" {:host "127.0.0.1" :port (:port p)} [] (str "t/" i)
                               {:qos 1 :payload (.getBytes "x") :properties {} :publisher publisher}))
            (is (.isReadingPaused publisher))
            (bridge/drop! "peer-8")
            (is (tu/wait-until #(not (.isReadingPaused publisher)))
                "a publisher held by a link that is gone would never be read again"))
          (finally (bridge/drop! "peer-8") (.stop ^MqttServer (:server p) 100)))))))

(deftest what-does-not-reach-the-peer-is-handed-back
  (testing "a peer that cannot be reached: on-lost, for every QoS 1 message"
    (let [dead-port (with-open [s (java.net.ServerSocket. 0)] (.getLocalPort s))
          lost      (atom 0)]
      (try
        (dotimes [i 3]
          (bridge/send-to! "me" "peer-9" {:host "127.0.0.1" :port dead-port} [] (str "t/" i)
                           {:qos 1 :payload (.getBytes "x") :properties {} :on-lost #(swap! lost inc)}))
        (is (tu/wait-until #(= 3 @lost) 5000))
        (finally (bridge/drop! "peer-9")))))

  (testing "written and not acknowledged when the link goes: handed back; acknowledged: not"
    (let [p    (peer connack-2)
          lost (atom #{})]
      (try
        (doseq [i (range 4)]
          (bridge/send-to! "me" "peer-10" {:host "127.0.0.1" :port (:port p)} [] (str "t/" i)
                           {:qos 1 :payload (.getBytes (str i)) :properties {}
                            :on-lost #(swap! lost conj i)}))
        (is (tu/wait-until #(= 2 (count (publishes p)))))
        (answer! p (MqttPubAck/encode {:packet-type :PUBACK :protocol-version 5
                                       :packet-identifier (:packet-identifier (first (publishes p)))
                                       :reason-code 0}))
        (is (tu/wait-until #(= 3 (count (publishes p)))))
        (bridge/drop! "peer-10")
        (is (tu/wait-until #(= #{1 2 3} @lost))
            "the two in flight and the one still queued; not the one acknowledged")
        (finally (bridge/drop! "peer-10") (.stop ^MqttServer (:server p) 100)))))

  (testing "QoS 0 has nothing to hand back"
    (let [dead-port (with-open [s (java.net.ServerSocket. 0)] (.getLocalPort s))
          lost      (atom 0)]
      (try
        (bridge/send-to! "me" "peer-11" {:host "127.0.0.1" :port dead-port} [] "t/0"
                         {:qos 0 :payload (.getBytes "x") :properties {} :on-lost #(swap! lost inc)})
        (Thread/sleep 500)
        (is (zero? @lost))
        (finally (bridge/drop! "peer-11"))))))

(deftest a-peer-still-reporting-is-waited-for
  ;; A load run with nothing killed lost five million deliveries: a broker
  ;; stops reading a bridge while one of its subscribers catches up, the
  ;; other end saw nothing acknowledged for five seconds and dropped the
  ;; link, and what it handed back was queued for clients still connected
  ;; there, who never read it.
  (with-redefs [bridge/window-wait-ms 200]
    (testing "a full window on a peer the cluster still hears from: waited on, nothing handed back"
      (let [p    (peer connack-2)
            lost (atom 0)]
        (try
          (reset! bridge/peer-alive? (fn [id] (= "peer-20" id)))
          (dotimes [i 3]
            (bridge/send-to! "me" "peer-20" {:host "127.0.0.1" :port (:port p)} [] (str "t/" i)
                             {:qos 1 :payload (.getBytes "x") :properties {} :on-lost #(swap! lost inc)}))
          (is (tu/wait-until #(= 2 (count (publishes p)))))
          (Thread/sleep 1000)
          (is (zero? @lost) "five times the wait, and nothing handed back")
          (puback! p (:packet-identifier (first (publishes p))))
          (is (tu/wait-until #(= 3 (count (publishes p)))) "and the third goes on the same link once a slot frees")
          (is (zero? @lost))
          (finally
            (reset! bridge/peer-alive? nil)
            (bridge/drop! "peer-20")
            (.stop ^MqttServer (:server p) 100)))))

    (testing "one it does not hear from is dropped, as before"
      (let [p    (peer connack-2)
            lost (atom 0)]
        (try
          (reset! bridge/peer-alive? (constantly false))
          (dotimes [i 3]
            (bridge/send-to! "me" "peer-21" {:host "127.0.0.1" :port (:port p)} [] (str "t/" i)
                             {:qos 1 :payload (.getBytes "x") :properties {} :on-lost #(swap! lost inc)}))
          (is (tu/wait-until #(= 3 @lost) 5000) "the two in flight and the one waiting for a slot")
          (finally
            (reset! bridge/peer-alive? nil)
            (bridge/drop! "peer-21")
            (.stop ^MqttServer (:server p) 100)))))))

(deftest a-peer-that-refuses-is-left-alone-for-a-while
  (testing "one failed connect marks it down; what follows is handed back without trying again"
    ;; The mark was never set: the link's thread compared itself with the
    ;; stored link by identity, and the stored one was a different map. Every
    ;; message then opened a connection of its own to a broker that was gone.
    (let [dead-port (with-open [s (java.net.ServerSocket. 0)] (.getLocalPort s))
          lost      (atom 0)]
      (try
        (bridge/send-to! "me" "peer-12" {:host "127.0.0.1" :port dead-port} [] "t/0"
                         {:qos 1 :payload (.getBytes "x") :properties {} :on-lost #(swap! lost inc)})
        (is (tu/wait-until #(= 1 @lost)))
        (is (tu/wait-until #(some? (:down-until (get @@#'bridge/connections "peer-12"))))
            "marked down")
        (bridge/send-to! "me" "peer-12" {:host "127.0.0.1" :port dead-port} [] "t/1"
                         {:qos 1 :payload (.getBytes "x") :properties {} :on-lost #(swap! lost inc)})
        (is (= 2 @lost) "handed back at once, without a connection attempt")
        (finally (bridge/drop! "peer-12"))))))

(deftest nothing-sent-at-a-dead-peer-goes-missing
  (testing "sends racing the link's end are each handed back exactly once"
    ;; A send that found the link still running, and queued after its thread
    ;; had emptied the queue for the last time, sat in a dead queue: one
    ;; message in ten, sent every half second at a killed broker.
    (dotimes [round 20]
      (let [dead-port (with-open [s (java.net.ServerSocket. 0)] (.getLocalPort s))
            peer-id   (str "peer-race-" round)
            lost      (atom 0)
            sends     50]
        (try
          (->> (range 4)
               (mapv (fn [_]
                       (future
                         (dotimes [_ (quot sends 4)]
                           (bridge/send-to! "me" peer-id {:host "127.0.0.1" :port dead-port} [] "t"
                                            {:qos 1 :payload (.getBytes "x") :properties {}
                                             :on-lost #(swap! lost inc)})))))
               (run! deref))
          (is (tu/wait-until #(= (* 4 (quot sends 4)) @lost) 5000)
              (str "round " round ": " @lost " of " (* 4 (quot sends 4)) " handed back"))
          (Thread/sleep 50)
          (is (= (* 4 (quot sends 4)) @lost) "and none twice")
          (finally (bridge/drop! peer-id)))))))

(defn- send-held! [p peer-id i undelivered]
  (bridge/send-to! "me" peer-id {:host "127.0.0.1" :port (:port p)} [] (str "t/" i)
                   {:qos 1 :payload (.getBytes (str i)) :properties {}
                    :msg-key (str "k" i)
                    :on-lost #(throw (AssertionError. "taken by the peer, so never lost"))
                    :on-undelivered #(swap! undelivered conj i)}))

(defn- ack-all! [p]
  (doseq [m (publishes p)]
    (answer! p (MqttPubAck/encode {:packet-type :PUBACK :protocol-version 5
                                   :packet-identifier (:packet-identifier m)
                                   :reason-code 0}))))

(deftest what-the-peer-took-and-did-not-deliver-is-queued-when-it-dies
  (testing "a peer that dies after its PUBACK, before saying its subscribers have it"
    ;; Its PUBACK used to end it here: the message was the peer's, and it died
    ;; in the peer's memory, on its way to a persistent subscriber that came
    ;; back on another broker to find nothing.
    (let [p           (peer connack-2)
          undelivered (atom #{})]
      (try
        (send-held! p "peer-13" 0 undelivered)
        (send-held! p "peer-13" 1 undelivered)
        (is (tu/wait-until #(= 2 (count (publishes p)))))
        (ack-all! p)
        (Thread/sleep 200)
        (bridge/settled-by! "peer-13" ["k0"])
        (is (empty? @undelivered) "nothing given up on while the peer lives")
        (.stop ^MqttServer (:server p) 100)
        (is (tu/wait-until #(= #{1} @undelivered) 5000)
            "the one it said it delivered stays delivered; the other is queued")
        (finally (bridge/drop! "peer-13") (.stop ^MqttServer (:server p) 100)))))

  (testing "the registry dropping the peer gives up on it at once"
    (let [p           (peer connack-2)
          undelivered (atom #{})]
      (try
        (send-held! p "peer-14" 0 undelivered)
        (is (tu/wait-until #(= 1 (count (publishes p)))))
        (ack-all! p)
        (Thread/sleep 200)
        (bridge/drop! "peer-14")
        (is (= #{0} @undelivered))
        (finally (bridge/drop! "peer-14") (.stop ^MqttServer (:server p) 100)))))

  (testing "this broker leaving the cluster queues nothing: its peers are alive"
    (let [p           (peer connack-2)
          undelivered (atom #{})]
      (try
        (send-held! p "peer-15" 0 undelivered)
        (is (tu/wait-until #(= 1 (count (publishes p)))))
        (ack-all! p)
        (Thread/sleep 200)
        (bridge/close-all!)
        (Thread/sleep (+ 500 bridge/awaiting-grace-ms))
        (is (empty? @undelivered))
        (finally (bridge/drop! "peer-15") (.stop ^MqttServer (:server p) 100))))))
