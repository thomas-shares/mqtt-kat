(ns mqttkat.flow-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [mqttkat.server :as server]
            [clojure.core.async :refer [chan go timeout >! <! <!! alts!!]]
            [mqttkat.client :as client])
  (:import [org.mqttkat.client MqttClient]
           [org.mqttkat MqttHandler]
           [org.mqttkat.packages MqttConnect MqttPingReq MqttPublish
            MqttDisconnect MqttSubscribe MqttPubRel MqttPubAck MqttPubRec
            MqttPubComp]))

;; lein auto test :only mqttkat.flow-test

(def lock (Object.))

(defn logger [msg & args]
  (when true
    (locking lock
      (println msg args))))

(defn server [f]
  (server/start!)
  (f)
  (server/stop!))

(defn client [f]
  (client/client "localhost" 1883)
  (f)
  (client/close ()))

;;(use-fixtures :each server)

(deftest connect-test
    (let [ch (chan 1)
          client (client/client "localhost" 1883 (MqttHandler. ^clojure.lang.IFn (fn [msg _] (go (>! ch msg))) 1))
          connect-msg {:packet-type :CONNECT
                       :protocol-name "MQTT"
                       :protocol-version 4
                       :keep-alive 100
                       :clean-session? true
                       :client-id "connect-test-client"}]
      (client/send-message client connect-msg)
      (is (= :CONNACK (:packet-type (first (alts!! [ch (timeout 1000)])))))))

(deftest retain-test
    (let [ch (chan 1)
          payload "this is a retained message"
          client (client/client "localhost" 1883 (MqttHandler. ^clojure.lang.IFn (fn [msg _] (go (>! ch msg))) 1))
          connect-msg {:packet-type :CONNECT, :protocol-name "MQTT", :protocol-version 4, :keep-alive 100, :clean-session? true, :client-id "zn3ghGgk2aEOwk"}
          publish-msg {:packet-type :PUBLISH :qos 0 :topic "retain-topic/test1" :retain? true :payload payload :duplicate false}
          subscribe-msg {:packet-type :SUBSCRIBE :topics [{:qos 0 :topic-filter "retain-topic/#"}] :packet-identifier 1}]
      (client/send-message client connect-msg)
      (is (= :CONNACK (:packet-type (first (alts!! [ch (timeout 1000)])))))
      (client/send-message client publish-msg)
      (<!! (timeout 50))
      (client/send-message client subscribe-msg)
      (loop [msg (first (alts!! [ch (timeout 1000)]))]
        (logger msg)
        (let [type (:packet-type msg)]
          (if (= type :PUBLISH)
            (do #_(println (String. (:payload msg) "UTF-8") "xxx")
                (is (= :PUBLISH type))
                (is (true? (:retain? msg)))
                (is (zero? (:qos msg)))
                (is (= "retain-topic/test1" (:topic msg)))
                (is (= payload (String. (:payload  msg) "UTF-8"))))
            (do (is (= :SUBACK type))
                (recur (first (alts!! [ch (timeout 1000)])))))))))

(deftest last-will-test
    (let [will-topic "will-topic"
          will-message "will message"
          ch-a (chan)
          ch-b (chan)
          client-a (client/client "localhost" 1883 (MqttHandler. ^clojure.lang.IFn (fn [msg _] (go (>! ch-a msg))) 1))
          client-b (client/client "localhost" 1883 (MqttHandler. ^clojure.lang.IFn (fn [msg _] (logger msg) (go (>! ch-b msg))) 1))
          connect-a-msg {:packet-type :CONNECT
                         :protocol-name "MQTT"
                         :protocol-version 4
                         :keep-alive 100
                         :clean-session? true
                         :client-id "will-client"
                         :will {:will-retain false
                                :will-topic will-topic
                                :will-message will-message
                                :will-qos 0}}
          connect-b-msg {:packet-type :CONNECT
                         :protocol-name "MQTT"
                         :protocol-version 4
                         :keep-alive 100
                         :clean-session? true
                         :client-id "sub-client"}
          subscribe-msg  {:packet-type :SUBSCRIBE
                          :topics [{:qos 0
                                    :topic-filter "will-topic"}]
                          :packet-identifier 1}]
      (client/send-message client-a connect-a-msg)
      (is (= :CONNACK (:packet-type (first (alts!! [ch-a (timeout 1000)])))))
      (client/send-message client-b connect-b-msg)
      (is (= :CONNACK (:packet-type (first (alts!! [ch-b (timeout 1000)])))))
      (client/send-message  client-b subscribe-msg)
      (is (= :SUBACK (:packet-type (first (alts!! [ch-b (timeout 1000)])))))
      (.close ^MqttClient client-a)
      (let [msg (first (alts!! [ch-b (timeout 1000)]))]
        (logger msg)
        (is (= :PUBLISH (:packet-type msg)))
        (is (= will-topic (:topic msg)))
        (is (= will-message (String. (:payload msg) "UTF-8")))
        (is (= 0 (:qos msg))))
      (.close ^MqttClient client-b)))

