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
