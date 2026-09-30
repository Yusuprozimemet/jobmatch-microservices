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
    does today). From Track E1a the monolith stops applying it; in the harness the container
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
  **Two places in `support/` route by service, and both need matching-service**, or runs keep
  sending top-matches to the monolith after it stops serving it: `Services.url` defaults every
  service but job-service to the monolith (`Services.java:40-48`), so matching-service's default
  becomes its container; and `Gateway` starts the gateway with `BACKEND_URL` and
  `JOB_SERVICE_URL` only (`Gateway.java:65-66`), so it adds `MATCHING_SERVICE_URL` from the same
  route table, or the gateway falls back to the backend's URL.
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
| B3a | | The service's test base, first half: Postgres | 200 |
| B3b | | The service's test base, second half: `StubUpstream` and the stub model, with self-tests (the test key came with B1) | 200–300 |
| C1 | | Trust lists (monolith, job-service, harness); compose adds matching-service unrouted | 150–250 |
| C2 | | The harness container, `ObservedContainers`; CI builds the image, path filters; a harness self-test | 250–380 |
| E1a | | The move and the switch: `git mv` of matching's code, `db/matching` and `SchedulingConfig`; the service's pom and `application.yaml` for them; the build fallout above, the database tests and `MODULE_SCHEMAS` among it; the `Services` entry and its container default; `Gateway`'s `MATCHING_SERVICE_URL`; `ProfileDirectoryUnavailableIT` and `PostingShortlistUnavailableIT` on the service's test base | 350–450 |
| E1b | | `LlmCallObservedIT` and the top-matches half of `InternalCallsObservedIT` on the service's test base | 150–250 |
| E2 | | Compose switch: `MATCHING_SERVICE_URL`, the LLM key to matching-service only, root `.env.example` | 50–100 |
| E3 | | The bulkhead and criterion 3's test | 150–250 |
| F | | Dead config (`app.llm.*`, `identity-url`, `LLM_*`), boundaries rules, job-service's `RestClient` rule, docs | 150–300 |

In order: 0, D, A1, A2, B1, B2, B3a, B3b, C1, C2, E1a, E1b, E2, E3, F. D comes early because
the gateway harness cannot route to matching-service before it, and its default changes nothing. Criterion 3
is red today and `main` is protected, so its test lands with the bulkhead (E3), not in Track 0.
B3b goes before E1a, which needs its stubs; C1 and C2 need neither and have landed. E1a cannot be
split further and stay green: once matching's code leaves, the database tests fail and the harness
must route top-matches to the container, in the same PR. `LlmCallObservedIT` and the top-matches
entries of `InternalCallsObservedIT` read metrics and spans in-process, so E1a deletes them from
the monolith and E1b, merged straight after, brings them back in the service; E1a's PR says so.
If E1a passes 400 it carries an `Oversized:` line with the reason. Between E1a and E2, compose
still routes top-matches to the backend, which no longer serves it; E2 follows E1b directly.

## Acceptance criteria
- [x] **new** — in the harness, `/api/jobs/top-matches` is served by the matching-service
      container: `ServiceRoutingIT` names it as the owner, and a request reaches it. Red today:
      `Services.owner("/api/jobs/top-matches")` is empty (the monolith).
      #224: `support/ServiceRoutingIT.topMatchesReachesTheMatchingService`, with a recording stub
      of its own for matching-service; top-matches reaches it and not job-service's stub. #224
      broke criteria 2, 4 and 8 but not this one, so it was first seen red at the close:
      on e596a1e, with matching-service's entry removed from `Services`,
      `topMatchesReachesTheMatchingService` failed, expected `Optional[matching-service]` but
      was `Optional.empty`; the other three passed. Reverted.
