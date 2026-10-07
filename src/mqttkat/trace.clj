(ns mqttkat.trace
  "Following chosen messages through a broker, one log line for each thing
   done with them: for finding where a message went that a load run says
   never arrived, when the counts and warnings only say how many.

   Off unless asked for, and then only for what is asked:

     -Dmqttkat.trace=<regex>          the client ids to follow, or
     MQTTKAT_TRACE=<regex>            in the environment, which reaches
                                      brokers started by scripts/brokers.bb
     -Dmqttkat.traceTopics=<regex>    and only on these topics; every topic
     MQTTKAT_TRACE_TOPICS=<regex>     when not given
     -Dmqttkat.traceWhat=<regex>      and only these steps, matched against
     MQTTKAT_TRACE_WHAT=<regex>       what a line says was done; every step
                                      when not given. Following every
                                      client through what only happens
                                      when a session moves leaves out the
                                      tens of millions of live sends.

   With topics given, a publish on one of them is followed at the broker it
   enters too: where it was planned to go, whichever clients it was for.

   A line reads `trace <client> <message> q<qos> <what> <detail>`, or for
   a publish where it entered `trace - <message> q<qos> on <topic> <what>
   <detail>`, the message named by its payload up to the first `|` — a
   chaos client's `<publisher>:<seq>` — or else by its key in the cluster.
   The QoS is the message's as it stands there: a publish's where it
   entered, a delivery's to the client. QoS 0 deliveries are not followed,
   so without it a message a client was not sent could not be told from
   one sent at QoS 0."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log])
  (:import [java.nio.charset StandardCharsets]))

(defn- setting [property env]
  (some-> (or (System/getProperty property) (System/getenv env))
          str/trim
          not-empty
          re-pattern))

(def ^:private clients (delay (setting "mqttkat.trace" "MQTTKAT_TRACE")))
(def ^:private topics (delay (setting "mqttkat.traceTopics" "MQTTKAT_TRACE_TOPICS")))
(def ^:private steps (delay (setting "mqttkat.traceWhat" "MQTTKAT_TRACE_WHAT")))

(def ^:private step-followed
  ;; what -> whether its lines are written. The steps are a handful of
  ;; literals, asked about once per message per client.
  (java.util.concurrent.ConcurrentHashMap.))

(defn- step? [what]
  (let [p @steps]
    (or (nil? p)
        (let [^java.util.concurrent.ConcurrentHashMap m step-followed
              hit (.get m what)]
          (if (some? hit)
            hit
            (let [hit (boolean (re-find p (str what)))]
              (.put m what hit)
              hit))))))

(defn topic?
  "Whether publishes on `topic` are followed where they enter."
  [topic]
  (boolean (when-let [p @topics]
             (and topic (re-find p topic)))))

(defn following?
  "Whether any client's messages on `topic` are followed."
  [topic]
  (boolean (and @clients (or (nil? @topics) (topic? topic)))))

(defn on?
  "Whether `client-id`'s messages on `topic` are followed."
  [client-id topic]
  (boolean (when-let [p @clients]
             (and client-id
                  (re-find p client-id)
                  (or (nil? @topics) (topic? topic))))))

(defn label
  "What a trace line calls `msg`: its payload up to the first `|`, at most
   forty bytes of it, or its key in the cluster."
  [msg]
  (let [^bytes p (:payload msg)
        k        (or (:mqttkat.handlers/msg-key msg) (:msg-key msg)
                     (:mqttkat.handlers/cluster-key msg))]
    (or (when (and p (pos? (alength p)))
          (let [n (min (alength p) 40)
                s (String. p 0 (int n) StandardCharsets/US_ASCII)
                i (str/index-of s "|")]
            (when i (subs s 0 i))))
        k
        "?")))

(defn trace!
  "Log `what` was done with `msg` for `client-id`, if it is followed."
  [client-id msg what & detail]
  (when (and (step? what) (on? client-id (:topic msg)))
    (log/info "trace" client-id (label msg) (str "q" (:qos msg)) what (str/join " " detail))))

(defn publish!
  "Log `what` was done with a publish of `msg` where it entered, if its
   topic is followed."
  [msg what & detail]
  (when (topic? (:topic msg))
    (log/info "trace -" (label msg) (str "q" (:qos msg)) (str "on " (:topic msg)) what
              (str/join " " detail))))
