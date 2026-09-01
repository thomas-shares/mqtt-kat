(ns mqttkat.smoke-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [mqttkat.client :as client]
            [mqttkat.test-util :as tu]))

(use-fixtures :once tu/broker-fixture)

(deftest broker-starts-and-serves
  (let [{:keys [client ch] :as c} (tu/connect! "smoke")]
    (is (= 0 (:connect-return-code (:connack c))))
    (client/send-message client {:packet-type :SUBSCRIBE
                                 :topics [{:qos 0 :topic-filter "smoke/#"}]
                                 :packet-identifier 1})
    (is (= [0] (:response (tu/expect! ch :SUBACK))))
    (client/send-message client {:packet-type :PUBLISH :qos 0 :topic "smoke/one"
                                 :retain? false :duplicate false :payload "hello"})
    (is (= "hello" (tu/payload-str (tu/expect-eventually! ch :PUBLISH))))
    (tu/close! c)))