- [x] **hold** — `MatchTopMatchesIT`, `MatchRankingIT`, `MatchScoreCacheIT`,
      `SessionWithoutAUserIT` and `JobRoutesIT` pass unedited, directly and through the gateway
      (`-Dharness.gateway=true`), with top-matches in the container. Broken on purpose in Track
      E1a (#224): the container's jobs URL at a closed port (port 1 on the host), and 19 of 31
      failed: `MatchRankingIT` 4 and 3 errors, `MatchScoreCacheIT` 6,
      `MatchTopMatchesIT` 6 (e.g. `sendsTheProfileSkillsAndTheShortlistToTheModel`, "Expecting
      actual not to be null"). `JobRoutesIT` and `SessionWithoutAUserIT` stayed green: they stop
      at 401 or 422, before the shortlist. `contract/` is unchanged since f776017.
- [x] **new** — with `StubLlm` hanging and 10 top-matches requests in flight, an 11th answers
      within 1 s with `aiScored` false on every row. Red today: the auditor's run at f776017
      took 20.2 s. The test lands in E3 with the bulkhead.
      #227: `HungModelIT` in the harness, `BulkheadHungModelTest` and `BulkheadConfigTest` in the
      service's module. With `max-concurrent-calls: 11`, the eleventh call took 15.0 s against
      1 s, and `BulkheadConfigTest` expected 10 but was 11.
- [x] **hold** — a user whose `sub` identity does not know gets 422 from top-matches, and the
      existence call was made: `aUserIdentityDoesNotKnowIsA422` passes, in the service's module
      after E1a. Broken on purpose in Track E1a (#224): `UserExistenceClient` answering true for
      every id, and `aUserIdentityDoesNotKnowIsA422` failed at its 422 assertion, and
      `anUnreachableIdentityIsA503` with it.
- [x] **hold** — with `StubLlm` hanging and 10 top-matches requests in flight, `GET /api/jobs`
      (job-service) and `GET /api/profile` (the monolith) answer within 1 s. Isolated since Day
      17 for job search and by virtual threads for the rest; this keeps it so. Broken on
      purpose: the model call and the profile read on one shared ten-thread executor (Track 0,
      #209): the ten hung calls fill it and `GET /api/profile` took 19.7 s against 1 s.
      #209: `HungModelIT`, seen red with that break, direct and, since #209, in CI's gateway
      run.
- [x] **hold** — top-matches answers within 30 s with `StubLlm` hanging, the gateway's read.
      Broken on purpose in Track 0 (#209): the model's read timeout at 40 s, and `HungModelIT`
      failed: top-matches took 42.4 s, limit 30 s.
- [x] **hold** — a request sent to the gateway with a `traceparent` reaches `StubLlm` and job-
      service's shortlist route with the same trace id. Broken on purpose in Track 0 (#209): the
      W3C propagator bean removed, and `TopMatchesTracedIT` failed: the model received no
      `traceparent`.
      The test reads what `StubLlm` recorded and job-service's own log line, not local spans,
      so it passed unedited after E1a moved matching out.
- [x] **hold** — `TopMatchesTrustTheSubjectIT` passes unedited against the container. Broken on
      purpose in Track E1a (#224): `UserTokens.subjectPrincipal` without
      `setDetails(new TokenSubject(...))`. It failed, expected 200 but was **401**, not the 422
      the spec's reasoning implied: without the details the service refuses the request.
      **Not met as written: the test was edited**, one word, `direct()` to `anonymous()`
      (#224, the maintainer's call). `direct()` is always the monolith (`IntegrationTest:125`),
      which answered 404 once top-matches left it; see the Notes.
- [x] **new** — `git grep -n "LLM_" -- backend/app/src/main backend/.env.example
      services/api-gateway/src/main services/job-service/src/main` finds nothing. Red today:
      finds 13, 6 in `backend/.env.example` and 7 in the backend's `application.yaml`. The
      harness (`MatchingTest`, `StubLlm`) and the docs keep the name; they configure and
      describe matching-service.
      #228 (with #224 and #226). At the close, on e596a1e: nothing, exit 1. Seen red by the
      spec-auditor on f776017: 13 lines.
- [x] **new** — `docker compose config --format json` lists `matching-service` with no `ports`
      and `LLM_API_KEY` in its environment, and no other service has `LLM_API_KEY`. Red today:
      no `matching-service`, and the check below exits with a `KeyError`.
      #226. At the close, on e596a1e: `ok`. Seen red in #226: the check exited 1 on `main` (no
      `LLM_API_KEY` on matching-service) and 1 with the key also given to the backend (others:
      `['backend']`). End to end, the gateway without `MATCHING_SERVICE_URL` answered 404 from the
      backend.
- [x] **new** — `test ! -d backend/matching` succeeds. Red today: the module exists.
      #224, by `git mv`. At the close, on e596a1e: `gone`. Seen red on every commit before #224.

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
- **Spec change after Track D (#210): the harness's gateway was given to no track.** Track D
  gave the gateway `MATCHING_SERVICE_URL`, but the harness's `Gateway` never passes it, and
  `Services.url` sends matching-service to the monolith by default. The maintainer found the
  first in D's review; the second turned up checking it. Both go to E1, not C2: before E1 the
  container has no matching code to serve top-matches, and after it the monolith has none.
  Criterion 2's gateway run fails without them: top-matches reaches the monolith, which no
  longer serves it.
- **Spec change after Track C2 (#221): B3 half-landed, and E1 could not be one PR.** #219 was
  B3a and named a B3b (`StubUpstream`, the stub model) that no PR took; the dashboard counted B3
  as merged and reported E1 next, which needs those stubs. The table now lists B3a and B3b. E1
  estimated 350–400, but the stubbed tests extend the monolith's `MatchingTest` (`aUser()`,
  `posting(...)`, `authenticatedAs(...)`), so they are rewritten, not renamed, and `numstat`
  counts them in full; the "E1b merged straight after" could not hold the database tests either,
  since `main` is protected and they fail the moment the code leaves. E1 is now E1a and E1b, as
  the tracks section says. Found starting E1, before any code.
- **Defect** — found: spec-change PR · cause: spec · the E1 estimate counted the stubbed
  tests as a move, and the fallback split named a part that could not merge green. Fixed in this
  PR.
- Expected PRs: 15 is the 13 tracks, this spec change and the closing PR. Day 17 estimated 10
  and took 14 track PRs, 18 in all (`day-17-...md:378`); this day has a user, a model and a
  schema that job-service had not.
- **Defect** — found: break on purpose · cause: spec · criterion 5's break named a one-thread
  executor shared with the profile read. One thread cannot hold ten model calls, so the test
  failed at its setup ("Expected 10 calls held by the model, found 1") and the 1 s check never
  ran. At ten threads the break reaches the check (19.7 s). Both results are in #209; the
  criterion now names the ten-thread break.
- **Beyond the plan (Track 0, #209):** CI's gateway run now includes `HungModelIT` and
  `TopMatchesTracedIT`, since criteria 6 and 7 are about a request sent to the gateway and CI
  had run them only direct.
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
- **Four departures from the spec, each recorded in its PR; the first three were the maintainer's call:**
  - **Criterion 8's test was edited** (#224), one word. `TopMatchesTrustTheSubjectIT` asked
    through `direct()`, which is always the monolith (`IntegrationTest:125`): once top-matches
    left, it got 404. The spec's premise, "`ApiClient`, which reaches the container", was
    wrong. Changing `direct()` itself would have moved about 40 calls that rely on it
    (`JobsLeftTheMonolithIT` among them), so the test asks through `anonymous()`, which routes
    by `Services`, direct or through the gateway. Not `contract/`, so not a stop-and-ask.
  - **User tokens: cookie only** (#213). "From the cookie and then the header, as the monolith
    does" was false: the monolith (`cookieResolver`) and the gateway (`verifiedCookie`) read
    only the cookie, and the gateway forwards it. `UserTokenTest.theAuthorizationHeaderIsNotRead`
    pins it (a Bearer token gets 401; with the header read, 200).
  - **No `InternalCallers` and no `/internal/**` chain** (#215, #216). matching-service serves
    no internal route, and copying job-service's would have added a required
    `BACKEND_KEY_SET_URL` that guards nothing. The user chain answers 401 to any path without a
    user token.
  - **`ObservedContainers` does not collect spans** (#221): it records database URLs. The
    container logs `[traceId,spanId]` per request and has its own `logs()`, as `JobService`
    does.
- **Four tracks split for the 400-line gate,** A1 (#212, #213), B1 (#215, #216), B2 (#217,
  #218) and E1 (#224, #225), and B3 (#219, #223) so each half reviewed on its own; that B3b was
  untaken was only found starting E1 (#222). **E1a used an `Oversized:` line** at 950 changed lines by `gh` (945 in the
  PR), as the spec allowed: the first since #3, before Day 3. Its ~460 deleted lines are the
  monolith's copies; nothing in it could land separately and keep `main` green.
- **Defect** — found: review · cause: implementation · seven tracks' first drafts, all by the
  implementer on Haiku: a `StubLlm` whose hang survived `reset()` and a missing half of
  criterion 7 reported as "no departures" (#209); a dead loop and a stale comment (#210); tabs,
  missing tests and a weak Prometheus check (#212); the principal set to the `sub` instead of
  the email and a `@Profile("test")` route (#213); a line parser that broke on blank lines and a
  non-atomic `volatile` counter, introduced squeezing under 400 lines (#223); `support/
  MatchingService` rewritten without `LLM_BASE_URL`/`LLM_API_KEY`, so every model assertion
  would have passed testing nothing, and database cases removed instead of made deterministic
  (#224); a service test named `*IT`, which the service module never runs, reported as passing
  (#227, found because it had no surefire report).
- **Defect** — found: self · cause: process · three reviewer mistakes in the main session:
  `git checkout --` after `git add -N` emptied `ServiceTokens.java` (#215); a break's `sed`
  stripped a comma and corrupted the SQL (#219); `git checkout docker-compose.yml`, reverting
  one break, discarded the whole track (#226). Each was redone before the PR. And #225 cut a
  stub profile to two skills, below the five matching needs, and got 422.
- **Defect** — found: break on purpose · cause: spec · two breaks reported other than the spec
  implied: criterion 8's unset details gave 401, not 422 (#224); C1's first break renamed only
  the trust entry's name, not its key-set URL, and job-service failed to start (#220).
- **Defect** — found: close · cause: spec · criterion 1 had a test but no break in any track;
  it was first seen red at the close. The close's first try at that break left one parenthesis
  too many: the class did not compile and all four tests errored ("Unresolved compilation
  problem"), which proves nothing. Redone with `clean`; that run is the one recorded above.
- **The close's Verify, on e596a1e:** the three harness images rebuilt; `clean verify` 425
  tests, 0 failures, 0 errors, 1 skipped, counted from the reports after deleting them;
  `checkstyle:check` 0 violations; criteria 9, 10 and 11's commands as recorded above. The
  gateway run and the two service modules were not rerun at the close: #228 ran them on the
  same code (425 through the gateway, only `ServiceJwksIT` red; job-service 55), and #227
  matching-service (63), and nothing but #229's tooling merged since.
- **Defect** — found: dashboard · cause: tooling · `spec-drift.py` lost tests that moved into
  a service under a new name; fixed in #229, outside the tracks.
- **Environment:** "remaining connection slots are reserved for roles with the SUPERUSER
  attribute" from the harness Postgres in local runs of #215, #216, #217, #219 and #221; each
  passed on rerun. The harness now runs a third container on the same Postgres.
- **Found, not owned:** `ServiceJwksIT` fails through the gateway (3 failures, 1 error): the
  gateway publishes `/.well-known/jwks.json` but not the service key set. It fails the same on
  695adf9, before Track F, and CI's gateway run leaves the class out. No day owns it.
- **The estimate:** 15 PRs expected (13 tracks, a spec change, the close). Took 18 track PRs
  (#209, #210, #212–#221, #223–#228), 3 spec changes (#206, #211, #222) and this close, 22,
  besides #200 (the seam-first rewrite, before Day 41) and #229 (tooling). The tracks came to
  5,252 changed lines against the table's 2,900–4,240; E1a's 950 is most of the overrun. Day
  17, the other extraction, took 14 track PRs.
- **Hand-offs this day leaves:**
  - **Day 22:** matching-service's NoSQL store.
  - **Day 23:** `JobMatchScoreCleanup`, `SchedulingConfig`, the `matching` table, the
    service's datasource and `db/matching`, and the harness's `matching` role and schema
    (`PostgresContainer` still creates them for the container). Day 24's "no datasource"
    criterion holds only after.
  - **Day 24:** the profile cache, retry, and whether to cache the existence answer (Day 39's
    reason against).
  - **Any day that touches the gateway's key sets:** `ServiceJwksIT` through the gateway.
