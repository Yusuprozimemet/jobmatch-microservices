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

## Target repo structure

```
services/
  api-gateway/          routing, JWT verify, rate limit
  identity-service/     auth + user + profile
  job-service/          jobs + mart (read-only)
  application-service/  saved jobs + tracker
  matching-service/     shortlist + LLM scorer
functions/
  cv-parse/  mailer/
charts/
  common/               Helm library chart, shared templates
  (one thin chart per service)
deploy/argocd/
infra/terraform/
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
- Move `job_match_scores` to NoSQL — the key is already `(skills_hash, posting_id,
  scorer_version)` and the only non-key query is the purge.
- Set a TTL attribute. **Delete `JobMatchScoreCleanup` and `SchedulingConfig`.**
- Calls identity-service for skills, job-service for the shortlist.

### Phase 5 — Extract application-service + events
**Deletion first:** Days 26 → 27 → 25 → 28. The events and their consumers run while the
foreign key that deletes saved jobs with their user still exists; then the table moves.
- Add the message bus. `identity-service` emits `user.deleted` via a transactional outbox;
  application- and matching-service consume it. (`user.registered` has no consumer; it is
  added when one needs it.)
- `saved_jobs` to its own database; uses the Phase 1 interfaces over HTTP.
- A service that trusts the token's `sub` refuses a deleted user; an access token outlives
  its user by up to 15 minutes.
- **Done when:** deleting a user clears every store that holds user data, and
  `AccountDeletionIT` stays green through every day of the phase.
- **Day 28 (identity-service) is the stopping point.** Evaluate before Phase 6.

### Phase 6 — Functions + uploads bucket
- New private `uploads` container, separate from the pipeline's `prod`/`dev` landing zone.
- `identity-service` issues short-lived SAS URLs; the browser uploads directly.
- `cv-parse` function: blob-triggered, extracts skills, PUTs them to identity-service.
- `mailer` function: queue-triggered. There is no notification container to replace, and
  reset mail is already sent after commit and asynchronously; shrink or cut this when the
  phase is reached.

### Phase 7 — Kubernetes
- **One IaC tool**, chosen when the phase is written: cluster, Postgres, NoSQL, storage,
  Key Vault, and the cluster add-ons (ingress-nginx, cert-manager, External Secrets,
  Argo CD, Alloy).
- Helm: `charts/common` library chart + one thin chart per service.
  Flyway runs as a `pre-upgrade` hook Job, not at startup.
- Secrets via External Secrets + workload identity — no connection strings in values.
- matching-service scales on CPU or request rate (an HPA): top-matches is a synchronous
  request, so there is no queue to scale on.
- More than one gateway replica needs a shared rate-limit store (Day 15's limit is in
  memory); the ingress overwrites `X-Forwarded-For` and `GATEWAY_TRUSTED_PROXIES` names it.
- Criteria are written for one maintainer and an agent: no contributor counts, on-call
  rotas or alert owners. The secret sweep checks for secrets, not for the word `password`.
- Functions stay **outside** the cluster; `cv-parse` re-enters via the Ingress with a
  service token, because it cannot reach a ClusterIP.

---

## What we are not doing

- **No Postgres in Kubernetes.** Databases stay managed.
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
| 7 Kubernetes + IaC | large, mostly new skills |

Measured so far: 1.5× the estimated track PRs in each of Phases 0–2.

---

## Day-by-day specs

All seven phases are broken into 37 day specs in [`specs/`](specs/). Read
[`specs/README.md`](specs/README.md) for the workflow.

Days 1-16 (Phases 0-2) are done. Days 17-37 are **provisional**: written from this plan
before the course correction, so each is rewritten against the code, with the spec-auditor,
when it is reached, not before. Days keep their numbers, so history and links hold; they run
in this order, and the dashboard follows it:

1. The record fix and the platform step (new day specs, numbered from 38).
2. Phase 3: Days 18, 19, 17, 20.
3. Phase 4: the profile endpoint and user id out of Day 24 first, then Days 21, 22 (no dual
   write), 23, the rest of 24.
4. Phase 5: Days 26, 27, 25, 28. **Stop and evaluate.**
5. Phases 6–7 (Days 29–37): rewritten after the evaluation, or not started.
