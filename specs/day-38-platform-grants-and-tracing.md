# Day 38 — Each module reads only its own schema, and every call out is traced

**Phase:** 3 · **Depends on:** Day 16, `plan.md` "Course correction after Phase 2" · **Expected PRs:** 3

The first of the platform step's three days (38–40), which run before any extraction. Days keep
their numbers; `plan.md`'s day-by-day list gives the order.

## Goal
No module's database login can read another module's schema, the LLM call shows up in traces and
metrics, and the gateway has health and metrics of its own, before any of them leaves the process.

## In scope
- **Revoke the cross-module reads.** V12:31, V13:23 and V14:17 grant `SELECT` on each module's
  tables to the other three module logins, and the default privileges from
  `scripts/db-init/module-roles.sh`, `scripts/db-setup.py` and the test harness grant every later
  table the same way. So `jobs_user`, which Day 17 hands to a separate container, can read
  `identity.user_credentials` today. No main code reads another module's schema (the Day 08–10
  greps and a grep for `identity.`, `applications.`, `matching.` under `*/src/main` print
  nothing), so nothing needs these grants.
  - New migrations, never an edit to V12–V14: identity `V3`, applications `V1`, matching `V1`, each
    run by that module's own Flyway as the schema's owner. Each revokes `USAGE` on its schema and
    every privilege on its tables and sequences from every grantee but itself, found from the
    catalogue as identity `V1` and `V2` do, and removes the default privileges for later objects.
  - The three role sources stop granting it: `db-setup.py` (module schemas are the owner's only;
    `app`, `analytics` and `analytics_dev` keep today's rule), `db-init/module-roles.sh`, and the
    harness's `support/` role setup.
  - `database/ModuleConnectionsIT.identityMayStillReadThem` asserts the opposite of this day and
    becomes the check that it cannot. It is not in `contract/`; the change is recorded.
- **The LLM call through Spring's client builder.** `MatchScorer:47` builds its client with the
  static `RestClient.builder()`, which has no observation registry, so the call makes no client
  span and no `http_client_requests` metric. It takes the injected `RestClient.Builder` instead,
  keeping its 5 s connect and `LLM_TIMEOUT_SECONDS` read timeouts. It is the only client of its
  kind today (`RestClient.builder()`, `RestTemplate`, `WebClient` and `HttpClient` grepped under
  `backend` and `services`). Days 19, 21 and 24 build theirs the same way.
- **The gateway's health and metrics.** `services/api-gateway` has no actuator: Day 34's probes and
  Day 37's gateway metrics have nothing to read. Add actuator with health and Prometheus on a
  management port (9090, as the backend's), not published by compose, and a scrape job in
  `observability/prometheus.yml`. The public port keeps answering `/actuator/**` itself, as today.

## Out of scope
- JDBC spans. Day 05 left them undecided; they are dropped here, with a line in Day 05's Notes,
  unless Phase 4 shows a query worth tracing.
- Who owns the `/api/jobs` metric `contract/ObservabilityIT` asserts once job search leaves the
  monolith: Day 40, with the harness that runs job-service.
- The service credential: Day 39.

## Tracks

| Track | Owner | Work |
|---|---|---|
| A | | The revoking migrations, the three role sources, `ModuleConnectionsIT` |
| B | | `MatchScorer` on the injected builder, and its metric test |
| C | | Gateway actuator on a management port, and the Prometheus scrape |

The tracks touch different modules and can land in any order.

## Acceptance criteria
- [ ] **new** — No module login reads another module's schema: through the application's own
      pools, `identity_user` reading `applications.saved_jobs`, `jobs_user` reading
      `identity.user_credentials`, `applications_user` reading `matching.job_match_scores` and
      `matching_user` reading `identity.users` each fail with `permission denied`
      (`ModuleConnectionsIT`). Red today: `identityMayStillReadThem` passes, counting zero rows.
- [ ] **new** — The same holds for a table created later: a table the owner creates in its schema
      in the test is not readable by another module's login. Red today: the default privileges
      grant it `SELECT`.
- [ ] **hold** — Each module still reads and writes its own schema, and `jobs_user` still reads the
      mart and cannot write it (`eachModuleLogsInAsItsOwnRoleWithItsOwnSchema`,
      `jobsCannotWriteTheMart`, the full suite with `contract/` unedited). Broken on purpose: a
      migration that also revokes `analytics` from `jobs_user` turns job search red.
- [ ] **new** — The LLM call is measured: with `StubLlm` answering, after
      `GET /api/jobs/top-matches` the application's `/actuator/prometheus` has an
      `http_client_requests_seconds_count` for the model's host. Red today: the static builder
      records nothing.
- [ ] **hold** — A failing model still falls back to skill overlap: `contract/MatchRankingIT` and
      `contract/MatchScoreCacheIT`, which fail the stub with `willFail(500)`, pass unedited.
      Broken on purpose: `MatchScorer` letting the failure through instead of falling back turns
      them red. The read timeout itself has no test (`StubLlm` fails by status, never by delay);
      the builder change keeps both timeouts, and the spec change decides whether a delay case
      is added.
- [ ] **new** — The gateway serves `/actuator/health` (200) and `/actuator/prometheus` on its
      management port, and the latter has `http_server_requests_seconds_count` for a routed call
      (a gateway test). Red today: the gateway has no actuator.
- [ ] **hold** — The gateway's public port still answers `/actuator/prometheus` itself, 401
      without a token, and routes nothing but `/api/**` and the key set (`SecurityTest`, the
      route tests). Broken on purpose: actuator on the public port serves it with 200.

## Verify
```bash
# The backend, direct, and the gateway. Read the reports.
cd backend && rm -rf */target/surefire-reports && ./mvnw clean verify && ./mvnw -B checkstyle:check
cd .. && backend/mvnw -B -f services/api-gateway/pom.xml clean verify checkstyle:check

# Compose, in a project of its own (never `down -v` on the maintainer's; stop theirs first,
# the network name is pinned).
docker compose -p day38check --env-file .env.example --profile obs up -d --build
docker compose -p day38check exec db psql -U "$POSTGRES_USER" -d project_db \
  -c "SET ROLE jobs_user; SELECT count(*) FROM identity.user_credentials"   # permission denied
curl -s localhost:9091/api/v1/targets | grep -o '"job":"[a-z-]*"' | sort -u   # backend and gateway
docker compose -p day38check down -v
```

## Notes
- Written after Phase 2 from the whole-plan audit, the first spec written after the course
  correction (#115). The spec-auditor reads it before the spec-change PR, as for every day.
- Revoking by catalogue rather than by a list of names is what identity `V1` and `V2` already do:
  a list misses the role added next (and `db-setup.py` gives the analytics roles read access to
  every schema too).
- The platform step's other two days: Day 39, the service credential and the rule for a deleted
  user's token; Day 40, the multi-service harness, the compose profile, and the `/api/jobs`
  metric.
