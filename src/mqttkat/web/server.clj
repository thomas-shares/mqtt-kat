(ns mqttkat.web.server
  "A small HTTP server beside the broker, for looking at its state.

   http-kit runs its own event loop, so run-server returns as soon as it is
   listening and nothing here blocks the caller — the broker's own start! and
   the stats loop are unaffected."
  (:require [clojure.tools.logging :as log]
            [mqttkat.web.console :as console]
            [mqttkat.web.page :as page]
            [mqttkat.web.ws :as ws]
            [org.httpkit.server :as http]
            [ring.middleware.content-type :refer [wrap-content-type]]
            [ring.middleware.not-modified :refer [wrap-not-modified]]
            [ring.middleware.resource :refer [wrap-resource]]))

(def default-port
  "8080 unless told otherwise with -Dmqttkat.httpPort."
  (if-let [p (System/getProperty "mqttkat.httpPort")]
    (Long/parseLong p)
    8080))

(defonce ^:private server (atom nil))

(defn- html [body]
  {:status  200
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body    body})

(defn handler
  "Routing, such as it is. A function of a request map, so it can be called
   directly in a test without going near a socket.

   /status is the raw $SYS table. It stays alongside the console because the
   console's readings are still the design's sample data, and one page here
   shows what the broker is actually doing."
  [{:keys [uri request-method]}]
  (if (not= :get request-method)
    {:status 405 :headers {"Allow" "GET"} :body "method not allowed"}
    (case uri
      "/"         (html (console/overview-page))
      "/topics"   (html (console/topics-page))
      "/settings" (html (console/settings-page))
      "/status"   (html (page/status))
      {:status 404 :headers {"Content-Type" "text/plain"} :body "not found"})))

(def ^:private wrapped
  "The handler with the static assets in front of it: wrap-resource serves
   /css and /js out of resources/public, and falls through to the routes above
   for everything else."
  (-> handler
      (wrap-resource "public")
      wrap-content-type
      wrap-not-modified))

(defn app
  "The websocket upgrade is routed before the middleware, not through it.
   as-channel returns something http-kit interprets rather than an ordinary
   ring response, and wrap-content-type and wrap-not-modified both reach for
   parts of a response an upgrade does not have."
  [request]
  (if (= "/ws" (:uri request))
    (ws/handler request)
    (wrapped request)))

(defn start!
  "Listen on `port`, 8080 by default. Idempotent.

   Returns the port in use, or nil if it could not listen. A status page is not
   worth a broker: this used to let a BindException out, and since -main calls
   it on the main thread, something else already holding 8080 killed the stats
   loop and left a broker running with nothing reporting on it. 8080 is a
   popular port to have taken."
  ([] (start! default-port))
  ([port]
   (if @server
     (http/server-port @server)
     (try
       (let [s (http/run-server app {:port port :legacy-return-value? false})]
         (ws/start!)
         (reset! server s)
         (log/info "http status page on port" (http/server-port s))
         (http/server-port s))
       (catch Exception e
         (log/error e "could not start the status page on port" port
                    "- the broker is unaffected")
         nil)))))

(defn stop! []
  (ws/stop!)
  (when-let [s @server]
    (http/server-stop! s)
    (reset! server nil)))
