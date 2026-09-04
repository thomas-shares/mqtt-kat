(ns mqttkat.ws-test
  "The websocket the console listens on, end to end: a real socket, a real MQTT
   client connecting to the broker, and the count arriving unprompted."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [mqttkat.client :as client]
            [mqttkat.web.console :as console]
            [mqttkat.events :as events]
            [mqttkat.test-util :as tu]
            [mqttkat.web.server :as web]
            [mqttkat.web.state :as state]
            [mqttkat.web.ws :as ws])
  (:import [java.net URI]
           [java.net.http HttpClient WebSocket WebSocket$Listener]
           [java.util.concurrent CompletableFuture LinkedBlockingQueue TimeUnit]))

(use-fixtures :once tu/broker-fixture)

(defn- console-markup
  "Every page that is kept live, as one string."
  []
  (str (console/overview-page) (console/topics-page)))

(defn- listener [^LinkedBlockingQueue received]
  (reify WebSocket$Listener
    (onOpen [_ socket] (.request socket 1))
    (onText [_ socket data _last]
      (.add received (str data))
      (.request socket 1)
      (CompletableFuture/completedFuture nil))
    (onError [_ _socket _error] nil)))

(defn- take-message
  "The next frame, or nil. Seconds rather than milliseconds because what is
   being waited on is a broker connect travelling out through an event
   listener and a socket."
  [^LinkedBlockingQueue q]
  (.poll q 5 TimeUnit/SECONDS))

(defn- take-event
  "The next frame of a given event type, skipping the once-a-second samples
   that share the stream with it."
  [^LinkedBlockingQueue q event]
  (let [deadline (+ (System/currentTimeMillis) 8000)]
    (loop [skipped []]
      (if-let [msg (and (< (System/currentTimeMillis) deadline) (take-message q))]
        (if (str/includes? msg (str "\"event\":\"" event "\""))
          msg
          (recur (conj skipped msg)))
        (do (is false (str "no " event " frame; saw " (pr-str skipped))) nil)))))

