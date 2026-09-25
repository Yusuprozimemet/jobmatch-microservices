# Day 40 — The suite runs across services, and compose runs them all

**Phase:** 3 · **Depends on:** Day 39 · **Expected PRs:** 4

The last of the platform step's three days (38–40), just before Phase 3's seam-first days
(18, 19, 17, 20). Written in full on Day 40 from the outline in #118, against `main` at 54e1b10,
with the maintainer's three choices below, and corrected from the spec-auditor's read before any
track.

## Goal
The harness can send a service's paths to a URL outside the monolith, directly and through the
gateway, with the Day 1–4 suite unedited. The gateway routes job search to an upstream of its
own. No `contract/` class reads the monolith's own port any more. So Day 17's extraction of
job-service is a change of URL: in the gateway, in the harness and in compose.

## In scope
- **The maintainer's choices, made on Day 40:**
  1. An extracted service runs in the harness **as a container**, from an image built before the
     run, as the gateway does (`support/Gateway`). Not a second Spring context in the test JVM:
     that would put job-service's artifact on `app`'s test classpath, coupling the two builds
     Day 17 separates.
  2. `contract/ObservabilityIT` **moves out of `contract/` today**, into the monolith's own tests
     (`observability/`). It tests one deployable's actuator, not the API, and it cannot survive
     Day 17 unedited: two of its tests send `/api/jobs`, and one expects `uri="/api/jobs"` on the
     monolith's management port (`@LocalManagementPort`). This is an approved edit of
     `contract/`, made before any day needs it, so "unedited" stays exact from Day 17 on.
  3. **The gateway gets a job-service route today**, with its upstream defaulting to the backend.
- **The gateway's job route.**
  - `Routes.java` routes `/api/jobs`, `/api/jobs/filters` and `/api/jobs/{postingId}`
    (`JobController`) to `gateway.job-service-url`. That is `${JOB_SERVICE_URL}` when it is set
    and `${gateway.backend-url}` when it is not, so nothing changes until Day 17 sets it.
  - `/api/jobs/top-matches` is matching's (`JobMatchController`, the same `@RequestMapping`) and
    stays on the backend route. The match has to exclude it, because `/api/jobs/{postingId}`
    would take it otherwise:
    `path("/api/jobs/{postingId}").and(path("/api/jobs/top-matches").negate())`.
  - **Order matters.** The job route is composed ahead of `/api/**` inside `Routes.backend`
    (credentials, then jobs, then the rest). A `RouterFunction` bean of its own, with no order,
    comes after `/api/**`, and every job path reaches the backend (tried by the auditor).
  - Same filters as the backend route: the verified token's `X-User-Id` put in place of the
    client's, and 502/504 when the upstream fails (`Routes.backendFailed`).
  - Nothing else moves: `/api/**` and `/.well-known/jwks.json` stay on the backend route.
  - A side effect on the gateway's metric: the three job paths get series of their own
    (`uri="/api/jobs"`, `"/api/jobs/filters"`, `"/api/jobs/{postingId}"`); the rest of the API
    stays one `uri="/api/**"` series. `ManagementPortTest.aRoutedCallIsCountedForPrometheus`
    sends `/api/jobs/filters` and asserts `/api/**`, so Track 0 moves its request to
    `/api/docs/openapi.yaml` (`permitAll`, on `/api/**`), and it holds through Track A.
  - `backend/docs/configuration.md`'s gateway settings table gets `JOB_SERVICE_URL`.
- **The harness routes by path.** The only way `contract/` reaches the application is through
  `IntegrationTest`'s `anonymous()` and `authenticatedAs()`, which build an `ApiClient` on
  `Gateway.baseUrl(port)`. So all of this is `support/`:
  - A table in `support/` that says which paths belong to which service, and each service's base
    URL. Job-service's entry has the job paths above, `top-matches` excluded. Its URL defaults to
    the monolith.
  - In the direct run, the clients `anonymous()` and `authenticatedAs()` build send each request
    to the base URL of the service that owns its path. `direct()` and `ApiClient.onPort` do not
    route: `direct()` is documented as always the application itself, and `ObservabilityIT` and
    `LlmCallObservedIT` use `onPort` for the management port. The cookie jar belongs to the
    client, not the host, so a login made on the monolith is sent to the other service too.
  - In the gateway run, the harness starts the gateway with `JOB_SERVICE_URL` set to the
    entry's URL, seen from inside the container (`host.testcontainers.internal`, with
    `Testcontainers.exposeHostPorts` first). The gateway, not the harness, does the routing.
  - A test class can point a service's paths at a URL of its own. The rest of the run keeps the
    defaults.
  - A self-test in `support/`, `ServiceRoutingIT`, points job-service's paths at a recording stub
    in the test JVM, in both runs, and shows which requests reached it.
  - Starting job-service's image is **Day 17's**, following `Gateway`'s pattern: an image built
    beforehand, never pulled, and a failure that prints the build command. There is no image to
    start until then. Day 17's Notes get this, with what the container forces on it.
