// The console's live half.
//
// The page arrives already correct — every reading is server-rendered — so
// nothing here has to draw a first state from nothing. It keeps that page up
// to date from /ws: a snapshot on open with the readings, the chart history
// behind them and the events already logged, then one frame a second, plus a
// frame the moment a client connects or disconnects.
//
// Every frame carries whole values rather than deltas, so a page that missed
// one is correct after the next and there is nothing to replay on reconnect.
(function () {
  "use strict";

  var RETRY_MAX = 10000;
  var WINDOW_KEY = "mqttkat.chartWindowMs";

  // Everything the server has sent, and the slice of it currently charted.
  // The two are separate so changing the window is instant: the history is
  // already here, and nothing has to be re-fetched or waited for.
  var samples = [];
  var view = [];
  var intervalMs = 1000;      // corrected by the snapshot
  var retentionMs = 120000;   // ditto — how far back the server can go
  var windowMs = 0;           // 0 means "everything the server has"
  var retry = 500;

  // The cluster's brokers, as the overview draws them: [{id, colour, …}],
  // and every broker's colour, id -> 0…6 or "other". Empty with no cluster,
  // and then every chart is drawn as one broker's.
  var members = [];
  var palette = {};

  function storedWindow() {
    try { return parseInt(window.localStorage.getItem(WINDOW_KEY), 10) || 0; }
    catch (e) { return 0; }   // private windows and blocked storage
  }

  function rememberWindow(ms) {
    try { window.localStorage.setItem(WINDOW_KEY, String(ms)); } catch (e) {}
  }

  // The charts read this, never `samples` — so one place decides what is on
  // screen and the hover, the axis and the peak all agree about it.
  function recomputeView() {
    if (!windowMs || samples.length === 0) { view = samples; return; }
    var wanted = Math.max(2, Math.round(windowMs / intervalMs));
    view = samples.length > wanted ? samples.slice(samples.length - wanted) : samples;
  }

  var statusEl = document.querySelector(".live-text");
  var dotEl = document.querySelector(".live-dot");

  // ── formatting ──────────────────────────────────────────────────────
  // Only for things the browser invents: the axis, the tooltip and the chart
  // scale. Every figure that appears in the page proper is formatted once, on
  // the server, and arrives as a string — see mqttkat.web.state.

  function pad(n) { return String(n).padStart(2, "0"); }

  // Topic names are chosen by whoever connected, so they are untrusted text
  // and never markup. The rest of this page writes server-formatted strings
  // into textContent; this one table builds HTML, so it escapes.
  function escapeHtml(s) {
    return String(s).replace(/[&<>"']/g, function (c) {
      return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c];
    });
  }

  function commas(n) { return Number(n).toLocaleString("en-US"); }

  function clock(t) {
    var d = new Date(t);
    return pad(d.getHours()) + ":" + pad(d.getMinutes()) + ":" + pad(d.getSeconds());
  }

  function compact(v) {
    var a = Math.abs(v);
    if (a >= 1e9) return (v / 1e9).toFixed(a >= 1e10 ? 0 : 1) + "G";
    if (a >= 1e6) return (v / 1e6).toFixed(a >= 1e7 ? 0 : 1) + "M";
    if (a >= 1e3) return (v / 1e3).toFixed(a >= 1e4 ? 0 : 1) + "k";
    return String(Math.round(v));
  }

  function bytes(v) {
    var units = ["B", "KiB", "MiB", "GiB", "TiB"], i = 0;
    while (v >= 1024 && i < units.length - 1) { v /= 1024; i++; }
    return (v >= 100 || i === 0 ? v.toFixed(0) : v.toFixed(1)) + " " + units[i];
  }

  // ── the y axis ──────────────────────────────────────────────────────

  // A round top, so the labels read 0/200/400 rather than 0/173/346, and so
  // the plot does not rescale on every frame. Without this the whole chart
  // twitches vertically once a second as the peak wanders by a message or
  // two, which reads as data moving when nothing has.
  function niceMax(peak) {
    if (!(peak > 0)) return 4;
    var magnitude = Math.pow(10, Math.floor(Math.log10(peak)));
    var steps = [1, 1.5, 2, 3, 4, 5, 7.5, 10];
    for (var i = 0; i < steps.length; i++) {
      var candidate = steps[i] * magnitude;
      if (peak <= candidate) return candidate;
    }
    return 10 * magnitude;
  }

  // The scale is held until the data leaves it, rather than recomputed from
  // whatever is on screen. Growing immediately keeps a spike inside the
  // panel; shrinking only once the peak has been under half the axis for a
  // while stops a chart flapping between two scales when a series sits near a
  // boundary.
  function rescale(chart, peak) {
    var wanted = niceMax(peak);
    if (!chart.max || wanted > chart.max) {
      chart.max = wanted;
      chart.shrinkFor = 0;
    } else if (wanted <= chart.max / 2) {
      chart.shrinkFor = (chart.shrinkFor || 0) + 1;
      if (chart.shrinkFor >= 8) { chart.max = wanted; chart.shrinkFor = 0; }
    } else {
      chart.shrinkFor = 0;
    }
    return chart.max;
  }

  // ── paths ───────────────────────────────────────────────────────────

  function points(values, w, h, max) {
    var n = values.length, out = [];
    for (var i = 0; i < n; i++) {
      // 0.94 keeps the peak just clear of the top edge.
      out.push([
        n === 1 ? w : (i / (n - 1)) * w,
        h - (Math.min(values[i], max) / max) * h * 0.94
      ]);
    }
    return out;
  }

  // Monotone cubic (Fritsch–Carlson), not a plain Catmull–Rom: an ordinary
  // spline overshoots at a step, and these series have hard floors — an
  // undershoot below zero would draw a broker sending negative messages, and
  // the fill would spill under the baseline where it did.
  function tangents(p) {
    var n = p.length, slope = [], m = [];
    for (var i = 0; i < n - 1; i++) {
      var dx = p[i + 1][0] - p[i][0];
      slope.push(dx === 0 ? 0 : (p[i + 1][1] - p[i][1]) / dx);
    }
    m.push(slope[0] || 0);
    for (var j = 1; j < n - 1; j++) {
      if (slope[j - 1] * slope[j] <= 0) m.push(0);
      else m.push((slope[j - 1] + slope[j]) / 2);
    }
    m.push(slope[n - 2] || 0);
    for (var k = 0; k < n - 1; k++) {
      if (slope[k] === 0) { m[k] = 0; m[k + 1] = 0; continue; }
      var a = m[k] / slope[k], b = m[k + 1] / slope[k];
      var s = a * a + b * b;
      if (s > 9) { var t = 3 / Math.sqrt(s); m[k] = t * a * slope[k]; m[k + 1] = t * b * slope[k]; }
    }
    return m;
  }

  function curve(p) {
    if (p.length === 0) return "";
    if (p.length === 1) return "M" + p[0][0].toFixed(1) + "," + p[0][1].toFixed(1);
    var m = tangents(p);
    var d = "M" + p[0][0].toFixed(1) + "," + p[0][1].toFixed(1);
    for (var i = 0; i < p.length - 1; i++) {
      var dx = (p[i + 1][0] - p[i][0]) / 3;
      d += "C" + (p[i][0] + dx).toFixed(1) + "," + (p[i][1] + dx * m[i]).toFixed(1) +
           " " + (p[i + 1][0] - dx).toFixed(1) + "," + (p[i + 1][1] - dx * m[i + 1]).toFixed(1) +
           " " + p[i + 1][0].toFixed(1) + "," + p[i + 1][1].toFixed(1);
    }
    return d;
  }

  function box(svg) {
    var b = svg.getAttribute("viewBox").split(/\s+/);
    return { w: parseFloat(b[2]), h: parseFloat(b[3]) };
  }

  function drawSeries(svg, name, values, max) {
    var b = box(svg);
    var line = svg.querySelector("path.series-" + name + "-line");
    var fill = svg.querySelector("path.series-" + name + "-fill");
    if (!line && !fill) return null;
    var p = points(values, b.w, b.h, max);
    var d = curve(p);
    if (line) line.setAttribute("d", d);
    if (fill) fill.setAttribute("d", d ? d + "L" + b.w + "," + b.h + "L0," + b.h + "Z" : "");
    return p.length ? p[p.length - 1] : null;
  }

  // A band per broker, stacked in the overview's order, so the top edge is
  // the cluster's total and the thickness of each band that broker's share.
  // Drawn into a group of its own, made the first time and emptied when the
  // cluster is one broker again.
  function stackGroup(svg) {
    var g = svg.querySelector("g.chart-stack");
    if (!g) {
      g = document.createElementNS(SVG_NS, "g");
      g.setAttribute("class", "chart-stack");
      svg.insertBefore(g, svg.querySelector(".chart-base"));
    }
    return g;
  }

  function colourClass(id) {
    var c = palette[id];
    return "broker-c-" + (c === undefined ? "other" : c);
  }

  function drawStack(svg, bands, max) {
    var g = stackGroup(svg);
    g.textContent = "";
    if (!bands) return;
    var b = box(svg);
    var lower = null;
    for (var i = 0; i < bands.length; i++) {
      var upper = points(bands[i].values, b.w, b.h, max);
      var top = curve(upper);
      var bottom = lower ? curve(lower.slice().reverse()).replace(/^M/, "L")
                         : "L" + b.w + "," + b.h + "L0," + b.h;
      var group = document.createElementNS(SVG_NS, "g");
      group.setAttribute("class", colourClass(bands[i].id));
      var fill = document.createElementNS(SVG_NS, "path");
      fill.setAttribute("class", "stack-fill");
      fill.setAttribute("d", top ? top + bottom + "Z" : "");
      var line = document.createElementNS(SVG_NS, "path");
      line.setAttribute("class", "stack-line");
      line.setAttribute("vector-effect", "non-scaling-stroke");
      line.setAttribute("d", top);
      group.appendChild(fill);
      group.appendChild(line);
      g.appendChild(group);
      lower = upper;
    }
  }

  // The bands, bottom up, when this page draws the cluster a broker at a
  // time: [{id, values}], each value that broker's `of` its share of the
  // point, the running total of those below it added. Null otherwise.
  function bands(of) {
    if (members.length < 2) return null;
    var below = view.map(function () { return 0; });
    return members.map(function (m) {
      var values = view.map(function (s, i) {
        var mine = s.by && s.by[m.id];
        below[i] += mine ? of(mine) : 0;
        return below[i];
      });
      return { id: m.id, values: values };
    });
  }

  function hideSeries(svg, names) {
    for (var i = 0; i < names.length; i++) {
      var paths = svg.querySelectorAll("path.series-" + names[i] + "-line, path.series-" + names[i] + "-fill");
      for (var j = 0; j < paths.length; j++) paths[j].setAttribute("d", "");
    }
  }

  // ── grid and labels ─────────────────────────────────────────────────

  var SVG_NS = "http://www.w3.org/2000/svg";

  function drawGrid(wrap, svg, max, format) {
    var b = box(svg);
    var g = svg.querySelector(".chart-grid-lines");
    var ticks = wrap.querySelector(".chart-ticks");
    if (!g || !ticks) return;
    var steps = b.h > 160 ? 4 : 2;
    g.textContent = "";
    ticks.textContent = "";
    for (var i = 1; i <= steps; i++) {
      var value = (max / steps) * i;
      // The same 0.94 the plot uses, or the labels would sit off the lines.
      var y = b.h - (value / max) * b.h * 0.94;
      var line = document.createElementNS(SVG_NS, "line");
      line.setAttribute("class", "chart-grid");
      line.setAttribute("x1", 0); line.setAttribute("x2", b.w);
      line.setAttribute("y1", y.toFixed(1)); line.setAttribute("y2", y.toFixed(1));
      line.setAttribute("vector-effect", "non-scaling-stroke");
      g.appendChild(line);

      var label = document.createElement("div");
      label.className = "chart-tick";
      label.style.top = ((y / b.h) * 100).toFixed(2) + "%";
      label.textContent = format(value);
      ticks.appendChild(label);
    }
  }

  function drawAxis(id) {
    var el = document.getElementById(id);
    if (!el || view.length === 0) return;
    var spans = el.querySelectorAll("span");
    for (var i = 0; i < spans.length; i++) {
      var at = Math.round((i / (spans.length - 1)) * (view.length - 1));
      spans[i].textContent = clock(view[at].t);
    }
  }

  // ── the two panels ──────────────────────────────────────────────────

  function chartOf(id) {
    var svg = document.getElementById(id);
    if (!svg) return null;
    var wrap = document.getElementById(id + "-wrap");
    return { svg: svg, wrap: wrap, peakEl: document.getElementById(id + "-peak") };
  }

  var throughput = chartOf("chart-throughput");
  var clients = chartOf("chart-clients");

  function field(name) {
    return view.map(function (s) { return s[name] || 0; });
  }

  function place(wrap, svg, selector, point) {
    var el = wrap && wrap.querySelector(selector);
    if (!el) return;
    if (!point) { el.hidden = true; return; }
    var b = box(svg);
    el.hidden = false;
    el.style.left = ((point[0] / b.w) * 100).toFixed(3) + "%";
    el.style.top = ((point[1] / b.h) * 100).toFixed(3) + "%";
  }

  function messages(p) { return (p.in || 0) + (p.out || 0); }

  function redrawThroughput() {
    if (!throughput) return;
    var stack = bands(messages);
    if (stack) {
      // The cluster, a band per broker: its messages in and out together,
      // as one broker's two lines a band each would be unreadable.
      var total = stack[stack.length - 1].values;
      var peak = Math.max.apply(null, total.concat([0]));
      var top = rescale(throughput, peak);
      drawGrid(throughput.wrap, throughput.svg, top, compact);
      hideSeries(throughput.svg, ["in", "out"]);
      drawStack(throughput.svg, stack, top);
      place(throughput.wrap, throughput.svg, ".chart-dot--out", null);
      place(throughput.wrap, throughput.svg, ".chart-dot--in", null);
      if (throughput.peakEl) throughput.peakEl.textContent = "in + out · peak " + compact(peak) + " msg/s";
      drawAxis("axis-throughput");
      return;
    }
    drawStack(throughput.svg, null);
    var inn = field("in"), out = field("out");
    // One scale across both, or inbound and outbound would be drawn against
    // different axes and could not be compared by eye.
    var max = rescale(throughput, Math.max.apply(null, inn.concat(out)));
    drawGrid(throughput.wrap, throughput.svg, max, compact);
    var lastOut = drawSeries(throughput.svg, "out", out, max);
    var lastIn = drawSeries(throughput.svg, "in", inn, max);
    place(throughput.wrap, throughput.svg, ".chart-dot--out", lastOut);
    place(throughput.wrap, throughput.svg, ".chart-dot--in", lastIn);
    if (throughput.peakEl) {
      throughput.peakEl.textContent = "peak " + compact(Math.max.apply(null, inn.concat(out))) + " msg/s";
    }
    drawAxis("axis-throughput");
  }

  function redrawClients() {
    if (!clients) return;
    var stack = bands(function (p) { return p.clients || 0; });
    if (stack) {
      var total = stack[stack.length - 1].values;
      var peak = Math.max.apply(null, total.concat([0]));
      var top = rescale(clients, peak);
      drawGrid(clients.wrap, clients.svg, top, compact);
      hideSeries(clients.svg, ["out"]);
      drawStack(clients.svg, stack, top);
      place(clients.wrap, clients.svg, ".chart-dot--out", null);
      if (clients.peakEl) clients.peakEl.textContent = "peak " + compact(peak);
      drawAxis("axis-clients");
      return;
    }
    drawStack(clients.svg, null);
    var c = field("clients");
    var max = rescale(clients, Math.max.apply(null, c));
    drawGrid(clients.wrap, clients.svg, max, compact);
    place(clients.wrap, clients.svg, ".chart-dot--out",
          drawSeries(clients.svg, "out", c, max));
    if (clients.peakEl) {
      clients.peakEl.textContent = "peak " + compact(Math.max.apply(null, c));
    }
    drawAxis("axis-clients");
  }

  // Sparklines share the history but get their own scale: each is one series
  // in its own box, and there is nothing to compare it against.
  var sparks = [
    { id: "spark-throughput", of: function (s) { return (s.in || 0) + (s.out || 0); }, series: "in" },
    { id: "spark-clients", of: function (s) { return s.clients || 0; }, series: "out" },
    { id: "spark-queued", of: function (s) { return s.queued || 0; }, series: "out" },
    { id: "spark-heap", of: function (s) { return s.heap || 0; }, series: "out" }
  ];

  function redrawSparks() {
    for (var i = 0; i < sparks.length; i++) {
      var svg = document.getElementById(sparks[i].id);
      if (!svg) continue;
      var values = view.map(sparks[i].of);
      var peak = Math.max.apply(null, values.concat([0]));
      drawSeries(svg, sparks[i].series, values, niceMax(peak));
    }
  }

  // ── active topics ───────────────────────────────────────────────────
  //
  // Only the topics page has this table; on the overview the payload is simply
  // ignored. Rows are rebuilt rather than diffed — a dozen of them, once a
  // second, is not worth the machinery, and rebuilding cannot leave a stale
  // row behind when a topic drops out of the busiest few.

  function setTopics(topics) {
    var body = document.getElementById("active-topics");
    if (!body || !topics) return;
    if (topics.length === 0) {
      // Only write the empty state once, or an idle broker rewrites this node
      // every second for no reason.
      if (!body.querySelector("#active-topics-empty")) {
        body.innerHTML = '<tr id="active-topics-empty"><td colspan="3">' +
                         '<div class="event-empty">Nothing published yet.</div></td></tr>';
      }
      return;
    }
    var html = "";
    for (var i = 0; i < topics.length; i++) {
      var t = topics[i];
      html += '<tr><td class="cell-topic active-topic-name" title="' + escapeHtml(t.topic) + '">' +
              escapeHtml(t.topic) + "</td>" +
              '<td class="cell-right num active-topic-rate">' + commas(Math.round(t.rate || 0)) + "</td>" +
              '<td class="cell-right num cell-dim active-topic-total">' + commas(t.total || 0) + "</td></tr>";
    }
    body.innerHTML = html;
  }

  // ── clients ─────────────────────────────────────────────────────────

  function pill(connected) {
    return '<span class="pill' + (connected ? '' : ' pill--dim') + '">' +
           (connected ? "connected" : "parked") + "</span>";
  }

  function idle(ms) {
    if (ms === null || ms === undefined) return "—";
    if (ms < 1000) return "now";
    if (ms < 60000) return Math.floor(ms / 1000) + "s";
    if (ms < 3600000) return Math.floor(ms / 60000) + "m";
    return Math.floor(ms / 3600000) + "h";
  }

  // A broker's page, as the server links it: the id as one path segment.
  function brokerHref(id) {
    return "/brokers/" + encodeURIComponent(id);
  }

  function brokerLink(id) {
    return '<a class="cell-link" href="' + escapeHtml(brokerHref(id)) + '">' + escapeHtml(id) + "</a>";
  }

  // The swatch for broker `id`'s colour, when there is a cluster to tell
  // brokers apart in.
  function brokerKey(id) {
    return palette[id] === undefined ? "" : '<span class="broker-key ' + colourClass(id) + '"></span> ';
  }

  // The brokers the overview adds up, and the legend that names each band.
  function setMembers(list) {
    if (!list) return;
    members = list;
    var strip = document.querySelector("[data-members]");
    if (strip) {
      var html = "";
      for (var i = 0; i < list.length; i++) {
        var m = list[i];
        html += '<a class="member ' + colourClass(m.id) + (m.counted ? "" : " member--out") +
                '" href="' + escapeHtml(brokerHref(m.id)) + '">' +
                '<span class="member-name">' + brokerKey(m.id) + '<span class="member-id">' + escapeHtml(m.id) + "</span></span>" +
                '<span class="member-state">' + escapeHtml(m.state) + "</span>" +
                '<span class="member-figs num">' + escapeHtml(m.clients) + " clients · " + escapeHtml(m.rate) + "</span></a>";
      }
      if (strip.innerHTML !== html) strip.innerHTML = html;
      strip.hidden = list.length < 2;
    }
    var legend = document.querySelector("[data-legend]");
    if (legend) {
      var keys = "";
      if (list.length >= 2) {
        for (var j = 0; j < list.length; j++) {
          keys += '<div class="legend-item">' + brokerKey(list[j].id) + escapeHtml(list[j].id) + "</div>";
        }
      } else {
        keys = '<div class="legend-item"><div class="legend-key"></div>Inbound</div>' +
               '<div class="legend-item"><div class="legend-key legend-key--out"></div>Outbound</div>';
      }
      if (legend.innerHTML !== keys) legend.innerHTML = keys;
    }
  }

  function setClients(clients) {
    var body = document.getElementById("client-list");
    if (!body || !clients) return;
    // The cluster's list says which broker each client is on; a broker's
    // own page does not need to.
    var withBroker = body.dataset.withBroker === "true";
    if (clients.length === 0) {
      if (!body.querySelector("#client-list-empty")) {
        body.innerHTML = '<tr id="client-list-empty"><td colspan="' + (withBroker ? 9 : 8) + '">' +
                         '<div class="event-empty">No clients connected.</div></td></tr>';
      }
      return;
    }
    var html = "";
    for (var i = 0; i < clients.length; i++) {
      var c = clients[i];
      // Client ids are chosen by whoever connected: untrusted text, escaped.
      html += "<tr>" +
        '<td class="cell-topic" title="' + escapeHtml(c.id) + '">' + escapeHtml(c.id) + "</td>" +
        (withBroker ? '<td class="cell-topic">' + (c.broker ? brokerKey(c.broker) + brokerLink(c.broker) : "—") + "</td>" : "") +
        "<td>" + pill(c.connected) + "</td>" +
        '<td class="cell-dim">' + escapeHtml(c.protocol) + "</td>" +
        '<td class="cell-dim">' + (c.clean ? "clean" : "persistent") + "</td>" +
        '<td class="cell-right num">' + commas(c.subscriptions || 0) + "</td>" +
        '<td class="cell-right num">' + commas(c.inflight || 0) + "</td>" +
        '<td class="cell-right num">' + commas(c.queued || 0) + "</td>" +
        '<td class="cell-right num cell-dim">' + idle(c["age-ms"]) + "</td></tr>";
    }
    body.innerHTML = html;
  }

  // ── brokers ─────────────────────────────────────────────────────────

  function brokerPill(stale) {
    return '<span class="pill' + (stale ? ' pill--dim' : '') + '">' +
           (stale ? "stale" : "up") + "</span>";
  }

  function bytesStr(n) {
    if (n === null || n === undefined) return "—";
    if (n < 1024) return n + " B";
    if (n < 1048576) return (n / 1024).toFixed(1) + " KB";
    if (n < 1073741824) return (n / 1048576).toFixed(1) + " MB";
    return (n / 1073741824).toFixed(2) + " GB";
  }

  function setBrokers(brokers) {
    var body = document.getElementById("broker-list");
    if (!body || !brokers) return;
    if (brokers.length === 0) {
      if (!body.querySelector("#broker-list-empty")) {
        body.innerHTML = '<tr id="broker-list-empty"><td colspan="11">' +
                         '<div class="event-empty">Not attached to a Rama cluster.</div></td></tr>';
      }
      return;
    }
    var html = "", clients = 0, rate = 0;
    for (var i = 0; i < brokers.length; i++) {
      var b = brokers[i], s = b.stats;
      if (s) { clients += s.clients || 0; rate += (s["in"] || 0) + (s.out || 0); }
      // Broker ids come from the command line of whoever started them: untrusted text, escaped.
      html += "<tr>" +
        '<td class="cell-topic" title="' + escapeHtml(b.id) + '">' + brokerKey(b.id) + brokerLink(b.id) +
          (b.self ? '<span class="cell-dim"> (this one)</span>' : "") + "</td>" +
        "<td>" + brokerPill(b.stale) + "</td>" +
        '<td class="cell-dim">' + escapeHtml(b.address || "") + "</td>" +
        '<td class="cell-dim">' + escapeHtml((s && s.version) || "—") + "</td>" +
        '<td class="cell-right num cell-dim">' + idle(b["up-ms"]) + "</td>" +
        '<td class="cell-right num">' + (s ? commas(s.clients || 0) : "—") + "</td>" +
        '<td class="cell-right num">' + (s ? commas(s.parked || 0) + " / " + commas(s.subscriptions || 0) : "—") + "</td>" +
        '<td class="cell-right num">' + (s ? commas(s["in"] || 0) + " / " + commas(s.out || 0) : "—") + "</td>" +
        '<td class="cell-right num">' + (s ? commas(s.queued || 0) + " / " + commas(s.inflight || 0) : "—") + "</td>" +
        '<td class="cell-right num">' + (s && s.heap ? bytesStr(s.heap) + " · " +
            (s.cpu !== null && s.cpu !== undefined ? Math.round(s.cpu * 100) + "%" : "—") : "—") + "</td>" +
        '<td class="cell-right num cell-dim">' + idle(b["age-ms"]) + "</td></tr>";
    }
    body.innerHTML = html;
    var el;
    if ((el = document.getElementById("b-count"))) el.textContent = brokers.length;
    if ((el = document.getElementById("b-clients"))) el.textContent = commas(clients);
    if ((el = document.getElementById("b-rate"))) el.textContent = commas(rate);
  }

  // ── how far back to chart ───────────────────────────────────────────
  //
  // Built from the retention the server reports rather than hard-coded, so the
  // page never offers a window it cannot fill — and picks up a broker started
  // with -Dmqttkat.wsHistoryMinutes without being edited.

  // Spelled out, because the control sits on a page of byte counts and
  // uppercase "2M" reads as megabytes.
  var WINDOWS = [
    { ms: 120000, label: "2 min" },
    { ms: 300000, label: "5 min" },
    { ms: 900000, label: "15 min" },
    { ms: 1800000, label: "30 min" },
    { ms: 3600000, label: "1 hr" },
    { ms: 10800000, label: "3 hr" }
  ];

  function windowLabel(ms) {
    for (var i = 0; i < WINDOWS.length; i++) if (WINDOWS[i].ms === ms) return WINDOWS[i].label;
    return "All";
  }

  function buildWindowPicker() {
    var host = document.getElementById("chart-window");
    if (!host) return;
    var offered = WINDOWS.filter(function (w) { return w.ms < retentionMs; });
    offered.push({ ms: 0, label: "All" });   // whatever the server has, always last

    // A remembered choice the current retention cannot serve falls back to
    // everything, rather than silently charting less than the label claims.
    var wanted = storedWindow();
    var usable = offered.some(function (w) { return w.ms === wanted; });
    windowMs = usable ? wanted : 0;

    host.innerHTML = "";
    offered.forEach(function (w) {
      var b = document.createElement("button");
      b.type = "button";
      b.className = "chart-window-option" + (w.ms === windowMs ? " is-selected" : "");
      b.textContent = w.label;
      b.setAttribute("aria-pressed", w.ms === windowMs ? "true" : "false");
      b.addEventListener("click", function () {
        windowMs = w.ms;
        rememberWindow(w.ms);
        var all = host.querySelectorAll(".chart-window-option");
        for (var i = 0; i < all.length; i++) {
          var on = all[i] === b;
          all[i].classList.toggle("is-selected", on);
          all[i].setAttribute("aria-pressed", on ? "true" : "false");
        }
        redraw();
      });
      host.appendChild(b);
    });
    host.setAttribute("aria-label", "Chart window, up to " + windowLabel(0));
  }

  function redraw() {
    recomputeView();
    if (view.length === 0) return;
    redrawThroughput();
    redrawClients();
    redrawSparks();
  }

  // ── hover ───────────────────────────────────────────────────────────

  function nearest(wrap, event) {
    var r = wrap.getBoundingClientRect();
    var fraction = Math.min(1, Math.max(0, (event.clientX - r.left) / r.width));
    return Math.round(fraction * (view.length - 1));
  }

  function tip(wrap, index, rows) {
    var el = wrap.querySelector(".chart-tip");
    var cursor = wrap.querySelector(".chart-cursor");
    if (!el || !cursor) return;
    var left = (index / Math.max(1, view.length - 1)) * 100;
    var html = '<div class="chart-tip-time">' + clock(view[index].t) + "</div>";
    for (var i = 0; i < rows.length; i++) {
      html += '<div class="chart-tip-row ' + rows[i].cls + '">' +
              '<span class="chart-tip-key">' + rows[i].name + "</span>" +
              "<span>" + rows[i].value + "</span></div>";
    }
    el.innerHTML = html;
    el.hidden = false;
    // Inside the plot, not above it: hanging off the top edge put the tooltip
    // over the metric row, which is a different panel and still has readings
    // on it. Clamped horizontally for the same reason — near either end it
    // would otherwise sit half outside the panel.
    el.style.left = Math.min(88, Math.max(12, left)).toFixed(3) + "%";
    el.style.top = "6px";
    cursor.hidden = false;
    cursor.style.left = left.toFixed(3) + "%";
  }

  function hideTip(wrap) {
    var el = wrap.querySelector(".chart-tip");
    var cursor = wrap.querySelector(".chart-cursor");
    if (el) el.hidden = true;
    if (cursor) cursor.hidden = true;
  }

  function trackHover(chart, rowsFor) {
    if (!chart || !chart.wrap) return;
    chart.wrap.addEventListener("mousemove", function (e) {
      if (view.length === 0) return;
      var i = nearest(chart.wrap, e);
      tip(chart.wrap, i, rowsFor(view[i]));
    });
    chart.wrap.addEventListener("mouseleave", function () { hideTip(chart.wrap); });
  }

  // Per broker, in the overview's order, then the cluster's total.
  function stackRows(s, of, format) {
    var rows = [], total = 0;
    for (var i = 0; i < members.length; i++) {
      var mine = s.by && s.by[members[i].id];
      var v = mine ? of(mine) : 0;
      total += v;
      rows.push({ name: escapeHtml(members[i].id), value: format(v), cls: colourClass(members[i].id) });
    }
    rows.push({ name: "Cluster", value: format(total), cls: "chart-tip-row--total" });
    return rows;
  }

  trackHover(throughput, function (s) {
    if (members.length >= 2) {
      return stackRows(s, messages, function (v) { return compact(v) + "/s"; });
    }
    return [
      { name: "In", value: compact(s.in || 0) + "/s", cls: "chart-tip-row--in" },
      { name: "Out", value: compact(s.out || 0) + "/s", cls: "chart-tip-row--out" },
      { name: "Queued", value: compact(s.queued || 0), cls: "chart-tip-row--out" }
    ];
  });

  trackHover(clients, function (s) {
    if (members.length >= 2) {
      return stackRows(s, function (p) { return p.clients || 0; }, compact);
    }
    return [
      { name: "Clients", value: compact(s.clients || 0), cls: "chart-tip-row--out" },
      { name: "Heap", value: bytes(s.heap || 0), cls: "chart-tip-row--out" }
    ];
  });

  // ── readings and events ─────────────────────────────────────────────

  function setFields(fields) {
    if (!fields) return;
    for (var id in fields) {
      if (!Object.prototype.hasOwnProperty.call(fields, id)) continue;
      var el = document.getElementById(id);
      if (el && el.textContent !== fields[id]) el.textContent = fields[id];
    }
  }

  var eventList = document.getElementById("event-list");

  function eventNode(entry) {
    var row = document.createElement("div");
    row.className = "event";
    var when = document.createElement("span");
    when.className = "event-time";
    when.textContent = clock(entry.t);
    var what = document.createElement("span");
    // textContent throughout: a client id is whatever the client sent, and
    // the console must not be the place a client gets to put markup in.
    var subject = document.createElement("strong");
    subject.textContent = entry.subject;
    what.appendChild(subject);
    what.appendChild(document.createTextNode(" " + entry.text));
    // Which broker, when the list is the whole cluster's.
    if (entry.broker) {
      var where = document.createElement("span");
      where.className = "event-where";
      where.appendChild(document.createTextNode(" on "));
      if (palette[entry.broker] !== undefined) {
        var key = document.createElement("span");
        key.className = "broker-key " + colourClass(entry.broker);
        where.appendChild(key);
      }
      where.appendChild(document.createTextNode(entry.broker));
      what.appendChild(where);
    }
    row.appendChild(when);
    row.appendChild(what);
    return row;
  }

  function addEvent(entry) {
    if (!eventList || !entry) return;
    var empty = eventList.querySelector(".event-empty");
    if (empty) empty.remove();
    eventList.insertBefore(eventNode(entry), eventList.firstChild);
    while (eventList.children.length > 6) eventList.removeChild(eventList.lastChild);
  }

  function setEvents(entries) {
    if (!eventList || !entries || entries.length === 0) return;
    eventList.textContent = "";
    for (var i = 0; i < entries.length && i < 6; i++) {
      eventList.appendChild(eventNode(entries[i]));
    }
  }

  // ── the topic tree ──────────────────────────────────────────────────
  //
  // The twisty. Branches are wired to their rows by name — a branch row
  // carries data-branch, its leaves carry data-parent — rather than by
  // position, so this does not depend on the rows staying adjacent or in the
  // order the server happened to emit them.

  var COLLAPSED_KEY = "mqttkat.collapsed";

  function readCollapsed() {
    // A page with a stylesheet and no storage is still a working page, and
    // private windows throw on the first read rather than returning null.
    try {
      return JSON.parse(localStorage.getItem(COLLAPSED_KEY)) || [];
    } catch (e) {
      return [];
    }
  }

  function writeCollapsed(list) {
    try {
      localStorage.setItem(COLLAPSED_KEY, JSON.stringify(list));
    } catch (e) {
      // Nothing to do about it, and nothing worth breaking the page over.
    }
  }

  // Indexed once, rather than looked up with an attribute selector built from
  // the branch name. A branch is the first segment of a topic, a topic is
  // whatever a client published to, and a name with a quote in it would then
  // be a selector a client got to write. Comparing dataset values never
  // parses anything.
  function indexBranches() {
    var index = {};
    var rows = document.querySelectorAll("tr[data-parent]");
    for (var i = 0; i < rows.length; i++) {
      var name = rows[i].dataset.parent;
      (index[name] = index[name] || []).push(rows[i]);
    }
    return index;
  }

  function initTree() {
    var buttons = document.querySelectorAll("button[data-branch]");
    if (buttons.length === 0) return;
    var index = indexBranches();

    function setBranch(button, expanded) {
      var rows = index[button.dataset.branch] || [];
      for (var i = 0; i < rows.length; i++) rows[i].hidden = !expanded;
      button.setAttribute("aria-expanded", expanded ? "true" : "false");
    }

    // $SYS alone is sixty-odd rows, so a branch someone collapsed should stay
    // collapsed — this page is server-rendered and reloaded to refresh it,
    // and reopening it on every reload would make the twisty useless.
    var collapsed = readCollapsed();

    for (var j = 0; j < buttons.length; j++) {
      var button = buttons[j];
      if (collapsed.indexOf(button.dataset.branch) !== -1) setBranch(button, false);
      button.addEventListener("click", function () {
        var expanded = this.getAttribute("aria-expanded") !== "true";
        setBranch(this, expanded);
        var name = this.dataset.branch;
        var list = readCollapsed().filter(function (n) { return n !== name; });
        if (!expanded) list.push(name);
        writeCollapsed(list);
      });
    }
  }

  initTree();

  // ── the socket ──────────────────────────────────────────────────────

  function setStatus(text, live) {
    if (statusEl) statusEl.textContent = text;
    if (dotEl) dotEl.classList.toggle("is-down", !live);
  }

  function apply(message) {
    setFields(message.fields);
    if (message.event === "snapshot" || message.event === "tick") {
      // Before anything is drawn, so every swatch and band is in the
      // colours of this frame's brokers.
      palette = message.palette || {};
      setMembers(message.members);
    }
    if (message.event === "snapshot") {
      samples = message.history || [];
      intervalMs = message.interval || intervalMs;
      retentionMs = message.retention || retentionMs;
      buildWindowPicker();
      setTopics(message.topics);
      setClients(message.clients);
      setBrokers(message.brokers);
      setEvents(message.events);
      redraw();
      return;
    }
    if (message.event === "tick") {
      setTopics(message.topics);
      setClients(message.clients);
      setBrokers(message.brokers);
      setEvents(message.events);
      // A chart drawn from Rama — the cluster's, or another broker's — gets
      // the last few points every tick, since they arrive there five at a
      // time and late: only those newer than the chart already has go on.
      if (message.samples) {
        var last = samples.length ? samples[samples.length - 1].t : -Infinity;
        for (var k = 0; k < message.samples.length; k++) {
          if (message.samples[k].t > last) {
            samples.push(message.samples[k]);
            last = message.samples[k].t;
          }
        }
      }
      if (message.sample || message.samples) {
        if (message.sample) samples.push(message.sample);
        // Trimmed to what the server itself keeps, so a tab left open all day
        // holds no more than a reconnecting one would be given.
        var cap = Math.max(2, Math.round(retentionMs / intervalMs));
        while (samples.length > cap) samples.shift();
      }
      redraw();
      return;
    }
    // Rama's proxy pushed the module's counts: readings only, nothing to log.
    if (message.event === "rama") return;
    // client-connected / client-disconnected: the readings, ahead of the next
    // sample. The charts wait for the sample so their points stay evenly
    // spaced in time — a connect drawn as a point of its own would put a
    // second's worth of chart into a millisecond.
    addEvent(message.entry);
  }

  function connect() {
    var scheme = location.protocol === "https:" ? "wss:" : "ws:";
    // The page tells the socket what it is, so the server sends the one table
    // this page has somewhere to put rather than all of them to all of us.
    var path = location.pathname;
    var page = path === "/topics" ? "topics"
             : path === "/clients" ? "clients"
             : path === "/brokers" ? "brokers"
             : path === "/rama" ? "rama"
             : path.indexOf("/brokers/") === 0 ? "broker"
             : "overview";
    // One broker's page names the broker, as its path does.
    var query = page === "broker"
      ? "broker&id=" + encodeURIComponent(decodeURIComponent(path.slice("/brokers/".length)))
      : page;
    var socket = new WebSocket(scheme + "//" + location.host + "/ws?page=" + query);

    socket.onopen = function () {
      retry = 500;
      setStatus("Live", true);
    };

    socket.onmessage = function (event) {
      try {
        apply(JSON.parse(event.data));
      } catch (e) {
        // A malformed frame is not worth breaking the page over; the next one
        // carries whole values anyway.
        console.warn("mqtt-kat: could not read", event.data, e);
      }
    };

    socket.onclose = function () {
      setStatus("Reconnecting", false);
      setTimeout(connect, retry);
      // Backing off matters: a broker that is down would otherwise be hit by
      // every open tab twice a second for as long as it stays down.
      retry = Math.min(retry * 2, RETRY_MAX);
    };

    socket.onerror = function () { socket.close(); };
  }

  connect();
  window.addEventListener("resize", redraw);
})();
