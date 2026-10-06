(ns mqttkat.chaos.runner
  "A chaos run: load on a set of brokers while clients are killed, subscribe
   and unsubscribe, and brokers are killed and brought back, followed by a
   check that every message arrived as its QoS promised.

     java -cp target/mqtt-kat-0.0.1-standalone.jar clojure.main \\
       -m mqttkat.chaos.runner chaos/three-brokers.edn [more.edn ...]

   Usually started by scripts/chaos.bb, which brings up Rama and the brokers
   from the same files first. Every file is merged over the defaults below,
   later files over earlier ones, maps deeply: a small file can adjust one
   number of a larger one.

   The run, in order:

     connect    every client, subscribers first, round-robin over the brokers,
                and wait until the subscriptions are in
     load       publishers publish at :rate for :duration-s while the actions
                under :chaos go off at random intervals
     recover    brokers that are down are started again, every client is let
                back in, and (with :final-reconnect?) every persistent
                subscriber reconnects once, which is when a session collects
                what was queued for it
     drain      until nothing has arrived for :drain-ms
     check      mqttkat.chaos.check over what the clients saw

   The report goes to <:report-dir>/<run-id>.edn, a summary to stdout, and the
   exit status is 0 only if nothing broke a QoS promise."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [mqttkat.chaos.check :as check]
            [mqttkat.chaos.client :as c]
            [mqttkat.chaos.ledger :as ledger])
  (:import [java.util Random]
           [java.util.concurrent.atomic AtomicBoolean])
  (:gen-class))

(set! *warn-on-reflection* true)

;; ── configuration ─────────────────────────────────────────────────────

(def defaults
  {:run-id     nil                      ; generated when not given
   :seed       nil                      ; for the random choices; timing is not reproducible
   :report-dir "logs/chaos"

   ;; Where the brokers are and how they were started. scripts/chaos.bb reads
   ;; this too; the runner uses it to find the brokers and to start one again
   ;; after killing it. Broker n listens on :port + n - 1.
   :setup {:brokers {:count 1 :host "127.0.0.1" :port 1885 :http 8085
                     :heap "1g" :rama "external"}
           :rama    {:start? true :stop? false}
           ;; How the brokers share the clients that connect, set for the
           ;; whole cluster through broker 1's console, as its Brokers page
           ;; does, before any client connects:
           ;;   :policy :off          every broker keeps whoever comes to it
           ;;           :round-robin  each broker takes its turn
           ;;           :load         the broker with the fewest clients
           ;;   :via    :disconnect   accepted, then DISCONNECT with a Server Reference
           ;;           :connack      a CONNACK with the Server Reference
           ;; Set every run, so a policy a run leaves in the cluster does not
           ;; carry into the next. With a policy on, every client connects to
           ;; the first broker that is up and goes where it is sent; only
           ;; version 5 clients can be sent, so a policy wants :mqtt {4 0, 5 1}.
           ;; Off, clients start round-robin over the brokers and come back
           ;; to any broker that is up, at random.
           :redirect {:policy :off :via :disconnect}
           ;; This process: chaos.bb starts it with -Xmx :heap.
           :runner  {:heap "4g"}}

   ;; How a broker is killed, stopped and started: brokers.bb unless said
   ;; otherwise. "{n}" is the broker's number; start gets the flags from
   ;; :setup :brokers appended.
   :control {:bb     "bb"
             :script "scripts/brokers.bb"}

   :load {:publishers       10
          :subscribers      30
          :topics           5
          :rate             200         ; messages per second, all publishers together
          :size             64          ; payload bytes
          :duration-s       30
          :window           32          ; unacknowledged QoS 1/2 publishes per publisher
          :qos              {0 1, 1 1, 2 1}   ; weights for each publish's QoS
          :sub-qos          {0 1, 1 1, 2 1}   ; weights for each subscription's QoS
          :persistent       0.75        ; share of subscribers with a persistent session
          :wildcard         0.2         ; share subscribed to every topic with +
          :mqtt             {4 1, 5 1}  ; weights for the protocol version
          :session-expiry-s 3600}

   ;; Each action goes off every :every-ms [min max], at random in between.
   ;; Leave one out to not have it.
   ;;   :kill-client          socket closed without a DISCONNECT, back after :down-ms
   ;;   :disconnect-client    DISCONNECT first, back after :down-ms
   ;;     :who :subscribers, :publishers or :any
   ;;   :toggle-subscription  a subscriber unsubscribes, or subscribes again
   ;;   :kill-broker          SIGKILL, started again after :down-ms
   ;;   :stop-broker          SIGTERM, the same
   ;;     :min-up             never take the brokers below this many
   :chaos {}

   :check {:subscribe-settle-ms 1000    ; how long a subscription takes to reach every broker
           :clean-grace-ms      10000   ; how long a clean session, or a subscription before its UNSUBSCRIBE, must outlive an ack for it to count
           :drain-ms            5000
           :max-drain-ms        120000
           :final-reconnect?    true
           :connect-timeout-ms  30000
           :max-violations      200}})  ; per kind, in the report

