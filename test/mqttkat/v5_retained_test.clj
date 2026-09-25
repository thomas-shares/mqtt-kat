(ns mqttkat.v5-retained-test
  "Retained messages under MQTT 5.

   §3.3.1.3: a retained message is the last one published on a topic, kept and
   given to every later subscriber. What is kept has to be the *message* — its
   properties as much as its payload — because a subscriber arriving later
   should not be able to tell it was not there at the time."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [mqttkat.client :as client]
            [mqttkat.test-util :as tu]))

(use-fixtures :once tu/broker-fixture)

(defn- publish! [c topic text & [props]]
  (tu/send-v5! c (cond-> {:packet-type :PUBLISH :topic topic :qos 0
                          :payload (.getBytes ^String text "UTF-8")
                          :retain? true :duplicate? false}
                   props (assoc :properties props))))

(deftest ^:portable a-retained-message-keeps-its-properties
  (testing "§3.3.1.3: the replay carries what the publisher sent"
    ;; *retained* stored only the QoS and the payload, so content type,
    ;; response topic, correlation data and user properties were all dropped on
    ;; the way in and could not come back. Live subscribers saw them; anyone
    ;; who subscribed afterwards did not, which is the difference a retained
    ;; message exists to remove.
    (let [topic (tu/topic "retain-props")
          pub   (tu/connect-v5! "retain-props-pub")]
      (try
        (publish! pub topic "kept" {:content-type    "text/plain"
                                    :user-properties [["a" "2"] ["c" "3"]]})
        (Thread/sleep 200)
        (let [sub (tu/connect-v5! "retain-props-sub")]
          (try
            (tu/send-v5! sub {:packet-type :SUBSCRIBE :packet-identifier 1
                              :topics [{:qos 0 :topic-filter topic}]})
            (tu/expect! (:ch sub) :SUBACK)
            (let [m (tu/expect-eventually! (:ch sub) :PUBLISH 3000)
                  p (:properties m)]
              (is (= "kept" (tu/payload-str m)))
              (is (true? (:retain? m)) "§3.3.1.3: replayed with RETAIN set")
              (is (= "text/plain" (:content-type p)))
              (is (= [["a" "2"] ["c" "3"]] (mapv vec (:user-properties p)))))
            (finally (tu/close! sub))))
        (finally
          ;; An empty payload clears it, so the next test starts clean.
          (publish! pub topic "")
          (tu/close! pub))))))

(deftest ^:portable a-retained-message-is-replaced-not-merged
  (testing "the newest publish is the one kept, properties and all"
    (let [topic (tu/topic "retain-replace")
          pub   (tu/connect-v5! "retain-replace-pub")]
      (try
        (publish! pub topic "first"  {:content-type "text/plain"})
        (publish! pub topic "second" {:content-type "application/json"})
        (Thread/sleep 200)
        (let [sub (tu/connect-v5! "retain-replace-sub")]
          (try
            (tu/send-v5! sub {:packet-type :SUBSCRIBE :packet-identifier 1
                              :topics [{:qos 0 :topic-filter topic}]})
            (tu/expect! (:ch sub) :SUBACK)
            (let [m (tu/expect-eventually! (:ch sub) :PUBLISH 3000)]
              (is (= "second" (tu/payload-str m)))
              (is (= "application/json" (:content-type (:properties m)))
                  "the first publish's content type did not linger"))
            (finally (tu/close! sub))))
        (finally
          (publish! pub topic "")
          (tu/close! pub))))))

(deftest ^:portable a-version-4-subscriber-gets-no-properties
  (testing "a 3.1.1 client cannot be sent a property block"
    ;; It would read the property length as the first byte of the payload.
    (let [topic (tu/topic "retain-v4")
          pub   (tu/connect-v5! "retain-v4-pub")]
      (try
        (publish! pub topic "kept" {:content-type "text/plain"})
        (Thread/sleep 200)
        (let [sub (tu/connect! "retain-v4-sub" :ordered? true)]
          (try
            (client/send-message
             (:client sub) {:packet-type :SUBSCRIBE :packet-identifier 1
                            :topics [{:qos 0 :topic-filter topic}]})
            (tu/expect! (:ch sub) :SUBACK)
            (let [m (tu/expect-eventually! (:ch sub) :PUBLISH 3000)]
              (is (= "kept" (tu/payload-str m)))
              (is (not (contains? m :properties))))
            (finally (tu/close! sub))))
        (finally
          (publish! pub topic "")
          (tu/close! pub))))))

