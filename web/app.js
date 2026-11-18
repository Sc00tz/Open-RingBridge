// Screens. Each render* function maps to one Android screen, and takes its data from one API call.
import { el, lineChart, columns, hypnogram, segmented, table, clock, gauge } from "/web/charts.js";

const view = document.getElementById("view");
const title = document.getElementById("title");
const syncBtn = document.getElementById("sync");
const syncLine = document.getElementById("syncline");
const css = name => getComputedStyle(document.documentElement).getPropertyValue(name).trim();

const STAGES = [
  { key: "awake", name: "Awake", color: "var(--s4)", dark: true },
  { key: "rem", name: "REM", color: "var(--s2)" },
  { key: "light", name: "Light", color: "var(--s3)" },
  { key: "deep", name: "Deep", color: "var(--s1)" },
];
const STATUS = { Optimal: "--best", Good: "--good", Average: "--warning", Poor: "--critical" };
const BANDS = [
  { name: "Poor", from: 0, to: 60, color: "var(--critical)" },
  { name: "Average", from: 60, to: 70, color: "var(--warning)" },
  { name: "Good", from: 70, to: 85, color: "var(--good)" },
  { name: "Optimal", from: 85, to: 100, color: "var(--best)" },
];
const METRICS = {
  heart_rate: { label: "Heart rate", unit: "BPM" },
  spo2: { label: "Blood oxygen", unit: "%" },
  hrv: { label: "HRV", unit: "ms" },
  respiration: { label: "Breathing", unit: "/min" },
  stress: { label: "Stress", unit: "", digits: 1 },
  glucose: { label: "Glucose", unit: "mmol/L", digits: 1, estimate: true },
};
const state = { tab: "today", date: null, night: null, range: 0, device: null, job: null };

// The hub signs people in; the Mac bench doesn't. A 401 from the hub means the session ended.
const send = async (path, options = {}) => {
  const r = await fetch(path, options);
  if (r.status === 401 && state.caps?.mode === "hub") { location.href = "/login"; throw new Error("Signed out"); }
  if (!r.ok) throw new Error(await r.text());
  return r.json();
};
const api = path => send(path);
const post = path => send(path, { method: "POST" });
const postJSON = (path, body, method = "POST") => send(path, { method, headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });

// ----- formatting. Ring times are wall-clock text, so parse them without shifting the time zone.
const parseLocal = s => new Date(s.slice(0, 10) + "T" + (s.slice(11) || "12:00:00"));
const longDate = s => parseLocal(s).toLocaleDateString([], { weekday: "short", month: "short", day: "numeric" });
const shortDate = s => parseLocal(s).toLocaleDateString([], { month: "numeric", day: "numeric" });
const timeOf = s => parseLocal(s).toLocaleTimeString([], { hour: "numeric", minute: "2-digit" });
const hm = sec => { const m = Math.round(sec / 60); return `${Math.floor(m / 60)}h ${String(m % 60).padStart(2, "0")}m`; };
const ago = ts => {
  const min = Math.round((Date.now() / 1000 - ts) / 60);
  if (min < 1) return "just now";
  if (min < 60) return `${min} min ago`;
  if (min < 1440) return `${Math.round(min / 60)} h ago`;
  if (Math.round(min / 1440) === 1) return "1 day ago";
  return `${Math.round(min / 1440)} days ago`;
};
const fmt = (v, digits = 0) => Number(v).toLocaleString(undefined, { minimumFractionDigits: digits, maximumFractionDigits: digits });

// ----- shared pieces
const card = (heading, sub, ...kids) => el("section", { class: "card" }, heading ? el("h2", {}, heading) : null, sub ? el("p", { class: "sub" }, sub) : null, ...kids);
const tag = text => el("span", { class: "tag" }, text);
const empty = (heading, text) => card(heading, null, el("p", { class: "note", style: "margin-top:4px" }, text));
function tile({ label, value, unit, when, estimate, none = "Not measured" }) {
  return el("div", { class: "tile" },
    el("div", { class: "label" }, label, estimate ? tag("Estimate") : null),
    value == null ? el("div", { class: "val none" }, "–") : el("div", { class: "val" }, value, unit ? el("small", {}, unit) : null),
    el("div", { class: "when" }, value == null ? none : (when || " ")));
}
function stepper(label, prev, next, go) {
  return el("div", { class: "stepper" },
    el("button", { class: "iconbtn", "aria-label": "Earlier", disabled: prev ? null : "", onclick: () => go(prev) }, "‹"),
    el("strong", {}, label),
    el("button", { class: "iconbtn", "aria-label": "Later", disabled: next ? null : "", onclick: () => go(next) }, "›"));
}
function statusLabel(band) {
  return el("span", { class: "status" }, el("i", { style: `background:var(${STATUS[band]})` }), band);
}
const pointRows = (points, digits = 0) => points.map(p => [timeOf(p.local), fmt(p.v, digits)]);
// A full day when the data fills it; otherwise zoom in, so a few readings stay readable.
const dayBounds = points => {
  const first = points[0].t, last = points[points.length - 1].t;
  if (last - first >= 12 * 3600) {
    const d = new Date(first * 1000); d.setHours(0, 0, 0, 0);
    const min = d.getTime() / 1000;
    return { type: "time", min, max: min + 86400 };
  }
  const pad = Math.max(300, (last - first) * 0.05);
  return { type: "time", min: first - pad, max: last + pad };
};

