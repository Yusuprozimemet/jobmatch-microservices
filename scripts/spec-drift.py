"""Measure how the spec and the code move against each other, merge by merge.

Writes one JSON file for the migration dashboard: drift, gap and a 2D projection per merge on
main, and each day's status, tracks and criteria. Needs git, the gh CLI and numpy.

    python scripts/spec-drift.py --out data.json
    python scripts/spec-drift.py --build dashboard.html  # docs/dashboard/ as one file, data inlined

Drift: angle between today's spec text (plan.md + specs/*.md, log word counts) and the spec as
first committed, each word weighted by how rare it was across the first commit's files, so words
every file uses ("the", "day") weigh nothing. Gap: angle between spec and code over the code-like
names the specs put in backticks, counted in the specs and in the code (Markdown, data/,
screenshots, this dashboard and comment lines left out: a comment naming a thing is not the
thing). Each merge's gap uses only the names the specs had used by then, so a later spec does
not move an earlier point.
"""
import argparse
import collections
import datetime
import fnmatch
import json
import math
import os
import posixpath
import re
import shlex
import subprocess

import numpy as np

WORD = re.compile(r"[a-z0-9_]{2,}")
TICK = re.compile(r"`([^`\n]+)`")
IDENT = re.compile(r"[A-Za-z_][A-Za-z0-9_]{3,}")
WORDS = re.compile(r"[A-Za-z0-9_]+")
COMMENT_LINE = re.compile(r"^\s*(//|/\*|\*|--|<!--|#(\s|!|$))")
COMMENT_TAIL = re.compile(r"/\*.*?\*/|\s(//|--|#)\s.*$")
CRITERION = re.compile(r"^- \[( |x)\]", re.M)
# A criterion's evidence: the PRs it cites, the tests it names (a class, or Class.member) and a
# break it was seen to fail under ("Red with the permitAll line removed", "expected: 404 but was").
CITED_PR = re.compile(r"(?<![\w&])#(\d+)\b")
EVIDENCE_TEST = re.compile(r"\b([A-Z][A-Za-z0-9]*(?:Test|IT|Tests))(?:\.([a-z][A-Za-z0-9_]*))?\b")
SEEN_RED = re.compile(r"\bRed (?:with|as|without|when|on)\b|went red|turned\b[^.]{0,30}\bred\b|but was")
SKIPPABLE = re.compile(r"@Disabled\b|@(?:Enabled|Disabled)If")
# A removal check: a grep a criterion says prints nothing ("`grep ...` returns nothing", "gives 0"),
# or a Verify line that echoes when it does (`grep ... || echo "clean"`).
GREP_SAYS_NOTHING = re.compile(r"`(grep [^`]+)`[^.]{0,40}?\b(?:returns|prints|finds|gives) (?:nothing|0)\b")
GREP_OR_ECHO = re.compile(r"^(grep\s.+?)\s*\|\|\s*echo\b", re.M)
# "Day 17", "Days 18-19", "Days 19, 21 and 24": the days a sentence names.
DAY_REF = re.compile(r"\bDays? (\d+)((?:\s*(?:[–-]|,|and|or)\s*\d+)*)")
PHASE_READ = re.compile(r"^\*\*Read [^*\n]*end of Phase (\d+)\.\*\*.*?(?=^\*\*Read |^## |\Z)", re.M | re.S)
# The platform step's specs (plan.md, "Course correction after Phase 2") are numbered from here.
PLATFORM = "platform"
FINISHED = ("done", "closed")
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


def kind(title, branch, files=None):
    """What a merge was. A spec change must touch the spec (a day spec or plan.md) when its files
    are known: #55 changed the spec rules and #59 and #107 the dashboard, none of them a spec."""
    t, b = title.lower(), branch.lower()
    if ("spec change" in t or "plan change" in t or re.match(r"day-\d+/spec", b)
            or b.startswith(("specs/", "plan/"))):
        if files is None or any(f == "plan.md" or day_of(f) for f in files):
            return "spec"
        return "other"
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


def word_angle(a, b, idf):
    keys = sorted(set(a) | set(b))
    w = np.array([idf(k) for k in keys])
    return angle(w * np.array([math.log1p(a.get(k, 0)) for k in keys]),
                 w * np.array([math.log1p(b.get(k, 0)) for k in keys]))


