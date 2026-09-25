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
  <div class="row"><span>gap</span><span>${fmt(r.gap_full)}°</span></div>
  <div class="row"><span>reached names in code</span><span>${r.coverage == null ? "–" : fmt(r.coverage * 100, 1) + "%"}</span></div>`;
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
    if (d > r.day) forward += n;
  }
});

/* ---------- stats ---------- */
document.getElementById("stats").innerHTML = `
  <div class="stat"><span class="k">Drift from the plan as written</span>
    <span class="v num">${fmt(last.drift)}°</span>
    <span class="n">0° on Sep 20. ${bigStep < 10 ? "It rises by small steps and never jumps." : `Its biggest single step was ${fmt(bigStep, 2)}° (${prLabel(R.find(r => r.step === bigStep))}).`}</span></div>
  <div class="stat"><span class="k">Gap between spec and code</span>
    <span class="v num">${fmt(first.gap_full)}° → ${fmt(last.gap_full)}°</span>
    <span class="n">${fmt(gapClosed)}° closed across ${N - 1} merges</span></div>
  <div class="stat"><span class="k">Share of the gap closed by the spec</span>
    <span class="v num">${Math.round(specShare * 100)}%</span>
    <span class="n"><span class="sw" style="background:var(--spec)"></span>${nKind("spec")} spec-change PRs ${specShare >= 0 ? "moved the spec toward the code" : "widened the gap, naming what was not built yet"}; <span class="sw" style="background:var(--code)"></span>${nKind("code")} code PRs closed ${Math.round(-byKind.code / gapClosed * 100)}%</span></div>
  <div class="stat"><span class="k">Spec text since Sep 20</span>
    <span class="v num">−${last.deleted} <small>/ +${last.added} lines</small></span>
    <span class="n">of ${DATA.origin_lines} lines as written: ${Math.round(last.deleted / DATA.origin_lines * 100)}% rewritten, the rest kept</span></div>`;
document.getElementById("headline").textContent = specShare >= -byKind.code / gapClosed
  ? "The plan moved to meet the code" : "The spec leads, the code follows";
document.querySelectorAll(".vocab-n").forEach(e => { e.textContent = DATA.vocab_size; });
document.getElementById("n-vectors").textContent = 2 * N;
const merged = R.filter(r => r.pr).map(r => r.pr);
document.getElementById("pr-range").textContent = `${N - 1} merges, #${Math.min(...merged)}–#${Math.max(...merged)}`;
document.getElementById("pc1").textContent = Math.round(DATA.pca_var[0] * 100) + "%";
document.getElementById("pc2").textContent = Math.round(DATA.pca_var[1] * 100) + "%";

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

/* ---------- trajectory ---------- */
const trajBox = document.getElementById("traj");
const TW = 600, TH = 440, TP = 34;
const pts = R.flatMap(r => [r.spec_xy, r.code_xy]);
const xs = pts.map(p => p[0]), ys = pts.map(p => p[1]);
const x0 = Math.min(...xs), x1 = Math.max(...xs), y0 = Math.min(...ys), y1 = Math.max(...ys);
const k = Math.min((TW - 2 * TP) / (x1 - x0), (TH - 2 * TP) / (y1 - y0));
const ox = (TW - (x1 - x0) * k) / 2, oy = (TH - (y1 - y0) * k) / 2;
const tx = x => ox + (x - x0) * k, ty = y => TH - (oy + (y - y0) * k);

