(ns mqttkat.load.stats
  "Counters and latency histograms for the load generator.

   Kept apart from the broker's own MqttStat on purpose. Those counters are
   what the broker says about itself; these are what an outside observer
   measured, and the whole point of the exercise is being able to hold the two
   up against each other. (They would also collide: MqttClient increments
   MqttStat on every send, so in a single JVM the generator's traffic and the
   broker's are the same numbers.)

   Everything here is written from many virtual threads at once and read once
   at the end, which is what LongAdder is for."
  (:import [java.util.concurrent.atomic AtomicLong DoubleAdder LongAdder]))

(set! *warn-on-reflection* true)

;; ── histogram ─────────────────────────────────────────────────────────
;;
;; Log-linear buckets: every value under 16 gets its own, and above that each
;; octave is split into 16. That is ~6% worst-case error on a reported
;; percentile, which is far finer than the run-to-run variation these numbers
;; have, at a fixed 1024 buckets no matter how many samples arrive.
;;
;; The alternative was keeping every sample to sort later. At the rates this is
;; built to drive — millions of messages — that is hundreds of megabytes of
;; long[] in the process doing the measuring, which is exactly how a load
;; generator becomes the thing it is measuring.

(def ^:private ^:const sub-count 16)
(def ^:private ^:const bucket-count 1024)

(defn bucket-index
  "Which bucket a value falls in. Public because the round trip through
   bucket-floor is worth testing directly."
  ^long [^long v]
  (if (< v sub-count)
    (max 0 v)
    (let [e   (- 63 (Long/numberOfLeadingZeros v))
          sub (- (unsigned-bit-shift-right v (- e 4)) 16)]
      (+ 16 (* (- e 4) 16) sub))))

(defn bucket-floor
  "The smallest value that lands in a bucket — what a percentile reports."
  ^long [^long idx]
  (if (< idx sub-count)
    idx
    (let [k   (- idx 16)
          e   (+ (unsigned-bit-shift-right k 4) 4)
          sub (bit-and k 15)]
      (bit-shift-left (+ 16 sub) (- e 4)))))

(defn histogram []
  {:buckets (into-array LongAdder (repeatedly bucket-count #(LongAdder.)))
   :n       (LongAdder.)
   :sum     (LongAdder.)
   :sumsq   (DoubleAdder.)
   :min     (AtomicLong. Long/MAX_VALUE)
   :max     (AtomicLong. Long/MIN_VALUE)})

(defn record!
  "Add one sample, in microseconds. Negative samples are dropped rather than
   clamped: they mean the two clocks being subtracted are not the same clock,
   and silently recording them as zero would hide that."
  [h ^long v]
  (when-not (neg? v)
    (.increment ^LongAdder (aget ^"[Ljava.util.concurrent.atomic.LongAdder;" (:buckets h)
                                 (int (bucket-index v))))
    (.increment ^LongAdder (:n h))
    (.add ^LongAdder (:sum h) v)
    (.add ^DoubleAdder (:sumsq h) (* (double v) (double v)))
    (.accumulateAndGet ^AtomicLong (:min h) v (reify java.util.function.LongBinaryOperator
                                                (applyAsLong [_ a b] (Math/min a b))))
    (.accumulateAndGet ^AtomicLong (:max h) v (reify java.util.function.LongBinaryOperator
                                                (applyAsLong [_ a b] (Math/max a b))))))

(defn- percentiles
  "Walks the buckets once for the whole set of quantiles, rather than once
   each."
  [^"[Ljava.util.concurrent.atomic.LongAdder;" buckets ^long n quantiles]
  (let [targets (mapv (fn [q] [q (long (Math/ceil (* q n)))]) quantiles)]
    (loop [idx 0, seen 0, todo targets, out {}]
      (cond
        (empty? todo) out
        (>= idx bucket-count) (into out (map (fn [[q _]] [q (bucket-floor (dec bucket-count))])) todo)
        :else
        (let [seen (+ seen (.sum ^LongAdder (aget buckets idx)))
              [done rest] (split-with (fn [[_ target]] (<= target seen)) todo)]
          (recur (inc idx) seen (vec rest)
                 (into out (map (fn [[q _]] [q (bucket-floor idx)])) done)))))))

(defn snapshot
  "Microseconds throughout. nil when nothing was recorded, so a report can say
   so rather than print zeros for a measurement that never happened."
  [h]
  (let [n (.sum ^LongAdder (:n h))]
    (when (pos? n)
      (let [sum  (.sum ^LongAdder (:sum h))
            mean (/ (double sum) n)
            var  (max 0.0 (- (/ (.sum ^DoubleAdder (:sumsq h)) n) (* mean mean)))
            ps   (percentiles (:buckets h) n [0.5 0.95 0.99 0.999])]
        {:n    n
         :min  (.get ^AtomicLong (:min h))
         :max  (.get ^AtomicLong (:max h))
         :mean mean
         :sd   (Math/sqrt var)
         :p50  (ps 0.5)
         :p95  (ps 0.95)
         :p99  (ps 0.99)
         :p999 (ps 0.999)}))))

;; ── counters ──────────────────────────────────────────────────────────

(def counter-names
  "Every counter the run keeps, in the order a report reads them.

   The three publish counters are separate for the same reason the broker
   separates queued from written: `attempted` is what the schedule asked for,
   `published` is what reached the socket, and `failed` is the difference with
   a reason. A generator that reports only the last one it managed cannot tell
   you it was the bottleneck."
  [:attempted :published :failed :acked
   :received :received-dup :received-unparseable])

(defn counters []
  (into {} (map (fn [k] [k (LongAdder.)])) counter-names))

(defn bump!
  ([cs k] (bump! cs k 1))
  ([cs k ^long n] (some-> ^LongAdder (get cs k) (.add n))))

(defn read-counters [cs]
  (into {} (map (fn [[k ^LongAdder a]] [k (.sum a)])) cs))
