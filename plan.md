# Microservices migration plan

## Verdict: the split is mechanical, the four things around it are not

The package structure already matches the target services almost exactly
(`auth`, `user`, `profile`, `jobs`, `matching`, `savedjobs`). Cutting them apart is
straightforward. What is not straightforward:

| Hard part | Why | Fix |
|---|---|---|
| **No tests** | `backend/src/test` has one context-load test. Nothing will tell us if a split broke behaviour. | Phase 0. Non-negotiable. |
| **Session auth** | `JSESSIONID` + Spring sessions (`SecurityConfig`) can't work behind a stateless gateway. | Phase 2 — rewrite to RS256 JWT. |
| **Three cross-module reads** | `JobRepository` reads `saved_jobs`; `SavedJobRepository` joins `analytics.fct_postings`; `matching`'s `JobMatchRepository` builds its shortlist straight from the mart. Separate databases make all three impossible. | Phase 1 — replace with interfaces before any network exists. |
| **GDPR delete** | One `ON DELETE CASCADE` becomes a cascade across four databases. | Phase 5 — `user.deleted` event. |

**Do Phases 0–2 and stop if time runs short.** They deliver the boundaries, the
tests and the auth model. Phases 0–2 are done (tag `phase-2`). Phases 3–7 turned out
not to be deployment work that can wait unchanged: read against the code, they
extract before building what replaces the extracted code, and assume a test harness,
a service credential and a deletion path nobody builds. The course correction below
changes their order and scope.

---

## Course correction after Phase 2

Three audits ran at the Phase 2 stop: Phase 2 and Phase 3 against the code, Phases 0–1
after the fact, and the whole plan. Their findings, and what this plan now does:

- **Extraction came before the seam, three times.** Day 17 moves `jobs` out while
  `JobsDirectory` is the only `PostingLookup`/`PostingShortlist`; Day 21 needs identity's
  `ProfileDirectory` and user-id resolver; Day 25 moves `saved_jobs` before deletion works
  across services. **Rule from now on: build the seam in-process, test it, then extract.
  No commit on `main` removes an implementation whose replacement is not already serving.**
- **The harness runs one application.** `support/IntegrationTest` boots the monolith, and
  three `contract/` classes (23 tests) are bound to its context. No day owned a harness
  that runs more than one service.
- **No day owned** a service credential, the local compose profile, trace-visible HTTP
  clients, or revoking the grants that let every module login read every other schema
  (password hashes included).
