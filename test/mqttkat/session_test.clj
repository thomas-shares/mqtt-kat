(ns mqttkat.session-test
  "Persistent sessions: what the broker keeps for a client that is not there.

   Both of these came from the Paho interoperability suite, which caught them
   where the unit suite did not."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [mqttkat.client :as client]
            [mqttkat.handlers :as h]
            [mqttkat.util :as util]
            [mqttkat.test-util :as tu])
  (:import [java.nio ByteBuffer]
           [org.mqttkat.packages MqttConnect MqttSubscribe]))

(defn- ^"[B" ->bytes [^ByteBuffer b]
  (let [a (byte-array (.remaining b))] (.get (.duplicate b) a) a))

(use-fixtures :once tu/broker-fixture)

(defn- subscribe-msg [topic qos id]
  {:packet-type :SUBSCRIBE :packet-identifier id
   :topics [{:qos qos :topic-filter topic}]})

(defn- publish-msg [topic payload qos id]
  (cond-> {:packet-type :PUBLISH :qos qos :topic topic :retain? false
           :duplicate false :payload payload}
    id (assoc :packet-identifier id)))

;; ── session present ──────────────────────────────────────────────────────

(deftest clean-session-is-never-told-a-session-is-present
  (testing "CONNACK reports Session Present 0 whenever CleanSession is 1"
    ;; §3.2.2.2. This reported whatever happened to be parked under the
    ;; client-id regardless of the clean-session flag, so a client asking for a
    ;; fresh session was told it had resumed one — and a client that trusts
    ;; that will not re-subscribe.
    (let [id (tu/client-id "session-present")]
      ;; Leave a session behind to be tempted by.
      (let [a (tu/connect! nil :id id :clean-session? false)]
        (client/send-message (:client a) (subscribe-msg (tu/topic "sp") 1 1))
        (tu/expect! (:ch a) :SUBACK)
        (client/send-message (:client a) {:packet-type :DISCONNECT})
        (tu/close! a))
      (tu/wait-for-parked-session! id)

      (testing "a persistent reconnect resumes it"
        (let [b (tu/connect! nil :id id :clean-session? false)]
          (is (true? (:session-present? (:connack b))))
          (client/send-message (:client b) {:packet-type :DISCONNECT})
          (tu/close! b)))
      (tu/wait-for-parked-session! id)

      (testing "a clean reconnect is told there is nothing"
        (let [c (tu/connect! nil :id id :clean-session? true)]
          (is (false? (:session-present? (:connack c)))
              "CleanSession 1 must be answered with Session Present 0")
          (tu/close! c))))))