(defn- await-clients
  "Wait for a frame saying the broker has `n` clients, whatever kind of frame
   it is.

   The count is what the page shows, and it is promised by two routes: an event
   the moment something happens, and the sample a second later. Which arrives
   first is not promised — connect/disconnect pushes are rate limited per event
   type, so a burst elsewhere in the suite can legitimately swallow one — and a
   test that insisted on the event was asserting the mechanism rather than the
   result.

   `n` is a count relative to whatever the broker already had, never an
   absolute one. This asserted 1 and then 0 while it was the only thing
   connecting; run after the rest of the suite it saw 3 and 2, because earlier
   namespaces leave clients registered. The two counts were checked against
   each other before this was changed — MqttStat and util/client-counts agree
   exactly — so the leftovers are real connections and the test was wrong, not
   the count."
  [^LinkedBlockingQueue q n]
  (let [deadline (+ (System/currentTimeMillis) 10000)]
    (loop [seen []]
      (if-let [msg (and (< (System/currentTimeMillis) deadline) (take-message q))]
        ;; Every frame carries the fields, so the count is on all three kinds.
        (if (= (str n) (second (re-find #"\"m-clients\":\"([\d,]+)\"" msg)))
          msg
          (recur (conj seen msg)))
        (do (is false (str "no frame reporting " n " clients; saw " (pr-str seen))) nil)))))

(deftest a-sample-is-a-rate-not-a-total
  (testing "in and out are messages per second, worked out from the counters"
    ;; The counters are cumulative, so the first sample has nothing to compare
    ;; against and must report zero rather than the broker's whole history.
    (state/forget!)
    (let [first-sample (state/sample-point (state/sample!))]
      (is (zero? (:in first-sample)) "the first sample has no interval behind it")
      (is (zero? (:out first-sample)))
      (is (contains? first-sample :clients))
      (is (contains? first-sample :t)))

    ;; Now make some traffic and sample again.
    (let [c (tu/connect! "ws-rate")]
      (client/send-message (:client c) {:packet-type :SUBSCRIBE :packet-identifier 1
                                        :topics [{:qos 0 :topic-filter (tu/topic "rate")}]})
      (tu/expect! (:ch c) :SUBACK)
      (Thread/sleep 150)
      (let [second-sample (state/sample-point (state/sample!))]
        (is (pos? (:in second-sample))
            "packets arrived between the two samples, so the rate is above zero"))
      (tu/close! c))))

(deftest a-sample-carries-one-field-per-plotted-line
  (testing "the chart sample is small, and is every series the page draws"
    ;; A hundred and twenty of these are held per browser and sent on every
    ;; open, so this is the payload worth keeping an eye on. It is also the
    ;; list console.js reads by name: a series added to a chart there and not
    ;; here draws a flat line at zero rather than failing.
    (is (= #{:t :clients :in :out :queued :heap}
           (set (keys (state/sample-point (state/sample!))))))))

(deftest the-snapshot-carries-enough-to-draw-with
  (testing "a page that has just opened gets history, not an empty chart"
    (let [snap (ws/snapshot)]
      (is (= "snapshot" (:event snap)))
      (is (= ws/sample-interval-ms (:interval snap))
          "the page is told how often to expect a sample")
      (is (vector? (:history snap))
          "and the samples kept so far, so the chart is populated at once")
      (is (string? (get (:fields snap) "m-clients"))
          "and every reading on the page, ready to assign")
      (is (contains? snap :events)
            "and the events already logged, so the list is not empty on open"))))

(deftest every-id-the-page-renders-is-a-field-the-socket-sends
  (testing "the page and the socket agree about what is kept up to date"
    ;; This is the whole contract between console.clj and console.js: the
    ;; server renders an id, state/fields hands out a string for it, the
    ;; browser assigns it. An id in the markup with no field behind it is a
    ;; reading frozen at page load, which looks live and is not — the failure
    ;; this is here to catch, because nothing else would.
    (let [fields (state/fields (state/current))
          markup (str (console-markup))
          ;; Only the ids that are readings. The charts and their wrappers are
          ;; addressed by id too, and are drawn rather than assigned.
          ids    (->> (re-seq #"id=\"([^\"]+)\"" markup)
                      (map second)
                      (remove #(or (str/starts-with? % "chart")
                                   (str/starts-with? % "spark")
                                   (str/starts-with? % "axis")
                                   (str/starts-with? % "grad")
                                   (= % "event-list"))))]
      (is (seq ids) "the page should render ids at all")
      (doseq [id ids]
        (is (contains? fields id)
            (str "the page renders #" id " but nothing keeps it up to date"))))))

(deftest events-reach-listeners-and-survive-a-bad-one
  (testing "one listener throwing does not stop the others"
    ;; These run on the connection's own reader thread, so a listener that
    ;; throws would otherwise take a client's CONNECT down with it.
    (let [seen (atom [])]
      (try
        (events/listen! ::boom (fn [_] (throw (RuntimeException. "no"))))
        (events/listen! ::ok (fn [e] (swap! seen conj e)))
        (events/emit! {:event :test :clients 1})
        ;; Contains rather than equals: the broker is shared with the rest of
        ;; the suite, so a real connect or disconnect can land here too.
        (is (some #{{:event :test :clients 1}} @seen)
            (str "the good listener should have been called; saw " (pr-str @seen)))
        (finally
          (events/forget! ::boom)
          (events/forget! ::ok))))))

(deftest the-console-is-told-when-clients-come-and-go
  (testing "a browser gets a snapshot on connect, then every change"
    (try
      (let [port     (web/start! 0)
            received (LinkedBlockingQueue.)
            ^WebSocket socket (-> (HttpClient/newHttpClient)
                         (.newWebSocketBuilder)
                         (.buildAsync (URI. (str "ws://localhost:" port "/ws"))
                                      (listener received))
                         (.get 5 TimeUnit/SECONDS))]
        (is (some? socket) "the websocket should connect")

        (testing "the current state arrives without asking"
          (let [first-message (take-message received)]
            (is (some? first-message) "a snapshot should be sent on open")
            (is (str/includes? first-message "\"event\":\"snapshot\""))
            (is (str/includes? first-message "\"history\"")
                "carrying the samples kept so far")))

        (testing "connecting an MQTT client reaches the browser"
          (is (= 1 (ws/connected)) "exactly this test's browser should be listening")
          (let [before (:clients (state/reading))
                c      (tu/connect! "ws-watched")]
            (is (some? (await-clients received (inc before)))
                "the page should be told a client arrived")
            (testing "and so does its going away"
              (tu/close! c)
              (is (some? (await-clients received before))
                  "the page should be told it left"))))

        (testing "a sample arrives on its own every interval"
          (let [tick (take-event received "tick")]
            (is (some? tick) "the state should be pushed without anything happening")
            (is (re-find #"\"in\":\d+" (str tick)) "carrying the inbound rate")
            (is (re-find #"\"out\":\d+" (str tick)) "and the outbound one")
            (is (str/includes? (str tick) "\"fields\"")
                "and every reading on the page, not only the chart sample")))

        (testing "an event says which client, not just that the count moved"
          (let [c (tu/connect! "ws-named")]
            (try
              (let [frame (take-event received "client-connected")]
                (is (str/includes? (str frame) "ws-named")
                    "the events list should be able to name the client"))
              (finally (tu/close! c)))))

        (.abort socket))
      (finally
        (web/stop!)))))

(deftest a-closed-browser-is-forgotten
  (testing "sockets are dropped when they go, not left to be written to"
    (try
      (let [port   (web/start! 0)
            ^WebSocket socket (-> (HttpClient/newHttpClient)
                       (.newWebSocketBuilder)
                       (.buildAsync (URI. (str "ws://localhost:" port "/ws"))
                                    (listener (LinkedBlockingQueue.)))
                       (.get 5 TimeUnit/SECONDS))]
        (is (= 1 (ws/connected)))
        (.abort socket)
        (let [deadline (+ (System/currentTimeMillis) 5000)]
          (loop []
            (when (and (pos? (ws/connected)) (< (System/currentTimeMillis) deadline))
              (Thread/sleep 50)
              (recur))))
        (is (zero? (ws/connected)) "the closed socket should have been dropped"))
      (finally
        (web/stop!)))))