def rarity(texts):
    """Inverse document frequency over one snapshot's files: 0 for a word in every file."""
    df = collections.Counter(w for t in texts.values() for w in set(WORD.findall(t.lower())))
    n = len(texts)
    return lambda w: math.log((1 + n) / (1 + df[w]))


def code_names(line, index):
    """The vocabulary names on one line of code, or none if the line is a comment."""
    if COMMENT_LINE.match(line):
        return []
    return [w for w in WORDS.findall(COMMENT_TAIL.sub("", line)) if w in index]


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
    first_seen = {}
    for i, s in enumerate(snaps):
        for t in s["texts"].values():
            for span in TICK.findall(t):
                for tok in IDENT.findall(span):
                    if code_like(tok):
                        first_seen.setdefault(tok, i)
    vocab = sorted(first_seen)
    index = {v: i for i, v in enumerate(vocab)}
    born = np.array([first_seen[v] for v in vocab])
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
        out = subprocess.run(["git", "grep", "-h", "-I", "-w", "-F", "-f", "-", sha, "--", ".",
                              ":(exclude)*.md", ":(exclude)data", ":(exclude)screenshots", ":(exclude)docs/dashboard",
                              ":(exclude)**/package-lock.json"], input=patterns,
                             capture_output=True, text=True, encoding="utf-8", errors="replace").stdout
        c = np.zeros(len(vocab))
        for line in out.splitlines():
            for tok in code_names(line, index):
                c[index[tok]] += 1
        return c

    origin = collections.Counter(w for t in snaps[0]["texts"].values() for w in WORD.findall(t.lower()))
    idf = rarity(snaps[0]["texts"])
    prev, day, rows, vectors = origin, 0, [], []
    for i, s in enumerate(snaps):
        touched = run("git", "diff", "--name-only", snaps[i - 1]["sha"], s["sha"], "--", "plan.md", "specs").split() if i else []
        s["kind"] = "origin" if s["pr"] is None else kind(s["title"], s["branch"], touched)
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
        mask, known = reached > 0, born <= i
        spec_v, code_v = np.log1p(spec) * known, np.log1p(code) * known
        rows.append(dict(i=i, sha=s["sha"], date=s["date"], pr=s["pr"], title=s["title"], kind=s["kind"],
                         day=day if s["pr"] else 0, drift=round(word_angle(words, origin, idf), 2),
                         step=round(word_angle(words, prev, idf), 2), added=added, deleted=deleted,
                         gap_full=round(angle(spec_v, code_v), 2),
                         coverage=round(float((code[mask] > 0).mean()), 3) if s["pr"] and mask.any() else None,
                         spec_files=spec_files, code_churn=churn))
        prev = words
        vectors.append((spec_v, code_v))
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


def run_order(plan, spec_days):
    """The order days run in, from plan.md's "Day-by-day specs" list, and the day after which work
    stops to be evaluated. Days the list does not name (the finished ones) come first. A day named
    twice runs at its last mention: Day 24's profile endpoint comes first, the rest of it last."""
    listed, stop = [], None
    for line in re.findall(r"^\d+\.\s+(.*(?:\n {3}.*)*)", section(plan, "Day-by-day specs"), re.M):
        days = []
        for a, b in re.findall(r"(\d+)(?:\s*[–-]\s*(\d+))?", line):
            if int(a) < 10:  # a phase number, not a day
                continue
            if "numbered from" in line:
                days += sorted(d for d in spec_days if d >= int(a)) or [PLATFORM]
            else:
                days += range(int(a), int(b or a) + 1)
        listed += days
        if "Stop and evaluate" in line and days:
            stop = days[-1]
    listed = [d for i, d in enumerate(listed) if d not in listed[i + 1:]]
    return [d for d in sorted(spec_days) if d not in listed] + listed, stop


def settle(days, order):
    """Each day's status. A day is finished once a day later in the run order has code merged, or
    its own closing PR has; finished with every box ticked is done, with boxes open is closed
    (Day 01 never ticked its boxes; Day 05 left two open). The first unfinished day is active."""
    pos = {d: i for i, d in enumerate(order)}
    worked = max((pos[d["day"]] for d in days if d["day"] in pos
                  and any(p["kind"] in ("code", "close") for p in d["prs"])), default=-1)
    for d in days:
        at = pos.get(d["day"], len(order))
        c = d["criteria"]
        if at < worked or (at == worked and any(p["kind"] == "close" for p in d["prs"])):
            d["status"] = "done" if c["total"] and c["ticked"] == c["total"] else "closed"
        elif d["status"] == "done":
            d["status"] = "ready"
    by_day = {d["day"]: d for d in days}
    for d in order:
        if d == PLATFORM:
            break
        if by_day[d]["status"] not in FINISHED:
            by_day[d]["status"] = "active"
            break
    return days


