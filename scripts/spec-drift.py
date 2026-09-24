"""Measure how the spec and the code move against each other, merge by merge.

Writes one JSON file for the migration dashboard: drift, gap and a 2D projection per merge on
main, and each day's status, tracks and criteria. Needs git, the gh CLI and numpy.

    python scripts/spec-drift.py --out data.json
    python scripts/spec-drift.py --build dashboard.html  # docs/dashboard/ as one file, data inlined

Drift: angle between today's spec text (plan.md + specs/*.md, log word counts) and the spec as
first committed. Gap: angle between spec and code over the code-like names the specs put in
backticks, counted in the specs and in the code (Markdown, data/, screenshots and this dashboard
left out: its method text names the very identifiers it counts).
"""
import argparse
import collections
import datetime
import json
import math
import os
import re
import subprocess

import numpy as np

WORD = re.compile(r"[a-z0-9_]{2,}")
TICK = re.compile(r"`([^`\n]+)`")
IDENT = re.compile(r"[A-Za-z_][A-Za-z0-9_]{3,}")
CRITERION = re.compile(r"^- \[( |x)\]", re.M)
PHASE_READ = re.compile(r"^\*\*Read [^*\n]*end of Phase (\d+)\.\*\*.*?(?=^\*\*Read |^## |\Z)", re.M | re.S)
PAGE = os.path.join(os.path.dirname(os.path.abspath(__file__)), os.pardir, "docs", "dashboard")


def run(*args):
    return subprocess.run(args, capture_output=True, text=True, encoding="utf-8",
                          errors="replace", check=True).stdout


class Blobs:
    """One git cat-file process for every file read, instead of one git show each."""

    def __init__(self):
        self.proc = subprocess.Popen(["git", "cat-file", "--batch"], stdin=subprocess.PIPE,
                                     stdout=subprocess.PIPE)

    def read(self, sha, path):
        self.proc.stdin.write(f"{sha}:{path}\n".encode())
        self.proc.stdin.flush()
        header = self.proc.stdout.readline().split()
        if header[-1] == b"missing":
            return ""
        body = self.proc.stdout.read(int(header[2]))
        self.proc.stdout.read(1)
        return body.decode("utf-8", "replace")


def day_of(path):
    m = re.search(r"day-(\d+)", path)
    return int(m.group(1)) if m else None


def code_like(tok):
    return bool(re.search(r"[a-z][A-Z]", tok)) or ("_" in tok.strip("_") and tok.lower() == tok)


def kind(title, branch):
    t, b = title.lower(), branch.lower()
    if "spec change" in t or "/spec" in b or b.startswith("specs/"):
        return "spec"
    if "tick" in t or "close" in b:
        return "close"
    if "track" in b or "track" in t or re.match(r"day-0[12]/", b):
        return "code"
    return "other"


def angle(x, y):
    nx, ny = np.linalg.norm(x), np.linalg.norm(y)
    if nx == 0 or ny == 0:
        return 90.0
    return float(np.degrees(np.arccos(np.clip(x @ y / (nx * ny), -1, 1))))


def word_angle(a, b):
    keys = sorted(set(a) | set(b))
    return angle(np.array([math.log1p(a.get(k, 0)) for k in keys]),
                 np.array([math.log1p(b.get(k, 0)) for k in keys]))


def snapshots(prs):
    out = []
    for line in run("git", "log", "--first-parent", "main", "--reverse",
                    "--format=%h|%ad|%s", "--date=short").splitlines():
        sha, date, subject = line.split("|", 2)
        m = re.match(r"Merge pull request #(\d+)", subject)
        p = prs.get(int(m.group(1)), {}) if m else {}
        title = p.get("title", subject) if m else "Initial commit: monolith + migration plan"
        out.append(dict(sha=sha, date=date, pr=int(m.group(1)) if m else None, title=title,
                        branch=p.get("headRefName", "")))
    return out