(defn deep-merge [& ms]
  (apply merge-with (fn [a b] (if (and (map? a) (map? b)) (deep-merge a b) b)) ms))

(defn read-config [path]
  (let [m (edn/read-string (slurp path))]
    (when-not (map? m)
      (throw (ex-info (str path " should hold a map") {:path path})))
    m))

(defn config
  "The defaults, then each file over the one before."
  [paths]
  (apply deep-merge defaults (map read-config paths)))

(defn brokers
  "[{:n :host :port}] from :setup :brokers."
  [cfg]
  (let [{:keys [count host port]} (get-in cfg [:setup :brokers])]
    (vec (for [n (range 1 (inc (long count)))]
           {:n n :host host :port (+ (long port) (dec n))}))))

;; ── randomness ────────────────────────────────────────────────────────

(defn- pick-weighted
  "A key of `weights` ({k weight}), with probability proportional to it."
  [^Random rng weights]
  (let [total (reduce + (vals weights))
        r     (* (.nextDouble rng) total)]
    (loop [[[k w] & more] (seq weights) acc 0.0]
      (if (or (nil? more) (< r (+ acc (double w))))
        k
        (recur more (+ acc (double w)))))))

(defn- between ^long [^Random rng [lo hi]]
  (let [lo (long lo) hi (long (or hi lo))]
    (if (<= hi lo) lo (+ lo (long (.nextInt rng (int (- hi lo))))))))

(defn- choose [^Random rng xs]
  (when (seq xs) (nth xs (.nextInt rng (count xs)))))

;; ── brokers ───────────────────────────────────────────────────────────

(defn- listening? [{:keys [host port]}]
  (try (with-open [_ (java.net.Socket. ^String host (int port))] true)
       (catch java.io.IOException _ false)))

(defn- wait-until [pred ms]
  (let [deadline (+ (System/currentTimeMillis) (long ms))]
    (loop []
      (cond (pred) true
            (> (System/currentTimeMillis) deadline) false
            :else (do (Thread/sleep 200) (recur))))))

(defn- start-flags [cfg]
  (let [b (get-in cfg [:setup :brokers])]
    (mapcat (fn [k] (when-some [v (get b k)] [(str "--" (name k)) (if (keyword? v) (name v) (str v))]))
            [:port :http :heap :rama :conductor :advertise])))

(defn- control-command [cfg action n]
  (let [{:keys [bb script]} (:control cfg)
        count (get-in cfg [:setup :brokers :count])]
    (case action
      :kill  [bb script "kill" (str n)]
      :stop  [bb script "stop" (str n)]
      :start (into [bb script "start" (str count)] (start-flags cfg)))))

