(ns mqttkat.logging
  "One logger per namespace, resolved once.

   clojure.tools.logging expands a call like `(log/trace ...)` into

     (let [logger (impl/get-logger *logger-factory* ns)]
       (if (impl/enabled? logger level)
         ...))

   — the logger is fetched *before* the level is checked, so a disabled trace
   statement still pays for the lookup. That lookup is not cheap here: the
   factory is slf4j over log4j2, and log4j2 works out the calling class by
   walking the stack.

   A CPU profile of a 2,000-subscriber QoS 1 run put **38.5% of the broker's
   time** under tools.logging, with the level at INFO and not one of those
   messages emitted. All of it was stack walking and the lock around it:
   `send_buffer` alone, which logs twice and runs once per delivery, was 18.7%.

   Loggers are stable for the life of a namespace, so they are cached here.
   Level changes still take effect — a log4j2 Logger is a live view of the
   configuration, which is what `enabled?` consults, so the test helper that
   silences a logger works exactly as before."
  (:require [clojure.tools.logging :as log]
            [clojure.tools.logging.impl :as impl])
  (:import [java.util.concurrent ConcurrentHashMap]
           [java.util.function Function]))

(defn caching-factory
  "`delegate` with its get-logger memoised by namespace."
  [delegate]
  (let [cache (ConcurrentHashMap.)
        make  (reify Function
                (apply [_ logger-ns] (impl/get-logger delegate logger-ns)))]
    (reify impl/LoggerFactory
      (name [_] (impl/name delegate))
      (get-logger [_ logger-ns] (.computeIfAbsent cache logger-ns make)))))

(defonce ^:private installed (atom false))

(defn install!
  "Wrap the current logger factory in a cache. Idempotent — wrapping twice
   would only add a second map in front of the first."
  []
  (when (compare-and-set! installed false true)
    (alter-var-root #'log/*logger-factory* caching-factory))
  true)