(deftest last-will-test-and-retain
    (let [will-topic "will-topic"
          will-message "will message"
          ch-a (chan)
          ch-b (chan)
          client-a (client/client "localhost" 1883 (MqttHandler. ^clojure.lang.IFn (fn [msg _] (go (>! ch-a msg))) 1))
          client-b (client/client "localhost" 1883 (MqttHandler. ^clojure.lang.IFn (fn [msg _] (logger msg) (go (>! ch-b msg))) 1))
          connect-a-msg {:packet-type :CONNECT
                         :protocol-name "MQTT"
                         :protocol-version 4
                         :keep-alive 100
                         :clean-session? true
                         :client-id "will-client"
                         :will {:will-retain true
                                :will-topic will-topic
                                :will-message will-message
                                :will-qos 0}}
          connect-b-msg {:packet-type :CONNECT
                         :protocol-name "MQTT"
                         :protocol-version 4
                         :keep-alive 100
                         :clean-session? true
                         :client-id "sub-client"}
          subscribe-msg {:packet-type :SUBSCRIBE
                         :topics [{:qos 0
                                   :topic-filter will-topic}]
                         :packet-identifier 1}]
      (client/send-message  client-a connect-a-msg)
      (is (= :CONNACK (:packet-type (first (alts!! [ch-a (timeout 1000)])))))
      (<!! (timeout 50))
      (.close ^MqttClient client-a)
      (client/send-message client-b connect-b-msg)
      (is (= :CONNACK (:packet-type (first (alts!! [ch-b (timeout 1000)])))))
      (client/send-message client-b subscribe-msg)
      (is (= :SUBACK (:packet-type (first (alts!! [ch-b (timeout 1000)])))))
      (let [msg (first (alts!! [ch-b (timeout 1000)]))]
        ;;(logger "Message: " msg)
        (is (= :PUBLISH (:packet-type msg)))
        (is (= will-topic (:topic msg)))
        (is (:retain? msg))
        (is (= will-message (String. (:payload msg) "UTF-8")))
        (is (= 0 (:qos msg))))
      (.close ^MqttClient client-b)))

(deftest zero-length-client-id-clean-session-false
    (let [ch (chan)
          client (client/client "localhost" 1883 (MqttHandler. ^clojure.lang.IFn (fn [msg _] (logger msg) (go (>! ch msg))) 1))
          connect-msg {:packet-type :CONNECT
                       :protocol-name "MQTT"
                       :protocol-version 4
                       :keep-alive 100
                       :clean-session? false
                       :client-id ""}]
      (client/send-message  client connect-msg)
      (let [msg (first (alts!! [ch (timeout 1000)]))]
        (is (= :CONNACK (:packet-type msg)))
        (is (= 0x02 (:connect-return-code msg))))))

(deftest zero-length-client-id-clean-session-true
    (let [ch (chan)
          client (client/client "localhost" 1883 (MqttHandler. ^clojure.lang.IFn (fn [msg _] (logger msg) (go (>! ch msg))) 1))
          connect-msg  {:packet-type :CONNECT
                        :protocol-name "MQTT"
                        :protocol-version 4
                        :keep-alive 100
                        :clean-session? true
                        :client-id ""}]
      (client/send-message client connect-msg)
      (let [msg (first (alts!! [ch (timeout 1000)]))]
        (is (= :CONNACK (:packet-type msg)))
        (is (= 0x00 (:connect-return-code msg))))))