const factorRows = factors => el("div", { class: "rows" }, factors.map(f => el("div", { class: "factor" },
  el("div", { class: "top" }, el("span", {}, f.name), el("b", {}, `${fmt(f.earned)} of ${f.weight}`)),
  el("div", { class: "meter" }, el("i", { style: `width:${f.earned / f.weight * 100}%` })),
  el("div", { class: "why" }, f.detail))));

function readinessCard(r) {
  if (!r.ready) return card("Readiness", null, gauge({ value: null, bands: BANDS, name: "Readiness" }), el("p", { class: "note", style: "text-align:center" }, r.reason));
  return card("Readiness", null,
    gauge({ value: r.score, label: r.band, bands: BANDS, name: "Readiness" }),
    el("div", { class: "gaugelabel" }, statusLabel(r.band)),
    r.notes.length ? el("div", { class: "notice", style: "margin-top:12px" }, el("strong", {}, "Limited data. "), r.notes.join(" ")) : null,
    el("details", { class: "table" }, el("summary", {}, "How this was worked out"),
      el("p", { class: "note" }, "Our own weights, using the factors the official app names. Updated once a day, after sleep. The ring has no usable temperature, so that factor is left out."),
      factorRows(r.factors)));
}

const workoutLine = w => [w.steps != null ? `${fmt(w.steps)} steps` : null, w.hr_avg ? `${fmt(w.hr_avg)} BPM average` : null].filter(Boolean).join(", ");
function workoutRow(w) {
  const seconds = (w.end_ts ?? w.start_ts) - w.start_ts;
  const kv = (a, b) => el("div", { class: "kv" }, el("span", {}, a), el("span", {}, b));
  const rows = [];
  if (w.steps != null) rows.push(kv("Steps", fmt(w.steps)));
  if (w.distance_m != null) rows.push(kv("Distance", `${fmt(w.distance_m / 1000, 2)} km`));
  if (w.kcal != null) rows.push(kv("Calories", `${fmt(w.kcal)} kcal`));
  if (w.hr_avg) rows.push(kv("Heart rate", `${fmt(w.hr_avg)} average, ${fmt(w.hr_min)}–${fmt(w.hr_max)}`));
  return el("details", { class: "card workout" },
    el("summary", {}, el("b", {}, w.label), el("span", {}, `${longDate(w.start_local)}, ${timeOf(w.start_local)} · ${hm(seconds)}`)),
    w.status === "lost" ? el("div", { class: "notice", style: "margin-top:8px" }, "The connection dropped before this ended, so it may be incomplete.") : null,
    el("div", { style: "margin-top:4px" }, rows),
    w.heart.length > 1 ? lineChart({ series: [{ name: "Heart rate", color: "var(--s1)", points: w.heart }], x: { type: "time", min: w.start_ts, max: w.end_ts }, unit: "BPM" }) : null,
    w.heart.length > 1 ? table(["Time", "BPM"], pointRows(w.heart)) : null);
}

// ----- Ring clock corrections. A flat battery stops the ring's clock, so it stamps later readings too early.
const span = (a, b) => `${longDate(a)} ${timeOf(a)} – ${longDate(b)} ${timeOf(b)}`;
const hoursText = sec => `${fmt(sec / 3600, sec % 3600 ? 1 : 0)} hours`;
async function decideClock(id, action) {
  try { await post(`/api/clock/${id}/${action}`); } catch (e) { alert(String(e.message || e)); }
  state.date = null;
  render();
}
function clockProposal(f) {
  return el("section", { class: "card" },
    el("h2", {}, "The ring's clock was behind"),
    el("p", { class: "sub" }, `By about ${hoursText(f.offset)}. This usually means its battery ran flat: the clock stops while it's dead, then carries on from where it stopped.`),
    el("div", { style: "margin-top:8px" },
      el("div", { class: "kv" }, el("span", {}, "Dated now"), el("span", {}, span(f.from_local, f.to_local))),
      el("div", { class: "kv" }, el("span", {}, "Likely real time"), el("span", {}, span(f.moved_from_local, f.moved_to_local)))),
    el("p", { class: "note", style: "margin-top:8px" }, "If you instead took the ring off and put it back on just before syncing, choose Leave them. Moving can be undone from the Ring screen."),
    el("div", { class: "seg", style: "margin-top:12px" },
      el("button", { class: "btn", onclick: () => decideClock(f.id, "apply") }, "Move them"),
      el("button", { class: "btn text", onclick: () => decideClock(f.id, "dismiss") }, "Leave them")));
}

