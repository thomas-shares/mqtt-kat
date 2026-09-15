(ns mqttkat.server
  (:require [clojure.tools.logging :as log]
            [mqttkat.handlers :as h]
            [mqttkat.handlers.connect :as connect]
            [mqttkat.handlers.disconnect :as disconnect]
            [mqttkat.handlers.connack :as connack]
            [mqttkat.logging :as logging]
            [mqttkat.profiling :as profiling]
            [mqttkat.rama.cluster :as rama]
            [mqttkat.sys :as sys]
            [mqttkat.web.server :as web]
            [mqttkat.util :as util]
            [mqttkat.s :refer [*server*]]
            [overtone.at-at :as at]
            ;;[clj-async-profiler.core :as prof]
            #_[virgil :as virgil])
  (:import [org.mqttkat.server MqttServer]
           [org.mqttkat MqttHandler])
  (:gen-class))

(set! *warn-on-reflection* true)
;;(virgil/watch-and-recompile ["src/java"] :verbose true)

(def handler-map {:CONNECT      connect/connect
                  :CONNACK      connack/connack
                  :PUBLISH      h/publish
                  :PUBACK       h/puback
                  :PUBREC       h/pubrec
                  :PUBREL       h/pubrel
                  :PUBCOMP      h/pubcomp
                  :SUBSCRIBE    h/subscribe
                  :UNSUBSCRIBE  h/unsubscribe
                  :PINGREQ      h/pingreq
                  :PINGRESP     h/pingresp
                  :DISCONNECT   disconnect/disconnect
                  :AUTHENTICATE h/authenticate})

(defn default-handler-fn [{:keys [packet-type client-key] :as msg} _]
  (log/trace "message is received." msg)
  (when packet-type
    ;; Any packet from a client proves it is alive — that is the whole job of
    ;; PINGREQ — so the keep-alive stamp is refreshed on the inbound path.
    (when client-key
      (h/update-timestamps [client-key]))
    ((packet-type handler-map) msg)))

(defn run-server [ip port handler]
  #_(prof/serve-files 8080)
  (let [s           (MqttServer. ^String ip ^int port handler)
        stop-server (fn stop-server [& {:keys [timeout] :or {timeout 100}}]
                      (log/debug "meta stop...")
                      (.stop s timeout))]
    (.start s)
    (with-meta stop-server {:local-port (.getPort s)
                            :server     s})))

(defn start!
  ([] (start! "0.0.0.0" 1883 (MqttHandler. ^clojure.lang.IFn default-handler-fn 4)))
  ([ip port]
   (start! ip port (MqttHandler. ^clojure.lang.IFn default-handler-fn 4)))
  ([ip port handler]
   ;; Before the broker takes a connection: every log statement on the publish
   ;; path resolves a logger, and unwrapped that is a stack walk each time.
   (logging/install!)
   (reset! *server* (run-server ip port handler))
   ;; Here rather than at namespace load: stop! resets the pool these run on,
   ;; so the schedule belongs with the thing being started.
   (h/start-retained-sweep!)
   @*server*))

(defn stop! []
  (when (@*server*)
    (log/info "Server stopping...")
    ;;(prof/stop {})
    (at/stop-and-reset-pool! h/my-pool :strategy :kill)
    (alter-meta! *server* #(assoc % :timeout 1000))
    (reset! *server* nil)
    ;; Nothing to do unless -main opened one.
    (rama/disconnect!)))

(defn -main
  "Start the broker, the $SYS publisher and the status page, and report until
   killed.

   Takes the MQTT port and then the HTTP one, both optional: a second instance
   can be run alongside one that already has 1883, which the out-of-process
   scale test needs and which is generally useful for trying something without
   stopping what is there."
  [& args]
  (let [port      (if-let [p (first args)] (Long/parseLong (str p)) 1883)
        http-port (if-let [p (second args)] (Long/parseLong (str p)) web/default-port)]
    ;; Before the broker, so the profile covers startup as well as the run.
    ;; A no-op unless -Dmqttkat.profile is set.
    (profiling/start!)
    ;; Before the broker listens, so a client is never accepted into a broker
    ;; whose durable side is still coming up. A no-op unless -Dmqttkat.rama
    ;; is set, and like sys/start! below it is here and not in start!: the
    ;; test suite's broker runs without it, and rama-test attaches its own.
    (rama/connect!)
    (start! "0.0.0.0" (int port))
    ;; Once listening, and not before: this tells the other brokers where
    ;; to forward to, and they will take it at its word. And taken back on
    ;; the way out, whichever way out it is: a SIGTERM never reaches stop!,
    ;; and an announcement left behind has the others forwarding to a broker
    ;; that is gone until something else notices.
    (rama/register! port)
    (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable rama/disconnect!))
    ;; Started here rather than in start!, so the test suite's broker does not
    ;; spend its life publishing retained $SYS messages into the state the
    ;; tests are asserting about. Anything that wants them calls sys/start!.
    (sys/start!)
    ;; http-kit brings its own threads, so this returns as soon as it is
    ;; listening; util/info below is still what holds the main thread.
    (web/start! http-port)
    (util/info)))

(comment
  (start!)
  (stop!)

  (virgil/watch-and-recompile ["src/java"] :verbose true)

  (do
    (stop!)
    (start!)))
