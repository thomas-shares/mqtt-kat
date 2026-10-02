(ns mqttkat.web.cluster
  "The console's view of the whole cluster, and of each broker in it.

   Every broker reports what its own console shows — its reading, its
   busiest topics, its clients, its recent events, and the chart points
   taken since it last reported — to Rama every few seconds
   (`$$broker-detail`, `$$broker-history`; see mqttkat.rama.module). The
   brokers need not share a machine, so Rama is the one place any console
   can read the others from. This namespace reads them back, keeps a copy
   refreshed while someone is looking, and adds them up.

   A view is one broker as the console shows it:

     {:id :self :address :console :stale :up-ms :age-ms :stats
      :detail {:reading :topics :clients :events :console-port}}

   This broker's own is always its live one, never its report back from
   Rama: that is up to five seconds old, and the console on this broker
   should not show it older than it is. Not attached to a cluster, the
   console has one view, this broker's, and every page reads as it did
   before there was a cluster."
  (:require [clojure.tools.logging :as log]
            [mqttkat.rama.cluster :as rama]
            [mqttkat.web.state :as state]))

(def lag-ms
  "How far behind now the cluster's charts end. A broker reports its points
   every five seconds; a second that not every broker has reported yet
   would be drawn as a dip that fills in on the next frame, so the chart
   stops where every report should be in."
  7000)

(def wait-ms
  "How far behind now a cluster chart may end while a broker's points are
   late — reported late, or slow to come back from a busy Rama — rather
   than draw that broker as gone for the seconds it has not reported yet:
   a page keeps the points it has, so a dip drawn once stays. A broker
   later than this is drawn without, as one that has stopped would be."
  20000)

(def carry-seconds
  "How long a broker's last chart point stands in for it when a second has
   none of its own. A broker samples once a second on its own clock, so now
   and then a second has no point from it, or two; carried, that is not a
   dip in the total."
  3)

(def history-ms
  "How far back the copy of the other brokers' history goes: what Rama
   keeps."
  (* 30 60 1000))

(def refresh-ms
  "The least time between two reads of the other brokers' reports. They
   change every five seconds; a page load and a tick in the same second
   need not both go to Rama."
  900)

;; ── this broker ───────────────────────────────────────────────────────

(defonce ^:private remote
  ;; The other brokers as Rama last had them: broker-id -> {:detail …
  ;; :history [points…]}, and when that was read.
  (atom {:at 0 :brokers {}}))

(defonce ^:private local
  ;; The detail this broker last built, on the websocket's tick.
  (atom nil))

(defonce ^:private local-events
  ;; This broker's recent events, newest first, as the websocket logs them:
  ;; kept apart from the tick's detail so one logged between two ticks is
  ;; on the page at once.
  (atom []))

(defn local-detail
  "This broker's detail, from `reading` (with its :rates, :cpu and :topics,
   as state/sample! returns it). What is reported to Rama, and what this
   broker's own view is."
  [reading]
  {:reading      (dissoc reading :topics :cpu-nanos)
   :topics       (vec (:topics reading))
   :clients      (state/client-rows)
   :events       @local-events
   :console-port @state/console-port})

(defn note-local!
  "Keep `detail` as this broker's view until the next tick."
  [detail]
  (reset! local detail))

(defn note-events!
  "This broker's recent events are now `events`, newest first."
  [events]
  (reset! local-events (vec events)))

(defn- current-local
  "This broker's detail: the tick's, or one built now when no tick has run
   yet — a page rendered before the websocket has started."
  []
  (assoc (or @local (local-detail (state/current))) :events @local-events))

(defonce ^:private own-history
  ;; This broker's chart points, oldest first, as a function: the websocket
  ;; keeps them, and says where with `chart-from!`.
  (atom (constantly [])))

