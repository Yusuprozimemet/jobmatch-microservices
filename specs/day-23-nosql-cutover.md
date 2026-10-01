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
- [x] **new** (Track A) — On a fresh database the app's migrations leave schema `matching`
      empty: `BackendApplicationTests` asserts `tablesIn("matching")` is empty. Red today: V10
      creates the table and V14 moves it there; the assertion fails listing
      `job_match_scores matching_user`.
      #245: `BackendApplicationTests` asserts `tablesIn("matching")` is empty; V15 drops the table.
      Seen red: V15 without its `DROP`, `Expecting empty but was: ["job_match_scores matching_user"]`.
      The same break left `ModuleMigrationsIT` green (it starts the service before the app migrates),
      so `BackendApplicationTests` is the one check on the drop.
- [x] **hold** (Track A, broken in Track B) — `ModuleConnectionsIT`'s `matching` cases pass
      unedited: `noModuleCanReadAnothersSchema` ("applications, matching.job_match_scores": still
      "permission denied for schema matching", which Postgres reports before it looks up the
      table) and `noModuleSchemaGrantsWhatIsCreatedLaterToAnyoneElse`. Broken on purpose, once
      the service's V1 no longer runs: V15 without its revokes; record what each reported.
      #245 (V15 with the revokes) and #246 (the service's V1 gone); `ModuleConnectionsIT` 10 of 10, no
      assertion edited, only its wait and the comment naming the service's container (#246). Seen red
      in #246, V15's revoke block removed: 2 of 10 red. `noModuleCanReadAnothersSchema` got
      `relation "matching.job_match_scores" does not exist` where it expects "permission denied for
      schema matching"; `noModuleSchemaGrantsWhatIsCreatedLaterToAnyoneElse` was not empty, listing
      `identity_user`, `applications_user` and `jobs_user` on `matching`. Not tried in #245: until
      #246 the service's V1 still revoked the same grants.
- [x] **new** (Track B) — `git grep -n -i "datasource\|flyway\|org\.postgresql\|testcontainers-postgresql\|jdbc\|PostgresContainer\|DB_HOST\|DB_PORT\|DB_NAME\|DB_MATCHING" -- services/matching-service`
      finds nothing. Red today: 56 lines in 8 files. (Not plain `postgres`: the prompts and API
      docs use it as a skill name, `MatchScorer.java:119`.)
      #246 (the main side) and #247 (the test side): the grep finds nothing (exit 1) on e00f1a0.
      Seen red at e95b65b, before both: 56 lines in 8 files, as the spec counted; after #246, 21
      lines, all on the test side.
