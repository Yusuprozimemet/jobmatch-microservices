# Day 25 — application-service becomes its own deployable

**Phase:** 5 · **Depends on:** Day 27 · **Expected PRs:** 15 (the spec change, Tracks 0–F
below, the close)

Third of Phase 5 (**26 → 27 → 25 → 28**). The `user.deleted` consumer already serves (Day 27),
so the table can move: the foreign key that deleted saved jobs with their user cannot cross into
a database of its own, and goes with the old table.

## Goal
Saved jobs and the tracker run as their own service with their own database, and deleting an
account still removes them, now only by `user.deleted`.

## In scope
- `backend/applications` → `services/application-service`, on Day 21's pattern: own pom,
  Dockerfile, a CI workflow with #277's `changes` and `gate` jobs, compose (no published port),
  gateway route `/api/saved-jobs/**`. The Day 27 consumer moves with it;
  `UserDeletedConsumer`'s Javadoc ("until Day 25 moves saved_jobs") is corrected in the move.
- **`apps_db`**, holding `saved_jobs` and the `job_state` type, migrated by application-service's
  own Flyway as `applications_user`, in an `applications` schema; created by `db-setup.py`, `scripts/db-init/30-apps-db.sh`
  and the harness's `PostgresContainer`, as Day 20 did `jobs_db` (CONNECT revoked from PUBLIC,
  granted to `applications_user`). Existing rows are copied from `project_db` by
  `scripts/copy-saved-jobs.py`, re-runnable: it skips rows already there.