// ----- Today
async function renderToday() {
  const proposals = (await api("/api/clock")).fixes.filter(f => f.status === "proposed").map(clockProposal);
  if (!state.date) {
    const days = await api("/api/days");
    state.date = days.default;
    state.today = days.today;
  }
  if (!state.date) return [empty("No data yet", "Press Sync to read the ring.")];
  const d = await api(`/api/day/${state.date}`);
  const { latest, stats, series, activity } = d;
  const out = [...proposals, stepper(longDate(d.date), d.prev, d.next, go => { state.date = go; render(); })];
  if (!d.next && state.today && d.date !== state.today) {
    const newest = Object.values(latest).map(p => p.local).sort().pop();
    out.push(el("div", { class: "notice" }, el("strong", {}, "No newer readings. "),
      `This is the latest day with data${newest ? `; the last reading was at ${timeOf(newest)}` : ""}. `,
      "If you've worn the ring since then, check it's charged and press Sync again."));
  }
  out.push(readinessCard(d.readiness));

  const hr = latest.heart_rate;
  if (hr) {
    out.push(card(null, null, el("div", { class: "hero" },
      el("div", {}, el("div", { class: "sub" }, "Heart rate, latest"),
        el("div", { class: "value" }, fmt(hr.v), el("span", { class: "unit" }, "BPM")),
        el("div", { class: "sub" }, `at ${timeOf(hr.local)}`)),
      stats.heart_rate.n > 1 ? el("div", { class: "note", style: "text-align:right" },
        el("div", {}, `Range ${fmt(stats.heart_rate.min)}–${fmt(stats.heart_rate.max)}`),
        el("div", {}, `Average ${fmt(stats.heart_rate.avg)}`)) : null),
      lineChart({ series: [{ name: "Heart rate", color: "var(--s1)", points: series.heart_rate }], x: dayBounds(series.heart_rate), unit: "BPM" }),
      table(["Time", "BPM"], pointRows(series.heart_rate))));
  }

  const sbp = latest.bp_systolic, dbp = latest.bp_diastolic;
  const tiles = [
    tile({ label: "Blood pressure", estimate: true, value: sbp && dbp ? `${fmt(sbp.v)}/${fmt(dbp.v)}` : null, unit: "mmHg", when: sbp && `at ${timeOf(sbp.local)}` }),
    tile({ label: "Steps", value: activity.bins.length ? fmt(activity.steps) : null, when: activity.bins.length ? `${fmt(activity.distance_m / 1000, 1)} km, ${fmt(activity.kcal)} kcal` : null, none: "None recorded" }),
  ];
  for (const key of ["spo2", "hrv", "respiration", "stress", "glucose"]) {
    const m = METRICS[key], p = latest[key];
    tiles.push(tile({ label: m.label, estimate: m.estimate, value: p ? fmt(p.v, m.digits) : null, unit: m.unit, when: p && `at ${timeOf(p.local)}` }));
  }
  out.push(el("div", { class: "tiles" }, tiles));

  if (d.night) {
    out.push(el("a", { class: "card link", href: "#sleep", onclick: () => { state.night = d.night.id; } },
      el("h2", {}, "Sleep"), el("div", { class: "hero" },
        el("div", {}, el("div", { class: "val", style: "font-size:26px;font-weight:600" }, hm(d.night.asleep)),
          el("div", { class: "sub" }, `${timeOf(d.night.start_local)} – ${timeOf(d.night.end_local)}`)),
        el("div", { style: "text-align:right" }, el("div", { style: "font-size:26px;font-weight:600" }, d.night.score.score), statusLabel(d.night.score.band)))));
  } else if (d.latest_night) {
    out.push(el("a", { class: "card link", href: "#sleep", onclick: () => { state.night = d.latest_night; } },
      el("h2", {}, "Sleep"), el("p", { class: "sub" }, `None recorded for this date. Latest night: ${longDate(d.latest_night)}.`)));
  }

  for (const w of d.workouts) out.push(workoutRow(w));

  if (activity.bins.length) {
    const times = activity.bins.map(a => a.start_local);
    out.push(card("Steps by half hour", null, columns({ labels: times.map(t => timeOf(t).replace(":00", "")), stacks: [{ name: "Steps", color: "var(--s1)", values: activity.bins.map(a => a.steps) }], unit: "steps" }),
      table(["Start", "Steps", "Meters", "kcal"], activity.bins.map(a => [timeOf(a.start_local), fmt(a.steps), fmt(a.distance_m), fmt(a.kcal)]))));
  }
  if (series.bp_systolic?.length > 1 && series.bp_diastolic) {
    out.push(card("Blood pressure", "The ring's estimate, not a cuff reading.",
      lineChart({ series: [{ name: "Systolic", color: "var(--s1)", points: series.bp_systolic }, { name: "Diastolic", color: "var(--s2)", points: series.bp_diastolic }], x: dayBounds(series.bp_systolic), unit: "mmHg" }),
      table(["Time", "Systolic", "Diastolic"], series.bp_systolic.map((p, i) => [timeOf(p.local), fmt(p.v), series.bp_diastolic[i] ? fmt(series.bp_diastolic[i].v) : null]))));
  }
  for (const key of ["spo2", "hrv"]) {
    if (series[key]?.length > 1) {
      const m = METRICS[key];
      out.push(card(m.label, null, lineChart({ series: [{ name: m.label, color: "var(--s1)", points: series[key] }], x: dayBounds(series[key]), unit: m.unit }), table(["Time", m.unit], pointRows(series[key]))));
    }
  }
  if (d.derived.length) {
    out.push(card("Worked out from your data", "Calculated here by combining readings. Not from the ring or the official app.",
      el("div", { style: "margin-top:8px" }, d.derived.map(i => el("div", { class: "derived" },
        el("div", { class: "top" }, el("span", {}, i.name), el("b", {}, `${fmt(i.value, i.unit === "m" ? 2 : 0)} ${i.unit}`)),
        el("div", { class: "why" }, `${i.formula}: ${i.inputs}.${i.caveat ? " " + i.caveat : ""}`))))));
  }
  return out;
}

