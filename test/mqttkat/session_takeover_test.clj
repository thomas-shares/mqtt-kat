(ns mqttkat.session-takeover-test
  "MQTT 3.1.1 §3.1.4 and MQTT 5.0 §4.13.1 — one client id, one connection.

   \"If the ClientId represents a Client already connected to the Server then
   the Server MUST disconnect the existing Client.\" This broker did not: both
   connections stayed live, and because the outbound window and the in-flight
   map are keyed by client id, the second connection inherited a window the
   first had already filled.

   That is why the Paho conformance suite cannot run end to end — every test in
   it reconnects as `myclientid` — and it is a 3.1.1 bug that the version 5
   work happened to expose rather than anything version 5 introduced."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [mqttkat.client :as client]
            [mqttkat.handlers :as h]
            [mqttkat.trie :as trie]
            [mqttkat.test-util :as tu])
  (:import [java.nio.channels SelectionKey]
           [org.mqttkat MqttStat]))

(use-fixtures :once tu/broker-fixture)

(defn- live-connections
  "Connections currently open under `id`."
  [id]
  (count (filter (fn [[k v]] (and (instance? SelectionKey k) (= id (:client-id v))))
                 @h/*clients*)))

(defn- settle [] (Thread/sleep 400))

(deftest a-second-connection-takes-over-from-the-first
  (testing "only one connection survives"
    (let [id (tu/client-id "takeover")
          a  (tu/connect-v5! nil :id id)
          b  (tu/connect-v5! nil :id id)]
      (try
        (settle)
        (is (= 1 (live-connections id))
            "the older connection must be disconnected, not left alongside")
        (is (not (client/connected? (:client a)))
            "and its socket closed, so the client knows")
        (is (client/connected? (:client b)) "while the new one carries on")
        (finally (tu/close! a b)))))

  (testing "and the new connection works normally afterwards"
    ;; The point of taking over is that the client id keeps working. A takeover
    ;; that left the id unusable would be worse than the bug.
    (let [id    (tu/client-id "takeover-works")
          topic (tu/topic "takeover")
          _     (tu/connect-v5! nil :id id)
          b     (tu/connect-v5! nil :id id)
          pub   (tu/connect-v5! "takeover-pub")]
      (try
        (settle)
        (tu/send-v5! b {:packet-type :SUBSCRIBE :packet-identifier 1
                        :topics [{:qos 0 :topic-filter topic}]})
        (tu/expect! (:ch b) :SUBACK)
        (tu/send-v5! pub {:packet-type :PUBLISH :topic topic :qos 0
                          :payload (.getBytes "after takeover" "UTF-8")
                          :retain? false :duplicate? false})
        (is (= "after takeover" (tu/payload-str (tu/expect! (:ch b) :PUBLISH 3000))))
        (finally (tu/close! b pub))))))

(deftest ^:portable the-displaced-client-is-told-why
  (testing "a version 5 client gets DISCONNECT 0x8E before the socket closes"
    ;; §4.13.1. Otherwise the client sees an unexplained close and will
    ;; reconnect, taking the connection back off whoever displaced it.
    (let [id (tu/client-id "told")
          a  (tu/connect-v5! nil :id id)]
      (try
        (let [b (tu/connect-v5! nil :id id)]
          (try
            (let [msg (tu/take! (:ch a) 3000)]
              ;; Guarded, because when this fails it is because nothing
              ;; arrived, and `(long nil)` reports that as a NullPointerException
              ;; from deep in RT — which says nothing about what went wrong.
              (is (= :DISCONNECT (:packet-type msg))
                  (str "expected a DISCONNECT, got " (pr-str msg)))
              (when (= :DISCONNECT (:packet-type msg))
                (is (= 0x8E (bit-and (long (or (:reason-code msg) 0)) 0xff))
                    "session taken over")))
            (finally (tu/close! b))))
        (finally (tu/close! a)))))

  (testing "a 3.1.1 client is only closed, having no DISCONNECT to read"
    (let [id (tu/client-id "told-v4")
          a  (tu/connect! nil :id id :ordered? true)]
      (try
        (let [b (tu/connect! nil :id id :ordered? true)]
          (try
            (is (nil? (tu/take! (:ch a) 900))
                "no packet a 3.1.1 client could not parse")
            (settle)
            (is (not (client/connected? (:client a))) "but the socket is gone")
            (finally (tu/close! b))))
        (finally (tu/close! a))))))

(deftest ^:portable a-persistent-session-survives-being-taken-over
  (testing "the replacement resumes the session rather than losing it"
    ;; §3.1.4 closes the *connection*; the session belongs to the client id and
    ;; is what the new connection is asking to continue. Discarding it here
    ;; would turn every takeover into a silent clean start.
    (let [id    (tu/client-id "takeover-persist")
          topic (tu/topic "persist")
          a     (tu/connect! nil :id id :clean-session? false :ordered? true)]
      (client/send-message (:client a)
                           {:packet-type :SUBSCRIBE :packet-identifier 1
                            :topics [{:qos 1 :topic-filter topic}]})
      (tu/expect! (:ch a) :SUBACK)
      (let [b (tu/connect! nil :id id :clean-session? false :ordered? true)]
        (try
          (settle)
          (is (true? (:session-present? (:connack b)))
              "the session the first connection built is still there")
          (finally (tu/close! a b)))))))

(deftest ^:portable other-client-ids-are-untouched
  (testing "taking over one id does not disturb another"
    (let [a (tu/connect-v5! (tu/client-id "other-a"))
          b (tu/connect-v5! (tu/client-id "other-b"))]
      (try
        (settle)
        (is (client/connected? (:client a)))
        (is (client/connected? (:client b)))
        (finally (tu/close! a b))))))

(deftest finding-the-displaced-connection-is-not-a-scan
  (testing "the lookup is by index, not by walking every client"
    ;; The connection-scale tests open fifty thousand clients. A scan of
    ;; *clients* on each CONNECT would make that quadratic — half a billion
    ;; comparisons — so the live connections are indexed by client id.
    (let [id (tu/client-id "indexed")
          c  (tu/connect-v5! nil :id id)]
      (try
        (settle)
        (is (contains? @h/*live-clients* id)
            "a live connection is registered under its client id")
        (finally (tu/close! c)))
      (settle)
      (is (not (contains? @h/*live-clients* id))
          "and deregistered when it goes, so the index cannot grow for ever"))))

(deftest a-late-teardown-leaves-the-replacement-its-state
  (testing "the displaced connection going away does not empty the new one's window"
    ;; *outbound* and *inflight* are keyed by client id, not by connection. When
    ;; the old connection's teardown runs after the new one has connected and
    ;; started sending, remove-client! used to delete both by that id and take
    ;; the replacement's in-flight messages with them. Driven by hand, because
    ;; over sockets the ordering is a race this test could not rely on losing.
    (let [id  "late-teardown"
          new {:id id}]
      (binding [h/*clients*      (atom {:old-conn {:client-id id :clean-session? true}
                                        :new-conn {:client-id id :clean-session? true}})
                h/*live-clients* (atom {id :new-conn})
                h/*outbound*     (atom {id (atom {:next-id 2 :inflight {1 new}})})
                h/*inflight*     (atom {[id 1] {:msg new :topic "t"}})]
        ;; remove-client! counts the disconnect; count a connect to match, so
        ;; the broker the rest of the suite shares is not left one short.
        (MqttStat/clientConnected)
        (h/remove-client! :old-conn)
        (is (not (contains? @h/*clients* :old-conn)) "the old connection is gone")
        (is (= :new-conn (h/live-connection id)) "the new one is still registered")
        (is (contains? @h/*outbound* id) "and keeps its outbound window")
        (is (contains? @h/*inflight* [id 1]) "and what it has in flight"))))

  (testing "with no replacement, a clean session's records still go"
    (let [id "no-replacement"]
      (binding [h/*clients*      (atom {:only-conn {:client-id id :clean-session? true}})
                h/*live-clients* (atom {id :only-conn})
                h/*outbound*     (atom {id (atom {:next-id 1 :inflight {}})})
                h/*inflight*     (atom {[id 1] {:msg {} :topic "t"}})]
        (MqttStat/clientConnected)
        (h/remove-client! :only-conn)
        (is (not (contains? @h/*outbound* id)))
        (is (not (contains? @h/*inflight* [id 1]))))))

  (testing "a persistent session is not parked over the replacement"
    ;; Parking would put the old connection's copy of the session back under
    ;; the client id, list its subscriptions as offline while the client is
    ;; connected, and start an expiry that discards the session — the
    ;; replacement's window with it — once the timer fires.
    (let [id  "late-teardown-persistent"
          sub {:topic-filter "late/t" :qos 1}
          old {:client-id id :protocol-version 5 :clean-session? false
               :properties {:session-expiry-interval 60}
               :subscribed-topics #{sub}}]
      (binding [h/*clients*         (atom {:old-conn old
                                           :new-conn (assoc old :subscribed-topics #{})})
                h/*live-clients*    (atom {id :new-conn})
                h/*outbound*        (atom {id (atom {:next-id 2 :inflight {1 {}}})})
                h/*inflight*        (atom {[id 1] {:msg {} :topic "t"}})
                h/*subscriber-trie* (atom (trie/make-trie))
                h/*offline-trie*    (atom (trie/make-trie))]
        (MqttStat/clientConnected)
        (try
          (h/remove-client! :old-conn)
          (is (not (contains? @h/*clients* :old-conn)) "the old connection is gone")
          (is (not (contains? @h/*clients* id))
              "and has not parked a session under the id the new one holds")
          (is (empty? (trie/trie-matching-vals @h/*offline-trie* "late/t"))
              "nor listed its subscriptions as offline")
          (is (contains? @h/*outbound* id) "the new connection keeps its window")
          (is (contains? @h/*inflight* [id 1]) "and what it has in flight")
          ;; Not bound like the rest: if one was scheduled it would fire on
          ;; the shared broker's state, so it goes either way.
          (finally (h/cancel-session-expiry! id)))))))

(deftest a-handed-over-release-whose-identifier-is-in-use-stays-queued
  ;; Not portable: a session resumed from the cluster, stood in for here. A
  ;; PUBREL a hand-over left on the queue under an identifier already in
  ;; flight here was taken off the queue and never sent, and the client went
  ;; on holding the identifier for it.
  (let [id       (tu/client-id "release-in-use")
        dequeued (atom [])
        queued   [["k-msg" {:topic "rel/t" :payload (.getBytes "m") :qos 2 :packet-identifier 7}]
                  ["k-rel" {:topic "rel/t" :qos 2 :packet-identifier 7 :released? true}]]]
    (reset! h/session-source
            {:my-broker-id "here"
             :resume       (fn [_] {:session       {:clean-session? false :connected? false}
                                    :subscriptions {}
                                    :queued        queued})
             :enqueue!     (fn [_ _ _] nil)
             :dequeue!     (fn [_ ks] (swap! dequeued into ks))
             :takeover!    (fn [_ _ _] nil)})
    (try
      (is (true? (h/adopt-session! id)))
      (is (= #{7} (set (keys (:inflight @(#'h/existing-outbound id)))))
          "the message is in flight under its identifier")
      (is (not (::h/released? (get-in @(#'h/existing-outbound id) [:inflight 7])))
          "the release did not replace it")
      (is (= [] @dequeued) "and is not taken off the cluster's queue")
      (is (= #{"k-msg"} (set (keys (get-in @@#'h/catching-up [id :had]))))
          "nor counted as had, so a later read finds it")
      (finally
        (reset! h/session-source nil)
        (swap! @#'h/catching-up dissoc id)
        (swap! h/*clients* dissoc id)))))

(deftest no-identifiers-until-the-hand-over-lands
  ;; Not portable: another broker, stood in for. Resumed without the old
  ;; broker's hand-over, the session gave out identifiers the client still
  ;; held messages under there, and lost a QoS 2 message to one of them.
  (let [id     (tu/client-id "gated")
        moved? (atom false)
        record (fn [] (if @moved?
                        {:clean-session? false :connected? false :broker-id "there" :connect-id "old"}
                        {:clean-session? false :connected? true :broker-id "there" :connect-id "old"}))
        msg    {:topic "gate/t" :payload (.getBytes "m") :qos 1}]
    (reset! h/session-source
            {:my-broker-id "here"
             :resume       (fn [_] {:session (record) :subscriptions {} :queued []})
             :enqueue!     (fn [_ _ _] nil)
             :dequeue!     (fn [_ _] nil)
             :takeover!    (fn [_ _ _] nil)})
    (try
      (with-redefs [h/hand-over-wait-millis 200]
        (is (true? (h/adopt-session! id)) "resumed once the wait ran out"))
      (is (nil? (h/acquire-packet-identifier! id msg)) "and gives out no identifier while it is still there")
      (Thread/sleep 400)
      (is (nil? (h/acquire-packet-identifier! id msg)) "nor a moment later")
      (reset! moved? true)
      (is (tu/wait-until #(some? (h/acquire-packet-identifier! id msg)))
          "once its record moves on, it does")
      (finally
        (reset! h/session-source nil)
        (swap! @#'h/catching-up dissoc id)
        (swap! h/*clients* dissoc id)))))

(deftest identifiers-are-given-out-again-if-the-hand-over-never-lands
  (let [id  (tu/client-id "gated-for-good")
        msg {:topic "gate/t" :payload (.getBytes "m") :qos 1}]
    (reset! h/session-source
            {:my-broker-id "here"
             :resume       (fn [_] {:session {:clean-session? false :connected? true
                                              :broker-id "there" :connect-id "old"}
                                    :subscriptions {} :queued []})
             :enqueue!     (fn [_ _ _] nil)
             :dequeue!     (fn [_ _] nil)
             :takeover!    (fn [_ _ _] nil)})
    (try
      (with-redefs [h/hand-over-wait-millis  200
                    h/identifier-gate-millis 500]
        (is (true? (h/adopt-session! id)))
        (is (nil? (h/acquire-packet-identifier! id msg)))
        (is (tu/wait-until #(some? (h/acquire-packet-identifier! id msg)))))
      (finally
        (reset! h/session-source nil)
        (swap! @#'h/catching-up dissoc id)
        (swap! h/*clients* dissoc id)))))
