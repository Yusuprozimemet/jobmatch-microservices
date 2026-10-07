# Specs

Spec-driven development. **No code without a spec. No spec without checkable acceptance criteria.**

## Workflow

1. Read the day spec against the code, and run every acceptance check on the code as it is.
   Fix what that turns up in a spec-change PR before any track starts.
2. Branch per track: `day-04/track-b-saved-jobs-tests`.
3. Build only what **In scope** lists.
4. Copy the acceptance criteria into the PR description and tick them.
5. The day is done when every box is ticked and **Verify** passes on `main`.

## Rules

- **The spec is the contract.** Found work outside it? Add a line to that day's *Notes*
  and raise it. Do not widen the PR — CI rejects diffs over 400 lines.
- **Criteria must be checkable by someone who did not write the code.**
  "Auth works" is not a criterion. "`POST /api/auth/login` returns 200 with a
  `Set-Cookie` containing `HttpOnly` and `SameSite=Lax`" is.
- **Every criterion is `new` or `hold`, and every check has been seen to fail.** A check that
  cannot fail proves nothing, and Phases 0 and 1 had three kinds of them: a CI gate that never
  ran the integration tests (Day 02), a verify command that ran no tests (Day 08), and greps for
  TODO markers that did not exist, so the criterion ticked itself (Days 08 and 09).
  - `new` is behaviour that does not exist yet. The check must fail on today's code, and the
    spec says how ("the grep finds two"). A `new` check that passes before the work is wrong.
  - `hold` must stay true through the change: contract tests passing unedited, a statement
    count. It passes before and after, so passing says nothing. Break the code on purpose once,
    watch the check fail, record what it reported, and revert. Day 08 did this with a
    per-posting loop: the query-count test failed with 2, 21 and 25 statements.
  - The proof goes in the PR that first runs the check: the spec-change PR if the check exists
    already, the track's PR if a track adds it (a Track 0). The closing PR links it when it
    ticks the box.
  - The record is one line of its own: `broken: <what was broken> → <what the check reported>`,
    or `broken: none → <why>` in a PR that broke nothing. The PR that ran the break carries it
    in its description; the closing PR copies it under the criterion it ticks. `spec-drift.py`
    reads no other wording as a break (from #310 on).
- **Tracks inside a day run in parallel. Days run in order.** Day N assumes N-1 is merged.
- **Record every defect in the day's *Notes* when it is found**, from Day 21 on, one `Defect`
  line each: where it was found and its cause, exactly one of `spec` (wrong, missing a case, or
  not meetable as written), `implementation` (the spec was right, the code did not do it) or
  `environment` (tooling, CI, the machine, a library). A track whose code the main session
  rewrote beyond review fixes gets a `Rewrite` line. These are the counts
  [`docs/paper/success-criteria.md`](../docs/paper/success-criteria.md) judges H1–H3 by.
- **IDs, from Day 28's spec change on.** Each criterion starts with its ID, `C28.3` for Day
  28's third, and keeps it when others are added or dropped. Each item a day leaves for later is
  a `Hand-off` line in its Notes with an ID, `H28.1`, and the day or the maintainer it goes to.
  The receiving spec picks it up by citing the ID; naming the day does not. `spec-drift.py`
  lists what no later day picks up, and any track a PR announces ("E1c, next") that no branch
  ever names. Specs before Day 28 keep their form.
- Changing a spec is normal. Change it in a PR *before* the work, not after.
- Specs written before the `new`/`hold` rule get their criteria tagged in that day's
  spec-change PR, when the spec is read against the code.

## Layout

```
specs/
  README.md        this file
  _template.md     start a new day from this
  day-NN-slug.md   one file per day
```

## Map to plan.md

| Days | Phase | Outcome | Status |
|---|---|---|---|
| 1–5 | 0 — make the split safe | Tests that survive the split; telemetry | ready |
| 6–11 | 1 — modularise in place | Boundaries proven, still one process | ready |
| 12–16 | 2 — gateway + JWT | Stateless auth behind a gateway | ready |
| 38–40 | platform step | Grants, tracing, service credential, harness and compose | ready |
| 17–20 | 3 — extract job-service | First independent service, own database | ready |
| 41, 21–24 | 4 — extract matching-service | LLM isolated; scores in NoSQL | ready |
| 25–28 | 5 — extract application-service | Events, GDPR cascade, monolith gone | ready |
| 42 | cleanup day | What Phases 3–5 left behind, before Phase 7 | ready |
| 32–35 | 7 — ECS on Fargate | Terraform, the services on ECS, observability, one deployment and its teardown | provisional |
| 29–31 | 6 — functions + uploads bucket | CV parsing, email off the request path; after Phase 7, or cut | provisional |

## Provisional specs

Days 29–35 are written from `plan.md`, not from experience. **Re-read and revise the
phase before starting it.** Each of them carries a `Status: provisional` line; delete that
line when the day has been reviewed and is ready to work. Days 17–20 and 41, 21–24 were
rewritten against the code when their phase reached them; Days 38–40 and 42 were written against it from the start. Days 36–37 were deleted before Phase 7 (`plan.md`, course correction before Phase 7).

Expect them to change. Day 19 in particular (partial failure and fallbacks) is a design
decision the team must agree on before anyone writes code.

## Stopping points

Days 16, 20, 24, 28, 35 and 31 each end a phase and are tagged releases. Days 16 and 28
are the two that stand on their own: **16** leaves a working monolith with real tests and
stateless auth; **28** leaves a complete microservice system with no Kubernetes.
