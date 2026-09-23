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

/* ---------- derived numbers ---------- */
const first = R[0], last = R[N - 1];
const byKind = { spec: 0, code: 0, close: 0, other: 0 };
for (let i = 1; i < N; i++) byKind[R[i].kind] += R[i].gap_full - R[i - 1].gap_full;
const gapClosed = first.gap_full - last.gap_full;
const specShare = -byKind.spec / gapClosed;
const nKind = k => R.filter(r => r.kind === k).length;

/* ---------- stats ---------- */
document.getElementById("stats").innerHTML = `
  <div class="stat"><span class="k">Drift from the plan as written</span>
    <span class="v num">${fmt(last.drift)}°</span>
    <span class="n">0° on Sep 20. It rises by small steps and never jumps.</span></div>
  <div class="stat"><span class="k">Gap between spec and code</span>
    <span class="v num">${fmt(first.gap_full)}° → ${fmt(last.gap_full)}°</span>
    <span class="n">${fmt(gapClosed)}° closed across ${N - 1} merges</span></div>
  <div class="stat"><span class="k">Share of the gap closed by the spec</span>
    <span class="v num">${Math.round(specShare * 100)}%</span>
    <span class="n"><span class="sw" style="background:var(--spec)"></span>${nKind("spec")} spec-change PRs moved the spec toward the code; <span class="sw" style="background:var(--code)"></span>${nKind("code")} code PRs closed ${Math.round(-byKind.code / gapClosed * 100)}%</span></div>
  <div class="stat"><span class="k">Spec text since Sep 20</span>
    <span class="v num">−${last.deleted} <small>/ +${last.added} lines</small></span>
    <span class="n">of ${DATA.origin_lines} lines as written: ${Math.round(last.deleted / DATA.origin_lines * 100)}% rewritten, the rest kept</span></div>`;

/* ---------- where we are ---------- */
const PHASES = ["Make the split safe", "Modularise in place", "Gateway + JWT", "Extract job-service",
  "Extract matching-service", "Extract application-service", "Functions + uploads bucket", "Kubernetes + IaC"];
