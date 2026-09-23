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
tests and the auth model. Phases 3–7 are deployment work that can happen later
without redoing anything.

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
- Own repo dir, own image, own database (`analytics.fct_*`).
- Add internal endpoints `POST /internal/postings/batch` and `/internal/postings/shortlist`.
- Point the publish sync at the new database.

### Phase 4 — Extract matching-service
Highest payoff: isolates the 20s LLM timeout from job search.
- Move `job_match_scores` to NoSQL — the key is already `(skills_hash, posting_id,
  scorer_version)` and the only non-key query is the purge.
- Set a TTL attribute. **Delete `JobMatchScoreCleanup` and `SchedulingConfig`.**
- Calls identity-service for skills, job-service for the shortlist.

### Phase 5 — Extract application-service + events
- `saved_jobs` to its own database; uses the Phase 1 interfaces over HTTP.
- Add the message bus. `identity-service` emits `user.registered` / `user.deleted`
  via a transactional outbox; application- and matching-service consume the delete.
- **Done when:** deleting a user clears all four stores.

### Phase 6 — Functions + uploads bucket
- New private `uploads` container, separate from the pipeline's `prod`/`dev` landing zone.
- `identity-service` issues short-lived SAS URLs; the browser uploads directly.
- `cv-parse` function: blob-triggered, extracts skills, PUTs them to identity-service.
- `mailer` function: queue-triggered. Replaces the always-on notification container.

### Phase 7 — Kubernetes
- Terraform: cluster, Postgres, NoSQL, storage, Key Vault.
- Pulumi: cluster add-ons (ingress-nginx, cert-manager, KEDA, External Secrets, Argo CD, Alloy).
- Helm: `charts/common` library chart + one thin chart per service.
  Flyway runs as a `pre-upgrade` hook Job, not at startup.
- Secrets via External Secrets + workload identity — no connection strings in values.
- KEDA scales matching-service on queue depth, not CPU.
- Functions stay **outside** the cluster; `cv-parse` re-enters via the Ingress with a
  service token, because it cannot reach a ClusterIP.

---

## What we are not doing

- **No Postgres in Kubernetes.** Databases stay managed.
- **No separate profile-service.** Profile stays in identity-service — same key, no
  independent scaling need.
- **No managed API gateway.** Spring Cloud Gateway is code we can test locally.
- **Terraform *and* Pulumi is a deliberate cost**, not a benefit: two state stores, two
  CI credentials, an ordering dependency. One tool would be simpler. Revisit at Phase 7.

## Local development

Phases 3+ break `docker compose up`. Before Phase 3, add a compose profile that runs
all services plus one Postgres with several databases. If local dev gets painful,
the team stops testing locally — treat this as part of the phase, not an afterthought.

## Rough effort

| Phase | Size |
|---|---|
| 0 tests + telemetry | large, unavoidable |
| 1 modularise | large — the real work |
| 2 gateway + JWT | medium, high risk |
| 3–5 extractions | medium each, low risk after Phase 1 |
| 6 functions | small |
| 7 Kubernetes + IaC | large, mostly new skills |

---

## Day-by-day specs

All seven phases are broken into 37 day specs in [`specs/`](specs/). Read
[`specs/README.md`](specs/README.md) for the workflow.

Days 1-16 (Phases 0-2) are ready to work. Days 17-37 are marked **provisional**: they were
written from this plan rather than from experience, so review and revise each phase before
starting it.
