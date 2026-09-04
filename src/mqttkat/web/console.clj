(ns mqttkat.web.console
  "MQTT Console — three server-rendered pages (overview, topics, settings).

   The design's markup, with its sample readings replaced by the broker's own.
   Every figure is rendered from mqttkat.web.state at request time and then
   kept fresh over the websocket, so the page is already correct before any
   JavaScript runs and stays correct without a reload. The ids below are the
   contract between the two: state/fields hands out strings keyed by element
   id, hiccup puts them in, console.js assigns them to the same ids.

   Settings is the exception and is still static. It is a form for
   configuration the broker does not read from anywhere yet; wiring it up
   means somewhere to save to, which is a different piece of work from
   reporting."
  (:require [clojure.string :as str]
            [hiccup2.core :as h]
            [hiccup.page :refer [doctype]]
            [mqttkat.handlers :as handlers]
            [mqttkat.s :as s]
            [mqttkat.sys :as sys]
            [mqttkat.web.state :as state]))

;; ── icons (Lucide, stroked on currentColor) ───────────────────────────

(defn- icon [& paths]
  [:svg {:width 16 :height 16 :viewBox "0 0 24 24" :fill "none"
         :stroke "currentColor" :stroke-width 2 :stroke-linecap "square"}
   paths])

(def icon-overview
  (icon [:rect {:x 3 :y 3 :width 7 :height 9}] [:rect {:x 14 :y 3 :width 7 :height 5}]
        [:rect {:x 14 :y 12 :width 7 :height 9}] [:rect {:x 3 :y 16 :width 7 :height 5}]))

(def icon-topics
  (icon [:path {:d "M4 4h6v6H4z"}] [:path {:d "M14 14h6v6h-6z"}] [:path {:d "M7 10v7h7"}]))

(def icon-settings
  (icon [:circle {:cx 12 :cy 12 :r 3}]
        [:path {:d "M12 2v3M12 19v3M2 12h3M19 12h3M5 5l2 2M17 17l2 2M19 5l-2 2M7 17l-2 2"}]))

(def icon-clients
  (icon [:circle {:cx 9 :cy 8 :r 3}] [:path {:d "M3 20c0-3.3 2.7-6 6-6s6 2.7 6 6"}]
        [:path {:d "M17 11h4M17 16h4"}]))

(defn- chevron [open?]
  [:svg {:width 12 :height 12 :viewBox "0 0 24 24" :fill "none"
         :stroke "currentColor" :stroke-width 3 :stroke-linecap "square"}
   [:path {:d (if open? "M6 9l6 6 6-6" "M9 6l6 6-6 6")}]])

;; ── chrome ────────────────────────────────────────────────────────────

(defn- nav-item [{:keys [href label glyph active? disabled?]}]
  [:a {:class (str "nav-item"
                   (when active? " is-active")
                   (when disabled? " is-disabled"))
       :href (or href "#")
       :aria-current (when active? "page")}
   glyph label])

(defn- sidebar [active foot]
  [:nav.side
   [:div.side-brand
    [:div.side-mark "MQTT" [:br] "Console"]
    [:div.label "Broker ops"]]
   [:div.side-nav
    (nav-item {:href "/"         :label "Overview" :glyph icon-overview :active? (= active :overview)})
    (nav-item {:href "/topics"   :label "Topics"   :glyph icon-topics   :active? (= active :topics)})
    (nav-item {:href "/settings" :label "Settings" :glyph icon-settings :active? (= active :settings)})
    (nav-item {:label "Clients"  :glyph icon-clients :disabled? true})]
   [:div.side-foot foot]])

(defn- page-head [{:keys [eyebrow title tools]}]
  [:header.page-head
   [:div.page-title-group
    [:div.label eyebrow]
    [:h1.page-title title]]
   [:div.page-tools tools]])

(def ^:private chart-gradients
  "Defined once for the document and referenced from the stylesheet, because
   an SVG paint server is addressed by fragment and CSS resolves that against
   the document rather than the sheet. Per-chart defs would mean per-chart
   ids, and the fill would have to move out of CSS and into the markup."
  [:svg {:width 0 :height 0 :aria-hidden "true" :style "position:absolute"}
   [:defs
    [:linearGradient {:id "grad-in" :x1 "0" :y1 "0" :x2 "0" :y2 "1"}
     [:stop.grad-in-top {:offset "0%"}]
     [:stop.grad-in-bottom {:offset "100%"}]]
    [:linearGradient {:id "grad-out" :x1 "0" :y1 "0" :x2 "0" :y2 "1"}
     [:stop.grad-out-top {:offset "0%"}]
     [:stop.grad-out-bottom {:offset "100%"}]]]])

