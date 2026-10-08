# Working in this repository

This repository migrates the JobMatch monolith to microservices one day spec at a time, and
records what each day turned up. Three layers have to agree: [`plan.md`](plan.md) is the goal and
changes rarely; the day specs in [`specs/`](specs/) are the steps; the code is where the steps
land. The spec rules are in [`specs/README.md`](specs/README.md) and the template in
[`specs/_template.md`](specs/_template.md). This file is the working order and the lessons the
rules do not state. [`docs/workflow.md`](docs/workflow.md) draws it.

## Starting

A person or an agent new to this repository: read `plan.md`, then `specs/README.md`; run
`python scripts/spec-drift.py --out spec-drift.json` (it needs `numpy` and the `gh` CLI; or open
the dashboard) for the current day and next step; then ask for it, for example "start Day 12". Nothing merges without the
maintainer, and nothing starts after a phase ends until they say so.

## A day, in order

1. **Audit before writing anything.** On the first day of a phase, run the **plan-auditor**
   agent on the phase first: do the specs still add up to what `plan.md` wants from it, and
   does the code still match their premises? Then, every day, run the **spec-auditor** agent on
   the day: every check run on the code as it is, every `hold` tried to break, every claim
   counted. Both only report. Fix what they find in a **spec-change PR** first (a **plan-change
   PR** when the code shows `plan.md` itself is wrong, which should be rare):
   `day-NN/spec-<slug>`, titled `Day NN spec change: <what was wrong>`. Every criterion is tagged
   `new` (fails today; the spec says how) or `hold` (true today; broken on purpose once).
2. **One PR per track**, each branched from fresh `main` after the previous one merged. Never
   stack branches. `day-NN/track-<letter>-<slug>`, titled `Day NN track X: <what it does>`. A
   Track 0 holds the tests that must pass before and after the change, and lands first.
3. **A closing PR:** `day-NN/close-the-day`. Tick each criterion with its evidence (test or
   command, and the PR it landed in), add Notes to the spec, and update the Day entry and measurement
   table in [`docs/lab-notebook.md`](docs/lab-notebook.md) (the README keeps only its Findings). Run `python scripts/token-usage.py` and commit the refreshed
   `docs/dashboard/token-usage.json`: CI cannot read the transcripts it counts.
4. **After opening any PR, stop and wait for the maintainer to say "merged".** Do not start the
   next step, and do not start a new phase, until told.
5. **After every "merged", refresh the dashboard before the next step:** the merge rebuilds the
   [public one](https://yusuprozimemet.github.io/jobmatch-microservices/) by itself (`.github/workflows/dashboard.yml`). On fresh
   `main`, run `python scripts/spec-drift.py` and report the next step it prints. The page on
   GitHub Pages is the only dashboard; nothing else is republished.
6. **When a phase's last day closes,** run the plan-auditor on the phase that just ended and on
   the next, report, and stop.

Tracks may run in a different order from the spec's table when one depends on another; say so in
the PR.

## One session per step

Every reply re-reads the whole conversation, and that re-reading is most of what the migration
costs: up to Day 17, 64% of the main sessions' context tokens were read after a session had
passed 300k (the Tokens panel on the dashboard, "context per reply"). So a step starts in a fresh
session: after reporting the next step in step 5, suggest the maintainer start a new session for
it (or `/compact` if they keep this one). Nothing is lost: the spec, `spec-drift.json`, this file
and memory carry what the next step needs.

Output that lands in the main session is re-read by every later reply. Keep it small:

- Maven, surefire and CI logs: filter to the failures and the counts (`grep`, `tail`), or have a
  subagent read them and return the summary. Never print a whole log or report.
- Read the part of a file you need (`offset`/`limit`, `grep -n`), not the whole file, unless the
  step changes all of it. Diffs: `--stat` first, then the files that matter.

## The auditors

`.claude/agents/plan-auditor.md` and `.claude/agents/spec-auditor.md`. Separate agents with their
own brief and a fresh context, so the session that implements is not the only one checking its
own specs: in this repository that session has read past its own errors more than once.

| When | Run | What it answers |
|---|---|---|
| first day of a phase, before anything else | plan-auditor | Do this phase's specs still deliver what `plan.md` wants, on today's code? |
| every day, before the spec-change PR | spec-auditor | Is every claim and check in this day's spec true, and can each check fail? |
| a phase's last day has closed | plan-auditor | What did the phase change that the next one must know? |
| a stopping point `plan.md` names, before going on | plan-auditor, on the whole plan | Is `plan.md` still the right plan, and what has piled up that no day owns? |
| any time, by hand | either | "use the plan-auditor on Phase 3" |

Both check hand-offs: what earlier days' Notes and test comments defer to a later day. Nothing
else carries them forward, and that is where most gaps have come from.

Their findings are advice to the maintainer. What becomes a PR is the maintainer's call.

## The implementer

`.claude/agents/implementer.md`, on Haiku: the maintainer's choice, so that code is written by a
smaller model and everything else stays with the main session. For each track, the main session
writes the brief (files, changes, tests), sends it, and then does what the implementer does not:
reviews the diff, runs the full checks in "Before pushing", breaks the code on purpose, commits,
and writes the PR. Specs, audits, spec-change PRs and closing PRs stay with the main session. The
implementer never commits or pushes.

## Before pushing

- Build job-service's image first, `docker build -t jobmatch-job-service:harness services/job-service`:
  every run starts it, and a stale one passes silently.
- From `services/identity-service/`: `../../mvnw clean verify` **and** `../../mvnw -B
  checkstyle:check` (`mvnw` and `checkstyle.xml` are at the root since Day 28). `verify` does not
  run checkstyle, and CI does; 200+ green tests have hidden a violation more than once.
- Delete `*/target/surefire-reports` first and read the reports, not the exit code. A stale report
  from an earlier run looks like a pass.
- A test that passes before and after a change proves nothing until it has been seen to fail.
  Break the code on purpose, run it, record what it reported, revert. The break never merges.
  Record it in one line, `broken: <what> → <what it reported>` (`specs/README.md`): the
  dashboard reads no other wording.
- The PR diff must stay under **400 changed lines** (CI: "Diff stays reviewable"). If it does not,
  split the PR along a line where each part stands alone. An `Oversized:` line in the
  description turns the failure into a warning. It has been used seven times: #1, #2 and #3
  (Days 1-2 and the README rewrite), then #224 (Day 21 E1a, 950 lines), #296 (Day 25 E1a,
  1,225 lines) and #326 (the README's results moved to `docs/lab-notebook.md`, outside the days), and #347 (Day 32 A2, 674 new lines, the maintainer's choice over a split). The two extraction tracks could not split and stay green: once a module's code
  leaves the monolith, its database tests fail and the harness must route its endpoints to the
  new container in the same PR. Both specs expected the line. The moved code showed as renames;
  the counted lines were monolith tests deleted and rebuilt on the service's base, and in #296
  mostly deletions (914 of 1,225). `main` is protected, so the check must pass before a merge.
