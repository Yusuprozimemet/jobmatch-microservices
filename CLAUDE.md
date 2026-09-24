# Working in this repository

This repository migrates the JobMatch monolith to microservices one day spec at a time, and
records what each day turned up. Three layers have to agree: [`plan.md`](plan.md) is the goal and
changes rarely; the day specs in [`specs/`](specs/) are the steps; the code is where the steps
land. The spec rules are in [`specs/README.md`](specs/README.md) and the template in
[`specs/_template.md`](specs/_template.md). This file is the working order and the lessons the
rules do not state. [`docs/workflow.md`](docs/workflow.md) draws it.

## Starting

A person or an agent new to this repository: read `plan.md`, then `specs/README.md`; run
`python scripts/spec-drift.py --out spec-drift.json` (or open the dashboard) for the current day
and next step; then ask for it, for example "start Day 12". Nothing merges without the
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
   command, and the PR it landed in), add Notes to the spec, and update the README's Day entry
   and measurement table.
4. **After opening any PR, stop and wait for the maintainer to say "merged".** Do not start the
   next step, and do not start a new phase, until told.
5. **After every "merged", refresh the dashboard before the next step:** the merge rebuilds the
   [public one](https://yusuprozimemet.github.io/jobmatch-microservices/) by itself (`.github/workflows/dashboard.yml`). On fresh
   `main`, run `python scripts/spec-drift.py`. A session that can publish to the maintainer's
   dashboard artifact republishes it from `--build dashboard.html`; any other session reports the
   next step the script prints.
6. **When a phase's last day closes,** run the plan-auditor on the phase that just ended and on
   the next, report, and stop.

Tracks may run in a different order from the spec's table when one depends on another; say so in
the PR.

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

## Before pushing

- From `backend/`: `./mvnw clean verify` **and** `./mvnw -B checkstyle:check`. `verify` does not
  run checkstyle, and CI does; 200+ green tests have hidden a violation more than once.
- Delete `*/target/surefire-reports` first and read the reports, not the exit code. A stale report
  from an earlier run looks like a pass.
- A test that passes before and after a change proves nothing until it has been seen to fail.
  Break the code on purpose, run it, record what it reported, revert. The break never merges.
- The PR diff must stay under **400 changed lines** (CI: "Diff stays reviewable"). If it does not,
  split the PR along a line where each part stands alone. An `Oversized:` line in the
  description turns the failure into a warning; it was used three times, on #1, #2 and #3 (Days
  1-2 and the README rewrite), and never since. `main` is protected, so the check must pass
  before a merge.
- A grep criterion cannot tell a comment from code: do not name, in a comment, the thing the
  day's grep checks is gone.

## Stop and ask, do not patch

- A Day 1–4 test (`app/src/test/.../contract/`, or the Day 01 harness self-tests) needs an edit.
  That is the signal the spec's premise is wrong. Changes to `support/` are allowed.
- A migration that has run needs an edit. Never edit V1–V14 or any applied migration; add a new one.
- A criterion cannot be met as written. Record the departure; do not deviate silently.

## Where things live

- **Migrations:** `app/src/main/resources/db/migration` holds V1–V14, applied as the owner
  (`DB_USER`). Each module's own migrations are in `<module>/src/main/resources/db/<module>`,
  applied by that module's Flyway as its role, baselined at 0, so the first is `V1__*.sql`
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
