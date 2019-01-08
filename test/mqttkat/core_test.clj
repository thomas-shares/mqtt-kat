(ns mqttkat.core-test
  (:require [clojure.test :refer :all]
            [mqttkat.server :as server]
            [mqttkat.handlers :refer [add-subscriber]]
            [mqttkat.client :as client]
            [mqttkat.spec :refer :all]
            [clojure.spec.gen.alpha :as gen]
            [clojure.spec.alpha :as s]
            [clojure.core.async :as async])
  (:import [org.mqttkat.server MqttServer]
           [org.mqttkat.client MqttClient]
           [org.mqttkat MqttHandler MqttUtil]
           [org.mqttkat.packages MqttConnect MqttPingReq MqttPublish
             MqttDisconnect MqttPingResp MqttSubscribe MqttSubAck
             MqttUnsubscribe MqttUnSubAck]))


(deftest subscription
  (is (= {"test" [:key]} (add-subscriber {} "test" :key))))


(deftest encode-bytes)
  ;    (is (= 0 (MqttUtil/calculateLenght 100))))

(def channel (async/chan 1))

(defn handler-fn [msg]
  (clojure.pprint/pprint (dissoc msg :client-key))
  (async/>!! channel msg))

(def handler (MqttHandler. ^clojure.lang.IFn handler-fn 2))

(defn mqtt-fixture [f]
  (server/start "0.0.0.0" 1883 handler)
  (f)
  (try
    (server/stop)
    (catch Exception e)))

(use-fixtures :once mqtt-fixture)

;(deftest connect-packet
;  (let [;_ (server/run-server "0.0.0.0" 1883 handler)
;        client (client/client  "localhost" 1883)
;        map (gen/generate (s/gen :mqtt/connect))
;        _ (clojure.pprint/pprint map)
;        bufs (MqttConnect/encode map)
;        _ (.sendMessage ^MqttClient client bufs)
;        received-map (async/<!! channel)
;        _ (.close client)
;    (is (= map (dissoc received-map :client-key))))

;(deftest publish-packet
;  (let [client (client/client "localhost" 1883)
;        map (gen/generate (s/gen :mqtt/publish))
;        _ (clojure.pprint/pprint map)
;        bufs (MqttPublish/encode map)
;        _ (.sendMessage ^MqttClient client bufs)
;        received-map (async/<!! channel)
;        _ (.close client)
;    (is (= map (dissoc received-map :client-key))))

;(deftest pingreq-packet
;  (let [client (client/client "localhost" 1883)
;        map (gen/generate (s/gen :mqtt/pingreq))
;        _ (clojure.pprint/pprint map)
;        bufs (MqttPingReq/encode map)
;        _ (.sendMessage ^MqttClient client bufs)
;        received-map (async/<!! channel)
;        _ (.close client)
;    (is (= map (dissoc received-map :client-key))))

;(deftest pingresp-packet
;  (let [client (client/client "localhost" 1883)
;        map (gen/generate (s/gen :mqtt/pingresp))
;        _ (clojure.pprint/pprint map)
;        bufs (MqttPingResp/encode map)
;        _ (.sendMessage ^MqttClient client bufs)
;        received-map (async/<!! channel)
;        _ (.close client)
;    (is (= map (dissoc received-map :client-key))))


;(deftest subscribe-packet
;  (let [client (client/client "localhost" 1883)
;        map (gen/generate (s/gen :mqtt/subscribe))
;        _ (clojure.pprint/pprint map)
;        bufs (MqttSubscribe/encode map)
;        _ (.sendMessage ^MqttClient client bufs)
;        received-map (async/<!! channel)
;        _ (.close ^MqttClient client)
;    (is (= map (dissoc received-map :client-key))))

;(deftest suback-packet
;  (let [client (client/client "localhost" 1883)
;        map (gen/generate (s/gen :mqtt/suback))
;        _ (clojure.pprint/pprint map)
;        bufs (MqttSubAck/encode map)
;        _ (.sendMessage ^MqttClient client bufs)
;        received-map (async/<!! channel)
;        _ (.close ^MqttClient client)
;    (is (= map (dissoc received-map :client-key))))

;(deftest unsubscribe-packet)
;  (let [client (client/client "localhost" 1883)]))
;        map (gen/generate (s/gen :mqtt/unsubscribe))]))
;        _ (clojure.pprint/pprint map)]))
;        bufs (MqttUnsubscribe/encode map)]))
;        _ (.sendMessage ^MqttClient client bufs)]))
;        received-map (async/<!! channel)]))
;        _ (.close ^MqttClient client)]))
;    (is (= map (dissoc received-map :client-key)))))

(deftest unsuback-packet
  (let [client (client/client "localhost" 1883)
        map (gen/generate (s/gen :mqtt/unsuback ))
        _ (clojure.pprint/pprint map)
        bufs (MqttUnSubAck/encode map)
        _ (.sendMessage ^MqttClient client bufs)
        received-map (async/<!! channel)
        _ (.close ^MqttClient client)]
    (is (= map (dissoc received-map :client-key)))))
