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

  var HISTORY = 120;          // two minutes at one sample a second
  var RETRY_MAX = 10000;

  var samples = [];
  var retry = 500;

  var statusEl = document.querySelector(".live-text");
  var dotEl = document.querySelector(".live-dot");

  // ── formatting ──────────────────────────────────────────────────────
  // Only for things the browser invents: the axis, the tooltip and the chart
  // scale. Every figure that appears in the page proper is formatted once, on
  // the server, and arrives as a string — see mqttkat.web.state.

  function pad(n) { return String(n).padStart(2, "0"); }

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
    if (!el || samples.length === 0) return;
    var spans = el.querySelectorAll("span");
    for (var i = 0; i < spans.length; i++) {
      var at = Math.round((i / (spans.length - 1)) * (samples.length - 1));
      spans[i].textContent = clock(samples[at].t);
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
    return samples.map(function (s) { return s[name] || 0; });
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

  function redrawThroughput() {
    if (!throughput) return;
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
      var values = samples.map(sparks[i].of);
      var peak = Math.max.apply(null, values.concat([0]));
      drawSeries(svg, sparks[i].series, values, niceMax(peak));
    }
  }

  function redraw() {
    if (samples.length === 0) return;
    redrawThroughput();
    redrawClients();
    redrawSparks();
  }

  // ── hover ───────────────────────────────────────────────────────────

  function nearest(wrap, event) {
    var r = wrap.getBoundingClientRect();
    var fraction = Math.min(1, Math.max(0, (event.clientX - r.left) / r.width));
    return Math.round(fraction * (samples.length - 1));
  }

  function tip(wrap, index, rows) {
    var el = wrap.querySelector(".chart-tip");
    var cursor = wrap.querySelector(".chart-cursor");
    if (!el || !cursor) return;
    var left = (index / Math.max(1, samples.length - 1)) * 100;
    var html = '<div class="chart-tip-time">' + clock(samples[index].t) + "</div>";
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
      if (samples.length === 0) return;
      var i = nearest(chart.wrap, e);
      tip(chart.wrap, i, rowsFor(samples[i]));
    });
    chart.wrap.addEventListener("mouseleave", function () { hideTip(chart.wrap); });
  }

  trackHover(throughput, function (s) {
    return [
      { name: "In", value: compact(s.in || 0) + "/s", cls: "chart-tip-row--in" },
      { name: "Out", value: compact(s.out || 0) + "/s", cls: "chart-tip-row--out" },
      { name: "Queued", value: compact(s.queued || 0), cls: "chart-tip-row--out" }
    ];
  });

  trackHover(clients, function (s) {
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

  // ── the socket ──────────────────────────────────────────────────────

  function setStatus(text, live) {
    if (statusEl) statusEl.textContent = text;
    if (dotEl) dotEl.classList.toggle("is-down", !live);
  }

  function apply(message) {
    setFields(message.fields);
    if (message.event === "snapshot") {
      samples = message.history || [];
      setEvents(message.events);
      redraw();
      return;
    }
    if (message.event === "tick") {
      if (message.sample) {
        samples.push(message.sample);
        while (samples.length > HISTORY) samples.shift();
      }
      redraw();
      return;
    }
    // client-connected / client-disconnected: the readings, ahead of the next
    // sample. The charts wait for the sample so their points stay evenly
    // spaced in time — a connect drawn as a point of its own would put a
    // second's worth of chart into a millisecond.
    addEvent(message.entry);
  }

  function connect() {
    var scheme = location.protocol === "https:" ? "wss:" : "ws:";
    var socket = new WebSocket(scheme + "//" + location.host + "/ws");

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
