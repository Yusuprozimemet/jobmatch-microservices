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
- **Reviewing the suite against Day 13 found it would not have survived.** Three contract classes
  named `JSESSIONID` outright, while Day 13 requires that nothing sets that cookie — two
  acceptance criteria that could not both hold. The name is now one line in `support/`, fixed in
  a spec-change pull request before the day rather than during it. The criterion it was measured
  by ("assert `Set-Cookie` attributes only") was satisfied to the letter throughout.

**Day 3 — profile and job search contract tests.** 68 tests across seven classes covering the
profile round trip and its skill normalisation, job search with paging and all four filters the
endpoint accepts, the filter options endpoint, job detail, the `savedCount` field, and which
`/api/jobs` routes a logged-out visitor may reach. Full suite: 131 tests in ~60 seconds.

- **The spec named a filter the endpoint does not have.** It asked for a filter on employment
  type and called the city filter "city"; the search takes `category`, `workMode`, `location` and
  `q`. One criterion could not be ticked without writing the feature it tested, and the free-text
  filter the search page leads with was missing from the spec entirely. Corrected in a
  spec-change pull request *before* the work, which is now the second time review of a spec
  against the code it describes has been worth more than the code review after it.
- **Another harness blind spot, same shape as Day 2's.** `ApiResponse` read JSON floats as
  doubles, so a response carrying `45000.00` parsed back to `45000.0` and no assertion on a
  number could have seen a currency scale change. The API was correct throughout; only the test
  client's view of it was lossy. Found by writing the assertion, not by reviewing the harness.
- **Two disagreements inside the monolith, pinned rather than fixed.** `/api/jobs/filters` offers
  employment type and experience level as options that the search silently ignores, because
  Spring drops a query parameter no `@RequestParam` declares. And search returns closed postings
  while matching excludes them. Both are product questions; pinning them turns the answer into a
  visible decision instead of a silent drift during the split.
- **A defect found in a spec three days ahead.** Checking what `savedCount` has to survive turned
  up two items in Day 8 that do not match the code: "the three other places" the join appears in
  is one other place, and a `// TODO day-08` marker it asks to remove does not exist, so that
  acceptance criterion ticks itself. The same failure mode as Day 2's CI gate — a check that
  passes because it is checking nothing.
- *Estimated 3 pull requests, took 4.* Track A came to 406 lines against the 400-line gate and
  was split. The gate has now bitten twice and has still not been overridden.

**Day 4 — saved jobs and matching contract tests.** 53 tests across six classes covering the
saved-job tracker, the posting details it hydrates, and `/api/jobs/top-matches` with the language
model replaced by a real HTTP stub. Full suite: 184 tests in ~90 seconds.

- **Three of the day's six acceptance criteria could not have been met, and one was a hazard.**
  The spec asked for the model to be stubbed, for a stub error to fall back to a 200, and for a
  repeat request to hit the stub exactly once. `application-test.yaml` blanks `app.llm.api-key`,
  which makes `MatchScorer` return before it makes any call — all three would have gone green
  testing nothing. Worse, the test profile sets no `app.llm.base-url`, so anyone enabling the key
  without also overriding the URL would have pointed the suite at a live provider, in a file whose
  own header says a test run must not reach the internet. Found by reading the spec against the
  code before starting, and fixed in a spec-change pull request.
- **The fixture is worse than production in the one path the day tests.** `MatchScorer` maps the
  model's reply back by the first eight characters of a posting id. Production ids are `md5(...)`
  and do not collide; every seeded id is `seed-00NN`, so `seed-0001` and `seed-0002` are the same
  id to the model. A test written against seed postings would have scored one and dropped the
  other without a word. Day 1 established that a fixture must not be *nicer* than production; this
  is the same rule from the other side.
- **Saved jobs and job search disagree about the same posting, twice** — on location, because one
  reads the free-text column and the other the normalised city bridge, and on skill order. Both
  are pinned so Day 9's shared `PostingLookup` has to make a decision rather than satisfy one view
  and quietly break the other.
- *Estimated 3 pull requests, took 5.* Track C came to 446 lines against the 400-line gate and was
  split. The gate has now forced a split three times and still has no overrides.
- Unrelated to the day, and worth recording: the `build` job went red twice on Maven Central
  rate-limiting. The Dockerfile copies the whole source tree before `mvn package`, so every commit
  re-downloads the full dependency tree and the layer cache never hits. The tests were never
  involved.

**Early read on the hypothesis.** Across four days the agent's implementation has been sound and
its most useful output has been *disagreement with the spec*. The constraint is specification
quality and estimation, as predicted — but not in the way predicted. The expectation was that
specs would be found wanting once the code was written against them. Instead, the two most
valuable findings came from reading a spec against the code **before** writing anything, and Day
4's would have produced a green suite that tested nothing had it not been caught. Phase 0 now ends
with 184 tests and a standing practice: review the day's spec against the system first, and change
it in its own pull request.

Still worth noting what has not happened: **no finding has come from reviewing the agent's code.**
Every one came from reading the system, or from checking a spec against it. The hard evidence
comes at Day 13, when the session-auth rewrite must pass Day 2's 49 tests unchanged.

## What is being measured

| Question | Evidence it will be judged on |
| --- | --- |
| Do contract tests written against the monolith survive the split? | Lines changed in `contract/` versus in `support/` after Day 13, and again after Days 17–28 — target: zero in `contract/` |
| How good are day-sized estimates for agent-implemented work? | Estimated vs actual pull requests per spec (currently 3→6, 3→3, 3→4, 3→5) |
| Does the agent catch defects in the system it is migrating? | Bugs found and filed per phase (currently: 1 CI gate, 2 harness, 1 production, 2 specs that could not be met, 1 fixture unlike production) |
| How often do specifications need revision once work starts? | Spec-change pull requests per day spec (currently 3 of 4 days worked) |
| Does the 400-line gate hold without override? | `Oversized:` overrides used (currently 0, with the gate having forced a split three times) |
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