const STOPS = [16, 20, 24, 28, 31, 37];
const STATUS = { done: "Done", active: "In progress", ready: "Ready", provisional: "Provisional" };
function drawNow() {
  const now = DATA.now, days = DATA.days;
  const cur = days.find(d => d.day === now.current_day);
  const done = days.filter(d => d.status === "done").length;
  document.getElementById("eyebrow").textContent =
    `jobmatch-microservices · Day ${dd(now.current_day)} of ${days.length} · ${N - 1} merged PRs`;
  const c = cur.criteria;
  const prs = cur.prs.length
    ? cur.prs.map(p => `<span class="chip" style="color:${KVAR[p.kind]}" title="${esc(p.title)}">#${p.number}</span>`).join(" ")
    : "none yet";
  const open = now.open_prs.map(p => `<a href="${esc(p.url)}" target="_blank" rel="noopener">#${p.number}</a> ${esc(p.title)}`).join("<br>");
  document.getElementById("today").innerHTML = `
    <div class="eyebrow">Day ${dd(cur.day)} · ${STATUS[cur.status]}</div>
    <div class="day-title">${md(cur.title)}</div>
    <div class="phase">Phase ${cur.phase}: ${PHASES[cur.phase] || ""} · ${done} of ${days.length} days done</div>
    <dl class="kv">
      <dt>Tracks</dt><dd><div class="trks">${cur.tracks.map(t => `<span class="trk ${cur.tracks_merged.includes(t) ? "done" : ""}" title="${esc(cur.track_work[t] || "")}">${t}</span>`).join("")}</div></dd>
      <dt>Criteria</dt><dd><span class="num">${c.ticked}/${c.total}</span> ticked · <span class="num">${c.new}</span> new · <span class="num">${c.hold}</span> hold</dd>
      <dt>Estimate</dt><dd><span class="num">${cur.expected_first ?? "–"} → ${cur.expected ?? "–"}</span> PRs, as first written → now</dd>
      <dt>Merged</dt><dd>${prs}</dd>
    </dl>
    <div class="next"><span class="eyebrow">Next step</span><b>${md(now.next_step)}</b>${open ? `<div>${open}</div>` : ""}</div>
    <div class="refreshed">refreshed ${esc(now.generated.replace("T", " "))} · main at ${esc(now.head)}${now.last_pr ? ` (#${now.last_pr})` : ""}</div>`;
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
    <span><i class="tile done"></i>Done</span><span><i class="tile active"></i>In progress (bar: tracks merged)</span>
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

/* ---------- conclusion ---------- */
// Everything here comes from the README at the head of main or from git, so it cannot go stale
// between refreshes. When the README falls behind the phases, the panel says so.
function drawConclusion() {
  const days = DATA.days, now = DATA.now, { reads, measured } = DATA.conclusion;
  const phases = [...new Set(days.map(d => d.phase))].filter(p => p != null);
  const closed = Math.max(-1, ...phases.filter(p => days.filter(d => d.phase === p).every(d => d.status === "done")));
  const read = reads[reads.length - 1];
  document.getElementById("conclusion-eyebrow").textContent = closed < 0
    ? "Conclusion · no phase closed yet"
    : `Conclusion · after Phase ${closed} · ${N - 1} merges`;
  const behind = !read ? `<p class="stale">The README has no phase read yet.</p>`
    : read.phase < closed ? `<p class="stale">The README's latest read is from the end of Phase ${read.phase}; Phase ${closed} has closed since and has none yet.</p>` : "";
  document.getElementById("read").innerHTML = behind + (read
    ? read.text.split(/\n\s*\n/).map(p => `<p>${md(p.replace(/\s*\n\s*/g, " "))}</p>`).join("")
    : "");

  const done = days.filter(d => d.status === "done").length;
  const cur = days.find(d => d.day === now.current_day);
  let next = "<p>Every day is done.</p>";
  if (cur) {
    const ds = days.filter(d => d.phase === cur.phase);
    const left = ds.filter(d => d.status !== "done").length;
    const prov = days.filter(d => d.status === "provisional");
    next = `<p><b>${md(now.next_step)}</b></p>
      <p>Phase ${cur.phase}, ${PHASES[cur.phase] || ""}, ends at Day ${dd(ds[ds.length - 1].day)}: ${left} day${left === 1 ? "" : "s"} left, counting this one.</p>
      ${prov.length ? `<p>${prov.length} day specs are still provisional, from Day ${dd(prov[0].day)} on.</p>` : ""}`;
  }
  document.getElementById("facts").innerHTML = `
    <div class="finding"><h3>By the count</h3>
      <p>${done} of ${days.length} days done, ${closed + 1} of ${phases.length} phases closed. ${N - 1} merges to <code>main</code>:
      ${nKind("code")} code tracks, ${nKind("spec")} spec changes, ${nKind("close")} closing PRs and ${nKind("other")} others.</p></div>
    <div class="finding"><h3>What is next</h3>${next}</div>`;

  document.getElementById("measured").innerHTML = measured.length
    ? `<thead><tr><th>Question</th><th>Evidence so far</th></tr></thead><tbody>` +
      measured.map(([q, e]) => `<tr><td class="t">${md(q)}</td><td class="t">${md(e)}</td></tr>`).join("") + "</tbody>"
    : `<tbody><tr><td class="t">The README has no measurement table.</td></tr></tbody>`;
}

drawNow(); drawConclusion();
}

function start() {
  if (window.SPEC_DRIFT) return boot(window.SPEC_DRIFT);
  document.getElementById("today").innerHTML = '<p class="load-error">The data did not load. Build the page with <code>python scripts/spec-drift.py --build dashboard.html</code>; if this is the built page, it was published without its data line.</p>';
}