(defn- run-command!
  "Run a control command, its output appended to the run's control log."
  [{:keys [control-log]} argv]
  (let [pb (doto (ProcessBuilder. ^java.util.List (vec argv))
             (.redirectErrorStream true)
             (.redirectOutput (java.lang.ProcessBuilder$Redirect/appendTo (io/file control-log))))]
    (.waitFor (.start pb))))

;; ── the run's state ───────────────────────────────────────────────────

(defn redirecting?
  "Whether the brokers are to send clients on, and the clients to follow."
  [cfg]
  (not= :off (get-in cfg [:setup :redirect :policy] :off)))

(defn- make-clients [cfg ^Random rng lg run-id]
  (let [{:keys [publishers subscribers topics qos sub-qos persistent wildcard mqtt
                window session-expiry-s]} (:load cfg)
        follow?  (redirecting? cfg)
        topic-of #(str "chaos/" run-id "/t" %)
        subs (vec (for [i (range subscribers)]
                    (let [wild? (< (.nextDouble rng) (double wildcard))
                          opts  {:id (str "chaos-" run-id "-s" i) :kind :sub :idx i
                                 :mqtt5? (= 5 (pick-weighted rng mqtt))
                                 :follow-redirects? follow?
                                 :persistent? (< (.nextDouble rng) (double persistent))
                                 :filter (if wild? (str "chaos/" run-id "/+") (topic-of (mod i topics)))
                                 :sub-qos (pick-weighted rng sub-qos)
                                 :session-expiry-s session-expiry-s}]
                      (ledger/client! lg (:id opts) (dissoc opts :id))
                      (c/make lg opts))))
        pubs (vec (for [i (range publishers)]
                    (c/make lg {:id (str "chaos-" run-id "-p" i) :kind :pub :idx i
                                :mqtt5? (= 5 (pick-weighted rng mqtt))
                                :follow-redirects? follow?
                                :persistent? false :window window})))]
    {:subscribers subs
     :publishers  pubs
     :topics      (mapv topic-of (range topics))
     :qos-weights qos}))

(defn- up-brokers [state]
  (->> @(:brokers state) vals (filter :up?) (sort-by :n) vec))

