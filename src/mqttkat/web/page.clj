(ns mqttkat.web.page
  "The HTML the status server renders.

   Kept apart from the server so the markup can be built and looked at without
   a socket, and so the server has nothing in it but routing."
  (:require [clojure.string :as str]
            [hiccup2.core :as h]
            [mqttkat.sys :as sys]))

(def ^:private styles "
  :root { color-scheme: light dark; }
  body { font: 14px/1.5 system-ui, sans-serif; margin: 2rem auto; max-width: 60rem; padding: 0 1rem; }
  h1 { font-size: 1.4rem; margin-bottom: 0; }
  p.sub { color: #777; margin-top: .25rem; }
  h2 { font-size: 1rem; margin: 2rem 0 .5rem; text-transform: uppercase; letter-spacing: .06em; color: #777; }
  table { border-collapse: collapse; width: 100%; }
  td { padding: .3rem .5rem; border-bottom: 1px solid rgba(128,128,128,.25); }
  td.v { text-align: right; font-variant-numeric: tabular-nums; white-space: nowrap; }
  code { font-family: ui-monospace, monospace; }
")

(def ^:private sections
  "Which topics go under which heading, in the order they are shown. The
   catch-all is last so anything added to sys/stats later still appears
   somewhere rather than being silently dropped."
  [["Clients"  #(str/starts-with? % "$SYS/broker/clients/")]
   ["Traffic"  #(re-find #"/(bytes|messages|publish)/" %)]
   ["Packets"  #(str/starts-with? % "$SYS/broker/mqtt/")]
   ["Load"     #(str/starts-with? % "$SYS/broker/load/")]
   ["Broker"   (constantly true)]])

(defn- short-name
  "Topics are shown without their common prefix: every row would otherwise
   start with the same twelve characters."
  [topic]
  (str/replace topic "$SYS/broker/" ""))

(defn- assign
  "Each topic to the first section that claims it."
  [stats]
  (reduce (fn [acc [topic value]]
            (let [heading (first (first (filter (fn [[_ pred]] (pred topic)) sections)))]
              (update acc heading (fnil conj []) [topic value])))
          {}
          stats))

(defn- table [rows]
  [:table
   (for [[topic value] (sort-by first rows)]
     [:tr
      [:td [:code (short-name topic)]]
      [:td.v (str value)]])])

(defn status
  "The status page, as a string of HTML."
  []
  ;; sys/stats is read fresh, and the load averages are read without being
  ;; advanced — see sys/load-averages for why that matters.
  (let [stats  (merge (sys/stats) (sys/load-averages))
        by-section (assign stats)]
    (str
     (h/html {:mode :html}
             (h/raw "<!DOCTYPE html>")
             [:html {:lang "en"}
              [:head
               [:meta {:charset "utf-8"}]
               [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
               [:title "mqtt-kat"]
               [:style (h/raw styles)]]
              [:body
               [:h1 "mqtt-kat"]
               [:p.sub "The same numbers published under "
                [:code "$SYS/#"] ", for looking at rather than subscribing to."]
               (for [[heading _] sections
                     :let [rows (get by-section heading)]
                     :when (seq rows)]
                 [:div [:h2 heading] (table rows)])]]))))