(defn- layout [{:keys [title active sidebar-foot]} & body]
  (str
   (doctype :html5)
   (h/html
    [:html {:lang "en"}
     [:head
      [:meta {:charset "utf-8"}]
      [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
      [:title title]
      [:link {:rel "preconnect" :href "https://fonts.gstatic.com" :crossorigin "anonymous"}]
      [:link {:rel "stylesheet"
              :href "https://fonts.googleapis.com/css2?family=Archivo:wght@400;500;600;700;800&display=swap"}]
      [:link {:rel "stylesheet" :href "/css/modernist.css"}]
      [:link {:rel "stylesheet" :href "/css/console.css"}]
      [:script {:src "/js/console.js" :defer true}]]
     [:body
      chart-gradients
      [:div.app
       (sidebar active sidebar-foot)
       body]]])))

(defn- mqtt-address
  "Where the broker is listening, for the sidebar. The port is on the server's
   metadata; before start! there is no server and no port to report."
  []
  (if-let [port (:local-port (meta @s/*server*))]
    ;; getLocalHost throws on a machine whose hostname does not resolve, which
    ;; is common enough in a container that the sidebar should not depend on
    ;; it. The port is the part worth knowing.
    (str (try (.getHostName (java.net.InetAddress/getLocalHost))
              (catch Exception _ "localhost"))
         ":" port)
    "not listening"))

(defn- broker-foot [fields]
  (list [:div.label "Broker"]
        [:div.side-foot-name sys/broker-version]
        [:div.side-foot-sub (mqtt-address)]
        [:div.side-foot-sub.num {:id "uptime-foot"} (fields "uptime-foot")]
        [:div.live [:div.live-dot] [:div.live-text "Live"]]))

;; ── charts ────────────────────────────────────────────────────────────

(defn- sparkline
  "An empty frame. console.js draws the line from the same history the big
   charts use, so there is nothing sensible to render here first — a shape
   made up server-side would be replaced within the second and would be a
   picture of nothing until it was."
  [{:keys [id accent]}]
  [:svg.spark {:id id :viewBox "0 0 120 32" :preserveAspectRatio "none"
               :data-series (if accent "in" "out")}
   [:path {:class (if accent "series-in-fill" "series-out-fill") :d ""}]
   [:path {:class (if accent "series-in-line" "series-out-line") :d ""
           :vector-effect "non-scaling-stroke"}]])

(defn- chart
  "One plotted panel. The wrapper is positioned so that the things that have
   to line up with the drawing but are not part of it — the value labels, the
   hover cursor, the tooltip, the marker on the latest reading — can be
   ordinary elements laid over it. Putting them in the SVG instead would run
   them through preserveAspectRatio=none, which stretches the drawing to the
   panel: it is what lets the line fill the width, and it would also turn
   every dot into an ellipse and every label into whatever the window size
   made of it."
  [{:keys [id height variant series]}]
  [:div.chart-wrap {:id (str id "-wrap") :data-chart id}
   [:div.chart-ticks]
   [:svg {:id id :class (str "chart " variant) :viewBox (str "0 0 1000 " height)
          :preserveAspectRatio "none"}
    [:g.chart-grid-lines]
    (for [s series]
      (list [:path {:class (str "series-" s "-fill") :d ""}]
            [:path {:class (str "series-" s "-line") :d "" :vector-effect "non-scaling-stroke"}]))
    [:line.chart-base {:x1 0 :y1 height :x2 1000 :y2 height :vector-effect "non-scaling-stroke"}]]
   [:div.chart-cursor {:hidden true}]
   (for [s series] [:div {:class (str "chart-dot chart-dot--" s) :hidden true}])
   [:div.chart-tip {:hidden true}]])

(defn- axis [id]
  [:div.axis.num {:id id} (for [_ (range 5)] [:span "—"])])

;; ── overview ──────────────────────────────────────────────────────────

(def ^:private headline-metrics
  "Label, unit and the sparkline series, alongside the ids state/fields fills.
   The numbers themselves are not here; they are read from the broker."
  [{:label "Messages in / out" :id "m-throughput" :unit "msg/s" :spark "spark-throughput" :accent true}
   {:label "Connected clients" :id "m-clients"    :spark "spark-clients"}
   {:label "Queued / inflight" :id "m-queued"     :spark "spark-queued"}
   {:label "CPU / heap"        :id "m-mem"        :spark "spark-heap"}])

(defn- metric [fields {:keys [label id unit spark accent]}]
  [:div.metric
   [:div.label label]
   [:div.metric-value
    [:div.metric-num.num {:id id} (fields id)]
    ;; A unit that is itself a reading — "of 64 max" — gets an id and is kept
    ;; up to date; a fixed one like msg/s does not need one.
    [:div.metric-unit (if unit unit [:span {:id (str id "-unit")} (fields (str id "-unit"))])]]
   (sparkline {:id spark :accent accent})
   [:div.metric-note {:id (str id "-note")} (fields (str id "-note"))]])

(defn overview-page []
  (let [now    (state/current)
        fields (state/fields now)]
    (layout
     {:title "Overview — MQTT Console" :active :overview :sidebar-foot (broker-foot fields)}
     [:div.main
      (page-head
       {:eyebrow "Broker health"
        :title "Overview"
        :tools (list
                [:div.page-stamp.num {:id "stamp"} (fields "stamp")]
                [:div.legend
                 [:div.legend-item [:div.legend-key] "Inbound"]
                 [:div.legend-item [:div.legend-key.legend-key--out] "Outbound"]])})

      [:div.metrics (for [m headline-metrics] (metric fields m))]

      [:div.panels
       [:div.panel.panel--ruled
        [:div.panel-head
         [:h2.panel-title "Message throughput"]
         [:div.chart-scale.num {:id "chart-throughput-peak"}]]
        [:div.panel-body
         (chart {:id "chart-throughput" :height 220 :variant "chart--tall" :series ["out" "in"]})
         (axis "axis-throughput")]
        [:div.panel-body.panel-body--split
         [:div.panel-head
          [:h2.panel-title "Connected clients"]
          [:div.chart-scale.num {:id "chart-clients-peak"}]]
         (chart {:id "chart-clients" :height 120 :variant "chart--short" :series ["out"]})
         (axis "axis-clients")]]

       [:div.panel
        [:div.panel-head [:h2.panel-title "Broker counters"]]
        [:div.table-wrap
         [:table.table
          [:thead [:tr [:th "Counter"] [:th.cell-right "Value"] [:th.cell-right "Rate"]]]
          [:tbody
           (for [{:keys [id name rate]} state/counter-rows]
             [:tr [:td name]
              [:td.cell-right.cell-strong.num {:id (str "c-" id)} (fields (str "c-" id))]
              (if (= :none rate)
                [:td.cell-right.cell-dim "—"]
                [:td.cell-right.cell-dim.num {:id (str "c-" id "-rate")}
                 (fields (str "c-" id "-rate"))])])]]]
        [:div.events
         [:div.label "Recent events"]
         [:div.event-list {:id "event-list"}
          [:div.event-empty "Nothing yet — connects and disconnects appear here."]]]]]])))

;; ── topics ────────────────────────────────────────────────────────────

(defn- payload-str
  "Retained payloads come off the wire, so this may be bytes or a string, and
   either may be long or not be text at all. Rendered short and, when it is
   not printable, described rather than pasted into the page."
  [payload]
  (let [s (cond
            (nil? payload)     ""
            (bytes? payload)   (String. ^bytes payload "UTF-8")
            :else              (str payload))
        s (str/replace s #"[\p{Cntrl}]" " ")]
    (cond
      (str/blank? s)      "—"
      (> (count s) 60)    (str (subs s 0 60) "…")
      :else               s)))

(defn- topic-rows
  "Every retained topic, $SYS branch first and then the rest, grouped under
   their first segment so the tree is a tree rather than a flat list.

   Retained is what the broker can actually answer for. A topic that has been
   published on but not retained leaves nothing behind — the broker forwards
   it and forgets it — so listing those would mean keeping a copy of every
   payload that crossed the broker, which is a store, not a console."
  []
  (let [retained @handlers/*retained*
        grouped  (group-by (fn [[topic _]] (first (str/split (str topic) #"/"))) retained)]
    (for [[branch entries] (sort-by (fn [[b _]] [(if (= "$SYS" b) 0 1) b]) grouped)
          row (cons {:kind :branch :topic branch :value (str (count entries) " sub-topics")}
                    (for [[topic {:keys [qos payload]}] (sort-by key entries)]
                      {:kind :leaf
                       :topic (str/replace-first (str topic) (str branch "/") "")
                       :value (payload-str payload)
                       :qos qos}))]
      row)))

(defn topics-page []
  (let [now    (state/current)
        fields (state/fields now)
        rows   (topic-rows)]
    (layout
     {:title "Topics — MQTT Console"
      :active :topics
      :sidebar-foot (broker-foot fields)}
     [:div.main
      (page-head
       {:eyebrow "Retained topics"
        :title "Topics"
        :tools [:div.page-stamp.num {:id "stamp"} (fields "stamp")]})

      [:div.stat-row
       [:div.stat [:div.label "Topics listed"] [:div.stat-value.num {:id "t-topics"} (fields "t-topics")]]
       [:div.stat [:div.label "Subscriptions"] [:div.stat-value.num {:id "t-subs"} (fields "t-subs")]]
       [:div.stat [:div.label "Combined rate"]
        [:div.stat-value.num [:span {:id "t-rate"} (fields "t-rate")] " " [:small "msg/s"]]]]

      [:div.table-wrap
       [:table.table
        [:thead
         [:tr [:th {:style "width:44%"} "Topic"] [:th "Last value"] [:th.cell-right "QoS"]]]
        [:tbody
         (if (empty? rows)
           [:tr [:td {:colspan 3} [:div.event-empty "No retained messages."]]]
           (for [{:keys [kind topic value qos]} rows]
             (if (= kind :branch)
               [:tr.tree-branch
                [:td [:span.tree-name (chevron true) topic]]
                [:td.cell-dim value]
                [:td.cell-right.cell-dim ""]]
               ;; No "retained" tag on the rows. Every topic on this page is
               ;; retained — that is what the page lists — so a tag on all of
               ;; them marks nothing and reads as noise on sixty-eight lines.
               [:tr
                [:td.tree-leaf topic]
                [:td.cell-strong value]
                [:td.cell-right.cell-dim qos]])))]]]])))

;; ── settings ──────────────────────────────────────────────────────────

(defn- field [label value & [type]]
  [:div.field
   [:label label]
   [:input.input {:type (or type "text") :value value}]])

(defn settings-page []
  (layout
   {:title "Settings — MQTT Console"
    :active :settings
    :sidebar-foot (broker-foot (state/fields (state/current)))}
   [:div.main.main--settings
    (page-head
     {:eyebrow "Connection"
      :title "Settings"
      :tools [:span.tag.tag-neutral "Not wired up yet"]})

    [:div.settings-cols
     [:div.settings-col.settings-col--ruled
      [:div.group
       [:h2.group-title "Broker"]
       [:div.row-2-1 (field "Host" "mqtt.plant-a.internal") (field "Port" "8883")]
       [:div.row-2 (field "Client ID" "ops-console-01") (field "Keepalive (s)" "60")]
       [:div.choice
        [:div.choice-label "Protocol version"]
        [:div.seg
         [:label.seg-opt [:input {:type "radio" :name "proto" :checked true}] "3.1.1"]
         [:label.seg-opt [:input {:type "radio" :name "proto"}] "5.0"]]]
       [:div.choice-row
        [:label.radio [:input {:type "radio" :name "session" :checked true}] [:span.dot] "Clean session"]
        [:label.radio [:input {:type "radio" :name "session"}] [:span.dot] "Persistent session"]]]

      [:hr.hr]

      [:div.group
       [:h2.group-title "Authentication"]
       [:div.row-2 (field "Username" "ops-readonly")
        (field "Password" "························" "password")]
       [:p.note "Nothing on this page is read or saved by the broker yet. It is the "
        "design's form, kept so the layout is not lost before there is somewhere "
        "for it to write to."]]]

     [:div.settings-col
      [:div.group
       [:h2.group-title "TLS"]
       [:div.choice-row
        [:label.radio [:input {:type "radio" :name "tls" :checked true}] [:span.dot] "TLS enabled"]
        [:label.radio [:input {:type "radio" :name "tls"}] [:span.dot] "Plaintext"]]
       (field "CA certificate" "/etc/mosquitto/certs/plant-a-ca.crt")
       [:div.row-2 (field "Client certificate" "ops-console.crt")
        (field "Client key" "ops-console.key")]
       [:div.choice
        [:div.choice-label "Certificate verification"]
        [:div.seg
         [:label.seg-opt [:input {:type "radio" :name "verify" :checked true}] "Verify peer"]
         [:label.seg-opt [:input {:type "radio" :name "verify"}] "Skip"]]]]

      [:hr.hr]

      [:div.group
       [:h2.group-title "Default subscription"]
       [:div.row-2-1 (field "Topic filter" "#") (field "QoS" "0")]]]]

    [:footer.page-foot
     [:div.note "Changes take effect on the next reconnect."]
     [:div.page-tools
      [:button.btn.btn-secondary {:type "button" :disabled true} "Test connection"]
      [:button.btn.btn-primary {:type "button" :disabled true} "Save configuration"]]]]))
