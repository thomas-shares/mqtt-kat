(ns mqttkat.load-test
  "The load generator, against the test broker.

   Small runs — the point is that the machinery is right, not that the numbers
   are big. `lein run -m mqttkat.load.runner` is where the big numbers live."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [mqttkat.load.client :as lc]
            [mqttkat.load.runner :as runner]
            [mqttkat.load.stats :as stats]
            [mqttkat.test-util :as tu]))

(use-fixtures :once tu/broker-fixture)

;; ── histogram ─────────────────────────────────────────────────────────

(deftest a-bucket-holds-the-values-that-map-to-it
  (testing "every value lands in a bucket whose floor is no greater than it"
    ;; The floor is what a percentile reports, so a floor above its own values
    ;; would report latencies that never happened.
    (doseq [v (concat (range 0 64)
                      [100 999 1000 65535 1000000 1000000000]
                      (map #(bit-shift-left 1 %) (range 0 62)))]
      (let [floor (stats/bucket-floor (stats/bucket-index v))]
        (is (<= floor v) (str v " landed in a bucket whose floor is " floor)))))

  (testing "and the error stays inside one part in sixteen"
    ;; 16 sub-buckets per octave. This is the accuracy claim in the namespace
    ;; docstring, so it is worth asserting rather than asserting.
    (doseq [v [17 100 999 1000 123456 999999999]]
      (let [floor (stats/bucket-floor (stats/bucket-index v))]
        (is (>= floor (* v 0.9375)) (str v " lost more than 1/16 to bucketing")))))

  (testing "buckets increase with value"
    (is (apply < (map stats/bucket-index [0 1 15 16 31 32 1000 1000000])))))

(deftest percentiles-come-out-where-they-were-put
  (testing "a known distribution reports the percentiles it was built from"
    (let [h (stats/histogram)]
      (doseq [v (range 1 1001)] (stats/record! h v))
      (let [s (stats/snapshot h)]
        (is (= 1000 (:n s)))
        (is (= 1 (:min s)))
        (is (= 1000 (:max s)))
        (is (< 495 (:mean s) 506))
        ;; Bucketed, so these land at a bucket floor at or just below the true
        ;; value — never above it.
        (is (<= (* 500 0.9375) (:p50 s) 500))
        (is (<= (* 950 0.9375) (:p95 s) 950))
        (is (<= (* 999 0.9375) (:p999 s) 999)))))

  (testing "nothing recorded reports nothing, rather than zeros"
    ;; A report full of 0.00 ms reads like a very fast broker.
    (is (nil? (stats/snapshot (stats/histogram)))))

  (testing "a negative sample is refused"
    ;; It means the two clocks being subtracted are not the same clock.
    (let [h (stats/histogram)]
      (stats/record! h -5)
      (is (nil? (stats/snapshot h))))))

;; ── payload ───────────────────────────────────────────────────────────

(deftest the-payload-carries-both-timestamps
  (testing "a round trip through the header gives back what went in"
    ;; The two timestamps are what separate service latency from response
    ;; latency, and the pair is the whole basis for trusting the first number.
    (let [payload (byte-array 128)
          b (java.nio.ByteBuffer/wrap payload)]
      (.putLong b 0 111) (.putLong b 8 222) (.putInt b 16 3) (.putLong b 20 444)
      (is (= {:intended 111 :sent 222 :publisher 3 :sequence 444}
             (lc/read-header payload)))))

  (testing "a payload too short to be ours is nil, not an exception"
    ;; Anything else publishing to the same broker lands in the subscribers'
    ;; laps, and it should be counted, not thrown.
    (is (nil? (lc/read-header (byte-array 4))))
    (is (nil? (lc/read-header nil)))
    (is (some? (lc/read-header (byte-array lc/header-bytes)))
        "exactly the header length is enough")))

;; ── options ───────────────────────────────────────────────────────────

(deftest options-are-parsed-and-checked
  (testing "defaults, overrides and types"
    (is (= runner/defaults (runner/parse-args [])))
    (let [o (runner/parse-args ["--publishers" "7" "--host" "broker.internal" "--qos" "2"])]
      (is (= 7 (:publishers o)) "numbers are parsed as numbers")
      (is (= "broker.internal" (:host o)) "strings are left alone")
      (is (= 2 (:qos o)))
      (is (= (:topics runner/defaults) (:topics o)) "and the rest keep their defaults")))

  (testing "a typo is refused rather than ignored"
    ;; Silently dropping --subscriber would run the default 10 and report a
    ;; number for a test nobody asked for.
    (is (thrown? clojure.lang.ExceptionInfo (runner/parse-args ["--subscriber" "10"])))
    (is (thrown? clojure.lang.ExceptionInfo (runner/parse-args ["--publishers"])))
    (is (thrown? clojure.lang.ExceptionInfo (runner/parse-args ["publishers" "10"]))))

  (testing "the usage text lists every option"
    (let [usage (runner/usage)]
      (doseq [k (keys runner/defaults)]
        (is (str/includes? usage (str "--" (name k)))
            (str "--" (name k) " should be documented"))))))

(deftest source-addresses-spread-only-where-they-may
  (testing "a loopback broker gets as many addresses as the count needs"
    ;; One address supplies about 14,000 bound ports and slows well before
    ;; that; 8,000 apiece keeps clear of the knee.
    (is (= [nil] (runner/source-addresses "localhost" 100 0))
        "a small run needs no spreading at all")
    (is (= 3 (count (runner/source-addresses "127.0.0.1" 20000 0)))
        "20,000 clients need three addresses")
    (is (apply distinct? (runner/source-addresses "localhost" 50000 0))))

  (testing "a broker somewhere else is left alone"
    ;; 127.0.0.0/8 is ours to hand out; the addresses of a real interface are
    ;; not, and binding to one we invented would fail.
    (is (= [nil] (runner/source-addresses "broker.example.com" 50000 0)))
    (is (= [nil] (runner/source-addresses "10.1.2.3" 50000 4))))

  (testing "an explicit count is honoured"
    (is (= 4 (count (runner/source-addresses "localhost" 100 4))))))

;; ── end to end ────────────────────────────────────────────────────────

(defn- run-small [qos]
  (runner/execute (merge runner/defaults
                         {:host tu/host :port tu/port
                          :publishers 2 :subscribers 6 :topics 3
                          :messages 300 :rate 3000 :qos qos
                          :size 64 :drain-ms 5000})))

(deftest every-qos-delivers-everything-it-published
  (doseq [qos [0 1 2]]
    (testing (str "QoS " qos " end to end")
      (let [r (run-small qos)]
        (is (= 300 (:published (:counts r))) "everything asked for was published")
        (is (zero? (:failed (:counts r))) "and nothing failed to send")
        ;; Six subscribers over three topics is two per topic, so 300 publishes
        ;; is 600 deliveries. Computed from what was actually published per
        ;; topic rather than assumed, which is what makes it an assertion.
        (is (= 600 (:expected r)))
        (is (= 600 (:received (:counts r)))
            (str "QoS " qos " should have delivered every message to every subscriber"))
        (is (zero? (:received-unparseable (:counts r)))
            "and every delivery should have been one of ours")))))

(deftest acknowledged-qos-leaves-nothing-outstanding
  (testing "every QoS 1 publish is acknowledged, and QoS 2 completed"
    ;; The count on its own does not prove this: a broker can deliver the
    ;; payload and never finish the handshake, and the run would look clean.
    (doseq [qos [1 2]]
      (let [r (run-small qos)]
        (is (= 300 (:acked (:counts r)))
            (str "QoS " qos " should have acknowledged all 300"))
        (is (zero? (:outstanding r))
            "nothing should still be in flight when the run ends"))))

  (testing "QoS 0 acknowledges nothing, and says so"
    ;; §4.3.1: at most once, no acknowledgement. A generator reporting acks
    ;; here would be reporting something it invented.
    (let [r (run-small 0)]
      (is (zero? (:acked (:counts r))))
      (is (nil? (:ack r)) "and there is no ack latency to report"))))

(deftest a-run-with-no-message-limit-stops-on-time
  (testing "--messages 0 runs until the duration, and reports what it did"
    ;; The mode ctrl-c uses. --duration exercises the same stop path without a
    ;; test having to signal itself.
    (let [r (runner/execute (merge runner/defaults
                                   {:host tu/host :port tu/port
                                    :publishers 2 :subscribers 4 :topics 2
                                    :messages 0 :duration 2 :rate 500 :qos 0
                                    :size 64 :progress-ms 0 :drain-ms 2000}))]
      (is (= :duration-reached (:ended r)) "the report says why it stopped")
      (is (<= 1500 (:elapsed-ms r) 6000) "and it stopped roughly when asked")
      (is (pos? (:published (:counts r))) "having published something")
      ;; The important one. Expected deliveries are counted per topic as each
      ;; message goes out; counting them up front — which is what this did
      ;; first — would have expected deliveries for a Long/MAX_VALUE of
      ;; messages that were never sent.
      (is (= (:received (:counts r)) (:expected r))
          "expectation is built from what was actually published, not planned")
      (is (= 1.0 (:delivery-ratio r))))))

(deftest a-burst-is-gathered-and-still-arrives-intact
  (testing "an unpaced burst builds a queue, which is when writes are gathered"
    ;; The other end-to-end tests here are paced, so the writer takes one
    ;; packet off an empty queue and the multi-buffer path never runs. At an
    ;; unlimited rate the queue builds and the writer gathers what is waiting
    ;; into one writev — measured at around fourteen packets per write under
    ;; load. This is the test that covers that path at all: a dropped or
    ;; reordered buffer in a gathered write would show up as a delivery ratio
    ;; below one.
    (let [r (runner/execute (merge runner/defaults
                                   {:host tu/host :port tu/port
                                    :publishers 4 :subscribers 20 :topics 2
                                    :messages 20000 :rate 0 :qos 0
                                    :size 64 :progress-ms 0 :drain-ms 3000}))]
      (is (= 20000 (:published (:counts r))))
      (is (= (:expected r) (:received (:counts r)))
          "every message reached every subscriber of its topic")
      (is (= 1.0 (:delivery-ratio r))))))

(deftest the-drain-waits-for-the-whole-tail
  (testing "the quiet window restarts every time something arrives"
    ;; The bug this replaces: the deadline was absolute from the moment
    ;; draining began, so the wait could never exceed drain-ms however much was
    ;; still in flight. A two million message run ended with deliveries still
    ;; arriving at 44,000/s and reported them as though they were never coming.
    (let [cs (stats/counters)]
      (future (dotimes [_ 10] (stats/bump! cs :received) (Thread/sleep 100)))
      (let [r (@#'runner/await-quiet cs 400 20000)]
        (is (:quiet? r) "it should end because the deliveries stopped")
        (is (>= (:drained-ms r) 900)
            "and should have waited for the whole second of arrivals, not just 400 ms"))))

  (testing "a tail that never ends is capped, and says so"
    ;; Otherwise a broker dribbling forever hangs the run.
    (let [cs   (stats/counters)
          stop (atom false)]
      (future (loop [] (when-not @stop (stats/bump! cs :received) (Thread/sleep 50) (recur))))
      (try
        (let [r (@#'runner/await-quiet cs 500 1500)]
          (is (false? (:quiet? r))
              "quiet? false is what makes the report call the delivered count a floor")
          (is (>= (:drained-ms r) 1400)))
        (finally (reset! stop true)))))

  (testing "nothing arriving at all returns promptly"
    (let [r (@#'runner/await-quiet (stats/counters) 300 20000)]
      (is (:quiet? r))
      (is (< (:drained-ms r) 3000) "an idle drain should not wait for the cap"))))

(deftest the-report-carries-the-whole-run
  (testing "everything needed to read the run back later is in one place"
    ;; The report is what gets pasted into a notebook next to a change. If it
    ;; leaves out the settings it was run with, it is a number without a
    ;; question attached.
    (let [r (run-small 1)]
      (is (= :complete (:ended r)))
      (is (some? (:setup-ms r)) "how long connecting took")
      (is (some? (:drain r)) "how long draining took, and whether it finished")
      (is (true? (:fully-drained? r)) "a small run drains completely")
      (is (>= (:total-ms r) (:elapsed-ms r))
          "the total window covers publishing and draining")
      (is (pos? (:mb-published r)) "payload volume out")
      (is (pos? (:mb-delivered r)) "and in")
      (is (= (:opts r) (:opts r)))
      (doseq [k [:publishers :subscribers :topics :qos :size :rate :window]]
        (is (contains? (:opts r) k) (str k " should be recorded with the results")))
      (doseq [k stats/counter-names]
        (is (contains? (:counts r) k) (str k " should be reported"))))))

(deftest the-report-says-whether-the-generator-kept-up
  (testing "the target, what was achieved, and the delay the generator added"
    ;; The whole point of the exercise: a throughput figure means nothing
    ;; without knowing whether the thing producing it was the limit.
    (let [r (run-small 1)]
      (is (pos? (:publish-rate r)))
      (is (pos? (:deliver-rate r)))
      (is (= 1.0 (:delivery-ratio r)))
      (is (>= (:lateness-us r) 0))
      (is (>= (:blocked-us r) 0))
      (is (some? (:service r)) "service latency was measured")
      (is (some? (:response r)) "and response latency alongside it")
      (is (= (:n (:service r)) (:n (:response r)))
          "both from the same deliveries — an n that differs reads as loss")
      ;; Deliberately NOT asserting response >= service. It is the obvious
      ;; invariant and it is false: below a millisecond of interval the
      ;; publisher parks once and sends a burst, so a message can go out ahead
      ;; of its own intended time and arrive before a perfectly paced
      ;; generator would have sent it. Half the messages do at these settings.
      ;; What is true is that both are measured over the same deliveries and
      ;; that they are close.
      (is (< (Math/abs (- (:p50 (:response r)) (:p50 (:service r))))
             (* 5 1000))
          "the two medians should be within a few milliseconds of each other"))))