# A track's name is a letter or a digit, with a number when a day splits one (Day 39's A1, A2).
TRACK_ROW = re.compile(r"^\| *([0-9A-Z][0-9]?) *\|")


def track_names(section_text):
    """The tracks a day's table lists, in its order."""
    return [m.group(1) for line in section_text.splitlines() if (m := TRACK_ROW.match(line))]


def merged_tracks(branches, tracks):
    """The tracks with a merged branch. `track-a1-...` is A1 when the table lists A1; otherwise it
    is part of A, as Day 15's `track-c1`..`c4` were its one track C."""
    merged = set()
    for branch in branches:
        m = re.search(r"/track-([0-9a-z])([0-9]*)(?:-|$)", branch)
        if m:
            full_name = (m.group(1) + m.group(2)).upper()
            if full_name in tracks:
                merged.add(full_name)
            else:
                merged.add(m.group(1).upper())
    return sorted(merged)


def excerpt(text, n):
    """text on one line, cut at a word within n characters and never inside a code span."""
    text = re.sub(r"\s+", " ", text).strip()
    if len(text) <= n:
        return text
    cut = text[:n].rsplit(" ", 1)[0]
    if cut.count("`") % 2:
        cut = cut[:cut.rindex("`")].rstrip()
    return cut + "…"


def criteria(section_text):
    """Each criterion under "Acceptance criteria": its kind, whether it is ticked, and the PRs,
    tests and seen-red breaks written into it."""
    out = []
    for block in re.split(r"^(?=- \[[ x]\])", section_text, flags=re.M):
        if not block.startswith("- ["):
            continue
        tag = re.search(r"\*\*(new|hold)\*\*", block)
        claim = excerpt(re.sub(r"^- \[[ x]\]\s*(\*\*\w+\*\*\s*—\s*)?", "", block), 140)
        out.append(dict(ticked=block.startswith("- [x]"), kind=tag.group(1) if tag else None,
                        claim=claim, prs=sorted({int(n) for n in CITED_PR.findall(block)}),
                        tests=sorted({c + ("." + m if m else "") for c, m in EVIDENCE_TEST.findall(block)}),
                        red=bool(SEEN_RED.search(block))))
    return out


def test_state(tests, sha, blobs):
    """Where each named test stands at a commit: present, skippable (a class or file CI can skip,
    as GatewayHarnessIT is opt-in), or missing. CI runs every test that is present, so a hold
    goes quiet only by its test leaving the tree or being switched off."""
    files = collections.defaultdict(list)
    for path in run("git", "ls-tree", "-r", "--name-only", sha).splitlines():
        if path.endswith((".java", ".kt")):
            files[os.path.splitext(os.path.basename(path))[0]].append(path)
    out = {}
    for t in tests:
        cls, _, member = t.partition(".")
        texts = [blobs.read(sha, p) for p in files.get(cls, [])]
        if not any(not member or re.search(rf"\b{member}\b", x) for x in texts):
            out[t] = "missing"
        else:
            out[t] = "skippable" if any(SKIPPABLE.search(x) for x in texts) else "present"
    return out


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
        tracks = track_names(section(text, "Tracks"))
        work = {r.split("|")[1].strip(): r.split("|")[3].strip() for r in section(text, "Tracks").splitlines()
                if TRACK_ROW.match(r)}
        mine = sorted((p for p in merged if p["headRefName"].startswith(f"day-{d:02d}/")), key=lambda p: p["number"])
        done_tracks = merged_tracks([p["headRefName"] for p in mine], tracks)
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
                         criteria=dict(total=total, ticked=ticked, new=crit.count("**new**"), hold=crit.count("**hold**")),
                         evidence=criteria(crit), removal_checks=removal_checks(text)))
    order, stop = run_order(blobs.read(head, "plan.md"), [d["day"] for d in days])
    return evidence(settle(days, order), snaps, blobs), order, stop


