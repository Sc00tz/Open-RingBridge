// Dependency-free SVG charts. Each one has a Compose Canvas equivalent later.
const NS = "http://www.w3.org/2000/svg";
const tip = () => document.getElementById("tip");

export function el(tag, attrs = {}, ...kids) {
  const node = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (v === null || v === undefined) continue;
    if (k === "class") node.className = v;
    else if (k.startsWith("on")) node.addEventListener(k.slice(2), v);
    else node.setAttribute(k, v);
  }
  for (const kid of kids.flat()) if (kid !== null && kid !== undefined) node.append(kid);
  return node;
}
function svg(tag, attrs = {}, text) {
  const node = document.createElementNS(NS, tag);
  for (const [k, v] of Object.entries(attrs)) node.setAttribute(k, v);
  if (text !== undefined) node.textContent = text;
  return node;
}

function showTip(x, y, head, rows) {
  const t = tip();
  t.replaceChildren(el("div", { class: "head" }, head),
    ...rows.map(r => el("div", { class: "row" },
      r.color ? el("i", { class: "key", style: `background:${r.color}` }) : null,
      el("b", {}, r.value), r.name ? el("span", {}, r.name) : null)));
  t.hidden = false;
  const box = t.getBoundingClientRect();
  t.style.left = Math.max(8, Math.min(innerWidth - box.width - 8, x - box.width / 2)) + "px";
  t.style.top = (y - box.height - 12 < 8 ? y + 16 : y - box.height - 12) + "px";
}
function hideTip() { tip().hidden = true; }

function niceTicks(lo, hi, count = 4, minStep = 1) {
  if (lo === hi) { lo -= 1; hi += 1; }
  const raw = (hi - lo) / count, mag = 10 ** Math.floor(Math.log10(raw));
  const step = Math.max(minStep, [1, 2, 2.5, 5, 10].map(m => m * mag).find(s => s >= raw));
  const start = Math.floor(lo / step) * step, end = Math.ceil(hi / step) * step;
  const out = [];
  for (let v = start; v <= end + step / 2; v += step) out.push(+v.toFixed(6));
  return out;
}
const compact = v => (v >= 10000 ? `${+(v / 1000).toFixed(1)}K` : v.toLocaleString());

export function legend(items) {
  return el("div", { class: "legend" }, items.map(i =>
    el("span", {}, el("i", { class: i.line ? "line" : "", style: `background:${i.color}` }), i.name)));
}

export function table(headers, rows, summary = "Show table") {
  const box = el("details", { class: "table" }, el("summary", {}, summary));
  box.addEventListener("toggle", () => {
    if (!box.open || box.querySelector("table")) return;
    box.append(el("div", { class: "tablewrap" }, el("table", {},
      el("thead", {}, el("tr", {}, headers.map(h => el("th", { scope: "col" }, h)))),
      el("tbody", {}, rows.map(r => el("tr", {}, r.map(c => el("td", {}, c ?? "–"))))))));
  });
  return box;
}

function responsive(draw) {
  const host = el("div", { class: "chart" });
  let last = 0;
  new ResizeObserver(([e]) => {
    const w = Math.round(e.contentRect.width);
    if (w && w !== last) { last = w; host.replaceChildren(draw(w)); }
  }).observe(host);
  return host;
}

export const clock = t => new Date(t * 1000).toLocaleTimeString([], { hour: "numeric", minute: "2-digit" });
const hourLabel = t => new Date(t * 1000).toLocaleTimeString([], { hour: "numeric" });

function timeTicks(lo, hi, maxTicks) {
  const steps = [1, 2, 3, 4, 6, 12, 24].map(h => h * 3600);
  const step = hi - lo <= 3600 * maxTicks / 4 ? 900 : hi - lo <= 3600 * maxTicks / 2 ? 1800 : steps.find(s => (hi - lo) / s <= maxTicks) ?? 86400;
  // Align to local clock hours.
  const offset = new Date(lo * 1000).getTimezoneOffset() * 60;
  const out = [];
  for (let t = Math.ceil((lo - offset) / step) * step + offset; t <= hi; t += step) out.push(t);
  return out;
}

/* Line chart with a single y-scale.
   series: [{name, color, points:[{t, v}]}]; x: {type:"time", min, max} or {type:"index", labels}. */
