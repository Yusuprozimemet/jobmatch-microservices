// The migration dashboard. scripts/spec-drift.py writes the data line that calls start().
function boot(DATA) {
const R = DATA.rows;
const N = R.length;
const KIND = { spec: "Spec change", code: "Code track", close: "Close the day", other: "Other", origin: "Plan as written" };
const KVAR = { spec: "var(--spec)", code: "var(--code)", close: "var(--close)", other: "var(--other)", origin: "var(--muted)" };
const fmt = (x, d = 1) => x == null ? "–" : Number(x).toFixed(d);
// PR titles come from anyone who opens a pull request, so everything from the data is escaped.
const esc = s => String(s).replace(/[&<>"]/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" })[c]);
const md = s => esc(s)
  .replace(/\[([^\]]+)\]\([^)]+\)/g, "$1")
  .replace(/`([^`]+)`/g, "<code>$1</code>")
  .replace(/\*\*([^*]+)\*\*/g, "<b>$1</b>")
  .replace(/\*([^*\s][^*]*)\*/g, "<em>$1</em>");
const dd = n => String(n).padStart(2, "0");
const prLabel = r => r.pr ? "#" + r.pr : "start";
const dayOfFile = f => { const m = f.match(/day-(\d+)/); return m ? +m[1] : 0; };
// A day's place in the run order (plan.md): Day 38 runs before Day 17. Mirrors ranker() in the script.
const ORDER = DATA.order || [];
const rank = d => !d ? -1 : ORDER.includes(d) ? ORDER.indexOf(d) : ORDER.length + d;
// The days that break the rising run of the order: run ahead of a lower day, or behind a higher one.
const OOO = new Set(DATA.out_of_order || []);
const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
const shortDate = s => `${MONTHS[+s.slice(5, 7) - 1]} ${+s.slice(8)}`;
const ranBetween = d => { const i = ORDER.indexOf(d); return [ORDER[i - 1], ORDER[i + 1]].map(x => x ? `Day ${dd(x)}` : "–"); };
const NS = "http://www.w3.org/2000/svg";
let sel = N - 1;
function el(tag, attrs = {}, parent) {
  const e = document.createElementNS(NS, tag);
  for (const [k, v] of Object.entries(attrs)) e.setAttribute(k, v);
  if (parent) parent.appendChild(e);
  return e;
}

/* ---------- tooltip ---------- */
const tip = document.getElementById("tip");
function showTip(ev, html) {
  tip.innerHTML = html; tip.hidden = false;
  const w = tip.offsetWidth, h = tip.offsetHeight;
  let x = ev.clientX + 14, y = ev.clientY + 14;
  if (x + w > innerWidth - 8) x = ev.clientX - w - 14;
  if (y + h > innerHeight - 8) y = ev.clientY - h - 14;
  tip.style.left = Math.max(8, x) + "px"; tip.style.top = Math.max(8, y) + "px";
}
const hideTip = () => { tip.hidden = true; };
function rowTip(r) {
  return `<b>${prLabel(r)} · ${KIND[r.kind]}</b><div style="margin:3px 0 6px">${esc(r.title)}</div>
  <div class="row"><span>date</span><span>${r.date}</span></div>
  <div class="row"><span>day reached</span><span>${r.day || "–"}</span></div>
  <div class="row"><span>drift</span><span>${fmt(r.drift)}°</span></div>
  <div class="row"><span>spec names in code</span><span>${r.name_coverage == null ? "–" : fmt(r.name_coverage * 100, 1) + "%"}</span></div>
  <div class="row"><span>Jaccard, spec ∩ code</span><span>${r.jaccard == null ? "–" : fmt(r.jaccard, 2)}</span></div>
  <div class="row"><span>gap angle (secondary)</span><span>${fmt(r.gap_full)}°</span></div>
  <div class="row"><span>gap by chance</span><span>${r.gap_chance == null ? "–" : fmt(r.gap_chance) + "°"}</span></div>
  <div class="row"><span>reached names in code</span><span>${r.coverage == null ? "–" : fmt(r.coverage * 100, 1) + "%"}</span></div>
  <div class="row"><span>service code out</span><span>${movedShare(r) == null ? "–" : fmt(movedShare(r)) + "%"}</span></div>`;
}

/* ---------- derived numbers ---------- */
const first = R[0], last = R[N - 1];
const byKind = { spec: 0, code: 0, close: 0, other: 0 };
for (let i = 1; i < N; i++) byKind[R[i].kind] += R[i].gap_full - R[i - 1].gap_full;
const gapClosed = first.gap_full - last.gap_full;
const specShare = -byKind.spec / gapClosed;
const bigStep = Math.max(...R.map(r => r.step));
const nKind = k => R.filter(r => r.kind === k).length;
let forward = 0, specEdits = 0;
R.forEach(r => {
  for (const [f, n] of Object.entries(r.spec_files || {})) {
    const d = dayOfFile(f);
    if (!d) continue;
    specEdits += n;
    if (rank(d) > rank(r.day)) forward += n;
  }
});

/* ---------- stats ---------- */
document.getElementById("stats").innerHTML = `
  <div class="stat"><span class="k">Drift from the plan as written</span>
    <span class="v num">${fmt(last.drift)}°</span>
    <span class="n">0° on Sep 20. ${bigStep < 10 ? "It rises by small steps and never jumps." : `Its biggest single step was ${fmt(bigStep, 2)}° (${prLabel(R.find(r => r.step === bigStep))}).`}</span></div>
  <div class="stat"><span class="k">Spec names in the code · Jaccard</span>
    <span class="v num">${last.name_coverage == null ? "–" : fmt(last.name_coverage * 100, 1) + "%"} <small>· ${last.jaccard == null ? "–" : fmt(last.jaccard, 2)}</small></span>
    <span class="n">Of the backticked names the specs use today, the share the code has; Jaccard is the names in both over the names in either.</span>
    <span class="n" style="color:var(--muted)">Gap angle (secondary): ${fmt(first.gap_full)}° → ${fmt(last.gap_full)}°, ${fmt(gapClosed)}° closed across ${N - 1} merges; ${fmt(last.gap_chance)}° by chance. ${last.coverage == null ? "" : `${fmt(last.coverage * 100, 1)}% of the names the reached days use are in the code.`}</span></div>
  <div class="stat"><span class="k">Share of the gap closed by the spec</span>
    <span class="v num">${Math.round(specShare * 100)}%</span>
    <span class="n"><span class="sw" style="background:var(--spec)"></span>${nKind("spec")} spec-change PRs ${specShare >= 0 ? "moved the spec toward the code" : "widened the gap, naming what was not built yet"}; <span class="sw" style="background:var(--code)"></span>${nKind("code")} code PRs closed ${Math.round(-byKind.code / gapClosed * 100)}%</span></div>
  <div class="stat"><span class="k">Spec text since Sep 20</span>
    <span class="v num">−${last.deleted} <small>/ +${last.added} lines</small></span>
    <span class="n">of ${DATA.origin_lines} lines as written: ${Math.round(last.deleted / DATA.origin_lines * 100)}% rewritten, the rest kept</span></div>`;
document.getElementById("headline").textContent = specShare >= -byKind.code / gapClosed
  ? "The plan moved to meet the code" : "The spec leads, the code follows";
document.querySelectorAll(".vocab-n").forEach(e => { e.textContent = DATA.vocab_size; });
const merged = R.filter(r => r.pr).map(r => r.pr);
document.getElementById("pr-range").textContent = `${N - 1} merges, #${Math.min(...merged)}–#${Math.max(...merged)}`;
document.querySelectorAll(".drift-ref").forEach(e => { e.textContent = DATA.drift_ref == null ? "–" : fmt(DATA.drift_ref) + "°"; });
document.getElementById("gap-chance").textContent = fmt(last.gap_chance) + "°";
document.getElementById("caveats").innerHTML = ((DATA.meta || {}).caveats || []).map(c => `<li>${esc(c)}</li>`).join("");

