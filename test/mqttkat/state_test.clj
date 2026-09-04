(ns mqttkat.state-test
  "mqttkat.web.state — the console's readings, and the formatting they arrive
   in.

   Worth its own tests because this is the only place a number the page shows
   is turned into the string it shows: the browser assigns these verbatim, so
   a rounding or unit mistake here is a wrong figure on the page with nothing
   downstream to catch it."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [mqttkat.client :as client]
            [mqttkat.test-util :as tu]
            [mqttkat.web.state :as state]))

(use-fixtures :once tu/broker-fixture)

(deftest bytes-are-reported-in-the-units-they-are-counted-in
  (testing "1024-based, and labelled as such"
    ;; A broker that says GB when it means GiB is out by 7% by the terabyte,
    ;; and the two are only distinguishable from the label.
    (is (= "0 B" (state/bytes-str 0)))
    (is (= "512 B" (state/bytes-str 512)))
    (is (= "1.0 KiB" (state/bytes-str 1024)))
    (is (= "1.5 KiB" (state/bytes-str 1536)))
    (is (= "1.0 MiB" (state/bytes-str (* 1024 1024))))
    (is (= "2.5 GiB" (state/bytes-str (long (* 2.5 1024 1024 1024))))))

  (testing "three significant figures, because that is what the row fits"
    (is (= "500 MiB" (state/bytes-str (* 500 1024 1024)))
        "no decimal once it would be a fourth digit")))

(deftest a-duration-reads-at-a-second-and-at-a-month
  (testing "the two largest non-zero units"
    (is (= "0 s" (state/duration-str 0)))
    (is (= "45 s" (state/duration-str 45)))
    (is (= "2 m 05 s" (state/duration-str 125)))
    (is (= "3 h 00 m" (state/duration-str 10800)))
    (is (= "26 d 04 h" (state/duration-str (+ (* 26 86400) (* 4 3600) 61))))))

(deftest numbers-are-grouped-the-same-way-everywhere
  (testing "Locale/ROOT, not the host's locale"
    ;; The page is one design. A machine set to a German locale should not
    ;; render 1.284 where every other one renders 1,284 — and the test for it
    ;; would pass on the developer's machine either way, which is why this
    ;; asserts the separator rather than trusting the default.
    (is (= "0" (state/commas 0)))
    (is (= "999" (state/commas 999)))
    (is (= "1,284" (state/commas 1284)))
    (is (= "18,402,991" (state/commas 18402991)))))

(deftest a-reading-is-every-number-the-page-needs
  (testing "and nothing is missing or negative"
    (let [r (state/reading)]
      (doseq [k [:clients :parked :max-clients :packets-in :packets-out :bytes-in :bytes-out
                 :queued :inflight :dropped :throttled :sockets :connects :disconnects
                 :publish-in :publish-out :subscriptions :retained :heap :heap-max :uptime]]
        (is (number? (k r)) (str k " should be a number"))
        (is (not (neg? (k r))) (str k " should never be negative")))
      (is (pos? (:heap-max r)) "the heap limit is known")
      (is (<= (:heap r) (:heap-max r)) "used heap cannot exceed the limit"))))

(deftest the-first-sample-reports-no-rate
  (testing "rather than the broker's whole history divided by one second"
    ;; The counters are cumulative and run for the life of the JVM, so a first
    ;; sample with nothing to subtract would report every message the broker
    ;; has ever handled as having happened in the last second.
    (state/forget!)
    (is (empty? (:rates (state/sample!))))
    ;; And an interval to divide by. Two samples inside the same millisecond
    ;; also report no rates — a rate over no elapsed time is not a number —
    ;; which the ticker never hits, sleeping a second between calls.
    (Thread/sleep 30)
    (let [second-sample (state/sample!)]
      (is (seq (:rates second-sample)) "the second one has an interval behind it")
      (is (every? (complement neg?) (vals (:rates second-sample)))
          "and no rate is negative"))))

(deftest reading-the-state-does-not-advance-it
  (testing "a page load must not consume the sampler's interval"
    ;; current/1 exists precisely so that rendering a page does not count as a
    ;; sample: if it did, every page load would halve the interval the next
    ;; rate was worked out over and the rates on screen would depend on how
    ;; often somebody pressed refresh.
    (state/forget!)
    (state/sample!)
    (Thread/sleep 60)
    (state/sample!)
    (let [rates (:rates (state/current))]
      (state/current)
      (state/current)
      (is (= rates (:rates (state/current)))
          "reading repeatedly gives the same rates"))))

