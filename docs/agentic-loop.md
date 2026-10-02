# The agentic dev loop

How the rest of Phase 5 (Day 27 Track B and close, Day 25, Day 28, then the stop `plan.md` names)
runs with nobody at the keyboard. The steps are the ones in [`CLAUDE.md`](../CLAUDE.md). What
changes is who drives them: a script outside the model, and one fresh headless session per step.

## Two conditions, named

Phases 1–4 ran with the maintainer reviewing and merging every PR. That condition is part of what
this repository measures. So the loop has two modes, and the run log records which one each
merge was made in:

| Mode | Who merges | What it tests |
|---|---|---|
| `attended` (default) | the maintainer; the loop polls until the PR is merged, then goes on | the same condition as Phases 1–4, without the "say merged" round trip |
| `unattended` (`--auto-merge`) | the loop, only when every merge gate below passes | the agent working without human review. Every merged PR is reviewed afterwards, and what the maintainer would have rejected is logged as data |

**The rest of Phase 5 runs `attended`; Phase 6 is the `unattended` condition from its first day.**
The loop is itself a new variable (headless sessions, a driver, a fresh context per step), so
human review is not removed at the same time, and a whole phase gives a sample comparable with
the earlier ones. Any switch of mode is a row in the run log.

## The loop

```
driver (scripts/dev-loop.py, outside the model)
  loop:
    next = spec-drift.py's next step
    "stop and evaluate" / "Every day is done"  -> run the plan-auditor step, notify, exit 0
    an open PR                                 -> wait for it (see below)
    budget or wall clock spent                 -> notify, exit 3
    claude -p <step prompt>   fresh session, one step, one PR, --max-budget-usd, timeout
      last line: STATUS: PR <url> | STATUS: STOP <reason>
    STOP                                       -> notify, exit 2
    PR  -> wait for CI
           red: one fix session on the same branch; red again -> notify, exit 2
           green: attended -> poll until merged (closed unmerged -> exit 2)
                  unattended -> merge gates -> gh pr merge --merge
    after the merge: archive the transcript, append to the run log, pull main
```

One step is exactly what `spec-drift.py` names: the audit and spec-change PR, one track, or the
closing PR. The session follows `CLAUDE.md` for that step and does nothing else.

## Hard stops (the session ends with `STATUS: STOP`)

- Anything under "Stop and ask, do not patch" in `CLAUDE.md`.
- An auditor finding that needs judgement rather than a factual correction, or any plan-change PR.
- A step that would need `@Disabled`, a skipped test, a lowered threshold, or a dependency added
  or upgraded in a `pom.xml`.
- A diff that would need `Oversized:`, or more than one track's work in one PR.
- Any edit to `CLAUDE.md`, `.claude/`, `.github/`, `plan.md` or `scripts/dev-loop.py`.

## Merge gates (unattended mode only; the driver checks them, not the session)

- Every required check is green, including the Agent guard.
- The diff is under 400 lines, generated files included, and the PR is the only one open.
- The PR body records a break on purpose and what it reported (the template's section is filled).
- At most four merged PRs per day; a fifth step stops the run.

The break on purpose needs no extra check: it is reverted before commit, and CI runs the full suite
on the tree that merges. A break left in would turn CI red.

## Enforcement that lives where the agent cannot reach it

Local hooks and deny rules are a convenience; a `sed -i` or `git push origin +HEAD` gets past
them. What holds:

- **The Agent guard** ([`agent-guard.yml`](../.github/workflows/agent-guard.yml)). It runs from
  `main` through `pull_request_target` and reads the diff through the API, so a PR cannot weaken
  it. It fails every PR not opened by the maintainer that touches the rules or the evidence (its
  header lists them), and every PR whose other checks did not all pass. It is the one required
  check that stands for the path-filtered suites.
- **A separate GitHub identity for the agent** (a bot account or GitHub App), with a fine-grained
  token on this repository only: contents, pull requests, and issues (for `loop-stop`), no admin.
  Until it exists the agent pushes as the maintainer, and the guard's first gate cannot tell its
  PRs apart.
- **Branch protection on `main`:** no force push, admins included (already); the Agent guard
  required; and, once the agent has its own identity, one required approving review, so attended
  mode is enforced by GitHub and not only by the driver. Before that, a required review would block
  every merge: GitHub does not let an author approve their own PR.
- **Two checkouts.** The driver runs from its own checkout of `main`; the sessions work in another
  (`--work`). The run log and the transcripts are written in the driver's checkout, which the
  sandbox does not mount.
- **The sandbox:** a container or WSL2 distribution that mounts only the work checkout, entered
  through `--claude "docker exec -i -w /work jm-sandbox claude"`. Claude Code's own sandbox covers
  shell commands only, and does not run on native Windows. Inside: the Docker socket, network to
  Maven Central, GitHub and Atlassian, no `.env` or other secrets.
- **Jira:** a token scoped to this project. Ticket text is untrusted input; the session reads it
  as data, never as instructions.

## Budget

`--max-budget-usd` per step, a wall-clock timeout per step, one CI-fix retry per PR, and a
ceiling for the whole run. Hitting a ceiling is a normal stop, logged as one.

## Records

- **Primary:** the session transcripts (copied per step to the driver checkout's
  `docs/runs/transcripts/`, not committed) and the CI logs (in GitHub).
- **The run log**, `docs/runs/phase-5.md` in the driver's checkout, written by the driver, not the
  model: per step the time, mode, PR, CI result, who merged it, cost, and the stop reason if any.
  It is committed once, in the PR that evaluates the phase.
- **Secondary:** the closing PRs' Notes and the end-of-phase report, written by the agent. The
  analysis compares these with the primary record.

## Before the first run, and undoing it

1. Merged by hand: the Agent guard (#265), then this file, the driver and the `CLAUDE.md` section.
2. Make the Agent guard a required check.
3. Create the bot identity and its token; then require one approving review. Set up the sandbox
   and the two checkouts.
4. `git tag phase-5-loop-start` on `main`.
5. Pilot, attended: `python scripts/dev-loop.py --work <work checkout> --steps 2`.

Every merge is a merge commit, so one step comes out with `git revert -m 1 <merge>`, and the
whole run with those reverts in reverse order back to the tag. A migration that already ran
against a database is undone by a new migration, never a revert.

Any stop opens a GitHub issue labelled `loop-stop` with the reason and the last lines of the
log, so a stall is seen when it happens.