def evidence(days, snaps, blobs):
    """Where each finished day's named tests stand on main. A name counts as evidence if it was in
    the tree when the day's last PR merged: a claim can offer a choice (Day 14: one IT "or a case
    added to" another), and a day can rewrite a test it names (Day 11), and neither is a loss."""
    at = {s["pr"]: i for i, s in enumerate(snaps) if s["pr"]}
    named = {t for d in days for c in d["evidence"] for t in c["tests"]}
    now = test_state(named, snaps[-1]["sha"], blobs)
    for d in days:
        ended = sorted(at[p["number"]] for p in d["prs"] if p["number"] in at)
        if d["status"] not in FINISHED or not ended:
            d["evidence"] = [dict(c, tests={}) for c in d["evidence"]]
            continue
        d["ended_at"] = ended[-1]
        then = test_state({t for c in d["evidence"] for t in c["tests"]}, snaps[ended[-1]]["sha"], blobs)
        for c in d["evidence"]:
            c["tests"] = {t: now[t] for t in c["tests"] if then[t] != "missing"}
    return days


def evidence_history(rows, days, snaps, blobs):
    """At each merge, where the tests of the days finished by then stand: the check that a hold's
    test has not left the tree or been switched off, merge by merge, as CI cannot see it."""
    for i, (r, s) in enumerate(zip(rows, snaps)):
        names = {t for d in days if d.get("ended_at", len(snaps)) <= i for c in d["evidence"] for t in c["tests"]}
        states = list(test_state(names, s["sha"], blobs).values())
        r["evidence"] = {k: states.count(k) for k in ("present", "skippable", "missing")} if names else None


def removal_checks(text):
    """The greps a day's criteria and Verify say print nothing, each once. Notes and Goal can quote
    a grep without it being a check (Day 08's "TODO day-08"), so they are not read."""
    crit = re.sub(r"\s+", " ", section(text, "Acceptance criteria"))
    verify = "\n".join(re.findall(r"^```bash\n(.*?)^```", section(text, "Verify"), re.M | re.S))
    return list(dict.fromkeys(c.strip() for c in GREP_SAYS_NOTHING.findall(crit) + GREP_OR_ECHO.findall(verify)))


def grep_rule(cmd):
    """A grep as (regex, paths, include glob), or None for one this does not run (a pipe)."""
    try:
        args = shlex.split(cmd)[1:]
    except ValueError:
        return None
    if "|" in args:
        return None
    flags = "".join(a[1:] for a in args if a.startswith("-") and not a.startswith("--"))
    include = next((a.split("=", 1)[1] for a in args if a.startswith("--include=")), None)
    words = [a for a in args if not a.startswith("-")]
    if not words:
        return None
    pattern = words[0]
    if "E" not in flags:  # basic regex: \| \( \+ ... are the operators, | ( + ... are literal
        pattern = re.sub(r"\\([|(){}+?])|([|(){}+?])", lambda m: m.group(1) or "\\" + m.group(2), pattern)
    return re.compile(pattern, re.I if "i" in flags else 0), words[1:] or ["."], include


def in_scope(path, scope):
    """Whether a tree path is the file, or under the directory, a shell path names; * stays in one level."""
    want = [p for p in scope.split("/") if p not in ("", ".")]
    have = path.split("/")
    return len(have) >= len(want) and all(fnmatch.fnmatchcase(h, w) for h, w in zip(have, want))


def removal_history(days, snaps, blobs):
    """Each finished day's removal checks, run at every merge from the day's end: the lines each
    finds. Paths are read from backend/, where most Verify blocks cd, or from the root when more of
    them exist there (Day 16 names backend/docs/). Blobs are counted once, by id."""
    checks = [dict(day=d["day"], cmd=cmd, since=d["ended_at"], rule=grep_rule(cmd), hits=[])
              for d in days if "ended_at" in d for cmd in d["removal_checks"]]
    checks = [c for c in checks if c["rule"]]
    seen = {}
    for i, s in enumerate(snaps):
        live = [c for c in checks if c["since"] <= i]
        if not live:
            continue
        tree = [(meta.split()[2], path) for meta, path in (line.split("\t", 1) for line in
                run("git", "ls-tree", "-r", s["sha"]).splitlines()) if meta.split()[1] == "blob"]
        for c in live:
            rx, paths, include = c["rule"]
            if "base" not in c:
                c["base"] = max(("backend", ""), key=lambda b: sum(
                    any(in_scope(f, posixpath.normpath(posixpath.join(b, p))) for _, f in tree) for p in paths))
            scopes = [posixpath.normpath(posixpath.join(c["base"], p)) for p in paths]
            n = 0
            for oid, path in tree:
                if (any(in_scope(path, sc) for sc in scopes)
                        and (include is None or fnmatch.fnmatchcase(posixpath.basename(path), include))):
                    if (c["cmd"], oid) not in seen:
                        text = blobs.read(s["sha"], path)
                        seen[c["cmd"], oid] = 0 if "\0" in text else sum(bool(rx.search(x)) for x in text.splitlines())
                    n += seen[c["cmd"], oid]
            c["hits"].append(n)
    return [dict(day=c["day"], cmd=c["cmd"], base=c["base"] or ".", since=c["since"], hits=c["hits"]) for c in checks]


