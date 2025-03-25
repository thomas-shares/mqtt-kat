(ns mqttkat.ping-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [mqttkat.server :as server]
            [clojure.core.async :refer [chan go timeout >! <! <!! alts!!]]
            [mqttkat.client :as client]) 
  (:import [org.mqttkat.client MqttClient]
           [org.mqttkat MqttHandler]
           [org.mqttkat.packages MqttConnect MqttPingReq MqttPublish
            MqttDisconnect MqttSubscribe MqttPubRel MqttPubAck MqttPubRec
            MqttPubComp]))

;; lein auto test :only mqttkat.ping-test


(deftest add-timer
  "A new client is connects and is disconnected after a time out" 
  (let [ch (chan 1)
        client (client/client "localhost" 1883 (MqttHandler. ^clojure.lang.IFn (fn [msg _] (go (>! ch msg))) 1))
        connect-msg {:packet-type :CONNECT
                     :protocol-name "MQTT"
                     :protocol-version 4
                     :keep-alive 1
                     :clean-session? true
                     :client-id "connect-test-client"}]
     (client/send-message client connect-msg)
     (is (= :CONNACK (:packet-type (first (alts!! [ch (timeout 1000)])))))
     (Thread/sleep 50)
     (is (= true (client/connected? client)))
     (Thread/sleep 2000)
     (is (= true (client/connected? client)))))
     