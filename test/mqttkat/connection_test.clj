(ns mqttkat.connection-test
  "Properties that hold per connection, whatever the broker does with threads:

     - a packet split across two TCP reads is reassembled, not dropped, and
       certainly does not take the broker down with it;
     - packets are processed in the order the client sent them.

   Both are guarantees TCP already gives the broker; losing them is the
   broker's own doing."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [mqttkat.client :as client]
            [mqttkat.test-util :as tu])
  (:import [java.net Socket]
           [java.nio ByteBuffer]
           [org.mqttkat.packages MqttConnect MqttPublish MqttSubscribe]))

(use-fixtures :once tu/broker-fixture)

(defn- ^"[B" ->bytes
  "The bytes of `buf`. Hinted ^bytes because every caller hands the result
   straight to OutputStream.write or alength, and without it each of those is
   a reflective call resolved at run time."
  [^ByteBuffer buf]
  (let [a (byte-array (.remaining buf))]
    (.get (.duplicate buf) a)
    a))

(defn- subscribe-msg [topic]
  {:packet-type :SUBSCRIBE :packet-identifier 1
   :topics [{:qos 0 :topic-filter topic}]})

(deftest split-packet-is-reassembled
  (testing "a PUBLISH arriving in two TCP reads still reaches the subscriber"
    (let [topic   (tu/topic "framing")
          payload "this packet arrives in two pieces"
          sub     (tu/connect! "framing-sub")]
      (client/send-message (:client sub) (subscribe-msg topic))
      (tu/expect! (:ch sub) :SUBACK)

      ;; A raw socket, so the writes really are two separate TCP segments.
      (with-open [raw (Socket. ^String tu/host ^int (int tu/port))]
        (let [out     (.getOutputStream raw)
              connect (->bytes (MqttConnect/encode
                                {:packet-type :CONNECT :protocol-name "MQTT" :protocol-version 4
                                 :keep-alive 0 :clean-session? true :client-id "framing-raw"}))
              publish (->bytes (MqttPublish/encode
                                {:packet-type :PUBLISH :qos 0 :topic topic :retain? false
                                 :duplicate? false :payload payload}))
              cut     6]                          ;; mid-packet: inside the topic name
          (.write out connect)
          (.flush out)
          (Thread/sleep 150)
          (.write out publish 0 cut)
          (.flush out)
          (Thread/sleep 150)                      ;; force a second, separate read
          (.write out publish cut (- (alength publish) cut))
          (.flush out)

          (let [msg (tu/expect-eventually! (:ch sub) :PUBLISH 2000)]
            (is (= payload (tu/payload-str msg))))))

      ;; and the broker has to have survived it
      (let [after (tu/connect! "framing-after")]
        (is (= 0 (:connect-return-code (:connack after)))
            "the broker stopped accepting connections after a split packet")
        (tu/close! after))
      (tu/close! sub))))

(defn- ^"[B" connect-bytes [version client-id]
  (->bytes (MqttConnect/encode {:packet-type :CONNECT :protocol-name "MQTT"
                                :protocol-version version :keep-alive 60
                                :clean-session? true :client-id client-id})))

(deftest unsupported-protocol-version-is-answered-before-the-close
  (testing "the rejection CONNACK is written even though the broker is closing"
    ;; handle-not-valid-protocol-version replies 0x01 and then disconnects, so
    ;; the reply is queued on a connection that is about to be torn down.
    (let [{:keys [client ch] :as c} (tu/client!)]
      (client/send-message client {:packet-type :CONNECT :protocol-name "MQTT"
                                   :protocol-version 5 :keep-alive 60
                                   :clean-session? true :client-id "wrong-version"})
      (let [msg (tu/expect! ch :CONNACK 2000)]
        (is (= 0x01 (:connect-return-code msg))
            "unacceptable protocol version should be reported as 0x01"))
      (tu/close! c))))

(deftest client-hanging-up-mid-handshake-is-quiet
  (testing "a client that disconnects while its CONNECT is still being handled"
    ;; Closing the connection must not interrupt the thread that is running the
    ;; handler: it used to, and an ordinary rejection came out as an
    ;; InterruptedException from the middle of handle-not-valid-protocol-version.
    (dotimes [_ 5]
      (with-open [raw (Socket. ^String tu/host ^int (int tu/port))]
        (.write (.getOutputStream raw) (connect-bytes 5 ""))
        (.flush (.getOutputStream raw)))
      (Thread/sleep 60))
    (let [after (tu/connect! "after-hangup")]
      (is (= 0 (:connect-return-code (:connack after)))
          "the broker should be unbothered by clients that hang up")
      (tu/close! after))))

