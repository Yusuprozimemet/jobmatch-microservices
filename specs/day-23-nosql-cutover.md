# Day 23 — matching-service lets go of Postgres

**Phase:** 4 · **Depends on:** Day 22 · **Expected PRs:** 5 (3 tracks, the spec change, the close;
6 if Track B splits)

The fourth of Phase 4's days (41, 21, 22, 23, 24). Day 22 took this day's first plan (the read
switch, the purge deletions, the hit-rate metric): with no dual write there was nothing to switch
later. What is left is the Postgres side, and one harness test Day 22 found red through the
gateway.

## Goal
`matching-service` starts with no Postgres configuration, the scores table is gone from every
database the app migrates, and `ServiceJwksIT` passes through the gateway.

## In scope
- **The drop is app `V15`**, applied by the monolith as the owner (`DB_USER`), like V12–V14. Not a
  `db/matching` V2: Track B removes the service's Flyway, after which V10 and V14 recreate the
  table on every fresh database and nothing would drop it; and compose does not make
  matching-service wait for `backend`, so a V2 could run before V14 has moved the table into
  schema `matching`. V15 drops `matching.job_match_scores` (`IF EXISTS`: a database restored from
  before V10 has none).
- **V15 also carries `db/matching` V1's revokes** (schema, tables, and the default privileges
  registered for `matching_user`). On a fresh database V1 is what removes the grants the harness's
  `PostgresContainer` gives every module schema on purpose (`PostgresContainer.java:146-151`);
  once V1 stops running, nothing else would, and `ModuleConnectionsIT` reads them.
- **matching-service lets go of Postgres:** `app.datasource` and `spring.flyway` in
  `application.yaml`; `db/matching` (V1 and its README); `MatchingDatabase.java`, which nothing
  uses since Day 22; the `spring-boot-starter-jdbc`, `spring-boot-starter-flyway`,
  `flyway-database-postgresql` and `postgresql` dependencies.
- **Its test-side Postgres:** `PostgresContainer`, `PostgresContainerTest`,
  `fixtures/matching-schema.sql`, the `testcontainers-postgresql` dependency, the three
  `app.datasource.matching.*` properties in `MatchingServiceTest` (17 test classes extend it), and
  `DynamoDbContainer`'s javadoc link to `PostgresContainer`.
- **`DB_*` out of compose and the harness:** the five `DB_*` keys of compose's `matching-service`,
  and the five `DB_*` `withEnv` lines of `support/MatchingService` with the comment that names
  them.
