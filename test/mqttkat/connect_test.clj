(ns mqttkat.connect-test
  "Unit tests for the CONNECT branch table. No broker and no sockets: each test
   drives mqttkat.handlers.connect/connect directly and asserts which branch it
   took, with the effectful leaf redefined away."
  (:require [clojure.test :refer [deftest is testing]]
            [mqttkat.handlers :as handlers]
            [mqttkat.handlers.connect :as connect :refer [handle-incorrect-clean-session
                                                          handle-not-valid-protocol-version
                                                          handle-success]]
            [mqttkat.handlers.disconnect :refer [disconnect-client]]))
;; lein auto test :only mqttkat.connect-test

(defn- connect-msg [& {:as overrides}]
  (merge {:protocol-name    "MQTT"
          :protocol-version 4
          :client-key       ""
          :client-id        "test-1"
          :clean-session?   true
          ;; mandatory on the wire, and handle-success NPEs without it
          :keep-alive       60}
         overrides))

(defn- branch-taken
  "Run connect with `leaf` redefined, and report whether connect reached it."
  [leaf msg]
  (let [reached (atom false)]
    (with-redefs-fn {leaf (fn [_] (reset! reached true))}
      #(connect/connect msg))
    @reached))

(deftest connect-no-error
  (is (branch-taken #'handle-success (connect-msg))))

(deftest connect-wrong-protocol-name
  (is (branch-taken #'disconnect-client (connect-msg :protocol-name "wrong"))))

(deftest connect-wrong-protocol-version
  (is (branch-taken #'handle-not-valid-protocol-version (connect-msg :protocol-version 1))))

(deftest no-client-id-and-no-clean-session
  (testing "a zero-length client id needs a clean session; without one it is rejected"
    (is (branch-taken #'handle-incorrect-clean-session
                      (connect-msg :client-id "" :clean-session? false)))))

(deftest client-id-and-no-clean-session
  (testing "a real client id may resume a session — that is not the rejected case"
    (is (branch-taken #'handle-success
                      (connect-msg :client-id "test-1" :clean-session? false)))))

(deftest second-connect-on-one-connection
  (testing "a connection that has already sent CONNECT is disconnected"
    (binding [handlers/*clients* (atom {"key-1" {:client-id "test-1"}})]
      (is (branch-taken #'disconnect-client (connect-msg :client-key "key-1"))))))
