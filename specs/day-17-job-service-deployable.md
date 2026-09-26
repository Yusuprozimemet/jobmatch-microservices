# Day 17 — job-service becomes its own deployable

**Phase:** 3 · **Depends on:** Day 19 · **Expected PRs:** 10

The third of Phase 3's seam-first days (18, 19, 17, 20). Rewritten on Day 17 from the provisional
draft, against `main` at b219a5f, after two spec-auditor reads, with the maintainer's four choices
below. What the reads found is in the spec-change PR.

## Goal
`jobs` runs as `job-service`, in its own container from its own image and pipeline, on the same
database. The gateway sends job search to it. The monolith calls it over HTTP for postings (Day
19's clients change only their URL) and answers its saved-count calls. The monolith no longer
holds job search. The Day 1–4 suite passes unedited against the container, directly and through
the gateway.

## In scope
- **The maintainer's choices, made on Day 17:**
  1. **`shared` is duplicated, not published.** job-service copies what `jobs` uses, in the same
     packages, so the moved files compile unedited: `PageResponse`, `SavedJobCounts`,
     `PostingLookup`, `PostingShortlist`, `PostingSummary`, `ShortlistedPosting`,
     `InternalClients`, `ServiceToken`, and `GlobalExceptionHandler`'s `ResponseStatusException`
     handler.
     - `MartSkills`, which only `jobs` uses, moves into `...backend.jobs` and leaves `shared`.
     - A published artifact would need a registry, with credentials, in both CI and the image
       build (the gateway's Dockerfile sees only its own folder, `services/api-gateway/Dockerfile:7-11`),
       and would tie the two releases together.
     - The wire records now exist twice. What keeps the copies in step is the tests that cross
       the container: `SavedJobHydrationIT` and `Match*IT` for the posting records, and
       `JobSavedCountIT` for the counts.
  2. **Tested from the backend's harness, one container per run.** job-service's own module keeps
     only tests that need no database: security, key, counts client, breaker. The direct run
     starts 13 contexts; they share one database and one service key, so one container serves
     them all, and its counts calls go through a relay (below).
  3. **Day 03's filters stay as they are.** `experienceLevels` and `employmentTypes` stay
     advertised and ignored, as `JobSearchIT.java:184-195` and `JobFiltersIT.java:31-32` pin.
     Either change would edit `contract/`. It is not a migration question, and no later day owns
     it.
  4. **Trusted issuers are a list, in both services**: `app.internal.trusted-issuers` holds
     `{name, key-set-url}` entries, set as `APP_INTERNAL_TRUSTEDISSUERS_0_NAME` and
     `..._0_KEYSETURL`.
     - A map cannot hold the hyphenated issuer from the environment: Boot 4.1 binds
       `APP_INTERNAL_TRUSTEDISSUERS_JOBMATCH_JOB_SERVICE` as `jobmatch.job.service`.
     - A list set in a higher-priority source replaces the whole list; it does not merge. So
       job-service trusts `jobmatch-backend` through a property of its own,
       `app.internal.backend-key-set-url` (`BACKEND_KEY_SET_URL`).
     - `IntegrationTest` moves `jobmatch-test-caller` to the list in the same PR (C1); otherwise
       `InternalRoutesIT` loses it. From C2 it lists job-service as well.
- **job-service**, at `services/job-service`, built as the gateway was on Day 15:
  - Its own pom, on Spring Boot's parent **4.1.0 as the backend's** (the gateway's is 4.0.8),
    Java 25, and checkstyle as the backend's. Its dependencies arrive with the tracks that need
    them:
    - A1: webmvc, security, `spring-boot-starter-oauth2-resource-server`, actuator, the
      Prometheus registry and `spring-boot-starter-opentelemetry`.
    - B2: `spring-boot-starter-restclient`, `resilience4j-spring-boot4` 2.4.0 and
      `spring-boot-starter-aspectj`, without which `@CircuitBreaker` silently does nothing.
    - D: `swagger-annotations` with an explicit version (Boot does not manage it), the JDBC
      starter and the Postgres driver. Before D there is no `DataSource` bean, and the JDBC
      auto-configuration would stop job-service starting.
  - A Dockerfile and a workflow, `job-service-ci-cd.yaml`, copied from the gateway's: checkstyle,
    verify, and the image to GHCR on `main`.
  - The name `jobmatch-job-service`, used for `spring.application.name`, `OTEL_SERVICE_NAME` and
    the issuer. Port 8080, management 9090, neither published. `JobServiceApplication` scans
    `nl.hackyourfuture.project.backend`.
  - Metrics and tracing as the backend's `management.*`, with `TracingConfig`'s W3C propagator
    copied (`TracingConfig.java:15-22`), so a `traceparent` is read while export is off. No
    OTel agent (Day 05).
  - **Three security chains:**
    - Actuator, on its own port.
    - `/internal/**`, service tokens only. Its copy of `InternalCallers` trusts
      `jobmatch-backend` at `backend-key-set-url`, and whatever the list names (in tests,
      `jobmatch-test-caller`).
    - A public chain that permits `GET /api/jobs`, `/api/jobs/filters`, `/api/jobs/*`,
      `GET /.well-known/service-jwks.json` and `/error`, as the monolith's does
      (`SecurityConfig.java:101,107`). Everything else is `anyRequest().authenticated()` with a
      401 entry point. It reads no cookie: `tokens/StaleCookieIT.java:38-42` expects 200 for
      stale cookies on `/api/jobs`, and `jobs` never reads a user.
      - Not `denyAll()`: that turns a trusted `/internal` 404 or 400 into 403.
      - Not without the key set or `/error`: the monolith could not fetch the key set, and any
        400 or 500 would reach an anonymous caller as 401.
      - This is the access table's third copy, which Day 16 warned of. Track A1's test pins the
        list exactly.
  - **Its own service key** (Day 39):
    - A PEM file at `SERVICE_JWT_PRIVATE_KEY_FILE`, read under identity's `SigningKey` rules:
      never generated, the key id its RFC 7638 thumbprint, no start without it.
    - The reading is copied, because `ServiceSigningKey.java:5` imports identity.
    - Tokens as `ServiceTokens` mints them, with `iss` and `sub` `jobmatch-job-service`.
    - The public half served at `GET /.well-known/service-jwks.json`.
  - **The counts client keeps its breaker.** The `internal` config and the `savedJobCounts`
    instance move to job-service's `application.yaml`, with `app.internal.applications-url`
    (`INTERNAL_APPLICATIONS_URL`) pointing at the monolith.
- **The move (Track D)** is `git mv` of `backend/jobs/src/main/**` into
  `services/job-service/src/main/java`, with packages unchanged, so the gate counts 0 lines for the
  ten files.
  - `MartSkills` changes its package line and two imports.
  - `jobs` leaves the reactor, `app`'s dependencies and `backend/Dockerfile:20`.
  - The monolith's `application.yaml` and `application-prod.yaml` drop the `jobs` datasource,
    the `savedJobCounts` breaker, and `app.internal.applications-url` with its comment
    (`application.yaml:83-84`). Compose drops the backend's `DB_JOBS_*`.
  - `shared/pom.xml:12-22`, which says "Three classes today" and has `MartSkills` "under
    protest", is rewritten.
  - `JobsDatabase` moves unedited: job-service reads `app.datasource.jobs.*` as `jobs_user`.
- **The monolith stops serving job search.** `SecurityConfig.java:110`'s `permitAll` for the three
  GETs goes, so they answer 401 anonymous and 404 logged in. `top-matches` keeps its line.
- **The monolith trusts job-service**, for its calls to `/internal/saved-counts`. Compose names
  `http://job-service:8080/.well-known/service-jwks.json`; the harness names the container's key
  set. This replaces `InternalCallers`' "Day 17 adds job-service" (lines 27 and 49).
- **Compose.** C1 adds job-service unrouted; D routes to it.
  - The service: built from `./services/job-service`, no `ports:`, healthy by readiness on 9090
    through the gateway's bash `/dev/tcp` check (`docker-compose.yml:98`; the image has no curl).
    It depends on `db` and `jwt-key`.
  - Its environment: `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_JOBS_USER`, `DB_JOBS_PASSWORD`,
    `SERVICE_JWT_PRIVATE_KEY_FILE=/run/keys/job-service.pem` (the `jwt-keys` volume,
    read-only), `BACKEND_KEY_SET_URL=http://backend:8080/.well-known/service-jwks.json`,
    `INTERNAL_APPLICATIONS_URL=http://backend:8080`, and tracing as the backend's.
  - `jwt-key` writes a third key, `job-service.pem` (`docker-compose.yml:31`).
  - `observability/prometheus.yml` scrapes `job-service:9090`.
  - In D, the backend sets `INTERNAL_JOBS_URL=http://job-service:8080`. The gateway sets
    `JOB_SERVICE_URL` to the same and waits for job-service to be healthy. The routes exist
    already (`services/api-gateway/src/main/resources/application.yaml:59-61`, Day 40), and
    `RoutesTest.java:93` stays true with the variable unset.
- **The harness: `support/JobService`, modelled on `support/Gateway`** (C2, defaults in D).
  - It starts `jobmatch-job-service:harness`, built beforehand and never pulled; a failure prints
    the `docker build` command. One container per run, on first use; Ryuk removes it. A stale
    image passes silently, as the gateway's can.
  - The container reaches the test Postgres through `host.testcontainers.internal` and the
    mapped port (`Testcontainers.exposeHostPorts`), as `jobs_user` on `analytics`.
  - Its key is generated by the harness (`TestSigningKey`), so tests can mint its tokens. The
    contexts trust it at its mapped port from `IntegrationTest`'s `@DynamicPropertySource`. That
    source is a supplier, so the container is up before any context needs it, which avoids Day
    40's startup cycle.
  - It trusts `jobmatch-backend` at a key set the harness serves from
    `TestSigningKey.servicePath()`, which every context signs with. It trusts
    `jobmatch-test-caller` at `TestServiceCaller`'s key set, as `TestServiceCaller.java:25`
    foretold.
  - Its counts calls go to a relay in the test JVM, which `IntegrationTest` points at the running
    test's context before each test. The relay only forwards: a failing stub behind it would
    open the one breaker for the rest of the run.
  - From C2, every run starts it:
    - CI builds the image before the first test step (`backend-ci-cd.yaml:48-50`), the path
      filter adds `services/job-service/**`, and the gateway class list adds
      `JobServiceHarnessIT`.
    - The run instructions that say only Docker is needed now say to build the image first:
      `backend/README.md:72,281,314,326`, `README.md:766` and `CLAUDE.md:76`.
  - D changes defaults only:
    - `Services.url`'s job-service entry becomes the container, as `http://localhost:<mapped>`,
      the only host `Gateway.insideContainer` accepts.
    - `Services` gives `/internal/postings/**` to job-service.
    - `IntegrationTest` sets `harness.job-service-url`, never `app.internal.jobs-url` itself.
- **Monolith tests the move would otherwise break.**
  - **Track 0**, each passing before and after:
    - **The indirection:** `application-test.yaml` maps
      `app.internal.jobs-url: ${harness.job-service-url:}`. When a base class and a subclass both
      register a property through `@DynamicPropertySource`, the base class wins (tried on Boot
      4.1.0). Without the indirection, `PostingLookupUnavailableIT`, `PostingShortlistUnavailableIT`
      (line 33 of each) and `CircuitBreakerIT` would call the container instead of
      `StubUpstream`.
    - `internal/PostingBatchIT`, `internal/PostingShortlistIT` and `postings/ShortlistOrderIT`
      stop injecting `@Qualifier("jobsDirectory")` (lines 34, 35 and 22). They expect the
      builders' fixed values, and send through a client routed by `Services`, so from D they hold
      Day 18's routes against the container.
    - `internal/InternalClientsIT`'s `emptyPropertyMeansThisProcessWithAServiceToken` (lines
      50-61) and `sameClientIsReturnedEveryTime` (line 119) read a property nobody sets, not
      `app.internal.jobs-url`.
    - `database/ModuleConnectionsIT`'s jobs cases log in over plain JDBC, not `jobsDataSource`
      (line 30).
    - `internal/CircuitBreakerIT` drives the monolith's `postingLookup` breaker through
      `/api/saved-jobs`. job-service's breaker is tested in its own module (E1).
  - **Track D:**
    - `ModuleBoundariesTest.jobsKeepsToItself` goes; with no classes left, ArchUnit fails it with
      "failed to check any classes". Its `JOBS` constant (line 40) leaves the rules at lines 54,
      67, 73, 85 and 94 too, and is not rebuilt by concatenation to dodge the grep.
    - `InternalClientsIT.breakersAreConfiguredAsTheSpecSays` drops `savedJobCounts`, or it would
      read Resilience4j's default window of 100.
    - `matching/InternalCallsObservedIT` drops its `/internal/saved-counts` leg (lines 36-39);
      E2 measures that call in job-service.
    - `internal/SavedJobCountsUnavailableIT` goes, and its cases come back in E1. Across the
      container, three failures and two successes would open the shared breaker, and
      `JobSavedCountIT` would then read 0.
- **Docs and stale comments** (E2, unless a track above takes them):
  - `backend/docs/configuration.md:85,122-123`, plus job-service's own variables;
  - `backend/docs/auth.md:308-309`, `backend/docs/architecture.md:49`, `backend/docs/api.md` §13,
    and `backend/docs/jobs-search.md:8,45`;
  - the gateway's `application.yaml:59-60`;
  - the Javadocs saying "until Day 17": `InternalClients.java:17-19`,
    `PostingLookupClient.java:25`, `PostingShortlistClient.java:24` and
    `InternalSavedCountsController.java:17`. `SavedJobCountsClient.java:23` is fixed where it
    lands, in E1.

## Out of scope
- Its own database — Day 20. Neither query-count test expires today (`JobSavedCountQueriesIT`
  expires on Day 25, `SavedJobHydrationQueriesIT` on Day 20).
- Fixtures inside job-service's module — Day 20's spec change decides, since choice 2 builds no
  such harness.
- `X-User-Id` — Day 21 (Days 15 and 39). Retry — Day 24.
- API docs across services: `/api/docs` stays the monolith's and loses Jobs. Accepted (Notes).

## Tracks

| Track | Owner | Work | Estimate |
|---|---|---|---|
| 0 | | The indirection and the monolith tests above | 250–350 |
| A1 | | Skeleton: pom, application, `application.yaml`, tracing, actuator and public chains; a test pinning the public list | 300–350 |
| A2 | | Dockerfile and `job-service-ci-cd.yaml` | 100–150 |
| B1 | | Service identity: key, minter, key set, `InternalCallers` (list and `backend-key-set-url`), the `/internal/**` chain; tests | 350–400 |
| B2 | | The `shared` copies, the breaker config and its dependencies | 300–350 |
| C1 | | The monolith's issuer list (with `IntegrationTest`); compose adds job-service unrouted, the third key and the scrape job | 200–300 |
| C2 | | The harness container, its key set and relay; CI builds the image first; run instructions; `JobServiceHarnessIT` | 300–380 |
| D | | The move and the switch; `JobsLeftTheMonolithIT`; the test edits above; dead config | 300–380 |
| E1 | | In job-service's module: a stub upstream, the counts fallback cases, the breaker | 300–380 |
| E2 | | `JobServiceObservedIT` (metric and trace), added to CI's gateway list; docs and Javadocs | 250–350 |

The tracks run in this order: B builds on A's pom, C2 needs B1's key set, D needs C2, and E1
needs the client D moves. The estimates are the second read's `wc -l` of what each track copies.
In a scratch clone, D's move counted 0 lines for the ten files, 1 and 1 for `MartSkills`, 47 for
`jobs/pom.xml` and 99 for `SavedJobCountsUnavailableIT`. A track still over 400 splits again, and
its PR says where.

## Acceptance criteria
- [ ] **new** — In compose, job-service runs with no published port.
      Check: after `docker compose -p day17check --env-file .env.example up -d --build --wait`,
      `docker compose -p day17check ps --format '{{.Service}}: {{range .Publishers}}{{.PublishedPort}} {{end}}'`
      shows only 0s for `job-service`, and 8080 only on `api-gateway`. Red today: there is no
      `job-service` service. (C1)
- [ ] **new** — Trusted issuers bind from the environment as a list, in both services.
      Check: a unit test of each `InternalCallers`. Given `APP_INTERNAL_TRUSTEDISSUERS_0_NAME`
      and `..._0_KEYSETURL` in a `SystemEnvironmentPropertySource` named `systemEnvironment`
      (under any other name the binder skips it), it trusts that issuer and no other. Red today:
      the map turns them into issuers named `0.name` and `0.keyseturl`. (B1, C1)
- [ ] **new** — job-service and the monolith trust each other, and only whom they list.
      Check: `JobServiceHarnessIT`.
      - A `jobmatch-job-service` token gets 200 from the monolith's `/internal/saved-counts`
        (red today: 401).
      - The monolith's token gets 404 from job-service's `POST /internal/nothing`, and from D,
        200 from `/internal/postings/batch`.
      - No token, an expired one, or an unlisted issuer gets 401.
      - Anonymous `GET /.well-known/service-jwks.json` gets 200. (C2)
- [ ] **new** — The monolith no longer serves job search or the postings routes.
      Check: `JobsLeftTheMonolithIT`, outside `contract/`, with `direct()`.
      - `GET /api/jobs`, `/api/jobs/filters` and `/api/jobs/seed-0001` answer 401 anonymous and
        404 logged in.
      - `POST /internal/postings/batch` and `/shortlist` answer 404 to the monolith's own token.
      - In compose, the Verify's in-network curl prints 401.

      Red today: 200 on each, measured by both reads. (D)
- [ ] **new** — `jobs` has left the monolith. Check: `test -z "$(git ls-files backend/jobs)"`
      (not `test ! -e`: the ignored `target/` stays), and
      `git grep -n -E "backend\.jobs|shared\.mart" -- backend` prints nothing. Red today: 11
      files tracked, and the grep finds 23 lines in 12 files (`git grep -c`). (D)
- [ ] **new** — job-service is measured on its own port. Check: `JobServiceObservedIT`. One
      `GET /api/jobs?q=<title>` adds 1 to two series on job-service's mapped management port:
      `http_server_requests_seconds_count{uri="/api/jobs",status="200"}` and
      `http_client_requests_seconds_count{uri="/internal/saved-counts",status="200"}`. Red today:
      both rise on the monolith's port instead. (E2; Day 40's hand-off)
- [ ] **new** — A trace crosses into job-service. Check: `JobServiceObservedIT`. For a
      `GET /api/jobs/{id}` carrying trace id T, the monolith's server span for
      `/internal/saved-counts` has trace id T, and a parent that is none of the monolith's own
      spans. It runs in both harness runs; the gateway continues the trace (Day 15). Red today:
      the parent is the monolith's own client span. (E2)
- [ ] **hold** — The Day 1–4 suite passes unedited, directly and through the gateway.
      Check: `git diff b219a5f HEAD -- backend/app/src/test/java/nl/hackyourfuture/project/backend/contract`
      prints nothing, and both CI backend runs are green (on b219a5f: 390 direct with 1 skipped,
      206 through the gateway).
      - Broken on purpose in this PR: `INTERNAL_APPLICATIONS_URL=http://localhost:1` for
        `JobSavedCountIT` turned 5 of 6 red, each expecting 3 or 1 and getting 0. The second read
        reproduced it.
      - Broken again in D, each on its own:
        - the container's counts URL at a dead port (`JobSavedCountIT`);
        - `harness.job-service-url` unset (`SavedJobHydrationIT` and `Match*IT` answer 500).
- [ ] **hold** — The query counts hold across processes. Check: `JobSavedCountQueriesIT` and
      `SavedJobHydrationQueriesIT` pass unedited. `StatementCounter.java:36` counts the whole
      database, the container included. They were seen red on Days 08 and 09. D breaks them
      again in the container, with the counts asked one id at a time.
- [ ] **hold** — A stale cookie on a public job path is ignored. Check: `tokens/StaleCookieIT`
      passes unedited in both runs, answered by job-service from D. Broken in D by giving
      job-service's public chain a resource server that reads the `access_token` cookie (should
      answer 401).