def trajectory(snaps, blobs):
    for s in snaps:
        names = run("git", "ls-tree", "-r", "--name-only", s["sha"], "--", "plan.md", "specs").split()
        s["texts"] = {n: blobs.read(s["sha"], n) for n in names if n.endswith(".md")}
    vocab = sorted({tok for s in snaps for t in s["texts"].values() for span in TICK.findall(t)
                    for tok in IDENT.findall(span) if code_like(tok)})
    index = {v: i for i, v in enumerate(vocab)}
    patterns = "\n".join(vocab) + "\n"

    def spec_counts(texts):
        c = np.zeros(len(vocab))
        for t in texts.values():
            for span in TICK.findall(t):
                for tok in IDENT.findall(span):
                    if tok in index:
                        c[index[tok]] += 1
        return c

    def code_counts(sha):
        out = subprocess.run(["git", "grep", "-o", "-h", "-w", "-F", "-f", "-", sha, "--", ".",
                              ":(exclude)*.md", ":(exclude)data", ":(exclude)screenshots", ":(exclude)docs/dashboard",
                              ":(exclude)**/package-lock.json"], input=patterns,
                             capture_output=True, text=True, encoding="utf-8", errors="replace").stdout
        c = np.zeros(len(vocab))
        for tok in out.split():
            tok = tok.split(":", 1)[-1]
            if tok in index:
                c[index[tok]] += 1
        return c

    origin = collections.Counter(w for t in snaps[0]["texts"].values() for w in WORD.findall(t.lower()))
    prev, day, rows, vectors = origin, 0, [], []
    for i, s in enumerate(snaps):
        s["kind"] = "origin" if s["pr"] is None else kind(s["title"], s["branch"])
        m = re.match(r"day-(\d+)/", s["branch"])
        if m and s["kind"] in ("code", "close"):
            day = max(day, int(m.group(1)))
        words = collections.Counter(w for t in s["texts"].values() for w in WORD.findall(t.lower()))
        spec, code = spec_counts(s["texts"]), code_counts(s["sha"])
        reached = spec_counts({f: t for f, t in s["texts"].items() if (day_of(f) or 99) <= max(day, 1)})
        added = deleted = 0
        for line in run("git", "diff", "--numstat", snaps[0]["sha"], s["sha"], "--", "plan.md", "specs").splitlines():
            a, d, _ = line.split("\t")
            added, deleted = added + int(a), deleted + int(d)
        spec_files, churn = {}, 0
        if i:
            for line in run("git", "diff", "--numstat", snaps[i - 1]["sha"], s["sha"], "--", "plan.md", "specs").splitlines():
                a, d, f = line.split("\t")
                spec_files[f] = int(a) + int(d)
            for line in run("git", "diff", "--numstat", snaps[i - 1]["sha"], s["sha"], "--", ".", ":(exclude)*.md",
                                ":(exclude)docs/dashboard").splitlines():
                a, d, _ = line.split("\t")
                churn += 0 if a == "-" else int(a) + int(d)
        mask = reached > 0
        rows.append(dict(i=i, sha=s["sha"], date=s["date"], pr=s["pr"], title=s["title"], kind=s["kind"],
                         day=day if s["pr"] else 0, drift=round(word_angle(words, origin), 2),
                         step=round(word_angle(words, prev), 2), added=added, deleted=deleted,
                         gap_full=round(angle(np.log1p(spec), np.log1p(code)), 2),
                         coverage=round(float((code[mask] > 0).mean()), 3) if s["pr"] and mask.any() else None,
                         spec_files=spec_files, code_churn=churn))
        prev = words
        vectors.append((np.log1p(spec), np.log1p(code)))
    X = np.array([v / (np.linalg.norm(v) or 1) for pair in zip(*vectors) for v in pair])
    X = X - X.mean(0)
    _, S, Vt = np.linalg.svd(X, full_matrices=False)
    P, n = X @ Vt[:2].T, len(rows)
    for k, r in enumerate(rows):
        r["spec_xy"] = [round(float(v), 4) for v in P[k]]
        r["code_xy"] = [round(float(v), 4) for v in P[n + k]]
    files = sorted({f for s in snaps for f in s["texts"] if f == "plan.md" or day_of(f)},
                   key=lambda f: (day_of(f) or 0, f))
    lines = sum(t.count("\n") for t in snaps[0]["texts"].values())
    return rows, files, len(vocab), [round(float(v), 3) for v in (S ** 2 / (S ** 2).sum())[:2]], lines