(deftest fields-are-strings-keyed-by-element-id
  (testing "ready to assign, with no formatting left to do in the browser"
    (state/forget!)
    (state/sample!)
    (Thread/sleep 30)
    (let [f (state/fields (state/sample!))]
      (is (every? string? (vals f)) "every field is a string")
      (is (every? string? (keys f)) "keyed by id")
      (doseq [id ["stamp" "m-clients" "m-clients-unit" "m-clients-note"
                  "m-throughput" "m-throughput-note" "m-queued" "m-mem"
                  "c-subscriptions" "c-uptime" "t-topics"]]
        (is (contains? f id) (str id " should be filled")))
      (is (re-matches #"Updated \d\d:\d\d:\d\d" (f "stamp")))
      (is (str/ends-with? (f "m-clients-unit") "max")))))

(deftest every-counter-row-has-a-value-and-a-rate
  (testing "so a row added to the table is filled by the same pass"
    ;; counter-rows is the single definition the server renders from and the
    ;; browser fills; a row whose value or rate id was never produced would
    ;; render as an empty cell that stayed empty.
    (state/forget!)
    (state/sample!)
    (Thread/sleep 30)
    (let [f (state/fields (state/sample!))]
      (doseq [{:keys [id rate]} state/counter-rows]
        (is (contains? f (str "c-" id)) (str id " should have a value"))
        (when-not (= :none rate)
          (is (contains? f (str "c-" id "-rate")) (str id " should have a rate")))))))

(deftest cpu-is-measured-against-a-core-not-the-machine
  (testing "one core busy for a second is one core"
    ;; This is the whole reason CPU is differenced here rather than taken from
    ;; getProcessCpuLoad. Both calls work; the difference is what they are a
    ;; share of. getProcessCpuLoad is a share of the whole machine, so on this
    ;; 24-core host a broker genuinely using 11% of a core reported 0.005 and
    ;; the console displayed a confident 0% while it was pushing 2,500
    ;; messages a second.
    (is (= 1.0 (state/cpu-cores 1.0 0 (long 1e9))))
    (is (= 0.5 (state/cpu-cores 1.0 0 (long 5e8))))
    (is (= 0.5 (state/cpu-cores 2.0 0 (long 1e9))) "over two seconds, half a core"))

  (testing "and is not capped at one"
    ;; A broker using four cores should say four, the way top's %CPU does.
    ;; Capping would hide exactly the case worth seeing.
    (is (= 4.0 (state/cpu-cores 1.0 0 (long 4e9)))))

  (testing "unknown when the JVM will not report it"
    (is (nil? (state/cpu-cores 1.0 nil nil)))
    (is (nil? (state/cpu-cores 0.0 0 100)) "and over no elapsed time"))

  (testing "the page gets a string either way"
    (let [f (state/fields (state/current))]
      (is (string? (f "m-mem")) "a percentage, or a dash")
      (is (re-find #"\d+ cores" (f "m-mem-note")) "with the core count for scale"))))

(deftest the-headline-counts-messages-not-packets
  (testing "in and out are PUBLISH, which is what a message is"
    ;; The console displayed "messages in / out" from counters that Connection
    ;; increments for every packet. Under a QoS 1 fan-out to 20,000
    ;; subscribers that showed 122,000/s each way for a broker doing 24,000
    ;; publishes in and 98,000 out — because every one of those deliveries
    ;; brings back a PUBACK. Both numbers looked plausible, which is why this
    ;; needs a test rather than an eye.
    (let [by-id (into {} (map (fn [[k id]] [id k])) @#'state/rated)]
      (is (= :publish-in (by-id "in")))
      (is (= :publish-out (by-id "out")))))

  (testing "packets are never fewer than messages"
    (let [r (state/reading)]
      (is (>= (:packets-in r) (:publish-in r)))
      (is (>= (:packets-out r) (:publish-out r)))))

  (testing "and a QoS 1 exchange moves more packets than it does messages"
    ;; The difference is the acknowledgement traffic, which is most of what a
    ;; broker at QoS 1 is doing and is invisible in a message count.
    (let [before (state/reading)
          c      (tu/connect! "state-packets")
          topic  (tu/topic "packets")]
      (try
        (client/send-message (:client c) {:packet-type :SUBSCRIBE :packet-identifier 1
                                          :topics [{:qos 1 :topic-filter topic}]})
        (tu/expect! (:ch c) :SUBACK)
        (client/send-message (:client c) {:packet-type :PUBLISH :topic topic :qos 1
                                          :packet-identifier 2
                                          :payload (.getBytes "hello" "UTF-8")
                                          :retain? false :duplicate? false})
        ;; PUBACK for the publish, then the delivery of it — this client is
        ;; its own subscriber. Both, so the exchange is finished before the
        ;; counters are read.
        (tu/expect! (:ch c) :PUBACK)
        (tu/expect! (:ch c) :PUBLISH)
        (Thread/sleep 300)
        (let [after (state/reading)]
          (is (> (- (:packets-in after) (:packets-in before))
                 (- (:publish-in after) (:publish-in before)))
              "the SUBSCRIBE and the acknowledgements are packets, not messages"))
        (finally (tu/close! c)))))

  (testing "both are on the page"
    (let [ids (set (map :id state/counter-rows))]
      (doseq [id ["publish-in" "publish-out" "packets-in" "packets-out"]]
        (is (contains? ids id) (str id " should be a counter row"))))))
