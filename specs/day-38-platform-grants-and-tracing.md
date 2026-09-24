# Day 38 — Each module reads only its own schema, and every call out is traced

**Phase:** 3 · **Depends on:** Day 16, `plan.md` "Course correction after Phase 2" · **Expected PRs:** 4

The first of the platform step's three days (38–40), which run before Phase 3's extractions.
Days keep their numbers; `plan.md`'s day-by-day list gives the order. `Phase: 3` groups the day
with the extractions it prepares on the dashboard.

## Goal
No module's database login can read another module's schema, the LLM call shows up in traces and
metrics, and the gateway has health and metrics of its own, before any of them leaves the process.

## In scope
- **Revoke the cross-module reads.** V12:31, V13:23 and V14:17 grant `SELECT` on each module's
  tables to the other three module logins, and the default privileges from
  `scripts/db-init/module-roles.sh`, `scripts/db-setup.py` and the test harness grant every later
  table the same way. So `jobs_user`, which Day 17 hands to a separate container, can read
  `identity.user_credentials` today. No main code reads another module's schema:
  `grep -rnE "(FROM|JOIN|INTO|UPDATE|TABLE)\s+(identity|applications|matching)\.[a-z_]+" */src/main | grep -v db/migration`
  prints nothing, and the Day 08, 09 and 10 greps are clean.
  - New migrations, never an edit to V12–V14: identity `V3`, applications `V1`, matching `V1`, each
    run by that module's own Flyway as the schema's owner (both are baselined at 0 already, so a
    first `V1` applies after the baseline). Each revokes `USAGE` on its schema and every privilege
    on its tables and sequences from every grantee but itself, found from the catalogue as
    identity `V1` and `V2` do. The module role is the grantor of every table and schema grant, so
    it can revoke them (tried on a database set up by `db-setup.py`).
  - **The schema `USAGE` revoke is the guard for later tables.** A module role can remove only its
    own default privileges. In production `db-setup.py` also registered defaults for the admin,
    `app_user` and the analytics roles in each module schema; a module role cannot remove those
    (`permission denied to change default privileges`), and they stay, inert without `USAGE`.
    Recorded, not cleaned up: `db-setup.py` keeps "never changes existing state".
  - The three role sources stop granting it for a new database: `db-setup.py` (module schemas are
    the owner's only; `app`, `analytics` and `analytics_dev` keep today's rule, and its report stops
    printing "read-only on" the module schemas), `db-init/module-roles.sh`, and the harness's
    `support/` role setup.
  - **Tests outside `contract/` that assert today's grants,** each changed and recorded:
    - `database/ModuleConnectionsIT.identityMayStillReadThem` becomes the check that it cannot;
    - `ModuleConnectionsIT.identityCannotWriteApplicationsTables` expects
      `permission denied for schema applications`, the refusal once `USAGE` is gone;
    - `database/ModuleMigrationsIT.andItStartsAtTheBaseline` (three cases) expects the new
      migrations in each module's history;
    - `tokens/RefreshTokenGrantsIT.noOtherModuleCanReadThem` (six cases) expects the schema
      refusal; `theOthersAreGrantedWhatIdentityCreates`, Day 12's guard, is inverted: no grantee
      but the owner in `pg_default_acl` for the module schemas.
  - `backend/docs/configuration.md` §6 stops saying module schemas are read by "everyone".
- **The LLM call through Spring's client builder.** `MatchScorer:47` builds its client with the
  static `RestClient.builder()`, which has no observation registry, so the call makes no client
  span and no `http_client_requests` metric. It takes the injected `RestClient.Builder` instead,
  keeping its 5 s connect and `LLM_TIMEOUT_SECONDS` read timeouts. It is the only such client in
  main code today. An ArchUnit rule in `ModuleBoundariesTest` keeps it that way (no static
  `RestClient.builder()` or `RestClient.create()` in main code), so Days 19, 21 and 24 cannot
  build theirs untraced.
