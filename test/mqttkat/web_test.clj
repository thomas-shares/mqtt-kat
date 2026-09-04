(ns mqttkat.web-test
  "The HTTP status page.

   The handler is a function of a request map, so most of this needs no socket
   at all; one test does bind a port, because \"it starts\" is the part that
   cannot be checked any other way — and the first time it was run for real it
   did not, which is why start! now catches."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [mqttkat.sys :as sys]
            [mqttkat.test-util :as tu]
            [mqttkat.web.console :as console]
            [mqttkat.web.page :as page]
            [mqttkat.web.server :as web])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers]))

(use-fixtures :once tu/broker-fixture)

(deftest the-page-renders-the-broker-state
  (testing "the status page is HTML and carries the numbers"
    ;; One sample first, so there are load averages to show. Before the first
    ;; one there are none, and the page leaves the section out rather than
    ;; printing a heading over nothing — which is what the next test checks.
    (sys/advance-load! 10.0)
    (let [html (page/status)]
      (is (str/starts-with? html "<!DOCTYPE html>"))
      (is (str/includes? html "<title>mqtt-kat</title>"))
      (doseq [heading ["Clients" "Traffic" "Packets" "Load" "Broker"]]
        (is (str/includes? html (str "<h2>" heading "</h2>"))
            (str "the " heading " section should be rendered")))
      (is (str/includes? html "clients/connected") "a $SYS name should appear")
      (is (str/includes? html "mqtt-kat 0.0.1") "the version should appear"))))

(deftest the-page-renders-before-any-load-sample
  (testing "a broker that has not published $SYS yet still has a page"
    (reset! @#'sys/load-state {:totals {} :averages {}})
    (let [html (page/status)]
      (is (str/includes? html "<h2>Clients</h2>") "the rest of the page is still there")
      (is (not (str/includes? html "<h2>Load</h2>"))
          "and the load section is left out rather than shown empty"))))

(deftest the-page-escapes-what-it-renders
  (testing "values are escaped, not interpolated"
    ;; hiccup2 escapes by default, which is the reason for using it over
    ;; hiccup1 here: these values are counters today, but a page that renders
    ;; broker state should not be one refactor away from injecting whatever a
    ;; client put in a topic name.
    (is (= "<p>a &amp; b</p>" (str (hiccup2.core/html [:p "a & b"]))))))

(deftest handler-routes-and-refuses
  (testing "each console page is served, and nothing else is"
    (doseq [[uri title] [["/"         "Overview — MQTT Console"]
                         ["/topics"   "Topics — MQTT Console"]
                         ["/settings" "Settings — MQTT Console"]
                         ["/status"   "mqtt-kat"]]]
      (let [ok (web/handler {:request-method :get :uri uri})]
        (is (= 200 (:status ok)) (str uri " should be served"))
        (is (= "text/html; charset=utf-8" (get-in ok [:headers "Content-Type"])))
        (is (str/includes? (:body ok) (str "<title>" title "</title>"))
            (str uri " should be the " title " page"))))
    (is (= 404 (:status (web/handler {:request-method :get :uri "/nope"}))))
    (let [refused (web/handler {:request-method :post :uri "/"})]
      (is (= 405 (:status refused)))
      (is (= "GET" (get-in refused [:headers "Allow"]))))))

(deftest the-console-links-its-stylesheets
  (testing "the pages ask for the stylesheets the design expects"
    (let [html (console/overview-page)]
      (is (str/includes? html "/css/modernist.css"))
      (is (str/includes? html "/css/console.css")))))

(deftest the-active-nav-item-is-marked
  (testing "each page marks its own nav entry, for CSS and for screen readers"
    (doseq [[page href] [[(console/overview-page) "/"]
                         [(console/topics-page) "/topics"]
                         [(console/settings-page) "/settings"]]]
      (is (re-find (re-pattern (str "aria-current=\"page\"[^>]*href=\"" href "\"|"
                                    "href=\"" href "\"[^>]*aria-current=\"page\"")) page)
          (str href " should be the current page")))
      (is (str/includes? (console/overview-page) "nav-item is-active"))))

(deftest every-class-in-the-markup-is-styled
  (testing "the pages use no class the stylesheets do not define"
    ;; Cheap protection against the two drifting apart: a renamed class in the
    ;; markup, or one dropped from the CSS, shows up as an unstyled corner of a
    ;; page that nobody notices until someone looks at that page. The CSS side
    ;; is matched loosely on purpose — anything that looks like a selector
    ;; counts — so this fails only when a class is genuinely absent.
    (let [markup   (str (console/overview-page) (console/topics-page) (console/settings-page))
          used     (into #{} (mapcat #(str/split % #"\s+"))
                         (map second (re-seq #"class=\"([^\"]+)\"" markup)))
          css      (str (slurp (io/resource "public/css/modernist.css"))
                        (slurp (io/resource "public/css/console.css")))
          defined  (into #{} (map second) (re-seq #"\.([A-Za-z][\w-]*)" css))
          missing  (sort (remove defined used))]
      (is (seq used) "the pages should emit classes at all")
      (is (empty? missing)
          (str "used in markup but not defined in css: " (pr-str missing))))))

(deftest static-assets-are-served-from-resources
  (testing "wrap-resource serves /css out of resources/public"
    ;; The stylesheets are what make this a console rather than a wall of text,
    ;; so the path they load over is worth a test of its own — a 404 here would
    ;; look like a CSS problem rather than a routing one.
    (let [css (web/app {:request-method :get :uri "/css/console.css"})]
      (is (= 200 (:status css)) "an existing stylesheet should be served")
      (is (str/includes? (get-in css [:headers "Content-Type"]) "text/css")
          "and typed as CSS by wrap-content-type"))
    (is (= 404 (:status (web/app {:request-method :get :uri "/css/not-there.css"})))
        "a missing one falls through to the routes")))

(deftest the-server-listens-and-serves
  (testing "start! binds, serves the page, and stops"
    ;; Port 0 so this cannot collide with whatever is on 8080 — which on this
    ;; machine is something else entirely, and is how the bind failure that
    ;; used to kill main was found.
    (try
      (let [port (web/start! 0)]
        (is (pos? port) "start! should report the port it got")
        (let [client   (HttpClient/newHttpClient)
              request  (-> (HttpRequest/newBuilder (URI. (str "http://localhost:" port "/")))
                           (.GET) (.build))
              response (.send client request (HttpResponse$BodyHandlers/ofString))]
          (is (= 200 (.statusCode response)))
          (is (str/includes? (.body response) "<title>Overview — MQTT Console</title>"))))
      (finally
        (web/stop!)))))

(deftest a-port-already-taken-does-not-take-the-broker-with-it
  (testing "start! reports failure rather than throwing"
    ;; -main calls this on the main thread, so an exception here stopped the
    ;; stats loop and left a broker running with nothing reporting on it.
    (try
      (let [taken (web/start! 0)]
        (web/stop!)
        ;; Hold the port with something else, then ask for it.
        (with-open [socket (java.net.ServerSocket. taken)]
          (is (nil? (web/start! (.getLocalPort socket)))
              "a port that cannot be bound should give nil, not an exception")))
      (finally
        (web/stop!)))))
