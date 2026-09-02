(ns mqttkat.util
  (:require [clojure.tools.logging :as log]
            [mqttkat.handlers :as handlers])
  (:import  [org.mqttkat MqttStat]
            [java.nio.channels SelectionKey]
            [java.util.concurrent.atomic LongAdder]))

(def interval
  "Seconds between stat lines."
  10)

(defn client-counts
  "Connected clients and parked sessions, counted apart.

   Connected clients are keyed in *clients* by their SelectionKey. A
   `clean-session? false` session is kept after its socket has gone, re-keyed
   under its client-id by handlers/remove-client!, so counting the map counts
   both — and that number only ever goes up."
  []
  (let [ks        (keys @handlers/*clients*)
        connected (count (filter #(instance? SelectionKey %) ks))]
    {:connected       connected
     :parked-sessions (- (count ks) connected)}))

(defn- snapshot
  "One reading of the counters, stamped with the clock they were read at.

   LongAdder/sum is not atomic across cells, so a snapshot taken mid-burst can
   be off by the few increments in flight. Over a ten-second window that is
   noise; nothing here needs an exact instantaneous total."
  []
  {:at        (System/nanoTime)
   :queued    (.sum ^LongAdder MqttStat/sentMessages)
   :written   (.sum ^LongAdder MqttStat/writtenMessages)
   :discarded (.sum ^LongAdder MqttStat/discardedMessages)
   :dropped   (.sum ^LongAdder MqttStat/droppedMessages)
   :throttled (.sum ^LongAdder MqttStat/publisherPauses)
   :received  (.sum ^LongAdder MqttStat/receivedMessages)})

(defn- backlog
  "Packets queued for a client and neither written nor dropped yet.

   Clamped at zero: the three counters are LongAdders read one after another,
   so a snapshot taken mid-burst can see `written` from after the `queued` it
   is subtracted from and come out slightly negative. A negative backlog is
   not a thing; it is read skew, and it would otherwise show up in the log and
   in the falling-behind comparison."
  [snap]
  (max 0 (- (:queued snap) (:written snap) (:discarded snap))))

(defn- round1 [x]
  (/ (Math/round (double (* x 10.0))) 10.0))

(defn- rate
  "Per-second rate over the elapsed time, not the nominal interval.

   Thread/sleep guarantees only a lower bound, and reading and logging happen
   outside it, so dividing by `interval` reports a rate that was never
   achieved."
  [k before now]
  (let [elapsed-ns (- (:at now) (:at before))]
    (if (pos? elapsed-ns)
      (round1 (/ (- (k now) (k before)) (/ elapsed-ns 1e9)))
      0.0)))

(defn stats
  "The numbers for one interval, from two snapshots.

   `queued` is what the fan-out promised a client; `written` is what reached
   its socket. They are the same number only on a broker that is keeping up."
  [before now]
  {:clients   (client-counts)
   :received  {:per-second (rate :received before now) :total (:received now)}
   :queued    {:per-second (rate :queued before now)   :total (:queued now)}
   :written   {:per-second (rate :written before now)  :total (:written now)}
   :backlog   (backlog now)
   ;; Two different fates, kept apart on purpose: `discarded` was queued and
   ;; then abandoned when the connection died, `dropped` was never queued
   ;; because the subscriber was too far behind. Only the first is a bug.
   :discarded (:discarded now)
   :dropped   {:per-second (rate :dropped before now) :total (:dropped now)}
   ;; How often a publisher was stopped so a subscriber could catch up. Rising
   ;; means back-pressure is doing the work that dropping would otherwise do.
   :throttled (:throttled now)})

(defn info
  "Log the broker's counters every `interval` seconds, forever.

   Called from mqttkat.server/-main, where it doubles as the thing that keeps
   the main thread alive; it never returns, so at a REPL give it a thread of
   its own. Interrupting it is the way to stop it.

   Reporting starts one interval in rather than immediately: the first sample
   has no window behind it, and dividing a running broker's totals by `interval`
   reports them as if they had all happened in the last ten seconds."
  []
  (loop [before (snapshot)]
    (Thread/sleep ^long (* interval 1000))
    (let [now (snapshot)]
      ;; A broken stat line must not take the loop down with it — losing
      ;; observability silently is worse than one bad log entry.
      (try
        (log/info "stats" (stats before now))
        (let [growth (- (backlog now) (backlog before))]
          (when (pos? growth)
            (log/warn "falling behind: outbound backlog grew by" growth
                      "to" (backlog now)
                      "- publishes are being accepted faster than they can be delivered")))
        (catch Throwable t
          (log/error t "stats reporting failed")))
      (recur now))))
