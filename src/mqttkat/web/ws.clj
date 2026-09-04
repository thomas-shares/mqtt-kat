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

(def history-size
  "Samples kept for the charts — two minutes at one a second.

   Kept here rather than in the browser so a page that has just opened draws a
   populated chart instead of one that fills in from the left over two
   minutes."
  120)

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

(defonce ^:private sockets (atom #{}))
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
   than the broadcast: one dead browser must not stop the others updating."
  [payload]
  (doseq [ch @sockets]
    (try
      (http/send! ch payload)
      (catch Throwable t
        (log/debug t "dropping a websocket that could not be written to")
        (swap! sockets disj ch)))))

(defn snapshot
  "What a page needs to be completely up to date the moment it connects: the
   readings, the chart history behind them, and the events already logged."
  []
  (let [now (state/current)]
    {:event    "snapshot"
     :interval sample-interval-ms
     :fields   (state/fields now)
     :history  @history
     :events   (recent-events)}))

(defn handler [request]
  (http/as-channel request
                   {:on-open  (fn [ch]
                                (swap! sockets conj ch)
                                (http/send! ch (json/generate-string (snapshot))))
                    :on-close (fn [ch _status]
                                (swap! sockets disj ch))}))

(defn- tick! []
  (let [reading (state/sample!)
        point   (remember! (state/sample-point reading))]
    (when (seq @sockets)
      (broadcast! (json/generate-string {:event  "tick"
                                         :fields (state/fields reading)
                                         :sample point})))))

(defn- describe
  "One line for the events list. The broker emits keywords and ids; turning
   those into a sentence is a presentation job and belongs on this side of the
   boundary, not in handlers."
  [{:keys [event client-id]}]
  (case event
    :client-connected    {:text "connected" :subject (or client-id "a client")}
    :client-disconnected {:text "disconnected" :subject (or client-id "a client")}
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
      (broadcast! (json/generate-string
                   {:event  (name event)
                    :fields (state/fields (state/current))
                    :entry  entry})))))

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
  (doseq [ch @sockets]
    (try (http/close ch) (catch Throwable _ nil)))
  (reset! sockets #{})
  (reset! last-event {})
  (state/forget!))

(defn connected
  "How many browsers are listening."
  []
  (count @sockets))
