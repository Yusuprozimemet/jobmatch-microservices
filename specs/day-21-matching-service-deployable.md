# Day 21 — matching-service becomes its own deployable

**Phase:** 4 · **Depends on:** Day 41 · **Expected PRs:** 15

The second of Phase 4's days (41, 21, 22, 23, 24). Day 41 put `ProfileDirectory` and the user's
existence behind identity's `/internal/**` routes, so matching reaches nothing in-process that a
container could not: this day moves it out, as Day 17 moved job search.

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
    `InternalClients`, `ServiceToken`, `TokenSubject` (Day 41's reader of the token's `sub`), and
    `GlobalExceptionHandler`'s `ResponseStatusException` handler.
  - **Its own service key**, as Days 39 and 17: a PEM at `SERVICE_JWT_PRIVATE_KEY_FILE`, tokens
    with `iss` and `sub` `jobmatch-matching-service`, the public half at
    `/.well-known/service-jwks.json`. The monolith (identity's routes) and job-service (the
    shortlist) add it to `app.internal.trusted-issuers`, and so does the harness's list
    (`IntegrationTest:61-65`, and the job-service container's).
  - **User tokens verified in the service**, against identity's `/.well-known/jwks.json`, from the
    cookie and then the header, as the monolith does. The id is the `sub` (Day 41's decision);
    `X-User-Id` is not read. **The verified token's authentication carries a `TokenSubject` in
    its details**, as identity's `AccessTokenAuthentication:77` does: `JobMatchController:43`
    reads the id only from there, so without it every user gets 422.
  - **The internal URLs are required.** `app.internal.identity-url` and `jobs-url` have no
    default in the service, and it does not start without them: empty meant "this process" in the
    monolith (`application.yaml:79-83`), and this process has no `/internal/**` routes.
  - **Its schema stays until Day 23.** `matching_user` on the `matching` schema, and the
    service's own Flyway applying `db/matching` (baseline 0, as `app/.../config/Migrations.java`
    does today). From Track E1 the monolith stops applying it; in the harness the container
    applies it when it starts, against the test Postgres, where `PostgresContainer` still creates
    the role and schema.
  - **Scheduling moves with it.** `SchedulingConfig` goes to the service with
    `JobMatchScoreCleanup`, the only `@Scheduled` in the monolith, or the purge silently stops;
    the monolith's copy is deleted. Day 23 deletes the service's.
  - Metrics and tracing as job-service's `management.*`, with `TracingConfig`'s propagator.
  - **Its own `RestClient` rule.** Day 38's `ModuleBoundariesTest.restClientsComeFromSpring`
    analyses `nl.hackyourfuture.project.backend` only, so matching's three clients leave it with
    the move. The service's module gets the same ArchUnit rule; so does job-service's, which has
    none.
- **A bulkhead on the model call**: a resilience4j semaphore bulkhead, at most 10 concurrent
  scoring calls, no wait. A full bulkhead is answered as an unavailable model is today: the
  skill-overlap order, `aiScored` false (`JobMatchController`'s OpenAPI text, `:36`). "A
  dedicated thread pool" meant nothing under virtual threads (`application.yaml:4-6`); a limit
  does.
- **Timeouts fit the gateway's 30 s read.** Since Day 41 a top-matches request makes three
  internal calls in a row (existence, profile, shortlist), each 1 s connect and 2 s read (Day 19),
  then the model call, 5 s connect and 20 s read: 34 s at worst. The service's model read is
  **15 s** (`LLM_TIMEOUT_SECONDS`), so the worst case is 9 + 5 + 15 = 29 s. Stated in the
  service's `application.yaml` beside the value.
- **Harness** (Day 40's hand-off): a `matching-service` entry in `support/Services`
  (`/api/jobs/top-matches`), a container from `jobmatch-matching-service:harness` on the test
  Postgres, pointing at the monolith for identity (URL and user JWKS), at the job-service
  container for postings, and at `StubLlm`; `ObservedContainers` collects its spans as it does
  job-service's. `StubLlm` gains `hang()`. CI builds the image before the suite, and the backend
  workflow's path filters add `services/matching-service/**` (`backend-ci-cd.yaml:6-19,51`).
- **The tests that stub matching's clients move into the service's module.**
  `internal/ProfileDirectoryUnavailableIT` (7 tests, among them `aUserIdentityDoesNotKnowIsA422`,
  the only test of Day 41's existence call) and `internal/PostingShortlistUnavailableIT` (3)
  point the monolith's own clients at `StubUpstream`; they go to the service's module, on a test
  base of its own (Postgres, `StubUpstream`, a stub model, a test key), as job-service's
  `SavedJobCountsUnavailableTest` did on Day 17. `matching/LlmCallObservedIT` (2) goes the same
  way: it reads the metric and span in-process.
- **The tests that reach top-matches over HTTP stay in the harness.**
  `matching/TopMatchesTrustTheSubjectIT` stays unedited: it mints a token and asks through
  `ApiClient`, which reaches the container. `matching/InternalCallsObservedIT` splits:
  `/internal/postings/batch` (from `/api/saved-jobs`) stays the monolith's; its three top-matches
  entries move to the service's module, as a test of the service's own client metrics and spans.
- **Gateway**: `gateway.matching-service-url` (`MATCHING_SERVICE_URL`), defaulting to the
  backend's URL, and a `matching-service` route for `/api/jobs/top-matches` ahead of the backend
  route.
- **Compose**: `matching-service`, unpublished, with `DB_MATCHING_*`, `LLM_*`,
  `INTERNAL_IDENTITY_URL`, `INTERNAL_JOBS_URL` and the trust entries; the gateway's
  `MATCHING_SERVICE_URL` is the switch. `LLM_API_KEY=` goes into the root `.env.example`.
- **The monolith lets go**, and removing `backend/matching` reaches further than the module:
  - `backend/pom.xml:28`, `backend/app/pom.xml:35`, `backend/Dockerfile:21`;
  - `Migrations.java:31,36` (the matching datasource and its migrate call), and
    `app.datasource.matching` in `application.yaml:52-55` and `application-prod.yaml:35-38`;
  - compose's backend `DB_MATCHING_*` (`docker-compose.yml:55-56`) and `backend/.env.example:16-17`;
  - the tests that count modules: `ModuleConnectionsIT`, `ModuleMigrationsIT`,
    `BackendApplicationTests`, `JobsDatabaseIT`; and `PostgresContainer.MODULE_SCHEMAS` splits
    into what the harness creates (still `matching`) and what the monolith connects as (no
    longer);
  - `app.llm.*`, `app.internal.identity-url` and `LLM_*` out of the backend's config;
    `ModuleBoundariesTest`'s matching rules out; the docs.

## Out of scope
- NoSQL — Day 22. Deleting `JobMatchScoreCleanup`, `SchedulingConfig`, the table and the
  service's datasource — Day 23; Day 24's "no datasource" criterion then only holds. Their
  specs are corrected in their own spec-change PRs.
- A profile cache, and retry — Day 24. Caching the existence answer — Day 24 decides it with
  Day 39's reason against.

## Tracks

| Track | Owner | Work | Estimate |
|---|---|---|---|
| 0 | | `StubLlm.hang()`; criteria 5, 6 and 7's tests on today's code | 200–300 |
| D | | Gateway: `matching-service-url` and the route, defaulting to the backend; its tests | 100–200 |
| A1 | | Skeleton: pom, application, `application.yaml` (required URLs, the 15 s read), tracing, actuator; the user-token chain with `TokenSubject`; the `RestClient` rule | 300–380 |
| A2 | | Dockerfile and `matching-service-ci-cd.yaml` | 100–150 |
| B1 | | Service identity: key, minter, key set, `InternalCallers`, the `/internal/**` chain; tests | 300–380 |
| B2 | | The `shared` copies and the breaker config | 250–350 |
| B3 | | The service's test base: Postgres, `StubUpstream`, stub model, test key | 200–300 |
| C1 | | Trust lists (monolith, job-service, harness); compose adds matching-service unrouted | 150–250 |
| C2 | | The harness container, `ObservedContainers`; CI builds the image, path filters; a harness self-test | 250–380 |
| E1 | | The move and the switch: `git mv` of matching's code, `db/matching`, `SchedulingConfig` and the stubbed tests; the build fallout above; the `Services` entry | 350–400 |
| E2 | | Compose switch: `MATCHING_SERVICE_URL`, the LLM key to matching-service only, root `.env.example` | 50–100 |
| E3 | | The bulkhead and criterion 3's test | 150–250 |
| F | | Dead config (`app.llm.*`, `identity-url`, `LLM_*`), boundaries rules, job-service's `RestClient` rule, docs | 150–300 |

In order: 0, D, A1, A2, B1, B2, B3, C1, C2, E1, E2, E3, F. D comes early because the gateway
harness cannot route to matching-service before it, and its default changes nothing. Criterion 3
is red today and `main` is protected, so its test lands with the bulkhead (E3), not in Track 0.
E1 is a move, so `git diff --numstat` counts renames by their edits; if it still passes 400, the
database tests and `MODULE_SCHEMAS` split off into an E1b merged straight after, and the PR says
so. Between E1 and E2, compose still routes top-matches to the backend, which no longer serves
it; E2 follows E1 directly.

## Acceptance criteria
- [ ] **new** — in the harness, `/api/jobs/top-matches` is served by the matching-service
      container: `ServiceRoutingIT` names it as the owner, and a request reaches it. Red today:
      `Services.owner("/api/jobs/top-matches")` is empty (the monolith).
- [ ] **hold** — `MatchTopMatchesIT`, `MatchRankingIT`, `MatchScoreCacheIT`,
      `SessionWithoutAUserIT` and `JobRoutesIT` pass unedited, directly and through the gateway
      (`-Dharness.gateway=true`), with top-matches in the container. Broken on purpose: <Track
      E1: the container's jobs URL at a closed port; what failed>.
- [ ] **new** — with `StubLlm` hanging and 10 top-matches requests in flight, an 11th answers
      within 1 s with `aiScored` false on every row. Red today: the auditor's run at f776017
      took 20.2 s. The test lands in E3 with the bulkhead.
- [ ] **hold** — a user whose `sub` identity does not know gets 422 from top-matches, and the
      existence call was made: `aUserIdentityDoesNotKnowIsA422` passes, in the service's module
      after E1. Broken on purpose: <Track E1: `UserExistenceClient` answering true for every id;
      what it reported>.
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
- [ ] **hold** — `TopMatchesTrustTheSubjectIT` passes unedited against the container. Broken on
      purpose: <Track E1: the service's token check leaving the details unset; what it
      reported>.
- [ ] **new** — `git grep -n "LLM_" -- backend/app/src/main backend/.env.example
      services/api-gateway/src/main services/job-service/src/main` finds nothing. Red today:
      finds 13, 6 in `backend/.env.example` and 7 in the backend's `application.yaml`. The
      harness (`MatchingTest`, `StubLlm`) and the docs keep the name; they configure and
      describe matching-service.
- [ ] **new** — `docker compose config --format json` lists `matching-service` with no `ports`
      and `LLM_API_KEY` in its environment, and no other service has `LLM_API_KEY`. Red today:
      no `matching-service`, and the check below exits with a `KeyError`.
- [ ] **new** — `test ! -d backend/matching` succeeds. Red today: the module exists.

## Verify
```bash
docker build -t jobmatch-job-service:harness services/job-service
docker build -t jobmatch-matching-service:harness services/matching-service
docker build -t jobmatch-api-gateway:harness services/api-gateway
rm -rf backend/*/target/surefire-reports backend/*/target/failsafe-reports
(cd backend && ./mvnw clean verify && ./mvnw -B checkstyle:check \
  && ./mvnw -B verify -pl app -am -Dharness.gateway=true)   # then read the reports
backend/mvnw -B -f services/matching-service/pom.xml verify checkstyle:check
backend/mvnw -B -f services/job-service/pom.xml verify checkstyle:check
git grep -n "LLM_" -- backend/app/src/main backend/.env.example services/api-gateway/src/main services/job-service/src/main  # nothing
docker compose -p day21check --env-file .env.example config --format json > compose.json
python -c "import json,sys; s=json.load(open('compose.json'))['services']; m=s['matching-service']; others=[k for k,v in s.items() if k!='matching-service' and 'LLM_API_KEY' in (v.get('environment') or {})]; sys.exit(0 if 'ports' not in m and 'LLM_API_KEY' in m.get('environment',{}) and not others else 1)" && echo ok
test ! -d backend/matching && echo gone
```

## Notes
- **What the draft promised and the code had already delivered:** `/api/jobs` left the monolith on
  Day 17, and the monolith runs on virtual threads, so a hung model has not delayed job search
  since then. The latency criterion is a hold for that reason; the new behaviour this day adds
  is the bulkhead and the key's move.
- Hand-offs this day takes: the service's own token check setting `TokenSubject` and the
  existence test (Day 41), the harness entry (Day 40), the `RestClient` rule (Day 38),
  `X-User-Id` (Days 15, 17 and 39: not read). What it leaves: the table, `SchedulingConfig` and
  the datasource (Day 23), the profile cache and retry (Day 24).
- **Audit, spec-change PR.** The spec-auditor ran every check on f776017: `clean verify` 426
  tests, 0 failures, 1 skipped; checkstyle 0; criteria 1, 3, 9, 10 and 11 fail as the spec says
  (criterion 3's 11th request took 20.2 s), and criteria 5 and 6 held. 14 findings, all applied
  here: 6 blocking (the existence test, what removing `backend/matching` breaks, the test list,
  Track 0's red test, a Verify that could not fail, the `TokenSubject` hand-off), 6 to fix (the
  `RestClient` rule, the 34 s worst case, track order, CI and trust lists, required URLs, the
  estimate) and 2 notes (who owns scheduling and the datasource; `.env.example`).
- Expected PRs: 15 is the 13 tracks, this spec change and the closing PR. Day 17 estimated 10
  and took 14 track PRs, 18 in all (`day-17-...md:378`); this day has a user, a model and a
  schema that job-service had not.
- **Defect** — found: auditor · cause: spec · the draft dropped Day 41's only test of the
  existence call with the classes that stub matching's clients, named none of what removing
  `backend/matching` breaks (datasource, migrations, config, compose, POMs, four database tests,
  `MODULE_SCHEMAS`), missed `TopMatchesTrustTheSubjectIT` and the `InternalCallsObservedIT`
  split, put a red test in Track 0 on a protected `main`, had Verify checks that printed null or
  checked nothing, and left out the `TokenSubject` hand-off. Rewritten in this PR.
- **Defect** — found: auditor · cause: spec · the timeouts were called "unchanged" and within the
  gateway's 30 s, but Day 41's two extra internal calls made the worst case 34 s. The model's
  read goes to 15 s in the service. Fixed in this PR.
- **Defect** — found: auditor · cause: spec · the draft (at bb6d380) had no `new`/`hold` tags,
  kept `ProfileDirectory` in-process, measured a path Day 17 had moved, and named no `shared`
  copies, key, Flyway, scheduling or harness entry. Rewritten in #200, with Day 41 split out of
  Day 24.
