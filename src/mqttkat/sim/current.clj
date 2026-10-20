(ns mqttkat.sim.current
  "The current design, as a model for the simulation: brokers that keep
   each session's window and pending queue in memory, and Rama as a store
   on the side that they write to and read back late. At the default
   settings: exactly-once topics claimed (MQTTKAT_EXACTLY_ONCE_TOPICS),
   QoS 2 held on the cluster (hold-qos-2?), forward-ack off,
   wait-on-cluster off.

   Each piece names the function in mqttkat.handlers it stands for. What is
   modelled:

   - a publisher's QoS 1 PUBLISH, fanned out at once, PUBACK once the
     writes it made have landed (publish-keyed, hand-off!)
   - a publisher's QoS 2: held on Rama on the PUBLISH (qos-2-accept), a DUP
     the broker never had read back from there, fanned out on the PUBREL,
     unheld, then PUBCOMP (pubrel, let-go!); a PUBREL for a message held
     elsewhere read from Rama with retries (release-from-cluster!)
   - routing: live here, connected on another broker per Rama's sessions
     (a copy over the bridge, fire and forget, handed back if that broker
     is down), or away (queued on Rama under the message key)
   - a session's window and pending queue (deliver-now-or-queue!), with
     the live deliveries of a recent connection reconciled (keyed,
     settled!)
   - exactly once: the claim before the send, the release before the
     PUBREL, the dequeue on the PUBCOMP (send-claimed!, claimed!, pubrec,
     pubcomp); with no window slot, overflow to Rama (overflow!)
   - the hand-over when a connection ends (hand-over-unacknowledged!),
     then :disconnected
   - adopting a session on CONNECT: the takeover, waiting for the other
     broker's hand-over (once-gone), the identifier gate when it never
     comes (gate-identifiers!), restoring the queue (restore-queued!)
   - catch-up reads, one at a time, with `had` and the take-offs that
     landed (catch-up!, read-again!, deliver-queued!, end-read!), nudges
     from Rama (nudged!), resend under a handed-over identifier
     (resend-handed-over!)

   With {:model {:forward-ack? mode}}, a forwarded copy is a hand-off: the
   publish waits until the other broker has taken it, or until it has been
   put on the cluster's queue here, having come back or its broker having
   died (forward-ack?, taken-or-handed-back). Taken means, by mode:

     true, :full   once the other broker's own hand-offs for it have
                   landed: in a window there, claimed, or on the queue
     :receipt      once it has reached the other broker, whatever that
                   then does with it: lost with it if it dies
     :eo           :full for a QoS 2 copy on an exactly-once topic, none
                   for the rest (fire and forget, as with it off)
     :eo+receipt   :full for those, :receipt for the rest

   And, with {:model {:leases? true}}, what the code does not do yet:
   identifier leases. A session's identifiers come in blocks of
   :lease-block, each recorded on Rama before it is used; a broker that
   resumes the session without its hand-over sends a PUBREL for every
   identifier of the recorded blocks the queue does not have in flight,
   so that none the client holds is orphaned (thoughts.md, 20261020).

   What is not: QoS 0, clean sessions, retained messages, shared
   subscriptions, will messages, expiry, back-pressure on publishers,
   limits on Rama's queues, the origin holds of forwarded copies, writes
   to Rama that fail or time out (they only lag), views lagging beyond
   Rama's own processing, dequeue batching."
  (:require [mqttkat.chaos.check :refer [matches?]]
            [mqttkat.sim.core :as sim]
            [mqttkat.sim.rama :as rama]))

(def defaults
  {:window             8                ; inflight-window, small to fill
   :pending-limit      1000
   :pause-threshold    100
   :resume-threshold   10
   :eo-filters         []
   :wait-on-cluster?   false
   :hand-over-wait-ms  10000
   :gone-ms            3000             ; bridge/peer-gone?
   :gate-ms            60000
   :catch-up-reads     [2000 5000 10000 20000 40000 60000]
   :catch-up-again-ms  2000
   :reconcile-ms       10000
   :redequeue-ms       5000
   :held-read-ms       [0 250 1000 3000]
   ;; Identifier leases (thoughts.md, 20261020): off, as the code is today.
   :leases?            false
   ;; A copy forwarded to another broker as a hand-off (forward-ack?):
   ;; off, as the code's default. See the namespace for the modes.
   :forward-ack?       false
   :forward-wait-ms    30000
   :lease-block        64})

(defn- cfg [w k] (get-in w [:config :model k] (get defaults k)))

;; ---------------------------------------------------------------- Rama

(defn- queue-verdict
  "mqttkat.rama.module/queue-verdict, without the limit."
  [taken had message]
  (cond
    taken                                          :taken-off
    (and (:pid had)
         (or (nil? (:pid message))
             (and (:released? had) (not (:released? message))))) :kept-in-flight
    :else                                          :put))

(defn- claim-verdict
  "mqttkat.rama.module/claim-verdict."
  [taken had message]
  (let [id (:pid had)]
    (if (or taken
            (and id (or (not= id (:pid message))
                        (and (:released? had) (not (:released? message))))))
      :refused
      :put)))

(defn- nudge [w c]
  (let [{:keys [broker connected?]} (get-in (rama/pstate w) [:sessions c])]
    (if (and connected? broker)
      (sim/after w (sim/rand-long w 1 5) {:to broker :type :nudge :client c
                                          :epoch (sim/epoch w broker)})
      w)))

