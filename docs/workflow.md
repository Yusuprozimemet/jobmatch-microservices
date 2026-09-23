# How the work runs

The rules are in [`CLAUDE.md`](../CLAUDE.md) and [`specs/README.md`](../specs/README.md). This page
draws them: who does what, what triggers what, and how to start.

Three layers have to agree. [`plan.md`](../plan.md) is the goal, and changes rarely. The day specs
in [`specs/`](../specs/) are the steps. The code is where the steps land.

## Who does what

```mermaid
flowchart LR
    M["Maintainer<br/>decides and merges"]
    S["Main session<br/>implementer: writes specs,<br/>code and PRs"]
    PA["plan-auditor<br/>big picture:<br/>plan ↔ specs ↔ code"]
    SA["spec-auditor<br/>one day:<br/>spec ↔ code"]
    CI["CI on GitHub<br/>tests, checkstyle,<br/>400-line gate"]
    D["Dashboard<br/>scripts/spec-drift.py"]

    M -- "'start Day N' / 'merged'" --> S
    S -- "calls" --> PA
    S -- "calls" --> SA
    PA -- "findings, read-only" --> S
    SA -- "findings, read-only" --> S
    S -- "PR" --> CI
    CI -- "green or red" --> M
    S -- "after each merge" --> D
    D -- "where we are" --> M
```

The two auditors ([`.claude/agents/`](../.claude/agents/)) only read and report. They never edit
files or open PRs, which is what keeps them independent from the session that writes the code.

## What triggers what

```mermaid
flowchart TD
    Start(["Maintainer: 'start Day N'"]) --> Phase{"First day<br/>of a phase?"}
    Phase -- yes --> PA1["plan-auditor<br/>does this phase still add up<br/>to the plan's outcome?"]
    Phase -- no --> SA
    PA1 --> SA["spec-auditor<br/>run every check on today's code,<br/>try to break each 'hold'"]
    SA --> Fix["Main session: spec-change PR<br/>(plan-change PR if the plan is wrong)"]
    Fix --> W1{{"wait: 'merged'"}}
    W1 --> Track["Main session: one track PR<br/>from fresh main"]
    Track --> W2{{"wait: 'merged'"}}
    W2 --> Dash["refresh dashboard"]
    Dash --> More{"More tracks?"}
    More -- yes --> Track
    More -- no --> Close["closing PR: tick criteria,<br/>Notes, README"]
    Close --> W3{{"wait: 'merged'"}}
    W3 --> Dash2["refresh dashboard"]
    Dash2 --> Last{"Last day<br/>of the phase?"}
    Last -- yes --> PA2["plan-auditor<br/>phase-end review"] --> Stop(["Stop. Report.<br/>Wait for the maintainer."])
    Last -- no --> Stop
```

| Trigger | Runs | Produces |
|---|---|---|
| "start Day N", first day of a phase | plan-auditor, then spec-auditor | findings, then a spec-change or plan-change PR |
| "start Day N", any other day | spec-auditor | findings, then a spec-change PR |
| "merged" | dashboard refresh, then the next track or the closing PR | a PR, then wait |
| a phase's last day closes | plan-auditor, phase-end review | a report; nothing starts until the maintainer says so |
| any time, by hand | either auditor | for example "use the plan-auditor on Phase 3" |

## Starting, for someone new

1. Clone the repository and open Claude Code in it. `CLAUDE.md` and the two agents load on
   their own; there is nothing to install for them.
2. Read `plan.md`, then `specs/README.md`.
3. Run `python scripts/spec-drift.py --out spec-drift.json`, or open the [dashboard](https://yusuprozimemet.github.io/jobmatch-microservices/), for the
   current day and the next step.
4. Say "start Day N". The session follows the order above and stops at every PR.

Three things to know: nothing merges without the maintainer; nothing starts after a phase ends
until they say so; an auditor's findings are advice, and the maintainer decides what becomes a PR.
