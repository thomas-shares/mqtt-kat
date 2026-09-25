(ns mqttkat.bridge-test
  "The bridge keeps to the peer's Receive Maximum (§4.9).

   A stand-in peer: a listener that answers CONNECT with a CONNACK carrying a
   Receive Maximum of two, keeps every packet it is sent, and acknowledges
   only when the test says so — which is what lets the window be watched
   filling and emptying."
  (:require [clojure.test :refer [deftest is testing]]
            [mqttkat.bridge :as bridge]
            [mqttkat.test-util :as tu])
  (:import [org.mqttkat MqttHandler]
           [org.mqttkat.packages MqttConnAck MqttPubAck MqttPubComp MqttPubRec]
           [org.mqttkat.server MqttServer]))

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