(defn apply-op
  "What the topologies do with an append."
  [w [kind c & args]]
  (let [ps    (rama/pstate w)
        count (fn [w k] (update-in w [:ledger :rama k] (fnil inc 0)))]
    (case kind
      :connected
      (let [[b cid ep] args]
        (rama/update-pstate w assoc-in [:sessions c] {:broker b :connect-id cid :connected? true
                                                      :epoch ep}))

      :lease
      (let [[b k blocks] args]
        (rama/update-pstate w assoc-in [:leases c] {:broker b :cur k :blocks blocks}))

      :disconnected
      (let [[_ cid] args]
        (if (= cid (get-in ps [:sessions c :connect-id]))
          (rama/update-pstate w assoc-in [:sessions c :connected?] false)
          w))

      (:enqueue :claim)
      (let [[k e] args
            taken (get-in ps [:taken-off c k])
            had   (get-in ps [:queued c k])
            v     (if (= :enqueue kind) (queue-verdict taken had e) (claim-verdict taken had e))
            w     (count w [kind v])]
        (if (= :put v)
          (cond-> (rama/update-pstate w update-in [:queued c] (fnil assoc (sorted-map)) k e)
            (= :enqueue kind) (nudge c))
          w))

      :dequeue
      (let [[ks] args]
        (rama/update-pstate w (fn [ps]
                                (reduce (fn [ps k]
                                          (-> ps
                                              (update-in [:queued c] dissoc k)
                                              (assoc-in [:taken-off c k] true)))
                                        ps ks))))

      :hold   (let [[pid m] args] (rama/update-pstate w assoc-in [:held [c pid]] m))
      :unhold (let [[pid] args] (rama/update-pstate w update :held dissoc [c pid])))))

;; ---------------------------------------------------------------- a broker

