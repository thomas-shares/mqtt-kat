#!/usr/bin/env bb
;; What a stalled broker's threads were doing, from the dump the stats loop
;; writes when a broker stops writing (mqttkat.util/dump-threads!):
;;
;;   bb scripts/threads.bb logs/brokers/broker-2-threads-111850.txt
;;
;; Counts the threads by the first frame of the broker's own code they are
;; in, so a thousand connection threads parked on one lock read as one line,
;; then prints one whole stack for each of the commonest few.
(require '[clojure.string :as str])

(let [[file n] *command-line-args*
      n        (parse-long (or n "8"))
      blocks   (->> (str/split (slurp file) #"\n\s*\n")
                    (map str/split-lines)
                    (filter #(some-> (first %) str/trim (str/starts-with? "#"))))
      ours     (fn [lines] (or (some #(when (re-find #"mqttkat" %) (str/trim %)) (rest lines))
                               (some-> (second lines) str/trim)
                               "(no frames)"))
      groups   (->> blocks (group-by ours) (sort-by (comp - count val)))]
  (println (count blocks) "threads")
  (doseq [[frame bs] (take n groups)]
    (println (format "%6d  %s" (count bs) frame)))
  (doseq [[frame bs] (take (min n 4) groups)]
    (println)
    (println "==" (count bs) "x" frame)
    (run! println (take 40 (first bs)))))