export function lineChart({ series, x, unit = "", height = 170, format = v => Math.round(v).toLocaleString(), breakAfter = 2400, floor = null }) {
  const all = series.flatMap(s => s.points);
  const wrap = el("div", {});
  wrap.append(responsive(w => {
    const m = { top: 12, right: 34, bottom: 22, left: 34 };
    const iw = w - m.left - m.right, ih = height - m.top - m.bottom;
    const values = all.map(p => p.v);
    let ticks = niceTicks(Math.min(...values), Math.max(...values));
    if (floor !== null) ticks = ticks.filter(t => t >= floor);
    const yLo = ticks[0], yHi = ticks[ticks.length - 1];
    const isTime = x.type === "time";
    const xLo = isTime ? x.min : 0, xHi = isTime ? x.max : Math.max(1, x.labels.length - 1);
    const sx = t => m.left + (xHi === xLo ? iw / 2 : (t - xLo) / (xHi - xLo) * iw);
    const sy = v => m.top + ih - (v - yLo) / (yHi - yLo || 1) * ih;
    const root = svg("svg", { viewBox: `0 0 ${w} ${height}`, height, role: "img", tabindex: 0,
      "aria-label": `${series.map(s => s.name).join(", ")}. Use the table below for every value.` });
    ticks.forEach((t, i) => {
      root.append(svg("line", { class: i ? "grid" : "base", x1: m.left, x2: w - m.right, y1: sy(t), y2: sy(t) }));
      root.append(svg("text", { x: m.left - 6, y: sy(t) + 4, "text-anchor": "end" }, compact(t)));
    });
    const xTicks = isTime ? timeTicks(xLo, xHi, Math.max(2, Math.floor(iw / 56)))
      : x.labels.map((_, i) => i).filter((_, i, a) => { const every = Math.ceil(a.length / Math.max(2, Math.floor(iw / 56))); return i % every === 0; });
    xTicks.forEach(t => root.append(svg("text", { x: sx(t), y: height - 6, "text-anchor": "middle" }, isTime ? (t % 3600 ? clock(t) : hourLabel(t)) : x.labels[t])));

    series.forEach(s => {
      const pts = s.points;
      let d = "";
      pts.forEach((p, i) => {
        const broken = i === 0 || (isTime && p.t - pts[i - 1].t > breakAfter);
        d += `${broken ? "M" : "L"}${sx(p.t).toFixed(1)} ${sy(p.v).toFixed(1)}`;
      });
      if (pts.length > 1) root.append(svg("path", { d, fill: "none", stroke: s.color, "stroke-width": 2, "stroke-linejoin": "round", "stroke-linecap": "round" }));
      const isolated = pts.filter((p, i) => isTime && (i === 0 || p.t - pts[i - 1].t > breakAfter) && (i === pts.length - 1 || pts[i + 1].t - p.t > breakAfter));
      const dots = pts.length <= 14 ? pts : isolated.concat(pts.slice(-1));
      dots.forEach(p => root.append(svg("circle", { cx: sx(p.t), cy: sy(p.v), r: 4, fill: s.color, stroke: "var(--surface)", "stroke-width": 2 })));
      if (pts.length) {
        const end = pts[pts.length - 1];
        root.append(svg("text", { class: "endlabel", x: Math.min(w - 2, sx(end.t) + 7), y: sy(end.v) + 4,
          "text-anchor": sx(end.t) + 34 > w ? "end" : "start", dy: sx(end.t) + 34 > w ? -10 : 0 }, format(end.v)));
      }
    });

    const cross = svg("line", { class: "cross", y1: m.top, y2: m.top + ih, visibility: "hidden" });
    root.append(cross);
    const positions = [...new Set(all.map(p => p.t))].sort((a, b) => a - b);
    let index = positions.length - 1;
    const show = (t, screenX, screenY) => {
      cross.setAttribute("x1", sx(t)); cross.setAttribute("x2", sx(t)); cross.setAttribute("visibility", "visible");
      const rows = series.map(s => {
        const p = s.points.find(q => q.t === t);
        return p ? { color: s.color, value: `${format(p.v)}${unit ? " " + unit : ""}`, name: series.length > 1 ? s.name : null } : null;
      }).filter(Boolean);
      showTip(screenX, screenY, isTime ? clock(t) : x.labels[t], rows);
    };
    const clear = () => { cross.setAttribute("visibility", "hidden"); hideTip(); };
    root.addEventListener("pointermove", e => {
      const box = root.getBoundingClientRect();
      const px = (e.clientX - box.left) * (w / box.width);
      let best = 0, bestD = Infinity;
      positions.forEach((t, i) => { const d = Math.abs(sx(t) - px); if (d < bestD) { bestD = d; best = i; } });
      index = best;
      show(positions[index], box.left + sx(positions[index]) * box.width / w, box.top + m.top);
    });
    root.addEventListener("pointerleave", clear);
    root.addEventListener("blur", clear);
    root.addEventListener("keydown", e => {
      if (e.key !== "ArrowLeft" && e.key !== "ArrowRight") return;
      e.preventDefault();
      index = Math.max(0, Math.min(positions.length - 1, index + (e.key === "ArrowRight" ? 1 : -1)));
      const box = root.getBoundingClientRect();
      show(positions[index], box.left + sx(positions[index]) * box.width / w, box.top + m.top);
    });
    return root;
  }));
  if (series.length > 1) wrap.append(legend(series.map(s => ({ name: s.name, color: s.color, line: true }))));
  return wrap;
}