(defn- broker-at
  "The broker up at `server-reference`, \"host:port\", or nil. By port:
   the host a broker advertises need not be the one the runner reaches it
   by."
  [state ^String server-reference]
  (let [port (some-> (subs server-reference (inc (.lastIndexOf server-reference ":"))) parse-long)]
    (first (filter #(= port (:port %)) (up-brokers state)))))

(defn- broker-for
  "Where `cl` connects next: where a broker last sent it, if that one is up;
   otherwise, with redirects on, the first broker up, which sends it on;
   otherwise any broker up, at random."
  [{:keys [rng cfg] :as state} cl]
  (or (some->> (c/take-redirect! cl) (broker-at state))
      (if (redirecting? cfg)
        (first (up-brokers state))
        (locking rng (choose rng (up-brokers state))))))

(defn set-redirect!
  "Set the cluster's redirect policy as the console's Brokers page does: a
   form post to /brokers/redirect on broker 1's console. Returns the HTTP
   status, or nil when the console could not be reached."
  [cfg]
  (let [{:keys [host http]} (get-in cfg [:setup :brokers])
        {:keys [policy via]} (get-in cfg [:setup :redirect])
        body    (str "policy=" (name (or policy :off)) "&via=" (name (or via :disconnect)))
        client  (-> (java.net.http.HttpClient/newBuilder)
                    (.followRedirects java.net.http.HttpClient$Redirect/NEVER)
                    (.build))
        request (-> (java.net.http.HttpRequest/newBuilder
                     (java.net.URI. (str "http://" host ":" http "/brokers/redirect")))
                    (.header "Content-Type" "application/x-www-form-urlencoded")
                    (.POST (java.net.http.HttpRequest$BodyPublishers/ofString body))
                    (.build))]
    (try
      (.statusCode (.send client request (java.net.http.HttpResponse$BodyHandlers/discarding)))
      (catch Exception _ nil))))

;; ── the loops ─────────────────────────────────────────────────────────

(defn- watchdog
  "Every 100 ms: notice dead connections, and connect whatever wants to be
   connected to a broker that is up."
  [{:keys [clients ^AtomicBoolean running rng] :as state}]
  (while (.get running)
    (doseq [cl clients]
      ;; Longer than a broker may hold a CONNACK: one resuming a session
      ;; another broker still has on record waits up to ten seconds for it
      ;; to go (handlers/hand-over-wait-millis). Ten seconds here gave up
      ;; just before every such CONNACK, so a client sent between brokers
      ;; by a redirect went round them for the rest of a load run, never
      ;; reading what it was owed.
      (c/check-connection! cl (get-in state [:cfg :check :connect-timeout-ms] 30000))
      (when (c/wants-connection? cl)
        (if-let [b (broker-for state cl)]
          (when-not (c/connect! cl b)
            (c/back-off! cl 500))
          (c/back-off! cl 500))))
    (Thread/sleep 100)))

(defn- publisher-loop
  [{:keys [^AtomicBoolean publishing topics qos-weights] :as state} cl cfg]
  (let [{:keys [rate publishers size]} (:load cfg)
        rng      (Random. (+ (long (:seed state)) 1000 (long (:idx cl))))
        interval (long (/ (* 1e9 (long publishers)) (max 1 (long rate))))
        t0       (System/nanoTime)]
    (loop [i 0]
      (when (.get publishing)
        (let [due  (+ t0 (* i interval))
              wait (- due (System/nanoTime))]
          (when (pos? wait) (Thread/sleep (quot wait 1000000) (int (mod wait 1000000))))
          (let [r (c/publish! cl (choose rng topics) (pick-weighted rng qos-weights) size 50)]
            (swap! (:tally state) update r (fnil inc 0)))
          (recur (inc i)))))))

(defn- chaos-loop
  "Fire `action` every :every-ms until the load phase ends."
  [{:keys [^AtomicBoolean publishing] :as state} action spec f]
  (let [rng (Random. (+ (long (:seed state)) (long (hash action))))]
    (loop []
      (let [pause (between rng (:every-ms spec))
            end   (+ (System/currentTimeMillis) pause)]
        (while (and (.get publishing) (< (System/currentTimeMillis) end))
          (Thread/sleep (long (min 100 (max 1 (- end (System/currentTimeMillis)))))))
        (when (.get publishing)
          (when (f rng spec)
            (swap! (:actions state) update action (fnil inc 0)))
          (recur))))))

(defn- client-pool [state who]
  (case who
    :subscribers (:subscribers state)
    :publishers  (:publishers state)
    (:clients state)))

(defn- kill-client-action [state graceful?]
  (fn [rng spec]
    (when-let [cl (choose rng (filterv c/connected? (client-pool state (:who spec :any))))]
      (let [broker (c/broker-of cl)]
        (when (c/kill! cl (between rng (:down-ms spec [0 2000])) graceful?)
          (ledger/event! (:ledger state) {:type (if graceful? :disconnect-client :kill-client)
                                          :client (:id cl) :broker broker})
          true)))))

(defn- toggle-action [state]
  (fn [rng _]
    (when-let [cl (choose rng (filterv c/connected? (:subscribers state)))]
      (when-let [what (c/toggle-subscription! cl)]
        (ledger/event! (:ledger state) {:type what :client (:id cl) :broker (c/broker-of cl)})
        true))))

(defn- start-broker! [state n]
  (let [b (get @(:brokers state) n)]
    (run-command! state (control-command (:cfg state) :start n))
    (if (wait-until #(listening? b) 90000)
      (do (swap! (:brokers state) assoc-in [n :up?] true)
          (ledger/event! (:ledger state) {:type :broker-up :broker n})
          (println (format "  broker-%d is back" n)))
      (do (ledger/event! (:ledger state) {:type :broker-failed-to-start :broker n})
          (println (format "  broker-%d did not come back - see logs/brokers/broker-%d.log" n n))))))

(defn- broker-action [state signal]
  (fn [rng spec]
    (let [up (up-brokers state)]
      (when (> (count up) (long (:min-up spec 1)))
        (let [{:keys [n]} (choose rng up)
              down        (between rng (:down-ms spec [5000 10000]))]
          (swap! (:brokers state) assoc-in [n :up?] false)
          (ledger/event! (:ledger state) {:type (if (= :kill signal) :kill-broker :stop-broker)
                                          :broker n})
          (println (format "  %s broker-%d for %d ms" (if (= :kill signal) "killing" "stopping") n down))
          (run-command! state (control-command (:cfg state) signal n))
          (Thread/sleep down)
          (start-broker! state n)
          true)))))

(defn- progress [state]
  (let [{:keys [ledger clients brokers tally actions]} state]
    (into (sorted-map)
          {:t-s        (quot (ledger/now ledger) 1000000)
           :published  (:sent @tally 0)
           :skipped    (:skipped @tally 0)
           :acked      (ledger/acked-count ledger)
           :delivered  (ledger/delivery-count ledger)
           :connected  (str (count (filter c/connected? clients)) "/" (count clients))
           ;; Connected clients by broker: how the clients are spread.
           :per-broker (into (sorted-map) (frequencies (keep #(when (c/connected? %) (c/broker-of %)) clients)))
           :brokers-up (mapv :n (up-brokers state))
           :chaos      @actions})))

(defn- start-thread [f]
  (Thread/startVirtualThread ^Runnable (fn [] (try (f) (catch Throwable t (.printStackTrace t))))))

;; ── the run ───────────────────────────────────────────────────────────

(def ^:private rama-queue-counts
  "The module's counts the drain watches: what is on the queues, and how
   many writes to them it has processed."
  ["queued" "event/enqueue" "event/dequeue"])

(defn- rama-counts
  "A function returning the cluster's queue counts (rama-queue-counts) when
   the brokers use an external Rama, or nil. Resolved here rather than
   required, so a run without Rama never loads it."
  [cfg]
  (when (= "external" (some-> (get-in cfg [:setup :brokers :rama]) name))
    (try
      (let [open   (requiring-resolve 'com.rpl.rama/open-cluster-manager)
            mname  (requiring-resolve 'com.rpl.rama/get-module-name)
            pstate (requiring-resolve 'com.rpl.rama/foreign-pstate)
            ;; A macro, so compiled here rather than resolved.
            select (eval '(fn [ps k] (com.rpl.rama/foreign-select-one (com.rpl.rama.path/keypath k) ps)))
            module @(requiring-resolve 'mqttkat.rama.module/MqttKatModule)
            key    @(requiring-resolve 'mqttkat.rama.module/stats-key)
            c      (open {"conductor.host" (or (get-in cfg [:setup :brokers :conductor]) "localhost")})
            ps     (pstate c (mname module) "$$rama-stats")
            said?  (atom false)]
        (println "  the drain watches Rama's" (pr-str rama-queue-counts) "as well")
        (fn []
          (try
            (-> (apply merge-with + (map #(dissoc % "at") (vals (select ps key))))
                (select-keys rama-queue-counts))
            (catch Throwable t
              (when (compare-and-set! said? false true)
                (println "  the drain could not read Rama's counts:" (or (ex-message t) (str t))))
              nil))))
      (catch Throwable t
        (println "  the drain cannot read Rama's counts, and ends on the clients alone:" (ex-message t))
        nil))))

(defn- drain!
  "Wait until nothing new has been acknowledged or delivered for :drain-ms,
   or :max-drain-ms has gone by. On an external Rama, until its queue
   counts have not moved for as long either: a broker that queued a copy
   on the cluster for a client that had moved on, while Rama was a minute
   behind, has it delivered only once Rama gets to it, and a drain that
   went by the clients alone ended first and counted it lost. Returns how
   it ended and, with Rama, the counts it ended on."
  [state]
  (let [{:keys [drain-ms max-drain-ms]} (get-in state [:cfg :check])
        lg       (:ledger state)
        rama     (rama-counts (:cfg state))
        observe  #(vector (ledger/delivery-count lg)
                          (ledger/acked-count lg)
                          (when rama (rama)))
        deadline (+ (System/currentTimeMillis) (long max-drain-ms))]
    (loop [last (observe) quiet-since (System/currentTimeMillis)]
      (Thread/sleep 250)
      (let [now (System/currentTimeMillis) seen (observe)
            how #(cond-> {:how %} rama (assoc :rama (peek seen)))]
        (cond
          (not= seen last)                          (recur seen now)
          (>= (- now quiet-since) (long drain-ms))  (how :drained)
          (> now deadline)                          (how :gave-up)
          :else                                     (recur last quiet-since))))))

(defn- summarise [result max-violations]
  (-> result
      (update :violations
              (fn [vs] (->> (group-by :kind vs)
                            (mapcat (fn [[_ vs]] (take max-violations vs)))
                            vec)))))

(defn run-scenario!
  "Run the scenario `cfg` against brokers that are already up. Returns the
   check's verdict, with the report's path under :report."
  [cfg]
  (let [run-id  (or (:run-id cfg) (format "%tY%<tm%<td-%<tH%<tM%<tS" (java.util.Date.)))
        seed    (long (or (:seed cfg) (System/currentTimeMillis)))
        rng     (Random. seed)
        lg      (ledger/ledger)
        dir     (doto (io/file (:report-dir cfg)) (.mkdirs))
        bs      (brokers cfg)
        pool    (make-clients cfg rng lg run-id)
        state   (merge pool
                       {:cfg         cfg
                        :seed        seed
                        :rng         rng
                        :ledger      lg
                        :clients     (into (:subscribers pool) (:publishers pool))
                        :brokers     (atom (into (sorted-map) (map (juxt :n #(assoc % :up? true)) bs)))
                        :running     (AtomicBoolean. true)
                        :publishing  (AtomicBoolean. true)
                        :tally       (atom {})
                        :actions     (atom (sorted-map))
                        :control-log (str (io/file dir (str run-id "-control.log")))
                        :drain       (atom nil)})
        chk     (:check cfg)]
    (println "chaos run" run-id "seed" seed)
    (when-let [down (seq (remove listening? bs))]
      (throw (ex-info (str "not listening: " (str/join ", " (map #(str (:host %) ":" (:port %)) down))
                           " - start the brokers first (bb scripts/chaos.bb does)")
                      {:down down})))
    (let [{:keys [policy via]} (get-in cfg [:setup :redirect])
          status (set-redirect! cfg)]
      (println "redirect" (pr-str {:policy policy :via via})
               (if (= 303 status) "set on the cluster" (str "not set: console answered " (pr-str status))))
      (when (and (redirecting? cfg) (not= 303 status))
        (throw (ex-info "could not set the redirect policy - is the console up, and the broker attached to Rama?"
                        {:status status}))))
    ;; connect: subscribers first, spread round-robin, or all to the first
    ;; broker to be sent on
    (doseq [[i cl] (map-indexed vector (:clients state))]
      (when-not (c/connect! cl (if (redirecting? cfg) (first bs) (nth bs (mod i (count bs)))))
        (c/back-off! cl 500)))
    (let [dog (start-thread #(watchdog state))]
      (when-not (wait-until #(every? c/subscribed? (:subscribers state)) (:connect-timeout-ms chk))
        (println "  not every subscriber subscribed in time:"
                 (count (remove c/subscribed? (:subscribers state))) "missing"))
      (Thread/sleep (long (:subscribe-settle-ms chk)))
      (println "load" (pr-str (select-keys (:load cfg) [:publishers :subscribers :topics :rate :duration-s])))
      (let [pubs   (mapv #(start-thread (fn [] (publisher-loop state % cfg))) (:publishers state))
            chaos  (for [[action spec] (:chaos cfg) :when spec]
                     (start-thread
                      #(chaos-loop state action spec
                                   (case action
                                     :kill-client         (kill-client-action state false)
                                     :disconnect-client   (kill-client-action state true)
                                     :toggle-subscription (toggle-action state)
                                     :kill-broker         (broker-action state :kill)
                                     :stop-broker         (broker-action state :stop)))))
            chaos  (doall chaos)
            end    (+ (System/currentTimeMillis) (* 1000 (long (get-in cfg [:load :duration-s]))))
            every  5000]
        (while (< (System/currentTimeMillis) end)
          (Thread/sleep (long (min every (max 1 (- end (System/currentTimeMillis))))))
          (println " " (pr-str (progress state))))
        (.set ^AtomicBoolean (:publishing state) false)
        (run! #(.join ^Thread %) pubs)
        (run! #(.join ^Thread %) chaos))
      ;; recover
      (println "recovering")
      (doseq [[n b] @(:brokers state) :when (not (:up? b))]
        (start-broker! state n))
      (run! c/wake! (:clients state))
      (wait-until #(every? c/connected? (:clients state)) 30000)
      (when (:final-reconnect? chk)
        (doseq [cl (:subscribers state) :when (:persistent? cl)]
          (c/kill! cl 0 true))
        (wait-until #(every? c/connected? (:clients state)) 30000))
      (println "draining")
      (let [{:keys [how rama]} (drain! state)]
        (println " " (pr-str (cond-> (assoc (progress state) :drain how) rama (assoc :rama rama))))
        (when (pos? (long (get rama "queued" 0)))
          (println "  Rama still has" (get rama "queued") "messages queued, with every client connected:"
                   "nothing read them back"))
        (when (= :gave-up how)
          ;; What was still on its way counts as lost below, and a broker
          ;; this far behind may yet have delivered it.
          (println "  the drain gave up with messages still arriving: a loss below may only be late"
                   "- give :check :max-drain-ms more, or the brokers less load"))
        (reset! (:drain state) how))
      (.set ^AtomicBoolean (:running state) false)
      (.join ^Thread dog))
    (let [snap   (ledger/snapshot lg)
          _      (println "checking" (ledger/delivery-count lg) "deliveries")
          t0     (System/nanoTime)
          result (check/check snap {:subscribe-settle (* 1000 (long (:subscribe-settle-ms chk)))
                                    :clean-grace      (* 1000 (long (:clean-grace-ms chk)))
                                    :max-violations   (:max-violations chk)})
          _      (println (format "  checked in %.1f s" (/ (- (System/nanoTime) t0) 1e9)))
          path   (str (io/file dir (str run-id ".edn")))
          counters (apply merge-with + (map c/counters (:clients state)))]
      (run! c/close! (:clients state))
      (with-open [w (io/writer path)]
        (binding [*out* w]
          (pp/pprint {:run-id   run-id
                      :seed     seed
                      :config   cfg
                      :clients  counters
                      :sessions (:clients snap)
                      :chaos    @(:actions state)
                      :drain    @(:drain state)
                      :result   (summarise result (:max-violations chk))
                      :events   (:events snap)})))
      (assoc result :report path :run-id run-id :clients counters :drain @(:drain state)))))

(defn -main [& paths]
  (when (empty? paths)
    (println "usage: clojure -m mqttkat.chaos.runner config.edn [more.edn ...]")
    (System/exit 2))
  (let [result (run-scenario! (config paths))]
    (pp/pprint (select-keys result [:ok? :drain :stats :counts :lost-by :lost-route :lost-by-session
                                    :lost-by-client :lost-span :lost-by-sent :duplicate-by :clients :report]))
    (doseq [v (take 10 (:violations result))]
      (println " " (pr-str (dissoc v :context))))
    (shutdown-agents)
    (System/exit (if (:ok? result) 0 1))))