(defonce ^:private peak-cache
  ;; [what it was worked out from, the cluster's peak]: see cluster-peak.
  (atom nil))

(defn chart-from!
  "Read this broker's chart points from `f`, a function of no arguments."
  [f]
  (reset! own-history f))

(defn forget!
  "Drop the copies. For tests and restarts."
  []
  (reset! own-history (constantly []))
  (reset! local nil)
  (reset! local-events [])
  (reset! remote {:at 0 :brokers {}})
  (reset! peak-cache nil))

;; ── the others, from Rama ─────────────────────────────────────────────

(defn- trim [points now]
  (let [cutoff (- (long now) history-ms)]
    (into [] (drop-while #(< (long (:t %)) cutoff)) points)))

(defn- refresh-one [{:keys [history] :as had} id now]
  (let [after (:t (peek history))
        fresh (rama/broker-history id after)]
    {:detail  (rama/broker-detail id)
     :history (trim (into (or history []) fresh) now)}))

(defn refresh!
  "Read every other broker's latest report, and the chart points it has
   added since the last read, from Rama. At most once in `refresh-ms`;
   a no-op when not attached. A broker that has left is dropped."
  []
  (let [now (System/currentTimeMillis)
        {:keys [at brokers]} @remote]
    (when (and (rama/attached?) (>= (- now (long at)) refresh-ms))
      (try
        (let [ids (remove #{rama/broker-id} (keys (rama/brokers)))]
          (reset! remote {:at      now
                          :brokers (into {} (for [id ids]
                                              [id (refresh-one (get brokers id) id now)]))}))
        (catch Throwable t
          (log/warn t "could not read the other brokers' reports from Rama"))))))

(defonce ^:private refreshing (atom false))

(defn refresh-soon!
  "refresh!, on a thread of its own unless one is still running: for the
   console's tick, which must not wait on Rama to take this broker's own
   sample."
  []
  (when (compare-and-set! refreshing false true)
    (.start (Thread/ofVirtual)
            ^Runnable (fn []
                        (try (refresh!)
                             (finally (reset! refreshing false)))))))

(defn- remote-of [id]
  (get-in @remote [:brokers id]))

;; ── views ─────────────────────────────────────────────────────────────

(defn- self-row []
  {:id rama/broker-id :self true :address nil :stale false})

(defn views
  "Every broker, this one first, each with the detail to show for it —
   nil for one that has not reported yet."
  []
  (let [rows (state/broker-rows)
        rows (if (some :self rows) rows (cons (self-row) rows))]
    (mapv (fn [{:keys [self id] :as row}]
            (assoc row :detail (if self (current-local) (:detail (remote-of id)))))
          rows)))

(defn view
  "The view of broker `id`, or nil when the cluster has no such broker."
  [id]
  (first (filter #(= id (:id %)) (views))))

(defn multi?
  "Whether there is more than this broker to show."
  [vs]
  (boolean (next vs)))

(defn- counted
  "The views whose figures go into the cluster's: this broker, and every
   other that has reported and is not stale — a broker gone quiet would
   otherwise be counted at its last figures for as long as it is listed."
  [vs]
  (filter #(and (:detail %) (or (:self %) (not (:stale %)))) vs))

;; ── adding up ─────────────────────────────────────────────────────────

(def ^:private summed
  "Reading keys that are per broker and add up to the cluster's."
  [:clients :parked :packets-in :packets-out :bytes-in :bytes-out
   :queued :inflight :dropped :throttled :sockets :connects :disconnects
   :publish-in :publish-out :subscriptions :heap :heap-max :cores :client-total])

(def ^:private shared
  "Reading keys that every broker has the same of, or near enough: the
   retained messages are the cluster's, copied to every broker, so adding
   them up would count each once per broker. Taken at the largest."
  [:retained :listed :uptime :tracked-topics])

(defn cluster-reading
  "One reading for the cluster, from each broker's."
  [readings]
  (let [rs (vec (remove nil? readings))
        add (fn [k] (reduce + 0 (keep k rs)))
        top (fn [k] (reduce max 0 (keep k rs)))
        cpus (keep :cpu rs)]
    (merge
     (into {} (map (fn [k] [k (add k)])) summed)
     (into {} (map (fn [k] [k (top k)])) shared)
     {:t                (reduce max (System/currentTimeMillis) (keep :t rs))
      ;; Each broker's is the most it has had at once since it started, so
      ;; adding them up would count peaks that never coincided, and a
      ;; broker restarted since would drop its own: the most any one
      ;; broker has had is all the readings alone can say. cluster-view
      ;; puts the cluster's own peak, from its chart, over this.
      :max-clients      (reduce max (add :clients) (keep :max-clients rs))
      :topics-truncated (boolean (some :topics-truncated rs))
      :cpu              (when (seq cpus) (reduce + 0.0 cpus))
      ;; Per second, and added up like the counters they come from, but for
      ;; the retained count, which is the cluster's on every broker.
      :rates            (let [rates (keep :rates rs)]
                          (assoc (apply merge-with + {} rates)
                                 "retained" (reduce max 0.0 (keep #(get % "retained") rates))))})))

(defn merge-topics
  "The busiest topics across the brokers, from each broker's busiest: a
   topic published to on two brokers is one row with both counts."
  [lists]
  (->> (apply concat lists)
       (reduce (fn [m {:keys [topic total rate]}]
                 (update m topic (fn [a]
                                   {:topic topic
                                    :total (+ (long (:total a 0)) (long (or total 0)))
                                    :rate  (+ (double (:rate a 0.0)) (double (or rate 0.0)))})))
               {})
       vals
       (sort-by (juxt (comp - :rate) (comp - :total) :topic))
       (take state/active-topic-limit)
       vec))

(defn merge-clients
  "The clients across the brokers, from each broker's first few, each row
   saying which broker it is on. `lists` is [broker-id {:rows :total}]
   pairs. A client listed by two brokers — parked on one, connected to the
   other — is shown where it is connected."
  [lists]
  {:total (reduce + 0 (map (comp #(or % 0) :total second) lists))
   :rows  (->> (for [[broker {:keys [rows]}] lists, row rows] (assoc row :broker broker))
               (group-by :id)
               vals
               (map (fn [rows] (first (sort-by (juxt (complement :connected) #(or (:age-ms %) 0)) rows))))
               (sort-by (juxt (complement :connected)
                              (comp - #(or (:inflight %) 0))
                              (comp - #(or (:queued %) 0))
                              :id))
               (take state/client-limit)
               vec)})

(def event-limit 12)

(defn merge-events
  "Every broker's recent events, newest first, each saying where when
   there is more than one broker for it to have been."
  [lists]
  (->> (for [[broker events] lists, e events]
         (if (next lists) (assoc e :broker broker) e))
       (sort-by (comp - #(or (:t %) 0)))
       (take event-limit)
       vec))

(defn- point-of [history-by-second s]
  (when-let [[b p] (first (rsubseq history-by-second <= s))]
    (when (> (long b) (- (long s) carry-seconds)) p)))

(def ^:private plotted [:clients :in :out :queued :heap])

(def ^:private stacked
  "What each broker's share of a cluster chart point is: the two charts'
   series, drawn one band per broker."
  [:clients :in :out])

(defn combine-series
  "One chart point a second, from second `from-s` to `to-s`, each the sum
   over the brokers of that broker's point for the second — or its last
   one, for up to `carry-seconds` — and, under :by, each broker's share.
   `histories` is one [broker-id points] pair per broker, in any order."
  [histories from-s to-s]
  (let [by-second (for [[id h] histories]
                    [id (into (sorted-map) (map (fn [p] [(quot (long (:t p)) 1000) p])) h)])]
    (vec (for [s (range from-s (inc (long to-s)))
               :let [ps (keep (fn [[id h]] (when-let [p (point-of h s)] [id p])) by-second)]
               :when (seq ps)]
           (reduce (fn [acc [id p]]
                     (-> (merge-with + acc (select-keys p plotted))
                         (assoc-in [:by id] (select-keys p stacked))))
                   (assoc (zipmap plotted (repeat 0)) :t (* 1000 (long s)) :by {})
                   ps)))))

(defn chart-end
  "The last second a cluster chart drawn at `now` covers: `lag-ms` ago, or
   the last point of a broker that is behind that — unless it is more than
   `wait-ms` behind. `histories` is [broker-id points] pairs, each oldest
   first."
  [histories now]
  (let [now   (long now)
        floor (- now wait-ms)
        lasts (keep (fn [[_ h]]
                      (when-let [t (:t (peek (vec h)))]
                        (when (>= (long t) floor) (long t))))
                    histories)]
    (quot (long (reduce min (- now lag-ms) lasts)) 1000)))

(defn cluster-history
  "The cluster's chart: this broker's `own` points and every other
   broker's that has reported, added up a second at a time, up to
   chart-end. `since` in millis, or everything kept.

   Every broker with points, stale or not: a point is what the broker had
   at that second however late it arrived, and one that has stopped has
   no points to add after its last."
  ([own] (cluster-history own nil))
  ([own since]
   (let [vs     (filter :detail (views))
         others (keep #(when-not (:self %) [(:id %) (:history (remote-of (:id %)))]) vs)
         now    (System/currentTimeMillis)
         to-s   (chart-end (cons [rama/broker-id own] others) now)
         from-s (quot (long (or since (- now history-ms))) 1000)
         clip   (fn [[id h]] [id (filter #(>= (long (:t %)) (* 1000 (- from-s carry-seconds))) h)])]
     (combine-series (map clip (cons [rama/broker-id own] others)) from-s to-s))))

(defn- cluster-peak
  "The most clients the cluster has had connected at once, over the chart's
   history: the top of its chart. Worked out again only when a report or a
   point of this broker's has come in since."
  []
  (let [own (@own-history)
        key [(:at @remote) (:t (peek (vec own)))]
        [k v] @peak-cache]
    (if (= k key)
      v
      (let [v (reduce max 0 (map :clients (cluster-history own)))]
        (reset! peak-cache [key v])
        v))))

(defn broker-history
  "Broker `id`'s own chart points after `since`, from Rama's copy."
  [id since]
  (let [h (:history (remote-of id))]
    (if since (filterv #(> (long (:t %)) (long since)) h) (vec h))))

;; ── one colour per broker ─────────────────────────────────────────────

(def colours
  "How many brokers get a colour of their own: the console's categorical
   palette (console.css, --broker-0 on). Past that the rest share one
   grey, \"other\", rather than hues made up to order that nobody could
   tell apart."
  7)

(defn palette
  "Broker id -> its colour, 0 to `colours` - 1, or \"other\".

   Each id asks for the slot its hash picks, and takes the next free one
   if that is gone, the ids going in sorted order. So a broker keeps its
   colour as others come and go, unless one it clashed with leaves, and
   every console — each works this out for itself — agrees on it."
  [ids]
  (loop [[id & more] (sort (distinct ids)) taken #{} out {}]
    (cond
      (nil? id)                 out
      (>= (count taken) colours) (recur more taken (assoc out id "other"))
      :else (let [want (mod (hash id) colours)
                  slot (first (remove taken (map #(mod (+ want %) colours) (range colours))))]
              (recur more (conj taken slot) (assoc out id slot))))))

(defn- member-state [{:keys [self stale detail]}]
  (cond self "this broker" stale "stale" (nil? detail) "not reported yet" :else "up"))

(defn members
  "The brokers the overview adds up, each with its colour and the two
   figures its charts draw: [{:id :colour :state :clients :rate}]."
  [vs]
  (let [p (palette (map :id vs))]
    (mapv (fn [{:keys [id] :as v}]
            (let [r     (get-in v [:detail :reading])
                  rates (:rates r {})]
              {:id      id
               :colour  (get p id)
               :state   (member-state v)
               :counted (boolean (some #{v} (counted vs)))
               :clients (state/commas (:clients r 0))
               :rate    (str (state/commas (Math/round (double (+ (get rates "in" 0.0) (get rates "out" 0.0)))))
                             " msg/s")}))
          vs)))

;; ── what a page shows ─────────────────────────────────────────────────

(defn blank-reading
  "A reading of nothing, for a broker that has not reported yet, and under
   one that has, for any figure its report lacks."
  []
  (cluster-reading []))

(defn cluster-view
  "What the overview, topics and clients pages show: {:reading :topics
   :clients :events :brokers :multi?} for the cluster, which is this broker
   alone when it has no cluster."
  []
  (let [vs (views)
        cs (counted vs)
        d  (map :detail cs)]
    {:multi?  (multi? vs)
     :brokers (count vs)
     :counted (count cs)
     ;; Alone, this broker's reading as it is: added up with nothing, it
     ;; would only lose its own timestamp.
     :reading (if (multi? vs)
                (let [r (cluster-reading (map :reading d))]
                  (update r :max-clients max (cluster-peak)))
                (:reading (first d)))
     :topics  (merge-topics (map :topics d))
     :clients (merge-clients (map (juxt :id (comp :clients :detail)) cs))
     :events  (merge-events (map (juxt :id (comp :events :detail)) cs))
     :members (when (multi? vs) (members vs))}))

(defn cluster-fields
  "The readings the cluster pages have besides a broker's own."
  [{:keys [multi? brokers counted]}]
  {"cluster-note"   (cond
                      (not multi?)          "this broker"
                      (= brokers counted)   (str brokers " brokers, added up")
                      :else                 (str counted " of " brokers " brokers reporting, added up"))
   "overview-kind"  (if multi? "Cluster health" "Broker health")
   "overview-title" (if multi? "Cluster overview" "Overview")})

(defn broker-fields
  "The readings the broker page has besides the broker's own figures."
  [{:keys [self stale age-ms detail]}]
  {"bd-state"    (cond self "up, this broker" stale "stale" (nil? detail) "not reported yet" :else "up")
   "bd-reported" (cond self "live" age-ms (str (state/commas (quot (long age-ms) 1000)) " s ago") :else "—")})

(defn broker-page?
  "Whether `page` is one broker's, [:broker id], rather than the cluster's."
  [page]
  (vector? page))

(defn- foot
  "The sidebar is this broker's, whichever broker or cluster the page is
   about."
  []
  {"uptime-foot" (str "up " (state/duration-str (state/uptime-seconds)))})

(defn page-view
  "What `page` shows: {:fields :topics :clients :events :multi?}, and the
   view it came from. `cv` is a delay of the cluster's view, so a tick that
   serves several pages works it out once."
  ([page] (page-view page (delay (cluster-view))))
  ([page cv]
   (if (broker-page? page)
     (let [v (view (second page))
           d (:detail v)]
       {:view    v
        :fields  (merge (state/fields (merge (blank-reading) (:reading d)))
                        (broker-fields v)
                        (foot))
        :topics  (:topics d [])
        :clients (:rows (:clients d) [])
        :events  (:events d [])})
     (let [c @cv]
       {:multi?  (:multi? c)
        :fields  (cond-> (merge (state/fields (:reading c)) (cluster-fields c) (foot))
                   (= page :rama) (merge (state/rama-fields)))
        :topics  (:topics c)
        :clients (:rows (:clients c))
        :events  (:events c)
        :members (:members c)
        :palette (when (:multi? c) (palette (map :id (:members c))))}))))
