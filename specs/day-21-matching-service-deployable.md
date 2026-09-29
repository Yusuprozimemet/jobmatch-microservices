# Day 21 — matching-service becomes its own deployable

**Phase:** 4 · **Depends on:** Day 41 · **Expected PRs:** 9
**Status:** provisional — rewritten against `main` at bb6d380 from the spec-auditor's first read;
the auditor reads it again when Day 41 has closed, and that day's spec-change PR deletes this line.

The second of Phase 4's days (41, 21, 22, 23, 24). The draft could not run: `ProfileDirectory` and
`@CurrentUserId` resolve through identity's repository, which a matching container cannot reach
since Day 38. Day 41 puts both behind identity's `/internal/**` routes first, so this day is a
change of URL, as Day 17 was for job-service.

## Goal
`matching` runs as `matching-service`, in its own container from its own image and pipeline. The
gateway sends `GET /api/jobs/top-matches` to it; it calls identity and job-service over HTTP; it
alone holds the LLM key and makes the LLM call. A hung model delays at most a bounded number of
top-matches requests, and never a request any other service serves. The Day 1–4 matching
contract classes pass unedited against the container, directly and through the gateway.

## In scope
- **Built as job-service was on Day 17**, at `services/matching-service`, and on its choices,
  which come back here (`day-17-...md:377`):
  - Its own pom on the backend's Boot parent, Java 25, the backend's checkstyle; a Dockerfile and
    `matching-service-ci-cd.yaml` copied from job-service's. Name `jobmatch-matching-service`;
    port 8080, management 9090, neither published.
  - **`shared` duplicated, not published.** It copies what `matching` uses, in the same
    packages: `ProfileDirectory`, `ProfileSnapshot`, `PostingShortlist`, `ShortlistedPosting`,
    `InternalClients`, `ServiceToken`, the `shared.web` reader of the token's `sub` from Day 41,
    and `GlobalExceptionHandler`'s `ResponseStatusException` handler.
  - **Its own service key**, as Day 39 and 17: a PEM at `SERVICE_JWT_PRIVATE_KEY_FILE`, tokens
    with `iss` and `sub` `jobmatch-matching-service`, the public half at
    `/.well-known/service-jwks.json`. The monolith (identity's routes) and job-service (the
    shortlist) add it to `app.internal.trusted-issuers`.
  - **User tokens verified in the service**, against identity's `/.well-known/jwks.json`, from the
    cookie and then the header, as the monolith does. The id is the `sub` (Day 41's decision);
    `X-User-Id` is not read.
  - **Its schema stays until Day 23.** `matching_user` on the `matching` schema, and Flyway
    applying `db/matching` (baseline 0, `app/.../config/Migrations.java`); `@EnableScheduling`, or
    `JobMatchScoreCleanup` silently stops purging. Days 22–23 move the scores and delete both.
  - Metrics and tracing as job-service's `management.*`, with `TracingConfig`'s propagator.
- **A bulkhead on the model call**: a resilience4j semaphore bulkhead, at most 10 concurrent
  scoring calls, no wait. A full bulkhead is answered as an unavailable model is today: the
  skill-overlap order, `aiScored` false (`JobMatchController`'s OpenAPI text). "A dedicated
  thread pool" meant nothing under virtual threads (`application.yaml:5`); a limit does.
- **Timeouts**: the model keeps 5 s connect and 20 s read; with Day 19's 1 s + 2 s internal
  calls, top-matches answers within the gateway's 30 s read. Stated, tested (below), unchanged.
- **Harness** (Day 40's hand-off): a `matching-service` entry in `support/Services`
  (`/api/jobs/top-matches`), a container from `jobmatch-matching-service:harness` on the test
  Postgres, pointing at the monolith for identity and at the job-service container for postings,
  and at `StubLlm`. `StubLlm` gains `hang()`.
- **Gateway**: `gateway.matching-service-url` (`MATCHING_SERVICE_URL`), defaulting to the
  backend's URL, and a `matching-service` route for `/api/jobs/top-matches` ahead of the backend
  route. Compose sets it in the last track, which is the switch.
- **The monolith lets go**: `backend/matching` deleted, `app.llm.*` and `LLM_API_KEY` out of the
  backend's config, `ModuleBoundariesTest`'s matching rules out, and `app/src/test/.../matching/`
  (`LlmCallObservedIT`, `InternalCallsObservedIT`) moved to what observes the container.

## Out of scope
- NoSQL, and deleting `JobMatchScoreCleanup` and `SchedulingConfig` — Days 22 and 23.
- A profile cache, and a datasource-free matching-service — Day 24.
- Caching the existence answer — Day 24 decides it with Day 39's reason against.

## Tracks

| Track | Owner | Work |
|---|---|---|
| 0 | | `StubLlm.hang()`; the isolation and bulkhead tests on today's code; the trace test |
| A | | Skeleton: pom, Dockerfile, workflow, security chains, service key, `/actuator/health` |
| B | | The move: `matching` and its `shared` copies, Flyway, scheduling, the bulkhead |
| C | | Harness: the container, the `Services` entry, the moved `matching/` tests |
| D | | Gateway: `matching-service-url` and the route, defaulting to the backend |
| E | | Compose and the switch: the service, the trust lists, `MATCHING_SERVICE_URL` |
| F | | The monolith lets go: module, config, boundaries rules; docs |

In order: 0, A, B, C, then D and E, then F. B's diff is mostly moves; if it passes 400 lines,
it splits at the `shared` copies, as Day 17's did.

## Acceptance criteria
- [ ] **new** — in the harness, `/api/jobs/top-matches` is served by the matching-service
      container: `ServiceRoutingIT` names it as the owner, and a request reaches it. Red today:
      `Services.owner("/api/jobs/top-matches")` is empty (the monolith).
- [ ] **hold** — `MatchTopMatchesIT`, `MatchRankingIT`, `MatchScoreCacheIT` and
      `SessionWithoutAUserIT` pass unedited, directly and through the gateway, with top-matches
      in the container. Broken on purpose: <Track C: the container's jobs URL at a closed
      port; what failed>.
- [ ] **new** — with `StubLlm` hanging and 10 top-matches requests in flight, an 11th answers
      within 1 s with `aiScored` false on every row. Red today: it waits the model's 20 s.
- [ ] **hold** — with `StubLlm` hanging and 10 top-matches requests in flight, `GET /api/jobs`
      (job-service) and `GET /api/profile` (the monolith) answer within 1 s. Isolated since Day
      17 for job search and by virtual threads for the rest; this keeps it so. Broken on
      purpose: <Track 0: the model call made on a one-thread executor shared with the profile
      read; what it reported>.
- [ ] **hold** — top-matches answers within 30 s with `StubLlm` hanging, the gateway's read.
      Broken on purpose: <Track 0: the model's read timeout at 40 s; what it reported>.
- [ ] **hold** — a request sent to the gateway with a `traceparent` reaches `StubLlm` and job-
      service's shortlist route with the same trace id. Broken on purpose: <Track 0: the
      propagator removed; what it reported>.
- [ ] **new** — `git grep -n "LLM_" -- backend/app/src/main backend/.env.example
      services/api-gateway/src/main services/job-service/src/main` finds nothing. Red today:
      finds 13, 6 in `backend/.env.example` and 7 in the backend's `application.yaml`. The
      harness (`MatchingTest`, `StubLlm`) and the docs keep the name; they configure and
      describe matching-service.
- [ ] **new** — `docker compose config --format json` lists `matching-service` with no `ports`,
      and `LLM_API_KEY` in its environment and in no other service's. Red today: no
      `matching-service`.
- [ ] **new** — `test -d backend/matching` fails. Red today: the module exists.

## Verify
```bash
docker build -t jobmatch-job-service:harness services/job-service
docker build -t jobmatch-matching-service:harness services/matching-service
cd backend && ./mvnw clean verify && ./mvnw -B checkstyle:check
cd .. && git grep -n "LLM_" -- backend/app/src/main backend/.env.example services/api-gateway/src/main services/job-service/src/main  # nothing
docker compose -p day21check --env-file .env.example config --format json | jq '.services["matching-service"].ports'  # null
```

## Notes
- **What the draft promised and the code had already delivered:** `/api/jobs` left the monolith on
  Day 17, and the monolith runs on virtual threads, so a hung model has not delayed job search
  since then. The latency criterion is a hold for that reason; the new behaviour this day adds
  is the bulkhead and the key's move.
- The counts above are for `main` at bb6d380; re-count at the day's spec-change PR, after Day 41.
- Expected PRs: 9 is the tracks, this spec change and the closing PR, less the tracks that join.
  Day 17 took 10 for a service with no user and no model; take its 1.5× note seriously.
- **Defect** — found: auditor · cause: spec · the draft kept `ProfileDirectory` in-process for a
  day, which no container can do since Day 38; had no `new`/`hold` tags; its latency criterion
  measured a path Day 17 had already moved; and it named no `shared` copies, key, Flyway,
  scheduling or harness entry. Rewritten in this PR, with Day 41 split out of Day 24.
