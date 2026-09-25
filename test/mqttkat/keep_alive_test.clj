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
            [mqttkat.test-util :as tu]
            [overtone.at-at :as at])
  (:import [java.nio.channels Selector SocketChannel]))

;; lein auto test :only mqttkat.keep-alive-test

(use-fixtures :once tu/broker-fixture)

(def ^:private keep-alive-secs 1)
(def ^:private past-the-limit-ms 4000)   ;; deadline, not a sleep: 1.5s + slack

(defn- with-selection-key
  "Call `f` with a real, unregistered-but-valid SelectionKey.

   check-timer ends by closing the client's connection, and the interop call
   that does it casts the key to a SelectionKey — so a String standing in for
   one throws before the call is even made. That throw is caught and logged,
   which left every run of these tests printing a ClassCastException stack
   trace that looks exactly like a failure and is not one.

   A key from a real channel costs two objects and removes the whole problem:
   with no attachment, closeConnection finds no Connection, closes the channel
   and returns. The close path is then genuinely exercised rather than being
   thrown out of."
  [f]
  (with-open [selector (Selector/open)
              channel  (SocketChannel/open)]
    (.configureBlocking channel false)
    (f (.register channel selector 0))))

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
    (with-selection-key
      (fn [k]
        (let [time-out (* 1500 60)                   ;; a 60 second keep alive
              entry   {:client-id "ka-active" :clean-session? true
                       :last-active (volatile! (System/currentTimeMillis))}]
          (binding [h/*clients* (atom {k entry})]
            (let [outcome (try (h/check-timer k time-out) :returned (catch Throwable t t))]
              (is (= :returned outcome)
                  (str "check-timer threw: " (when (instance? Throwable outcome)
                                               (.getMessage ^Throwable outcome))))
              (is (contains? @h/*clients* k)
                  "a client active 0ms ago must not be reaped by a 90s timeout"))))))))

(deftest check-timer-reaps-a-silent-client
  (testing "the reaper does drop a client that has gone quiet past the limit"
    ;; A real SelectionKey, so this reaches the close rather than throwing on
    ;; the way to it. check-timer removes the client *before* closing, so the
    ;; reaping is what the assertion below checks either way — but going
    ;; through the real path is the point of having the test.
    (with-selection-key
      (fn [k]
        (let [time-out (* 1500 keep-alive-secs)
              entry    {:client-id "ka-silent" :clean-session? true
                        :last-active (volatile! (- (System/currentTimeMillis) 60000))}]
          (binding [h/*clients* (atom {k entry})]
            (let [outcome (try (h/check-timer k time-out) :returned (catch Throwable t t))]
              (is (= :returned outcome)
                  (str "check-timer threw: " (when (instance? Throwable outcome)
                                               (.getMessage ^Throwable outcome))))
              (is (not (contains? @h/*clients* k))
                  "a client silent for 60s must be reaped by a 1.5s timeout"))))))))

(def ^:private a-will
  {:will-topic "ka/will" :will-message "gone" :will-qos 0 :will-retain false})

(deftest a-will-is-sent-once-and-then-cleared
  (testing "handle-will-if-present takes the will off the client as it sends it"
    ;; The reaper, the socket closing and a takeover can all reach this for the
    ;; same connection; only the first may publish.
    (let [sent (atom [])]
      (with-redefs [h/publish-will #(swap! sent conj %)]
        (binding [h/*clients* (atom {"k" {:client-id "ka-will-once" :will a-will}})]
          (h/handle-will-if-present "k")
          (h/handle-will-if-present "k")
          (is (= 1 (count @sent)) "the will must go out exactly once")
          (is (= "gone" (:payload (first @sent))))
          (is (not (contains? (get @h/*clients* "k") :will))
              "the will must be removed from the client once sent"))))))

(deftest check-timer-clears-the-will-and-the-timer-of-a-parked-session
  (testing "a persistent session reaped by keep alive is parked without its will"
    (with-selection-key
      (fn [k]
        (let [sent     (atom [])
              time-out (* 1500 keep-alive-secs)
              id       "ka-will-parked"
              entry    {:client-id id :clean-session? false :will a-will
                        :last-active (volatile! (- (System/currentTimeMillis) 60000))}]
          (with-redefs [h/publish-will #(swap! sent conj %)]
            (binding [h/*clients* (atom {k entry})]
              (h/add-timer! k keep-alive-secs)
              ;; add-timer! stamps the client as active now; put it back in the past.
              (swap! h/*clients* assoc-in [k :last-active]
                     (volatile! (- (System/currentTimeMillis) 60000)))
              (h/check-timer k time-out)
              (is (= 1 (count @sent)) "the will is published when the client is reaped")
              (is (not (contains? @h/*clients* k)) "the connection is forgotten")
              (let [parked (get @h/*clients* id)]
                (is (some? parked) "the persistent session is parked under its client id")
                (is (not (contains? parked :will))
                    "a will that has fired must not be carried into the parked session")
                (is (nil? (:timer parked)) "the parked session holds no keep-alive timer"))
              (h/discard-session! id))))))))

;; Killed through at/kill, so recording what it is handed shows which timers
;; were stopped without reaching into at-at's job records.
(defn- recording-kills [killed]
  (let [kill at/kill]
    (fn [job] (swap! killed conj job) (kill job))))

(deftest a-timer-whose-client-is-gone-stops-itself
  (testing "a keep-alive timer left behind by its client cancels itself on its next tick"
    ;; The root atom, not a binding: the tick runs on the timer pool, where a
    ;; binding made on this thread is not seen.
    (let [k      (Object.)
          killed (atom [])]
      (with-redefs [at/kill (recording-kills killed)]
        (try
          (swap! h/*clients* assoc k {:client-id "ka-orphan"})
          (h/add-timer! k keep-alive-secs)
          (let [timer (get-in @h/*clients* [k :timer])]
            (is (some? timer) "add-timer! files the timer under the key")
            ;; Gone without remove-timer!, which is what used to leave the
            ;; timer firing for the life of the broker.
            (swap! h/*clients* dissoc k)
            (is (tu/wait-until #(some (fn [j] (identical? j timer)) @killed)
                               past-the-limit-ms)
                "the timer must cancel itself once its client is gone")
            (is (not (contains? @h/*clients* k))
                "stopping the timer must not bring the client's entry back"))
          (finally
            (h/remove-timer! k)
            (swap! h/*clients* dissoc k)))))))

(deftest a-second-timer-replaces-the-first
  (testing "add-timer! stops the timer already filed under the key"
    (let [k      (Object.)
          killed (atom [])]
      (with-redefs [at/kill (recording-kills killed)]
        (binding [h/*clients* (atom {k {:client-id "ka-twice"}})]
          (h/add-timer! k keep-alive-secs)
          (let [first-timer (get-in @h/*clients* [k :timer])]
            (h/add-timer! k keep-alive-secs)
            (is (some #(identical? first-timer %) @killed)
                "the first timer must be stopped, not orphaned")
            (is (not (identical? first-timer (get-in @h/*clients* [k :timer])))
                "the entry holds the new timer")
            (h/remove-timer! k)))))))

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

(deftest ^:portable idle-client-is-disconnected
  (testing "a silent client is dropped after 1.5 x keep alive"
    (let [{:keys [client] :as c} (tu/connect! "ka-idle" :keep-alive keep-alive-secs)]
      (is (true? (client/connected? client)) "connected once the CONNACK is in")
      (is (tu/wait-until #(not (client/connected? client)) past-the-limit-ms)
          (str "still connected " past-the-limit-ms "ms into a keep alive of "
               keep-alive-secs "s of silence"))
      (tu/close! c))))

(deftest ^:portable active-client-is-not-disconnected
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