/* ---------- scrubber ---------- */
const scrub = document.getElementById("pr-scrub");
scrub.max = N - 1; scrub.value = sel;
scrub.addEventListener("input", () => setSel(+scrub.value));
let timer = null;
const playBtn = document.getElementById("play");
function stop() { clearInterval(timer); timer = null; playBtn.textContent = "▶ Replay"; }
playBtn.addEventListener("click", () => {
  if (timer) return stop();
  if (matchMedia("(prefers-reduced-motion: reduce)").matches) { setSel(N - 1); return; }
  playBtn.textContent = "❚❚ Pause";
  let i = sel >= N - 1 ? 0 : sel;
  setSel(i);
  timer = setInterval(() => { i++; if (i >= N) return stop(); setSel(i); }, 260);
});

function drawReadout() {
  const s = R[sel], prev = R[Math.max(0, sel - 1)];
  const dGap = s.gap_full - prev.gap_full, dDrift = s.drift - prev.drift;
  const sign = x => (x > 0 ? "+" : x < 0 ? "−" : "±") + Math.abs(x).toFixed(2);
  const edits = Object.entries(s.spec_files || {}).sort((a, b) => b[1] - a[1]);
  const moved = sel === 0 ? "Starting point: the monolith and the plan written for it." :
    Math.abs(dDrift) < 0.01 && Math.abs(dGap) < 0.05 ? "Neither vector moved noticeably." :
    Math.abs(dDrift) < 0.01 ? `Only the code moved. The gap ${dGap < 0 ? "narrowed" : "widened"} by ${Math.abs(dGap).toFixed(2)}°.` :
    `The spec rotated ${dDrift.toFixed(2)}° and the gap ${dGap < 0 ? "narrowed" : "widened"} by ${Math.abs(dGap).toFixed(2)}°.`;
  document.getElementById("readout").innerHTML = `
    <div class="eyebrow">Selected merge</div>
    <div class="title">${prLabel(s)} · ${esc(s.title)}</div>
    <div><span class="chip" style="color:${KVAR[s.kind]}">${KIND[s.kind]}</span> <span class="num" style="color:var(--muted);font-size:12.5px;margin-left:6px">${s.sha} · ${s.date}</span></div>
    <p style="font-size:14px;color:var(--ink-2)">${moved}</p>
    <div class="hr"></div>
    <dl>
      <dt>Drift from the plan as written</dt><dd>${fmt(s.drift, 2)}° <span style="color:var(--muted)">(${sign(dDrift)})</span></dd>
      <dt>Spec names in code</dt><dd>${s.name_coverage == null ? "–" : fmt(s.name_coverage * 100, 1) + "%"}</dd>
      <dt>Jaccard, spec ∩ code</dt><dd>${s.jaccard == null ? "–" : fmt(s.jaccard, 2)}</dd>
      <dt>Gap angle, spec vs code (secondary)</dt><dd>${fmt(s.gap_full, 2)}° <span style="color:var(--muted)">(${sign(dGap)})</span></dd>
      <dt>Reached-day names in code</dt><dd>${s.coverage == null ? "–" : fmt(s.coverage * 100, 1) + "%"}</dd>
      <dt>Code lines changed</dt><dd>${s.code_churn.toLocaleString()}</dd>
      <dt>Spec lines changed</dt><dd>${edits.reduce((a, b) => a + b[1], 0)}</dd>
    </dl>
    ${edits.length ? `<div style="font-size:12.5px;color:var(--ink-2)">${edits.slice(0, 5).map(([f, n]) => `<div style="display:flex;justify-content:space-between;gap:10px"><code>${esc(f.replace("specs/", ""))}</code><span class="num">${n}</span></div>`).join("")}${edits.length > 5 ? `<div style="color:var(--muted)">+${edits.length - 5} more files</div>` : ""}</div>` : ""}`;
}

