(ns mqttkat.web.server
  "A small HTTP server beside the broker, for looking at its state.

   http-kit runs its own event loop, so run-server returns as soon as it is
   listening and nothing here blocks the caller — the broker's own start! and
   the stats loop are unaffected."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [mqttkat.rama.cluster :as cluster]
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

(defn- form-fields
  "The fields of a urlencoded form post, as a map. Hand-rolled: the console
   has one form, and ring's params middleware would be the only thing that
   wanted the body read for it."
  [{:keys [body]}]
  (if-not body
    {}
    (let [text (if (string? body) body (slurp body))]
      (into {}
            (keep (fn [pair]
                    (when (seq pair)
                      (let [[k v] (str/split pair #"=" 2)]
                        [(java.net.URLDecoder/decode (or k "") "UTF-8")
                         (java.net.URLDecoder/decode (or v "") "UTF-8")]))))
            (str/split text #"&")))))

(defn handler
  "Routing, such as it is. A function of a request map, so it can be called
   directly in a test without going near a socket.

   /status is the raw $SYS table. It stays alongside the console now that the
   console shows live figures too, because it shows every $SYS topic rather
   than the ones the design had room for, and it needs no JavaScript."
  [{:keys [uri request-method] :as request}]
  (cond
    ;; The one thing the console changes rather than shows. A form post,
    ;; answered with a redirect back to the page, so a refresh does not
    ;; apply it again.
    (and (= :post request-method) (= "/brokers/redirect" uri))
    (let [fields (form-fields request)
          policy (some-> (get fields "policy") keyword)
          via    (some-> (get fields "via") keyword)]
      (cond
        (not (some #{policy} cluster/redirect-policies))
        {:status 400 :headers {"Content-Type" "text/plain"} :body "policy should be one of off, round-robin, load"}

        (and via (not (some #{via} cluster/redirect-vias)))
        {:status 400 :headers {"Content-Type" "text/plain"} :body "via should be one of disconnect, connack"}

        :else
        (do (cluster/setting! cluster/redirect-setting (name policy))
            (when via (cluster/setting! cluster/redirect-via-setting (name via)))
            {:status 303 :headers {"Location" "/brokers"} :body ""})))

    (not= :get request-method)
    {:status 405 :headers {"Allow" "GET"} :body "method not allowed"}

    :else
    (case uri
      "/"         (html (console/overview-page))
      "/topics"   (html (console/topics-page))
      "/clients"  (html (console/clients-page))
      "/brokers"  (html (console/brokers-page))
      ;; No /settings. console/settings-page still exists, but every field on
      ;; it is invented and the broker reads none of it, so it is deliberately
      ;; not reachable rather than served as though it did something.
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