// ----- Sleep
async function renderSleep() {
  const q = state.night ? `?night=${state.night}` : "";
  const d = await api(`/api/sleep${q}`);
  if (!d.night) return [empty("No sleep yet", "Wear the ring overnight, then press Sync.")];
  const n = d.night, v = n.vitals, s = n.score;
  state.night = n.id;
  const out = [stepper(`Woke ${longDate(n.id)}`, d.prev, d.next, go => { state.night = go; render(); })];
  out.push(card(null, null,
    el("div", { class: "hero" },
      el("div", {}, el("div", { class: "sub" }, "Sleep score"), el("div", { class: "value" }, s.score), el("div", { style: "margin-top:4px" }, statusLabel(s.band))),
      el("div", { class: "note", style: "text-align:right" }, el("div", { style: "font-size:20px;font-weight:600;color:var(--ink)" }, hm(n.asleep)), el("div", {}, "asleep"), el("div", {}, `${timeOf(n.start_local)} – ${timeOf(n.end_local)}`))),
    s.notes.length ? el("div", { class: "notice", style: "margin-top:12px" }, el("strong", {}, "Limited data. "), s.notes.join(" ")) : null));

  out.push(card("Stages", `${Math.round((n.efficiency ?? 0) * 100)}% of the night asleep`,
    segmented(STAGES.slice().reverse().map(st => ({ name: st.name, value: n.seconds[st.key], color: st.color, darkText: st.dark })), n.in_bed),
    hypnogram({ segments: n.segments, start: n.start_ts, end: n.end_ts, stages: STAGES }),
    table(["Start", "Stage", "Minutes"], n.segments.map(g => [timeOf(g.start_local), STAGES.find(x => x.key === g.stage)?.name ?? g.stage, fmt(g.seconds / 60)]), "Show every segment")));

  out.push(card("How the score was worked out", "Our own formula, so every point can be checked. The official app's score isn't copied, and may differ.",
    factorRows(s.factors)));

  const items = [];
  if (v.resting_hr) items.push(tile({ label: "Resting heart rate", value: fmt(v.resting_hr), unit: "BPM", when: `Lowest ${fmt(v.hr_min)}, average ${fmt(v.hr_avg)}` }));
  if (v.spo2_avg) items.push(tile({ label: "Blood oxygen", value: fmt(v.spo2_avg), unit: "%", when: `Lowest ${fmt(v.spo2_min)}%` }));
  if (v.hrv_avg) items.push(tile({ label: "HRV", value: fmt(v.hrv_avg), unit: "ms", when: "Average overnight" }));
  if (v.resp_avg) items.push(tile({ label: "Breathing", value: fmt(v.resp_avg), unit: "/min", when: "Average overnight" }));
  if (items.length) out.push(el("div", { class: "tiles" }, items));
  else out.push(empty("Overnight vitals", "The ring stored no heart rate or oxygen readings during this session."));

  const hrPts = d.series.heart_rate, oxy = d.series.spo2;
  const bounds = { type: "time", min: n.start_ts, max: n.end_ts };
  if (hrPts?.length > 1) out.push(card("Heart rate overnight", v.hr_low_at_ts ? `Lowest around ${clock(v.hr_low_at_ts)}, ${fmt(v.hr_low_hours_before_wake, 1)} h before waking` : null,
    lineChart({ series: [{ name: "Heart rate", color: "var(--s1)", points: hrPts }], x: bounds, unit: "BPM" }), table(["Time", "BPM"], pointRows(hrPts))));
  if (oxy?.length > 1) out.push(card("Blood oxygen overnight", "One reading about every 5 minutes. This isn't a sleep apnea test.",
    lineChart({ series: [{ name: "Blood oxygen", color: "var(--s1)", points: oxy }], x: bounds, unit: "%" }), table(["Time", "%"], pointRows(oxy))));

  const kv = (a, b) => el("div", { class: "kv" }, el("span", {}, a), el("span", {}, b));
  const c = d.consistency, r = d.recovery;
  const longer = [kv("Steps during this session", fmt(n.steps_during))];
  if (n.dip) longer.push(kv("Heart rate dip", `${fmt(n.dip.dip * 100)}% below daytime`));
  if (c.ready) longer.push(kv(`Average sleep, last ${c.nights} nights`, hm(c.avg_asleep)), kv("Sleep debt against 8 h", hm(c.debt_seconds)),
    kv("Bedtime varies by", `±${fmt(c.bedtime_spread_min)} min`), kv("Wake time varies by", `±${fmt(c.wake_spread_min)} min`));
  if (r.ready) {
    longer.push(kv("Resting heart rate vs your usual", `${fmt(r.resting_hr)} vs ${fmt(r.resting_hr_usual)} BPM`));
    if (r.hrv) longer.push(kv("HRV vs your usual", `${fmt(r.hrv)} vs ${fmt(r.hrv_usual)} ms`));
  } else longer.push(kv("Recovery comparison", `${r.have} of ${r.needed} earlier nights`));
  out.push(card("Worked out from your data", null, el("div", { style: "margin-top:4px" }, longer)));
  return out;
}