- **The gateway's health and metrics.** `services/api-gateway` has no actuator: Day 34's probes and
  Day 37's gateway metrics have nothing to read.
  - Add the actuator starter and `micrometer-registry-prometheus` (Day 05 found
    `/actuator/prometheus` needs the registry), with health and Prometheus on a management port
    (9090, as the backend's), not published by compose, and a scrape job in
    `observability/prometheus.yml`.
  - `Security.java` gains an `@Order(1)` chain permitting the actuator endpoints, as the backend's
    `SecurityConfig:39-57` has: the gateway's one chain ends in `anyRequest().authenticated()` and
    covers the management port too, so without it Prometheus gets 401.
  - OTLP metrics export off by default, as the backend's (`application.yaml:158-164`): the gateway
    pushes to `localhost:4318` every minute today.
  - The public port routes no `/actuator/**`: with or without a token, it never answers them 200.

## Out of scope
- JDBC spans. Day 05 left them undecided; they are dropped, and the closing PR adds the line to
  Day 05's Notes, unless Phase 4 shows a query worth tracing.
- Who owns the `/api/jobs` metric `contract/ObservabilityIT` asserts once job search leaves the
  monolith: Day 40, with the harness that runs job-service.
- The service credential: Day 39.

## Tracks

| Track | Owner | Work |
|---|---|---|
| 0 | | A gateway test: with a valid token, the public port answers `/actuator/health` and `/actuator/prometheus` 404 and the upstream receives nothing |
| A | | The revoking migrations, the three role sources, the four test classes, the docs line |
| B | | `MatchScorer` on the injected builder, its metric and span tests, the ArchUnit rule |
| C | | Gateway actuator on a management port, its security chain, the Prometheus scrape |

Track 0 lands first. A, B and C touch different modules and can land in any order after it.
Track A is the largest; each migration is one catalogue loop, and it splits by module if it
passes the 400-line gate.

## Acceptance criteria
- [ ] **new** — No module login reads another module's schema: through the application's own
      pools, `identity_user` reading `applications.saved_jobs`, `jobs_user` reading
      `identity.user_credentials`, `applications_user` reading `matching.job_match_scores` and
      `matching_user` reading `identity.users` each fail with `permission denied for schema`
      (`ModuleConnectionsIT`). Red today: `identityMayStillReadThem` passes, counting zero rows;
      in compose, `jobs_user` counts `identity.user_credentials` (0) instead of being refused.
- [ ] **new** — A table a module creates later is not granted to anyone else: no row in
      `pg_default_acl` for the module schemas names a grantee other than the schema's owner, and a
      table created through a module's own pool (as `<module>_user`) is not readable by another
      module's login. Red today: `theOthersAreGrantedWhatIdentityCreates` passes on the harness's
      default privileges. (Created through the harness connection, a superuser, the table gets no
      grants today either; that is why the check goes through the module's pool.)
- [ ] **hold** — Each module still reads and writes its own schema, and `jobs_user` still reads the
      mart and cannot write it (`eachModuleLogsInAsItsOwnRoleWithItsOwnSchema`,
      `jobsCannotWriteTheMart`, the full suite with `contract/` unedited). Broken on purpose: the
      catalogue loop without its `grantee <> owner` filter revokes the owner's own rights too, and
      the suite goes red.
- [ ] **new** — The LLM call is measured and traced: with `StubLlm` answering, after
      `GET /api/jobs/top-matches` the application's `/actuator/prometheus` has
      `http_client_requests_seconds_count` for `uri="/chat/completions"`, and the test tracer
      records a client span for the call. Red today: the static builder records neither (a
      scratch run of the auditor's found 0 lines; built from the injected builder, 1). The
      closing PR ticks Day 05's LLM-span box with this.
- [ ] **new** — No main code builds a `RestClient` outside Spring: the ArchUnit rule. Red today:
      `MatchScorer:47`.
- [ ] **hold** — A failing model still falls back to skill overlap:
      `contract/MatchTopMatchesIT.fallsBackToSkillOverlapWhenTheModelFails` and
      `contract/MatchRankingIT` pass unedited. Broken on purpose (in the spec-auditor's scratch
      copy): with `MatchScorer` rethrowing the failure, `MatchRankingIT` had 2 of 8 red and
      `MatchTopMatchesIT` 1 of 8. (`MatchScoreCacheIT` stayed green: it never asserts the first
      response.) The read timeout itself has no test: `StubLlm` fails by status, never by delay,
      and the builder change keeps both timeouts.
- [ ] **new** — The gateway serves `/actuator/health` (200) and `/actuator/prometheus` on its
      management port without a token, and the latter has `http_server_requests_seconds_count`
      for a routed call (a gateway test); in compose, Prometheus lists the gateway's scrape job
      as up. Red today: the gateway has no actuator, and Prometheus lists only
      `jobmatch-backend`.
- [ ] **hold** — The public port never serves actuator: with a valid token, `/actuator/health` and
      `/actuator/prometheus` answer 404 and the upstream receives nothing; without one, 401
      (Track 0). Green today (401 and 404). Broken on purpose (the auditor's scratch copy):
      actuator added with no management port answered the valid-token request 200 with the
      Prometheus text, while `SecurityTest` and `RoutesTest` stayed green, which is why this
      check is new.

## Verify
```bash
# The backend, direct, and the gateway. Read the reports.
cd backend && rm -rf */target/surefire-reports && ./mvnw clean verify && ./mvnw -B checkstyle:check
cd .. && backend/mvnw -B -f services/api-gateway/pom.xml clean verify checkstyle:check

# Compose, in a project of its own (never `down -v` on the maintainer's; stop theirs first,
# the network name is pinned).
docker compose -p day38check --env-file .env.example --profile obs up -d --build
docker compose -p day38check exec db sh -c \
  'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "SET ROLE jobs_user; SELECT count(*) FROM identity.user_credentials"'
  # before: count 0 · after: permission denied for schema identity
curl -s localhost:9091/api/v1/targets | grep -o '"job":"[a-z-]*"' | sort -u   # backend and gateway
docker compose -p day38check --env-file .env.example --profile obs down -v
```

## Notes
- Written after Phase 2 from the whole-plan audit, the first spec written after the course
  correction (#115).
- Revoking by catalogue rather than by a list of names is what identity `V1` and `V2` already do:
  a list misses the role added next.
- The platform step's other two days: Day 39, the service credential and the rule for a deleted
  user's token; Day 40, the multi-service harness, the compose profile, and the `/api/jobs`
  metric.
- **Spec corrected on Day 38, before the work, from the spec-auditor's read** of `main` at
  30c1d4a. It settled by trying what reading could not:
  - **The public-port `hold` could not fail.** The gateway answers `/actuator/*` 401 without a
    token whether actuator is there or not; with actuator on the public port a valid token got
    200 while `SecurityTest` and `RoutesTest` stayed green. Now a valid-token case, in Track 0.
  - **The management port would have been behind the gateway's security chain,** so Prometheus
    would get 401; Track C adds the actuator chain, as the backend has.
  - **The later-table `new` passed today**: the harness creates tables as a superuser, whose tables
    get no grants. It now goes through a module's pool and checks `pg_default_acl`.
  - **Track A turns 11 more tests red** outside `contract/` than the one named: `ModuleConnectionsIT`
    (the refusal becomes "for schema"), `ModuleMigrationsIT` (the history), `RefreshTokenGrantsIT`
    (Day 12's guard). Tried with scratch migrations: 302 run, 11 failures, 1 error.
  - **A module role can revoke the grants V12–V14 made** (it is their grantor) but not the defaults
    `db-setup.py` registered for other creators; the `USAGE` revoke is the guard.
  - **The Verify's `psql -U "$POSTGRES_USER"`** ran with an empty variable on the host
    (`role "root" does not exist`), which would have read as a refusal after the change too.
  - **The fallback `hold` named a test that does not notice the break** (`MatchScoreCacheIT`) and
    missed the one that does (`MatchTopMatchesIT`).
  - **The grep quoted for "no cross-schema SQL"** prints 106 lines (package names, keys); the one
    that prints nothing is quoted now.
  - **"Days 19, 21 and 24 build theirs the same way"** was a hand-off only this spec knew of; an
    ArchUnit rule holds it now. The LLM span, not just the metric, is checked, so Day 05's open
    box closes here; the gateway's OTLP metrics push is switched off as the backend's is.
  - `data/`, the DAGs and `scripts/` read no module schema; `fk_saved_jobs_user` and its cascade
    still work with identity's `USAGE` revoked (tried).