/* ---------- line charts ---------- */
const LW = 1000, LP = { l: 44, r: 104, t: 14, b: 60 };
const lx = i => LP.l + (i / (N - 1)) * (LW - LP.l - LP.r);
function dayStarts() {
  const out = []; let d = -1;
  R.forEach((r, i) => { if (r.day !== d) { out.push([i, r.day]); d = r.day; } });
  return out;
}
// The first merge of each calendar day: the x axis is merges, so a busy date takes more width.
function dateStarts() {
  return R.map((r, i) => [i, r.date]).filter(([i, s]) => !i || R[i - 1].date !== s);
}
const steps = (lo, hi, n) => Array.from({ length: n + 1 }, (_, i) => +(lo + (hi - lo) * i / n).toFixed(1));
function lineChart(box, { H, yMin, yMax, ticks, unit, series, label, strip, digits = 1 }) {
  box.innerHTML = "";
  const svg = el("svg", { viewBox: `0 0 ${LW} ${H}`, role: "img", "aria-label": label }, box);
  const ly = v => LP.t + (1 - (v - yMin) / (yMax - yMin)) * (H - LP.t - LP.b);
  ticks.forEach(t => {
    el("line", { x1: LP.l, x2: LW - LP.r, y1: ly(t), y2: ly(t), stroke: "var(--grid)" }, svg);
    el("text", { x: LP.l - 8, y: ly(t) + 4, "text-anchor": "end", class: "num" }, svg).textContent = t + unit;
  });
  el("line", { x1: LP.l, x2: LW - LP.r, y1: H - LP.b, y2: H - LP.b, stroke: "var(--axis)" }, svg);
  let lastX = -99;
  dayStarts().forEach(([i, d]) => {
    if (!d || lx(i) - lastX < 34) return;
    lastX = lx(i);
    el("line", { x1: lx(i), x2: lx(i), y1: LP.t, y2: H - LP.b, stroke: "var(--grid)", "stroke-dasharray": "2 3" }, svg);
    const t = el("text", { x: lx(i) + 3, y: H - LP.b + (strip ? 30 : 16), class: "num" + (OOO.has(d) ? " ooo" : "") }, svg);
    t.textContent = "D" + dd(d) + (OOO.has(d) ? "*" : "");
  });
  let lastDate = -99;
  dateStarts().forEach(([i, s]) => {
    el("line", { x1: lx(i), x2: lx(i), y1: H - LP.b, y2: H - LP.b + (strip ? 36 : 22), stroke: "var(--axis)" }, svg);
    if (lx(i) - lastDate < 44) return;
    lastDate = lx(i);
    el("text", { x: lx(i) + 3, y: H - LP.b + (strip ? 46 : 32), class: "date" }, svg).textContent = shortDate(s);
  });
  if (strip) {
    const w = (LW - LP.l - LP.r) / (N - 1);
    R.forEach((r, i) => el("rect", { x: lx(i) - w / 2 + 1, y: H - LP.b + 5, width: Math.max(2, w - 2), height: 8, rx: 2, fill: KVAR[r.kind] }, svg));
  }
  el("line", { x1: lx(sel), x2: lx(sel), y1: LP.t, y2: H - LP.b, stroke: "var(--ink-2)", "stroke-width": 1 }, svg);
  const cross = el("line", { x1: 0, x2: 0, y1: LP.t, y2: H - LP.b, stroke: "var(--muted)", "stroke-dasharray": "3 3", visibility: "hidden" }, svg);
  series.forEach(s => {
    const pts = R.map((r, i) => [i, s.get(r)]).filter(p => p[1] != null);
    el("polyline", { points: pts.map(([i, v]) => `${lx(i)},${ly(v)}`).join(" "), fill: "none", stroke: s.color, "stroke-width": s.dash ? 1.5 : 2, "stroke-linejoin": "round", "stroke-linecap": "round", ...(s.dash ? { "stroke-dasharray": "5 4" } : {}) }, svg);
    const v = s.get(R[sel]);
    if (v != null && !s.dash) el("circle", { cx: lx(sel), cy: ly(v), r: 5, fill: s.color, stroke: "var(--surface)", "stroke-width": 2 }, svg);
    const end = pts[pts.length - 1];
    el("text", { x: lx(end[0]) + 10, y: ly(end[1]) + 4 + (s.dy || 0), class: "lbl" }, svg).textContent = s.end || `${s.name} ${fmt(end[1], digits)}${unit}`;
  });
  const hit = el("rect", { x: LP.l - 6, y: 0, width: LW - LP.l - LP.r + 12, height: H, class: "hit" }, svg);
  const idxAt = ev => {
    const p = svg.createSVGPoint(); p.x = ev.clientX; p.y = ev.clientY;
    const q = p.matrixTransform(svg.getScreenCTM().inverse());
    return Math.max(0, Math.min(N - 1, Math.round((q.x - LP.l) / (LW - LP.l - LP.r) * (N - 1))));
  };
  hit.addEventListener("mousemove", ev => { const i = idxAt(ev); cross.setAttribute("x1", lx(i)); cross.setAttribute("x2", lx(i)); cross.setAttribute("visibility", "visible"); showTip(ev, rowTip(R[i])); });
  hit.addEventListener("mouseleave", () => { cross.setAttribute("visibility", "hidden"); hideTip(); });
  hit.addEventListener("click", ev => setSel(idxAt(ev)));
}
// The axes follow the data, so a later phase that pushes drift or coverage past today's range stays on the chart.
const degMax = Math.ceil(Math.max(DATA.drift_ref || 0, ...R.map(r => Math.max(r.drift, r.gap_full, r.gap_chance || 0))) / 15) * 15;
const covMin = Math.min(92, Math.floor(Math.min(...R.filter(r => r.coverage != null).map(r => r.coverage * 100)) / 2) * 2);
function drawLines() {
  lineChart(document.getElementById("lines"), {
    H: 330, yMin: 0, yMax: degMax, ticks: steps(0, degMax, degMax / 15), unit: "°", strip: true,
    label: "Drift and gap in degrees across merges",
    series: [
      { name: "gap", color: "var(--gap)", get: r => r.gap_full },
      { name: "chance", color: "var(--gap)", dash: true, get: r => r.gap_chance ?? null },
      { name: "drift", color: "var(--spec)", get: r => r.drift },
      ...(DATA.drift_ref == null ? [] : [{ name: "halves", color: "var(--spec)", dash: true, get: () => DATA.drift_ref }]),
    ],
  });
  lineChart(document.getElementById("cover"), {
    H: 190, yMin: covMin, yMax: 100, ticks: steps(covMin, 100, 4), unit: "%", strip: false,
    label: "Share of reached-day spec names present in code",
    series: [{ name: "in code", color: "var(--code)", get: r => r.coverage == null ? null : +(r.coverage * 100).toFixed(2) }],
  });
  lineChart(document.getElementById("evidence-history"), {
    H: 190, yMin: 0, yMax: evMax, ticks: steps(0, evMax, 4), unit: "", strip: false, digits: 0,
    label: "Tests named by finished days, present on each merge and not running",
    series: [
      { name: "present", color: "var(--close)", get: r => r.evidence && r.evidence.present },
      { name: "not running", color: "var(--code)", get: r => r.evidence && r.evidence.skippable + r.evidence.missing },
    ],
  });
  lineChart(document.getElementById("moved"), {
    H: 190, yMin: 0, yMax: 100, ticks: steps(0, 100, 4), unit: "%", strip: false,
    label: "Share of the four services' main source lines under services/",
    series: [{ name: "out", color: "var(--close)", get: r => movedShare(r) }],
  });
  lineChart(document.getElementById("churn"), {
    H: 220, yMin: 0, yMax: 100, ticks: steps(0, 100, 4), unit: "%", strip: true,
    label: "Spec and code lines changed so far, each as a share of its own total",
    series: [
      { name: "code", color: "var(--code)", end: "code", dy: -7, get: r => cumShare(r, "code") },
      { name: "spec", color: "var(--spec)", end: "spec", dy: 8, get: r => cumShare(r, "spec") },
    ],
  });
  lineChart(document.getElementById("test-ratio"), {
    H: 200, yMin: 0, yMax: ratioMax, ticks: steps(0, ratioMax, ratioMax <= 6 ? ratioMax : 4),
    unit: "×", strip: false, digits: 2,
    label: "Test lines changed so far per production line changed so far",
    series: [
      { name: "one to one", color: "var(--muted)", dash: true, end: "1 : 1", get: () => 1 },
      { name: "tests", color: "var(--close)", get: r => testRatio(r) },
    ],
  });
  const p = R[sel].placed || {};
  document.getElementById("placed").innerHTML = `<p>At ${prLabel(R[sel])}: ` + Object.keys(p).sort().map(s =>
    `<b>${s}</b> ${p[s][1] ? (p[s][0] ? `${p[s][1]} lines out, ${p[s][0]} still in the monolith` : `out (${p[s][1]} lines)`) : `in the monolith (${p[s][0]} lines)`}`).join(" · ")
    + "</p>";
}
const movedShare = r => {
  const v = Object.values(r.placed || {}), all = v.reduce((a, [i, o]) => a + i + o, 0);
  return all ? +(v.reduce((a, [, o]) => a + o, 0) / all * 100).toFixed(1) : null;
};
const evMax = Math.max(4, Math.ceil(Math.max(0, ...R.map(r => r.evidence ? r.evidence.present : 0)) / 20) * 20);
// Code lines outnumber spec lines several times over, so each runs to 100% of its own total rather than sharing a scale.
const CUM = new Map();
R.reduce(([s, c], r) => {
  const next = [s + Object.values(r.spec_files || {}).reduce((a, b) => a + b, 0), c + (r.code_churn || 0)];
  CUM.set(r, next); return next;
}, [0, 0]);
const cumShare = (r, k) => { const [s, c] = CUM.get(r), [S, C] = CUM.get(last); return k === "spec" ? (S ? s / S * 100 : 0) : (C ? c / C * 100 : 0); };
// Lines per box, summed merge by merge; the ratio waits for 1,000 production lines, since Days 1-4 wrote only tests.
const WORK = new Map();
R.reduce((acc, r) => {
  const next = { ...acc };
  Object.entries(r.work || {}).forEach(([k, v]) => { next[k] = (next[k] || 0) + v; });
  WORK.set(r, next); return next;
}, {});
const RATIO_FROM = 1000;
const testRatio = r => { const w = WORK.get(r); return w.prod >= RATIO_FROM ? +(w.test / w.prod).toFixed(2) : null; };
const ratioMax = Math.max(2, Math.ceil(Math.max(0, ...R.map(testRatio).filter(v => v != null))));
// One bar, split into boxes in order; a box's share is printed inside it when there is room.
function shareBar(box, parts, label) {
  box.innerHTML = "";
  const H = 40, total = parts.reduce((a, p) => a + p.value, 0) || 1, P = { l: 0, r: 0 };
  const svg = el("svg", { viewBox: `0 0 ${LW} ${H}`, role: "img", "aria-label": label }, box);
  let x = P.l;
  parts.forEach(p => {
    const w = p.value / total * (LW - P.l - P.r);
    if (!w) return;
    const rect = el("rect", { x: x + 1, y: 4, width: Math.max(1, w - 2), height: H - 8, rx: 3, fill: p.color }, svg);
    if (w > 54) el("text", { x: x + 10, y: H / 2 + 5, class: "num", style: "fill:var(--surface);font-weight:600" }, svg).textContent = fmt(p.value / total * 100, 0) + "%";
    rect.addEventListener("mousemove", ev => showTip(ev, `<b>${esc(p.name)}</b>${p.tip || ""}<div class="row"><span>share</span><span>${fmt(p.value / total * 100, 1)}%</span></div>`));
    rect.addEventListener("mouseleave", hideTip);
    x += w;
  });
}
function drawWork() {
  const w = WORK.get(last), n = k => w[k] || 0;
  const row = (k, v) => `<div class="row"><span>${k}</span><span>${v.toLocaleString()}</span></div>`;
  const parts = [
    { name: "Specs", value: n("spec"), color: "var(--spec)", tip: row("plan.md and specs/", n("spec")) },
    { name: "Context", value: n("context"), color: "var(--w-context)", tip: row("CLAUDE.md, agents, README, docs", n("context")) },
    { name: "Production code", value: n("prod"), color: "var(--code)", tip: row("code and config", n("prod")) },
    { name: "Tests and verification", value: n("test") + n("tooling"), color: "var(--close)",
      tip: row("tests", n("test")) + row("scripts, CI, dashboard", n("tooling")) },
  ];
  shareBar(document.getElementById("work-bar"), parts, "Lines changed in merged pull requests, by what the file is");
  document.getElementById("work-legend").innerHTML = parts.map(p =>
    `<span><i class="dot" style="background:${p.color}"></i>${p.name} · ${kilo(p.value)}</span>`).join("")
    + `<span>${fmt(n("test") / Math.max(1, n("prod")), 1)} test lines per production line</span>`;
}
const gapDelta = i => +(R[i].gap_full - R[i - 1].gap_full).toFixed(2);
const signed = (x, d = 2) => (x > 0 ? "+" : x < 0 ? "−" : "") + fmt(Math.abs(x), d);