// ----- Trends
async function renderTrends() {
  const { rows } = await api(`/api/trends?days=${state.range}`);
  const filter = el("div", { class: "filters", role: "group", "aria-label": "Date range" },
    [[7, "7 days"], [30, "30 days"], [90, "90 days"], [0, "All"]].map(([days, name]) =>
      el("button", { class: "chip", "aria-pressed": String(state.range === days), onclick: () => { state.range = days; render(); } }, name)));
  const out = [filter];
  if (!rows.length) return [...out, empty("Nothing in this range", "The ring has no data for these dates. Try All.")];
  out.push(el("p", { class: "note" }, "Only days with data are shown, so a gap in time doesn't appear as a gap in the chart."));

  const labelled = key => rows.filter(r => r[key] != null);
  const sleepRows = labelled("asleep");
  if (sleepRows.length) {
    const h = sec => sec / 3600;
    out.push(card("Sleep", "Hours per night, by the date you woke", columns({ labels: sleepRows.map(r => shortDate(r.date)), unit: "h", format: v => fmt(v, 1),
      stacks: [...STAGES].reverse().map(st => ({ name: st.name, color: st.color, values: sleepRows.map(r => h(r[st.key] || 0)) })) }),
      table(["Woke", "Score", "Asleep", "Deep", "REM", "Light"], sleepRows.map(r => [shortDate(r.date), r.sleep_score, hm(r.asleep), hm(r.deep), hm(r.rem), hm(r.light)]))));
  }
  const stepRows = labelled("steps");
  if (stepRows.length) out.push(card("Steps", null, columns({ labels: stepRows.map(r => shortDate(r.date)), stacks: [{ name: "Steps", color: "var(--s1)", values: stepRows.map(r => r.steps) }], unit: "steps" }),
    table(["Date", "Steps", "km", "kcal"], stepRows.map(r => [shortDate(r.date), fmt(r.steps), fmt(r.distance_m / 1000, 1), fmt(r.kcal)]))));

  const line = (name, key, unit, digits = 0, caption = null, floor = null) => {
    const list = labelled(key);
    if (!list.length) return;
    const labels = list.map(r => shortDate(r.date));
    out.push(card(name, caption, lineChart({ series: [{ name, color: "var(--s1)", points: list.map((r, i) => ({ t: i, v: r[key] })) }], x: { type: "index", labels }, unit, format: v => fmt(v, digits), floor }),
      table(["Date", unit || "Value"], list.map((r, i) => [labels[i], fmt(r[key], digits)]))));
  };
  line("Readiness", "readiness", "", 0, "Our own daily score. It may change as your baseline builds.");
  line("Sleep score", "sleep_score", "");
  line("Heart rate, daily average", "heart_rate", "BPM");
  line("Resting heart rate", "resting_hr", "BPM");
  const bp = labelled("bp_systolic").filter(r => r.bp_diastolic != null);
  if (bp.length) {
    const labels = bp.map(r => shortDate(r.date));
    out.push(card("Blood pressure, daily average", "The ring's estimate, not a cuff reading.", lineChart({ series: [
      { name: "Systolic", color: "var(--s1)", points: bp.map((r, i) => ({ t: i, v: r.bp_systolic })) },
      { name: "Diastolic", color: "var(--s2)", points: bp.map((r, i) => ({ t: i, v: r.bp_diastolic })) }], x: { type: "index", labels }, unit: "mmHg" }),
      table(["Date", "Systolic", "Diastolic"], bp.map((r, i) => [labels[i], fmt(r.bp_systolic), fmt(r.bp_diastolic)]))));
  }
  line("Blood oxygen, daily average", "spo2", "%");
  line("HRV, daily average", "hrv", "ms");
  line("Breathing, daily average", "respiration", "/min");
  line("Stress, daily average", "stress", "", 1, "The ring's own index. Its scale isn't documented.");
  line("Glucose, daily average", "glucose", "mmol/L", 1, "The ring's estimate. The unit is unconfirmed.");
  return out;
}