(deftest a-slow-subscriber-does-not-stall-a-fast-one
  (testing "a subscriber that never reads cannot hold up delivery to others"
    ;; The slow client's socket buffer fills and stays full, so the broker's
    ;; write to it cannot complete. With a shared pool of writer threads that
    ;; is head-of-line blocking for everyone; with a writer thread per
    ;; connection only the slow client is parked.
    (let [topic    (tu/topic "slow")
          messages 200
          payload  (apply str (repeat 900 "x"))
          fast     (tu/connect! "slow-fast")]
      (client/send-message (:client fast) (subscribe-msg topic))
      (tu/expect! (:ch fast) :SUBACK)

      (with-open [slow (Socket. ^String tu/host ^int (int tu/port))]
        (let [out (.getOutputStream slow)]
          (.write out (->bytes (MqttConnect/encode
                                {:packet-type :CONNECT :protocol-name "MQTT" :protocol-version 4
                                 :keep-alive 0 :clean-session? true :client-id "slow-reader"})))
          (.flush out)
          (Thread/sleep 100)
          (.write out (->bytes (MqttSubscribe/encode
                                {:packet-type :SUBSCRIBE :packet-identifier 1
                                 :topics [{:qos 0 :topic-filter topic}]})))
          (.flush out)
          (Thread/sleep 200))                    ;; and from here on it never reads

        (let [pub (tu/connect! "slow-pub")]
          (dotimes [_ messages]
            (client/send-message (:client pub) {:packet-type :PUBLISH :qos 0 :topic topic
                                                :retain? false :duplicate false :payload payload}))
          (let [deadline (+ (System/currentTimeMillis) 10000)
                received (loop [n 0]
                           (cond
                             (= n messages)                          n
                             (> (System/currentTimeMillis) deadline)  n
                             :else (if (tu/take! (:ch fast) 1000) (recur (inc n)) n)))]
            (is (= messages received)
                (str "the fast subscriber got " received " of " messages
                     " while a slow one was not reading")))
          (tu/close! pub)))
      (tu/close! fast))))

(deftest packets-sent-before-a-hangup-are-still-handled
  (testing "a publish immediately followed by a hangup is not lost"
    ;; A raw socket, because MqttClient.sendMessage only queues the bytes for
    ;; its own selector thread — closing straight after would lose the packet
    ;; on the client side and prove nothing about the broker.
    ;;
    ;; The broker's teardown has to go through the connection's own queue,
    ;; behind whatever is already in it. Handled anywhere else it races the
    ;; packets that preceded it, and the reader is shut down before reaching
    ;; them.
    (let [topic   (tu/topic "hangup")
          payload "sent just before hanging up"
          sub     (tu/connect! "hangup-sub")]
      (client/send-message (:client sub) (subscribe-msg topic))
      (tu/expect! (:ch sub) :SUBACK)
      (dotimes [i 5]
        (with-open [raw (Socket. ^String tu/host ^int (int tu/port))]
          (let [out (.getOutputStream raw)]
            (.write out (connect-bytes 4 (str "hangup-pub-" i)))
            (.write out (->bytes (MqttPublish/encode
                                  {:packet-type :PUBLISH :qos 0 :topic topic :retain? false
                                   :duplicate? false :payload payload})))
            (.flush out)))                       ;; and closed on the next line
        (let [msg (tu/expect-eventually! (:ch sub) :PUBLISH 2000)]
          (is (= payload (tu/payload-str msg))
              "the publish was dropped when the connection was torn down")))
      (tu/close! sub))))

(deftest broker-survives-keys-cancelled-by-other-threads
  (testing "a connection closed off the selector thread does not kill the loop"
    ;; The keep-alive reaper closes idle connections from a timer thread, and
    ;; disconnect-client from a connection's own thread. Either cancels a
    ;; SelectionKey that the selector thread may be about to ask about, which
    ;; raised CancelledKeyException straight out of the loop and killed it.
    ;;
    ;; This provokes the race rather than forcing it, so it can pass on a bad
    ;; build; it is here to catch a gross regression, not to prove the fix.
    (let [victims (doall (repeatedly 20 #(tu/connect! "cancel-victim" :keep-alive 1)))]
      (dotimes [_ 8]
        (let [churn (doall (repeatedly 5 #(tu/connect! "cancel-churn")))]
          (Thread/sleep 150)
          (apply tu/close! churn)))
      (apply tu/close! victims))
    (let [after (tu/connect! "cancel-after")]
      (is (= 0 (:connect-return-code (:connack after)))
          "the broker stopped answering: the selector thread is gone")
      (tu/close! after))))

(deftest packets-are-processed-in-order
  (testing "a PUBLISH sent before a SUBSCRIBE is processed before it"
    ;; The retained copy is delivered with retain? true; a live delivery to an
    ;; already-registered subscriber carries retain? false. So the flag says
    ;; which of the two the broker handled first.
    (dotimes [_ 10]
      (let [topic (tu/topic "order")
            {:keys [client ch] :as c} (tu/connect! "order")]
        (client/send-message client {:packet-type :PUBLISH :qos 0 :topic topic
                                     :retain? true :duplicate false
                                     :payload "retained by the earlier packet"})
        (client/send-message client (subscribe-msg (tu/wildcard topic)))
        (let [msg (tu/expect-eventually! ch :PUBLISH 1500)]
          (is (true? (:retain? msg))
              "the SUBSCRIBE was handled before the PUBLISH that preceded it"))
        (tu/close! c)))))
