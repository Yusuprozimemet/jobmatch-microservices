"""Count the Claude Code tokens spent on this repository, per date, migration day and model.

Reads the session transcripts Claude Code keeps on this machine (~/.claude/projects/<this
repository's path>, subagents included) and writes totals only, no prompt or reply text, to
docs/dashboard/token-usage.json, which scripts/spec-drift.py puts on the dashboard. The
transcripts live on the maintainer's machine, not in CI, so this runs locally and its output is
committed; the closing PR of each day refreshes it.

    python scripts/token-usage.py

A reply is counted once: a transcript repeats a reply's usage on every content block it streamed,
so replies are keyed by message and request id. The day is the branch the session was on
(day-17/track-d-... is Day 17); anything else (main, tooling/, docs/) has no day. Claude Code
deletes transcripts after cleanupPeriodDays (30 by default), so an entry already in the file is
never lowered: each count keeps the larger of the file's and the transcripts'.
"""
import argparse
import collections
import datetime
import glob
import json
import os
import re

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.normpath(os.path.join(HERE, os.pardir, "docs", "dashboard", "token-usage.json"))
FIELDS = ("input", "cache_write", "cache_read", "output", "replies")
DAY_BRANCH = re.compile(r"^day-(\d+)/")
DATED = re.compile(r"-\d{8}$")


def transcripts(root):
    # Claude Code names a project's folder after its path, every character but letters and digits
    # turned into "-"; worktrees of this repository get folders that start the same way.
    name = re.sub(r"[^A-Za-z0-9]", "-", os.path.abspath(os.path.join(HERE, os.pardir))).lower()
    for folder in glob.glob(os.path.join(root, "*")):
        if os.path.basename(folder).lower().startswith(name):
            yield from glob.glob(os.path.join(folder, "**", "*.jsonl"), recursive=True)


def count(files):
    seen, totals = set(), collections.defaultdict(lambda: dict.fromkeys(FIELDS, 0))
    for path in files:
        with open(path, encoding="utf-8") as f:
            for line in f:
                try:
                    entry = json.loads(line)
                except ValueError:
                    continue
                msg = entry.get("message") if entry.get("type") == "assistant" else None
                usage = msg and msg.get("usage")
                model = msg and DATED.sub("", msg.get("model") or "")
                key = msg and (msg.get("id"), entry.get("requestId"))
                if not usage or not model.startswith("claude") or key in seen:
                    continue
                seen.add(key)
                when = datetime.datetime.fromisoformat(entry["timestamp"].replace("Z", "+00:00"))
                day = DAY_BRANCH.match(entry.get("gitBranch") or "")
                t = totals[(when.astimezone().date().isoformat(), int(day.group(1)) if day else None, model)]
                t["input"] += usage.get("input_tokens", 0)
                t["cache_write"] += usage.get("cache_creation_input_tokens", 0)
                t["cache_read"] += usage.get("cache_read_input_tokens", 0)
                t["output"] += usage.get("output_tokens", 0)
                t["replies"] += 1
    return totals


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--transcripts", default=os.path.join(os.path.expanduser("~"), ".claude", "projects"))
    ap.add_argument("--out", default=OUT)
    args = ap.parse_args()
    totals = count(transcripts(args.transcripts))
    if os.path.exists(args.out):
        with open(args.out, encoding="utf-8") as f:
            for r in json.load(f):
                t = totals[(r["date"], r["day"], r["model"])]
                for k in FIELDS:
                    t[k] = max(t[k], r[k])
    rows = [dict(date=d, day=n, model=m, **t) for (d, n, m), t in totals.items()]
    rows.sort(key=lambda r: (r["date"], r["day"] or 0, r["model"]))
    with open(args.out, "w", encoding="utf-8", newline="\n") as f:
        f.write("[\n" + ",\n".join(json.dumps(r, separators=(",", ":")) for r in rows) + "\n]\n")
    out = sum(r["output"] for r in rows)
    print(f"{args.out}: {len(rows)} rows, {rows[0]['date']} to {rows[-1]['date']}, {out:,} output tokens")


if __name__ == "__main__":
    main()