- **The other two bound `contract/` classes stay bound, on purpose.**
  - `AuthGoogleSignInIT` (10 tests: `@Import` of a stub client registration and
    `@TestPropertySource`) tests Google sign-in, which is identity's.
  - `ObservabilityLoggingIT` (5, `@TestPropertySource`): four of its tests log from the test JVM
    and test the logging configuration; one calls `forgot-password`, identity's. Both halves
    stay in the monolith until Day 28.
  - Neither needs an edit before then. Day 28 decides what they bind to, and its Notes get this.
- **`ObservabilityIT`'s move** (Track C), with `git mv` so the history follows it:
  - both `/api/jobs` requests (`exposesRequestTimingsForPrometheusToScrape` and
    `servesTrafficWithNoCollectorConfigured`) become `/.well-known/jwks.json`, which the monolith
    keeps and records as `uri="/.well-known/jwks.json"` (tried by the auditor);
  - the metric test asserts that the series' count **rises across its own request**, not that
    the series exists. Other classes in the same cached context (`JwksIT`, `AccessTokenIT`,
    `ServiceJwksIT`), and the gateway fetching the key set, record that series first. So an
    existence check passes without the request, as today's `/api/jobs` one would in a full run.
  - its Javadoc's stale lines go, which Day 38 left because `contract/` could not be edited: the
    LLM span "checked by hand" (`LlmCallObservedIT` checks it) and the JDBC spans (dropped on
    Day 38).
