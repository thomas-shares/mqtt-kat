(ns mqttkat.rama.latency
  "How long the broker's appends to Rama take, by what was appended and
   what it waited for: `enqueue@ack`, `hold@append-ack`, `probe/queues@ack`.

   An append at :append-ack is done once the depot has it; at :ack, once
   the topology has processed it, which is the depot's time plus however far
   behind the topology is. The probes (mqttkat.rama.cluster/probe!) are
   no-ops appended at :ack to each depot every few seconds: their time is
   the topology's lag, with nothing of the broker's own load in it.

   Kept twice: since the broker started, which its reports carry to the
   registry for the chaos runner, and since the last log line, which is
   logged every log-every-millis when there was anything."
  (:require [clojure.tools.logging :as log])
  (:import [java.util.concurrent ConcurrentHashMap CompletableFuture]
           [java.util.concurrent.atomic AtomicLongArray]
           [java.util.function BiConsumer Function]))

(def bounds
  "The upper bounds, in ms, of the buckets a time is counted in. A
   percentile is read as the bound of the bucket it falls in: coarse, and
   enough to tell 5 ms from 500."
  [1 2 5 10 20 50 100 200 500 1000 2000 5000 10000 30000 Long/MAX_VALUE])

(def ^:private n-buckets (count bounds))
;; After the buckets: count, sum, max.
(def ^:private slots (+ n-buckets 3))

(defonce ^:private since-start (ConcurrentHashMap.))
(defonce ^:private since-log (atom (ConcurrentHashMap.)))

(defn- bucket-of ^long [^long ms]
  (loop [i 0]
    (if (<= ms (long (nth bounds i))) i (recur (inc i)))))

(defn- slots-for ^AtomicLongArray [^ConcurrentHashMap m k]
  (.computeIfAbsent m k (reify Function (apply [_ _] (AtomicLongArray. (int slots))))))

(defn- add! [^AtomicLongArray a ^long ms]
  (.incrementAndGet a (int (bucket-of ms)))
  (.incrementAndGet a (int n-buckets))
  (.addAndGet a (int (inc n-buckets)) ms)
  (let [i (int (+ 2 n-buckets))]
    (loop []
      (let [m (.get a i)]
        (when (and (> ms m) (not (.compareAndSet a i m ms)))
          (recur))))))

(defn note!
  "Count `ms` against `k`."
  [k ms]
  (let [ms (max 0 (long ms))]
    (add! (slots-for since-start k) ms)
    (add! (slots-for @since-log k) ms)))

(defn timed
  "`fut`, an append's future, timed from now under `k` when it completes
   without failing. Returns `fut`."
  [k ^CompletableFuture fut]
  (let [t0 (System/nanoTime)]
    (.whenComplete fut (reify BiConsumer
                         (accept [_ _ e]
                           (when-not e
                             (note! k (quot (- (System/nanoTime) t0) 1000000))))))
    fut))

(defn key-of
  "The name an append of `event` waiting for `ack-level` is timed under."
  [event ack-level]
  (str (name event) "@" (name ack-level)))

(defn- percentile [^AtomicLongArray a ^double p]
  (let [n (.get a (int n-buckets))
        want (long (Math/ceil (* p n)))]
    (loop [i 0 seen 0]
      (let [seen (+ seen (.get a (int i)))]
        (if (or (>= seen want) (= i (dec n-buckets)))
          (let [b (long (nth bounds i))]
            (if (= b Long/MAX_VALUE) (.get a (int (+ 2 n-buckets))) b))
          (recur (inc i) seen))))))

(defn- summary-of
  "{k [count p50 p99 max]}, in ms, each percentile the bound of its bucket."
  [^ConcurrentHashMap m]
  (into (sorted-map)
        (keep (fn [[k ^AtomicLongArray a]]
                (let [n (.get a (int n-buckets))]
                  (when (pos? n)
                    [k [n (percentile a 0.5) (percentile a 0.99) (.get a (int (+ 2 n-buckets)))]]))))
        m))

(defn summary
  "Since the broker started: {\"enqueue@ack\" [count p50 p99 max] ...}."
  []
  (summary-of since-start))

(defn take-window!
  "Since the last call, and start a new window."
  []
  (let [[old _] (reset-vals! since-log (ConcurrentHashMap.))]
    (summary-of old)))

(defn log-line
  "One line for `window`, a summary: each key as count/p50/p99/max."
  [window]
  (apply str (interpose " | " (for [[k [n p50 p99 mx]] window]
                                (format "%s n=%d p50<=%d p99<=%d max=%d" k n p50 p99 mx)))))

(def log-every-millis
  "How often the times since the last line are logged."
  10000)

(defn log-window!
  "Log what was appended since the last time, if anything was."
  []
  (let [w (take-window!)]
    (when (seq w)
      (log/info "rama appends, ms, last" (quot log-every-millis 1000) "s:" (log-line w)))))

(defn clear!
  "Forget everything: for tests."
  []
  (.clear ^ConcurrentHashMap since-start)
  (reset! since-log (ConcurrentHashMap.)))
