(ns mqttkat.sim.clients
  "MQTT clients for the simulation, the same whatever broker design is
   modelled. All keep their sessions (doc/guarantees.md: kept sessions are
   what is promised anything across a failure).

   A publisher sends QoS 1 and 2 messages at a steady rate, a few in
   flight at once, and on every reconnect sends again what is unfinished,
   in identifier order (§4.4): the PUBLISH, with DUP, before its PUBREC;
   the PUBREL after it. A message is accepted, and owed to the
   subscribers, once its PUBACK or PUBREC arrives.

   A subscriber hands each message to its application on arrival, QoS 2
   once per identifier: a PUBLISH under an identifier it holds unreleased
   is taken for the same message again, whatever it carries. When it
   carries another message, that one is lost, and the ledger says why
   (:identifier-reused). Each answer goes back after a processing time, in
   order, and some subscribers are slow.

   The packets, as maps:
     {:type :connect :client-id}  {:type :connack}
     {:type :publish :pid :m {:id :topic :qos ...} :dup?}
     {:type :puback|:pubrec|:pubrel|:pubcomp :pid}"
  (:require [mqttkat.sim.core :as sim]))

(defn publisher [id {:keys [topics qos rate-ms max-inflight stop-at]}]
  {:kind :client :role :publisher :id id
   :topics topics :qos qos :rate-ms rate-ms :max-inflight max-inflight :stop-at stop-at
   :conn nil :connected? false :n 0 :next-pid 0 :inflight (sorted-map)})

(defn subscriber [id {:keys [filters reply-ms]}]
  {:kind :client :role :subscriber :id id :filters filters :reply-ms reply-ms
   :conn nil :connected? false :awaiting-rel {} :busy 0})

(defn- send! [w id msg]
  (let [c (:conn (sim/node w id))]
    (if c (sim/send-on w c id msg) w)))

(defn- reconnect-later [w id]
  (let [[lo hi] (get-in w [:config :reconnect-ms] [100 1000])]
    (sim/timer w id (sim/rand-long w lo hi) {:type :connect})))

(defn- connect! [w id]
  (let [b (sim/pick w (get-in w [:config :brokers]))]
    (if-not (sim/up? w b)
      (reconnect-later w id)
      (let [[w c] (sim/connect w id b)]
        (-> w
            (sim/update-node id assoc :conn c :connected? false)
            (sim/send-on c id {:type :connect :client-id id})
            (sim/timer id (get-in w [:config :connack-timeout-ms] 15000)
                       {:type :connack-timeout :conn c}))))))

(defn- next-pid [{:keys [next-pid inflight]}]
  (loop [p (inc (mod next-pid 65535))]
    (if (contains? inflight p) (recur (inc (mod p 65535))) p)))

;; ---------------------------------------------------------------- publishers

(defn- resend-unfinished [w id]
  (reduce (fn [w [pid {:keys [state m]}]]
            (if (= :recd state)
              (send! w id {:type :pubrel :pid pid})
              (-> (send! w id {:type :publish :pid pid :m m :dup? true})
                  (sim/trace (:id m) id "sent again, pid" pid))))
          w
          (:inflight (sim/node w id))))

(defn- publish-one [w id]
  (let [{:keys [topics qos n] :as p} (sim/node w id)
        pid   (next-pid p)
        m     {:id (str id ":" n) :topic (sim/pick w topics) :qos (sim/pick w qos)}]
    (-> w
        (sim/update-node id #(-> % (update :n inc) (assoc :next-pid pid)
                                 (assoc-in [:inflight pid] {:m m :state :sent})))
        (assoc-in [:ledger :published (:id m)] (assoc m :publisher id :sent (sim/now w)))
        (sim/trace (:id m) id "published, pid" pid)
        (send! id {:type :publish :pid pid :m m :dup? false}))))

(defn- accepted [w id m]
  (if (get-in w [:ledger :published (:id m) :accepted])
    w
    (-> w
        (assoc-in [:ledger :published (:id m) :accepted] (sim/now w))
        (sim/trace (:id m) id "accepted"))))