(deftest session-test
 (let [ch (chan)
       client-a (client/client "localhost" 1883 (MqttHandler. ^clojure.lang.IFn (fn [msg _] (logger msg) (go (>! ch msg))) 1))
       client-b (client/client "localhost" 1883 (MqttHandler. ^clojure.lang.IFn (fn [msg _] (logger msg) (go (>! ch msg))) 1))
       connect-msg {:packet-type :CONNECT
                    :protocol-name "MQTT"
                    :protocol-version 4
                    :keep-alive 100
                    :clean-session? false
                    :client-id "connect-test-client"}]
   (client/send-message client-a connect-msg)
   (let [msg (first (alts!! [ch (timeout 1000)]))]
     (is (= :CONNACK (:packet-type msg)))
     (is (= 0x00 (:connect-return-code msg)))
     (.close ^MqttClient client-a))
   (client/send-message client-b connect-msg)
   (let [msg (first (alts!! [ch (timeout 1000)]))]
     (is (= :CONNACK (:packet-type msg)))
     (is (= 0x00 (:connect-return-code msg)))
     (is (true? (:session-present? msg)))
     (.close ^MqttClient client-a))))

(deftest sub-unsub-test
  (let [ch (chan)
        client (client/client "localhost" 1883 (MqttHandler. ^clojure.lang.IFn (fn [msg _] (logger msg) (go (>! ch msg))) 1))
        connect-msg {:packet-type :CONNECT
                     :protocol-name "MQTT"
                     :protocol-version 4
                     :keep-alive 100
                     :clean-session? true
                     :client-id "sub-unsub-test-client"}
        subscribe-msg {:packet-type :SUBSCRIBE
                       :topics [{:qos 0
                                 :topic-filter "sub-topic/test"}]
                       :packet-identifier 123}
        unsubscribe-msg {:packet-type :UNSUBSCRIBE
                         :topics ["sub-topic/test"]
                         :packet-identifier 124}
        publish-msg {:packet-type :PUBLISH
                     :qos 0
                     :topic "sub-topic/test"
                     :retain? false
                     :payload "this is a message"
                     :duplicate false}]
    (client/send-message client connect-msg)
    (let [msg (first (alts!! [ch (timeout 1000)]))]
      (is (= :CONNACK (:packet-type msg)))
      (is (= 0x00 (:connect-return-code msg))))
    (client/send-message client subscribe-msg)
    (let [msg (first (alts!! [ch (timeout 1000)]))]
      (is (= :SUBACK (:packet-type msg)))
      (is (= [0] (:response msg))))
    (client/send-message client publish-msg)
    (let [msg (first (alts!! [ch (timeout 1000)]))]
      (is (= :PUBLISH (:packet-type msg)))
      (is (= 0 (:qos msg)))
      (is (= "sub-topic/test" (:topic msg)))
      (is (= "this is a message" (String. (:payload msg) "UTF-8"))))
    (client/send-message client unsubscribe-msg)
    (let [msg (first (alts!! [ch (timeout 1000)]))]
      (is (= :UNSUBACK (:packet-type msg)))
      (is (= 124 (:packet-identifier msg))))
    (client/send-message client publish-msg)
    (.close ^MqttClient client)))


(deftest subscribe-test
  (let [ch (chan)
        client-a (client/client "localhost" 1883 (MqttHandler. ^clojure.lang.IFn (fn [msg _] (logger msg) (go (>! ch msg))) 1))
        client-b (client/client "localhost" 1883 (MqttHandler. ^clojure.lang.IFn (fn [msg _] (logger msg) (go (>! ch msg))) 1))
        payload "this is a message"
        connect-msg {:packet-type :CONNECT
                     :protocol-name "MQTT"
                     :protocol-version 4
                     :keep-alive 100
                     :clean-session? false
                     :client-id "connect-test-client"}
        subscribe-msg {:packet-type :SUBSCRIBE
                       :topics [{:qos 1
                                 :topic-filter "qos-0-topic/test"}]
                       :packet-identifier 123}
        publish-msg {:packet-type :PUBLISH :qos 0 :topic "qos-0-topic/test" :retain? false :payload payload :duplicate false}]
    (client/send-message client-a connect-msg)
    (let [msg (first (alts!! [ch (timeout 1000)]))]
      (is (= :CONNACK (:packet-type msg)))
      (is (= 0x00 (:connect-return-code msg))))
    (client/send-message client-a subscribe-msg)
    (let [msg (first (alts!! [ch (timeout 1000)]))]
      (is (= :SUBACK (:packet-type msg)))
      (is (= [1] (:response msg))))
    (client/send-message client-a publish-msg)
    (let [msg (first (alts!! [ch (timeout 1000)]))]
      (is (= :PUBLISH (:packet-type msg)))
      (is (= 0 (:qos msg)))
      (is (= "qos-0-topic/test" (:topic msg)))
      (is (= payload (String. (:payload msg) "UTF-8"))))
    (.close ^MqttClient client-a)
    (client/send-message client-b connect-msg)
    (let [msg (first (alts!! [ch (timeout 1000)]))]
        (is (= :CONNACK (:packet-type msg)))
        (is (= 0x00 (:connect-return-code msg)))
        (is (true? (:session-present? msg))))
    (client/send-message client-b publish-msg)
    (let [msg (first (alts!! [ch (timeout 1000)]))]
      (is (= :PUBLISH (:packet-type msg)))
      (is (= 0 (:qos msg)))
      (is (= "qos-0-topic/test" (:topic msg)))
      (is (= payload (String. (:payload msg) "UTF-8"))))
    (.close ^MqttClient client-b)))


