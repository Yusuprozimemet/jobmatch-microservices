# Success criteria, fixed before the result

Written 2026-09-28, with Days 1–20 (Phases 0–3) and Days 38–40 (the platform step) closed and
Phase 4 not started. This file says, before the rest of the migration runs, what will count as
the migration succeeding and what will count as the method working or failing. A paper written
from this repository judges the result against this file, not against criteria chosen after it.

**Changing it.** The thresholds below are not edited. A change is a new dated section at the end,
saying what changed and why, with the original left in place; the paper reports both.

## What is already known, and what is not

Twenty-three days are already done, so their results were seen before this was written. Every
count below is therefore reported twice:

- **Whole record:** every day from Day 1 to the end.
- **Prospective:** only the days that close after this file's commit: Days 21–28, in the order
  `plan.md` sets. These are the ones the criteria were written blind to.

A claim that holds on the whole record and not on the prospective days is reported as not holding.

## Where the study ends

- **End point:** the close of Day 28 (identity-service), the stopping point `plan.md` names for
  Phase 5. Phases 6 and 7 are not part of the result; if they are done, they are reported as an
  extension, judged by the same rules.
- **Time limit:** if Day 28 has not closed by **2027-03-31**, the study ends on that date, and the
  paper reports the state of the system then as a partial result. Abandoning the migration before
  either point is a result too, and is reported as the migration not succeeding.

## Part 1: did the migration succeed?

All four must hold at the end point. Any one failing means the migration did not succeed.

| # | Criterion | Measured by |
|---|---|---|
| M1 | **Behaviour preserved.** The `contract/` suite written against the monolith in Days 1–4 passes on `main` at the end point, and no assertion in it was changed to make it pass. Moving a test out of `contract/` with the maintainer's recorded approval, as Day 40 did, is allowed and counted; editing what a test expects is a failure. | CI on the end-point commit; `git diff` of `contract/` from `phase-2` to the end point, every changed line classified |
| M2 | **The services are separate.** job-service, matching-service, application-service and identity-service each run in their own container, own their data in their own database (or NoSQL store), and no service reads another's tables. | The day specs' own criteria for Days 17, 20, 21, 23, 25 and 28, ticked with evidence in their closing PRs |
| M3 | **Deleting a user clears every store.** `AccountDeletionIT` passes on every merge to `main` during Phase 5, and at the end point deleting a user removes their data from every store that holds it. | CI history for Phase 5; the Day 27 and Day 28 criteria |
| M4 | **Each service builds and tests on its own.** Every extracted service builds and runs its tests from its own CI workflow, without building the others. Deploying to a cluster is Phase 7 and is not required. | The workflows in `.github/workflows/`, one run each on the end-point commit |

## Part 2: did the method work?

The hypothesis, from the README: the binding constraint on an agent doing long-horizon engineering
work is **specification quality**, not model capability. With no comparison group, it is tested by
where the defects came from. It is judged on the prospective days.

**Classification.** Every defect found from Day 21 on is recorded in the day's Notes when it is
found, not when the paper is written, with:

- **where it was found:** by the auditors or the spec-change PR (before any code), in review or a
  break on purpose (before merge), or after the track merged (an escape);
- **its cause**, exactly one of:
  - **spec**: the spec was wrong, missing a case, or could not be met as written;
  - **implementation**: the spec was right and the code did not do what it said;
  - **environment**: tooling, CI, the machine, a library, not the spec or the code.

| # | Prediction | Supported if | Refuted if |
|---|---|---|---|
| H1 | Defects are caught before they reach `main`. | At least two thirds of the defects found in Days 21–28 are found before merge, and no more than one escape per phase | More than one third are escapes |
| H2 | When work goes wrong, the spec is the cause more often than the agent. | Among escapes and defects found in review, `spec` outnumbers `implementation` | `implementation` equals or outnumbers `spec` |
| H3 | A good spec is enough for a smaller model. The implementer runs on Haiku. | At most one track in four needs its code rewritten by the main session beyond review fixes | More than one track in four needs a rewrite |

If H2 is refuted, the paper says so: that the agent, not the spec, was the limit on these days.

## Part 3: predictions about the process

These are predictions from the record so far, stated so they can be wrong. Missing one does not
make the migration fail; it is reported as a miss.

| # | Prediction | Basis |
|---|---|---|
| P1 | Days 21–28 take 1.3× to 2.0× the track PRs their rewritten specs estimate, taken over the whole phase | 1.5× in every phase so far (`plan.md`, Rough effort) |
| P2 | Every day in 21–28 needs at least one spec-change PR | 22 of 23 days so far (README) |
| P3 | No `Oversized:` override is used | None since PR #3 |

## Reported with no threshold

The tokens spent per day and per model (`docs/dashboard/token-usage.json`), the context read per
reply, calendar days per spec day, and the hand-offs from earlier days that were dropped. These
are described, not judged.