(defn- publisher-packet [w id {:keys [type pid]}]
  (let [entry (get-in (sim/node w id) [:inflight pid])]
    (case type
      :connack (-> w (sim/update-node id assoc :connected? true) (resend-unfinished id))
      :puback  (if (and entry (= 1 (:qos (:m entry))))
                 (-> w (accepted id (:m entry)) (sim/update-node id update :inflight dissoc pid))
                 w)
      :pubrec  (if (and entry (= 2 (:qos (:m entry))))
                 (-> w (accepted id (:m entry))
                     (sim/update-node id assoc-in [:inflight pid :state] :recd)
                     (send! id {:type :pubrel :pid pid}))
                 w)
      :pubcomp (if (and entry (= :recd (:state entry)))
                 (-> w (sim/trace (:id (:m entry)) id "completed")
                     (sim/update-node id update :inflight dissoc pid))
                 w)
      w)))

(defn- tick [w id]
  (let [{:keys [connected? inflight max-inflight rate-ms stop-at]} (sim/node w id)]
    (if (>= (sim/now w) (long stop-at))
      w
      (-> (if (and connected? (< (count inflight) (long max-inflight)))
            (publish-one w id)
            w)
          (sim/timer id (sim/rand-long w (quot (long rate-ms) 2) (* 3 (quot (long rate-ms) 2)))
                     {:type :tick})))))

;; ---------------------------------------------------------------- subscribers

(defn- reply-later
  "Answer with `msg` after this subscriber's processing time, in order."
  [w id msg]
  (let [{:keys [busy reply-ms conn]} (sim/node w id)
        at (max (long busy) (+ (sim/now w) (long (apply sim/rand-long w reply-ms))))]
    (-> w
        (sim/update-node id assoc :busy at)
        (sim/schedule at {:to id :type :send :conn conn :msg msg}))))

(defn- deliver! [w id m pid broker]
  (-> w
      (update-in [:ledger :delivered id (:id m)] (fnil conj []) [(sim/now w) pid broker])
      (sim/trace (:id m) id "delivered, pid" pid "from" broker)))

(defn- subscriber-packet [w id {:keys [type pid m dup?]} broker]
  (case type
    :connack (sim/update-node w id assoc :connected? true)
    :publish (if (= 1 (:qos m))
               (-> w (deliver! id m pid broker) (reply-later id {:type :puback :pid pid}))
               (let [held (get-in (sim/node w id) [:awaiting-rel pid])]
                 (cond
                   (nil? held)
                   (-> w (deliver! id m pid broker)
                       (sim/update-node id assoc-in [:awaiting-rel pid] (:id m))
                       (reply-later id {:type :pubrec :pid pid}))

                   (= held (:id m))
                   (-> w (sim/trace (:id m) id "again, pid" pid (if dup? "(dup)" ""))
                       (reply-later id {:type :pubrec :pid pid}))

                   :else
                   (-> w (sim/note :identifier-reused {:client id :pid pid :held held :got (:id m)
                                                       :at (sim/now w) :broker broker})
                       (sim/trace (:id m) id "taken for" held "which holds pid" pid "- dropped")
                       (reply-later id {:type :pubrec :pid pid})))))
    :pubrel  (-> w (sim/update-node id update :awaiting-rel dissoc pid)
                 (reply-later id {:type :pubcomp :pid pid}))
    w))

;; ---------------------------------------------------------------- either

(defn handle [w {:keys [to type conn msg]}]
  (let [{:keys [role] :as c} (sim/node w to)
        current? (and conn (= conn (:conn c)))]
    (case type
      :start           (if (= :publisher role)
                         (-> (connect! w to) (sim/timer to 0 {:type :tick}))
                         (connect! w to))
      :connect         (if (:conn c) w (connect! w to))
      :tick            (tick w to)
      :send            (if (sim/open? w conn) (sim/send-on w conn to msg) w)
      :closed          (if current?
                         (-> w (sim/update-node to assoc :conn nil :connected? false)
                             (reconnect-later to))
                         w)
      :connack-timeout (if (and current? (not (:connected? c)))
                         (sim/close w conn [0 0] [10 100])
                         w)
      :drop            (if (:conn c)
                         (sim/close w (:conn c) [10 200] (get-in w [:config :drop-detect-ms] [10 3000]))
                         w)
      :packet          (let [broker (:broker (sim/conn w conn))]
                         (if (= :publisher role)
                           (publisher-packet w to msg)
                           (subscriber-packet w to msg broker))))))