- A grep criterion cannot tell a comment from code: do not name, in a comment, the thing the
  day's grep checks is gone.

## Stop and ask, do not patch

- A Day 1–4 test (`services/identity-service/app/src/test/.../contract/`, or the Day 01 harness
  self-tests) needs an edit. That is the signal the spec's premise is wrong. Changes to `support/`
  are allowed.
- A migration that has run needs an edit. Never edit V1–V16 or any applied migration; add a new one.
- A criterion cannot be met as written. Record the departure; do not deviate silently.

## Where things live

- **Migrations:** in `services/identity-service/` (the monolith's remainder since Day 28),
  `app/src/main/resources/db/migration` holds V1–V16, applied as the owner (`DB_USER`). Each
  module's own migrations are in `<module>/src/main/resources/db/<module>`, applied by that
  module's Flyway as its role, baselined at 0, so the first is `V1__*.sql`
  (`app/.../config/Migrations.java`).
- **Database roles:** each module connects as `<module>_user` with its own schema as the search
  path; `jobs_user` only reads. The roles come from `scripts/db-setup.py` (production),
  `scripts/db-init/` (a fresh compose volume) and the test harness. A migration never creates a
  role.
- **Measurement:** `python scripts/spec-drift.py --out spec-drift.json` rebuilds the migration
  dashboard's data from git and GitHub; `--build` writes the page (`docs/dashboard/`) with it.
  Every merge to `main` republishes it to [GitHub Pages](https://yusuprozimemet.github.io/jobmatch-microservices/).

## Pitfalls this repository has already hit

- **Windows line endings.** `core.autocrlf` is on. Shell scripts run inside Linux containers, so
  `.gitattributes` pins `*.sh` to LF; a script written or edited from Windows Python in text mode
  comes out CRLF, and needs rewriting in binary mode.
- **Temp paths differ** between Git Bash and Windows programs: `/tmp` is not the same directory
  for `bash`, `python` and `gh`. Use a real path for files passed between them.
- **Never `docker compose down -v` on the maintainer's project**: it deletes their local
  database. Check compose changes in a separate project with its own volume
  (`docker compose -p <name> --env-file .env.example up -d --build`), then tear that down.
- **Flyway** writes the baseline description into its history unescaped: no apostrophes.
- **Postgres connections:** four module pools per application context. Keep them small (five,
  one idle); the test run caches several contexts.

## Commits and pull requests

- Commit messages: a subject that says what changed, then why, and what was seen failing.
- PR descriptions follow `.github/pull_request_template.md`: what was built, why this approach
  (including what was broken on purpose and what it reported), contract impact, how to run, and
  the self-check. Spec-change PRs tick the `new`/`hold` line.
- Record mistakes, including your own, in the PR and the day's Notes. The corrections are part of
  what this repository measures.
