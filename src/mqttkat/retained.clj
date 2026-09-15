(ns mqttkat.retained
  "The retained messages: one per topic, the last publish that asked to be
   kept (§3.3.1.3).

   An atom, read by whatever needs it — replay on subscribe, the sweep, the
   counters — and written only through `retain!` and `clear!`. Those two are
   the seam: a `sink` installed by mqttkat.rama.cluster hears every write and
   records it, so that a message retained on one broker is retained for the
   subscribers of every broker and survives a restart. The copy that comes
   back from Rama is applied with `sync!`, which does not go through the
   sink — it is the record, not a new write.

   $-topics never reach the sink. $SYS/… is each broker's own statistics
   under the same names on every broker, and sharing them would have one
   broker's readings replacing another's."
  (:require [clojure.tools.logging :as log]))

(defonce store
  ;; topic -> {:qos :payload :properties :stored-at}
  (atom {}))

(defonce sink
  ;; (fn [topic message-or-nil]) or nil.
  (atom nil))

(defn- tell-sink! [^String topic message]
  (when-let [f @sink]
    (when-not (.startsWith topic "$")
      (try
        (f topic message)
        (catch Throwable t
          (log/warn t "could not record retained message on" topic))))))

(defn retain!
  "Keep `message` — {:qos :payload :properties :stored-at} — for `topic`."
  [topic message]
  (swap! store assoc topic message)
  (tell-sink! topic message))

(defn clear!
  "Forget what is retained on `topic`, if anything."
  [topic]
  (swap! store dissoc topic)
  (tell-sink! topic nil))

(defn sync!
  "Apply what the record says, without telling it again."
  [topic message]
  (if message
    (swap! store assoc topic message)
    (swap! store dissoc topic)))
