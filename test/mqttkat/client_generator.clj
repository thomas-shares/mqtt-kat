(ns mqttkat.client-generator
  (:require [causatum.event-streams :as es]
            [clojure.test :refer [deftest]]))

(def model
  {:graph
    {:connect [{:disconnect {:weight 1}}
               {:publish {:weight 2}}]
     :publish [{:disconnect {:weight 1}}
               {:publish {:weight 2}}]
     :disconnect [{:connect {:weight 1}}]}})


(defn connect []
  (println "connect"))

(defn publish []
  (println "publish"))

(defn disconnect []
  (println "disconnect"))

;(deftest simulation
    ;; We create an event stream (or chain of state transitions, if you will) by
    ;; calling Causatum's event-stream function with our model and an initial seed
    ;; state.
;    (doseq [{state :state} (take 15 (es/event-stream model [{:rtime 0, :state :connect}]))]
;      (println "State:" state)))
      ;(({:connect connect, :publish publish, :disconnect disconnect} state))))
        