- **Compose.**
  - The default `up` already runs every service there is: `docker compose config --services`
    with no profile lists `db`, `jwt-key`, `backend`, `api-gateway` and `frontend`. Each
    extracted service joins it on its own day (17, 21, 25, 28).
  - Missing today is what Day 38 recorded (#123, #124): the gateway can answer 500 for a few
    hundred milliseconds after its port opens. A healthcheck on `/actuator/health/readiness`
    (management port 9090, inside the network), and
    `depends_on: api-gateway: condition: service_healthy` for the frontend, make the frontend and
    `up --wait` wait for readiness. They do not close the window for a request sent straight to
    the published 8080 in it.
  - The runtime image, `eclipse-temurin:25-jre`, has no `curl`, `wget` or `nc`, but has `bash`,
    so the check uses bash's `/dev/tcp` and adds nothing to the image. It works as the image's
    uid 1000.
  - With Docker's default 30 s `interval`, `up --wait` takes at least 30 s: the healthcheck sets
    a short `interval`, a `start_period`, and a `start_interval` (the auditor's 2 s, 30 s, 1 s
    gave `(healthy)` in about 8 s).
  - `backend/docs/configuration.md` is corrected in two places: line 72 ("start order only,
    **not** readiness") and the paragraph at line 90 ("There is no actuator, no `/health`…",
    already false about actuator, and the frontend now waits for the gateway's readiness).
- **The query-count tests' expiry notes are right, so they stay as they are.**
  - `StatementCounter` reads `pg_stat_statements` for the current database only (`dbid`). So it
    counts a statement from any process, container or JVM, as long as that process uses the test
    database.
  - `SavedJobHydrationQueriesIT` ("Day 20") stops holding when the mart moves to `jobs_db`.
  - `JobSavedCountQueriesIT` ("Day 25") stops holding when `saved_jobs` moves to `apps_db`.
  - Neither expires when job search moves on Day 17, because job-service shares the test
    database until Day 20 and asks the monolith for saved counts. Days 17 and 20's Notes get
    this.

## Out of scope
- Extracting anything, and job-service's image and the harness's start of it: Day 17.
- Trusting job-service's service tokens from compose's environment, where a hyphenated map key
  cannot be set: Day 17 (in its Notes from Day 39).
- A second database in compose (`jobs_db`) and the harness's seeding of it: Day 20.
- A per-endpoint request metric at the gateway: Day 37. From Track A the job paths have series of
  their own; the rest of the API is still one `uri="/api/**"` series. Day 37's Notes get this.
- A backend healthcheck in compose (`configuration.md`: "Nothing checks that the backend is
  actually up"): the gateway's is the one a recorded failure asks for.

## Tracks

| Track | Owner | Work |
|---|---|---|
| 0 | | Holds first, in the gateway's tests: `RoutesTest`, with no job-service URL set, sends every job path and `top-matches` (with a valid token) to the backend; `ManagementPortTest`'s routed call moves to `/api/docs/openapi.yaml` |
| A | | The gateway's job route, ahead of `/api/**`, and `gateway.job-service-url`; compose's gateway healthcheck and the frontend waiting on it; `configuration.md`'s frontend row and line-90 paragraph |
| B | | The harness's route table, routing in `anonymous()`/`authenticatedAs()`'s clients, `Gateway` passing `JOB_SERVICE_URL`, `ServiceRoutingIT` with a recording stub; CI's gateway run names it |
| C | | `ObservabilityIT` out of `contract/` into `observability/`: `/.well-known/jwks.json` for both requests, the count rising, the stale Javadoc; CI's gateway run names it |

Track 0 lands first, then A and B in that order: B's gateway half needs A's image. C stands alone
and may land any time after 0. Track B is about 300–350 lines by the auditor's count. If it passes
400, it splits into B1 (the table, the direct routing and `ServiceRoutingIT`'s direct half) and B2
(the gateway half and CI).

## Acceptance criteria
- [ ] **hold** — With no job-service URL set, the gateway sends every job path to the backend,
      path and query unchanged: `GET /api/jobs?city=Amsterdam&page=2`, `/api/jobs/filters` and
      `/api/jobs/seed-0001`, and `/api/jobs/top-matches` with a valid `access_token` cookie
      (`TestKeys`, with `gateway.jwks-url` set as `SecurityTest` does: without a token the gateway
      answers `top-matches` 401 and nothing reaches any upstream). In `RoutesTest`, Track 0. Green
      today: `/api/**` is one route. Broken on purpose in Track A's PR, since the property only
      exists from there: the job-service URL's default pointed at an address nothing listens on.
      The three job paths answer 502, and `top-matches` still passes.
- [ ] **new** — With `gateway.job-service-url` pointed at a second upstream:
      - `GET /api/jobs?city=Amsterdam&page=2`, `/api/jobs/filters` and `/api/jobs/seed-0001`
        reach it and not the backend, with a verified token's `X-User-Id` and without the one
        the client sent;
      - with a valid token, `/api/jobs/top-matches`, `/api/saved-jobs` and
        `/.well-known/jwks.json` reach the backend;
      - a job path answers 502 when that upstream is down.

      Red today: nothing reads the property, and all of them reach the backend. Broken on
      purpose in Track A's PR, each alone:
      - the `top-matches` exclusion removed, so `top-matches` (with a token) reaches job-service;
      - the job route composed after `/api/**`, so every job path reaches the backend.
- [ ] **new** — In compose, the gateway is `healthy` only once `/actuator/health/readiness`
      answers `UP`, and the frontend starts after that. `docker compose ps api-gateway` shows
      `(healthy)`, and `up --wait` returns once it does. Red today: the service has no
      healthcheck, so `docker inspect -f '{{.State.Health}}'` prints `<nil>`, and `ps` shows
      `Up` alone. Broken on purpose in Track A's PR: the check pointed at port 9091, and
      `up --wait` fails with "container … api-gateway … is unhealthy".
- [ ] **new** — The harness sends a service's paths where its entry says, in both runs.
      With job-service's paths pointed at a recording stub in the test JVM (`ServiceRoutingIT`):
      - `anonymous().get("/api/jobs")`, and `authenticatedAs(user).get("/api/jobs/seed-0001")`,
        reach the stub; the second carries the user's cookie (direct) or the user's
        `X-User-Id` (through the gateway);
      - `/api/jobs/top-matches` and `/api/users/me` reach the monolith, and `/api/users/me`
        answers 200.

      The direct half runs in every `verify`. The gateway half runs with
      `-Dharness.gateway=true`, as `GatewayHarnessIT` does, and CI's gateway run names it. Red
      today: there is no route table, and every request reaches the monolith. Broken on purpose
      in Track B's PR, twice, each alone:
      - the clients ignoring the table, so the direct half goes red;
      - `Gateway` not passing `JOB_SERVICE_URL`, so the gateway half goes red.
- [ ] **hold** — The Day 1–4 suite passes, **unedited**, in both runs, with job paths sent
      through the table to their default, the monolith. At every track, the `contract/` diff
      against 54e1b10, leaving out the one approved move, prints nothing (the Verify's
      `git diff`). Green today. Broken on purpose in Track B's PR: job-service's default URL
      pointed at a port nothing listens on turns the classes that call a job path red
      (`JobSearchIT`, `JobDetailIT`, `JobFiltersIT`, `JobSavedCountIT` among them), and the
      failures are counted and recorded.
- [ ] **new** — No `contract/` class reads the monolith's own port or its `/api/jobs` metric:
      the Verify's `grep` finds nothing. `observability/ObservabilityIT` runs its 8 tests, and
      `git log --follow` shows its history. Its metric test asserts that the
      `uri="/.well-known/jwks.json"` count rises across its own request. CI's gateway run names
      it, because `contract.*IT` no longer matches it. Red today: the grep finds
      `ObservabilityIT.java`. Broken on purpose in Track C's PR: the request before the second
      metric read removed, so the metric test goes red **in the full run**, not only alone.

## Verify
```bash
# From the repository root, in Git Bash.
backend/mvnw -B -f services/api-gateway/pom.xml clean verify checkstyle:check

# Backend direct. Read these reports before the gateway run overwrites them.
(cd backend && rm -rf */target/surefire-reports && ./mvnw -B clean verify && ./mvnw -B checkstyle:check)

# Backend through the gateway, from a fresh reports directory.
docker build -t jobmatch-api-gateway:harness services/api-gateway
(cd backend && rm -rf */target/surefire-reports && ./mvnw -B verify -pl app -am -Dharness.gateway=true \
  -Dtest='nl.hackyourfuture.project.backend.contract.*IT,StaleCookieIT,RefreshIT,GatewayHarnessIT,ServiceRoutingIT,ObservabilityIT' \
  -Dsurefire.failIfNoSpecifiedTests=false)

# contract/ unedited but for the approved move: prints nothing.
C=backend/app/src/test/java/nl/hackyourfuture/project/backend/contract
git diff --stat 54e1b10 -- "$C" ":(exclude)$C/ObservabilityIT.java"
# Nothing in contract/ reads the monolith's port or its /api/jobs metric: prints nothing.
grep -rlE 'LocalManagementPort|uri=\\"/api/jobs' "$C"

# Compose, in a project of its own. Never `down -v` on the maintainer's; stop theirs first,
# the network name (finalproject) is pinned.
docker compose config --services
docker compose -p day40check --env-file .env.example up -d --build --wait
docker compose -p day40check ps api-gateway frontend      # api-gateway (healthy)
docker compose -p day40check --env-file .env.example down -v
```

## Notes
- Written as an outline in #118 from `plan.md`'s course correction, and in full on Day 40.
- **Spec corrected on Day 40, before the work, from the spec-auditor's read** of `main` at
  54e1b10 and the first draft. 13 findings, 4 must-fix, settled by trying:
  - **`top-matches` needs a token.** The gateway answers it 401 without one and forwards
    nothing, so the first draft's `hold` was red today and its break could not show.
  - **The move broke the "prints nothing" diff.** A path-limited diff shows `ObservabilityIT`'s
    move as 124 deletions, so the check excludes that one file.
  - **Track A would turn `ManagementPortTest` red.** Its routed call is a job path, and job paths
    get series of their own. Track 0 moves the call.
  - **Route order.** A job route as a bean of its own came after `/api/**` and caught nothing.
  - The rest: the metric test's existence check passed without its request in a full run; a
    second `/api/jobs` request in `ObservabilityIT`; two `configuration.md` lines; "closes it"
    overclaimed; Docker's 30 s default interval; a Verify whose failed step left the next
    commands in the wrong directory and whose gateway run overwrote the direct reports; Day 17's
    hand-off missing what the container forces; the fixture count; `ObservabilityLoggingIT` is
    mostly logging; Day 38's stale-Javadoc hand-off.
  - **A second read** ran the corrected Verify as written (gateway 33 tests; direct 334, 1
    skipped; through the gateway 203; the diff empty; the grep red), and tried "the count rises"
    300 times each way (0 misses). It found two more: a startup cycle Day 17 inherits (now in its
    Notes) and a second stale `configuration.md` paragraph.
- **The gateway run keeps one gateway per application port** (`Gateway.BY_APPLICATION_PORT`),
  and Spring's context cache gives test classes with the same configuration the same port. A
  self-test that points job paths at a stub must not share a gateway with the rest of the suite,
  or every later job test in that context reaches the stub. Either the self-test gets a context
  of its own, at the cost of one more set of four module pools (CLAUDE.md), or the gateway key
  includes the route table.
- A stub bound to `127.0.0.1` in the test JVM is reached from a gateway container through
  `host.testcontainers.internal`, even with `exposeHostPorts` called after another gateway
  started (tried by the auditor: 200, and the request recorded).
- The mart fixtures are copied, not moved, when job-service gets its own database (Day 20).
  13 contract classes seed postings (`aPosting()`, `seed-*`, or `MatchingTest`), 8 of them for
  paths the monolith keeps, and `TestDatabase.reset()` reseeds the mart before every test.
- The closing PR records Track C in the README's measurement table as the one approved change
  to `contract/`, with its reason.
- From Day 38 (#123, #124): in compose the gateway can answer 500 for a few hundred milliseconds
  after its port opens, before Spring Cloud Gateway's proxy has its header filters. Track A's
  healthcheck makes the frontend and `up --wait` wait it out.