// ----- Record: workouts and spot measurements
const clockText = sec => {
  const s = Math.max(0, Math.floor(sec));
  return `${Math.floor(s / 3600)}:${String(Math.floor(s / 60) % 60).padStart(2, "0")}:${String(s % 60).padStart(2, "0")}`;
};
function workoutCard(kinds, job) {
  const running = job?.state === "running";
  const mine = job && kinds[job.kind];
  if (mine && running) {
    const t = job.totals ?? {};
    return [
      card(job.label, job.message,
        el("div", { class: "liverow", style: "margin-top:8px" },
          el("div", { class: "timer" }, clockText(Date.now() / 1000 - job.started_ts)),
          el("button", { class: "btn", onclick: () => post("/api/cancel") }, "Stop")),
        job.points?.length > 1 ? lineChart({ series: [{ name: "Heart rate", color: "var(--s1)", points: job.points }], x: { type: "time", min: job.points[0].t, max: job.points[job.points.length - 1].t }, unit: "BPM" }) : null),
      el("div", { class: "tiles" },
        tile({ label: "Heart rate", value: job.heart ?? null, unit: "BPM", none: "Waiting for the ring" }),
        tile({ label: "Steps", value: t.steps != null ? fmt(t.steps) : null, none: "Waiting for the ring" }),
        tile({ label: "Distance", value: t.distance_m != null ? fmt(t.distance_m / 1000, 2) : null, unit: "km", none: "Waiting for the ring" }),
        tile({ label: "Calories", value: t.kcal != null ? fmt(t.kcal) : null, unit: "kcal", none: "Waiting for the ring" })),
      job.kind === "bike" ? el("p", { class: "note" }, "The ring works out distance from steps, so biking distance won't mean much until the Android app can use GPS.") : null,
    ];
  }
  return [card("Start a workout", "Stay within Bluetooth range of this computer. The Android app will remove that limit.",
    el("div", { class: "kinds" }, Object.entries(kinds).map(([kind, label]) =>
      el("button", { class: "btn tonal", disabled: running ? "" : null, onclick: () => startJob(`/api/workout/${kind}`) }, label))),
    mine && job.state === "error" ? el("p", { class: "note", style: "margin-top:8px;color:var(--critical)" }, job.message) : null)];
}

const MEASURE_NOTES = { blood: "Estimate", glucose: "Estimate" };
function formatValues(values) {
  if (values.bp_systolic) return `${values.bp_systolic}/${values.bp_diastolic} mmHg`;
  const [k, v] = Object.entries(values)[0] ?? [];
  if (!k) return "";
  const m = METRICS[k];
  return `${fmt(v, m.digits)} ${m.unit}`.trim();
}
async function renderMeasure() {
  const [d, w] = await Promise.all([api("/api/device"), api("/api/workouts")]);
  state.device = d;
  const job = state.job;
  const running = job?.state === "running";
  const out = [...workoutCard(w.kinds, job), el("h2", { class: "sectionhead" }, "Spot measurement"),
    el("div", { class: "notice" }, "Put the ring on your finger, off the charger, and keep still. The ring handles one measurement at a time.")];
  for (const [kind, m] of Object.entries(d.measures)) {
    const mine = job && job.kind === kind;
    const row = el("section", { class: "card measure" },
      el("div", {}, el("div", { class: "name" }, m.label, MEASURE_NOTES[kind] ? tag(MEASURE_NOTES[kind]) : null), el("div", { class: "sub" }, `About ${m.seconds} seconds`)),
      mine && running
        ? el("button", { class: "btn tonal", onclick: () => post("/api/cancel") }, "Stop")
        : el("button", { class: "btn tonal", disabled: running ? "" : null, onclick: () => startJob(`/api/measure/${kind}`) }, "Start"));
    if (mine) {
      if (running) {
        const elapsed = Date.now() / 1000 - job.started_ts;
        row.append(el("div", { class: "live" }, Object.keys(job.values).length ? formatValues(job.values) : job.message + "…"));
        row.append(el("div", { class: "progress" }, el("i", { style: `width:${Math.min(100, elapsed / m.seconds * 100)}%` })));
      } else if (job.state === "done" && job.result?.values) {
        row.append(el("div", { class: "live" }, formatValues(job.result.values)));
        row.append(el("p", { class: "note", style: "grid-column:1/-1" }, "Saved."));
      } else if (job.state === "error") {
        row.append(el("p", { class: "note", style: "grid-column:1/-1;color:var(--critical)" }, job.message));
      } else if (job.state === "cancelled") {
        row.append(el("p", { class: "note", style: "grid-column:1/-1" }, "Stopped. Nothing saved."));
      }
    }
    out.push(row);
  }
  if (w.workouts.length) {
    out.push(el("h2", { class: "sectionhead" }, "Past workouts"), ...w.workouts.map(workoutRow));
  }
  return out;
}