/* ---------- per-merge change in drift and gap ---------- */
// Two panels on the merge axis of the line chart above: the step each merge took on drift, then on gap.
function drawDeltas() {
  const box = document.getElementById("deltas");
  box.innerHTML = "";
  const panels = [
    { name: "drift", label: "Δ drift", get: r => r.drift },
    { name: "gap", label: "Δ gap", get: r => r.gap_full },
  ];
  const PH = 200, GAP = 18, H = LP.t + panels.length * PH + (panels.length - 1) * GAP + 40;
  const svg = el("svg", { viewBox: `0 0 ${LW} ${H}`, role: "img", "aria-label": "Change in drift and in gap at each merge, in merge order" }, box);
  panels.forEach((pn, k) => {
    const top = LP.t + k * (PH + GAP), bot = top + PH;
    const pts = R.slice(1).map((r, j) => ({ i: j + 1, r, d: +(pn.get(r) - pn.get(R[j])).toFixed(2) }));
    const lo = Math.min(0, ...pts.map(p => p.d)), hi = Math.max(0, ...pts.map(p => p.d));
    const unit = hi - lo > 4 ? 1 : 0.5, yMin = Math.floor(lo / unit) * unit, yMax = Math.max(Math.ceil(hi / unit) * unit, yMin + unit);
    const ly = v => top + (1 - (v - yMin) / (yMax - yMin)) * PH;
    for (let t = yMin; t <= yMax + 1e-9; t += unit) {
      el("line", { x1: LP.l, x2: LW - LP.r, y1: ly(t), y2: ly(t), stroke: Math.abs(t) < 1e-9 ? "var(--axis)" : "var(--grid)" }, svg);
      el("text", { x: LP.l - 8, y: ly(t) + 4, "text-anchor": "end", class: "num" }, svg).textContent = signed(t, t % 1 ? 1 : 0) + "°";
    }
    el("rect", { x: LP.l, y: top, width: LW - LP.l - LP.r, height: PH, fill: "none", stroke: "var(--axis)" }, svg);
    el("text", { x: LW - LP.r + 10, y: top + 16, class: "lbl" }, svg).textContent = pn.label;
    const net = pts.reduce((a, p) => a + p.d, 0);
    el("text", { x: LW - LP.r + 10, y: top + 34, class: "num" }, svg).textContent = `net ${signed(net, 1)}°`;
    let lastX = -99;
    dayStarts().forEach(([i, d]) => {
      if (!d || lx(i) - lastX < 34) return;
      lastX = lx(i);
      el("line", { x1: lx(i), x2: lx(i), y1: top, y2: bot, stroke: "var(--grid)", "stroke-dasharray": "2 3" }, svg);
      if (k === panels.length - 1) {
        const t = el("text", { x: lx(i) + 3, y: bot + 16, class: "num" + (OOO.has(d) ? " ooo" : "") }, svg);
        t.textContent = "D" + dd(d) + (OOO.has(d) ? "*" : "");
      }
    });
    el("line", { x1: lx(sel), x2: lx(sel), y1: top, y2: bot, stroke: "var(--ink-2)", "stroke-width": 1 }, svg);
    pts.forEach(p => {
      el("circle", { cx: lx(p.i), cy: ly(p.d), r: 3.5, fill: KVAR[p.r.kind], stroke: "var(--surface)", "stroke-width": 1 }, svg);
      if (p.i === sel) el("circle", { cx: lx(p.i), cy: ly(p.d), r: 7, fill: "none", stroke: "var(--ink)", "stroke-width": 1.5 }, svg);
      const hit = el("circle", { cx: lx(p.i), cy: ly(p.d), r: 7, class: "hit" }, svg);
      hit.addEventListener("mousemove", ev => showTip(ev, rowTip(p.r) + `<div class="row"><span>change in ${pn.name}</span><span>${signed(p.d)}°</span></div>`));
      hit.addEventListener("mouseleave", hideTip);
      hit.addEventListener("click", () => setSel(p.i));
    });
  });
  let lastDate = -99;
  const bot = H - 40 + 4;
  dateStarts().forEach(([i, s]) => {
    if (lx(i) - lastDate < 44) return;
    lastDate = lx(i);
    el("text", { x: lx(i) + 3, y: bot + 28, class: "date" }, svg).textContent = shortDate(s);
  });
}

/* ---------- pull requests per day ---------- */
function drawPerDay() {
  const [S, C] = CUM.get(last);
  document.getElementById("churn-legend").innerHTML = `<span><i style="background:var(--spec)"></i>Spec lines changed · ${kilo(S)}</span>
    <span><i style="background:var(--code)"></i>Code lines changed · ${kilo(C)}</span>`;
  const merges = R.filter(r => r.pr);
  const totals = ["spec", "code", "close", "other"].map(k => ({ name: KIND[k], value: merges.filter(r => r.kind === k).length, color: KVAR[k] }));
  shareBar(document.getElementById("pr-total"), totals, "Every merged pull request by kind");
  document.getElementById("pr-total-legend").innerHTML = totals.map(t =>
    `<span><i class="dot" style="background:${t.color}"></i>${t.name} · ${t.value}</span>`).join("") + `<span>${merges.length} in all</span>`;
  const box = document.getElementById("perday");
  box.innerHTML = "";
  const kinds = ["spec", "code", "close", "other"], H = 240, P = { l: 44, r: 16, t: 22, b: 30 };
  const days = DATA.days.filter(d => d.prs.length).sort((a, b) => rank(a.day) - rank(b.day));
  const count = (d, k) => d.prs.filter(p => p.kind === k).length;
  const yMax = Math.max(4, Math.ceil(Math.max(...days.map(d => d.prs.length)) / 4) * 4);
  const ly = v => P.t + (1 - v / yMax) * (H - P.t - P.b);
  const bw = (LW - P.l - P.r) / days.length, w = Math.min(28, bw - 6);
  const svg = el("svg", { viewBox: `0 0 ${LW} ${H}`, role: "img", "aria-label": "Pull requests per day by kind, in run order" }, box);
  steps(0, yMax, 4).forEach(t => {
    el("line", { x1: P.l, x2: LW - P.r, y1: ly(t), y2: ly(t), stroke: t ? "var(--grid)" : "var(--axis)" }, svg);
    el("text", { x: P.l - 8, y: ly(t) + 4, "text-anchor": "end", class: "num" }, svg).textContent = t;
  });
  days.forEach((d, j) => {
    const x = P.l + j * bw + (bw - w) / 2;
    let base = 0;
    kinds.forEach(k => {
      const n = count(d, k);
      if (!n) return;
      // A 2px gap of surface between segments, taken from the top of each.
      el("rect", { x, y: ly(base + n), width: w, height: Math.max(1, ly(base) - ly(base + n) - 2), rx: 2, fill: KVAR[k] }, svg);
      base += n;
    });
    el("text", { x: x + w / 2, y: ly(base) - 6, "text-anchor": "middle", class: "num" }, svg).textContent = base;
    el("text", { x: x + w / 2, y: H - P.b + 16, "text-anchor": "middle", class: "num" + (OOO.has(d.day) ? " ooo" : "") }, svg)
      .textContent = "D" + dd(d.day) + (OOO.has(d.day) ? "*" : "");
    const hit = el("rect", { x: P.l + j * bw, y: P.t, width: bw, height: H - P.t - P.b, class: "hit" }, svg);
    hit.addEventListener("mousemove", ev => showTip(ev, `<b>Day ${dd(d.day)}</b><div style="margin:3px 0 6px">${esc(d.title)}</div>`
      + kinds.map(k => `<div class="row"><span>${KIND[k]}</span><span>${count(d, k)}</span></div>`).join("")
      + `<div class="row"><span>expected PRs, first written</span><span>${d.expected_first ?? "–"}</span></div>`
      + `<div class="row"><span>expected PRs, last written</span><span>${d.expected ?? "–"}</span></div>`));
    hit.addEventListener("mouseleave", hideTip);
    hit.addEventListener("click", () => {
      const nums = new Set(d.prs.map(p => p.number)), i = R.map(r => nums.has(r.pr)).lastIndexOf(true);
      if (i > 0) setSel(i);
    });
  });
}

