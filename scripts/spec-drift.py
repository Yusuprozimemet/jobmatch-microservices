"""Measure how the spec and the code move against each other, merge by merge.

Writes one JSON file for the migration dashboard: gap and name coverage per merge on
main, and each day's status, tracks and criteria. Needs git, the gh CLI and numpy.

    python scripts/spec-drift.py --out data.json
    python scripts/spec-drift.py --build dashboard.html  # docs/dashboard/ as one file, data inlined

Gap: angle between spec and code over the code-like names the specs put in backticks, counted in
the specs and in the code (Markdown, data/, screenshots, this dashboard and comment lines left
out: a comment naming a thing is not the thing). Each merge's gap uses only the names the specs had used by then, so a later spec does
not move an earlier point.

The angle needs a reference to be read. Two vectors of counts are never negative, so they sit
well under 90° even when they have nothing to do with each other. Gap chance is the gap to the
same code counts shuffled across the names (the mean and 95% band of CHANCE_DRAWS, seeded): a gap near it says
the code uses the spec's names in no particular proportion. The angle is the secondary reading;
two plain set figures come first. Name coverage: of the names the specs use at a merge, the share
the code has. Jaccard: the names in both, over the names in either (a name the specs have dropped
but the code keeps counts against it). Names kept: that other direction alone, how many names the
specs once used and have dropped that the code still has.

Boundaries: for plan.md's four services, how much of their main source has left the monolith for
services/, and how many imports still cross from one service's packages into another's. Both are
read from the tree, like the gap; neither depends on what a day ticked.
"""
import argparse
import collections
import datetime
import fnmatch
import hashlib
import json
import os
import platform
import posixpath
import re
import shlex
import subprocess

import numpy as np

TICK = re.compile(r"`([^`\n]+)`")
IDENT = re.compile(r"[A-Za-z_][A-Za-z0-9_]{3,}")
WORDS = re.compile(r"[A-Za-z0-9_]+")
COMMENT_LINE = re.compile(r"^\s*(//|/\*|\*|--|<!--|#(\s|!|$))")
COMMENT_TAIL = re.compile(r"/\*.*?\*/|\s(//|--|#)\s.*$")
CRITERION = re.compile(r"^- \[( |x)\]", re.M)
# A criterion's evidence: the PRs it cites and the tests it names (a class, or Class.member).
CITED_PR = re.compile(r"(?<![\w&])#(\d+)\b")
EVIDENCE_TEST = re.compile(r"\b([A-Z][A-Za-z0-9]*(?:Test|IT|Tests))(?:\.([a-z][A-Za-z0-9_]*))?\b")
# A break on purpose, in one fixed line (specs/README.md): `broken: <what> → <what it reported>`,
# on its own line in a PR description or under a ticked criterion; `broken: none → <why>` says
# there was none. It replaced three regexes over free text (#310), which read a break described in
# other words as none. Nothing earlier was asked for the line, so it is read from PR #310 on: a
# PR before it, or a day none of whose PRs reaches it, is not measured. A day number would not do:
# Days 39-41 ran before Day 28.
BREAK_LINE = re.compile(r"^[ \t]*(?:[-*][ \t]+)?broken:[ \t]*(\S[^\n]*?)[ \t]*(?:→|->)[ \t]*\S", re.M)
BREAK_LINE_FROM_PR = 310
# A test CI can skip: switched off, or run only on a condition. A condition on a system property
# is met when a workflow passes it as true (GatewayHarnessIT, -Dharness.gateway=true), so that
# test runs. A bare @Disabled stays skippable.
SKIPPABLE = re.compile(r"@Disabled\b|@(?:Enabled|Disabled)If")
ON_PROPERTY = re.compile(r'@EnabledIfSystemProperty\(\s*named\s*=\s*"([^"]+)"\s*,\s*matches\s*=\s*"true"\s*\)')
CI_PROPERTY = re.compile(r"-D([\w.-]+)=true\b")
# Tests that moved to a class of another stem, which moves() cannot tell by name: the PR that
# completed the move, and (old, new). Both kept every test method's name.
RENAMED = {296: ("ApplicationsUserDeletedConsumerIT", "UserDeletedConsumerTest"),  # Day 25 E1a
           298: ("CircuitBreakerIT", "PostingLookupBreakerTest")}  # Day 25 E1b