// ----- Ring
async function renderDevice() {
  const [d, clockData] = await Promise.all([api("/api/device"), api("/api/clock")]);
  state.device = d;
  const s = d.status, kv = (a, b) => el("div", { class: "kv" }, el("span", {}, a), el("span", {}, b));
  const theme = (() => { try { return localStorage.getItem("theme") || "system"; } catch { return "system"; } })();
  const applied = clockData.fixes.filter(f => f.status === "applied");
  const hub = state.caps?.mode === "hub";
  return [
    ...clockData.fixes.filter(f => f.status === "proposed").map(clockProposal),
    applied.length ? card("Clock corrections", "Readings moved because the ring's clock was behind when it stamped them.",
      applied.map(f => el("div", { style: "margin-top:10px" },
        kv(`${hoursText(f.offset)} later`, span(f.moved_from_local, f.moved_to_local)),
        el("button", { class: "btn text", onclick: () => decideClock(f.id, "undo") }, "Undo")))) : null,
    card(d.ring_name || "Ring", s ? `Last read ${ago(s.updated_ts)}${hub ? " by your phone" : ""}` : (hub ? "Not uploaded by the app yet" : "Not read by this dashboard yet"),
      el("div", { style: "margin-top:8px" }, s ? [
        s.battery != null ? kv("Battery", `${s.battery}%${s.charging ? ", charging" : ""}`) : null,
        s.firmware ? kv("Firmware", s.firmware) : null,
        s.device_id != null ? kv("Device ID", String(s.device_id)) : null,
      ].filter(Boolean) : kv("Battery", hub ? "Sync from the Android app" : "Press Sync to read"))),
    hub ? null : card("What syncing does", null, el("div", { style: "margin-top:8px" },
      kv("Sets the ring's clock", "Yes"), kv("Reads and backs up raw history", "Yes"),
      kv("Acknowledges history", "No"), kv("Deletes anything from the ring", "No")),
      el("p", { class: "note", style: "margin-top:8px" }, "Close the official app before syncing. The ring appears to allow one connection at a time.")),
    ...(hub ? await accountCards() : []),
    card("Data sources", "Other sources, like a bed or CPAP through Home Assistant, can be added later without changing how data is stored.",
      el("div", { style: "margin-top:8px" }, kv("Ring", d.last_sync ? `Last sync ${ago(d.last_sync.ts)}` : "Not synced"))),
    card("Appearance", null, el("div", { class: "seg" }, ["system", "light", "dark"].map(t =>
      el("button", { class: "chip", "aria-pressed": String(theme === t), onclick: () => { setTheme(t); render(); } }, t[0].toUpperCase() + t.slice(1))))),
  ].filter(Boolean);
}
// ----- Hub accounts (the Ring screen, when served by the hub)
function field(label, attrs) {
  const input = el("input", attrs);
  return [el("label", { class: "field" }, label, input), input];
}
function form(fields, button, submit) {
  const msg = el("div", { class: "formerr", role: "alert" });
  const inputs = fields.map(([, input]) => input);
  const go = async e => {
    e.preventDefault();
    msg.className = "formerr"; msg.textContent = "";
    try {
      const ok = await submit(...inputs.map(i => i.type === "checkbox" ? i.checked : i.value));
      if (ok) { msg.className = "formok"; msg.textContent = ok; inputs.forEach(i => { if (i.type !== "checkbox") i.value = ""; }); }
    } catch (err) { msg.textContent = String(err.message || err); }
  };
  return el("form", { onsubmit: go }, fields.map(([label]) => label), el("button", { class: "btn", type: "submit", style: "margin-top:12px" }, button), msg);
}
async function accountCards() {
  const a = await api("/api/account");
  const kv = (x, y) => el("div", { class: "kv" }, el("span", {}, x), el("span", {}, y));
  const cards = [
    card("Account", `Signed in as ${a.user.username}${a.user.is_admin ? " (admin)" : ""}`,
      el("button", { class: "btn tonal", style: "margin-top:12px", onclick: async () => { await post("/api/logout"); location.href = "/login"; } }, "Sign out")),
    card("Phones", "Apps signed in to this account. Remove one you no longer use.",
      a.apps.length ? a.apps.map(p => el("div", { class: "kv" }, el("span", {}, `${p.name} · last used ${ago(p.last_used_ts || p.created_ts)}`),
        el("button", { class: "btn text", onclick: async () => { if (confirm(`Sign out ${p.name}?`)) { await send(`/api/account/apps/${p.id}`, { method: "DELETE" }); render(); } } }, "Remove")))
        : el("p", { class: "note", style: "margin-top:8px" }, "None yet. In the Android app, open Ring > Server and sign in with this hub's address.")),
    card("Change password", "This signs out your other browsers and phones.",
      form([field("Current password", { type: "password", autocomplete: "current-password", required: "" }),
            field("New password (10+ characters)", { type: "password", autocomplete: "new-password", minlength: "10", required: "" })],
        "Change password", async (current, next) => { await postJSON("/api/account/password", { current, new: next }); return "Password changed."; })),
  ];
  if (a.users) {
    cards.push(card("People", "Each account has its own data.",
      a.users.map(u => kv(u.username, u.is_admin ? "Admin" : "")),
      form([field("Username", { autocomplete: "off", required: "" }),
            field("Password (10+ characters)", { type: "password", autocomplete: "new-password", minlength: "10", required: "" }),
            [el("label", { class: "check" }, el("input", { type: "checkbox" }), "Admin"), null]].map(([l, i]) => [l, i ?? l.querySelector("input")]),
        "Add person", async (username, password, is_admin) => { await postJSON("/api/users", { username, password, is_admin }); setTimeout(render, 600); return `Added ${username}.`; })));
  }
  return cards;
}

