"""A pull request's branch, title, commit subjects and Jira keys agree with each other.

    python scripts/pr-names.py <base sha> <head sha>     # reads PR_BRANCH, PR_TITLE, PR_BODY

spec-drift.py reads a day's progress from branch names and Jira from the keys in them, so a name
that disagrees is a wrong state on the dashboard or in Jira, found only after the merge. Three
escaped that way (docs/paper, the process defects): #237's branch said `track-b`, its title track
B1, and the dashboard counted all of B done (D22-013); #263's branch and commits said KAN-5, its
description KAN-6, and neither was the sub-task (D27-062); #321 was opened from
`claude/focused-bell-qqwqdx`, and Day 28 still showed Track D next (D28-081). The rules:

- the branch is `day-NN/<slug>`, or `<area>/<slug>` for work outside the days (AREAS);
- a title that names a day comes from a day's branch;
- a title's `Day NN track X` and the branch's `day-NN/track-x-` name the same day and track, and
  neither comes alone;
- a close (`Day NN close`) comes from `day-NN/close-the-day`, on the same day;
- the branch, the title and the commit subjects name one Jira key at most, and a description that
  names keys names that one.

A wrong key used the same way everywhere still passes: CI cannot ask Jira which key is the right one.
It prints each problem and exits 1 if there is any.
"""
import os
import re
import subprocess
import sys

# Areas outside the days, as used so far. A new one is added here, on purpose.
AREAS = ("workflow", "paper", "tooling", "dashboard", "docs", "specs", "plan", "ci", "security", "trial",
         "dependabot")
DAY_BRANCH = re.compile(r"^day-(\d{2})/[0-9A-Za-z]")
AREA_BRANCH = re.compile(r"^([a-z]+)/[0-9A-Za-z]")
BRANCH_TRACK = re.compile(r"^day-\d{2}/track-([0-9a-z]+)(?=-|$)")
TITLE_DAY = re.compile(r"^(?:KAN-\d+ )?Day (\d{2})\b")
# "Day 35 track C2a (1 of 2): ..." and "Day 38 track C fix: ..." are tracks too.
TITLE_TRACK = re.compile(r"\bDay (\d{2}) track ([0-9A-Za-z]+)\b")
TITLE_CLOSE = re.compile(r"^(?:KAN-\d+ )?Day (\d{2}) close\b")
CLOSE_BRANCH = re.compile(r"^day-(\d{2})/close-the-day(?:-|$)")
KEY = re.compile(r"\bKAN-\d+\b")


def problems(branch, title, body, subjects):
    found = []
    day = DAY_BRANCH.match(branch)
    area = AREA_BRANCH.match(branch)
    if not day and not (area and area.group(1) in AREAS):
        found.append(f"branch `{branch}` is neither day-NN/<slug> nor one of {', '.join(a + '/' for a in AREAS)}")

    title_day = TITLE_DAY.match(title)
    if title_day and not day:
        found.append(f"the title names Day {title_day.group(1)}; the branch `{branch}` names no day")

    branch_track = BRANCH_TRACK.match(branch)
    title_track = TITLE_TRACK.search(title)
    if branch_track and not title_track:
        found.append(f"the branch is track {branch_track.group(1).upper()}; the title has no `Day NN track X`")
    elif title_track and not branch_track:
        found.append(f"the title is track {title_track.group(2)}; the branch `{branch}` has no `track-x-`")
    elif branch_track and (day.group(1), branch_track.group(1)) != (title_track.group(1), title_track.group(2).lower()):
        found.append(f"the branch is Day {day.group(1)} track {branch_track.group(1).upper()}, "
                     f"the title Day {title_track.group(1)} track {title_track.group(2)}")

    title_close = TITLE_CLOSE.match(title)
    close_branch = CLOSE_BRANCH.match(branch)
    if title_close and not (close_branch and close_branch.group(1) == title_close.group(1)):
        found.append(f"a Day {title_close.group(1)} close comes from day-{title_close.group(1)}/close-the-day, not `{branch}`")

    named = sorted(set(KEY.findall(" ".join([branch, title] + list(subjects)))))
    if len(named) > 1:
        found.append(f"the branch, the title and the commits name {len(named)} Jira keys: {', '.join(named)}")
    in_body = set(KEY.findall(body or ""))
    if in_body and named and not in_body & set(named):
        found.append(f"the description names {', '.join(sorted(in_body))}, not {', '.join(named)}")
    return found


def main(argv):
    base, head = argv[1], argv[2]
    subjects = subprocess.run(["git", "log", "--format=%s", f"{base}..{head}"], check=True,
                              capture_output=True, text=True).stdout.splitlines()
    found = problems(os.environ["PR_BRANCH"], os.environ["PR_TITLE"], os.environ.get("PR_BODY", ""), subjects)
    for line in found:
        print(f"::error::{line}")
    print(f"{len(found)} problems over the branch, the title and {len(subjects)} commits")
    return 1 if found else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