- [ ] **hold** — `jobs_user` stays read-only and in its lane, and job-service connects as it.
      Check: `database/ModuleConnectionsIT`.
      - Over plain JDBC from Track 0: `jobs_user` cannot write the mart or read
        `identity.user_credentials`. Broken in Track 0 with `GRANT USAGE ON SCHEMA identity TO
        jobs_user`. A table grant alone keeps the "for schema" error, so the test stays green.
      - From D, the `pg_stat_statements` rows that mention `fct_postings` after a `/api/jobs`
        have `userid = 'jobs_user'::regrole`. Broken in D by starting the container as the
        owner.

## Verify
```bash
# The compose network name is pinned (docker-compose.yml:197): stop the maintainer's project first,
# never `down -v` it. Rebuild both images first: a stale one passes silently.
docker build -t jobmatch-job-service:harness services/job-service
docker build -t jobmatch-api-gateway:harness services/api-gateway
backend/mvnw -B -f services/job-service/pom.xml clean verify checkstyle:check
(cd backend && rm -rf */target/surefire-reports && ./mvnw -B clean verify && ./mvnw -B checkstyle:check)
(cd backend && ./mvnw -B verify -pl app -am -Dharness.gateway=true \
  -Dtest='nl.hackyourfuture.project.backend.contract.*IT,StaleCookieIT,RefreshIT,GatewayHarnessIT,ServiceRoutingIT,ObservabilityIT,JobServiceHarnessIT,JobServiceObservedIT' \
  -Dsurefire.failIfNoSpecifiedTests=false)
test -z "$(git ls-files backend/jobs)" && git grep -n -E "backend\.jobs|shared\.mart" -- backend   # nothing

docker compose -p day17check --env-file .env.example up -d --build --wait
docker compose -p day17check ps --format '{{.Service}}: {{range .Publishers}}{{.PublishedPort}} {{end}}'
# A fresh volume has no mart (/api/jobs answers 500 whoever serves it); seed it from the fixtures:
for f in analytics-schema analytics-seed; do
  docker compose -p day17check exec -T db sh -c 'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"' \
    < backend/app/src/test/resources/fixtures/$f.sql
done
curl -s -o /dev/null -w '%{http_code}\n' localhost:8080/api/jobs                # 200, from job-service
docker run --rm --network finalproject curlimages/curl \
  -s -o /dev/null -w '%{http_code}\n' http://backend:8080/api/jobs              # 401
docker compose -p day17check down -v
```

