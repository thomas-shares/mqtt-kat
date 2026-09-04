(ns mqttkat.web-test
  "The HTTP status page.

   The handler is a function of a request map, so most of this needs no socket
   at all; one test does bind a port, because \"it starts\" is the part that
   cannot be checked any other way — and the first time it was run for real it
   did not, which is why start! now catches."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [mqttkat.handlers :as handlers]
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

(deftest settings-is-built-but-not-reachable
  (testing "the page still renders, and nothing serves or links to it"
    ;; Kept deliberately: every field on it is invented and the broker reads
    ;; none of it, so serving it would be a page that looks like configuration
    ;; and configures nothing. This asserts both halves — that the markup has
    ;; not rotted, and that no route or nav entry has quietly brought it back.
    (is (str/includes? (console/settings-page) "<title>Settings — MQTT Console</title>")
        "the page should still build")
    (is (= 404 (:status (web/handler {:request-method :get :uri "/settings"})))
        "but nothing should serve it")
    (doseq [[name page] [["overview" (console/overview-page)]
                         ["topics"   (console/topics-page)]]]
      (is (not (str/includes? page "href=\"/settings\""))
          (str "the " name " page should not link to it")))))

(deftest every-topic-row-is-wired-to-its-branch
  (testing "the twisty can find the rows it collapses"
    ;; Branch and leaf are paired by name, not by being next to each other, so
    ;; this is the pairing the browser relies on: a leaf whose data-parent
    ;; matches no branch is a row no twisty can ever hide, and it would look
    ;; like a broken button rather than like missing markup.
    (with-redefs [handlers/*retained*
                  (atom {"$SYS/broker/version"  {:qos 0 :payload "mqtt-kat"}
                         "$SYS/broker/uptime"   {:qos 0 :payload "9 s"}
                         "sensors/a/temp"       {:qos 1 :payload "21.5"}
                         "sensors/b/temp"       {:qos 0 :payload "19.0"}})]
      (let [html     (console/topics-page)
            branches (set (map second (re-seq #"data-branch=\"([^\"]*)\"" html)))
            parents  (set (map second (re-seq #"data-parent=\"([^\"]*)\"" html)))]
        (is (= #{"$SYS" "sensors"} branches) "one branch per first segment")
        (is (= branches parents) "and every branch has rows under it")
        (is (= 2 (count (re-seq #"aria-expanded=" html)))
            "every branch button carries its state for screen readers")
        (is (= 4 (count (re-seq #"tree-row" html))) "with a row per topic")))))

(deftest a-topic-name-cannot-escape-the-markup
  (testing "a client publishing a quote does not get to write attributes"
    ;; A branch is the first segment of a topic and a topic is whatever a
    ;; client published to, so these attribute values are client input. This
    ;; is also why the browser indexes the rows rather than building a
    ;; selector out of the name.
    (with-redefs [handlers/*retained*
                  (atom {"ev\"il onload=x/sub" {:qos 0 :payload "<script>alert(1)</script>"}})]
      (let [html (console/topics-page)]
        (is (not (str/includes? html "<script>alert(1)</script>"))
            "the payload should be escaped, not rendered")
        (is (not (re-find #"data-branch=\"ev\" ?onload" html))
            "and the quote should not close the attribute")
        (is (str/includes? html "&quot;") "hiccup2 should have escaped it")))))

(deftest the-active-nav-item-is-marked
  (testing "each page marks its own nav entry, for CSS and for screen readers"
    (doseq [[page href] [[(console/overview-page) "/"]
                         [(console/topics-page) "/topics"]]]
      (is (re-find (re-pattern (str "aria-current=\"page\"[^>]*href=\"" href "\"|"
                                    "href=\"" href "\"[^>]*aria-current=\"page\"")) page)
          (str href " should be the current page")))
      (is (str/includes? (console/overview-page) "nav-item is-active"))))

(deftest a-column-heading-sits-over-its-own-figures
  (testing "right-aligned cells have right-aligned headings"
    ;; The headings drifted left of their numbers because modernist sets
    ;; `.table th { text-align: left }` at the same specificity as a bare
    ;; `.cell-right` and loads first, so the th rule won and only the body
    ;; cells moved. Asserting the selector is scoped is the cheap way to keep
    ;; that from coming back — a plain `.cell-right` here would lose again.
    (let [css (slurp (io/resource "public/css/console.css"))]
      (is (re-find #"\.table\s+\.cell-right\s*\{[^}]*text-align:\s*right" css)
          "the rule should be scoped to .table so it outweighs .table th")
      (is (not (re-find #"(?m)^\.cell-right\s*\{" css))
          "and there should be no unscoped .cell-right to lose to it")))

  (testing "and both pages mark their heading cells"
    ;; A th without the class is a heading that never lines up, whatever the
    ;; CSS says.
    (doseq [[name html] [["overview" (console/overview-page)]
                         ["topics"   (console/topics-page)]]]
      (is (str/includes? html "<th class=\"cell-right\"")
          (str "the " name " table should mark its right-aligned headings")))))

(deftest every-class-in-the-markup-is-styled
  (testing "the pages use no class the stylesheets do not define"
    ;; Cheap protection against the two drifting apart: a renamed class in the
    ;; markup, or one dropped from the CSS, shows up as an unstyled corner of a
    ;; page that nobody notices until someone looks at that page. The CSS side
    ;; is matched loosely on purpose — anything that looks like a selector
    ;; counts — so this fails only when a class is genuinely absent.
    ;; settings-page is in here although nothing serves it: its stylesheet is
    ;; kept for when it is wired up, and this is what stops those rules being
    ;; dropped as unused in the meantime.
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