- **The harness tests the change breaks, named here because no earlier spec did:**
  `BackendApplicationTests:32` (asserts the table is in `matching`); `ModuleMigrationsIT:43`
  (expects matching's Flyway history, `0 BASELINE|1 SQL`) and its `MatchingService.port()` wait
  (`:22-27`); `ModuleConnectionsIT`'s wait (`:44-49`), whose comment says the revokes come from the
  service's container.
- **`ServiceJwksIT` through the gateway.** The gateway routes `/api/**` and
  `/.well-known/jwks.json` to the backend (`Routes.java:76-77`), not
  `/.well-known/service-jwks.json`, on purpose (Day 39); `ServiceJwksIT` sends its five requests
  with `anonymous()`, which goes through the gateway under `-Dharness.gateway=true` and gets 401.
  The calls move to `direct()`: without the gateway `anonymous()` already went straight to the
  monolith, so the request is the same, and the rule that the gateway does not route the path
  stays checked by the gateway's own `SecurityTest:96-105`. `ServiceJwksIT` joins CI's
  through-the-gateway list (`backend-ci-cd.yaml:85`), so a return to `anonymous()` is caught.

## Out of scope
- **The `matching` role and schema stay**, as do `MATCHING_DB_PASSWORD` in `.env.example`, the
  role in `scripts/db-init/10-module-roles.sh` and `scripts/db-setup.py`, and `matching` in the
  harness's `MODULE_SCHEMAS`. Not a choice: `V14__move_matching_tables.sql:4-12` raises on a
  fresh database without them, and V14 cannot be edited. Removing them needs V14 rewritten, and
  no day owns that.
- **`matching.flyway_schema_history`** on databases the service's Flyway ran on. Nothing reads it
  after Track B; it is left, owned by `matching_user`, and goes if the schema ever does.
  Dropping it in V15 would make the service's Flyway, still present between Tracks A and B,
  re-baseline on its next start.
- Moving any other data to NoSQL. Nothing else in this app is a key-value cache.
- The profile cache, retry, the existence answer — Day 24.

## Tracks

| Track | Owner | Work |
|---|---|---|
| A | | App `V15`: drop the table, carry `db/matching` V1's revokes; `BackendApplicationTests:32` to an empty `matching` |
| B | | The service's datasource, Flyway, `db/matching`, `MatchingDatabase`, dependencies and test-side Postgres out; `DB_*` out of compose and the harness; `ModuleMigrationsIT` and `ModuleConnectionsIT` for it. After A |
| C | | `ServiceJwksIT` on `direct()`; into CI's through-the-gateway list |

No Track 0. The three harness tests above cannot change before the work and pass both before and
after: each asserts what V15 or Track B changes, so each moves in the track that changes it. The
tests that do hold through the day exist already (the criteria below).

Track B deletes about 300 lines before its edits. If it passes 400, it splits: B1 the service's
main side, compose and the harness, keeping `org.postgresql` in test scope; B2 the test-side
Postgres, after B1.

## Acceptance criteria
- [ ] **new** (Track A) — On a fresh database the app's migrations leave schema `matching`
      empty: `BackendApplicationTests` asserts `tablesIn("matching")` is empty. Red today: V10
      creates the table and V14 moves it there; the assertion fails listing
      `job_match_scores matching_user`.
- [ ] **hold** (Track A, broken in Track B) — `ModuleConnectionsIT`'s `matching` cases pass
      unedited: `noModuleCanReadAnothersSchema` ("applications, matching.job_match_scores": still
      "permission denied for schema matching", which Postgres reports before it looks up the
      table) and `noModuleSchemaGrantsWhatIsCreatedLaterToAnyoneElse`. Broken on purpose, once
      the service's V1 no longer runs: V15 without its revokes; record what each reported.
- [ ] **new** (Track B) — `git grep -n -i "datasource\|flyway\|org\.postgresql\|testcontainers-postgresql\|jdbc\|PostgresContainer\|DB_HOST\|DB_PORT\|DB_NAME\|DB_MATCHING" -- services/matching-service`
      finds nothing. Red today: 56 lines in 8 files. (Not plain `postgres`: the prompts and API
      docs use it as a skill name, `MatchScorer.java:119`.)
- [ ] **new** (Track B) — `git grep -n "DB_" -- backend/app/src/test/java/nl/hackyourfuture/project/backend/support/MatchingService.java`
      finds nothing, and `docker compose --env-file .env.example config --format json` has no
      `DB_*` key in `matching-service`'s environment. Red today: six lines; five keys
      (`DB_HOST`, `DB_MATCHING_PASSWORD`, `DB_MATCHING_USER`, `DB_NAME`, `DB_PORT`).
- [ ] **new** (Track B) — The harness's matching-service container starts with no database to
      reach: the suite passes with it. Red today: its Flyway connects at startup; the five
      `DB_*` lines out of `support/MatchingService` alone, the container fails to start. Record
      what it reported in Track B's PR.
- [ ] **hold** — `MatchScoreCacheIT` (6), `MatchTopMatchesIT` (8) and `MatchRankingIT` (8) pass
      unedited, directly and through the gateway (`-Dharness.gateway=true`). Broken on purpose
      in Track B: `findScores` returning `Map.of()`; record what it reported.
- [ ] **new** (Track C) — `ServiceJwksIT` passes through the gateway:
      `./mvnw -B -pl app verify -Dharness.gateway=true -Dtest=ServiceJwksIT`, 5 run, 0 failures,
      0 errors; and `grep -c ServiceJwksIT .github/workflows/backend-ci-cd.yaml` is 1. Red today:
      5 run, 3 failures, 1 error (the spec-auditor's run); the grep finds 0.

## Verify
```bash
docker build -t jobmatch-job-service:harness services/job-service
docker build -t jobmatch-matching-service:harness services/matching-service
cd backend && rm -rf */target/surefire-reports \
  && ./mvnw clean verify && ./mvnw -B checkstyle:check \
  && ./mvnw -B -pl app verify -Dharness.gateway=true && cd ..
backend/mvnw -B -f services/matching-service/pom.xml verify checkstyle:check
git grep -n -i "datasource\|flyway\|org\.postgresql\|testcontainers-postgresql\|jdbc\|PostgresContainer\|DB_HOST\|DB_PORT\|DB_NAME\|DB_MATCHING" -- services/matching-service || echo clean
grep -c ServiceJwksIT .github/workflows/backend-ci-cd.yaml    # 1
# compose, in a project of its own, never the maintainer's:
docker compose -p day23 --env-file .env.example up -d --build
docker compose -p day23 --env-file .env.example config --format json \
  | python -c "import json,sys; e=json.load(sys.stdin)['services']['matching-service']['environment']; print([k for k in e if k.startswith('DB_')])"
docker compose -p day23 down -v
```

## Notes
- The table was a key-value cache wearing a relational costume; Day 22 moved the cache, this day
  takes the costume off.
- **Day 24's "no datasource and no Flyway configuration"** (`day-24:33`) is this day's, by
  Track B; Day 24 keeps it as a `hold`.
- **Day 21's hand-off of `JobMatchScoreCleanup` and `SchedulingConfig`** (`day-21:351`) was done
  by Day 22 (#238, its criterion at `day-22:157`), not here.
- **Defect** — found: auditor · cause: spec · the draft put the drop in a `db/matching` V2, which
  Track B's removal of the service's Flyway would leave unrun on every fresh database while V10
  and V14 recreate the table, and which compose could run before V14. Day 22's Out of scope and
  hand-off said the same. Fixed in this spec-change PR: app V15.
- **Defect** — found: auditor · cause: spec · the draft left keeping the `matching` role and
  schema as a choice, and cited `V14:22-27` for the check that makes it none; the check is at
  `:4-12` (V14 is 17 lines). Day 22 repeats the reference (`day-22:93`, `:272`); corrected there
  in this PR.
- **Defect** — found: auditor · cause: spec · In scope missed the service's test-side Postgres,
  `MatchingServiceTest`'s properties, `MatchingDatabase`, and three harness tests the change
  breaks; and named `DB_*` in `.env.example`, which has only `MATCHING_DB_PASSWORD`, for the role
  that stays.
- **Defect** — found: spec-change PR · cause: spec · neither the draft nor the audit named that
  `db/matching` V1 is what revokes the harness's grants on schema `matching`; without the
  service's Flyway nothing would, and `ModuleConnectionsIT` would fail. V15 carries them.
- **Defect** — found: auditor · cause: spec · the draft's criteria had no `new`/`hold` tags and
  no checks.
- **Defect** — found: review (Day 22) · cause: implementation · `ServiceJwksIT` sends
  `anonymous()` requests to a path the gateway does not route, so it fails under
  `-Dharness.gateway=true`; CI's list does not run it, which is how it stayed red. Day 22's
  Notes recorded it; Track C fixes it.