(defn broker-state [id]
  {:kind :broker :id id
   :conns {}          ; conn -> {:client :at :acked?}
   :live {}           ; client -> conn
   :adopting {}       ; conn -> what the adopt waits on
   :inbound {}        ; [publisher pid] -> {:m :held? :releasing?}
   :out {}            ; subscriber -> {:inflight {pid e} :pending [e] :next-id :gate}
   :catching-up {}    ; subscriber -> {:connect-id :had {k landed} :reading? ...}
   :barriers {}       ; tok -> {:n :then}
   :copies {}         ; forwarded copies not yet taken -> {:client :m :tok :peer}
   :overflowed #{}})

(defn- bs [w id] (sim/node w id))
(defn- bu [w id f & args] (apply sim/update-node w id f args))
(defn- live-conn [w id c] (get-in (bs w id) [:live c]))
(defn- acked? [w id conn] (boolean (get-in (bs w id) [:conns conn :acked?])))

(defn- to-client [w id c pkt]
  (if-let [conn (live-conn w id c)] (sim/send-on w conn id pkt) w))

(defn- out [w id c]
  (get-in (bs w id) [:out c] {:inflight (sorted-map) :pending [] :next-id 0}))
(defn- set-out [w id c o] (bu w id assoc-in [:out c] o))

(defn- eo-topic? [w topic] (boolean (some #(matches? % topic) (cfg w :eo-filters))))

(defn- eo-key
  "exactly-once-key: every session here is kept."
  [w e]
  (when (and (= 2 (:qos (:m e))) (eo-topic? w (:topic (:m e))))
    (or (:cluster-key e) (:key e))))

(defn- t [w e id & text] (apply sim/trace w (:id (:m e)) id text))

(declare continue)

;; Hand-offs: a set of writes something waits on, as once-handed-off!.
(defn- barrier [w id then]
  (let [[w tok] (sim/fresh w "bar")]
    [(bu w id assoc-in [:barriers tok] {:n 1 :then then}) tok]))

(defn- hold-barrier [w id tok]
  (if tok (bu w id update-in [:barriers tok :n] inc) w))

(defn- release-barrier [w id tok]
  (if-let [{:keys [n then]} (get-in (bs w id) [:barriers tok])]
    (if (= 1 n)
      (-> (bu w id update :barriers dissoc tok) (continue id then))
      (bu w id update-in [:barriers tok :n] dec))
    w))

(defn- write
  "Append `op`; `then` once it is answered at `level`, and `tok`'s barrier
   waits for it."
  [w id op level then tok]
  (let [depot (if (#{:connected :disconnected :hold :unhold} (first op)) :sessions :queues)]
    (-> (hold-barrier w id tok)
        (rama/append id depot op level (when (and level (or then tok))
                                         {:type :landed :then then :barrier tok})))))

;; ---------------------------------------------------------------- catching up

(defn- had-map [had & ks] (reduce (fn [h k] (if (contains? h k) h (assoc h k nil))) (or had {}) ks))

(defn- note-had [w id c k]
  (if (get-in (bs w id) [:catching-up c])
    (bu w id update-in [:catching-up c :had] had-map k)
    w))

(defn- landed [w id c k]
  (if (get-in (bs w id) [:catching-up c])
    (bu w id assoc-in [:catching-up c :had k] (sim/now w))
    w))

(defn- read-soon [w id c cid ms]
  (sim/timer w id ms {:type :read :client c :cid cid :gaps nil}))

(defn- dequeue [w id c k landed?]
  (write w id [:dequeue c [k]] :ack (when landed? {:k :taken-off :client c :key k}) nil))

(defn- settled
  "settled!: done with `e`; off the cluster's queue if it came from there,
   and a live delivery reconciled taken off it too, now and again later."
  [w id c e]
  (let [k (:cluster-key e)]
    (cond-> w
      k (dequeue id c k (not (:reconcile? e)))
      (and (:reconcile? e) (:key e) (not= (:key e) k))
      (dequeue id c (:key e) false)
      (and (:reconcile? e) (:key e))
      (sim/timer id (cfg w :redequeue-ms) {:type :redequeue :client c :key (:key e)}))))

;; ---------------------------------------------------------------- sending

(defn- next-identifier [{:keys [next-id inflight]}]
  (loop [p (inc (mod (or next-id 0) 65535))]
    (if (contains? inflight p) (recur (inc (mod p 65535))) p)))

(defn- n-blocks [w] (quot 65535 (long (cfg w :lease-block))))

(defn- block-of [w pid] (quot (dec (long pid)) (long (cfg w :lease-block))))

(defn- pick-pid
  "The identifier the next message for this session goes out under, nil
   for none yet. Without leases, next-identifier. With them, the next one
   free in the block this broker holds the lease on, nil while it holds
   none or the block is used up."
  [w {:keys [next-id inflight lease] :as o}]
  (if-not (cfg w :leases?)
    (next-identifier o)
    (when lease
      (let [b  (long (cfg w :lease-block))
            lo (inc (* (long lease) b))
            hi (* (inc (long lease)) b)]
        (loop [p (max lo (inc (long (or next-id 0))))]
          (cond (> p hi)                    nil
                (contains? inflight p)      (recur (inc p))
                :else                       p))))))

(defn- request-lease
  "Lease block `k` for `c` on the cluster: recorded there, with every block
   this broker may have identifiers out in, before any of it is used. A
   broker that resumes the session without a hand-over releases what is
   recorded (go-live)."
  [w id c k]
  (let [o      (out w id c)
        blocks (into (sorted-set k) (map #(block-of w %)) (keys (:inflight o)))]
    (-> (set-out w id c (assoc o :leasing k))
        (update-in [:ledger :leases] (fnil inc 0))
        (write id [:lease c id k blocks] :ack
               {:k :leased :client c :block k :conn (live-conn w id c)} nil))))

(defn- ensure-lease
  "With leases, a new block once this one is used up, if none is coming."
  [w id c]
  (let [o (out w id c)]
    (if (and (cfg w :leases?) (live-conn w id c) (not (:leasing o)) (nil? (pick-pid w o)))
      (request-lease w id c (mod (inc (long (or (:lease o) (:base-block o) -1))) (n-blocks w)))
      w)))

(defn- send-publish [w id c e pid dup?]
  (-> (to-client w id c {:type :publish :pid pid :m (:m e) :dup? dup?})
      (t e id "sent to" c "pid" pid (if dup? "(dup)" ""))))

(defn- send-claimed
  "send-claimed!: an exactly-once delivery is claimed under `pid` first,
   and sent once the claim has landed and reads back as this one."
  [w id c e pid tok]
  (if-let [k (and (not (:dup? e)) (eo-key w e))]
    (-> (t w e id "claiming" k "pid" pid "for" c)
        (write id [:claim c k {:m (:m e) :pid pid}] :ack
               {:k :claimed :client c :key k :pid pid :eid (:eid e)} tok))
    (send-publish w id c e pid false)))

(defn- here? [w id c pid eid]
  (and (live-conn w id c)
       (= eid (get-in (out w id c) [:inflight pid :eid]))))

(defn- take-pending
  "take-pending!: the head of the pending queue into the window."
  [w id c]
  (let [{:keys [inflight pending gate] :as o} (out w id c)
        room? (and (seq pending) (< (count inflight) (long (cfg w :window))) (not gate)
                   (acked? w id (live-conn w id c)))
        pid   (when room? (pick-pid w o))]
    (cond
      pid   (let [e (first pending)]
              [(set-out w id c (assoc o :next-id pid :pending (subvec pending 1)
                                      :inflight (assoc inflight pid e)))
               pid e])
      room? [(ensure-lease w id c) nil nil]
      :else [w nil nil])))

(defn- drain
  "drain-pending!: one from pending; a session that overflowed, caught up,
   has its queue read."
  [w id c]
  (let [[w pid e] (take-pending w id c)
        w (if pid (send-claimed w id c e pid nil) w)]
    (if (and (<= (count (:pending (out w id c))) (long (cfg w :resume-threshold)))
             (contains? (:overflowed (bs w id)) c))
      (-> (bu w id update :overflowed disj c)
          (read-soon id c (live-conn w id c) 0))
      w)))

(defn- flush-pending [w id c]
  (loop [w w]
    (let [[w pid e] (take-pending w id c)]
      (if pid (recur (send-claimed w id c e pid nil)) w))))

(defn- enqueue [w id c k e tok why]
  (-> (t w e id why "for" c "as" k)
      (write id [:enqueue c k e] :ack {:k :none} tok)))

(defn- overflow? [w id c e]
  (boolean (and (:key e)
                (or (cfg w :wait-on-cluster?)
                    (eo-key w e)
                    (and (:via-bridge? e)
                         (>= (count (:pending (out w id c))) (long (cfg w :pause-threshold))))))))

(defn- place
  "deliver-now-or-queue!: into the window and sent, or waiting here, or to
   the cluster's queue."
  [w id c e tok]
  (let [conn (live-conn w id c)
        {:keys [inflight pending gate] :as o} (out w id c)
        pid  (when (and (acked? w id conn) (< (count inflight) (long (cfg w :window)))
                        (empty? pending) (not gate))
               (pick-pid w o))]
    (cond
      pid
      (let [w   (set-out w id c (assoc o :next-id pid :inflight (assoc inflight pid e)))
            w   (if (:reconcile? e) (note-had w id c (:key e)) w)]
        (send-claimed w id c e pid tok))

      (and (acked? w id conn) (overflow? w id c e))
      (-> (bu w id update :overflowed conj c)
          (enqueue id c (:key e) {:m (:m e)} tok "overflowed")
          (ensure-lease id c))

      :else
      (-> (set-out w id c (update o :pending conj e))
          (t e id "pending for" c)
          (ensure-lease id c)))))

(defn- holds? [o k]
  (some #(or (= k (:key %)) (= k (:cluster-key %)))
        (concat (:pending o) (vals (:inflight o)))))

(defn- deliver-local
  "A live delivery (keyed, deliver-or-queue!)."
  [w id c m tok via-bridge?]
  (let [cu        (get-in (bs w id) [:catching-up c])
        k         (:key m)
        now       (sim/now w)
        conn      (live-conn w id c)
        catching? (and cu (< now (long (or (:reconcile-until cu) 0))))
        recent?   (or catching?
                      (< (- now (long (get-in (bs w id) [:conns conn :at] 0))) (long (cfg w :reconcile-ms))))]
    (cond
      (contains? (:had cu) k)
      (sim/trace w (:id m) id "not sent to" c ": had")

      (and recent? (not catching?) (holds? (out w id c) k))
      (sim/trace w (:id m) id "not sent to" c ": here already")

      :else
      (let [[w eid] (sim/fresh w "e")]
        (place w id c {:m m :key k :eid eid :reconcile? recent? :via-bridge? via-bridge?} tok)))))

(defn- copy-ack
  "What a copy of `m` forwarded to another broker waits for, by the mode
   of forward-ack?: :full, :receipt, or nil for nothing."
  [w m]
  (let [eo? (and (= 2 (:qos m)) (eo-topic? w (:topic m)))]
    (case (cfg w :forward-ack?)
      (true :full) :full
      :receipt     :receipt
      :eo          (when eo? :full)
      :eo+receipt  (if eo? :full :receipt)
      nil)))

(defn- fan-out
  "route, subscribers-for, queue-for-offline-sessions!, forward-to-brokers!:
   each subscriber live here, connected elsewhere, or away."
  [w id m tok]
  (let [ps (rama/pstate w)]
    (reduce (fn [w [s filters]]
              (if-not (some #(matches? % (:topic m)) filters)
                w
                (let [{:keys [broker connected?]} (get-in ps [:sessions s])]
                  (cond
                    (live-conn w id s)
                    (deliver-local w id s m tok false)

                    (and connected? broker (not= id broker))
                    (if-let [mode (when tok (copy-ack w m))]
                      (let [[w cid] (sim/fresh w "copy")]
                        (-> (hold-barrier w id tok)
                            (bu id assoc-in [:copies cid] {:client s :m m :tok tok :peer broker})
                            (sim/send-link id broker {:type :copy :client s :m m :copy cid :ack mode})
                            (sim/timer id (cfg w :forward-wait-ms) {:type :copy-wait :copy cid})
                            (sim/trace (:id m) id "forwarded to" broker "for" s "as a hand-off," (name mode))))
                      (-> (sim/send-link w id broker {:type :copy :client s :m m})
                          (sim/trace (:id m) id "forwarded to" broker "for" s)))

                    :else
                    (enqueue w id s (:key m) {:m m} tok "queued, away,")))))
            w
            (sort-by key (:subs ps)))))

;; ---------------------------------------------------------------- resuming

(defn- resend-handed-over
  "resend-handed-over!: in flight here under the identifier it was handed
   over with, and sent; [w false] when that is taken or the window full."
  [w id c k {:keys [pid released? m]}]
  (let [{:keys [inflight] :as o} (out w id c)]
    (if (or (contains? inflight pid) (>= (count inflight) (long (cfg w :window))))
      [w false]
      (let [[w eid] (sim/fresh w "e")
            e (if released?
                {:m m :released? true :release-key k :eid eid}
                {:m m :key (:key m) :cluster-key k :eid eid})
            w (set-out w id c (assoc-in o [:inflight pid] e))]
        [(if released?
           (-> (to-client w id c {:type :pubrel :pid pid}) (t e id "PUBREL again to" c "pid" pid))
           (send-publish w id c e pid true))
         true]))))

(defn- deliver-queued
  "deliver-queued!: what the cluster's queue had for `c`, sent or put in
   pending here, leaving out what is had or here. Returns [w took]."
  [w id c cid queued]
  (let [conn (live-conn w id c)]
    (if-not (and conn (= cid conn) (acked? w id conn))
      [w #{}]
      (let [o     (out w id c)
            here  (into (set (keys (get-in (bs w id) [:catching-up c :had])))
                        (mapcat (juxt :cluster-key :key :release-key))
                        (concat (:pending o) (vals (:inflight o))))
            [w took]
            (reduce (fn [[w took] [k e]]
                      (cond
                        (contains? here k) [w took]

                        (:pid e)
                        (let [[w ok?] (resend-handed-over w id c k e)]
                          [w (if ok? (conj took k) took)])

                        (< (count (:pending (out w id c))) (quot (long (cfg w :pending-limit)) 2))
                        (let [[w eid] (sim/fresh w "e")
                              e2 {:m (:m e) :key (:key (:m e)) :cluster-key k :eid eid}]
                          [(-> (set-out w id c (update (out w id c) :pending conj e2))
                               (t e2 id "taken from the queue for" c))
                           (conj took k)])

                        :else [w took]))
                    [w #{}]
                    queued)
            w (if (seq took)
                (bu w id (fn [b] (if (= cid (get-in b [:catching-up c :connect-id]))
                                   (update-in b [:catching-up c :had] #(apply had-map % took))
                                   b)))
                w)]
        [(flush-pending w id c) took]))))

(defn- begin-read [w id c cid]
  (let [{:keys [connect-id reading?]} (get-in (bs w id) [:catching-up c])]
    (cond
      (not= cid connect-id) [w false]
      reading?              [(bu w id update-in [:catching-up c] assoc :again? true :scheduled? false) false]
      :else                 [(bu w id update-in [:catching-up c] assoc :reading? true :again? false
                                 :scheduled? false)
                             true])))

(defn- read-again
  "read-again!: the queue read now, acted on when the read comes back."
  [w id c cid gaps]
  (let [[w began?] (begin-read w id c cid)]
    (cond
      began?
      (rama/query w id #(get-in % [:queued c])
                  {:type :read-result :client c :cid cid :began (sim/now w) :gaps gaps})
      ;; catch-up-after!: the next of the reads goes on whether or not
      ;; this one could begin.
      (and (seq (rest gaps)) (= cid (get-in (bs w id) [:catching-up c :connect-id])))
      (sim/timer w id (second gaps) {:type :read :client c :cid cid :gaps (rest gaps)})
      :else w)))

(defn- read-result [w id {:keys [client cid began gaps result]}]
  (let [c       client
        had     (get-in (bs w id) [:catching-up c :had])
        fresh   (remove #(contains? had (key %)) result)
        found   (set (keys result))
        [w took] (deliver-queued w id c cid fresh)
        left?   (< (count took) (count fresh))
        w (if (live-conn w id c)
            (let [o (out w id c)]
              (if (= :landed (:gate o))
                (flush-pending (set-out w id c (dissoc o :gate)) id c)
                w))
            w)
        ;; end-read!
        again?  (and (= cid (get-in (bs w id) [:catching-up c :connect-id]))
                     (get-in (bs w id) [:catching-up c :again?]))
        w (bu w id (fn [b]
                     (if (= cid (get-in b [:catching-up c :connect-id]))
                       (update-in b [:catching-up c]
                                  #(-> %
                                       (assoc :reading? false :again? false)
                                       (update :had (fn [h]
                                                      (into {} (remove (fn [[k l]]
                                                                         (and l (< (long l) (long began))
                                                                              (not (contains? found k)))))
                                                            h)))
                                       (update :had (fn [h] (apply had-map h took)))))
                       b)))
        w (if again? (read-soon w id c cid 0) w)]
    (cond
      (seq (rest gaps))  (sim/timer w id (second gaps) {:type :read :client c :cid cid :gaps (rest gaps)})
      left?              (read-soon w id c cid (cfg w :catch-up-again-ms))
      :else              w)))

(defn- restore
  "restore-queued!: what the queue had on the resume, in flight under its
   identifier when that is free here, else waiting. The keys restored."
  [w id c queued]
  (reduce (fn [[w ks] [k {:keys [pid released? m]}]]
            (let [o (out w id c)
                  [w eid] (sim/fresh w "e")]
              (cond
                (and pid (not (contains? (:inflight o) pid)))
                [(set-out w id c (assoc-in o [:inflight pid]
                                           (if released?
                                             {:m m :released? true :release-key k :eid eid}
                                             {:m m :key (:key m) :cluster-key k :eid eid})))
                 (conj ks k)]

                released? [w ks]

                :else
                [(set-out w id c (update o :pending conj {:m m :key (:key m) :cluster-key k :eid eid}))
                 (conj ks k)])))
          [w []]
          queued))

(defn- go-live
  "The session adopted: restored, live, CONNACK, what was in flight sent
   again (redeliver-inflight!), pending flushed, catch-up reads begun."
  [w id conn c {:keys [queued lease]} waited-out? orphaned?]
  (let [[w restored] (restore w id c queued)
        now (sim/now w)
        w (-> w
              (bu id assoc-in [:catching-up c]
                  {:connect-id conn :had (apply had-map {} restored)
                   :reconcile-until (+ now (long (last (cfg w :catch-up-reads))))})
              (bu id assoc-in [:live c] conn)
              (bu id assoc-in [:conns conn :acked?] true)
              (bu id update :adopting dissoc conn)
              (write id [:connected c id conn (sim/epoch w id)] :append-ack nil nil)
              (sim/send-on conn id {:type :connack}))
        ;; Leases: what the broker before had out and never handed over
        ;; released, every identifier of its blocks the queue does not
        ;; have in flight; a client that holds none answers with a
        ;; PUBCOMP (§4.3.3). Then a block of this broker's own.
        w (if (cfg w :leases?)
            (let [b      (long (cfg w :lease-block))
                  queued-pids (into #{} (keep (comp :pid val)) queued)
                  stray  (when orphaned?
                           (for [k (:blocks lease)
                                 p (range (inc (* (long k) b)) (inc (* (inc (long k)) b)))
                                 :when (not (contains? queued-pids p))]
                             p))
                  w      (reduce #(sim/send-on %1 conn id {:type :pubrel :pid %2}) w stray)]
              (-> (update-in w [:ledger :stray-pubrels] (fnil + 0) (count stray))
                  (set-out id c (-> (out w id c) (assoc :base-block (:cur lease))
                                    (dissoc :lease :leasing)))
                  (request-lease id c (mod (inc (long (or (:cur lease) -1))) (n-blocks w)))))
            w)
        w (if waited-out?
            (-> (set-out w id c (assoc (out w id c) :gate :closed))
                (sim/timer id 250 {:type :gate-poll :client c :conn conn
                                   :deadline (+ now (long (cfg w :gate-ms)))}))
            ;; adopt-session!: a gate a connection before closed here
            ;; does not outlast it.
            (set-out w id c (dissoc (out w id c) :gate)))
        w (reduce (fn [w [pid e]]
                    (if (:released? e)
                      (-> (to-client w id c {:type :pubrel :pid pid}) (t e id "PUBREL again to" c pid))
                      (send-publish w id c e pid true)))
                  w
                  (:inflight (out w id c)))
        w (flush-pending w id c)
        gaps (map - (cfg w :catch-up-reads) (cons 0 (cfg w :catch-up-reads)))]
    (sim/timer w id (first gaps) {:type :read :client c :cid conn :gaps gaps})))

(defn- peer-gone? [w x]
  (and (not (sim/up? w x))
       (>= (- (sim/now w) (long (get-in w [:down-since x] 0))) (long (cfg w :gone-ms)))))

(defn- adopt
  "adopt-session!, once-gone: wait for the broker that has the session to
   hand it over, then resume from the cluster's queue."
  [w id conn c session]
  (let [{:keys [broker connected? connect-id]} session
        {:keys [deadline old]} (get-in (bs w id) [:adopting conn])]
    (cond
      (not (and (sim/open? w conn) (get-in (bs w id) [:conns conn]))) w

      (and connected? broker (not= broker id)
           (or (nil? old) (= connect-id old))
           (not (peer-gone? w broker))
           (or (nil? deadline) (< (sim/now w) (long deadline))))
      (let [w (if old
                w
                (-> (bu w id assoc-in [:adopting conn]
                        {:old connect-id :deadline (+ (sim/now w) (long (cfg w :hand-over-wait-ms)))})
                    (sim/send-link id broker {:type :takeover :client c :connect-id connect-id})))]
        (sim/timer w id 100 {:type :adopt-poll :conn conn :client c}))

      :else
      (let [waited-out? (boolean (and deadline (>= (sim/now w) (long deadline))))
            ;; Resumed without the hand-over of whoever had it: the broker
            ;; is gone or another incarnation, or did not hand over in time.
            orphaned?   (boolean (and connected? broker
                                      (or waited-out?
                                          (not (sim/up? w broker))
                                          (not= (:epoch session) (sim/epoch w broker)))))]
        (rama/query w id #(hash-map :queued (get-in % [:queued c]) :lease (get-in % [:leases c]))
                    {:type :resume :conn conn :client c :waited-out? waited-out?
                     :orphaned? orphaned?})))))

;; ---------------------------------------------------------------- leaving

(defn- hand-over
  "hand-over-unacknowledged!, then :disconnected once it has landed."
  [w id c cid]
  (let [{:keys [inflight pending] :as o} (out w id c)
        w (set-out w id c (assoc o :inflight (sorted-map) :pending []))
        [w tok] (barrier w id {:k :handed :client c :cid cid})
        w (reduce (fn [w [pid e]]
                    (if (:released? e)
                      (enqueue w id c (or (:release-key e) (str (or (:key e) (:cluster-key e)) "-rel"))
                               {:m (:m e) :pid pid :released? true} tok "PUBREL handed over")
                      (enqueue w id c (or (:key e) (:cluster-key e)) {:m (:m e) :pid pid}
                               tok "handed over in flight")))
                  w inflight)
        w (reduce (fn [w e]
                    (if (:cluster-key e)
                      (t w e id "left on the queue at the hand-over")
                      (enqueue w id c (:key e) {:m (:m e)} tok "handed over waiting")))
                  w pending)]
    (release-barrier w id tok)))

(defn- remove-client
  "remove-client!: a connection here has gone."
  [w id conn]
  (let [{:keys [client]} (get-in (bs w id) [:conns conn])
        w (bu w id update :conns dissoc conn)]
    (if (and client (= conn (live-conn w id client)))
      (-> (bu w id update :live dissoc client)
          (bu id (fn [b] (if (= conn (get-in b [:catching-up client :connect-id]))
                           (update b :catching-up dissoc client)
                           b)))
          (hand-over id client conn))
      w)))

;; ---------------------------------------------------------------- publishers

(defn- new-key [w]
  (let [[w n] (sim/fresh w "")]
    [w (format "%010d-%s" (sim/now w) n)]))

(defn- publish-released
  "publish-released!, then let-go!: unhold, then PUBCOMP."
  [w id m c pid conn]
  (let [[w tok] (barrier w id {:k :let-go :client c :pid pid :conn conn})]
    (-> (sim/trace w (:id m) id "published on the PUBREL")
        (fan-out id m tok)
        (release-barrier id tok))))

(defn- pubcomp-to [w id c pid conn]
  (let [k (or (live-conn w id c) conn)]
    (-> (bu w id update :inbound dissoc [c pid])
        (sim/send-on k id {:type :pubcomp :pid pid}))))

(defn- accept-qos-2
  "qos-2-accept: held on the cluster, PUBREC once the depot has it; or the
   one held already, from the broker that had it first."
  [w id conn c pid m taken]
  (if (get-in (bs w id) [:inbound [c pid]])
    (sim/send-on w conn id {:type :pubrec :pid pid})
    (let [msg (or taken m)
          w   (bu w id assoc-in [:inbound [c pid]] {:m msg :held? true})]
      (if taken
        (-> (sim/trace w (:id msg) id "taken from the cluster's hold as" (:key msg))
            (sim/send-on conn id {:type :pubrec :pid pid}))
        (-> (sim/trace w (:id msg) id "held as" (:key msg))
            (write id [:hold c pid msg] :append-ack {:k :pubrec :conn conn :pid pid} nil))))))

(defn- publisher-packet [w id conn c {:keys [type pid m dup?]}]
  (case type
    :publish
    (let [[w k] (new-key w)
          bm (assoc m :key k)]
      (if (= 1 (:qos m))
        (let [[w tok] (barrier w id {:k :puback :conn conn :pid pid})]
          (-> (sim/trace w (:id m) id "received as" k)
              (fan-out id bm tok)
              (release-barrier id tok)))
        (cond
          (get-in (bs w id) [:inbound [c pid]]) (sim/send-on w conn id {:type :pubrec :pid pid})
          dup? (rama/query w id #(get-in % [:held [c pid]])
                           {:type :held-for-dup :conn conn :client c :pid pid :m bm})
          :else (accept-qos-2 w id conn c pid bm nil))))

    :pubrel
    (let [local (get-in (bs w id) [:inbound [c pid]])]
      (cond
        (:releasing? local) (sim/send-on w conn id {:type :pubcomp :pid pid})
        local (-> (bu w id assoc-in [:inbound [c pid] :releasing?] true)
                  (publish-released id (:m local) c pid conn))
        :else (-> (bu w id assoc-in [:inbound [c pid]] {:releasing? true})
                  (sim/timer id 0 {:type :held-read :conn conn :client c :pid pid
                                   :at (cfg w :held-read-ms) :t0 (sim/now w)}))))
    w))

;; ---------------------------------------------------------------- subscribers

(defn- subscriber-packet [w id conn c {:keys [type pid]}]
  (let [o (out w id c)
        e (get-in o [:inflight pid])]
    (case type
      :puback
      (if e
        (-> (set-out w id c (update o :inflight dissoc pid)) (t e id "PUBACK from" c)
            (settled id c e) (drain id c))
        w)

      :pubrec
      (let [k (when e (eo-key w e))
            w (if (and e (not (:released? e)))
                (set-out w id c (update-in o [:inflight pid]
                                           #(cond-> (assoc % :released? true)
                                              k (assoc :release-key k :settle? true))))
                w)
            w (if (and e (not k) (not (:settle? e))) (settled w id c e) w)]
        (if k
          (-> (t w e id "releasing" k "pid" pid)
              (write id [:claim c k {:m (:m e) :pid pid :released? true}] :ack
                     {:k :released :client c :pid pid :conn conn} nil))
          (sim/send-on w conn id {:type :pubrel :pid pid})))

      :pubcomp
      (if e
        (let [k (:release-key e)
              w (-> (set-out w id c (update o :inflight dissoc pid)) (t e id "PUBCOMP from" c))
              w (if k
                  (-> (note-had w id c k)
                      (dequeue id c k true))
                  w)]
          (drain w id c))
        w)
      w)))

;; ---------------------------------------------------------------- continuations

(defn- hand-back
  "A forwarded copy done with: taken by the other broker (`why` nil), or
   not, and put on the cluster's queue here. Either way the publish it is
   part of stops waiting for it once that has landed."
  [w id cid why]
  (if-let [{:keys [client m tok]} (get-in (bs w id) [:copies cid])]
    (-> (bu w id update :copies dissoc cid)
        (cond-> why (enqueue id client (:key m) {:m m} tok why))
        (release-barrier id tok))
    w))

(defn- continue [w id {:keys [k] :as then}]
  (case k
    nil       w
    :none     w
    :puback   (sim/send-on w (:conn then) id {:type :puback :pid (:pid then)})
    :pubrec   (sim/send-on w (:conn then) id {:type :pubrec :pid (:pid then)})
    :let-go   (write w id [:unhold (:client then) (:pid then)] :append-ack
                     (assoc then :k :pubcomp) nil)
    :pubcomp  (pubcomp-to w id (:client then) (:pid then) (:conn then))
    :taken-off (landed w id (:client then) (:key then))
    :handed   (write w id [:disconnected (:client then) id (:cid then)] :append-ack nil nil)
    :claimed  (let [{c :client ck :key :keys [pid eid]} then]
                (if (here? w id c pid eid)
                  (rama/query w id #(get-in % [:queued c ck])
                              {:type :claim-read :client c :key ck :pid pid :eid eid})
                  w))
    :copy-taken (sim/send-link w id (:origin then) {:type :taken :copy (:copy then)})
    :leased   (let [{c :client :keys [block conn]} then
                    o (out w id c)]
                (if (and (= conn (live-conn w id c)) (= block (:leasing o)))
                  (-> (set-out w id c (-> o (assoc :lease block :next-id (* (long block) (long (cfg w :lease-block))))
                                          (dissoc :leasing)))
                      (flush-pending id c))
                  w))
    :released (let [{c :client :keys [pid conn]} then]
                (if (= conn (live-conn w id c))
                  (sim/send-on w conn id {:type :pubrel :pid pid})
                  w))))

(defn- claim-read
  "claimed!: the claim landed; what the queue says decides."
  [w id {c :client k :key :keys [pid eid result]}]
  (let [entry result
        e     (get-in (out w id c) [:inflight pid])]
    (cond
      (not (here? w id c pid eid))
      w

      (and (= pid (:pid entry)) (not (:released? entry)))
      (send-publish w id c e pid false)

      :else
      (let [w (set-out w id c (update (out w id c) :inflight dissoc pid))]
        (if entry
          (let [[w ok?] (-> (t w e id "claim refused, in flight under" (:pid entry))
                            (resend-handed-over id c k entry))]
            (drain (if ok?
                     w
                     (-> (bu w id update-in [:catching-up c :had] dissoc k)
                         (read-soon id c (live-conn w id c) (cfg w :catch-up-again-ms))))
                   id c))
          (drain (t w e id "claim refused: completed") id c))))))

;; ---------------------------------------------------------------- events

(defn handle [w {:keys [to type conn msg] :as ev}]
  (let [id to]
    (case type
      :packet
      (let [{:keys [client]} (get-in (bs w id) [:conns conn])]
        (if (= :connect (:type msg))
          (let [c   (:client-id msg)
                old (live-conn w id c)
                w   (if old (-> (sim/close w old [10 200] [0 0]) (remove-client id old)) w)
                w   (bu w id assoc-in [:conns conn] {:client c :at (sim/now w) :acked? false})]
            (rama/query w id #(get-in % [:sessions c]) {:type :session :conn conn :client c}))
          (cond
            (nil? client) w
            (= :subscriber (:role (sim/node w client))) (subscriber-packet w id conn client msg)
            :else (publisher-packet w id conn client msg))))

      :session    (adopt w id (:conn ev) (:client ev) (:result ev))
      :adopt-poll (if (get-in (bs w id) [:adopting (:conn ev)])
                    (rama/query w id #(get-in % [:sessions (:client ev)])
                                {:type :session :conn (:conn ev) :client (:client ev)})
                    w)
      :resume     (if (and (sim/open? w (:conn ev)) (get-in (bs w id) [:conns (:conn ev)]))
                    (go-live w id (:conn ev) (:client ev) (:result ev) (:waited-out? ev)
                             (:orphaned? ev))
                    w)
      :gate-poll  (let [{c :client :keys [conn deadline]} ev
                        o (out w id c)]
                    (cond
                      (not= conn (live-conn w id c)) w
                      (not= :closed (:gate o)) w
                      (>= (sim/now w) (long deadline))
                      (flush-pending (set-out w id c (dissoc o :gate)) id c)
                      :else
                      (let [{:keys [broker]} (get-in (rama/pstate w) [:sessions c])]
                        (if (or (= broker id) (peer-gone? w broker))
                          (-> (set-out w id c (assoc o :gate :landed)) (read-soon id c conn 0))
                          (sim/timer w id 250 ev)))))

      :closed     (remove-client w id conn)
      :landed     (-> (continue w id (:then ev)) (release-barrier id (:barrier ev)))
      :claim-read (claim-read w id ev)
      :read       (read-again w id (:client ev) (:cid ev) (:gaps ev))
      :read-result (read-result w id ev)
      :redequeue  (dequeue w id (:client ev) (:key ev) false)
      :nudge      (let [c (:client ev)
                        cu (get-in (bs w id) [:catching-up c])]
                    (if (and (:connect-id cu) (not (:scheduled? cu)))
                      (-> (bu w id assoc-in [:catching-up c :scheduled?] true)
                          (read-soon id c (:connect-id cu) 0))
                      w))

      :held-for-dup
      (accept-qos-2 w id (:conn ev) (:client ev) (:pid ev) (:m ev) (:result ev))

      :held-read
      (let [{c :client :keys [pid conn at t0]} ev]
        (rama/query w id #(get-in % [:held [c pid]])
                    {:type :held-read-result :conn conn :client c :pid pid :at at :t0 t0}))
      :held-read-result
      (let [{c :client :keys [pid conn at t0 result]} ev]
        (cond
          result (publish-released w id result c pid conn)
          (next at) (sim/timer w id (max 0 (- (+ (long t0) (long (second at))) (sim/now w)))
                               (assoc ev :type :held-read :at (next at)))
          :else (pubcomp-to w id c pid conn)))

      :link
      (let [{:keys [type client m connect-id copy ack]} msg]
        (case type
          ;; Taken, with forward-ack?, once what this broker did with it
          ;; has landed: in a window here, claimed, or on the queue; or,
          ;; :receipt, as soon as it is here.
          :copy     (let [w (if (= :receipt ack)
                              (sim/send-link w id (:from ev) {:type :taken :copy copy})
                              w)
                          [w tok] (if (= :full ack)
                                    (barrier w id {:k :copy-taken :origin (:from ev) :copy copy})
                                    [w nil])
                          w (if (live-conn w id client)
                              (deliver-local w id client m tok true)
                              (enqueue w id client (:key m) {:m m} tok "queued, gone from here,"))]
                      (release-barrier w id tok))
          :taken    (hand-back w id copy nil)
          :takeover (let [conn (live-conn w id client)]
                      (if (and conn (= conn connect-id))
                        (-> (sim/close w conn [10 200] [0 0]) (remove-client id conn))
                        w))))

      :undelivered
      (let [{:keys [client m copy]} msg]
        (cond
          copy                 (hand-back w id copy "handed back by the bridge,")
          (= :copy (:type msg)) (enqueue w id client (:key m) {:m m} nil "handed back by the bridge,")
          :else                w))

      ;; taken-or-handed-back: what a broker that died had not taken.
      :peer-down
      (reduce (fn [w [cid {:keys [peer]}]]
                (if (= peer (:peer ev)) (hand-back w id cid "handed back, its broker gone,") w))
              w (sort-by key (:copies (bs w id))))
      :copy-wait (hand-back w id (:copy ev) "handed back after waiting,"))))

(defn on-kill
  "What a broker had only in memory when it died: noted, so that a loss
   can be told from a crash window (doc/guarantees.md, R1)."
  [w id]
  (let [b (bs w id)
        w (reduce-kv (fn [w c {:keys [inflight pending]}]
                       (reduce (fn [w e]
                                 (-> (sim/note w :crash-held {:msg (:id (:m e)) :client c :broker id
                                                              :at (sim/now w)})
                                     (t e id "in memory when" id "died")))
                               w (concat (vals inflight) pending)))
                     w (:out b))]
    (reduce-kv (fn [w _ {:keys [m]}]
                 (if m
                   (-> (sim/note w :crash-held {:msg (:id m) :client nil :broker id :at (sim/now w)})
                       (sim/trace (:id m) id "held in memory when" id "died"))
                   w))
               w (:inbound b))))

(def model
  {:name         :current
   :broker-state broker-state
   :broker-handle handle
   :apply-op     apply-op
   :on-kill      on-kill
   :queued       (fn [w c] (get-in (rama/pstate w) [:queued c]))})