- **Hand-offs got lost.** 8 of 19 in Days 1–11 were dropped, half done or broke early.
  Both auditors now check them (#113).
- **Parts of the plan do not match the system:** matching has no queue to scale on; there
  is no notification container, and reset mail is already asynchronous; Phase 7's criteria
  assume a team and a deployment this repository does not have.
- **Pace:** track pull requests have run at 1.5× the estimate in every phase.

**The scope rule.** Every Phase 3–5 task must establish a boundary, verify one, or make an
extraction safer. Anything else is cut or deferred. The corrected plan must not be larger
than the one it replaces.

**Order of work before Phase 3:**

1. **Fix the record.** CLAUDE.md and the README say the 400-line gate was never overridden;
   #1–#3 were. The gate reports and does not block, because `main` is not protected. Phase 2
   took 23 track PRs, not 21. The README's status table and the dashboard's fixed headline
   are out of date; the dashboard counts days with open boxes as done and non-spec PRs as
   spec changes. Day 13's browser-refresh box is ticked or waived with a reason. A
   `phase-2.1` tag marks `main` after #112 (`Secure` cookies); `phase-2` stays.
2. **The platform step,** its own day specs, before any extraction:
   - a harness that runs an extracted service beside the monolith, directly and through the
     gateway, so the Day 1–4 suite keeps passing unedited; what happens to the three bound
     `contract/` classes is decided here, before any of them would need an edit;
   - how an extracted service obtains a service credential, and the rule for a token whose
     user has been deleted: a service that trusts `sub` still refuses that user;
   - new migrations revoking the cross-module grants (with `db-setup.py`, `db-init/` and the
     test harness);
   - the local compose profile (see *Local development*);
   - every outbound HTTP client built from Spring's builder, so the LLM call is traced;
     who owns the gateway's metrics and the `/api/jobs` metric `ObservabilityIT` asserts.
3. **Then Phases 3–5 in the seam-first order below. Day 28 is the stopping point:** after
   it, the architecture is evaluated before Phases 6–7 are started or rewritten.

---

## Target cloud: AWS, decided before Phase 4

Until Phase 3 the plan named Azure where it named anything (Cosmos DB, SAS URLs, blob
triggers, Key Vault), because the data platform runs there. No code depends on it: Phases 0–3
built nothing cloud-specific, and Day 22 is the first day that would. The maintainer chose
AWS for the application runtime, to learn it; this is recorded here so the change of target
is part of what the repository measures, not a silent drift.

- **The application runtime moves to AWS; the data platform stays on Azure.** Python, dbt,
  Airflow and Databricks are out of scope and keep publishing the mart as they do today.
- **ECS on Fargate, not Kubernetes.** Phase 7 shrinks: no Helm, no Argo CD, no External
  Secrets, no cluster add-ons.
- **Emulators for tests, one real deployment at the end.** Compose and CI run DynamoDB, SQS,
  SNS, S3 and Lambda against local emulators; no test needs an AWS account. ECS, the load
  balancer, IAM and CloudWatch need a real one: Phase 7 deploys once, under a budget alarm,
  and tears down. The teardown is a criterion, not a courtesy.

| Plan needs | AWS | Local and CI | Day |
|---|---|---|---|
| Score store with a TTL | DynamoDB | `amazon/dynamodb-local` | 22–23 |
| `user.deleted` to two consumers | SNS topic → one SQS queue per consumer | emulator | 26–27 |
| Uploads bucket, direct browser upload | S3, presigned PUT URLs | emulator | 29 |
| `cv-parse`, `mailer` | Lambda (S3 event, SQS), SES | emulator | 30–31 |
| Postgres | RDS for PostgreSQL | the compose Postgres | 37 |
| Services | ECS on Fargate behind an ALB, images in ECR | compose | 34–37 |
| Secrets | Secrets Manager, ECS task roles | environment variables | 36 |
| Metrics, logs, traces | ADOT collector → CloudWatch, X-Ray | the Grafana stack | 37 |
| Infrastructure | Terraform, one state in S3 | Terraform against the emulator | 32 |

Observability needs no code change: Day 5 made the services export OTLP, and only the
collector's destination differs. Day numbers are where the provisional specs sit today;
Days 29–37 are rewritten after Day 28, as before, now for AWS.

---

## Target repo structure

```
services/
  api-gateway/          routing, JWT verify, rate limit
  identity-service/     auth + user + profile
  job-service/          jobs + mart (read-only)
  application-service/  saved jobs + tracker
  matching-service/     shortlist + LLM scorer
functions/
  cv-parse/  mailer/    Lambda
infra/terraform/        network, ECS services and task definitions, RDS, DynamoDB,
                        S3, SNS/SQS, ECR, ALB, Secrets Manager, CloudWatch
frontend/               unchanged
data/                   unchanged
```

Each service: own `Dockerfile`, own `pom.xml`, own Flyway migrations, own database,
own CI workflow (the repo already has per-area workflows — copy that pattern).

---

## Phases

Each phase is a series of <400-line PRs, so CI stays green.

### Phase 0 — Make the split safe
**Goal:** be able to tell if we break something.
- Integration tests (Testcontainers) covering the five public API surfaces:
  auth, profile, jobs search, saved jobs, top-matches. Test the HTTP contract,
  not the internals — these tests must survive the split unchanged.
- Add `spring-boot-starter-actuator` + Micrometer + OpenTelemetry tracing through
  `spring-boot-starter-opentelemetry`. (Planned as the Java agent; Day 05 found it produces
  no HTTP server spans on Spring Framework 7 / Tomcat 11 and suppresses Spring's own.)
- **Done when:** tests pass against the monolith and will be reused verbatim later.

### Phase 1 — Modularise in place (no network yet)
**Goal:** prove the boundaries hold while everything is still one process.
- Convert packages to Maven modules. A module may only call another through a
  published interface.
- Kill the three cross-module reads:
  - `saved_count` in job search → `SavedJobCounts` interface, returns counts by posting id.
  - Saved jobs hydration → `PostingLookup` interface, batch fetch by ids.
  - Match shortlist → `PostingShortlist` interface; the SQL moves into `jobs`.
- Resolve the user once, at the edge: controllers pass `userId` down instead of each
  module turning an email into an id through `UserDirectory`.
- Split migrations per module; give each its own schema.
- **Done when:** modules compile independently and no SQL crosses a schema.
- **This phase is where the real work is. It is also fully reversible.**

### Phase 2 — Gateway + JWT
**Goal:** stateless auth, still one backend behind it.
- New `api-gateway` (Spring Cloud Gateway): routing, CORS, rate limit, JWT verify via JWKS.
- Rewrite auth: RS256 JWT in an `HttpOnly; Secure; SameSite=Lax` cookie, refresh
  tokens in the database, `/.well-known/jwks.json` published.
- `PendingGoogleLink` moves from the session to a short-TTL table.
- Gateway forwards `X-User-Id` from the `sub` claim.
- **Done when:** login, Google sign-in and logout work with no server session.
- **Riskiest phase. Ship it alone so it can be rolled back alone.**

### Phase 3 — Extract job-service
Easiest extraction: read-only, no user data, the data pipeline already owns its schema.
**Seam first:** Days 18 → 19 → 17 → 20.
- Add internal endpoints `POST /internal/postings/batch` and `/internal/postings/shortlist`
  while `jobs` is still in the monolith (Day 18), and the HTTP clients behind
  `PostingLookup`/`PostingShortlist` (Day 19), in-process and tested.
- The monolith serves `POST /internal/saved-counts`, which job search needs once it leaves.
- Then own repo dir and own image (Day 17): the extraction is a change of URL.
- Own database (`analytics.fct_*`), and point the publish sync at it (Day 20).

### Phase 4 — Extract matching-service
Highest payoff: isolates the 20s LLM timeout from job search.
**Seam first:** identity's profile endpoint and client (Day 24's) come before Day 21, and so
does how matching gets the user id without identity's resolver.
- Move `job_match_scores` to DynamoDB — the key is already `(skills_hash, posting_id,
  scorer_version)` and the only non-key query is the purge.
- Set a TTL attribute; DynamoDB deletes expired items itself. **Delete `JobMatchScoreCleanup` and `SchedulingConfig`.**
- Calls identity-service for skills, job-service for the shortlist.

### Phase 5 — Extract application-service + events
**Deletion first:** Days 26 → 27 → 25 → 28. The events and their consumers run while the
foreign key that deletes saved jobs with their user still exists; then the table moves.
- Add the message bus: an SNS topic with one SQS queue per consumer. `identity-service`
  emits `user.deleted` via a transactional outbox; application- and matching-service
  consume it. (`user.registered` has no consumer; it is
  added when one needs it.)
- `saved_jobs` to its own database; uses the Phase 1 interfaces over HTTP.
- A service that trusts the token's `sub` refuses a deleted user; an access token outlives
  its user by up to 15 minutes.
- **Done when:** deleting a user clears every store that holds user data, and
  `AccountDeletionIT` stays green through every day of the phase.
- **Day 28 (identity-service) is the stopping point.** Evaluate before Phase 6.

### Phase 6 — Functions + uploads bucket
- New private S3 `uploads` bucket. The pipeline's landing zone stays on Azure.
- `identity-service` issues short-lived presigned PUT URLs; the browser uploads directly.
- `cv-parse` Lambda: triggered by the S3 upload, extracts skills, PUTs them to identity-service.
- `mailer` Lambda: SQS-triggered, sends through SES. There is no notification service to
  replace, and reset mail is already sent after commit and asynchronously; shrink or cut
  this when the phase is reached.

### Phase 7 — ECS on Fargate
- **Terraform** for everything long-lived: network, ECS cluster and services, RDS, DynamoDB,
  S3, SNS/SQS, ECR, the ALB, Secrets Manager and CloudWatch.
- One task definition per service, generated from one Terraform module.
  Flyway runs as a one-off ECS task before the deploy, not at startup.
- Secrets from Secrets Manager into the task definition, AWS access through task roles —
  no connection strings or access keys in variables or images.
- matching-service scales on CPU or ALB request count (ECS service auto scaling):
  top-matches is a synchronous request, so there is no queue to scale on.
- More than one gateway task needs a shared rate-limit store (Day 15's limit is in
  memory); the ALB sets `X-Forwarded-For` and `GATEWAY_TRUSTED_PROXIES` names it.
- An ADOT collector sidecar sends the services' OTLP to CloudWatch and X-Ray.
- Criteria are written for one maintainer and an agent: no contributor counts, on-call
  rotas or alert owners. The secret sweep checks for secrets, not for the word `password`.
- The Lambdas re-enter through the ALB with a service token; the services are not public
  otherwise.
- **Done when:** one real deployment serves the five public API surfaces under a budget
  alarm, and `terraform destroy` leaves nothing billable behind.

---

## What we are not doing

- **No self-run Postgres.** Databases stay managed: RDS.
- **No Kubernetes.** ECS on Fargate runs five services without a cluster to operate.
- **No move of the data platform.** It stays on Azure; only the application runtime moves.
- **No AWS account in tests.** Everything a test touches runs locally.
- **No separate profile-service.** Profile stays in identity-service — same key, no
  independent scaling need.
- **No managed API gateway.** Spring Cloud Gateway is code we can test locally.
- **No Terraform *and* Pulumi.** Two state stores, two CI credentials and an ordering
  dependency bought nothing; Phase 7 uses one.
- **No dual write for the NoSQL move (Day 22).** A score cache can cut over directly.

## Local development

Phases 3+ break `docker compose up`. Before Phase 3, add a compose profile that runs
all services plus one Postgres with several databases. If local dev gets painful,
the team stops testing locally — treat this as part of the phase, not an afterthought.
**Owner: the platform step.** Each extracted service joins the default `up`, as the gateway
did on Day 16.

## Rough effort

| Phase | Size |
|---|---|
| 0 tests + telemetry | large, unavoidable |
| 1 modularise | large — the real work |
| 2 gateway + JWT | medium, high risk |
| platform step | medium: harness, service credential, grants, compose |
| 3–5 extractions | medium each, low risk only seam-first |
| 6 functions | small |
| 7 ECS + Terraform | large, mostly new skills; smaller than the Kubernetes phase it replaces |

Measured so far: 1.5× the estimated track PRs in each of Phases 0–2. From the platform step on,
the README's Day entries record two estimates, the provisional spec's and the rewritten one's
(Day 20: 3 and 5; took 5).

---

## Day-by-day specs

All seven phases and the platform step are broken into 40 day specs in [`specs/`](specs/). Read
[`specs/README.md`](specs/README.md) for the workflow.

Days 1-20 (Phases 0-3) and 38-40 (the platform step) are done. Days 21-37 are **provisional**:
written from this plan before the course correction, so each is rewritten against the code,
with the spec-auditor, when it is reached, not before. Days keep their numbers, so history and
links hold; they run in this order, and the dashboard follows it:

1. The record fix and the platform step (new day specs, numbered from 38).
2. Phase 3: Days 18, 19, 17, 20.
3. Phase 4: the profile endpoint and user id out of Day 24 first, then Days 21, 22 (no dual
   write), 23, the rest of 24.
4. Phase 5: Days 26, 27, 25, 28. **Stop and evaluate.**
5. Phases 6–7 (Days 29–37): rewritten for AWS after the evaluation, or not started.