(deftest qos-1-test
  (let [payload "qos-1 test message"
        ch (chan)
        client-a (client/client "localhost" 1883 (MqttHandler. ^clojure.lang.IFn (fn [msg _] (logger (str "Received: " msg)) (go (>! ch msg))) 1))
        client-b (client/client "localhost" 1883 (MqttHandler. ^clojure.lang.IFn (fn [msg _] (logger (str "Received: " msg)) (go (>! ch msg))) 1))
        connect-msg {:packet-type :CONNECT
                     :protocol-name "MQTT"
                     :protocol-version 4
                     :keep-alive 100
                     :clean-session? false
                     :client-id "qos-1-test"}
        publish-msg {:packet-type :PUBLISH :packet-identifier 666 :qos 1 :topic "qos-1-topic/test1" :retain? false :payload payload :duplicate false}
        subscribe-msg {:packet-type :SUBSCRIBE
                       :topics [{:qos 1
                                 :topic-filter "qos-1-topic/test1"}]
                       :packet-identifier 123}]
    (client/send-message client-a connect-msg)
    (is (= :CONNACK (:packet-type (first (alts!! [ch (timeout 1000)])))))
    (client/send-message client-a subscribe-msg)
    (is (= :SUBACK (:packet-type (first (alts!! [ch (timeout 1000)])))))
    (client/send-message client-a publish-msg)
    #_(let [msg-1 (first (alts!! [ch (timeout 1000)]))
            msg-2 (first (alts!! [ch (timeout 1000)]))
            messages [msg-1 msg-2]]
        (logger "asdg" messages))

    (loop [msg (first (alts!! [ch (timeout 1000)]))
           count 2]
      ;;(logger "Count: " count)
      (when (> count 0)
        (let [type (:packet-type msg)]
          (if (= type :PUBLISH)
            (do (is (= :PUBLISH type))
                (is (= 1 (:qos msg)))
                ;;(is (= 1 (:packet-identifier msg)))
                (is (= "qos-1-topic/test1" (:topic msg)))
                (is (= payload (String. (:payload  msg) "UTF-8")))
                (recur (msg (first (alts!! [ch (timeout 1000)]))) (dec count)))
            (do (is (= :PUBACK type))
                (is (= 666 (:packet-identifier msg)))
                (recur (first (alts!! [ch (timeout 1000)])) (dec count)))))))

    (<!! (timeout 50))
    (client/send-message client-a {:packet-type :DISCONNECT})
    (client/close client-a)
    (logger "disconnect client-a")

    ;; The client (WE) didn't send a PUBACK back to the server... so when we reconnect with the same client id 
    ;; we should get the same message again. and this time we PUBACK it.

    (<!! (timeout 50))
    (client/send-message client-b connect-msg)
    (logger "Connected client-b")
    (loop [msg (first (alts!! [ch (timeout 1000)]))
           count 3]
      (logger msg)
      (logger "Count: " count)
      (when (> count 0)
        (logger "Count: " count)
        (logger msg)
        (let [type (:packet-type msg)]
          (if (= type :PUBLISH)
            (do (is (= :PUBLISH type))
                (is (= 1 (:qos msg)))
                (is (true? (:duplicate? msg)))
                (is (= "qos-1-topic/test1" (:topic msg)))
                (is (= payload (String. (:payload  msg) "UTF-8")))
                (client/send-message client-b {:packet-type :PUBACK :packet-identifier (:packet-identifier msg)}))
            (do (is (= :CONNACK type))
                (is (true? (:session-present? msg)))
                (recur (first (alts!! [ch (timeout 1000)])) (dec count)))))))

    (logger "done...")))