## Notes
- Two spec-auditor reads (17 findings, then 13) are recorded in the spec-change PR.
- **Hand-offs picked up here:**
  - Day 03: the filters (choice 3).
  - Days 06, 09 and 18: `MartSkills` (choice 1). Day 06 named Day 09, which declined without
    naming a day.
  - Day 15: routes (Day 40 wrote them) and `X-User-Id` (Day 21).
  - Day 16: the access table's third copy.
  - Day 18: job-service's `/internal/**` chain and its trust (B1).
  - Day 19: the startup cycle (the harness, above), the counts client's needs (B2), and its
    failure tests (E1).
  - Day 38: `jobs_user` (the last hold).
  - Day 39: the key, the key set, and a trust list that can be set from the environment
    (choice 4).
  - Day 40: the image, the harness, CI, and the metric on job-service's own port. The comment
    at `scripts/spec-drift.py:48` is an example, not a hand-off.
- Between D and E1, `main` has no failure tests for the counts client. E1 is the next PR, and D's
  description says so.
- job-service keeps its `shared` copies in `backend.shared.*`, so the move is renames.
- **Accepted:** from D, the public `/api/docs` no longer lists Jobs. No Phase 3–5 day owns
  putting it back; the plan-auditor weighs it at the Day 28 stop.
- **For Day 20:** its draft copies fixtures into "job-service's test harness", and there is none.
  Its own database has to be reached from the backend's harness.
- **For Days 21 and 25:** choices 1 and 4 come back. Phases 0–2 ran at 1.5× their estimates.