(deftest a-clean-session-discards-what-was-stored
  (testing "connecting clean throws the old session away rather than parking it"
    ;; §3.1.2.4. The stored session used to survive a clean connect, so the
    ;; next persistent connect resumed a session the client had asked to be
    ;; rid of — and the parked entry never went away.
    (let [id (tu/client-id "discard")]
      (let [a (tu/connect! nil :id id :clean-session? false)]
        (client/send-message (:client a) (subscribe-msg (tu/topic "discard") 1 1))
        (tu/expect! (:ch a) :SUBACK)
        (client/send-message (:client a) {:packet-type :DISCONNECT})
        (tu/close! a))
      (tu/wait-for-parked-session! id)

      (let [c (tu/connect! nil :id id :clean-session? true)]
        (tu/close! c))
      ;; Wait for that connection to be torn down before asking what the broker
      ;; kept. Without this the next CONNECT could be handled before the clean
      ;; one's disconnect, and the answer depended on which won.
      (let [deadline (+ (System/currentTimeMillis) 5000)]
        (loop []
          (when (and (contains? @h/*clients* id)
                     (< (System/currentTimeMillis) deadline))
            (Thread/sleep 25)
            (recur))))
      ;; The clean connect discarded it, so a persistent one now finds nothing.
      (let [d (tu/connect! nil :id id :clean-session? false)]
        (is (false? (:session-present? (:connack d)))
            "the session should have been discarded by the clean connect")
        (tu/close! d)))))

;; ── offline message queueing ─────────────────────────────────────────────

(deftest messages-are-kept-for-an-offline-persistent-session
  (testing "QoS 1 and 2 published while a session is away arrive on reconnect"
    ;; §4.1. The subscriptions were deleted from the trie on disconnect, so a
    ;; publish in between matched nothing at all and there was nothing to keep.
    (let [id     (tu/client-id "offline")
          topic  (tu/topic "offline")
          sub    (tu/connect! nil :id id :clean-session? false)]
      (client/send-message (:client sub) (subscribe-msg topic 2 1))
      (tu/expect! (:ch sub) :SUBACK)
      (client/send-message (:client sub) {:packet-type :DISCONNECT})
      (tu/close! sub)
      (tu/wait-for-parked-session! id)

      ;; Published to nobody who is connected.
      (let [pub (tu/connect! "offline-pub")]
        (client/send-message (:client pub) (publish-msg topic "one" 1 11))
        (tu/expect-eventually! (:ch pub) :PUBACK 2000)
        (client/send-message (:client pub) (publish-msg topic "two" 2 12))
        (tu/expect-eventually! (:ch pub) :PUBREC 2000)
        (client/pubrel (:client pub) 12)
        (tu/expect-eventually! (:ch pub) :PUBCOMP 2000)
        (tu/close! pub))

      (let [deadline (+ (System/currentTimeMillis) 2000)]
        (loop []
          (when (and (< (h/pending-count id) 2)
                     (< (System/currentTimeMillis) deadline))
            (Thread/sleep 25)
            (recur))))
      (is (= 2 (h/pending-count id))
          "both messages should have been kept for the absent session")

      ;; Back again: they should be waiting.
      (let [back (tu/connect! nil :id id :clean-session? false :ordered? true :buffer 64)]
        (is (true? (:session-present? (:connack back))))
        (let [got (tu/take-n! (:ch back) 2 4000)
              payloads (set (map tu/payload-str (:PUBLISH got)))]
          (is (= #{"one" "two"} payloads)
              "both queued messages should be delivered on reconnect"))
        (tu/close! back)))))

(deftest qos-0-is-not-kept-for-an-offline-session
  (testing "at-most-once means nothing is stored for a client that is not there"
    ;; §4.1 requires this of QoS 1 and 2 only, and the interoperability suite
    ;; accepts either. Pinned so the choice is deliberate rather than accidental.
    (let [id    (tu/client-id "offline-qos0")
          topic (tu/topic "offline-qos0")
          sub   (tu/connect! nil :id id :clean-session? false)]
      (client/send-message (:client sub) (subscribe-msg topic 2 1))
      (tu/expect! (:ch sub) :SUBACK)
      (client/send-message (:client sub) {:packet-type :DISCONNECT})
      (tu/close! sub)
      (tu/wait-for-parked-session! id)

      (let [pub (tu/connect! "offline-qos0-pub")]
        (client/send-message (:client pub) (publish-msg topic "fleeting" 0 nil))
        (Thread/sleep 300)
        (tu/close! pub))
      (is (zero? (h/pending-count id))
          "a QoS 0 publish should not be kept for an absent client")

      (let [back (tu/connect! nil :id id :clean-session? false)]
        (is (nil? (tu/take! (:ch back) 500))
            "and nothing should arrive on reconnect")
        (tu/close! back)))))

;; ── retained replay ──────────────────────────────────────────────────────

(deftest retained-messages-are-replayed-at-every-qos
  (testing "a new subscription gets what is retained, whatever QoS it was published at"
    ;; The subscriber maps were rebuilt here without their :qos, and qos-2-send
    ;; dispatches on exactly that — so a retained QoS 2 message matched none of
    ;; its branches and was never replayed. QoS 0 and 1 came through, which is
    ;; why only a test covering all three catches it.
    (let [base (tu/topic "retained-all")
          pub  (tu/connect! "retained-pub")]
      (doseq [[qos suffix id] [[0 "zero" nil] [1 "one" 21] [2 "two" 22]]]
        (client/send-message (:client pub)
                             (assoc (publish-msg (str base "/" suffix) (str "at-" qos) qos id)
                                    :retain? true))
        (case (long qos)
          0 nil
          1 (tu/expect-eventually! (:ch pub) :PUBACK 2000)
          2 (do (tu/expect-eventually! (:ch pub) :PUBREC 2000)
                (client/pubrel (:client pub) id)
                (tu/expect-eventually! (:ch pub) :PUBCOMP 2000))))

      (let [sub (tu/connect! "retained-sub" :ordered? true :buffer 64)]
        (client/send-message (:client sub) (subscribe-msg (str base "/+") 2 1))
        (tu/expect-eventually! (:ch sub) :SUBACK 2000)
        (let [got      (tu/take-n! (:ch sub) 3 4000)
              payloads (set (map tu/payload-str (:PUBLISH got)))]
          (is (= #{"at-0" "at-1" "at-2"} payloads)
              "all three retained messages should be replayed, QoS 2 included"))
        (tu/close! sub))

      ;; Leave nothing retained behind for the rest of the suite.
      (doseq [[qos suffix id] [[0 "zero" nil] [1 "one" 31] [2 "two" 32]]]
        (client/send-message (:client pub)
                             (assoc (publish-msg (str base "/" suffix) "" qos id)
                                    :retain? true))
        (case (long qos)
          0 nil
          1 (tu/expect-eventually! (:ch pub) :PUBACK 2000)
          2 (do (tu/expect-eventually! (:ch pub) :PUBREC 2000)
                (client/pubrel (:client pub) id)
                (tu/expect-eventually! (:ch pub) :PUBCOMP 2000))))
      (tu/close! pub))))

;; ── abrupt disconnects ───────────────────────────────────────────────────

(defn- connected-client-ids []
  (set (keep :client-id (vals @h/*clients*))))

(deftest subscriptions-go-when-a-clean-session-disconnects
  (testing "a clean session takes its subscriptions with it"
    ;; remove-client! removed subscriptions from the trie only for a persistent
    ;; session. A clean one — the common case — left them behind pointing at a
    ;; dead SelectionKey, for the life of the broker: the trie grew by every
    ;; client that had ever connected, and every publish matched all of them.
    ;;
    ;; These clients never read, so their CONNACK and SUBACK are still in the
    ;; socket buffer when they go, which is how a real client that crashes
    ;; leaves. That part is deliberate but not what is asserted here.
    (let [topic (tu/topic "reset")
          ids   (mapv (fn [_] (tu/client-id "reset")) (range 5))]
      (doseq [id ids]
        (let [sock (java.net.Socket.)]
          (.connect sock (java.net.InetSocketAddress. ^String tu/host ^int (int tu/port)))
          (let [^java.io.OutputStream out (.getOutputStream sock)]
            (.write out ^bytes (->bytes (MqttConnect/encode
                                         {:packet-type :CONNECT :protocol-name "MQTT"
                                          :protocol-version 4 :keep-alive 0
                                          :clean-session? true :client-id id})))
            (.write out ^bytes (->bytes (MqttSubscribe/encode
                                         {:packet-type :SUBSCRIBE :packet-identifier 1
                                          :topics [{:qos 0 :topic-filter topic}]})))
            (.flush out))
          ;; No read at all, so the CONNACK and SUBACK are still sitting in the
          ;; receive buffer: this close is a reset, not a FIN.
          (Thread/sleep 100)
          (.close sock)))

      ;; Asserted against these five by name rather than against a total: the
      ;; broker is shared with the rest of the suite, and a count taken before
      ;; and after picks up whoever else happened to come or go.
      (let [deadline (+ (System/currentTimeMillis) 5000)]
        (loop []
          (when (and (some (connected-client-ids) ids)
                     (< (System/currentTimeMillis) deadline))
            (Thread/sleep 50)
            (recur))))
      (is (not-any? (connected-client-ids) ids)
          "every reset connection should have been cleaned up")
      (is (empty? (h/matching-subscribers topic))
          "and its subscriptions should have gone with it"))))
