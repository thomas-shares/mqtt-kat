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
        connect-buf (MqttConnect/encode {:packet-type :CONNECT
                                         :protocol-name "MQTT"
                                         :protocol-version 4
                                         :keep-alive 100
                                         :clean-session? true
                                         :client-id "connect-test-client"})]
    (.sendMessage ^MqttClient client connect-buf)
    (is (= :CONNACK (:packet-type (first (alts!! [ch (timeout 1000)])))))))

(deftest retain-test
  (let [ch (chan 1)
        payload "this is a retained message"
        client (client/client "localhost" 1883 (MqttHandler. ^clojure.lang.IFn (fn [msg _] (go (>! ch msg))) 1))
        connect-buf (MqttConnect/encode {:packet-type :CONNECT, :protocol-name "MQTT", :protocol-version 4, :keep-alive 100, :clean-session? true, :client-id "zn3ghGgk2aEOwk"})
        publish-buf (MqttPublish/encode {:packet-type :PUBLISH :qos 0 :topic "retain-topic/test1" :retain? true :payload payload :duplicate false})
        subscribe-buf (MqttSubscribe/encode {:packet-type :SUBSCRIBE :topics [{:qos 0 :topic-filter "retain-topic/#"}] :packet-identifier 1})]
    (.sendMessage ^MqttClient client connect-buf)
    (is (= :CONNACK (:packet-type (first (alts!! [ch (timeout 1000)])))))
    (.sendMessage ^MqttClient client publish-buf)
    (<!! (timeout 100))
    (.sendMessage ^MqttClient client subscribe-buf)
    (loop [msg (first (alts!! [ch (timeout 1000)]))]
      (logger msg)
      (let [type (:packet-type msg)]
        (if (= type :PUBLISH)
          (do (is (= :PUBLISH type))
              (is (true? (:retain? msg)))
              (is (zero? (:qos msg)))
              (is (= "retain-topic/test1" (:topic msg)))
              (is (= payload (String. (:payload  msg) "UTF-8"))))
          (do (is (= :SUBACK type))
              (recur (first (alts!! [ch (timeout 2000)])))))))))

(deftest last-will-test
  (let [will-topic "will-topic"
        will-message "will message"
        ch-a (chan)
        ch-b (chan)
        client-a (client/client "localhost" 1883 (MqttHandler. ^clojure.lang.IFn (fn [msg _] (go (>! ch-a msg))) 1))
        client-b (client/client "localhost" 1883 (MqttHandler. ^clojure.lang.IFn (fn [msg _] (logger msg) (go (>! ch-b msg))) 1))
        connect-a-buf (MqttConnect/encode {:packet-type :CONNECT
                                           :protocol-name "MQTT"
                                           :protocol-version 4
                                           :keep-alive 100
                                           :clean-session? true
                                           :client-id "will-client"
                                           :will {:will-retain false
                                                  :will-topic will-topic
                                                  :will-message will-message
                                                  :will-qos 0}})
        connect-b-buf (MqttConnect/encode {:packet-type :CONNECT
                                           :protocol-name "MQTT"
                                           :protocol-version 4
                                           :keep-alive 100
                                           :clean-session? true
                                           :client-id "sub-client"})
        subscribe-buf (MqttSubscribe/encode {:packet-type :SUBSCRIBE
                                             :topics [{:qos 0
                                                       :topic-filter "will-topic"}]
                                             :packet-identifier 1})]
    (.sendMessage ^MqttClient client-a connect-a-buf)
    (is (= :CONNACK (:packet-type (first (alts!! [ch-a (timeout 1000)])))))
    (.sendMessage ^MqttClient client-b connect-b-buf)
    (is (= :CONNACK (:packet-type (first (alts!! [ch-b (timeout 1000)])))))
    (.sendMessage ^MqttClient client-b subscribe-buf)
    (is (= :SUBACK (:packet-type (first (alts!! [ch-b (timeout 1000)])))))
    (.close ^MqttClient client-a)
    (let [msg (first (alts!! [ch-b (timeout 1000)]))]
      (is (= :PUBLISH (:packet-type msg)))
      (is (= will-topic (:topic msg)))
      (is (= will-message (String. (:payload msg) "UTF-8")))
      (is (= 0 (:qos msg))))
    (.close client-b)))