/* ---------- verification that leaves no lines ---------- */
// One stacked bar per day in run order, plus one for the PRs that belong to no day.
function dayStack(box, items, parts, label, list) {
  box.innerHTML = "";
  const days = [...new Set(items.map(v => v.day))].sort((a, b) => (a == null) - (b == null) || rank(a) - rank(b));
  const groups = days.map(d => ({ d, items: items.filter(v => v.day === d) }));
  const H = 220, P = { l: 44, r: 16, t: 22, b: 30 };
  const yMax = Math.max(4, Math.ceil(Math.max(...groups.map(g => parts.reduce((a, p) => a + p.count(g.items), 0))) / 4) * 4);
  const ly = v => P.t + (1 - v / yMax) * (H - P.t - P.b);
  const bw = (LW - P.l - P.r) / groups.length, w = Math.min(28, bw - 6);
  const svg = el("svg", { viewBox: `0 0 ${LW} ${H}`, role: "img", "aria-label": label }, box);
  steps(0, yMax, 4).forEach(t => {
    el("line", { x1: P.l, x2: LW - P.r, y1: ly(t), y2: ly(t), stroke: t ? "var(--grid)" : "var(--axis)" }, svg);
    el("text", { x: P.l - 8, y: ly(t) + 4, "text-anchor": "end", class: "num" }, svg).textContent = t;
  });
  groups.forEach((g, j) => {
    const x = P.l + j * bw + (bw - w) / 2;
    let base = 0;
    parts.forEach(p => {
      const n = p.count(g.items);
      if (!n) return;
      el("rect", { x: x + 0.5, y: ly(base + n) + 0.5, width: w - 1, height: Math.max(1, ly(base) - ly(base + n) - 2), rx: 2,
        fill: p.outline ? "none" : p.color, stroke: p.outline ? p.color : "none", "stroke-dasharray": p.outline ? "3 2" : "none" }, svg);
      base += n;
    });
    const name = g.d == null ? "none" : "D" + dd(g.d) + (OOO.has(g.d) ? "*" : "");
    el("text", { x: x + w / 2, y: H - P.b + 16, "text-anchor": "middle", class: "num" + (OOO.has(g.d) ? " ooo" : "") }, svg).textContent = name;
    const hit = el("rect", { x: P.l + j * bw, y: P.t, width: bw, height: H - P.t - P.b, class: "hit" }, svg);
    hit.addEventListener("mousemove", ev => showTip(ev, `<b>${g.d == null ? "No day (tooling, docs)" : "Day " + dd(g.d)}</b>`
      + parts.map(p => `<div class="row"><span>${p.name}</span><span>${p.count(g.items)}</span></div>`).join("") + list(g.items)));
    hit.addEventListener("mouseleave", hideTip);
  });
}
function drawVerification() {
  const V = DATA.verification || [], code = V.filter(v => v.kind === "code");
  if (!V.length) return;
  const n = (vs, s) => vs.filter(v => v.breaks === s).length;
  const breakParts = [
    { name: "break recorded", color: "var(--close)", count: vs => n(vs, "recorded") },
    { name: "says why none", color: "var(--other)", count: vs => n(vs, "none") },
    { name: "silent", color: "var(--muted)", outline: true, count: vs => n(vs, "silent") },
  ];
  const nums = vs => vs.map(v => "#" + v.number).join(", ");
  dayStack(document.getElementById("breaks"), code, breakParts, "Code PRs per day by whether they record a break on purpose",
    vs => n(vs, "silent") ? `<div style="margin-top:6px">silent: ${nums(vs.filter(v => v.breaks === "silent"))}</div>` : "");
  document.getElementById("breaks-legend").innerHTML = breakParts.map(p =>
    `<span><i class="dot" style="${p.outline ? `border:1px dashed ${p.color}` : `background:${p.color}`}"></i>${p.name} · ${p.count(code)}</span>`).join("")
    + `<span>${code.length} code PRs</span>`;
  const sum = (vs, k) => vs.reduce((a, v) => a + v[k], 0);
  // Only the failures are drawn: a day's one or two would be a sliver on a bar of its 20 pushes.
  const ciParts = [{ name: "pushes CI failed", color: "var(--fail)", count: vs => sum(vs, "failed") }];
  dayStack(document.getElementById("ci-fails"), V, ciParts, "Commits pushed to each day's PRs, by whether CI failed them",
    vs => `<div class="row"><span>pushes CI ran on</span><span>${sum(vs, "pushes")}</span></div>` + vs.filter(v => v.failed).map(v => `<div class="row"><span>#${v.number}</span><span>${esc(v.failed_in.join(", "))}</span></div>`).join(""));
  const pushes = sum(V, "pushes"), failed = sum(V, "failed");
  document.getElementById("ci-legend").innerHTML = `<span><i class="dot" style="background:var(--fail)"></i>pushes CI failed · ${failed} of ${pushes} (${fmt(failed / Math.max(1, pushes) * 100, 1)}%)</span>`
    + `<span>in ${V.filter(v => v.failed).length} of ${V.length} merged PRs</span>`;
}

/* ---------- heatmap ---------- */
function drawHeat() {
  const box = document.getElementById("heat");
  box.innerHTML = "";
  const files = DATA.files, cols = R.slice(1);
  const CW = 15, CH = 13, L = 96, T = 30;
  const W = L + cols.length * CW + 8, H = T + files.length * CH + 34;
  const svg = el("svg", { viewBox: `0 0 ${W} ${H}`, width: W, height: H, role: "img", "aria-label": "Lines edited per spec file per merge", style: `min-width:${W}px;max-width:${W}px` }, box);
  const max = Math.max(...cols.flatMap(r => Object.values(r.spec_files || {})), 1);
  files.forEach((f, j) => {
    const d = dayOfFile(f), twin = files.filter(g => dayOfFile(g) === d).length > 1;
    el("text", { x: L - 8, y: T + j * CH + CH - 3, "text-anchor": "end", class: "num", style: "font-size:10.5px" }, svg)
      .textContent = f === "plan.md" ? "plan.md" : "day " + dd(d) + (twin ? " " + f.split("-")[2] : "");
  });
  cols.forEach((r, c) => {
    const x = L + c * CW;
    el("rect", { x: x + 1, y: 8, width: CW - 2, height: 8, rx: 2, fill: KVAR[r.kind] }, svg);
    files.forEach((f, j) => {
      const n = (r.spec_files || {})[f] || 0;
      const pct = n ? Math.round(22 + 78 * Math.sqrt(n / max)) : 0;
      el("rect", { x: x + 1, y: T + j * CH + 1, width: CW - 2, height: CH - 2, rx: 2,
        fill: n ? `color-mix(in oklab, var(--spec) ${pct}%, var(--surface))` : "var(--surface-2)" }, svg);
    });
    if (c % 5 === 0 || c === cols.length - 1)
      el("text", { x: x + CW / 2, y: H - 12, "text-anchor": "middle", class: "num", style: "font-size:10px" }, svg).textContent = "#" + r.pr;
  });
  // frontier: bottom edge of the day being worked on
  const rowOfDay = d => files.findIndex(f => dayOfFile(f) === d);
  let path = "";
  cols.forEach((r, c) => {
    const y = T + (rowOfDay(Math.max(r.day, 1)) + 1) * CH;
    path += (c === 0 ? `M${L + c * CW},${y}` : `V${y}`) + `H${L + (c + 1) * CW}`;
  });
  el("path", { d: path, fill: "none", stroke: "var(--code)", "stroke-width": 2, "stroke-linejoin": "round" }, svg);
  if (sel > 0) el("rect", { x: L + (sel - 1) * CW + 0.5, y: 4, width: CW - 1, height: T + files.length * CH - 2, rx: 3, fill: "none", stroke: "var(--ink)", "stroke-width": 1.5 }, svg);
  const hit = el("rect", { x: L, y: 0, width: cols.length * CW, height: T + files.length * CH, class: "hit" }, svg);
  const at = ev => {
    const p = svg.createSVGPoint(); p.x = ev.clientX; p.y = ev.clientY;
    const q = p.matrixTransform(svg.getScreenCTM().inverse());
    return [Math.floor((q.x - L) / CW), Math.floor((q.y - T) / CH)];
  };
  hit.addEventListener("mousemove", ev => {
    const [c, j] = at(ev);
    if (c < 0 || c >= cols.length) return hideTip();
    const r = cols[c];
    if (j < 0 || j >= files.length) return showTip(ev, rowTip(r));
    const f = files[j], n = (r.spec_files || {})[f] || 0, d = dayOfFile(f);
    const where = !d ? "the plan" : rank(d) > rank(r.day) ? `a future day (working on day ${r.day})` : d === r.day ? "the current day" : "a finished day";
    showTip(ev, `<b>${prLabel(r)} → ${esc(f.replace("specs/", ""))}</b><div style="margin:3px 0 6px">${esc(r.title)}</div>
      <div class="row"><span>lines edited</span><span>${n}</span></div><div class="row"><span>target</span><span>${where}</span></div>`);
  });
  hit.addEventListener("mouseleave", hideTip);
  hit.addEventListener("click", ev => { const [c] = at(ev); if (c >= 0 && c < cols.length) setSel(c + 1); });
  el("text", { x: L, y: H - 1, class: "lbl", style: "font-size:11px" }, svg).textContent = "▬ orange step: the day being worked on · darker blue = more lines edited";
}