def days_named(text):
    out = set()
    for m in DAY_REF.finditer(text):
        nums = [int(m.group(1))] + [int(n) for n in re.findall(r"\d+", m.group(2))]
        out.add(nums[0])
        for sep, a, b in zip(re.findall(r"[–-]|,|and|or", m.group(2)), nums, nums[1:]):
            out.update(range(a, b + 1) if sep in "–-" else {b})
    return out


def hand_offs(days, order, sha, blobs):
    """What finished days hand to days not yet run, and whether the receiving spec picked it up.
    A Notes item is picked up when the receiving spec names its day, or a finished day the item
    names (Day 39 points to Day 38's findings); a code comment, when the spec names its file."""
    finished = {d["day"] for d in days if d["status"] in FINISHED}
    text = {d["day"]: blobs.read(sha, d["file"]) for d in days}
    ahead = {d: i for i, d in enumerate(order) if d in text and d not in finished}
    out = []
    for d in days:
        if d["day"] not in finished:
            continue
        for item in re.split(r"\n(?=\s*- )", section(text[d["day"]], "Notes")):
            named = days_named(item)
            for t in named & set(ahead):
                picked = bool(({d["day"]} | named & finished) & days_named(text[t]))
                out.append(dict(to=t, source=f"Day {d['day']:02d} Notes", picked=picked, text=item))
    listed = run("git", "grep", "-n", "-I", "-E", r"\bDays? [0-9]+\b", sha, "--", ".", ":(exclude)*.md",
                 ":(exclude)docs/dashboard", ":(exclude)data")
    for line in listed.splitlines():
        path, number, code = line.split(":", 3)[1:]
        stem = os.path.basename(path)
        stem = os.path.splitext(stem)[0] if stem.endswith((".java", ".sql", ".ts", ".py")) else stem
        if COMMENT_LINE.match(code):
            for t in days_named(code) & set(ahead):
                out.append(dict(to=t, source=f"{path}:{number}", picked=stem in text[t], text=code))
    for h in out:
        h["text"] = excerpt(re.sub(r"^\s*(- |//|/\*+|\*|--|#|<!--)\s*", "", h["text"]), 160)
    return sorted(out, key=lambda h: (ahead[h["to"]], h["picked"], h["source"]))


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


def next_step(days, open_prs, order, stop):
    if open_prs:
        return "Waiting on " + ", ".join(f"#{p['number']} {p['title']}" for p in open_prs)
    by_day = {d["day"]: d for d in days}
    left = [d for d in order if d == PLATFORM or by_day[d]["status"] not in FINISHED]
    if not left:
        return "Every day is done."
    if left[0] == PLATFORM:
        return "The platform step: write its day spec, numbered from 38 (plan.md, Course correction after Phase 2)"
    if stop is not None and order.index(left[0]) > order.index(stop):
        return f"Day {stop:02d} is done: stop and evaluate before going on (plan.md)"
    day = by_day[left[0]]
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
    days, order, stop = roadmap(snaps, prs, blobs)
    evidence_history(rows, days, snaps, blobs)
    now = dict(generated=datetime.datetime.now().astimezone().isoformat(timespec="minutes"),
               head=snaps[-1]["sha"], last_pr=snaps[-1]["pr"],
               current_day=next((d["day"] for d in days if d["status"] == "active"), None),
               next_step=next_step(days, open_prs, order, stop), stop_after=stop,
               open_prs=[dict(number=p["number"], title=p["title"], url=p["url"]) for p in open_prs])
    data = json.dumps(dict(now=now, rows=rows, files=files, days=days, origin_lines=lines,
                           removals=removal_history(days, snaps, blobs),
                           hand_offs=hand_offs(days, order, snaps[-1]["sha"], blobs),
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