def section(text, heading):
    m = re.search(rf"^## {heading}\n(.*?)(?=^## |\Z)", text, re.M | re.S)
    return m.group(1) if m else ""


def roadmap(snaps, prs, blobs):
    head = snaps[-1]["sha"]
    merged = [p for p in prs.values() if p.get("state") == "MERGED"]
    days = []
    # A day's spec can be renamed (Day 10 was), so its first estimate is looked up by day.
    first_files = {day_of(f): f for f in run("git", "ls-tree", "--name-only", snaps[0]["sha"], "specs/").split()}
    for path in sorted(run("git", "ls-tree", "--name-only", head, "specs/").split()):
        d = day_of(path)
        if not d:
            continue
        text, first = blobs.read(head, path), blobs.read(snaps[0]["sha"], first_files.get(d, path))
        crit = section(text, "Acceptance criteria")
        tracks = [r.split("|")[1].strip() for r in section(text, "Tracks").splitlines()
                  if re.match(r"^\| *[0-9A-Z] *\|", r)]
        work = {r.split("|")[1].strip(): r.split("|")[3].strip() for r in section(text, "Tracks").splitlines()
                if re.match(r"^\| *[0-9A-Z] *\|", r)}
        mine = sorted((p for p in merged if p["headRefName"].startswith(f"day-{d:02d}/")), key=lambda p: p["number"])
        done_tracks = sorted({m.group(1).upper() for p in mine
                              if (m := re.search(r"/track-([0-9a-z])[0-9]*(?:-|$)", p["headRefName"]))})
        ticked, total = crit.count("- [x]"), len(CRITERION.findall(crit))
        exp = re.search(r"Expected PRs:\*\* *(\d+)", text)
        exp0 = re.search(r"Expected PRs:\*\* *(\d+)", first)
        phase = re.search(r"\*\*Phase:\*\* *(\d+)", text)
        title = re.search(r"^# Day \d+ — (.+)$", text, re.M)
        status = ("done" if total and ticked == total
                  else "provisional" if "**Status:** provisional" in text else "ready")
        days.append(dict(day=d, file=path, title=title.group(1) if title else path, phase=int(phase.group(1)) if phase else None,
                         status=status, expected=int(exp.group(1)) if exp else None,
                         expected_first=int(exp0.group(1)) if exp0 else None,
                         tracks=tracks, track_work=work, tracks_merged=done_tracks,
                         track_prs=sum(kind(p["title"], p["headRefName"]) == "code" for p in mine),
                         prs=[dict(number=p["number"], kind=kind(p["title"], p["headRefName"]), title=p["title"]) for p in mine],
                         criteria=dict(total=total, ticked=ticked, new=crit.count("**new**"), hold=crit.count("**hold**"))))
    # Days run in order, so a day is done once a later one has code merged, ticked or not.
    # Day 01 never ticked its boxes and Day 05 recorded two that did not hold.
    worked = max((d["day"] for d in days if any(p["kind"] in ("code", "close") for p in d["prs"])), default=0)
    for d in days:
        if d["day"] < worked or (d["day"] == worked and any(p["kind"] == "close" for p in d["prs"])):
            d["status"] = "done"
        elif d["status"] == "done" and d["day"] > worked:
            d["status"] = "ready"
    current = next((d for d in days if d["status"] != "done"), None)
    if current:
        current["status"] = "active"
    return days


