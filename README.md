# JobMatch → Microservices

**An experiment in spec-driven development: can a working monolith be migrated to microservices
by an AI agent, when the work is governed by written specifications instead of conversation?**

This repository takes [JobMatch](docs/original-readme.md) — a deployed, six-person
HackYourFuture final project — and rebuilds it as a set of independently deployable services.
Every change is driven by a numbered day specification with acceptance criteria written *before*
any code exists. The implementer is [Claude Opus 5](https://www.anthropic.com/claude) running at
high reasoning effort; the architecture, the specifications and every merge decision are mine.

> **Status:** in progress — Phase 0, Day 2 of 37 complete. This is an open lab notebook, not a
> finished system. Findings so far are in [Results to date](#results-to-date).

---

## Table of contents

- [Why this repository exists](#why-this-repository-exists)
- [The system being migrated](#the-system-being-migrated)
- [Method: spec-driven development](#method-spec-driven-development)
- [The migration plan](#the-migration-plan)
- [Target architecture](#target-architecture)
- [Results to date](#results-to-date)
- [What is being measured](#what-is-being-measured)
- [Running it](#running-it)
- [Repository layout](#repository-layout)
- [Documentation](#documentation)
- [Credits](#credits)

---

## Why this repository exists

Two goals, deliberately paired because each makes the other harder to fake.

**1. Learn distributed systems by doing the migration, not by reading about it.**
The interesting parts of microservices are not the ones that appear in tutorials. They are the
ones a real codebase forces on you: a session-cookie auth model that cannot survive a stateless
gateway, two SQL joins that reach across a schema boundary that is about to become a network
boundary, and a `ON DELETE CASCADE` that has to become a distributed, eventually-consistent
delete before it stops being GDPR compliance. JobMatch has all four. They are documented as
"the four hard parts" in [`plan.md`](plan.md), and the phase order exists to attack them.

**2. Find out where an LLM agent's competence actually ends on long-horizon engineering work.**
Agents are good at bounded tasks. A monolith migration is the opposite: thirty-seven days of
work where a mistake on day 8 is discovered on day 20, and where the correct answer is often
"do not build that yet." The hypothesis under test is that the binding constraint is not model
capability but **specification quality** — that an agent given a spec with third-party-checkable
acceptance criteria and a hard review gate produces reviewable, correct work, while the same
agent given a conversational prompt produces plausible work that quietly drifts.

The deliverable is therefore twofold: a migrated system, and an honest record of where the
method held and where it did not. Negative results are recorded in the specs' *Notes* sections
rather than edited out.

## The system being migrated

JobMatch is a job-search application: a daily data pipeline ingests postings, cleans and
deduplicates them and extracts skills and locations; the application lets a user build a skills
profile, search the cleaned set, and see matches ranked 0–100 with an explanation of the score.
It was built by a team of six over a HackYourFuture cohort, deployed, and is documented in full
in the [preserved original README](docs/original-readme.md).

The starting point is a snapshot of `HackYourFutureProjects/c55-final-project-group-C`
at commit `d43a24d`, committed here unmodified as the first commit so that every subsequent
change is visible in the diff.

| Layer | Stack | Migration status |
| --- | --- | --- |
| **Backend** | Java 25, Spring Boot 4.1, Spring Security (sessions + Google OAuth2), PostgreSQL, Flyway, Maven | **The subject of the migration** |
| **Frontend** | Next.js 16, React 19, TypeScript | Unchanged; consumes the gateway from Phase 2 |
| **Data** | Python, dbt, Airflow, Databricks, Azure | Unchanged; already owns its own schema |
| **Matching** | Skill-overlap SQL rescored by an LLM | Extracted in Phase 4 |
| **Infrastructure** | Docker Compose, GitHub Actions, GHCR | Kubernetes + Terraform/Pulumi in Phase 7 |

Why this codebase is a good subject: it is small enough to migrate (52 backend source files) but not
a toy — real auth with two identity paths, a cross-team database contract, an external LLM call
on the request path, GDPR export and erasure, and a package layout that *already* maps almost
one-to-one onto the target services. That last point matters: it means the split itself is
mechanical, so the experiment is not testing whether the agent can move files. It is testing
whether the agent can handle the four things around the split that are not mechanical.

## Method: spec-driven development

The governing rule, from [`specs/README.md`](specs/README.md):

> **No code without a spec. No spec without checkable acceptance criteria.**

**The chain.** [`plan.md`](plan.md) sets seven phases and names the hard parts. Each phase is
decomposed into day-sized specs in [`specs/`](specs/) — 37 of them, one file each, from a
[common template](specs/_template.md). Every spec states a one-sentence goal, what is **in
scope**, what is explicitly **out of scope** and which day owns it instead, parallel tracks, the
acceptance criteria, and a `Verify` command anyone can run. Work happens on a branch per track
(`day-04/track-b-saved-jobs-tests`), and the acceptance criteria are copied into the pull request
description and ticked there.

**Division of labour.** I write the plan and the specs, review every diff, and decide what
merges. Opus 5 implements against the spec — and, where a spec turns out to be wrong, says so in
writing rather than working around it. The agent's authority ends at the spec boundary: work
found outside it is added to that day's *Notes* and raised, not absorbed into the current pull
request.

**What is enforced by machine rather than by good intentions.** This is the part that makes the
experiment repeatable:

| Gate | Mechanism | What it prevents |
| --- | --- | --- |
| Diff size ≤ 400 changed lines | [`pr-checks.yml`](.github/workflows/pr-checks.yml) | The large agent-authored pull request that gets approved instead of reviewed |
| Pull request uses the template | [`pr-checks.yml`](.github/workflows/pr-checks.yml) | Checks skipped by `gh pr create --body`, the path most AI tooling takes |
| Tests, lint and build are green | Per-area CI workflows | Work declared done that does not run |
| Contract tests assert HTTP only | Day 2–4 acceptance criteria | Tests coupled to internals, which cannot survive the rewrite they exist to protect |

The 400-line limit is the load-bearing one. It forces the agent to sequence its own work, and it
is the reason a day that was estimated at three pull requests can legitimately take six — which
is a finding, not a failure.

**Changing a spec is normal.** It happens in a pull request *before* the work, not as a
retroactive edit afterwards. Days 17–37 are marked `provisional` precisely because they were
written from the plan rather than from experience, and must be re-read and revised before their
phase begins.

## The migration plan

Seven phases. The ordering principle is that everything reversible and testable happens before
anything that requires a network.

| Phase | Days | Outcome | Status |
| --- | --- | --- | --- |
| **0 — Make the split safe** | 1–5 | Integration tests over the five public API surfaces, asserting the HTTP contract only, so they survive the split unchanged. Actuator, Micrometer, OpenTelemetry. | **in progress** |
| **1 — Modularise in place** | 6–11 | Maven modules that may only call each other through published interfaces; the two cross-schema joins replaced by interfaces; migrations split per module. No network yet. | ready |
| **2 — Gateway + JWT** | 12–16 | Spring Cloud Gateway in front; session auth rewritten to RS256 JWT with JWKS, refresh tokens in the database, Google sign-in without a session. | ready |
| **3 — Extract job-service** | 17–20 | First independent service: read-only, no user data, own database and image. | provisional |
| **4 — Extract matching-service** | 21–24 | Isolates the 20-second LLM timeout from job search; match scores move to NoSQL with a native TTL. | provisional |
| **5 — Extract application-service** | 25–28 | Saved jobs to its own store; message bus with a transactional outbox; `user.deleted` cascade across four databases. | provisional |
| **6 — Functions + uploads** | 29–31 | CV parsing and mail off the request path; direct-to-blob uploads via short-lived SAS URLs. | provisional |
| **7 — Kubernetes + IaC** | 32–37 | Terraform, Pulumi add-ons, a Helm library chart, GitOps with Argo CD, external secrets, KEDA. | provisional |

Two phases stand on their own as stopping points. **Day 16** leaves a working monolith with a
real test suite and stateless auth behind a gateway — valuable even if nothing further is built.
**Day 28** leaves a complete microservice system with no Kubernetes.

Phase 1 is where the real work is, and it is fully reversible: if the boundaries do not hold
while everything is still one process, they will not hold over HTTP.

## Target architecture

```
services/
  api-gateway/          routing, JWT verification, rate limiting
  identity-service/     auth + user + profile
  job-service/          jobs + mart (read-only)
  application-service/  saved jobs + application tracker
  matching-service/     shortlist + LLM scorer
functions/
  cv-parse/  mailer/
charts/                 Helm library chart + one thin chart per service
deploy/argocd/          GitOps
infra/terraform/
frontend/               unchanged
data/                   unchanged
```

Each service gets its own `Dockerfile`, `pom.xml`, Flyway migrations, database and CI workflow.

Decisions recorded as deliberately **not** taken, with reasons, in [`plan.md`](plan.md): no
Postgres inside Kubernetes, no separate profile-service, no managed API gateway, and an explicit
acknowledgement that running both Terraform and Pulumi is a cost rather than a benefit.

## Results to date

Recorded as they happen, including the ones that make the method look worse.

**Day 1 — integration test harness.** Testcontainers Postgres shared across the run, an
`IntegrationTest` base class, builders (`aUser()`, `aProfile()`, `aPosting()`), and fixtures for
the three `analytics` mart tables the pipeline owns and Flyway therefore does not create.

- *Estimated 3 pull requests, took 6.* The harness is ~1,400 lines against a 400-line gate, and
  the pieces have a compile order. The estimate was mine and it was wrong; the gate exposed it
  rather than the review missing it.
- The agent sourced the mart column types from `data/sql/job_schema.sql` rather than from the
  dbt model the spec pointed at, correctly noting that the model is Databricks SQL and the sync
  script remaps those types to Postgres on the way in. The spec was wrong; the correction is
  recorded in the spec.

**Day 2 — auth contract tests.** 49 tests across six classes covering register, login, logout,
the password-reset lifecycle, password change, and all three Google sign-in branches. The OIDC
provider is stubbed as a *real* RS256 signing provider with a JWKS endpoint rather than a mocked
bean, so issuer, audience, signature, expiry and nonce are still validated by the production
decoder. Full suite: 63 tests in ~45 seconds.

- **The suite the spec asked for would have been green by never running.** Surefire's default
  includes stop at `*Test`/`*Tests` and no Failsafe plugin was configured, so `mvnw verify`
  matched no `*IT` class. Found and fixed during the day. This is the exact failure mode the
  method exists to catch — a CI gate that passes because it is testing nothing.
- A harness bug from Day 1 surfaced: `UserBuilder.googleAccount()` wrote `oauth_provider =
  'google'` where the application writes and matches on `'GOOGLE'`, so the fixture built a row
  no Google sign-in could ever match. Day 1's own self-tests never exercised it.
- One production wart found in the monolith and pinned rather than fixed, per the spec's "if a
  test reveals a bug, file it; do not fix it here": `POST /api/auth/register` echoes back the
  un-normalised email while storing the lowercase one.
- Two flows were identified as unreachable over HTTP and deliberately left uncovered, with the
  reasoning written down — rather than covered with a test that reaches past the contract.

**Early read on the hypothesis.** Across two days the agent's implementation has been sound and
its most useful output has been *disagreement with the spec* — three of the findings above are
the agent reporting that the instruction was wrong. The constraint so far is specification
quality and estimation, as predicted. The hard evidence comes at Day 13, when the session-auth
rewrite must pass these 49 tests unchanged.

## What is being measured

| Question | Evidence it will be judged on |
| --- | --- |
| Do contract tests written against the monolith survive the split? | Day 2–4 tests passing, unmodified, after Day 13 and again after Days 17–28 |
| How good are day-sized estimates for agent-implemented work? | Estimated vs actual pull requests per spec (currently 3→6, 3→3) |
| Does the agent catch defects in the system it is migrating? | Bugs found and filed per phase (currently: 1 CI gate, 1 harness, 1 production) |
| How often do specifications need revision once work starts? | Spec-change pull requests per day spec |
| Does the 400-line gate hold without override? | `Oversized:` overrides used (currently 0) |
| Is the finished system actually independently deployable? | Each service builds, tests and deploys from its own workflow |

## Running it

The monolith still runs as it always did. Copy the environment file, bring up database, API and
web app, and open [http://localhost:3000](http://localhost:3000):

```bash
cp .env.example .env
scripts/dev-up.sh          # or: docker compose up --build
```

Run the contract test suite that Phase 0 is building:

```bash
cd backend && ./mvnw verify
```

Job listings come from the data pipeline, so a fresh local database shows an empty job list until
the pipeline publishes into it — see [`data/README.md`](data/README.md).

## Repository layout

```
.
├── plan.md             The migration plan: seven phases, four hard parts, what we are not doing
├── specs/              37 day specs — the contract for every change in this repository
├── docs/
│   └── original-readme.md   The monolith's own README, preserved
├── backend/            Spring Boot monolith — the subject of the migration
│   ├── src/test/.../contract/   Contract tests that must survive the split unchanged
│   └── src/test/.../support/    The Phase 0 test harness
├── frontend/           Next.js web app (unchanged)
├── data/               Data pipeline (unchanged)
├── scripts/            Local development and deployment scripts
├── .github/workflows/  CI/CD and the pull request gates
└── docker-compose.yml  The local stack
```

## Documentation

**The experiment**

| What | Where |
| --- | --- |
| The migration plan, phase by phase | [`plan.md`](plan.md) |
| The spec workflow and its rules | [`specs/README.md`](specs/README.md) |
| The 37 day specs | [`specs/`](specs/) |

**The system under migration**

| What | Where |
| --- | --- |
| The monolith's original README | [`docs/original-readme.md`](docs/original-readme.md) |
| Backend guide | [`backend/README.md`](backend/README.md) |
| Every endpoint: request, response, status codes, error shapes | [`backend/docs/api.md`](backend/docs/api.md) |
| Sign-in, sessions, Google OAuth and the password flows | [`backend/docs/auth.md`](backend/docs/auth.md) |
| How jobs are ranked and scored against a profile | [`backend/docs/matching-profile.md`](backend/docs/matching-profile.md) |
| Both database schemas, their tables, and who owns which | [`backend/docs/schema.md`](backend/docs/schema.md) |
| Frontend guide · Data pipeline guide | [`frontend/README.md`](frontend/README.md) · [`data/README.md`](data/README.md) |

## Credits

JobMatch was built by Hamed Razizadeh, Monerh Al Sqyan, Yusup Rozimemet, Halyna Romanyshyn,
Baraah Alshiaani and Mohamad Bader Almsaddi alzin as a HackYourFuture final project; the full
team listing is in the [original README](docs/original-readme.md#team). This migration
repository is my own work — the plan, the specifications and the reviews are mine, the
implementation is Claude Opus 5's under those specifications, and commits state which.