- **The key goes with the old table.** V13 says `fk_saved_jobs_user` "stays until Day 27"; the
  plan moved it to the table move. V16 drops `applications.saved_jobs` from `project_db`, and the
  key with it, in the last track: only after application-service serves from `apps_db` and its
  consumer reads its queue there. Never an edit to V13. The `applications` role and schema stay
  in `project_db`: V13 raises without them, and V12 and V14 grant to the role (Day 28's review).
- **`applications_user` stops connecting to `project_db`** in the last track, once the monolith
  no longer runs the module as that role. `db-setup.py:376` grants it today, and the harness
  leaves PUBLIC's default.
- `POST /internal/saved-counts` moves with it; job-service's `INTERNAL_APPLICATIONS_URL`
  (`docker-compose.yml:108`) points at application-service.
- **The service credential and trust**, as Day 21's B1 and C1: a key in compose's `jwt-key`, a
  `/.well-known/service-jwks.json`, `InternalCallers` trusting job-service for
  `/internal/saved-counts`; the backend (identity) and job-service trusting
  `jobmatch-application-service` (`docker-compose.yml:58-62`, `:110-111`), and the harness the
  same.
- **The `shared` copies** application-service needs (`CurrentUserId`/`TokenSubject`,
  `PageResponse`, `PostingLookup`, the breaker config), and a test base of its own: Postgres with
  `apps_db`, `StubUpstream`, a LocalStack of its own (`SqsContainer`, as matching-service has
  since #269).
- **The deleted-user rule** (Day 39): `userId` comes from the token's `sub`, and
  application-service asks identity `GET /internal/users/{id}` before acting. It never reads
  `users`. Unlike matching-service's 422, it answers 404 "User not found", as
  `SavedJobController.java:114` does today and `contract/SessionWithoutAUserIT` pins.
- **The harness** (Day 40's hand-off): an entry in `support/Services`, a container from
  `jobmatch-application-service:harness` on the test Postgres, a gateway route with an upstream
  URL of its own. The container's consumer reads a queue of its own, subscribed to the topic by
  `support/EventBus`: on the shared `applications-user-deleted` it would take
  `UserDeletedRelayIT`'s messages (Day 27's break, 2 of 4 failed).
- **The relay in the harness's default context.** `application-test.yaml:39-42` turns it off,
  so after the cutover nothing would publish `user.deleted` for `AccountDeletionIT`. It goes on
  in the default context; `events/UserDeletedOutboxIT`, which counts the unpublished row, moves
  to a context with the relay off. `support/` and test configuration only.
- **`contract/AccountDeletionIT`, the one approved edit** (the maintainer, 2026-10-01, on the
  Phase 5 audit): after the move it reads a table that has left `project_db`, and deletion now
  arrives later through SQS. The edit changes only where and when rows are counted:
  `savedJobsOf` reads `apps_db` through a `support/` helper and waits up to 10 s for the expected
  count; `leavesEveryoneElsesSavedJobsAlone` first waits for the deleted user's rows to reach zero
  (Day 26's break showed it passes when nothing is deleted). Its three tests and their assertions
  stay. The class Javadoc's "Day 27 replaces the key" is corrected in the same edit (Day 27's
  Notes). No annotation is added: if the relay design above needs one, stop and ask.
- **`contract/JobSavedCountIT` stays unedited, through a bridge** (the maintainer, 2026-10-03):
  it inserts into `saved_jobs` through `jdbc()`, which is `project_db` (`:89`, `:97`). From the
  switch on, `support/` replaces the harness's `applications.saved_jobs` with a `postgres_fdw`
  foreign table over `apps_db`, with `job_state`'s default, since the insert omits it. Harness
  only; nothing in `main/` sees it. Criterion 5 is therefore checked on a migrated database, not
  through the harness.
- **The tests the move breaks**, each moved, given a `support/` `apps_db` helper, or deleted, as
  Day 21 listed them: `BackendApplicationTests:34`, `database/ModuleConnectionsIT:45,54,65`,
  `ModuleMigrationsIT`, `CircuitBreakerIT:117`, `PostingLookupUnavailableIT:102`,
  `LongSavedListIT:63`, `SavedCountsIT:180`, `matching/InternalCallsObservedIT:86`,
  `SavedJobHydrationQueriesIT:40`, `events/UserDeletedOutboxIT:108` (its "and their saved jobs"
  half goes with the key), `events/ApplicationsUserDeletedConsumerIT` (to the service, criterion
  4), `ModuleBoundariesTest`, `PostgresContainer`'s `MODULE_SCHEMAS`/`CONNECTED_SCHEMAS`.
- `queries/JobSavedCountQueriesIT` stays in the harness: it guards job-service's batching (one
  statement per search page at 1, 20 and 100), which a test inside application-service cannot
  see. It counts with `StatementCounter.appsStatementsMentioning("saved_jobs")`, as Day 20's
  `SavedJobHydrationQueriesIT` counts in `jobs_db`.
- **Compose:** at the switch, the backend loses `EVENTS_USER_DELETED_QUEUE_URL`
  (`docker-compose.yml:80-84`) and its consumer goes off. Two consumers on one queue would each
  take about half the deletions, and the backend's would delete in the wrong database.
- **The build:** `app/pom.xml` gets `software.amazon.awssdk:sqs` at test scope when the
  `applications` dependency (`:30`) leaves; `support/EventBus` has it only through
  `applications/pom.xml:54-58` today. CI: `application-service` in `backend-ci-cd.yaml`'s path
  filter (`:11-13`) and `changes` regex, and its image built beside `:76-80`.
- **Docs:** `backend/docs/schema.md:25`, `saving-tracking.md:35` (the key's DDL),
  `privacy-data.md`, `configuration.md`, `api.md`, `backend/README.md`, `backend/.env.example:14`.

## Out of scope
- Replacing the existence call with an event-fed record — decided against on Day 27.
- identity-service and `identity_db` — Day 28.
- `jobs_user`'s CONNECT on `project_db` (Day 20's hand-off, "Day 25 or 28 decides") — the Day 28
  review: this day changes only `applications_user`'s.

## Tracks

| Track | Owner | Work | Estimate |
|---|---|---|---|
| 0 | | The harness creates `apps_db` with its grants; `db-init/30-apps-db.sh`, `db-setup.py`; `StatementCounter.appsStatementsMentioning`; the relay on in the default context, `UserDeletedOutboxIT` in a relay-off one | 250–350 |
| D | | Gateway: `application-service-url` and the route, defaulting to the backend; its tests | 100–200 |
| A1 | | Skeleton: pom, application, `application.yaml`, Flyway on `apps_db`, tracing, actuator; the user-token chain with `TokenSubject`; the identity existence client | 300–380 |
| A2 | | Dockerfile and `application-service-ci-cd.yaml` with the gate | 100–150 |
| B1 | | Service identity: key, `jwt-key`, key set, `InternalCallers`, the `/internal/**` chain | 300–380 |
| B2 | | The `shared` copies and the breaker config | 250–350 |
| B3 | | The service's test base: Postgres with `apps_db`, `StubUpstream`, `SqsContainer`, self-tests | 250–380 |
| C1 | | Trust lists (backend, job-service, harness); compose adds application-service unrouted | 150–250 |
| C2 | | The harness container, unrouted, on a queue of its own; CI builds the image, path filters | 250–380 |
| E1 | | The move and the switch: `git mv`, the `Services` entry, the gateway's URL, `AccountDeletionIT`'s edit, the `JobSavedCountIT` bridge, the broken tests above | 350–450 |
| E2 | | Compose switch: the route, `INTERNAL_APPLICATIONS_URL`, the backend's consumer off; `copy-saved-jobs.py` | 100–200 |
| F | | V16 drops the table and the key; `applications_user`'s CONNECT on `project_db`; `sqs` at test scope in `app`; dead config; boundaries rules; docs | 200–350 |

Seam first: no track removes the monolith's applications code before application-service
serves (`plan.md`, "Course correction"). Tracks run in the order of the table. E1 may need
`Oversized:` as Day 21's E1a did; if it can be split along a line where each half stands, it is.

## Acceptance criteria
- [x] **hold** — Day 04's saved-jobs tests and Day 03's `savedCount` test pass unedited after
      every track: `SavedJobsIT` 16, `SavedJobStatsIT` 6, `SavedJobHydrationIT` 11,
      `JobSavedCountIT` 6. Broken on purpose in this spec change: the update kept the old
      `job_state`; `SavedJobsIT` 2 of 16 failed, `expected: "APPLIED" but was: "SAVED"`.
      16, 6, 11 and 6, or the whole backend suite green, after #279, #280, #285–#287,
      #291–#297, #301 and #302, and at the close on 9b4fe1d. Not run after #281–#284,
      #288–#290, #298 and #305: application-service only, nothing the harness runs. Seen red again in E1a
      (#296): the relay's counts route sent back to the monolith, `JobSavedCountIT` 6 of 6,
      `expected: 3 but was: 0`.
- [x] **hold** — `contract/AccountDeletionIT` 3 of 3, its diff from `main` at e4be87e the
      approved edit and nothing else. Broken on purpose on Day 26 (`DELETE … AND false`:
      `expected: 0L but was: 2L`); broken again after E1 by the container's consumer off.
      3 of 3 after every backend track and at the close; `git diff --stat e4be87e HEAD --
      backend/app/src/test/…/contract/` is `AccountDeletionIT.java` +13 −13, all in #296.
      Seen red in #296: the container's consumer off, 2 of 3, `expected: 0L but was: 1L`.
- [x] **hold** — `queries/CurrentUserQueriesIT` 2 of 2 unedited. Broken on purpose on Day 41:
      two existence calls per request, `expected 1 but was 2`; again on application-service.
      2 of 2 after every backend track and at the close. On application-service the break
      went to its own `CurrentUserIdTest`, not the IT (#293): two existence calls, 1 of 10,
      `expected: 1 but was: 2`.
- [x] **hold** — `contract/SessionWithoutAUserIT` 3 of 3 unedited; a deleted user's
      `/api/saved-jobs` calls answer 404 "User not found". Broken on purpose in this spec change:
      the controller's 404 made 422; 1 of 3 failed, `expected: 404 but was: 422`. Again in A1,
      on application-service's mapping of identity's 404.
      3 of 3 after every backend track and at the close. "Again in A1" moved with the
      existence client to B4 (#293): identity's 404 read as "exists", `CurrentUserIdTest`
      1 of 10, `expected: 404 but was: 200`.
- [x] **hold** — Day 27's consumer tests, 3 of 3, in application-service's module (`mvnw -f
      services/application-service/pom.xml verify`). Broken on purpose in this spec change:
      the delete's `WHERE user_id = ?` widened with `OR true`; 1 of 3 failed, `expected: 2L but
      was: 0L` (the other user's rows went). Again in E1, on the moved copy.
      #296: `UserDeletedConsumerTest` 3 of 3; 96 of 96 in the module at the close. Seen red in
      #296: the moved copy's `OR true`, 1 of 3, `expected: 2L but was: 0L`.
- [x] **hold** — `queries/JobSavedCountQueriesIT` 4 of 4: one `saved_jobs` statement in
      `apps_db` per search page at 1, 20 and 100. Broken on purpose (Day 08's): a per-posting
      loop reported 2, 21 and 25.
      4 of 4 after every backend track and at the close. #296 edited it to count in apps_db
      and ran no break after, so the close did: job-service's search asking once per posting
      (image rebuilt), 2 of 4, `expected: 1L` but 20 and 24 at sizes 20 and 100 (size 1
      cannot tell). Reverted, image rebuilt, 4 of 4.
- [x] **new** — `fk_saved_jobs_user` and `project_db`'s `applications.saved_jobs` are gone, by
      V16; `git log -- …/V13*` shows V13 unedited. On a compose database: `SELECT
      count(*) FROM pg_constraint WHERE conname='fk_saved_jobs_user'` → 0 and
      `to_regclass('applications.saved_jobs')` → null. Red today: 1, and not null.
      #301. At the close, compose `-p day25` on 9b4fe1d: `0` and `null`. `git log --
      …/V13*` ends at e7426c5 (Day 11): V13 unedited. Seen red in #301: V16 keeping the
      table, the bridge's setup `relation "saved_jobs" already exists`, 5 of 5 errored; keeping
      the enum, `savedJobsLeftProjectDbWithItsKey` 1 of 5.
- [x] **new** — `applications_user` cannot connect to `project_db`; it connects to `apps_db`.
      Red today: it connects (`ModuleConnectionsIT.eachModuleLogsInAsItsOwnRoleWithItsOwnSchema`).
      `jobs_db` already refuses it: a hold, broken in Track 0 by granting it CONNECT.
      #301: `applicationsCannotConnectToProjectDb`. At the close: `psql -U applications_user
      -d project_db` → `User does not have CONNECT privilege`; on `apps_db`,
      `applications_user`. Seen red in #301: the harness keeping PUBLIC's CONNECT, 1 of 8.
      The `jobs_db` hold broken in #279: CONNECT granted, `JobsDatabaseIT` 1 of 5.
- [x] **hold** — With `application-service` stopped, job search answers 200 with
      `savedCount: 0` for a job that has a saved row, and `1` with it up (the Verify block).
      Broken on purpose in E2: job-service's counts fallback made to throw; the search answers
      500.
      #297, and the close on 9b4fe1d (`-p day25`): `1 200` up, `0 200` stopped. Seen red
      in #297: job-service's fallback made to `throw e`, the search answered 500.
- [x] **new** — `scripts/copy-saved-jobs.py` run twice leaves the same `apps_db` row count as
      once, equal to `project_db`'s. Red today: no script, no `apps_db`.
      #297, before V16: run 1 copied 2, run 2 copied 0, same count. `apps_db` held 4 rows to
      `project_db`'s 3: all three, plus the Verify block's row, which `project_db` never had;
      "equal" holds for `project_db`'s rows. The count cannot see an overwrite: `DO UPDATE`
      in its place changed a row's state and kept the count (#297), so the row's state was
      checked too. After V16 (close): exit 0, "nothing to copy".

## Verify
```bash
# Not on the maintainer's project: publishes 8080, 5432 and 3000, and `down -v` removes the
# `finalproject` network. Check nothing else is up first.
docker build -t jobmatch-job-service:harness services/job-service
docker build -t jobmatch-matching-service:harness services/matching-service
docker build -t jobmatch-application-service:harness services/application-service
(cd backend && rm -rf */target/surefire-reports && ./mvnw clean verify && ./mvnw -B checkstyle:check)
./backend/mvnw -f services/application-service/pom.xml clean verify checkstyle:check

docker compose -p day25 --env-file .env.example up -d --build --wait
for f in schema seed; do
  docker compose -p day25 exec -T db psql -U admin -d jobs_db -v ON_ERROR_STOP=1 \
    -c "SET ROLE analytics_user" -f - < backend/app/src/test/resources/fixtures/analytics-$f.sql
done
ID=$(curl -s 'localhost:8080/api/jobs?size=1' | jq -r '.content[0].postingId')
docker compose -p day25 exec -T db psql -U admin -d apps_db -v ON_ERROR_STOP=1 \
  -c "INSERT INTO applications.saved_jobs (user_id, posting_id) VALUES (gen_random_uuid(), '$ID')"
count() { curl -s -w ' %{http_code}' 'localhost:8080/api/jobs?size=1' | sed 's/.*"savedCount":\([0-9]*\).* \([0-9]*\)$/\1 \2/'; }
count                                                                   # 1 200
docker compose -p day25 ps --status running --services | grep -x application-service   # listed
docker compose -p day25 stop application-service
count                                                                   # 0 200
docker compose -p day25 down -v
```

## Notes
- Decided by reading (the old Notes' question): jobs ↔ applications cannot cascade. job-service
  calls counts only on search and detail (`JobService.java:33,50`), not on the batch route;
  applications' lookup fallback returns `Map.of()` (`PostingLookupClient.java:54-81`). Each path
  is one hop.
- The bridge (`postgres_fdw` in the harness) was tried by the auditor in a throwaway
  `postgres:18.4-alpine`: an unqualified insert under `search_path=applications` landed in
  `apps_db`. V16 then runs on the harness before `support/` creates the foreign table, since
  `DROP TABLE` refuses a foreign table.
- **Defect** — found: auditor · cause: spec · the criteria were untagged, five without a command;
  the Verify block could not fail (no mart on a fresh volume: 500; `stop` of a missing service
  exits 0; `savedCount` 0 either way). Fixed in this spec change.
- **Defect** — found: auditor · cause: spec · "Day 03's `savedCount` test passes unedited"
  could not hold: it inserts into `project_db`. Fixed by the bridge, this spec change.
- **Defect** — found: auditor · cause: spec · the approved `AccountDeletionIT` edit could not pass
  with the relay and consumer off in its context. Fixed by the relay and queue design above.
- **Defect** — found: auditor · cause: spec · three tracks for an extraction Day 21 did in
  fifteen PRs; no service credential, trust lists, shared copies or test base; the compose line
  was `:95`, not `:108`. Fixed in this spec change.

- **Track order: 0 (#279), D (#280), A1a–c (#281–#283), A2 (#284), B1a–c (#285–#287),
  B2a–c (#288–#290), B3a–b (#291, #292), B4 (#293), C1 (#294), C2 (#295), E1a (#296), E2
  (#297), E1b (#298), F1 (#301), F2 (#302), E1c (#305).** Estimated 15 PRs; took 25 with the
  spec change and this close: 23 tracks for the spec's 12.
- **Departures, each recorded in its PR:**
  - **Six tracks split at the 400-line gate:** A1, B1, B2 and E1 in three, B3 and F in two.
    E1a (#296) took `Oversized:` at 1,225 changed lines, 914 of them deletions, as the spec
    allowed.
  - **B4 is A1's existence client, moved after B1** (#293): it needs the service's token
    chain. The maintainer agreed it on 2026-10-03.
  - **E2 ran before E1b** (#297, #298): compose sent saved jobs to a backend that no longer
    served them.
  - **`UserDeletedOutboxIT` pauses the bus** (`EventBus.pause()`, #279) instead of a relay-off
    context. The maintainer agreed it.
  - **`sqs` at test scope in `app` landed in E1a** (#296), not F: the move removed it.
  - **V16 also drops `job_state`,** and carries the deleted applications V1's revokes (#301).
    F2 also deleted `shared.applications.SavedJobCounts` (#302).
  - **Harness tests edited outside `contract/`:** `RefreshTokenGrantsIT`, `ModuleBoundariesTest`,
    `ModuleConnectionsIT`, `InternalClientsIT`, `JobServiceHarnessIT`, `JobServiceObservedIT`,
    `BackendApplicationTests`. The spec named most of them; `RefreshTokenGrantsIT` it did not
    (below).
- **Defect** — found: close · cause: process · **E1c never had a PR.** #298 announced it;
  spec-drift read the day as done once F2 merged, since no branch named it. For a day,
  `/internal/saved-counts` and the internal-call metrics had no test on application-service.
  The evidence-gathering for this close found it; the maintainer chose to land it first
  (#305).
- **Defect** — found: review (#305) · cause: implementation · the implementer's E1c dropped
  three of the original's checks (one entry per distinct id, the in-process comparison, both
  "without a query") and reported no departures. Restored before the breaks.
- **Defect** — found: Track E1c · cause: process · my own: in #305, two breaks run together hid the
  cap break (without the zero entries `countsFor` never queries). Run alone, it failed.
- **Defect** — found: Track E1a · cause: implementation · the first `savedJobsOf` let the
  consumer-off break pass. Fixed in #296 before the break was recorded.
- **Defect** — found: Track F1 · cause: spec · `RefreshTokenGrantsIT` (Day 12) checked
  identity's refusal as `applications_user`, now refused at login; no spec line named it. 2 of
  3 red on the first full run; it checks as `matching_user` since #301. `db-setup.py` first
  revoked only PUBLIC's CONNECT, not the role's own grant, caught in review.
- **Defect** — found: Track E2 · cause: implementation · two bugs in `copy-saved-jobs.py` that
  only a real database showed (a named cursor on an autocommit connection, `executemany` on a
  connection). The first run rolled back; fixed in #297.
- **Defect** — found: Track E2 · cause: spec · criterion 10's row count cannot see an
  overwrite (above).
- **Defect** — found: review · cause: implementation · tabs where the module uses spaces (#290,
  #298), a monolith Javadoc edited (#293), "no departures" reported where there were
  (#281, #282, #296).
- **Defect** — found: Track A1b · cause: process · a literal test password; GitGuardian
  flagged it, the branch was squashed and force-pushed (#282).
- **Defect** — found: Tracks B1a, B1c, F2 · cause: process · work lost from the working tree
  (`git checkout` over unstaged work; doc edits that vanished), redone and the breaks rerun
  (#285, #287, #302).
- **The close's Verify, on 9b4fe1d:** backend 435 tests, 0 failures, 0 errors, 1 skipped,
  checkstyle clean (run on 89406b8; #305 changed nothing under `backend/`);
  application-service 96 of 96, checkstyle clean. Compose `-p day25`, `.env.example`: every
  service healthy; `1 200` up, `0 200` with application-service stopped; the V16 and CONNECT
  checks above. `down -v` after; no other container was running.
- **Hand-offs this day leaves:**
  - **Day 28:** the `applications` role and schema left in `project_db` (V13 raises without
    them); `jobs_user`'s CONNECT on `project_db` and `DB_JOBS_USER`; the DLQ retention and
    Terraform for the topic, queues and `apps_db`.
  - **Deployment:** run `copy-saved-jobs.py` after the switch and before V16; existing
    volumes rerun `db-setup.py` to lose `applications_user`'s CONNECT (#301).
  - **The maintainer:** "Application Service gate" in the branch protection's required checks
    (#284).
