# JobMatch → Microservices

**An experiment in spec-driven development: can a working monolith be migrated to microservices
by an AI agent, when the work is governed by written specifications instead of conversation?**

This repository takes [JobMatch](docs/original-readme.md) — a deployed, six-person
HackYourFuture final project — and rebuilds it as a set of independently deployable services.
Every change is driven by a numbered day specification with acceptance criteria written *before*
any code exists. The implementer is [Claude Opus 5](https://www.anthropic.com/claude) running at
high reasoning effort; the architecture, the specifications and every merge decision are mine.

> **Status:** in progress. The [migration dashboard](https://yusuprozimemet.github.io/jobmatch-microservices/) shows the current day
> and the next step; it is rebuilt from the repository after every merge. This is an open lab
> notebook, not a finished system. Findings so far are in [Results to date](#results-to-date).

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
| **0 — Make the split safe** | 1–5 | Integration tests over the five public API surfaces, asserting the HTTP contract only, so they survive the split unchanged. Actuator, Micrometer, OpenTelemetry. | done |
| **1 — Modularise in place** | 6–11 | Maven modules that may only call each other through published interfaces; the three cross-module reads replaced by interfaces; migrations split per module. No network yet. | **in progress** |
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

**Day 5 — observability baseline.** Actuator on its own unpublished port, Prometheus and OTLP
registries, structured JSON logs, request tracing, and a Grafana stack behind a compose profile.
Seven of nine acceptance criteria hold. Full suite: 196 tests.

- **The day's central assumption was wrong, and finding out took four pull requests.** The spec
  said to trace with the OpenTelemetry Java agent. On Spring Framework 7 and Tomcat 11 the agent
  produces JDBC spans and no request span — and, measured as an A/B against the running stack, it
  *suppresses* Spring's own instrumentation, so attaching it makes things worse than leaving it
  off. The agent is gone; traces come from `spring-boot-starter-opentelemetry`. The trade is
  recorded rather than papered over: we gained the request span and lost the JDBC spans.
- **Two configuration traps, both silent.** The sampling property is
  `management.tracing.sampling.probability`; the name without `sampling` binds to nothing and
  leaves sampling at 0.1. And an OTLP endpoint set to the empty string does not mean "no
  exporter" — the exporter fails to build and the application does not start, which took out all
  195 tests at once.
- **The health endpoint would have taken the application out of the load balancer.** The mail
  indicator opens an SMTP connection on every check and reports DOWN when the relay is
  unreachable, dragging the whole endpoint down. Phase 7 makes that the Kubernetes readiness
  probe, so an undeliverable password-reset email would have stopped everyone browsing jobs.
- **`docker compose up` could never start the backend.** `DB_PORT` was read by the prod profile
  and set by nothing, so the container died on an invalid JDBC URL. Pre-existing, unrelated to
  this day, and found only because the day's Verify block required the stack to come up.
- **I got two readings wrong and corrected both in the open.** I reported that the starter
  instruments requests on evidence that turned out to come from the agent being attached too; and
  I reported that log correlation did not work, from a Tomcat error line written after the span
  scope closes. Both corrections are in the pull requests that made them.
- *Estimated 3 pull requests, took 7.*

**Phase 0 is complete.** 196 tests, a metrics endpoint, structured logs, and a trace you can find
from a log line. The contract tests that Days 2 to 4 built are the thing the rest of the migration
is measured against.

**Day 6 — Maven multi-module skeleton.** `backend` becomes a parent over six modules. The feature
modules are created empty and the code moves wholesale into `app`; Day 7 distributes it. All seven
criteria hold, 196 tests still green.

- **The dependency rule is enforced, not documented.** `maven-enforcer` fails the build when a
  feature module reaches for another, with a message saying to publish an interface instead. Proved
  by breaking it on purpose and watching it fail — a rule nobody has seen fail is a comment.
- **A green test suite does not mean the image builds.** The Dockerfile copied one module's sources,
  so the moment `shared` had code the container could not compile it, while `mvnw verify` — which
  builds the tree that is actually on disk — stayed green. Day 7 gives five more modules their first
  sources, so the bug was queued to recur five times.
- **Reading the spec against the code first found three classes with no module to go to**, including
  one used by two future services that therefore cannot live in either. It is in `shared` with an
  expiry date written into the pom.
- *Estimated 2 pull requests, took 2.* The first day that landed on its estimate.

**Day 7 — move the code into the modules.** Roughly seventy classes move into `identity`, `jobs`,
`applications` and `matching`, leaving `app` with five files. `applications` and `matching` stop
reading `identity`'s tables and ask through two interfaces in `shared`. 202 tests green.

- **Not one test file was edited.** The 196 contract tests never name an application class, so a
  seventy-class package move was invisible to them. That claim was made throughout Phase 0 and
  this is the first time it was put under load. It held.
- **The spec's central instruction was wrong in a way that would have looked like success.** It
  said to expect three compile failures; two of them were SQL strings, which no compiler can see.
  Following it, you would have moved the code, found one kind of failure instead of three, had
  nothing to unblock for Days 8 and 9, and concluded the boundary was clean while two modules
  still read each other's tables. The corrected spec has a criterion that runs backwards: after
  the move, those joins must **still be there**.
- **`UserLookup`, the class the spec said to break the coupling on, does not exist** anywhere in
  the repository. Day 10 is titled "Delete `UserLookup`" and rests on the same premise.
- **I tested my own justification for a file and it was false.** The ArchUnit rules were
  introduced as catching what `maven-enforcer` cannot; adding a dependency and an import together
  fails at the enforcer first. They earn their place as an independent record of the boundary —
  with the enforcer skipped they still fail — which is a smaller claim and the true one.
- *Estimated 4 pull requests, took 5.*

**Day 8 — remove the `saved_count` join.** `jobs` stops reading `saved_jobs` and asks
`applications` through `SavedJobCounts`, one call per page. The first of the two cross-module
joins is gone. 206 tests green.

- **The gate Day 3 wrote for this day passed unedited.** `JobSavedCountIT` was written five days
  earlier with a note that it "must not need editing"; the join under it was replaced and it did
  not. This is the first time a contract test stood between a refactor and a behaviour change
  instead of only recording one.
- **The spec's verify command ran no tests at all.** With `-Dtest` and no `-pl`, surefire applied
  the pattern to every module and stopped on `shared`, before `app`, where the tests live. It
  failed loudly, but `app/target` still held a green report from an earlier run, and anyone who
  read that instead of the exit code saw six passes that had not happened. A different route to
  Day 2's failure mode: a check reporting on tests it did not run.
- **One criterion asked for something no test could see.** "Assert the query count" had nothing
  to count with: the contract tests speak HTTP and JDBC, and neither shows how many statements a
  request ran. `pg_stat_statements` in the test container does, from the database side, without
  touching an application bean. The test it made passes before the change and after, so it was
  broken on purpose first: a per-posting loop failed it with 1 + page size statements.
- **Two slips of mine, caught before pushing:** a comment that put `saved_jobs` back under `jobs/`
  and failed the day's own grep, and a checkstyle violation that 206 green tests could not see,
  because `mvnw verify` does not run checkstyle.
- *Estimated 2 pull requests, took 3.* The third is the query-count test, which the spec change
  added before work started.

**Day 9 — remove the hydration join, and move the shortlist.** `applications` and `matching` stop
reading the mart. They ask `jobs` through `PostingLookup` for posting details and through
`PostingShortlist` for match candidates. No SQL crosses a module boundary any more. 210 tests
green.

- **The spec's central instruction could not be followed.** "Order and page inside `saved_jobs`"
  was impossible: the list is ordered by a mart column, and `saved_jobs` has nothing to order by.
  Following it would have changed the order users see and failed a Day 4 test. The fix hydrates
  the whole personal list and pages in memory.
- **`plan.md` counted two cross-module reads; there were three.** `matching` built its shortlist
  straight from the mart, and no Phase 1 day moved it. Found by reading every remaining spec
  against the code before starting, and added as Track C the same day.
- **Breaking the code on purpose found what reading had not.** Closing under the new rule that
  every check must be seen to fail, I reversed the saved-jobs sort. All 31 of Day 4's tests
  passed. They pin where a vanished posting goes, not which way the list runs. The spec had said
  Day 4 pins the order, and two spec changes and four track PRs had read past it. Nothing changed
  for users, since Track B kept newest first, but only by reading.
- *Estimated 2 pull requests, took 4.* Both extras came from spec changes before the work: the
  query-count test, and Track C.

**Day 10 — resolve the user once, at the edge.** Only `identity` turns an email into a user now.
`applications` and `matching` receive a user id from `@CurrentUserId` and never see an email;
`UserDirectory` is deleted. 217 tests green.

- **The first day run under the rule that every check must be seen to fail.** The rule's first
  catch was in the spec: it protected two modules' different answers for a session with no user
  behind it, and no test covered that case. A test pinning them landed before anything moved, and
  was broken on purpose twice across the day.
- **The resolver returns an empty value instead of throwing,** so each module keeps its own answer
  (404 and 422) and the order of answers is unchanged.
- **A branch copied into four controllers could never run.** Each copy accepted a principal type
  that nothing in the application creates. Removing it left every test green.
- **I set Day 8's trap again and caught it before pushing:** a comment in the day's own test named
  the class the day's grep checks is gone.
- *Estimated 3 pull requests, took 4.* The fourth is the tests-first track the spec change added.

**Day 11 — split the database by module.** Every table is in its module's schema, owned by its
module's role, and each module connects as that role: a write into another module's tables is
refused by Postgres. Each module has its own migrations and history. The end of Phase 1. 232
tests green.

- **The spec's plan could not have run.** It had each module's own migrations move its tables,
  but only a table's owner can move it. Tried on a throwaway Postgres before any work, and the
  moves now run as the owner and hand each table over.
- **Account deletion still works under the strict roles,** because Postgres runs a cascade as
  the owner of the table it deletes from. A test for it landed first; nothing had covered it.
- **Four more things only running found:** an enum that does not move with its table, a
  read-only pool setting that did nothing, an apostrophe that broke Flyway, and four default-sized
  pools that ran the test run out of connections.
- **One Day 1 test was rewritten, by decision:** it pinned every table to the old schema, the
  layout the day existed to change. `contract/` was not touched.
- *Estimated 3 pull requests, took 6.* Two came from the spec change (a tests-first track, and
  data sources as a track of their own), one from the 400-line gate splitting a track.

**Day 12 — JWT issuance and JWKS.** `identity` can mint RS256 access tokens, publish the key
they verify with at `/.well-known/jwks.json`, and issue, redeem and revoke refresh tokens stored
only as their hash. Nothing uses them yet: login is untouched until Day 13. 255 tests green.

- **The first day audited by agents with a fresh context** (#73). Before any work, the
  plan-auditor and the spec-auditor found that neither of the spec's checks could fail, that
  its key could come from nowhere without breaking the clean compose start, that identity's
  first migration would break two Day 1 harness tests, and that a missing `jti` would have made
  a Day 13 test flaky. All of it was fixed in the spec before the work (#74).
- **Breaking the code on purpose found one more:** the check that other modules cannot read the
  token hashes passed with the migration's revoke removed, as long as the test database lacked
  the default privileges production has. The test now asserts that they are there.
- **Two Day 1 harness tests changed, by decision before the work:** they pinned identity's
  table list and migration history, which the day's first migration grows. `contract/` was not
  touched.
- *Estimated 3 pull requests, took 3.* The spec change raised it to 4 for a split of the key
  track under the 400-line gate, which was not needed.

**Day 13 — replace session auth with JWT.** The backend authenticates from an access-token
cookie verified in process and holds no session; a refresh cookie, rotated on use and revoked at
logout and on a new password, keeps a browser signed in past the token's 15 minutes; the
frontend refreshes once on a 401. 275 tests green.

- **The test the plan was built around held.** The session-auth rewrite passed Day 02's 49 auth
  tests, and all 190 contract tests, with nothing in `contract/` edited: the auth cookie's name
  was the one line the contract suite reads that changed, as Day 02's spec change had arranged.
- **It held because of what the spec-auditor found first.** Its scratch build of the day, before
  any work, showed the spec as written would have turned the principal into a token object (81
  contract tests red without the fix), broken Google sign-in, left account deletion signing no
  one out, and let an expired cookie turn login, register and the job list into 401s with every
  contract test green. The frontend would have signed every user out after 15 minutes. All of it
  went into the spec before the work (#82).
- **The switch landed last,** after the tests, the cookies, refresh and the frontend, so `main`
  never signed a browser out early and reverting one PR restores session auth.
- *Estimated 3 pull requests, took 5.* The spec change added a tests-first track and a frontend
  track.

**Read on the hypothesis at the end of Phase 0.** Across five days the agent's implementation has
been sound and its most useful output has been *disagreement with the spec*. The constraint is
specification quality and estimation, as predicted — but not in the way predicted. The expectation
was that specs would be found wanting once code was written against them. For Days 3 and 4 the
most valuable findings came earlier still, from reading a spec against the code before writing
anything.

Day 5 is the counter-example, and the more honest one. Its central assumption — trace with the
Java agent — could not be checked by reading anything. It needed the stack running, a request
made, and the result looked at; and the answer was that the tool the spec named does not work on
this stack and makes things worse when present. That cost four pull requests and produced two
intermediate conclusions that were themselves wrong and had to be corrected in public. **Days
written from documentation are checked by reading; days written from assumption are only checked
by running.**

What still has not happened: **no finding has come from reviewing the agent's code.** Every one
came from reading the system, checking a spec against it, or running the thing and looking. The
hard evidence comes at Day 13, when the session-auth rewrite must pass Day 2's 49 tests
unchanged.

**Read at the end of Phase 1.** The modules are separated three ways now: at compile time (the
Maven enforcer, Day 6), in the test suite (ArchUnit, Day 7), and in the database (one role and one
schema each, Day 11). Across Days 6–11, which moved about seventy classes, removed three
cross-module reads and split the database, no existing contract test was changed; `contract/`
only gained tests.

The shape of the findings changed after Day 9. Until then they came from reading a spec against
the code. Since then a second source has found as much: **breaking the code on purpose.** A check
has to be seen failing before it counts, and doing that found a gate that did not pin the order it
claimed to (Day 9), statuses the spec protected with no test behind them (Day 10), and a setting
that did nothing (Day 11). Still no finding has come from reviewing the agent's code. Day 13 is
the test the plan was built around, now with the database roles in place under it.

## What is being measured

| Question | Evidence it will be judged on |
| --- | --- |
| Do contract tests written against the monolith survive the split? | Lines changed in `contract/` versus in `support/`. **Day 7 moved ~70 classes into modules: zero lines changed in `contract/`. Day 8 replaced a cross-module join: zero again, and 53 lines added to `support/` for a statement counter. Day 9 removed two more: zero again. Day 10 moved the user lookup to the edge: zero again. Day 11 split the database by module: zero again. Day 12 added token issuance: zero again, and 73 lines added to `support/` for a signing key and production's default privileges. Day 13 replaced session auth with tokens: zero again, with `Cookies.AUTH` the one line changed that the contract suite reads, and 131 lines added to `support/` for three helpers only the tests outside `contract/` use.** Additions between refactors are counted apart: 32 lines pinning the saved-jobs order (#57), and one new file for Day 10's tests-first track. Day 13 and Days 17–28 are the remaining tests |
| How good are day-sized estimates for agent-implemented work? | Estimated vs actual pull requests per spec (currently 3→6, 3→3, 3→4, 3→5, 3→7, 2→2, 4→5, 2→3, 2→4, 3→4, 3→6, 3→3, 3→5) |
| Does the agent catch defects in the system it is migrating? | Bugs found and filed per phase (currently: 1 CI gate, 2 harness, 2 production, 8 specs that could not be met, 3 spec verify commands that ran no tests, 1 spec check that could not fail, 1 plan that missed a cross-module read, 1 gate that does not pin what its spec says, 2 protected behaviours with no test behind them, 1 dead branch copied into four controllers, 1 plan that could not have run as written, 3 database objects a spec missed (a moved enum, a cascade, a revoke), 1 missing claim that would have made a test flaky, 1 migration that broke two harness tests by construction, 1 setting that did nothing, 2 fixtures unlike production, 1 principal a spec would have changed by accident, 1 frontend regression a spec missed, 1 CI build that re-downloaded the world) |
| How often do specifications need revision once work starts? | Spec-change pull requests per day spec (currently 12 of 13 days worked; Days 5, 8, 9, 10, 11 and 12 needed two and Day 13 three, Day 8's first made on Day 3 while writing its gate, Day 13's first on Day 2, those of Days 10 to 13 on Day 9) |
| Does the 400-line gate hold without override? | `Oversized:` overrides used (currently 0, with the gate having forced a split four times, the fourth Day 11's Track D) |
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
