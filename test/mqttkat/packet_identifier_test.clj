(ns mqttkat.packet-identifier-test
  "Packet identifiers: allocation, release, and the two ways the old global
   pool could stop the broker for good.

   Identifiers used to come from one 1024-slot core.async channel shared by
   every client, taken with a blocking <!!. These cover what replaced it."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [mqttkat.client :as client]
            [mqttkat.handlers :as h]
            [mqttkat.test-util :as tu]))

(def ^:private msg {:topic "some/topic" :payload "some payload" :qos 1})

(defn- fresh
  "Run `f` against an empty *outbound*, so these do not see, or leave, state
   belonging to the broker the rest of the suite is sharing."
  [f]
  (binding [h/*outbound* (atom {})] (f)))

(deftest identifiers-are-scoped-to-one-client
  (fresh
   (fn []
     (testing "two clients each own the full identifier space (§2.3.1)"
       (is (= 1 (h/acquire-packet-identifier! "client-a" msg)))
       (is (= 1 (h/acquire-packet-identifier! "client-b" msg))
           "the pool used to be global, so these two competed for one space")
       (is (= 1 (h/inflight-count "client-a")))
       (is (= 1 (h/inflight-count "client-b")))))))

(deftest the-window-bounds-one-client-and-does-not-block
  (fresh
   (fn []
     (let [ids (doall (repeatedly (inc h/inflight-window)
                                  #(h/acquire-packet-identifier! "c" msg)))]
       (is (= h/inflight-window (count (remove nil? ids)))
           "the window is what bounds a client, not the identifier space")
       (is (nil? (last ids))
           "past the window it returns nil - the old take blocked the caller here")
       (is (apply distinct? (remove nil? ids))
           "no identifier is issued twice while it is in flight")))))

(deftest an-acknowledgement-never-issued-is-ignored
  (fresh
   (fn []
     (let [id (h/acquire-packet-identifier! "c" msg)]
       (is (= msg (h/release-packet-identifier! "c" id))
           "a real acknowledgement hands back what was in flight")
       (is (nil? (h/release-packet-identifier! "c" id))
           "the same acknowledgement twice must not put a live identifier back")
       (is (nil? (h/release-packet-identifier! "c" 4242))
           "nor an acknowledgement for something never sent")
       (is (nil? (h/release-packet-identifier! "who?" 1))
           "nor one from a client with nothing outstanding")
       (is (zero? (h/inflight-count "c")))))))

(deftest identifiers-come-back-when-acknowledged
  (fresh
   (fn []
     (dotimes [_ (* 4 h/inflight-window)]
       (let [id (h/acquire-packet-identifier! "c" msg)]
         (is (some? id) "an acknowledged identifier has to become available again")
         (h/release-packet-identifier! "c" id)))
     (is (zero? (h/inflight-count "c"))))))

(deftest a-client-that-vanishes-takes-its-identifiers-with-it
  (testing "a disconnect with messages outstanding costs the broker nothing"
    ;; This is the leak. The old pool got identifiers back only through
    ;; PUBACK/PUBCOMP, so a client that dropped with messages in flight burned
    ;; them permanently; after 1024 of those the blocking take never returned
    ;; and QoS 1/2 delivery stopped broker-wide, with nothing logged.
    (fresh
     (fn []
       (dotimes [_ h/inflight-window]
         (h/acquire-packet-identifier! "gone" msg))
       (is (= h/inflight-window (h/inflight-count "gone")))
       (swap! h/*outbound* dissoc "gone")          ; what remove-client! does
       (is (zero? (h/inflight-count "gone")))
       (is (= 1 (h/acquire-packet-identifier! "fresh" msg))
           "a new client starts with its whole identifier space")))))

(deftest the-counter-wraps-and-never-yields-zero
  (fresh
   (fn []
     (reset! h/*outbound* {"c" {:next-id (dec h/max-packet-identifier) :inflight {}}})
     (is (= h/max-packet-identifier (h/acquire-packet-identifier! "c" msg)))
     (is (= 1 (h/acquire-packet-identifier! "c" msg))
         "wraps back to 1; 0 is not a valid identifier"))))

;; ── against a live broker ────────────────────────────────────────────────

(use-fixtures :once tu/broker-fixture)

(deftest a-stray-puback-does-not-wedge-the-connection
  (testing "a PUBACK for an identifier never issued leaves the client usable"
    ;; PUBACK used to return the client's identifier to the shared channel
    ;; without checking it had ever been issued. With the pool full that
    ;; >!! blocked the connection's reader thread for good; short of full it
    ;; quietly put a live identifier back into circulation for reuse.
    (let [{:keys [client ch] :as c} (tu/connect! "stray-ack")]
      (client/send-message client {:packet-type :PUBACK :packet-identifier 4242})
      ;; A SUBSCRIBE rather than a ping only because the test client cannot
      ;; encode PINGREQ; any packet needing a reply proves the same thing.
      (client/send-message client {:packet-type :SUBSCRIBE :packet-identifier 1
                                   :topics [{:qos 0 :topic-filter (tu/topic "stray")}]})
      (is (some? (tu/expect-eventually! ch :SUBACK 2000))
          "the reader thread should have carried on past the stray ack")
      (tu/close! c))))
