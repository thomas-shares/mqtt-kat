(ns mqttkat.keep-alive-test
  "The MQTT 3.1.1 Keep Alive contract (§3.1.2.10):

     If the Keep Alive value is non-zero and the Server does not receive a
     Control Packet from the Client within one and a half times the Keep Alive
     time period, it MUST disconnect the Network Connection.

   None of this works today. These tests are written against the contract
   rather than against the current behaviour, so most of them fail until the
   mechanism is fixed. Each targets one defect:

     registers-a-keep-alive-timer  handle-success calls add-timer!, which
                                   writes :timer and :last-active under the
                                   client key, and then calls add-client!,
                                   which replaces that whole entry.
     check-timer-*                 the reaper's condition compares nine tenths
                                   of an epoch millisecond count against a
                                   timestamp, so it is true for any clock
                                   reading after 1970 — and it derefs the
                                   :last-active the first defect deleted.
     idle-client-is-disconnected   the end-to-end contract.
     active-client-*               the other half of it. This one passes today
                                   for the wrong reason — nothing is ever
                                   disconnected — and guards the fix: only
                                   update-timestamps marks a client alive, and
                                   it runs when the broker SENDS to a client,
                                   never when it receives from one."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [mqttkat.client :as client]
            [mqttkat.handlers :as h]
            [mqttkat.test-util :as tu]))

;; lein auto test :only mqttkat.keep-alive-test

(use-fixtures :once tu/broker-fixture)

(def ^:private keep-alive-secs 1)
(def ^:private past-the-limit-ms 4000)   ;; deadline, not a sleep: 1.5s + slack

(defn- broker-entry
  "The broker's own record for the client with `id`. The test broker runs in
   this JVM, so the state is directly observable."
  [id]
  (some (fn [[_ v]] (when (= id (:client-id v)) v)) @h/*clients*))

;; ── the mechanism ─────────────────────────────────────────────────────────────

(deftest registers-a-keep-alive-timer
  (testing "a client that asked for a keep alive gets a timer and a liveness stamp"
    (let [id (tu/client-id "ka-timer")
          c  (tu/connect! nil :id id :keep-alive keep-alive-secs)
          _  (Thread/sleep 200)
          entry (broker-entry id)]
      (is (some? entry) "the client should be registered with the broker")
      (is (contains? entry :timer)
          "add-timer! wrote :timer, and then add-client! replaced the entry")
      (is (contains? entry :last-active)
          "add-timer! wrote :last-active, and then add-client! replaced the entry")
      (tu/close! c))))

(deftest check-timer-leaves-an-active-client-alone
  (testing "the reaper does not touch a client that was active a moment ago"
    (let [k       "fake-selection-key"
          time-out (* 1500 60)                       ;; a 60 second keep alive
          entry   {:client-id "ka-active" :clean-session? true
                   :last-active (volatile! (System/currentTimeMillis))}]
      (binding [h/*clients* (atom {k entry})]
        (let [outcome (try (h/check-timer k time-out) :returned (catch Throwable t t))]
          (is (= :returned outcome)
              (str "check-timer threw: " (when (instance? Throwable outcome)
                                           (.getMessage ^Throwable outcome))))
          (is (contains? @h/*clients* k)
              "a client active 0ms ago must not be reaped by a 90s timeout"))))))

(deftest check-timer-reaps-a-silent-client
  (testing "the reaper does drop a client that has gone quiet past the limit"
    (let [k        "fake-selection-key"
          time-out (* 1500 keep-alive-secs)
          entry    {:client-id "ka-silent" :clean-session? true
                    :last-active (volatile! (- (System/currentTimeMillis) 60000))}]
      (binding [h/*clients* (atom {k entry})]
        (let [outcome (try (h/check-timer k time-out) :returned (catch Throwable t t))]
          (is (= :returned outcome)
              (str "check-timer threw: " (when (instance? Throwable outcome)
                                           (.getMessage ^Throwable outcome))))
          (is (not (contains? @h/*clients* k))
              "a client silent for 60s must be reaped by a 1.5s timeout"))))))

(deftest update-timestamps-tolerates-a-vanishing-client
  (testing "marking liveness never throws when the client is already gone"
    ;; The broker handles a packet on one thread while another disconnects the
    ;; client that sent it; the entry, or its :last-active, can be gone by the
    ;; time the stamp is written.
    (binding [h/*clients* (atom {"has-stamp"    {:last-active (volatile! 0)}
                                 "no-stamp"     {:client-id "keep-alive-0"}})]
      (is (nil? (h/update-timestamps ["has-stamp" "no-stamp" "never-existed"]))
          "a missing client or a missing stamp must be skipped, not thrown on")
      (is (pos? @(get-in @h/*clients* ["has-stamp" :last-active]))
          "the client that does have a stamp is still updated"))))

;; ── the contract, end to end ──────────────────────────────────────────────────

(deftest idle-client-is-disconnected
  (testing "a silent client is dropped after 1.5 x keep alive"
    (let [{:keys [client] :as c} (tu/connect! "ka-idle" :keep-alive keep-alive-secs)]
      (is (true? (client/connected? client)) "connected once the CONNACK is in")
      (is (tu/wait-until #(not (client/connected? client)) past-the-limit-ms)
          (str "still connected " past-the-limit-ms "ms into a keep alive of "
               keep-alive-secs "s of silence"))
      (tu/close! c))))

(deftest active-client-is-not-disconnected
  (testing "a client that keeps pinging is left alone"
    (let [{:keys [client ch] :as c} (tu/connect! "ka-active" :keep-alive keep-alive-secs)]
      ;; Ping well inside the 1.5s budget, so a slow machine cannot turn this
      ;; into a false failure — the point is that pinging keeps you alive, not
      ;; how close to the deadline you can cut it.
      (dotimes [_ 8]
        (Thread/sleep 300)
        (client/pingreq client)
        (tu/expect! ch :PINGRESP))
      (is (true? (client/connected? client))
          "a client that pinged within every interval was disconnected anyway")
      (tu/close! c))))