- [x] **new** (Track B) — `git grep -n "DB_" -- backend/app/src/test/java/nl/hackyourfuture/project/backend/support/MatchingService.java`
      finds nothing, and `docker compose --env-file .env.example config --format json` has no
      `DB_*` key in `matching-service`'s environment. Red today: six lines; five keys
      (`DB_HOST`, `DB_MATCHING_PASSWORD`, `DB_MATCHING_USER`, `DB_NAME`, `DB_PORT`).
      #246. **Not met as written; see Notes.** The grep for `DB_` matches `SCORES_DYNAMODB_ENDPOINT`
      (Day 22's line, `MatchingService.java:83`), so it exits 0 on e00f1a0, and the spec's "six lines"
      red counted that line. Anchored on the quote, `git grep -n '"DB_'` finds nothing (exit 1); the
      compose check prints `[]`. Seen red at e95b65b, before #246: the anchored grep, 5 lines; the
      compose check, `['DB_HOST', 'DB_MATCHING_PASSWORD', 'DB_MATCHING_USER', 'DB_NAME', 'DB_PORT']`.
- [x] **new** (Track B) — The harness's matching-service container starts with no database to
      reach: the suite passes with it. Red today: its Flyway connects at startup; the five
      `DB_*` lines out of `support/MatchingService` alone, the container fails to start. Record
      what it reported in Track B's PR.
      #246: the suite passes with this image (428 run, 0 failures). Seen red on main's image, the five
      `DB_*` lines out of `support/MatchingService` alone: the container exited with code 1,
      `FlywaySqlUnableToConnectToDbException: Unable to obtain connection from database`.
- [x] **hold** — `MatchScoreCacheIT` (6), `MatchTopMatchesIT` (8) and `MatchRankingIT` (8) pass
      unedited, directly and through the gateway (`-Dharness.gateway=true`). Broken on purpose
      in Track B: `findScores` returning `Map.of()`; record what it reported.
      Unedited (0 lines in `contract/` this day), green directly and through the gateway in #245–#248
      and at the close. Seen red in #246, `findScores` returning `Map.of()`: 4 of 6 red in
      `MatchScoreCacheIT`, expected 1 model call, was 2, directly and through the gateway alike.
      `MatchTopMatchesIT` and `MatchRankingIT` stayed green under that break: neither depends on a
      cache hit.
- [x] **new** (Track C) — `ServiceJwksIT` passes through the gateway:
      `./mvnw -B -pl app verify -Dharness.gateway=true -Dtest=ServiceJwksIT`, 5 run, 0 failures,
      0 errors; and `grep -c ServiceJwksIT .github/workflows/backend-ci-cd.yaml` is 1. Red today:
      5 run, 3 failures, 1 error (the spec-auditor's run); the grep finds 0.
      #248: 5 run, 0 failures, 0 errors through the gateway; the grep is 1. Seen red in #248, the old
      file (all `anonymous()`) through the gateway: 5 run, 3 failures, 1 error, as the spec's red; the
      grep was 0 before. #248's first CI comment named the class and made the grep 2; reworded.

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
- **Track order: A (#245), B1 (#246), B2 (#247), C (#248).** B split as the tracks section
  allowed: B1 the main side, compose and the harness, keeping `org.postgresql` in test scope; B2
  the test side. C depends on neither and went last.
- **Departures, each recorded in its PR:**
  - **`ModuleMigrationsIT` changed in Track A (#245), not B.** V15 broke two of its assertions:
    `appKeepsTheHistoryOfEverythingBefore` (`expected 14 but was 15`) and matching's
    `andItStartsAtTheBaseline` (`["1 SQL"]` against `0 BASELINE|1 SQL`: the service's Flyway
    baselines only a schema that holds something, and V15 empties it). B1 then removed matching's
    history cases and added `matchingKeepsNoHistoryOfItsOwn`.
  - **Compose's `matching-service` lost `depends_on: db`** (#246), which In scope did not name;
    the harness container also lost the Postgres port it exposed.
  - **`org.testcontainers:testcontainers` added in test scope** (#247): `DynamoDbContainer`'s
    `GenericContainer` came only through `testcontainers-postgresql`. Without it, `clean
    test-compile` fails with `package org.testcontainers.containers does not exist`.
  - **Criterion 4 ticked on a grep anchored on the quote** (`'"DB_'`), at the close. As written
    it cannot print clean; see the defect below.
- **Defect** — found: Track A · cause: spec · the list of harness tests the change breaks missed
  `ModuleMigrationsIT`'s app history count and its matching baseline case (#245).
- **Defect** — found: Track B2 · cause: spec · removing `testcontainers-postgresql` also removed
  the only path to `GenericContainer`; In scope named no replacement (#247).
- **Defect** — found: break on purpose · cause: process · #247's first break, the new dependency
  removed, passed without `clean`: the incremental compile skipped the unchanged sources. A
  dependency check without `clean` checks nothing.
- **Defect** — found: close · cause: spec · criterion 4's `git grep -n "DB_"` matches
  `SCORES_DYNAMODB_ENDPOINT` (`support/MatchingService.java:83`, Day 22's), so it cannot print
  clean. Its "six lines" red counted that line beside the five `DB_*` keys, and neither the
  spec change nor its audit saw it. The anchored grep goes from 5 lines (e95b65b) to none.
- **Defect** — found: review · cause: implementation · V15's header comment, from the
  implementer on Haiku, said `matching_user` no longer needs Postgres; the role stays (Out of
  scope). Rewritten before #245.
- **Defect** — found: self · cause: implementation · #248's first CI comment named `ServiceJwksIT`,
  making criterion 7's grep 2, not 1: the grep-versus-comment pitfall. Reworded before the PR.
- **Fixed at the close:** `backend/README.md`'s `docker run` example and its two login rows
  listed `DB_MATCHING_*` for the backend, which has not read them since Day 21 (found in #246).
- **The close's Verify, on e00f1a0:** the job-service, matching-service and api-gateway harness
  images rebuilt; `clean verify` 428 tests, 0 failures, 0 errors, 1 skipped, counted from the
  reports after deleting them; `checkstyle:check` 0 violations; through the gateway 428, 0
  failures, 0 errors, `ServiceJwksIT` included; matching-service `clean verify checkstyle:check` 69, 0 failures, no violations; the
  service-wide grep nothing (exit 1); the CI grep 1; the compose check `[]`. `up -d --build
  --wait` in a project of its own (`day23`): every service running, matching-service healthy with
  `depends_on` only `dynamodb` and `jwt-key`; app's history ends at
  `15|drop job match scores|t`, and schema `matching` holds no table, not even a Flyway history.
  Then `down -v` on that project, which also removed the shared network `finalproject`, as on
  Day 22.
- **Found, not owned:**
  - `ScoreTableStartupTest` is still in package `…backend.matching` (Day 22's slip); the
    service's other tests are in `…matchingservice`.
  - `matching.flyway_schema_history` stays on databases the service's Flyway ran on, as Out of
    scope decided; nothing reads it.
  - The `matching` role and schema, `MATCHING_DB_PASSWORD`, and their lines in `db-init`,
    `db-setup.py` and the harness stay because `V14__move_matching_tables.sql:4-12` raises
    without them. Removing them needs V14 rewritten; no day owns that.
- **The estimate:** 5 PRs expected, 6 if Track B split; it did. Took 6: the spec change (#244),
  4 track PRs (#245–#248) and this close. The tracks came to 510 changed lines (75, 194, 221,
  20). No `Oversized:` line.
- **Tests:** `contract/` unchanged; `support/` +1 −7 (`MatchingService`, the `DB_*` env).
  Outside both: `BackendApplicationTests` +3 −2, `ModuleMigrationsIT` +14 −13,
  `ModuleConnectionsIT` −9 (its wait and comment), `ServiceJwksIT` +7 −6. In matching-service,
  `PostgresContainer`, `PostgresContainerTest` and the schema fixture deleted (−196),
  `MatchingServiceTest` +3 −6, `DynamoDbContainer` +5 −1. Backend tests 429 → 428 (matching's
  two history cases out, one new); matching-service 72 → 69.
- **Hand-offs this day leaves:**
  - **Day 24:** its `hold` "no datasource and no Flyway configuration" (`day-24:33`) is true now;
    Day 23's grep finds nothing in `services/matching-service`.
  - **Day 32:** matching-service needs no database in Terraform; only the DynamoDB table (Day
    22's hand-off).