(deftest ^:portable retain-as-published-at-every-qos
  (testing "§3.8.3.1: the flag the publisher set, kept for this subscription"
    ;; send-publish! hard-coded :retain? false, so only QoS 0 ever carried the
    ;; flag. A bridge subscribing with Retain As Published at QoS 1 saw every
    ;; retained message arrive as an ordinary one, and mirrored it onward as
    ;; ordinary — which is exactly the case the option exists for.
    (doseq [qos [0 1 2]]
      (let [topic (tu/topic (str "rap-" qos))
            sub   (tu/connect-v5! (str "rap-sub-" qos))
            pub   (tu/connect-v5! (str "rap-pub-" qos))]
        (try
          (tu/send-v5! sub {:packet-type :SUBSCRIBE :packet-identifier 1
                            :topics [{:qos 2 :topic-filter topic
                                      :retain-as-published? true}]})
          (tu/expect! (:ch sub) :SUBACK)
          (doseq [[text retained?] [["plain" false] ["retained" true]]]
            (tu/send-v5! pub (cond-> {:packet-type :PUBLISH :topic topic :qos qos
                                      :payload (.getBytes ^String text "UTF-8")
                                      :retain? retained? :duplicate? false}
                               (pos? qos) (assoc :packet-identifier (inc qos))))
            (when (= 2 qos)
              (tu/expect-eventually! (:ch pub) :PUBREC 2000)
              (tu/send-v5! pub {:packet-type :PUBREL :packet-identifier (inc qos)})))
          (let [a (tu/expect-eventually! (:ch sub) :PUBLISH 3000)]
            (is (= "plain" (tu/payload-str a)) (str "qos " qos))
            (is (false? (boolean (:retain? a))) (str "not retained, at qos " qos))
            (when (pos? (long (:qos a)))
              (tu/send-v5! sub {:packet-type :PUBACK
                                :packet-identifier (:packet-identifier a)})))
          (let [b (tu/expect-eventually! (:ch sub) :PUBLISH 3000)]
            (is (= "retained" (tu/payload-str b)) (str "qos " qos))
            (is (true? (boolean (:retain? b)))
                (str "published retained, so delivered retained, at qos " qos)))
          (finally
            (tu/send-v5! pub {:packet-type :PUBLISH :topic topic :qos 0
                              :payload (byte-array 0) :retain? true :duplicate? false})
            (tu/close! sub pub)))))))

(deftest ^:portable a-replayed-retained-message-says-so-at-every-qos
  (testing "§3.3.1.3: a replay to a new subscriber always has RETAIN set"
    (doseq [qos [1 2]]
      (let [topic (tu/topic (str "replay-" qos))
            pub   (tu/connect-v5! (str "replay-pub-" qos))]
        (try
          (tu/send-v5! pub {:packet-type :PUBLISH :topic topic :qos 0
                            :payload (.getBytes "kept" "UTF-8")
                            :retain? true :duplicate? false})
          (Thread/sleep 200)
          (let [sub (tu/connect-v5! (str "replay-sub-" qos))]
            (try
              (tu/send-v5! sub {:packet-type :SUBSCRIBE :packet-identifier 1
                                :topics [{:qos qos :topic-filter topic}]})
              (tu/expect! (:ch sub) :SUBACK)
              (let [m (tu/expect-eventually! (:ch sub) :PUBLISH 3000)]
                (is (= "kept" (tu/payload-str m)))
                (is (true? (boolean (:retain? m)))
                    (str "replayed with RETAIN, subscribed at qos " qos)))
              (finally (tu/close! sub))))
          (finally
            (tu/send-v5! pub {:packet-type :PUBLISH :topic topic :qos 0
                              :payload (byte-array 0) :retain? true :duplicate? false})
            (tu/close! pub)))))))