/* ---------- findings + table ---------- */
// Each heading is a claim, so each is chosen by the numbers it rests on rather than fixed.
function drawFindings() {
  const covFloor = Math.min(...R.filter(r => r.coverage != null).map(r => r.coverage)) * 100;
  const codeShare = -byKind.code / gapClosed;
  const days = DATA.days.filter(finished).length;
  const f = [
    [bigStep < 10 ? "The destination held; the path moved" : "The plan changed direction",
     `Drift reached ${fmt(last.drift)}°; the biggest single step was ${fmt(bigStep, 2)}°. ${days} days in, ${Math.round(last.deleted / DATA.origin_lines * 100)}% of the Sep 20 spec's lines have been rewritten.`],
    [specShare >= codeShare ? "The spec moved toward the code, not the reverse" : "The code now closes more of the gap than the spec",
     `Of the ${fmt(gapClosed)}° of gap that closed, ${Math.round(specShare * 100)}% came from ${nKind("spec")} spec-change PRs and ${Math.round(codeShare * 100)}% from ${nKind("code")} code PRs.`],
    [covFloor >= 90 ? "Spec first keeps the gap from ever opening" : "The gap opened at least once",
     `Reached-day coverage never fell below ${fmt(covFloor, 1)}%: the share of names in the specs of days already reached that exist in the code.`],
    [forward * 2 >= specEdits ? "Corrections reach forward" : "Corrections land on the day at hand",
     `${Math.round(forward / specEdits * 100)}% of all edited day-spec lines (${forward} of ${specEdits}) went to days not reached yet.`],
  ];
  document.getElementById("findings").innerHTML = f.map(([h, p]) => `<div class="finding"><h3>${h}</h3><p>${p}</p></div>`).join("");
}
function drawTable() {
  document.getElementById("tbl").innerHTML = `<thead><tr><th>PR</th><th>Type</th><th>Day</th><th class="r">Drift °</th><th class="r">Gap °</th><th class="r">Names in code</th><th class="r">Spec lines</th><th class="r">Code lines</th><th>Title</th></tr></thead><tbody>` +
    R.map((r, i) => `<tr data-i="${i}" class="${i === sel ? "sel" : ""}"><td class="num">${prLabel(r)}</td><td><span class="sw" style="background:${KVAR[r.kind]}"></span>${KIND[r.kind]}</td><td class="num">${r.day || "–"}</td><td class="r">${fmt(r.drift, 2)}</td><td class="r">${fmt(r.gap_full, 2)}</td><td class="r">${r.coverage == null ? "–" : fmt(r.coverage * 100, 1) + "%"}</td><td class="r">${Object.values(r.spec_files || {}).reduce((a, b) => a + b, 0)}</td><td class="r">${r.code_churn}</td><td class="t">${esc(r.title)}</td></tr>`).join("") + "</tbody>";
}
function drawScrubNow() {
  const s = R[sel];
  document.getElementById("scrub-now").innerHTML = `<span class="chip" style="color:${KVAR[s.kind]}">${KIND[s.kind]}</span>
    <span class="t">${prLabel(s)} · ${esc(s.title)}</span>
    <span class="num" style="color:var(--ink-2)">drift ${fmt(s.drift)}° · gap ${fmt(s.gap_full)}°</span>`;
}
function setSel(i) {
  sel = i; scrub.value = i;
  drawScrubNow(); drawReadout(); drawLines(); drawDeltas(); drawHeat();
  document.querySelectorAll("#tbl tr[data-i]").forEach(tr => tr.classList.toggle("sel", +tr.dataset.i === sel));
}

/* ---------- where we are ---------- */
const PHASES = ["Make the split safe", "Modularise in place", "Gateway + JWT", "Extract job-service",
  "Extract matching-service", "Extract application-service", "Functions + uploads bucket", "Kubernetes + IaC"];
