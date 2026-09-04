(ns mqttkat.events
  "Somewhere for the broker to say what happened, without knowing who cares.

   The web layer depends on the broker; the broker must not depend on the web
   layer, or the two cannot be loaded apart and every test of one drags in the
   other. So handlers emits into here, and anything that wants to know
   registers itself.

   Listeners run on the thread that emitted — a connection's own reader thread
   — so they must be quick and must not throw. Both are enforced here rather
   than trusted: a listener that blocks would slow the connection that fired
   it, and one that throws would otherwise take the CONNECT down with it."
  (:require [clojure.tools.logging :as log]))

(defonce ^:private listeners (atom {}))

(defn listen!
  "Call `f` with every event from now on, replacing any listener under `k`."
  [k f]
  (swap! listeners assoc k f)
  k)

(defn forget! [k]
  (swap! listeners dissoc k))

(defn emit!
  "Tell every listener. Cheap when there are none, which is the normal case —
   a broker with nobody watching should not pay for this."
  [event]
  (let [current @listeners]
    (when (seq current)
      (doseq [[k f] current]
        (try
          (f event)
          (catch Throwable t
            (log/error t "event listener" k "failed on" (:event event))))))))