function drawTraj() {
  trajBox.innerHTML = "";
  const svg = el("svg", { viewBox: `0 0 ${TW} ${TH}`, role: "img", "aria-label": "Spec and code paths projected onto two principal components" }, trajBox);
  el("rect", { x: 0.5, y: 0.5, width: TW - 1, height: TH - 1, rx: 8, fill: "none", stroke: "var(--grid)" }, svg);
  el("line", { x1: tx(0), x2: tx(0), y1: 8, y2: TH - 8, stroke: "var(--grid)" }, svg);
  el("line", { x1: 8, x2: TW - 8, y1: ty(0), y2: ty(0), stroke: "var(--grid)" }, svg);
  el("text", { x: TW - 12, y: TH - 12, "text-anchor": "end" }, svg).textContent = `PC1 · ${Math.round(DATA.pca_var[0] * 100)}% of variance`;
  el("text", { x: tx(0) + 6, y: 20 }, svg).textContent = `PC2 · ${Math.round(DATA.pca_var[1] * 100)}%`;
  for (const side of ["spec", "code"]) {
    const key = side + "_xy", col = `var(--${side})`;
    const path = rs => rs.map(r => `${tx(r[key][0])},${ty(r[key][1])}`).join(" ");
    el("polyline", { points: path(R), fill: "none", stroke: col, "stroke-width": 1.5, "stroke-opacity": 0.2, "stroke-linejoin": "round" }, svg);
    el("polyline", { points: path(R.slice(0, sel + 1)), fill: "none", stroke: col, "stroke-width": 2, "stroke-linejoin": "round", "stroke-linecap": "round" }, svg);
  }
  const s = R[sel];
  el("line", { x1: tx(s.spec_xy[0]), y1: ty(s.spec_xy[1]), x2: tx(s.code_xy[0]), y2: ty(s.code_xy[1]), stroke: "var(--gap)", "stroke-width": 1.5, "stroke-dasharray": "5 4" }, svg);
  const mx = (tx(s.spec_xy[0]) + tx(s.code_xy[0])) / 2, my = (ty(s.spec_xy[1]) + ty(s.code_xy[1])) / 2;
  el("text", { x: mx, y: my - 8, "text-anchor": "middle", class: "lbl-strong" }, svg).textContent = `gap ${fmt(s.gap_full)}°`;
  for (const side of ["spec", "code"]) {
    R.forEach((r, i) => {
      const cx = tx(r[side + "_xy"][0]), cy = ty(r[side + "_xy"][1]);
      el("circle", { cx, cy, r: i === sel ? 6.5 : 3.6, fill: `var(--${side})`, stroke: "var(--surface)", "stroke-width": 2, opacity: i <= sel ? 1 : 0.22 }, svg);
      const h = el("circle", { cx, cy, r: 9, class: "hit" }, svg);
      h.addEventListener("mousemove", ev => showTip(ev, `<div style="color:var(--${side});font-weight:600;margin-bottom:2px">${side === "spec" ? "Spec" : "Code"} vector</div>` + rowTip(r)));
      h.addEventListener("mouseleave", hideTip);
      h.addEventListener("click", () => setSel(i));
    });
  }
  const lab = (p, txt, dx, dy, anchor, cls = "lbl") => { el("text", { x: tx(p[0]) + dx, y: ty(p[1]) + dy, "text-anchor": anchor, class: cls }, svg).textContent = txt; };
  lab(R[0].spec_xy, "plan as written, Sep 20", -10, -12, "end");
  lab(R[0].code_xy, "monolith, Sep 20", 10, -12, "start");
  if (sel > 3) {
    lab(s.spec_xy, `spec at ${prLabel(s)}`, 0, 22, "middle", "lbl-strong");
    lab(s.code_xy, `code at ${prLabel(s)}`, 0, 22, "middle", "lbl-strong");
  }
}

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
      <dt>Gap, spec vs code</dt><dd>${fmt(s.gap_full, 2)}° <span style="color:var(--muted)">(${sign(dGap)})</span></dd>
      <dt>Reached-day names in code</dt><dd>${s.coverage == null ? "–" : fmt(s.coverage * 100, 1) + "%"}</dd>
      <dt>Code lines changed</dt><dd>${s.code_churn.toLocaleString()}</dd>
      <dt>Spec lines changed</dt><dd>${edits.reduce((a, b) => a + b[1], 0)}</dd>
    </dl>
    ${edits.length ? `<div style="font-size:12.5px;color:var(--ink-2)">${edits.slice(0, 5).map(([f, n]) => `<div style="display:flex;justify-content:space-between;gap:10px"><code>${esc(f.replace("specs/", ""))}</code><span class="num">${n}</span></div>`).join("")}${edits.length > 5 ? `<div style="color:var(--muted)">+${edits.length - 5} more files</div>` : ""}</div>` : ""}`;
}

/* ---------- line charts ---------- */
const LW = 1000, LP = { l: 44, r: 104, t: 14, b: 46 };
const lx = i => LP.l + (i / (N - 1)) * (LW - LP.l - LP.r);
function dayStarts() {
  const out = []; let d = -1;
  R.forEach((r, i) => { if (r.day !== d) { out.push([i, r.day]); d = r.day; } });
  return out;
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
    el("text", { x: lx(i) + 3, y: H - LP.b + (strip ? 30 : 16), class: "num" }, svg).textContent = "D" + dd(d);
  });
  if (strip) {
    const w = (LW - LP.l - LP.r) / (N - 1);
    R.forEach((r, i) => el("rect", { x: lx(i) - w / 2 + 1, y: H - LP.b + 5, width: Math.max(2, w - 2), height: 8, rx: 2, fill: KVAR[r.kind] }, svg));
  }
  el("line", { x1: lx(sel), x2: lx(sel), y1: LP.t, y2: H - LP.b, stroke: "var(--ink-2)", "stroke-width": 1 }, svg);
  const cross = el("line", { x1: 0, x2: 0, y1: LP.t, y2: H - LP.b, stroke: "var(--muted)", "stroke-dasharray": "3 3", visibility: "hidden" }, svg);
  series.forEach(s => {
    const pts = R.map((r, i) => [i, s.get(r)]).filter(p => p[1] != null);
    el("polyline", { points: pts.map(([i, v]) => `${lx(i)},${ly(v)}`).join(" "), fill: "none", stroke: s.color, "stroke-width": 2, "stroke-linejoin": "round", "stroke-linecap": "round" }, svg);
    const v = s.get(R[sel]);
    if (v != null) el("circle", { cx: lx(sel), cy: ly(v), r: 5, fill: s.color, stroke: "var(--surface)", "stroke-width": 2 }, svg);
    const end = pts[pts.length - 1];
    el("text", { x: lx(end[0]) + 10, y: ly(end[1]) + 4, class: "lbl" }, svg).textContent = `${s.name} ${fmt(end[1], digits)}${unit}`;
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
const degMax = Math.ceil(Math.max(...R.map(r => Math.max(r.drift, r.gap_full))) / 15) * 15;
const covMin = Math.min(92, Math.floor(Math.min(...R.filter(r => r.coverage != null).map(r => r.coverage * 100)) / 2) * 2);
function drawLines() {
  lineChart(document.getElementById("lines"), {
    H: 330, yMin: 0, yMax: degMax, ticks: steps(0, degMax, degMax / 15), unit: "°", strip: true,
    label: "Drift and gap in degrees across merges",
    series: [
      { name: "gap", color: "var(--gap)", get: r => r.gap_full },
      { name: "drift", color: "var(--spec)", get: r => r.drift },
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
}
const evMax = Math.max(4, Math.ceil(Math.max(0, ...R.map(r => r.evidence ? r.evidence.present : 0)) / 20) * 20);

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
    const where = !d ? "the plan" : d > r.day ? `a future day (working on day ${r.day})` : d === r.day ? "the current day" : "a finished day";
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
  drawScrubNow(); drawTraj(); drawReadout(); drawLines(); drawHeat();
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
      return `<div class="tile ${d.status}${STOPS.includes(d.day) ? " stop" : ""}" data-day="${d.day}" tabindex="0" aria-label="Day ${d.day}, ${STATUS[d.status]}">${dd(d.day)}${bar}</div>`;
    }).join("")}</div></div>`;
  }).join("");
  document.getElementById("roadmap").innerHTML = rows + `<div class="tile-legend">
    <span><i class="tile done"></i>Done</span><span><i class="tile closed"></i>Closed, boxes open</span><span><i class="tile active"></i>In progress (bar: tracks merged)</span>
    <span><i class="tile"></i>Ready</span><span><i class="tile provisional"></i>Provisional</span>
    <span><i class="tile stop"></i>Ends a phase</span></div>`;
  document.querySelectorAll("#roadmap .tile[data-day]").forEach(tile => {
    const d = days.find(x => x.day === +tile.dataset.day);
    const show = ev => showTip(ev, `<b>Day ${dd(d.day)} · ${STATUS[d.status]}</b><div style="margin:3px 0 6px">${md(d.title)}</div>
      <div class="row"><span>estimate</span><span>${d.expected_first ?? "–"} → ${d.expected ?? "–"} PRs</span></div>
      <div class="row"><span>track PRs merged</span><span>${d.track_prs}</span></div>
      <div class="row"><span>criteria ticked</span><span>${d.criteria.ticked}/${d.criteria.total}</span></div>
      <div class="row"><span>tagged new / hold</span><span>${d.criteria.new} / ${d.criteria.hold}</span></div>`);
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
  const cell = (n, of) => `<td class="r${n ? "" : " none"}">${n}/${of}</td>`;
  document.getElementById("evidence").innerHTML = `<thead><tr><th>Day</th><th class="r">Cite a PR</th><th class="r">Name a test</th><th class="r">Seen red</th><th class="r">Tests on main</th><th>Title</th></tr></thead><tbody>` +
    days.map(d => {
      const ev = d.evidence.filter(c => c.ticked), states = ev.flatMap(c => Object.values(c.tests));
      const off = states.filter(s => s !== "present").length;
      return `<tr><td class="num">${dd(d.day)}</td>${cell(ev.filter(c => c.prs.length).length, ev.length)}${cell(ev.filter(c => Object.keys(c.tests).length).length, ev.length)}${cell(ev.filter(c => c.red).length, ev.length)}<td class="r${off ? " none" : ""}">${states.length ? `${states.length - off}/${states.length} present` : "–"}</td><td class="t">${md(d.title)}</td></tr>`;
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

drawNow(); drawEvidence(); drawHandOffs(); drawConclusion(); drawFindings(); drawTable(); setSel(sel);
}

function start() {
  if (window.SPEC_DRIFT) return boot(window.SPEC_DRIFT);
  document.getElementById("today").innerHTML = '<p class="load-error">The data did not load. Build the page with <code>python scripts/spec-drift.py --build dashboard.html</code>; if this is the built page, it was published without its data line.</p>';
}
