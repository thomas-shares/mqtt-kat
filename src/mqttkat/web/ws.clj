(ns mqttkat.web.ws
  "The WebSocket the console listens on.

   Three things go over it. The broker's whole displayed state once a second,
   which is what every reading on the page is kept fresh from; a chart sample
   alongside it; and a message the moment a client connects or disconnects, so
   the count does not sit stale for up to a second when someone is watching it
   change.

   Every message carries whole values rather than deltas, so a page that
   missed a frame or has only just opened is right after the next one, and
   there is nothing to replay on reconnect."
  (:require [cheshire.core :as json]
            [clojure.tools.logging :as log]
            [mqttkat.events :as events]
            [mqttkat.web.state :as state]
            [org.httpkit.server :as http]))

(def sample-interval-ms
  "How often the broker's state goes out. -Dmqttkat.wsInterval=N milliseconds."
  (if-let [p (System/getProperty "mqttkat.wsInterval")]
    (Long/parseLong p)
    1000))

(def retention-minutes
  "How much chart history the broker keeps, in minutes.
   -Dmqttkat.wsHistoryMinutes=N.

   Kept on the server rather than in the browser so a page that has just opened
   draws a populated chart instead of one that fills in from the left. That is
   also why it is worth having more than a couple of minutes of it: a tab
   opened after something interesting happened can still see it, which two
   minutes never allowed.

   The cost is a snapshot on connect proportional to this and a little heap:
   one small map per sample, thirty minutes of them at one a second."
  (if-let [p (System/getProperty "mqttkat.wsHistoryMinutes")]
    (Long/parseLong p)
    30))

(def history-size
  "Samples kept for the charts, derived from the retention and the interval."
  (max 1 (quot (* retention-minutes 60000) sample-interval-ms)))

(def event-log-size
  "How many broker events the 'recent events' list remembers."
  12)

(def ^:private min-event-gap-ms
  "The least time between two pushes *of the same event*.

   Without a limit a burst of connections is a burst of frames: the scale test
   opens ten thousand in a second, and a browser left open would be sent ten
   thousand messages. Anything skipped is carried by the next sample anyway.

   Per event type rather than overall, which is how this started: one gap for
   everything meant a connect suppressed the disconnect that followed it a few
   milliseconds later, and the page was told a client had arrived but never
   that it had gone."
  100)

(defonce ^:private sockets
  ;; channel -> the page it belongs to, as a keyword. What goes over a socket
  ;; depends on the page: the topic table and the client list each live on one
  ;; page only. A comment rather than a docstring: defonce takes none.
  (atom {}))
(defonce ^:private history (atom []))
(defonce ^:private event-log (atom []))
(defonce ^:private ticker (atom nil))
(defonce ^:private last-event (atom {}))

(defn- remember! [reading]
  (swap! history (fn [h] (vec (take-last history-size (conj h reading)))))
  reading)

(defn recent-events
  "Newest first, which is the order the page lists them in."
  []
  (reverse @event-log))

(defn broadcast!
  "Send to every open socket. A send that fails takes that socket out rather
   than the broadcast: one dead browser must not stop the others updating.

   `payload` is a function of the page, so a message is built once per distinct
   page rather than once per browser — three at most, and usually one."
  [payload]
  (let [open @sockets
        by-page (into {} (map (fn [page] [page (payload page)])) (distinct (vals open)))]
    (doseq [[ch page] open]
      (try
        (http/send! ch (get by-page page))
        (catch Throwable t
          (log/debug t "dropping a websocket that could not be written to")
          (swap! sockets dissoc ch))))))