function setTheme(t) {
  try { localStorage.setItem("theme", t); } catch {}
  if (t === "system") document.documentElement.removeAttribute("data-theme");
  else document.documentElement.dataset.theme = t;
}

// ----- ring jobs and routing
let polling = null;
async function startJob(path) {
  try {
    state.job = await post(path);
  } catch (e) {
    syncLine.textContent = String(e.message || e);
    return;
  }
  updateSyncLine();
  if (state.tab === "measure") render();
  polling ??= setInterval(pollJob, 1000);
}
async function pollJob() {
  state.job = await api("/api/job");
  updateSyncLine();
  const running = state.job?.state === "running";
  if (state.tab === "measure") render(true);
  if (!running) {
    clearInterval(polling); polling = null;
    if (state.job?.state === "done") { state.date = null; render(true); }
  }
}
function updateSyncLine() {
  const job = state.job;
  const running = job?.state === "running";
  syncBtn.disabled = running;
  syncLine.className = "syncline";
  if (running) { syncLine.textContent = `${job.label}: ${job.message}…`; return; }
  if (job?.state === "error") { syncLine.className = "syncline err"; syncLine.textContent = job.message; return; }
  const last = state.device?.last_sync;
  if (job?.kind === "sync" && job.state === "done") {
    const failed = job.result?.failed ?? [];
    const newest = job.result?.newest_before_ts;
    if (failed.length) syncLine.textContent = `Synced, but ${failed.join(", ")} failed the checksum and wasn't imported.`;
    else if (job.result?.clock_proposals) syncLine.textContent = "Synced. The ring's clock looks like it was behind; see Today.";
    else if (newest && Date.now() / 1000 - newest > 30 * 60)
      syncLine.textContent = `Synced. The newest reading was from ${ago(newest)}. If you've been wearing the ring, sync again in about 5 minutes so its clock can be checked.`;
    else syncLine.textContent = "Synced just now.";
  } else if (state.caps && !state.caps.ring) {
    syncLine.textContent = last ? `Last upload from your phone ${ago(last.ts)}` : "Nothing uploaded yet. Sign in to this hub from the Android app to start syncing.";
  } else {
    syncLine.textContent = last ? (last.status === "imported-captures" ? "Showing saved test captures. Press Sync to read the ring." : `Synced ${ago(last.ts)}`) : "";
  }
}
syncBtn.addEventListener("click", () => startJob("/api/sync"));

const SCREENS = { today: ["Today", renderToday], sleep: ["Sleep", renderSleep], trends: ["Trends", renderTrends], measure: ["Record", renderMeasure], device: ["Ring", renderDevice] };
let renderId = 0;
async function render(quiet = false) {
  const id = ++renderId;
  const [name, fn] = SCREENS[state.tab];
  title.textContent = name;
  document.querySelectorAll(".navbar a").forEach(a => a.dataset.tab === state.tab ? a.setAttribute("aria-current", "page") : a.removeAttribute("aria-current"));
  if (!quiet) view.classList.add("loading");
  try {
    const nodes = await fn();
    if (id !== renderId) return;
    const y = scrollY;
    view.replaceChildren(...nodes);
    if (quiet) scrollTo(0, y);
  } catch (e) {
    if (id === renderId) view.replaceChildren(empty("Something went wrong", String(e.message || e)));
  }
  view.classList.remove("loading");
  if (!state.device) { try { state.device = await api("/api/device"); } catch {} }
  updateSyncLine();
}
function route() {
  const [tab, date] = location.hash.slice(1).split("/");  // #today/2026-07-05 opens a given day
  state.tab = SCREENS[tab] ? tab : "today";
  if (date && state.tab === "today") state.date = date;
  document.getElementById("tip").hidden = true;
  scrollTo(0, 0);
  render();
}
addEventListener("hashchange", route);
try { const t = localStorage.getItem("theme"); if (t && t !== "system") document.documentElement.dataset.theme = t; } catch {}
try { state.caps = await api("/api/capabilities"); } catch { state.caps = { mode: "bench", ring: true }; }
if (!state.caps.ring) {
  // Served by the hub: the phone talks to the ring, so there's no Sync button or Record screen here.
  syncBtn.hidden = true;
  delete SCREENS.measure;
  document.querySelector('.navbar a[data-tab="measure"]').remove();
  document.querySelector(".navbar").classList.add("four");
  document.title = "Open RingBridge";
} else {
  // A workout can outlast a page reload, so pick it back up.
  try {
    const job = await api("/api/job");
    if (job?.state === "running") { state.job = job; polling ??= setInterval(pollJob, 1000); }
  } catch {}
}
route();