/* Stacked or plain columns. stacks: [{name, color, values:[...]}] */
export function columns({ labels, stacks, unit = "", height = 170, format = v => Math.round(v).toLocaleString() }) {
  const wrap = el("div", {});
  wrap.append(responsive(w => {
    const m = { top: 14, right: 4, bottom: 22, left: 38 };
    const iw = w - m.left - m.right, ih = height - m.top - m.bottom;
    const totals = labels.map((_, i) => stacks.reduce((sum, s) => sum + (s.values[i] || 0), 0));
    const ticks = niceTicks(0, Math.max(...totals, 1));
    const top = ticks[ticks.length - 1];
    const sy = v => m.top + ih - v / top * ih;
    const root = svg("svg", { viewBox: `0 0 ${w} ${height}`, height, role: "img",
      "aria-label": `${stacks.map(s => s.name).join(", ")} by date. Use the table below for every value.` });
    ticks.forEach((t, i) => {
      root.append(svg("line", { class: i ? "grid" : "base", x1: m.left, x2: w - m.right, y1: sy(t), y2: sy(t) }));
      root.append(svg("text", { x: m.left - 6, y: sy(t) + 4, "text-anchor": "end" }, compact(t)));
    });
    const band = iw / labels.length, bar = Math.min(24, band - 2);
    const every = Math.ceil(labels.length / Math.max(2, Math.floor(iw / 52)));
    labels.forEach((label, i) => {
      const x = m.left + i * band + (band - bar) / 2;
      if (i % every === 0) root.append(svg("text", { x: x + bar / 2, y: height - 6, "text-anchor": "middle" }, label));
      let base = 0;
      const parts = stacks.map(s => ({ s, v: s.values[i] || 0 })).filter(p => p.v > 0);
      parts.forEach((p, j) => {
        const y1 = sy(base + p.v), y0 = sy(base);
        const gap = j ? 2 : 0;
        const h = Math.max(1, y0 - y1 - gap);
        const isTop = j === parts.length - 1, r = isTop ? Math.min(4, h, bar / 2) : 0;
        const d = `M${x} ${y1 + h}V${y1 + r}${r ? `Q${x} ${y1} ${x + r} ${y1}` : ""}H${x + bar - r}${r ? `Q${x + bar} ${y1} ${x + bar} ${y1 + r}` : ""}V${y1 + h}Z`;
        root.append(svg("path", { d, fill: p.s.color, class: "mark" }));
        base += p.v;
      });
      const hit = svg("rect", { x: m.left + i * band, y: m.top, width: band, height: ih, fill: "transparent", tabindex: parts.length ? 0 : -1 });
      const show = e => {
        const box = hit.getBoundingClientRect();
        showTip(e.clientX ?? box.left + box.width / 2, box.top + 4, label, parts.slice().reverse().map(p => ({
          color: p.s.color, value: `${format(p.v)}${unit ? " " + unit : ""}`, name: stacks.length > 1 ? p.s.name : null })));
      };
      if (parts.length) {
        hit.addEventListener("pointermove", show); hit.addEventListener("focus", show);
        hit.addEventListener("pointerleave", hideTip); hit.addEventListener("blur", hideTip);
      }
      root.append(hit);
    });
    return root;
  }));
  if (stacks.length > 1) wrap.append(legend(stacks.map(s => ({ name: s.name, color: s.color }))));
  return wrap;
}