# A removal check: a grep a criterion says prints nothing ("`grep ...` returns nothing", "gives 0"),
# or a Verify line that echoes when it does (`grep ... || echo "clean"`).
GREP_SAYS_NOTHING = re.compile(r"`(grep [^`]+)`[^.]{0,40}?\b(?:returns|prints|finds|gives) (?:nothing|0)\b")
GREP_OR_ECHO = re.compile(r"^(grep\s.+?)\s*\|\|\s*echo\b", re.M)
# "Day 17", "Days 18-19", "Days 19, 21 and 24": the days a sentence names.
DAY_REF = re.compile(r"\bDays? (\d+)((?:\s*(?:[–-]|,|and|or)\s*\d+)*)")
# IDs, from Day 28's spec change on (specs/README.md): criterion 3 of Day 28 is C28.3, and the
# first hand-off Day 28's Notes leave is H28.1. A spec picks a hand-off up by citing its ID.
CRITERION_ID = re.compile(r"^C(\d+)\.(\d+)\s+")
HAND_OFF_ID = re.compile(r"\bH(\d+)\.(\d+)\b")
# A track a PR names ("**E1c** (next, ...)"), not a grep's -A3 or Day 14's A1.
TRACK_NAMED = re.compile(r"(?<![-\w])(?<!Day \d\d's )(?<!Day \d\d )([A-Z]\d+[a-z]?|0[a-z])\b")
PHASE_READ = re.compile(r"^\*\*Read [^*\n]*end of Phase (\d+)\.\*\*.*?(?=^\*\*Read |^## |\Z)", re.M | re.S)
# The platform step's specs (plan.md, "Course correction after Phase 2") are numbered from here.
PLATFORM = "platform"
# plan.md's target services ("Target repo structure"), by the monolith packages each one takes.
# shared, config and the gateway are no service's, so depending on them crosses nothing.
SERVICE = dict(auth="identity", user="identity", profile="identity", identity="identity",
               jobs="jobs", mart="jobs", savedjobs="applications", applications="applications",
               matching="matching")
OWN_PACKAGE = re.compile(r"(?:^|/)src/main/java/nl/hackyourfuture/project/backend/([a-z]+)/")
IMPORT = re.compile(r"^import\s+(?:static\s+)?nl\.hackyourfuture\.project\.backend\.([a-z]+)\.", re.M)
FINISHED = ("done", "closed")
CHANCE_DRAWS = 1000
# The most gh is asked for: a list that comes back this long may have been cut, and nothing else says so.
PR_LIMIT = 500
RUN_LIMIT = 5000
# What every figure built on the backticked names cannot see; the dashboard lists these as written.
CAVEATS = [
    "Gap, name coverage and Jaccard see only identifiers the specs put in backticks: a name written "
    "in plain prose is not counted.",
    "They match names, not meaning: prose is not understood, so a spec can name a thing the code has "
    "and still describe it wrongly.",
    "A name a spec says should disappear still counts as shared vocabulary while the code has it.",
]
PAGE = os.path.join(os.path.dirname(os.path.abspath(__file__)), os.pardir, "docs", "dashboard")
# What git said about each merge, kept between runs: every call cached here names commits only by
# SHA, so its output never changes. Keyed on this script's text too, so a change here starts over.
CACHE_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), os.pardir, ".spec-drift-cache.json")
CACHE = {}


def run(*args):
    return subprocess.run(args, capture_output=True, text=True, encoding="utf-8",
                          errors="replace", check=True).stdout


def gh_list(limit, *args):
    """A gh list call's JSON, refused when it is as long as the limit: what was cut is not known."""
    items = json.loads(run("gh", *args, "--limit", str(limit)))
    if len(items) >= limit:
        raise SystemExit(f"gh {' '.join(args[:2])} returned its limit of {limit}: some are missing, raise it")
    return items


def at_commits(*args):
    """git with only commit SHAs for revisions: the output is fixed, so it comes from the cache."""
    key = "\0".join(args)
    out = CACHE.setdefault("git", {}).get(key)
    if out is None:
        out = CACHE["git"][key] = run("git", *args)
    return out


def load_cache(path):
    with open(__file__, "rb") as f:
        version = hashlib.sha256(f.read().replace(b"\r\n", b"\n")).hexdigest()
    try:
        with open(path, encoding="utf-8") as f:
            cached = json.load(f)
    except (OSError, ValueError):
        cached = {}
    CACHE.clear()
    CACHE.update(cached if cached.get("version") == version else dict(version=version))


def save_cache(path):
    with open(path + ".tmp", "w", encoding="utf-8") as f:
        json.dump(CACHE, f, separators=(",", ":"))
    os.replace(path + ".tmp", path)