(defn- page-of
  "Which page a socket belongs to, from ?page= on the websocket URL.

   Anything unrecognised is the overview, which is the page that wants least —
   an unknown page getting the smallest payload is the safe way round."
  [request]
  (case (second (re-find #"(?:^|&)page=([^&]*)" (or (:query-string request) "")))
    "topics"  :topics
    "clients" :clients
    "brokers" :brokers
    :overview))

(defn snapshot
  "What a page needs to be completely up to date the moment it connects: the
   readings, the chart history behind them, and the events already logged.

   Plus whichever table that page has: the topic list or the client list. Each
   lives on one page only, so sending both to all three would be idle bandwidth
   on two of them."
  [page]
  (let [now (state/current)]
    (cond-> {:event     "snapshot"
             :interval  sample-interval-ms
             ;; So the page can offer windows it can actually fill, rather than
             ;; hard-coding a guess at what the server keeps.
             :retention (* history-size sample-interval-ms)
             :fields    (state/fields now)
             :history   @history
             :events    (recent-events)}
      (= page :topics)  (assoc :topics (:topics now))
      (= page :clients) (assoc :clients (:rows (state/client-rows)))
      (= page :brokers) (assoc :brokers (state/broker-rows)))))

(defn handler [request]
  (let [page (page-of request)]
    (http/as-channel request
                     {:on-open  (fn [ch]
                                  (swap! sockets assoc ch page)
                                  (http/send! ch (json/generate-string (snapshot page))))
                      :on-close (fn [ch _status]
                                  (swap! sockets dissoc ch))})))

(def report-every
  "Every how many samples this broker tells the cluster how it is doing:
   one report in five seconds, for a table that is looked at rather than
   charted."
  5)

(defonce ^:private ticks (atom 0))

(defn- tick! []
  (let [reading (state/sample!)
        point   (remember! (state/sample-point reading))]
    ;; Onto the event bus, for whoever keeps the cluster's registry — the
    ;; console does not know whether there is one, and need not.
    (when (zero? (mod (swap! ticks inc) report-every))
      (events/emit! {:event :broker-sample :stats (state/broker-stats reading)}))
    (when (seq @sockets)
      (broadcast!
       (fn [page]
         (json/generate-string
          (cond-> {:event  "tick"
                   :fields (state/fields reading)
                   :sample point}
            (= page :topics)  (assoc :topics (:topics reading))
            (= page :clients) (assoc :clients (:rows (state/client-rows)))
            (= page :brokers) (assoc :brokers (state/broker-rows)))))))))

(defn- describe
  "One line for the events list. The broker emits keywords and ids; turning
   those into a sentence is a presentation job and belongs on this side of the
   boundary, not in handlers."
  [{:keys [event client-id] :as broker-event}]
  (case event
    :client-connected    {:text "connected" :subject (or client-id "a client")}
    :client-disconnected {:text "disconnected" :subject (or client-id "a client")}
    :client-subscribed   {:text (str "subscribed to " (:filter broker-event)) :subject (or client-id "a client")}
    :client-unsubscribed {:text (str "unsubscribed from " (:filter broker-event)) :subject (or client-id "a client")}
    {:text (name event) :subject (or client-id "")}))

(defn- log-event! [broker-event]
  (let [entry (assoc (describe broker-event) :t (System/currentTimeMillis))]
    (swap! event-log (fn [l] (vec (take-last event-log-size (conj l entry)))))
    entry))

(defn- on-broker-event [{:keys [event] :as broker-event}]
  (let [entry (log-event! broker-event)
        now   (:t entry)
        sent  (get @last-event event 0)]
    ;; Logged either way, throttled only for sending: the list is what someone
    ;; looks at after the fact, and a burst is exactly when it should not have
    ;; holes in it.
    (when (>= (- now sent) min-event-gap-ms)
      (swap! last-event assoc event now)
      (let [payload (json/generate-string
                     {:event  (name event)
                      :fields (state/fields (state/current))
                      :entry  entry})]
        ;; The same for every page: an event is a reading, not a table.
        (broadcast! (constantly payload))))))

(defn start!
  "Begin sampling and forwarding. Idempotent."
  []
  (events/listen! ::console on-broker-event)
  (when (compare-and-set! ticker nil ::starting)
    (let [running (atom true)]
      (reset! ticker running)
      (.start (Thread/ofVirtual)
              ^Runnable (fn []
                          (while @running
                            (try
                              (tick!)
                              (catch Throwable t
                                ;; Reporting on the broker must never stop it.
                                (log/error t "websocket sample failed")))
                            (Thread/sleep (long sample-interval-ms)))))))
  true)

(defn stop! []
  (events/forget! ::console)
  (when-let [running @ticker]
    (when (instance? clojure.lang.Atom running)
      (reset! running false)))
  (reset! ticker nil)
  (doseq [ch (keys @sockets)]
    (try (http/close ch) (catch Throwable _ nil)))
  (reset! sockets {})
  (reset! last-event {})
  (state/forget!))

(defn connected
  "How many browsers are listening."
  []
  (count @sockets))