/* Hypnogram: the row shows the stage as well as the color, so it never relies on color alone. */
export function hypnogram({ segments, start, end, stages }) {
  return responsive(w => {
    const rowH = 26, top = 2, m = { left: 48, right: 4 };
    const height = top + stages.length * rowH + 22;
    const iw = w - m.left - m.right;
    const sx = t => m.left + (t - start) / (end - start) * iw;
    const root = svg("svg", { viewBox: `0 0 ${w} ${height}`, height, role: "img", "aria-label": "Sleep stages over the night. Use the table below for every segment." });
    stages.forEach((s, i) => {
      root.append(svg("text", { class: "row", x: 0, y: top + i * rowH + 17 }, s.name));
      root.append(svg("line", { class: "grid", x1: m.left, x2: w - m.right, y1: top + (i + 1) * rowH, y2: top + (i + 1) * rowH }));
    });
    timeTicks(start, end, Math.max(2, Math.floor(iw / 56))).forEach(t =>
      root.append(svg("text", { x: sx(t), y: height - 6, "text-anchor": "middle" }, hourLabel(t))));
    segments.forEach(seg => {
      const i = stages.findIndex(s => s.key === seg.stage);
      if (i < 0) return;
      const x = sx(seg.start_ts), width = Math.max(1.5, (seg.seconds / (end - start)) * iw - 1);
      const r = svg("rect", { class: "mark", x, y: top + i * rowH + 3, width, height: rowH - 6, rx: Math.min(4, width / 2), fill: stages[i].color, tabindex: 0 });
      const show = e => {
        const box = r.getBoundingClientRect();
        showTip(e.clientX ?? box.left, box.top, `${clock(seg.start_ts)} – ${clock(seg.start_ts + seg.seconds)}`,
          [{ color: stages[i].color, value: `${Math.round(seg.seconds / 60)} min`, name: stages[i].name }]);
      };
      r.addEventListener("pointermove", show); r.addEventListener("focus", show);
      r.addEventListener("pointerleave", hideTip); r.addEventListener("blur", hideTip);
      root.append(r);
    });
    return root;
  });
}

/* Tall segmented bar for one whole split into parts. Labels print only where they fit. */
export function segmented(parts, total) {
  const bar = el("div", { class: "segbar", role: "img",
    "aria-label": parts.map(p => `${p.name} ${Math.round(p.value / total * 100)}%`).join(", ") });
  parts.filter(p => p.value > 0).forEach(p => {
    const share = p.value / total;
    bar.append(el("div", { style: `flex:${p.value} 1 0;background:${p.color}`, class: p.darkText ? "dark" : "", title: `${p.name} ${Math.round(share * 100)}%` },
      share >= 0.1 ? `${Math.round(share * 100)}%` : ""));
  });
  return el("div", {}, bar, legend(parts.filter(p => p.value > 0).map(p => ({ name: p.name, color: p.color }))));
}

// A half-circle gauge. The number and label are always shown, so color is never the only signal.
export function gauge({ value, label, bands, name }) {
  const width = 280, height = 158, cx = 140, cy = 140, r = 112, gap = 1.2;
  const polar = (v, radius) => {
    const a = Math.PI * (1 - v / 100);
    return [cx + radius * Math.cos(a), cy - radius * Math.sin(a)];
  };
  const arc = (from, to, color) => {
    const [x1, y1] = polar(from, r), [x2, y2] = polar(to, r);
    return svg("path", { d: `M${x1} ${y1}A${r} ${r} 0 0 1 ${x2} ${y2}`, fill: "none", stroke: color, "stroke-width": 16 });
  };
  const root = svg("svg", { viewBox: `0 0 ${width} ${height}`, role: "img", "aria-label": value == null ? `${name}: no score` : `${name}: ${value} of 100, ${label}` });
  bands.forEach((b, i) => {
    const part = arc(b.from + (i ? gap / 2 : 0), b.to - (i < bands.length - 1 ? gap / 2 : 0), value == null ? "var(--grid)" : b.color);
    part.append(svg("title", {}, `${b.name}: ${b.from}–${b.to === 100 ? 100 : b.to - 1}`));
    root.append(part);
  });
  if (value != null) {
    // A short needle in the arc's inner edge, so it never crosses the number.
    const a = Math.PI * (1 - value / 100), side = [Math.sin(a) * 5, Math.cos(a) * 5];
    const [tx, ty] = polar(value, r - 10), [bx, by] = polar(value, r - 34);
    root.append(svg("polygon", { points: `${tx},${ty} ${bx + side[0]},${by + side[1]} ${bx - side[0]},${by - side[1]}`, fill: "var(--ink)", stroke: "var(--surface)", "stroke-width": 2 }));
  }
  root.append(svg("text", { x: cx, y: cy - 22, "text-anchor": "middle", class: "gaugevalue" }, value == null ? "–" : String(value)));
  return el("div", { class: "gauge" }, root);
}
