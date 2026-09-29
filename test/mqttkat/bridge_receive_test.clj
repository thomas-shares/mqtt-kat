(ns mqttkat.bridge-receive-test
  "What a broker does with a publish that arrives over another broker's
   bridge. No Rama needed: a bridge is known by its client id, and what it
   is to serve travels in the publish's user properties.

   Not ^:portable: bridges, and the properties they carry, are this broker's
   own, not anything the MQTT spec says about another broker."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [mqttkat.bridge :as bridge]
            [mqttkat.test-util :as tu]))

(use-fixtures :once tu/broker-fixture)

(defn- subscribe! [c topic-filter]
  (tu/send-v5! c {:packet-type :SUBSCRIBE :packet-identifier 1
                  :topics [{:qos 1 :topic-filter topic-filter}]})
  (tu/expect! (:ch c) :SUBACK))

(deftest a-copy-for-its-groups-alone-reaches-nobody-else
  (testing "one member of the named group gets it; the ordinary subscriber does not"
    ;; Sent when the broker chosen to serve a shared group could not be
    ;; reached: this broker's ordinary subscribers had their copy when the
    ;; message was first forwarded, and must not get it a second time.
    (let [topic  (tu/topic "groups-only")
          member (tu/connect-v5! "go-member")
          plain  (tu/connect-v5! "go-plain")
          peer   (tu/connect-v5! "go-bridge" :id (str bridge/client-id-prefix "go-peer"))]
      (try
        (subscribe! member (str "$share/g/" topic))
        (subscribe! plain topic)
        (tu/send-v5! peer {:packet-type :PUBLISH :topic topic :qos 1 :packet-identifier 9
                           :payload (.getBytes "for the group" "UTF-8") :retain? false :duplicate? false
                           :properties {:user-properties [[bridge/share-property (str "g/" topic)]
                                                          [bridge/groups-only-property "1"]]}})
        (tu/expect-eventually! (:ch peer) :PUBACK 2000)
        (let [m (tu/expect-eventually! (:ch member) :PUBLISH 2000)]
          (is (= "for the group" (tu/payload-str m)))
          (is (empty? (:user-properties (:properties m)))
              "the instructions were for this broker, not passed on"))
        (is (nil? (tu/take! (:ch plain) 700)) "nothing for the ordinary subscriber")
        (finally (tu/close! member plain peer)))))

  (testing "without the mark, a bridged copy is for the ordinary subscribers too, as before"
    (let [topic  (tu/topic "groups-and-more")
          member (tu/connect-v5! "gm-member")
          plain  (tu/connect-v5! "gm-plain")
          peer   (tu/connect-v5! "gm-bridge" :id (str bridge/client-id-prefix "gm-peer"))]
      (try
        (subscribe! member (str "$share/g/" topic))
        (subscribe! plain topic)
        (tu/send-v5! peer {:packet-type :PUBLISH :topic topic :qos 1 :packet-identifier 9
                           :payload (.getBytes "for all" "UTF-8") :retain? false :duplicate? false
                           :properties {:user-properties [[bridge/share-property (str "g/" topic)]]}})
        (tu/expect-eventually! (:ch member) :PUBLISH 2000)
        (tu/expect-eventually! (:ch plain) :PUBLISH 2000)
        (finally (tu/close! member plain peer))))))

(deftest the-message-key-is-for-the-broker-not-its-subscribers
  (let [props {:content-type    "text/plain"
               :user-properties [["app" "x"]
                                 [bridge/msg-key-property "0001234567890-abcdef12"]
                                 [bridge/share-property "g/t/#"]]}]
    (is (= "0001234567890-abcdef12" (bridge/msg-key props)))
    (is (nil? (bridge/msg-key {:user-properties [["app" "x"]]})))
    (is (= [#{["g" "t/#"]} {:content-type "text/plain" :user-properties [["app" "x"]]}]
           (bridge/take-shares props))
        "taken off with the share instructions, before any subscriber sees it")))