class Blobs:
    """One git cat-file process for every file read, instead of one git show each. Use as
    `with Blobs() as blobs:`, so the process is closed however the block ends."""

    def __enter__(self):
        self.proc = subprocess.Popen(["git", "cat-file", "--batch"], stdin=subprocess.PIPE,
                                     stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
        return self

    def __exit__(self, *exc):
        for f in (self.proc.stdin, self.proc.stdout):
            try:
                f.close()
            except OSError:
                pass
        self.proc.wait()

    def read(self, sha, path):
        try:
            self.proc.stdin.write(f"{sha}:{path}\n".encode())
            self.proc.stdin.flush()
            header = self.proc.stdout.readline().split()
        except (OSError, ValueError) as e:
            raise RuntimeError(f"git cat-file failed reading {path} at {sha}: {e}") from e
        if header[-1:] == [b"missing"]:
            return ""
        if len(header) != 3 or not header[2].isdigit():
            said = b" ".join(header).decode("utf-8", "replace") or "nothing (the process ended)"
            raise RuntimeError(f"git cat-file failed reading {path} at {sha}: it said {said}")
        body = self.proc.stdout.read(int(header[2]))
        self.proc.stdout.read(1)
        return body.decode("utf-8", "replace")


def day_of(path):
    m = re.search(r"day-(\d+)", path)
    return int(m.group(1)) if m else None


def code_like(tok):
    return bool(re.search(r"[a-z][A-Z]", tok)) or ("_" in tok.strip("_") and tok.lower() == tok)


WORK_KINDS = ("spec", "context", "prod", "test", "tooling")
NOT_WRITTEN = re.compile(r"(^|/)package-lock\.json$|^docs/dashboard/token-usage\.json$|^screenshots/")
TEST_PATH = re.compile(r"(^|/)tests?/|(^|/)(test_[^/]*|[^/]*_test|conftest)\.py$|(Test|Tests|IT)\.java$"
                       r"|\.(test|spec)\.[jt]sx?$")


def renamed_to(path):
    """numstat writes a moved file as src/{a => b}/X.java or a => b; its lines count where it went."""
    path = re.sub(r"\{[^{}]* => ([^{}]*)\}", r"\1", path)
    return path.split(" => ")[-1].replace("//", "/")


def work_kind(path):
    """Which box a changed file's lines go in; the first rule that matches wins. None: generated
    by a tool, not written, so not counted."""
    if NOT_WRITTEN.search(path):
        return None
    if path == "plan.md" or path.startswith("specs/"):
        return "spec"
    if path.endswith(".md") or path.startswith(".claude/") or (path.startswith("docs/")
                                                               and not path.startswith("docs/dashboard/")):
        return "context"
    if TEST_PATH.search(path):
        return "test"
    if path.startswith(("scripts/", ".github/", "docs/dashboard/")):
        return "tooling"
    return "prod"


def broke(text):
    """Whether text has a `broken:` line for a break, not a `broken: none → <why>`."""
    return any(w.lower() != "none" for w in BREAK_LINE.findall(text or ""))


def break_state(body, number):
    """What a PR description says about breaking the code on purpose: "recorded", "none" (only
    `broken: none → <why>`), "silent", or "not measured" for a PR from before the line."""
    if number < BREAK_LINE_FROM_PR:
        return "not measured"
    if broke(body):
        return "recorded"
    return "none" if BREAK_LINE.search(body or "") else "silent"


def ci_pushes(runs):
    """Per branch: the commits CI ran on for a pull request, and those that failed a workflow."""
    shas, failed = collections.defaultdict(set), collections.defaultdict(set)
    for r in runs:
        if r["event"] == "pull_request":
            shas[r["headBranch"]].add(r["headSha"])
            if r["conclusion"] == "failure":
                failed[r["headBranch"]].add((r["headSha"], r["workflowName"]))
    return {b: (len(s), len({sha for sha, _ in failed[b]}), sorted({w for _, w in failed[b]})) for b, s in shas.items()}


def verification(prs, runs):
    """Every merged PR: whether it recorded a break, and how many of its pushes CI failed."""
    ci, out = ci_pushes(runs), []
    for p in sorted(prs.values(), key=lambda p: p["number"]):
        if p.get("state") == "MERGED":
            pushes, failed, failed_in = ci.get(p["headRefName"], (0, 0, []))
            out.append(dict(number=p["number"], day=day_of(p["headRefName"]), kind=kind(p["title"], p["headRefName"]),
                            breaks=break_state(p.get("body"), p["number"]), pushes=pushes, failed=failed, failed_in=failed_in))
    return out


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
    """None when either vector is all zeros: it has no direction, so no angle to the other."""
    nx, ny = np.linalg.norm(x), np.linalg.norm(y)
    if nx == 0 or ny == 0:
        return None
    return float(np.degrees(np.arccos(np.clip(x @ y / (nx * ny), -1, 1))))


def deg(a):
    return None if a is None else round(a, 2)


def band(angles):
    """The mean of a set of angles and the 95% interval they fall in, rounded; None if it is empty."""
    if not len(angles):
        return None
    return [round(float(v), 2) for v in (np.mean(angles), *np.percentile(angles, [2.5, 97.5]))]


def angles(X, y):
    """The angle between each row of X and y; the rows with no direction are left out."""
    nx, ny = np.linalg.norm(X, axis=1), np.linalg.norm(y)
    ok = nx > 0
    if ny == 0:
        return np.array([])
    return np.degrees(np.arccos(np.clip(X[ok] @ y / (nx[ok] * ny), -1, 1)))


def chance_angle(x, y, keep):
    """The angle between x and y with y's entries in keep shuffled among themselves, over
    CHANCE_DRAWS shuffles: [mean, 2.5th, 97.5th percentile], or None with no angle. Seeded per
    call, so the same counts give the same chance at every merge."""
    idx, rng = np.flatnonzero(keep), np.random.default_rng(0)
    if len(idx) < 2:
        a = angle(x, y)
        return None if a is None else [round(a, 2)] * 3
    Z = np.tile(y, (CHANCE_DRAWS, 1))
    # sorted first: only the counts matter, not their order
    Z[:, idx] = rng.permuted(np.tile(np.sort(y[idx]), (CHANCE_DRAWS, 1)), axis=1)
    return band(angles(Z, x))


def name_overlap(spec, code, known):
    """Name coverage and Jaccard over the names known at a merge: of the names the specs use, the
    share the code has; and the names in both over the names in either. None with nothing to divide.
    Last, the other direction: how many names the specs once used and have dropped, that the code keeps."""
    named, in_code = (spec > 0) & known, (code > 0) & known
    both, either = (named & in_code).sum(), (named | in_code).sum()
    return (round(float(both / named.sum()), 3) if named.any() else None,
            round(float(both / either), 3) if either else None,
            int((in_code & ~named).sum()))


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


def ranker(order):
    """A day's place in the run order: 0 (and plan.md) first, a day the order does not list after it by number."""
    pos = {d: i for i, d in enumerate(d for d in order if d != PLATFORM)}
    return lambda d: -1 if not d else pos.get(d, len(pos) + d)


def trajectory(snaps, blobs, order=()):
    """Gap and coverage per merge. Days compare by their place in the run order, so from
    Day 38 the unbuilt Days 17-37 are not reached; with no order, by number."""
    rank = ranker(order)
    for s in snaps:
        names = at_commits("ls-tree", "-r", "--name-only", s["sha"], "--", "plan.md", "specs").split()
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
        """A merge's counts need only the names the specs had used by then (later ones are masked
        by born), and a SHA fixes its history, so counts cached under it stay right."""
        cached = CACHE.setdefault("code", {}).get(sha)
        if cached is not None:
            return np.array([cached.get(v, 0) for v in vocab], dtype=float)
        out = subprocess.run(["git", "grep", "-h", "-I", "-w", "-F", "-f", "-", sha, "--", ".",
                              ":(exclude)*.md", ":(exclude)data", ":(exclude)screenshots", ":(exclude)docs/dashboard",
                              ":(exclude)**/package-lock.json"], input=patterns,
                             capture_output=True, text=True, encoding="utf-8", errors="replace").stdout
        c = np.zeros(len(vocab))
        for line in out.splitlines():
            for tok in code_names(line, index):
                c[index[tok]] += 1
        CACHE["code"][sha] = {vocab[k]: int(n) for k, n in enumerate(c) if n}
        return c

    day, rows = 0, []
    for i, s in enumerate(snaps):
        touched = at_commits("diff", "--name-only", snaps[i - 1]["sha"], s["sha"], "--", "plan.md", "specs").split() if i else []
        s["kind"] = "origin" if s["pr"] is None else kind(s["title"], s["branch"], touched)
        m = re.match(r"day-(\d+)/", s["branch"])
        if m and s["kind"] in ("code", "close"):
            day = max(day, int(m.group(1)), key=rank)
        spec, code = spec_counts(s["texts"]), code_counts(s["sha"])
        reached = spec_counts({f: t for f, t in s["texts"].items() if day_of(f) and rank(day_of(f)) <= rank(max(day, 1))})
        added = deleted = 0
        for line in at_commits("diff", "--numstat", snaps[0]["sha"], s["sha"], "--", "plan.md", "specs").splitlines():
            a, d, _ = line.split("\t")
            added, deleted = added + int(a), deleted + int(d)
        spec_files, churn, work = {}, 0, dict.fromkeys(WORK_KINDS, 0)
        if i:
            for line in at_commits("diff", "--numstat", snaps[i - 1]["sha"], s["sha"]).splitlines():
                a, d, f = line.split("\t")
                w = work_kind(renamed_to(f))
                if a != "-" and w:
                    work[w] += int(a) + int(d)
            for line in at_commits("diff", "--numstat", snaps[i - 1]["sha"], s["sha"], "--", "plan.md", "specs").splitlines():
                a, d, f = line.split("\t")
                spec_files[f] = int(a) + int(d)
            for line in at_commits("diff", "--numstat", snaps[i - 1]["sha"], s["sha"], "--", ".", ":(exclude)*.md",
                                ":(exclude)docs/dashboard").splitlines():
                a, d, _ = line.split("\t")
                churn += 0 if a == "-" else int(a) + int(d)
        mask, known = reached > 0, born <= i
        spec_v, code_v = np.log1p(spec) * known, np.log1p(code) * known
        name_coverage, jaccard, names_kept = name_overlap(spec, code, known)
        chance = chance_angle(spec_v, code_v, known)
        rows.append(dict(i=i, sha=s["sha"], date=s["date"], pr=s["pr"], title=s["title"], kind=s["kind"],
                         day=day if s["pr"] else 0, added=added, deleted=deleted,
                         gap_full=deg(angle(spec_v, code_v)),
                         gap_chance=chance and chance[0], gap_chance_band=chance and chance[1:],
                         coverage=round(float((code[mask] > 0).mean()), 3) if s["pr"] and mask.any() else None,
                         name_coverage=name_coverage, jaccard=jaccard, names_kept=names_kept,
                         spec_files=spec_files, code_churn=churn, work=work))
    files = sorted({f for s in snaps for f in s["texts"] if f == "plan.md" or day_of(f)},
                   key=lambda f: (rank(day_of(f)), f))
    lines = sum(t.count("\n") for t in snaps[0]["texts"].values())
    return rows, files, len(vocab), lines


def boundaries(rows, snaps, blobs):
    """At each merge, for plan.md's four services: the lines of main source still in the monolith
    and in the service's own directory under services/, and the imports where one service's
    package reaches into another's ("matching>identity"). Tests are left out, as a test may
    assemble modules; a fully qualified name used without an import is not seen."""
    seen = {}
    for r, s in zip(rows, snaps):
        placed, crossed = {}, collections.Counter()
        for line in at_commits("ls-tree", "-r", s["sha"]).splitlines():
            meta, path = line.split("\t", 1)
            m = OWN_PACKAGE.search(path)
            if not path.endswith(".java") or not m or m.group(1) not in SERVICE:
                continue
            blob = meta.split()[2]
            if blob not in seen:
                text = blobs.read(s["sha"], path)
                seen[blob] = (text.count("\n"), IMPORT.findall(text))
            n, imports = seen[blob]
            owner = SERVICE[m.group(1)]
            placed.setdefault(owner, [0, 0])[path.startswith("services/")] += n
            crossed.update(f"{owner}>{SERVICE[p]}" for p in imports if SERVICE.get(p, owner) != owner)
        r["placed"], r["crossings"] = placed, dict(crossed)


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


def out_of_order(order):
    """The days that do not run in numeric order: those outside the longest run of the order that
    rises by number: Days 38-40 ran ahead of Day 17, Day 17 behind Days 18-19, and Day 25 is planned
    behind Days 26-27."""
    days = [d for d in order if d != PLATFORM]
    best = []  # best[i]: the longest rising run that ends at days[i]
    for i, d in enumerate(days):
        best.append(max((best[j] for j in range(i) if days[j] < d), key=len, default=[]) + [d])
    keep = set(max(best, key=len, default=[]))
    return [d for d in days if d not in keep]


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


# A track's name is a letter or a digit, with a number when a day splits one (Day 39's A1, A2),
# or a lowercase letter (Day 20's 0a, 0b).
TRACK_ROW = re.compile(r"^\| *([0-9A-Z][0-9]?[a-z]?) *\|")


def track_names(section_text):
    """The tracks a day's table lists, in its order."""
    return [m.group(1) for line in section_text.splitlines() if (m := TRACK_ROW.match(line))]


def merged_tracks(branches, tracks):
    """The tracks with a merged branch. `track-a1-...` is A1 when the table lists A1; otherwise it
    is part of A, as Day 15's `track-c1`..`c4` were its one track C. A letter after the digits is a
    part of that track: Day 17 split B1 again into `track-b1a`..`b1c`, unless the table lists it
    with that letter, as Day 20 lists 0a and 0b."""
    by_lower = {t.lower(): t for t in tracks}
    merged = set()
    for branch in branches:
        m = re.search(r"/track-([0-9a-z])([0-9]*)([a-z]?)(?:-|$)", branch)
        if m:
            head, number, part = m.groups()
            name = next((by_lower[c] for c in (head + number + part, head + number) if c in by_lower),
                        head.upper())
            merged.add(name)
    return sorted(merged)


def unopened_tracks(day_prs):
    """Tracks a day's PRs name that no branch of the day ever did: #298 announced E1c, and the day
    read as done for a day before #305 opened it. A branch covers the tracks it begins (`track-e1`
    covers E1c) and those that begin it (`track-b` was Day 22's B1). Only letters a branch uses count."""
    branches = {m.group(1) for p in day_prs if (m := re.search(r"/track-([0-9a-z]+?)(?:-|$)", p["headRefName"]))}
    found = {}
    for p in sorted(day_prs, key=lambda p: p["number"]):
        for t in TRACK_NAMED.findall(p.get("body") or ""):
            low = t.lower()
            if low[0] in {b[0] for b in branches} and not any(b.startswith(low) or low.startswith(b) for b in branches):
                found.setdefault(t, p["number"])
    return [dict(track=t, pr=n) for t, n in sorted(found.items())]


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
    tests and `broken:` lines written into it."""
    out = []
    for block in re.split(r"^(?=- \[[ x]\])", section_text, flags=re.M):
        if not block.startswith("- ["):
            continue
        tag = re.search(r"\*\*(new|hold)\*\*", block)
        body = block[5:].lstrip()
        cid = CRITERION_ID.match(body)
        claim = excerpt(re.sub(r"^(\*\*\w+\*\*\s*—\s*)?", "", body[cid.end() if cid else 0:]), 140)
        out.append(dict(id=f"C{cid.group(1)}.{cid.group(2)}" if cid else None,
                        ticked=block.startswith("- [x]"), kind=tag.group(1) if tag else None,
                        claim=claim, prs=sorted({int(n) for n in CITED_PR.findall(block)}),
                        tests=sorted({c + ("." + m if m else "") for c, m in EVIDENCE_TEST.findall(block)}),
                        red=broke(block)))
    return out


def in_evidence_format(crit):
    """Whether a spec was written to the evidence format (#55: every criterion new or hold, every
    check seen to fail), which came in on Day 9. A spec written before it tags no criterion, and
    nothing asked its ticks to cite a PR, name a test or record a break: its zeros are not measured."""
    return any(c["kind"] for c in crit)


def moves(snaps):
    """The test classes that moved under a new name, and the merge each move completed at, as
    (index, old, new). A test moved into a service ends in Test, not IT (surefire runs it there),
    and is rewritten for that service's harness, too much for git to call it a rename: Day 21
    moved PostingShortlistUnavailableIT to matching-service at 27% alike. So a move is told by
    name: a class leaves the tree, and a class of the same stem arrives in that merge or a later
    one. Day 17 deleted SavedJobCountsUnavailableIT in #174, and its Test arrived in #180. Only a
    test moves: StubLlm leaving and StubLlmTest arriving is a helper gone and a test of it. A
    name can arrive more than once (InternalCallsObservedTest in matching-service on Day 21, then
    in application-service in #305, after the IT left in #296): the first arrival at or after
    the test left is where it went. A move to another stem is in RENAMED."""
    at = {s["sha"]: i for i, s in enumerate(snaps)}
    added, deleted, i = collections.defaultdict(list), {}, None
    for line in run("git", "log", "--first-parent", "--diff-merges=first-parent", "--no-renames", "--reverse",
                    "--name-status", "--format=@%h", snaps[-1]["sha"], "--", "*.java", "*.kt").splitlines():
        if line.startswith("@"):
            i = at.get(line[1:])
        elif line[:1] in ("A", "D") and i is not None:
            name = os.path.splitext(os.path.basename(line.split("\t")[-1]))[0]
            if line[0] == "A":
                added[name].append(i)
            else:
                deleted[name] = i
    stem = lambda n: re.sub(r"(?:IT|Tests?)$", "", n)
    out = []
    for old, gone in ((n, k) for n, k in deleted.items() if stem(n) != n):
        new = min(((k, n) for n, ks in added.items() if n != old and stem(n) == stem(old)
                   for k in ks if k >= gone), default=None)
        if new:
            out.append((new[0], old, new[1]))
    pr_at = {s["pr"]: i for i, s in enumerate(snaps) if s.get("pr")}
    return out + [(pr_at[n], old, new) for n, (old, new) in RENAMED.items() if n in pr_at]


def test_state(tests, sha, blobs, moved=None):
    """Where each named test stands at a commit: present, skippable (a class or file CI can skip,
    unless CI meets its condition), or missing. CI runs every test that is present, so a hold
    goes quiet only by its test leaving the tree or being switched off. A class that has left
    the tree is looked for under the name it moved to by then (moved: old to new)."""
    files, ci = collections.defaultdict(list), set()
    for path in (line.split("\t", 1)[1] for line in at_commits("ls-tree", "-r", sha).splitlines()):
        if path.endswith((".java", ".kt")):
            files[os.path.splitext(os.path.basename(path))[0]].append(path)
        elif path.startswith(".github/workflows/"):
            ci.update(CI_PROPERTY.findall(blobs.read(sha, path)))
    met = lambda m: "" if m.group(1) in ci else m.group(0)
    out = {}
    for t in tests:
        cls, _, member = t.partition(".")
        seen = {cls}
        while cls not in files and (moved or {}).get(cls) not in seen | {None}:
            cls = moved[cls]
            seen.add(cls)
        texts = [blobs.read(sha, p) for p in files.get(cls, [])]
        if not any(not member or re.search(rf"\b{member}\b", x) for x in texts):
            out[t] = "missing"
        else:
            out[t] = "skippable" if any(SKIPPABLE.search(ON_PROPERTY.sub(met, x)) for x in texts) else "present"
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
        worked = sorted(s["date"] for s in snaps if s["pr"] in {p["number"] for p in mine})
        ticked, total = crit.count("- [x]"), len(CRITERION.findall(crit))
        exp = re.search(r"Expected PRs:\*\* *(\d+)", text)
        exp0 = re.search(r"Expected PRs:\*\* *(\d+)", first)
        phase = re.search(r"\*\*Phase:\*\* *(\d+)", text)
        title = re.search(r"^# Day \d+ — (.+)$", text, re.M)
        ev = criteria(crit)
        status = ("done" if total and ticked == total
                  else "provisional" if "**Status:** provisional" in text else "ready")
        days.append(dict(day=d, file=path, title=title.group(1) if title else path, phase=int(phase.group(1)) if phase else None,
                         status=status, expected=int(exp.group(1)) if exp else None,
                         expected_first=int(exp0.group(1)) if exp0 else None,
                         tracks=tracks, track_work=work, tracks_merged=done_tracks,
                         tracks_unopened=unopened_tracks([p for p in prs.values()
                                                          if p["headRefName"].startswith(f"day-{d:02d}/")]),
                         track_prs=sum(kind(p["title"], p["headRefName"]) == "code" for p in mine),
                         prs=[dict(number=p["number"], kind=kind(p["title"], p["headRefName"]), title=p["title"]) for p in mine],
                         criteria=dict(total=total, ticked=ticked, new=crit.count("**new**"), hold=crit.count("**hold**")),
                         worked=[worked[0], worked[-1]] if worked else None,
                         evidence_format=in_evidence_format(ev),
                         break_format=any(p["number"] >= BREAK_LINE_FROM_PR for p in mine),
                         evidence=ev, removal_checks=removal_checks(text)))
    order, stop = run_order(blobs.read(head, "plan.md"), [d["day"] for d in days])
    return evidence(settle(days, order), snaps, blobs), order, stop


def evidence(days, snaps, blobs):
    """Where each finished day's named tests stand on main. A name counts as evidence if it was in
    the tree when the day's last PR merged: a claim can offer a choice (Day 14: one IT "or a case
    added to" another), and a day can rewrite a test it names (Day 11), and neither is a loss."""
    at = {s["pr"]: i for i, s in enumerate(snaps) if s["pr"]}
    named = {t for d in days for c in d["evidence"] for t in c["tests"]}
    now = test_state(named, snaps[-1]["sha"], blobs, {old: new for _, old, new in moves(snaps)})
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
    moved = moves(snaps)
    for i, (r, s) in enumerate(zip(rows, snaps)):
        names = {t for d in days if d.get("ended_at", len(snaps)) <= i for c in d["evidence"] for t in c["tests"]}
        states = list(test_state(names, s["sha"], blobs, {old: new for k, old, new in moved if k <= i}).values())
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
                at_commits("ls-tree", "-r", s["sha"]).splitlines()) if meta.split()[1] == "blob"]
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
    A Notes item with an ID of its day (H28.1) is picked up only by a spec that cites the ID; with
    no day ahead to take it and no spec citing it, it is listed with `to` None, as no later day
    picks it up. An item without one is picked up when the receiving spec names its day, or a
    finished day the item names (Day 39 points to Day 38's findings); a code comment, when the
    spec names its file."""
    finished = {d["day"] for d in days if d["status"] in FINISHED}
    text = {d["day"]: blobs.read(sha, d["file"]) for d in days}
    ahead = {d: i for i, d in enumerate(order) if d in text and d not in finished}
    out = []
    for d in days:
        if d["day"] not in finished:
            continue
        for item in re.split(r"\n(?=\s*- )", section(text[d["day"]], "Notes")):
            named = days_named(item)
            ids = sorted({f"H{a}.{b}" for a, b in HAND_OFF_ID.findall(item) if int(a) == d["day"]})
            if ids:
                cites = {t for t in text if t != d["day"] and any(re.search(rf"\b{re.escape(i)}\b", text[t]) for i in ids)}
                for t in named & set(ahead) - {d["day"]}:
                    out.append(dict(to=t, id=ids[0], source=f"Day {d['day']:02d} Notes", picked=t in cites, text=item))
                if not cites and not named & set(ahead) - {d["day"]}:
                    out.append(dict(to=None, id=ids[0], source=f"Day {d['day']:02d} Notes", picked=False, text=item))
                continue
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
    return sorted(out, key=lambda h: (ahead.get(h["to"], len(order)), h["picked"], h["source"]))


def conclusion(readme):
    """What the README says at the head of main: its phase reads, and its measurement table."""
    reads = [dict(phase=int(m.group(1)), text=m.group(0).strip()) for m in PHASE_READ.finditer(readme)]
    rows = [[c.strip() for c in r.strip().strip("|").split("|")]
            for r in section(readme, "What is being measured").splitlines() if r.startswith("|")]
    return dict(reads=reads, measured=[r for r in rows[2:] if len(r) == 2])


def token_usage():
    """Tokens per date, migration day and model, as scripts/token-usage.py last counted them."""
    path = os.path.join(PAGE, "token-usage.json")
    if not os.path.exists(path):
        return []
    with open(path, encoding="utf-8") as f:
        return json.load(f)


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
    for t in day.get("tracks_unopened", []):
        return f"Day {day['day']:02d} Track {t['track']}: announced in #{t['pr']}, no branch names it"
    return f"Day {day['day']:02d}: the closing PR"


def provenance():
    """What made these numbers: the script's commit, the versions, and the draws behind each band."""
    dirty = bool(run("git", "status", "--porcelain", "--", os.path.abspath(__file__)).strip())
    return dict(commit=run("git", "rev-parse", "HEAD").strip(), script_edited=dirty,
                python=platform.python_version(), numpy=np.__version__,
                chance_draws=CHANCE_DRAWS)


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    out = ap.add_mutually_exclusive_group()
    out.add_argument("--out", default="spec-drift.json")
    out.add_argument("--build", help="write docs/dashboard/ here as one page, with the data inlined")
    ap.add_argument("--cache", default=CACHE_FILE, help="what earlier runs read from git (default: %(default)s)")
    ap.add_argument("--no-cache", action="store_true", help="read everything from git, and keep nothing")
    args = ap.parse_args()
    if not args.no_cache:
        load_cache(args.cache)
    listed = gh_list(PR_LIMIT, "pr", "list", "--state", "all", "--json", "number,title,headRefName,state,url,body")
    runs = gh_list(RUN_LIMIT, "run", "list", "--event", "pull_request",
                   "--json", "headBranch,headSha,conclusion,event,workflowName")
    prs = {p["number"]: p for p in listed}
    open_prs = sorted((p for p in listed if p["state"] == "OPEN"), key=lambda p: p["number"])
    with Blobs() as blobs:
        snaps = snapshots(prs)
        head = snaps[-1]["sha"]
        spec_days = [d for d in map(day_of, run("git", "ls-tree", "--name-only", head, "specs/").split()) if d]
        rows, files, vocab, lines = trajectory(snaps, blobs, run_order(blobs.read(head, "plan.md"), spec_days)[0])
        days, order, stop = roadmap(snaps, prs, blobs)
        evidence_history(rows, days, snaps, blobs)
        boundaries(rows, snaps, blobs)
        now = dict(generated=datetime.datetime.now().astimezone().isoformat(timespec="minutes"),
                   head=snaps[-1]["sha"], last_pr=snaps[-1]["pr"],
                   current_day=next((d["day"] for d in days if d["status"] == "active"), None),
                   next_step=next_step(days, open_prs, order, stop), stop_after=stop,
                   open_prs=[dict(number=p["number"], title=p["title"], url=p["url"]) for p in open_prs])
        data = json.dumps(dict(now=now, rows=rows, files=files, days=days, origin_lines=lines,
                               order=[d for d in order if d != PLATFORM],
                               out_of_order=out_of_order(order),
                               removals=removal_history(days, snaps, blobs),
                               hand_offs=hand_offs(days, order, snaps[-1]["sha"], blobs),
                               vocab_size=vocab, tokens=token_usage(),
                               verification=verification(prs, runs),
                               conclusion=conclusion(blobs.read(snaps[-1]["sha"], "README.md")),
                               meta=dict(caveats=CAVEATS, provenance=provenance())),
                          separators=(",", ":"))
    target = args.build or args.out
    if args.build:
        data = build(data)
    with open(target, "w", encoding="utf-8") as f:
        f.write(data)
    if not args.no_cache:
        save_cache(args.cache)
    print(f"{target}: {len(rows)} snapshots, {len(days)} days, next: {now['next_step']}")


if __name__ == "__main__":
    main()
