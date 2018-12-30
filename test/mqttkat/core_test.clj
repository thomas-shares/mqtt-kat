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
           [org.mqttkat MqttHandler]
           [org.mqttkat.packages MqttConnect MqttPingReq MqttPublish MqttDisconnect]))


(deftest subscription
  (is (= {"test" [:key]} (add-subscriber {} "test" :key))))

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
     
(deftest connect-packet
  (let [;_ (server/run-server "0.0.0.0" 1883 handler)
        client (client/client  "localhost" 1883)
        map (gen/generate (s/gen :mqtt/connect))
        _ (clojure.pprint/pprint map)
        bufs (MqttConnect/encode map)
        _ (.sendMessage ^MqttClient client bufs)
        received-map (async/<!! channel)]
    (is (= map (dissoc received-map :client-key)))))