def conclusion(readme):
    """What the README says at the head of main: its phase reads, and its measurement table."""
    reads = [dict(phase=int(m.group(1)), text=m.group(0).strip()) for m in PHASE_READ.finditer(readme)]
    rows = [[c.strip() for c in r.strip().strip("|").split("|")]
            for r in section(readme, "What is being measured").splitlines() if r.startswith("|")]
    return dict(reads=reads, measured=[r for r in rows[2:] if len(r) == 2])


def fill(page, data, name):
    # A PR title is written by whoever opens the PR; "</script>" in one must not end the data line.
    data = data.replace("</", "<\\/")
    page, n = re.subn(r"^window\.SPEC_DRIFT = .*;$", lambda _: f"window.SPEC_DRIFT = {data};", page, flags=re.M)
    if n != 1:
        raise SystemExit(f"{name}: expected one window.SPEC_DRIFT line, found {n}")
    return page


def build(data):
    """docs/dashboard/ as a single page: the stylesheet and script inlined, the data filled in."""
    def read(name):
        with open(os.path.join(PAGE, name), encoding="utf-8") as f:
            return f.read()
    page = read("index.html")
    for tag, name, wrap in (('<link rel="stylesheet" href="dashboard.css">', "dashboard.css", "style"),
                            ('<script src="dashboard.js"></script>', "dashboard.js", "script")):
        if page.count(tag) != 1:
            raise SystemExit(f"index.html: expected one {tag}")
        page = page.replace(tag, f"<{wrap}>\n{read(name)}</{wrap}>")
    return fill(page, data, "index.html")


def next_step(days, open_prs):
    if open_prs:
        return "Waiting on " + ", ".join(f"#{p['number']} {p['title']}" for p in open_prs)
    day = next((d for d in days if d["status"] != "done"), None)
    if day is None:
        return "Every day is done."
    if not any(p["kind"] == "spec" for p in day["prs"]):
        return f"Day {day['day']:02d}: read the spec against the code, then the spec-change PR"
    left = [t for t in day["tracks"] if t not in day["tracks_merged"]]
    if left:
        return f"Day {day['day']:02d} Track {left[0]}: {day['track_work'][left[0]]}"
    return f"Day {day['day']:02d}: the closing PR"


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    out = ap.add_mutually_exclusive_group()
    out.add_argument("--out", default="spec-drift.json")
    out.add_argument("--build", help="write docs/dashboard/ here as one page, with the data inlined")
    args = ap.parse_args()
    listed = json.loads(run("gh", "pr", "list", "--state", "all", "--limit", "500",
                            "--json", "number,title,headRefName,state,url"))
    prs = {p["number"]: p for p in listed}
    open_prs = sorted((p for p in listed if p["state"] == "OPEN"), key=lambda p: p["number"])
    blobs = Blobs()
    snaps = snapshots(prs)
    rows, files, vocab, pca, lines = trajectory(snaps, blobs)
    days = roadmap(snaps, prs, blobs)
    now = dict(generated=datetime.datetime.now().astimezone().isoformat(timespec="minutes"),
               head=snaps[-1]["sha"], last_pr=snaps[-1]["pr"],
               current_day=next((d["day"] for d in days if d["status"] != "done"), None),
               next_step=next_step(days, open_prs),
               open_prs=[dict(number=p["number"], title=p["title"], url=p["url"]) for p in open_prs])
    data = json.dumps(dict(now=now, rows=rows, files=files, days=days, origin_lines=lines,
                           vocab_size=vocab, pca_var=pca,
                           conclusion=conclusion(blobs.read(snaps[-1]["sha"], "README.md"))),
                      separators=(",", ":"))
    target = args.build or args.out
    if args.build:
        data = build(data)
    with open(target, "w", encoding="utf-8") as f:
        f.write(data)
    print(f"{target}: {len(rows)} snapshots, {len(days)} days, next: {now['next_step']}")


if __name__ == "__main__":
    main()
