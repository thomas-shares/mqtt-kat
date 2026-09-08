(ns mqttkat.handlers.disconnect
  (:require [clojure.tools.logging :as log]
            [mqttkat.s :refer [*server*]]
            [mqttkat.handlers :refer [handle-will-if-present remove-client! remove-timer!
                                      set-session-expiry!]])
  (:import [org.mqttkat MqttReasonCode]
           [org.mqttkat.server MqttServer]))

(defn close-connection!
  "Forget the client and shut the socket. Says nothing about the will — the
   two callers below differ on exactly that."
  [client-key]
  (remove-timer! client-key)
  (remove-client! client-key)
  (let [{s :server} (meta @*server*)]
    (.closeConnection ^MqttServer s client-key)))

(defn disconnect-client
  "Drop a client that did not say goodbye, publishing its will.

   The will exists for this case: the connection ended without the client
   asking it to, so anything watching should be told."
  [client-key]
  (log/trace "Disconnecting client::" client-key)
  (handle-will-if-present client-key)
  (close-connection! client-key))

(defn disconnect
  "A DISCONNECT from the client.

   §3.14.4: on receiving one the server discards the will *without publishing
   it*. This used to publish it — every polite goodbye told the client's
   subscribers it had crashed — and no test caught it, because both will tests
   drop the socket instead of disconnecting, which is the case where the will
   genuinely should fire.

   MQTT 5's reason code 0x04 is the exception, and the only way to ask for it:
   a 3.1.1 client that wanted its will published had to drop the connection and
   hope it looked like a crash."
  [{:keys [client-key reason-code from-client? properties]}]
  (log/trace "Disconnecting client:" client-key "reason:" reason-code
             "from client:" from-client?)
  ;; §3.14.2.2.2: applied before the session is parked, because it is what
  ;; decides how long it is parked for.
  (when-let [expiry (:session-expiry-interval properties)]
    (set-session-expiry! client-key expiry))
  ;; from-client? false is the broker's own DISCONNECT, raised because the
  ;; socket has gone — see MqttDisconnect/broadcastEnded. That is precisely the
  ;; case the will exists for, and treating it as a polite goodbye stopped
  ;; every will from firing until this told the two apart.
  (if (or (false? from-client?)
          (= (long (or reason-code 0)) (long MqttReasonCode/DISCONNECT_WITH_WILL_MESSAGE)))
    (do (log/trace "publishing the will")
        (handle-will-if-present client-key))
    (log/trace "polite DISCONNECT: the will is discarded unpublished"))
  (close-connection! client-key))
