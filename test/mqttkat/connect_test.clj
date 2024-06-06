(ns mqttkat.connect-test
  (:require [clojure.test :refer [deftest is]]
            [mqttkat.handlers.connect :as connect :refer [handle-incorrect-clean-session
                                                          handle-not-valid-protocol-version
                                                          handle-success]]
            [mqttkat.handlers.disconnect :refer [disconnect-client]]))
;; lein auto test :only mqttkat.connect-test

(deftest connect-no-error
  (let [success (atom false)]
    (with-redefs [handle-success (fn [_] (reset! success true))]
      (connect/connect 
          {:protocol-name "MQTT" :protocol-version 4 :client-key "" :client-id "test-1" :clean-session? true}))
    (is @success)))


(deftest connect-wrong-protocol-name
  (let [fail (atom false)]
    (with-redefs [disconnect-client (fn [_] (reset! fail true))]
      (connect/connect
         {:protocol-name "wrong" :protocol-version 4 :client-key "" :client-id "test-1" :clean-session? true}))
    (is @fail)))

(deftest connect-wrong-protocol-version
  (let [fail (atom false)]
    (with-redefs [handle-not-valid-protocol-version (fn [_] (reset! fail true))]
      (connect/connect
         {:protocol-name "MQTT" :protocol-version 1 :client-key "" :client-id "test-1" :clean-session? true}))
    (is @fail)))

(deftest no-client-id-and-no-clean-session
  (let [fail (atom false)]
    (with-redefs [handle-incorrect-clean-session (fn [_] (reset! fail true))]
      (connect/connect {:protocol-name "MQTT" :protocol-version 4 :client-key "" :client-id "" :clean-session? false}))
    (is @fail)))

(deftest client-id-and-clean-session 
  (let [fail (atom false)]
    (with-redefs [handle-incorrect-clean-session (fn [_] (reset! fail true))]
      (connect/connect {:protocol-name "MQTT" :protocol-version 4 :client-key "" :client-id "" :clean-session? false}))
    (is @fail)))
