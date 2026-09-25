(ns mqttkat.unsubscribe-pending-test
  "UNSUBSCRIBE and what the broker was still holding for that subscription
   (§3.10.4, the same in 3.1.1 and 5.0).

   The server MUST stop adding messages for the filter and MUST complete the
   QoS 1 and 2 deliveries it has started; it MAY go on delivering what is
   buffered. This broker now drops the buffered ones — its pending queue —
   unless another subscription the client still holds wants them."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [mqttkat.handlers :as h]
            [mqttkat.test-util :as tu]))

(defn- sub [f & {:as more}]
  (merge {:filter f :topic-filter f :qos 1} more))

(defn- pending-topics [client-id]
  (mapv :topic (:pending (some-> (get @h/*outbound* client-id) deref))))

(defn- fresh
  "An empty *outbound*, so these neither see nor leave the shared broker's."
  [f]
  (binding [h/*outbound* (atom {})] (f)))

(defn- hold!
  "One message in flight on `a/0`, then `topics` queued behind it in order."
  [client-id topics]
  (h/acquire-packet-identifier! client-id {:topic "a/0" :qos 1} 1)
  (doseq [t topics]
    (h/queue-pending! client-id {:topic t :qos 1})))

(deftest queued-messages-for-a-removed-filter-are-dropped
  (fresh
   (fn []
     (hold! "c" ["a/1" "b/1" "a/2" "b/2"])
     (is (= 2 (h/drop-unsubscribed-pending! "c" [(sub "a/#")] [(sub "b/#")])))
     (is (= ["b/1" "b/2"] (pending-topics "c"))
         "the rest stay, and stay in order (§4.6)")
     (is (= 1 (h/inflight-count "c"))
         "what is in flight still completes: §3.10.4 says MUST"))))

(deftest a-message-another-subscription-wants-is-kept
  (fresh
   (fn []
     (testing "overlapping filters: leaving one does not cost the other"
       (hold! "c" ["a/1" "a/x"])
       (is (= 1 (h/drop-unsubscribed-pending! "c" [(sub "a/+")] [(sub "a/1")])))
       (is (= ["a/1"] (pending-topics "c")))))))

(deftest a-shared-subscription-keeps-what-it-was-picked-for
  (fresh
   (fn []
     ;; That message was given to this member on the group's behalf; no other
     ;; member will be sent it, so dropping it would lose it for the group.
     (hold! "c" ["a/1"])
     (is (zero? (h/drop-unsubscribed-pending!
                 "c" [(sub "$share/g/a/#" :topic-filter "a/#" :share-group "g")] [])))
     (is (= ["a/1"] (pending-topics "c"))))))

(deftest a-wildcard-rooted-filter-never-matched-a-dollar-topic
  (fresh
   (fn []
     ;; §4.7.2: `#` did not match `$SYS/x`, so something else queued it.
     (hold! "c" ["$SYS/x" "y"])
     (is (= 1 (h/drop-unsubscribed-pending! "c" [(sub "#")] [])))
     (is (= ["$SYS/x"] (pending-topics "c"))))))

(deftest nothing-to-drop-conjures-nothing
  (fresh
   (fn []
     (is (zero? (h/drop-unsubscribed-pending! "nobody" [(sub "#")] [])))
     (is (not (contains? @h/*outbound* "nobody"))
         "no outbound state is created for a client that had none"))))

;; ── through the broker ────────────────────────────────────────────────

(use-fixtures :once tu/broker-fixture)

(defn- publish! [pub topic i]
  (tu/send-v5! pub {:packet-type :PUBLISH :topic topic :qos 1
                    :packet-identifier (inc i)
                    :payload (.getBytes (str topic "#" i) "UTF-8")
                    :retain? false :duplicate? false}))

(defn- next-publish [ch ms]
  (loop []
    (when-let [m (tu/take! ch ms)]
      (if (= :PUBLISH (:packet-type m)) m (recur)))))

(deftest unsubscribing-drops-what-was-waiting-on-the-window
  (testing "a receive maximum of one: one in flight, the rest queued"
    (let [ta     (tu/topic "unsub-drop-a")
          tb     (tu/topic "unsub-drop-b")
          sub-id (tu/client-id "unsub-drop-sub")
          sub    (tu/connect-v5! sub-id :properties {:receive-maximum 1})
          pub    (tu/connect-v5! (tu/client-id "unsub-drop-pub"))]
      (try
        (tu/send-v5! sub {:packet-type :SUBSCRIBE :packet-identifier 1
                          :topics [{:qos 1 :topic-filter ta}
                                   {:qos 1 :topic-filter tb}]})
        (tu/expect! (:ch sub) :SUBACK)
        (publish! pub ta 0)
        (let [first-msg (next-publish (:ch sub) 3000)]
          (is (= ta (:topic first-msg)) "the first goes straight out")
          (publish! pub ta 1)
          (publish! pub tb 2)
          (publish! pub ta 3)
          (is (tu/wait-until #(= 3 (h/pending-count sub-id)))
              "the other three wait behind it")
          (tu/send-v5! sub {:packet-type :UNSUBSCRIBE :packet-identifier 2
                            :topics [ta]})
          (tu/expect! (:ch sub) :UNSUBACK 3000)
          (is (= 1 (h/pending-count sub-id)) "only the one for the other filter is left")
          (tu/send-v5! sub {:packet-type :PUBACK
                            :packet-identifier (:packet-identifier first-msg)})
          (let [after (next-publish (:ch sub) 2000)]
            (is (= tb (:topic after)) "the next one out is for the filter still held")
            (tu/send-v5! sub {:packet-type :PUBACK
                              :packet-identifier (:packet-identifier after)}))
          (is (nil? (next-publish (:ch sub) 700))
              "and nothing for the filter that was left"))
        (finally (tu/close! sub pub))))))
