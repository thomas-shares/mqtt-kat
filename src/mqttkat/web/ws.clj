(ns mqttkat.web.ws
  "The WebSocket the console listens on.

   Four things go over it. The broker's whole displayed state once a second,
   which is what every reading on the page is kept fresh from; a chart sample
   alongside it; and a message the moment a client connects or disconnects, so
   the count does not sit stale for up to a second when someone is watching it
   change. And, to the Rama page, the module's counts the moment Rama's
   proxy pushes them.

   What \"the broker's state\" is depends on the page. The overview, topics
   and clients pages show the cluster, added up from every broker's report
   to Rama (see mqttkat.web.cluster); a broker's own page, /brokers/<id>,
   shows that one, wherever it runs. With no cluster, the cluster is this
   broker, and every page shows it live.

   Every message carries whole values rather than deltas, so a page that
   missed a frame or has only just opened is right after the next one, and
   there is nothing to replay on reconnect."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [mqttkat.events :as events]
            [mqttkat.rama.cluster :as rama]
            [mqttkat.web.cluster :as cluster]
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
  ;; Not to a socket still waiting for its snapshot: see handler.
  (let [open    (into {} (remove (comp #{::pending} val)) @sockets)
        by-page (into {} (map (fn [page] [page (payload page)])) (distinct (vals open)))]
    ;; A payload of nil is nothing for that page.
    (doseq [[ch page] open
            :let [message (get by-page page)]
            :when message]
      (try
        (http/send! ch message)
        (catch Throwable t
          (log/debug t "dropping a websocket that could not be written to")
          (swap! sockets dissoc ch))))))

(defn- query-params [^String qs]
  (into {}
        (keep (fn [pair]
                (let [[k v] (str/split pair #"=" 2)]
                  (when (seq k)
                    [(java.net.URLDecoder/decode (str k) "UTF-8")
                     (java.net.URLDecoder/decode (str v) "UTF-8")]))))
        (str/split (or qs "") #"&")))

(defn page-of
  "Which page a socket belongs to, from ?page= on the websocket URL: a
   keyword, or [:broker id] for one broker's page, from ?page=broker&id=.

   Anything unrecognised is the overview, which is the page that wants least —
   an unknown page getting the smallest payload is the safe way round."
  [request]
  (let [params (query-params (:query-string request))]
    (case (get params "page")
      "topics"  :topics
      "clients" :clients
      "brokers" :brokers
      "rama"    :rama
      "broker"  (if-let [id (not-empty (get params "id"))] [:broker id] :overview)
      :overview)))

(def ^:private broker-page? cluster/broker-page?)
(def ^:private page-view cluster/page-view)

(defn- self-page?
  "Whether `page` shows this broker's own events: the cluster's pages do,
   and this broker's own."
  [page]
  (or (keyword? page) (= page [:broker rama/broker-id])))

(defn page-fields
  "The readings for `page`."
  [page]
  (:fields (page-view page)))

(defn- local-since [since]
  (if since (filterv #(> (long (:t %)) (long since)) @history) @history))

(defn- page-history
  "The chart points `page` draws, after `since` millis or all of them: the
   cluster's added up, a broker's own, or this broker's."
  [page multi? since]
  (cond
    (broker-page? page)   (if (= (second page) rama/broker-id)
                            (local-since since)
                            (cluster/broker-history (second page) since))
    (and multi? (= page :overview)) (cluster/cluster-history @history since)
    :else                 (local-since since)))

(defn snapshot
  "What a page needs to be completely up to date the moment it connects: the
   readings, the chart history behind them, and the events already logged.

   Plus whichever table that page has: the topic list or the client list. Each
   lives on one page only, so sending both to all three would be idle bandwidth
   on two of them."
  [page]
  (cluster/refresh!)
  (let [pv (page-view page)]
    (cond-> {:event     "snapshot"
             :interval  sample-interval-ms
             ;; So the page can offer windows it can actually fill, rather than
             ;; hard-coding a guess at what the server keeps.
             :retention (* history-size sample-interval-ms)
             :fields    (:fields pv)
             :history   (page-history page (:multi? pv) nil)
             :events    (:events pv)}
      (:palette pv)              (assoc :palette (:palette pv))
      (= page :overview)         (assoc :members (or (:members pv) []))
      (#{:topics} page)          (assoc :topics (:topics pv))
      (#{:clients} page)         (assoc :clients (:clients pv))
      (broker-page? page)        (assoc :topics (:topics pv) :clients (:clients pv))
      (= page :brokers)          (assoc :brokers (state/broker-rows)))))

(defn handler [request]
  (let [page (page-of request)]
    (http/as-channel request
                     {:on-open  (fn [ch]
                                  ;; Registered at once, so it is counted, but
                                  ;; as pending: broadcasts skip it until its
                                  ;; snapshot has gone. Registered with its page
                                  ;; straight away, a tick could land between
                                  ;; the two and reach the page before the
                                  ;; snapshot it is meant to follow. At worst a
                                  ;; page misses the one sample taken while it
                                  ;; was pending; every tick carries whole
                                  ;; values, so only a chart point is lost.
                                  (swap! sockets assoc ch ::pending)
                                  (http/send! ch (json/generate-string (snapshot page)))
                                  ;; Unless it closed meanwhile: on-close has
                                  ;; removed it, and this must not put it back.
                                  (swap! sockets (fn [m] (if (contains? m ch) (assoc m ch page) m))))
                      :on-close (fn [ch _status]
                                  (swap! sockets dissoc ch))})))

(def report-every
  "Every how many samples this broker tells the cluster how it is doing:
   one report in five seconds, for a table that is looked at rather than
   charted."
  5)

(defonce ^:private ticks (atom 0))

(defonce ^:private reported-up-to
  ;; The time of the last chart point the cluster is known to have.
  (atom 0))

(def ^:private report-limit
  "The most chart points one report carries: a minute's. Points not yet
   known to have landed go again in the next report, so a broker whose
   reports failed for a while catches up with its recent past rather than
   sending all of it at once."
  60)

(def ^:private tail-ms
  "How far back a tick's points reach on a page drawn from Rama, past the
   longest a cluster chart waits for a late broker. The page keeps the ones
   newer than it has, so this only has to be longer than a report is late."
  15000)

(defn- report!
  "Tell the cluster how this broker is doing: the registry's few figures,
   what its console shows, and the chart points it does not have yet.
   Onto the event bus, for whoever keeps the cluster's registry — the
   console does not know whether there is one, and need not. Whoever does
   calls :recorded once the report has landed; until then its points are
   sent again with the next one, which writes them again harmlessly."
  [reading detail]
  (let [since   @reported-up-to
        fresh   (filterv #(> (long (:t %)) (long since)) @history)
        samples (subvec fresh (max 0 (- (count fresh) report-limit)))
        upto    (:t (peek samples))]
    (events/emit! (cond-> {:event   :broker-sample
                           :stats   (state/broker-stats reading)
                           :detail  detail
                           :samples samples}
                    upto (assoc :recorded #(swap! reported-up-to max (long upto)))))))

(defn- tick! []
  (let [reading (state/sample!)
        _       (state/rama-sample!)
        point   (remember! (state/sample-point reading))
        detail  (cluster/local-detail reading)]
    (cluster/note-local! detail)
    (when (zero? (mod (swap! ticks inc) report-every))
      (report! reading detail))
    (when (seq @sockets)
      ;; What Rama had last time, while it is read again: a read that takes
      ;; seconds under load would hold up this broker's own points as long.
      (cluster/refresh-soon!)
      (let [cv  (delay (cluster/cluster-view))
            now (System/currentTimeMillis)]
        (broadcast!
         (fn [page]
           (let [pv     (page-view page cv)
                 remote (or (and (broker-page? page) (not= (second page) rama/broker-id))
                            (and (:multi? pv) (= page :overview)))]
             (json/generate-string
              (cond-> {:event  "tick"
                       :fields (:fields pv)}
                ;; A page drawn from Rama gets the last few points and keeps
                ;; the ones it has not got: they arrive five at a time, late.
                remote                    (assoc :samples (page-history page (:multi? pv)
                                                                        (- now tail-ms cluster/wait-ms)))
                (not remote)              (assoc :sample point)
                (:multi? pv)              (assoc :events (:events pv))
                (:palette pv)             (assoc :palette (:palette pv))
                ;; Empty rather than left out, so a page whose cluster has
                ;; shrunk to one broker stops drawing it as a cluster.
                (= page :overview)        (assoc :members (or (:members pv) []))
                (= page :topics)          (assoc :topics (:topics pv))
                (= page :clients)         (assoc :clients (:clients pv))
                (broker-page? page)       (assoc :topics (:topics pv) :clients (:clients pv)
                                                 :events (:events pv))
                (= page :brokers)         (assoc :brokers (state/broker-rows)))))))))))

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
    (cluster/note-events! (recent-events))
    entry))

(defn- push-rama!
  "Rama's proxy has pushed new counts: on to the Rama page, and no other.
   Throttled like the events — each task's copy arrives as its own push, a
   handful a second — and not logged: it is a reading, not something that
   happened."
  []
  (let [now (System/currentTimeMillis)]
    (when (>= (- now (get @last-event :rama-stats 0)) min-event-gap-ms)
      (swap! last-event assoc :rama-stats now)
      (let [payload (delay (json/generate-string {:event "rama" :fields (state/rama-fields)}))]
        (broadcast! (fn [page] (when (= page :rama) @payload)))))))

(defn- on-broker-news [{:keys [event] :as broker-event}]
  (let [entry (log-event! broker-event)
        now   (:t entry)
        sent  (get @last-event event 0)]
    ;; Logged either way, throttled only for sending: the list is what someone
    ;; looks at after the fact, and a burst is exactly when it should not have
    ;; holes in it.
    (when (>= (- now sent) min-event-gap-ms)
      (swap! last-event assoc event now)
      ;; The readings as each page shows them — the cluster's, or one
      ;; broker's — and the entry only to the pages whose events include
      ;; this broker's.
      (let [cv (delay (cluster/cluster-view))]
        (broadcast!
         (fn [page]
           (json/generate-string
            (cond-> {:event  (name event)
                     :fields (:fields (page-view page cv))}
              (self-page? page) (assoc :entry (cond-> entry
                                                (and (keyword? page) (:multi? @cv))
                                                (assoc :broker rama/broker-id)))))))))))

(defn- on-broker-event [{:keys [event] :as broker-event}]
  (case event
    :rama-stats    (push-rama!)
    ;; The console's own report to the cluster, sent from the tick: not
    ;; something that happened to the broker.
    :broker-sample nil
    (on-broker-news broker-event)))

(defn start!
  "Begin sampling and forwarding. Idempotent."
  []
  (events/listen! ::console on-broker-event)
  (cluster/chart-from! (fn [] @history))
  (when (compare-and-set! ticker nil ::starting)
    (let [running (atom true)]
      (reset! ticker running)
      (.start (Thread/ofVirtual)
              ^Runnable (fn []
                          (while @running
                            (let [started (System/currentTimeMillis)]
                              (try
                                (tick!)
                                (catch Throwable t
                                  ;; Reporting on the broker must never stop it.
                                  (log/error t "websocket sample failed")))
                              ;; A sample a second, however long the tick
                              ;; took: one that slept a whole interval after
                              ;; a slow tick left seconds without a point.
                              (Thread/sleep (max 1 (- (long sample-interval-ms)
                                                      (- (System/currentTimeMillis) started))))))))))
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
  (reset! reported-up-to 0)
  (cluster/forget!)
  (state/forget!))

(defn connected
  "How many browsers are listening."
  []
  (count @sockets))
