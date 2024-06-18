(ns mqttkat.flow-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [mqttkat.server :as server]
            [mqttkat.client :as client])
  (:import [org.mqttkat.client MqttClient]
           [org.mqttkat MqttHandler]
           [org.mqttkat.packages MqttConnect MqttPingReq MqttPublish
            MqttDisconnect MqttSubscribe MqttPubRel MqttPubAck MqttPubRec
            MqttPubComp]))

(defn server [f]
  (server/start!)
  (f)
  (server/stop!))

(defn client [f]
  (client/client "localhost" 1883)
  (f)
  (client/close ()))

(use-fixtures :each server)

#_(deftest connect-test
    (let [client (client/client "localhost" 1883)
          buf (MqttConnect/encode {:packet-type :CONNECT, :protocol-name "MQTT", :protocol-version 4, :keep-alive 2, :clean-session? false, :client-id "zn3ghGgk2aEOwk"})]
      (.sendMessage ^MqttClient client buf)
    ;;(client/connect client)
      (Thread/sleep 5000)
      (client/disconnect client)))

(def data (atom ""))
(defn handler-fn [msg _]
  (println "handler-fn msg: " msg)
  (when (= :PUBLISH (:packet-type msg))
    (if (contains? msg :payload)
      (println "Client received payload!!!: " (String. (:payload msg) "UTF-8") "xxxx")
      (println "Client received: " msg   "end messsage"))

    (reset! data (String. (:payload msg) "UTF-8"))))

(deftest retain-test
  (let [payload "this is a retained message"
        _ (reset! data "")
        client (client/client "localhost" 1883 (MqttHandler. ^clojure.lang.IFn handler-fn 2))
        connect-buf (MqttConnect/encode {:packet-type :CONNECT, :protocol-name "MQTT", :protocol-version 4, :keep-alive 100, :clean-session? false, :client-id "zn3ghGgk2aEOwk"})
        publish-buf (MqttPublish/encode {:packet-type :PUBLISH :qos 0 :topic "retain-topic/test1" :retain? true :payload payload :duplicate false})
        subscribe-buf (MqttSubscribe/encode {:packet-type :SUBSCRIBE :topics [{:qos 0 :topic-filter "retain-topic/#"}] :packet-identifier 1})]
    (.sendMessage ^MqttClient client connect-buf)
    (.sendMessage ^MqttClient client publish-buf)
    (Thread/sleep 100)
    (.sendMessage ^MqttClient client subscribe-buf)
    (Thread/sleep 50)
    (is (= payload @data))))