const STOPS = [16, 20, 24, 28, 31, 37];
const STATUS = { done: "Done", closed: "Closed, boxes open", active: "In progress", ready: "Ready", provisional: "Provisional" };
const finished = d => d.status === "done" || d.status === "closed";
function drawNow() {
  const now = DATA.now, days = DATA.days;
  const cur = days.find(d => d.day === now.current_day);
  const done = days.filter(finished).length;
  const open = now.open_prs.map(p => `<a href="${esc(p.url)}" target="_blank" rel="noopener">#${p.number}</a> ${esc(p.title)}`).join("<br>");
  const nextBox = `<div class="next"><span class="eyebrow">Next step</span><b>${md(now.next_step)}</b>${open ? `<div>${open}</div>` : ""}</div>
    <div class="refreshed">refreshed ${esc(now.generated.replace("T", " "))} · main at ${esc(now.head)}${now.last_pr ? ` (#${now.last_pr})` : ""}</div>`;
  document.getElementById("eyebrow").textContent = cur
    ? `jobmatch-microservices · Day ${dd(now.current_day)} of ${days.length} · ${N - 1} merged PRs`
    : `jobmatch-microservices · between days · ${N - 1} merged PRs`;
  // Between days: the next piece of work has no day spec yet (the platform step), or work has stopped.
  if (!cur) document.getElementById("today").innerHTML = `
    <div class="eyebrow">Between days</div>
    <div class="phase">${done} of ${days.length} days finished</div>${nextBox}`;
  else {
  const c = cur.criteria;
  const prs = cur.prs.length
    ? cur.prs.map(p => `<span class="chip" style="color:${KVAR[p.kind]}" title="${esc(p.title)}">#${p.number}</span>`).join(" ")
    : "none yet";
  document.getElementById("today").innerHTML = `
    <div class="eyebrow">Day ${dd(cur.day)} · ${STATUS[cur.status]}</div>
    <div class="day-title">${md(cur.title)}</div>
    <div class="phase">Phase ${cur.phase}: ${PHASES[cur.phase] || ""} · ${done} of ${days.length} days finished</div>
    <dl class="kv">
      <dt>Tracks</dt><dd><div class="trks">${cur.tracks.map(t => `<span class="trk ${cur.tracks_merged.includes(t) ? "done" : ""}" title="${esc(cur.track_work[t] || "")}">${t}</span>`).join("")}</div></dd>
      <dt>Criteria</dt><dd><span class="num">${c.ticked}/${c.total}</span> ticked · <span class="num">${c.new}</span> new · <span class="num">${c.hold}</span> hold</dd>
      <dt>Estimate</dt><dd><span class="num">${cur.expected_first ?? "–"} → ${cur.expected ?? "–"}</span> PRs, as first written → now</dd>
      <dt>Merged</dt><dd>${prs}</dd>
    </dl>
    ${nextBox}`;
  }
  const rows = PHASES.map((name, p) => {
    const ds = days.filter(d => d.phase === p);
    if (!ds.length) return "";
    return `<div class="phase-row"><div class="phase-name"><b>${p}. ${name}</b>Days ${ds[0].day}–${ds[ds.length - 1].day}</div><div class="tiles">${ds.map(d => {
      const share = d.tracks.length ? Math.min(1, d.tracks_merged.length / d.tracks.length) : 0;
      const bar = d.status === "active" ? `<span class="bar"><i style="width:${Math.round(share * 100)}%"></i></span>` : "";
      return `<div class="tile ${d.status}${STOPS.includes(d.day) ? " stop" : ""}${OOO.has(d.day) ? " ooo" : ""}" data-day="${d.day}" tabindex="0" aria-label="Day ${d.day}, ${STATUS[d.status]}">${dd(d.day)}${bar}</div>`;
    }).join("")}</div></div>`;
  }).join("");
  document.getElementById("roadmap").innerHTML = rows + `<div class="tile-legend">
    <span><i class="tile done"></i>Done</span><span><i class="tile closed"></i>Closed, boxes open</span><span><i class="tile active"></i>In progress (bar: tracks merged)</span>
    <span><i class="tile"></i>Ready</span><span><i class="tile provisional"></i>Provisional</span>
    <span><i class="tile stop"></i>Ends a phase</span><span><i class="tile ooo"></i>Run out of numeric order</span></div>`;
  document.querySelectorAll("#roadmap .tile[data-day]").forEach(tile => {
    const d = days.find(x => x.day === +tile.dataset.day);
    const show = ev => showTip(ev, `<b>Day ${dd(d.day)} · ${STATUS[d.status]}</b><div style="margin:3px 0 6px">${md(d.title)}</div>
      <div class="row"><span>estimate</span><span>${d.expected_first ?? "–"} → ${d.expected ?? "–"} PRs</span></div>
      <div class="row"><span>track PRs merged</span><span>${d.track_prs}</span></div>
      <div class="row"><span>criteria ticked</span><span>${d.criteria.ticked}/${d.criteria.total}</span></div>
      <div class="row"><span>tagged new / hold</span><span>${d.criteria.new} / ${d.criteria.hold}</span></div>
      <div class="row"><span>merged on</span><span>${d.worked ? shortDate(d.worked[0]) + (d.worked[1] !== d.worked[0] ? " – " + shortDate(d.worked[1]) : "") : "–"}</span></div>
      ${OOO.has(d.day) ? `<div class="row"><span>runs between</span><span>${ranBetween(d.day).join(" and ")}</span></div>` : ""}`);
    tile.addEventListener("mousemove", show);
    tile.addEventListener("focus", () => { const r = tile.getBoundingClientRect(); show({ clientX: r.right, clientY: r.bottom }); });
    tile.addEventListener("mouseleave", hideTip);
    tile.addEventListener("blur", hideTip);
  });
}

/* ---------- evidence ---------- */
// Counted from each finished day's ticked criteria; the test states are main's, from the script.
function drawEvidence() {
  const days = DATA.days.filter(d => finished(d) && d.evidence.some(c => c.ticked));
  // A day written before the evidence format was never asked to record these: its zero is "not measured".
  const cell = (n, of, pre) => pre ? `<td class="r pre">${n ? `${n}/${of}` : "not measured"}</td>`
    : `<td class="r${n ? "" : " none"}">${n}/${of}</td>`;
  document.getElementById("evidence").innerHTML = `<thead><tr><th>Day</th><th class="r">Cite a PR</th><th class="r">Name a test</th><th class="r">Seen red</th><th class="r">Tests on main</th><th>Title</th></tr></thead><tbody>` +
    days.map(d => {
      const ev = d.evidence.filter(c => c.ticked), states = ev.flatMap(c => Object.values(c.tests));
      const off = states.filter(s => s !== "present").length;
      const pre = !d.evidence_format;
      return `<tr${pre ? ` title="Spec written before the evidence format (#55)"` : ""}><td class="num">${dd(d.day)}${pre ? ` <span class="pre-tag">pre-format</span>` : ""}</td>${cell(ev.filter(c => c.prs.length).length, ev.length, pre)}${cell(ev.filter(c => Object.keys(c.tests).length).length, ev.length, pre)}${cell(ev.filter(c => c.red).length, ev.length, pre)}<td class="r${off ? " none" : ""}">${states.length ? `${states.length - off}/${states.length} present` : "–"}</td><td class="t">${md(d.title)}</td></tr>`;
    }).join("") + "</tbody>";
  const gaps = days.flatMap(d => d.evidence.flatMap(c => Object.entries(c.tests).filter(([, s]) => s !== "present")
    .map(([t, s]) => `<li>Day ${dd(d.day)}: <code>${esc(t)}</code> is ${s} · ${md(c.claim)}</li>`)));
  document.getElementById("evidence-gaps").innerHTML = `<h3>Named tests not running on main</h3>` +
    (gaps.length ? `<ul>${gaps.join("")}</ul>` : `<p>None: every test a finished day names is on <code>main</code>, and none can be skipped.</p>`);
  const strip = c => `<svg class="strip" viewBox="0 0 ${N} 1" preserveAspectRatio="none" shape-rendering="crispEdges" role="img" aria-label="${c.hits.filter(n => n).length} of ${c.hits.length} merges found lines">` +
    c.hits.map((n, k) => `<rect x="${c.since + k}" y="0" width="1" height="1" fill="var(--${n ? "code" : "close"})"><title>${prLabel(R[c.since + k])}: ${n} line${n === 1 ? "" : "s"}</title></rect>`).join("") + "</svg>";
  document.getElementById("removals").innerHTML = `<thead><tr><th>Day</th><th>Check</th><th>From</th><th class="r">On main</th><th>Every merge since</th></tr></thead><tbody>` +
    (DATA.removals || []).map(c => { const now = c.hits[c.hits.length - 1];
      return `<tr><td class="num">${dd(c.day)}</td><td class="t"><code>${esc(c.cmd)}</code></td><td class="num">${esc(c.base)}</td><td class="r${now ? " none" : ""}">${now ? now + " lines" : "nothing"}</td><td style="width:32%">${strip(c)}</td></tr>`; }).join("") + "</tbody>";
}

/* ---------- hand-offs ---------- */
// The script sorts them by the receiving day's place in the run order, open ones first.
function drawHandOffs() {
  const groups = [];
  for (const h of DATA.hand_offs || []) {
    if (!groups.length || groups[groups.length - 1].to !== h.to) groups.push({ to: h.to, items: [] });
    groups[groups.length - 1].items.push(h);
  }
  const source = s => s.startsWith("Day ") ? esc(s) : `<code title="${esc(s)}">${esc(s.split("/").pop())}</code>`;
  document.getElementById("hand-offs").innerHTML = groups.length ? groups.map((g, k) => {
    const open = g.items.filter(h => !h.picked), d = DATA.days.find(x => x.day === g.to);
    return `<details${k === 0 ? " open" : ""}><summary><b>Day ${dd(g.to)}</b> ${d ? md(d.title) : ""} · <span class="${open.length ? "none" : ""}">${open.length} open</span> of ${g.items.length}</summary>
      <ul>${g.items.map(h => `<li class="${h.picked ? "picked" : ""}">${source(h.source)}: ${md(h.text)}${h.picked ? " <em>(picked up)</em>" : ""}</li>`).join("")}</ul></details>`;
  }).join("") : "<p>No finished day names a day not yet run.</p>";
}

/* ---------- conclusion ---------- */
// Everything here comes from the README at the head of main or from git, so it cannot go stale
// between refreshes. When the README falls behind the phases, the panel says so.
function drawConclusion() {
  const days = DATA.days, now = DATA.now, { reads, measured } = DATA.conclusion;
  const phases = [...new Set(days.map(d => d.phase))].filter(p => p != null);
  const closed = Math.max(-1, ...phases.filter(p => days.filter(d => d.phase === p).every(finished)));
  const read = reads[reads.length - 1];
  document.getElementById("conclusion-eyebrow").textContent = closed < 0
    ? "Conclusion · no phase closed yet"
    : `Conclusion · after Phase ${closed} · ${N - 1} merges`;
  const behind = !read ? `<p class="stale">The README has no phase read yet.</p>`
    : read.phase < closed ? `<p class="stale">The README's latest read is from the end of Phase ${read.phase}; Phase ${closed} has closed since and has none yet.</p>` : "";
  document.getElementById("read").innerHTML = behind + (read
    ? read.text.split(/\n\s*\n/).map(p => `<p>${md(p.replace(/\s*\n\s*/g, " "))}</p>`).join("")
    : "");

  const done = days.filter(finished).length;
  const cur = days.find(d => d.day === now.current_day);
  let next = `<p><b>${md(now.next_step)}</b></p>`;
  if (cur) {
    const ds = days.filter(d => d.phase === cur.phase);
    const left = ds.filter(d => !finished(d)).length;
    const prov = days.filter(d => d.status === "provisional");
    next = `<p><b>${md(now.next_step)}</b></p>
      <p>Phase ${cur.phase}, ${PHASES[cur.phase] || ""}, ends at Day ${dd(ds[ds.length - 1].day)}: ${left} day${left === 1 ? "" : "s"} left, counting this one.</p>
      ${prov.length ? `<p>${prov.length} day specs are still provisional, from Day ${dd(prov[0].day)} on.</p>` : ""}`;
  }
  document.getElementById("facts").innerHTML = `
    <div class="finding"><h3>By the count</h3>
      <p>${done} of ${days.length} days finished (${days.filter(d => d.status === "closed").length} with boxes open), ${closed + 1} of ${phases.length} phases closed. ${N - 1} merges to <code>main</code>:
      ${nKind("code")} code tracks, ${nKind("spec")} spec changes, ${nKind("close")} closing PRs and ${nKind("other")} others.</p></div>
    <div class="finding"><h3>What is next</h3>${next}</div>`;

  document.getElementById("measured").innerHTML = measured.length
    ? `<thead><tr><th>Question</th><th>Evidence so far</th></tr></thead><tbody>` +
      measured.map(([q, e]) => `<tr><td class="t">${md(q)}</td><td class="t">${md(e)}</td></tr>`).join("") + "</tbody>"
    : `<tbody><tr><td class="t">The README has no measurement table.</td></tr></tbody>`;
}

/* ---------- tokens ---------- */
const MODEL_VAR = { "claude-opus-5-5": "var(--m-opus)", "claude-opus-5": "var(--m-opus-prev)", "claude-sonnet-5": "var(--m-sonnet)", "claude-haiku-4-5": "var(--m-haiku)" };
const modelName = m => m.replace(/^claude-/, "").replace(/^(\w)(\w*)-/, (_, a, b) => a.toUpperCase() + b + " ").replace(/-/g, ".");
const kilo = n => n >= 1e6 ? fmt(n / 1e6, 2) + "M" : n >= 1e3 ? fmt(n / 1e3, 0) + "k" : String(n);
function drawTokens() {
  const T = DATA.tokens || [];
  const box = document.getElementById("token-bars");
  if (!T.length) { box.innerHTML = '<p class="load-error">No token counts yet: run <code>python scripts/token-usage.py</code>.</p>'; return; }
  const sum = (rows, k) => rows.reduce((a, r) => a + r[k], 0);
  // Every reply re-reads the whole conversation, so context per reply is what a long session costs.
  const ctx = rows => sum(rows, "input") + sum(rows, "cache_write") + sum(rows, "cache_read");
  const perReply = rows => kilo(Math.round(ctx(rows) / Math.max(1, sum(rows, "replies"))));
  const models = [...new Set(T.map(r => r.model))].sort((a, b) => sum(T.filter(r => r.model === b), "output") - sum(T.filter(r => r.model === a), "output"));
  const color = m => MODEL_VAR[m] || "var(--other)";
  document.getElementById("token-legend").innerHTML = models.map(m =>
    `<span><i class="dot" style="background:${color(m)}"></i>${esc(modelName(m))} · ${kilo(sum(T.filter(r => r.model === m), "output"))}</span>`).join("");
  // The log counts by model, not by agent; the implementer is the one agent on Haiku, so its share is the Haiku rows.
  const haiku = T.filter(r => /haiku/.test(r.model)), pct = (a, b) => fmt(b ? a / b * 100 : 0, 1) + "%";
  document.getElementById("token-implementer").innerHTML = haiku.length
    ? `<b>The implementer</b> (Haiku, which writes the track code from a brief) used <b>${pct(ctx(haiku), ctx(T))}</b> of the context read (${kilo(ctx(haiku))} of ${kilo(ctx(T))}) and <b>${pct(sum(haiku, "output"), sum(T, "output"))}</b> of the output (${kilo(sum(haiku, "output"))} of ${kilo(sum(T, "output"))}). The main sessions (specs, audits, review, the breaks on purpose, the pull requests) used the rest. Counted by model: any other Haiku reply would count here too.`
    : "";
  const dates = [...new Set(T.map(r => r.date))].sort();
  const H = 260, P = { l: 56, r: 12, t: 12, b: 46 }, bw = (LW - P.l - P.r) / dates.length;
  const perDate = dates.map(d => T.filter(r => r.date === d));
  const top = Math.max(...perDate.map(rs => sum(rs, "output")));
  const yMax = Math.ceil(top / 10 ** Math.floor(Math.log10(top)) * 2) / 2 * 10 ** Math.floor(Math.log10(top));
  const ly = v => P.t + (1 - v / yMax) * (H - P.t - P.b);
  box.innerHTML = "";
  const svg = el("svg", { viewBox: `0 0 ${LW} ${H}`, role: "img", "aria-label": "Output tokens per date by model" }, box);
  steps(0, yMax, 4).forEach(t => {
    el("line", { x1: P.l, x2: LW - P.r, y1: ly(t), y2: ly(t), stroke: "var(--grid)" }, svg);
    el("text", { x: P.l - 8, y: ly(t) + 4, "text-anchor": "end", class: "num" }, svg).textContent = kilo(t);
  });
  perDate.forEach((rs, i) => {
    const x = P.l + i * bw;
    let y = 0;
    models.forEach(m => {
      const v = sum(rs.filter(r => r.model === m), "output");
      if (!v) return;
      const rect = el("rect", { x: x + bw * 0.15, y: ly(y + v), width: bw * 0.7, height: ly(y) - ly(y + v), fill: color(m) }, svg);
      rect.addEventListener("mousemove", ev => showTip(ev, `<b>${dates[i]} · ${esc(modelName(m))}</b>
        <div class="row"><span>output</span><span>${kilo(v)}</span></div>
        <div class="row"><span>input + cache</span><span>${kilo(ctx(rs.filter(r => r.model === m)))}</span></div>
        <div class="row"><span>context per reply</span><span>${perReply(rs.filter(r => r.model === m))}</span></div>
        <div class="row"><span>replies</span><span>${sum(rs.filter(r => r.model === m), "replies")}</span></div>`));
      rect.addEventListener("mouseleave", hideTip);
      y += v;
    });
    el("text", { x: x + bw / 2, y: H - P.b + 18, "text-anchor": "middle", class: "num" }, svg).textContent = dates[i].slice(5);
    el("text", { x: x + bw / 2, y: H - P.b + 34, "text-anchor": "middle", class: "num" }, svg).textContent = perReply(rs) + " ctx";
  });
  const days = [...new Set(T.map(r => r.day))].sort((a, b) => rank(a) - rank(b));
  const cell = (rs, m) => { const v = sum(rs.filter(r => r.model === m), "output"); return `<td class="num">${v ? kilo(v) : "–"}</td>`; };
  document.getElementById("token-days").innerHTML =
    `<thead><tr><th>Day</th><th>Replies</th><th>Output</th><th>Input + cache</th><th>Context per reply</th>${models.map(m => `<th>${esc(modelName(m))}</th>`).join("")}</tr></thead><tbody>` +
    days.map(d => {
      const rs = T.filter(r => r.day === d);
      return `<tr><td>${d == null ? "Between days (main, tooling, docs)" : "Day " + dd(d)}</td><td class="num">${sum(rs, "replies")}</td>
        <td class="num">${kilo(sum(rs, "output"))}</td><td class="num">${kilo(ctx(rs))}</td><td class="num">${perReply(rs)}</td>${models.map(m => cell(rs, m)).join("")}</tr>`;
    }).join("") + "</tbody>";
}

drawNow(); drawWork(); drawPerDay(); drawVerification(); drawTokens(); drawEvidence(); drawHandOffs(); drawConclusion(); drawFindings(); drawTable(); setSel(sel);
}

function start() {
  if (window.SPEC_DRIFT) return boot(window.SPEC_DRIFT);
  document.getElementById("today").innerHTML = '<p class="load-error">The data did not load. Build the page with <code>python scripts/spec-drift.py --build dashboard.html</code>; if this is the built page, it was published without its data line.</p>';
}
