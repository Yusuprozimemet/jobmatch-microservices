# Day 20 — job-service gets its own database

**Phase:** 3 · **Depends on:** Day 17 · **Expected PRs:** 5

This is the last of Phase 3's days (18, 19, 17, 20). It was rewritten on Day 20 from the
provisional draft, against `main` at c5866e7, after the spec-auditor's read and with the
maintainer's three choices below. The draft predated Days 17–19 and 38–40. It claimed a
job-service test harness that does not exist, and a pipeline with no tests (`data/tests/publishing`
has 9). It also said "all tests pass unedited", which cannot hold once the mart leaves the database
the harness reads.

## Goal
The mart (`analytics`, and `analytics_dev` beside it) lives in `jobs_db`, which only job-service
(`jobs_user`) and the publish roles can connect to. `project_db` has no analytics schema. The
harness, compose and `db-setup.py` all set it up the same way, and the publish's swap is tested
against a real Postgres.

## In scope
- **The maintainer's choices, made on Day 20:**
  1. **The Day 01 self-tests are retargeted, not rewritten.** `TestHarnessTest` and
     `SecondHarnessTest` keep their assertions. Their mart queries go through a new `support/`
     helper onto `jobs_db`. This is an approved departure from "Day 1–4 tests unedited", and the
     closing PR records it in the README's measurement table. `contract/` stays unedited.
  2. **`project_db` keeps no copy.** The harness, compose and a fresh `db-setup.py` create no
     `analytics` schema there. A stale copy would let a monolith path that still reads the mart
     pass without anyone noticing. Rollback republishes into `project_db` (Track C).
  3. **`analytics_dev` moves with `analytics`.** Both DAGs publish to `jobs_db` through the same
     `BACKEND_PG_DB` Airflow Variable.
- **`jobs_db` everywhere, one shape:** `REVOKE CONNECT … FROM PUBLIC`. `CONNECT` goes to
  `jobs_user`, `analytics_user` and `analytics_dev_user` only. `analytics` is owned by
  `analytics_user` and `analytics_dev` by `analytics_dev_user`. `ALTER DEFAULT PRIVILEGES FOR ROLE
  analytics_user IN SCHEMA analytics GRANT SELECT ON TABLES TO jobs_user`: the publish drops and
  recreates the table, and without this `jobs_user` loses its grant on the first swap.
  - **The mart tables are created by `analytics_user`**, because only tables it creates get the
    default grant to `jobs_user`. `analytics_user` has no `CREATE` on the database, and Postgres
    refuses even `CREATE SCHEMA IF NOT EXISTS` on an existing schema without it (the auditor's
    run: `permission denied for database jobs_db`). So the admin creates the schema, owned by
    `analytics_user`, and `fixtures/analytics-schema.sql` loses its two `CREATE SCHEMA` lines.
    The harness is the fixture's only reader. The harness creates `app` in `project_db` itself.
    The harness and Verify both run the fixture after `SET ROLE analytics_user`.
  - **Harness** (`PostgresContainer`): creates `jobs_db` in the same container, with an
    `analytics_user` role, and the mart as above. A second data source and a
    `jobsJdbc()` helper go onto it. `TestDatabase.reset()` truncates and reseeds the mart there.
    `aPosting()` (`IntegrationTest`), `PostingBuilder`, `ShortlistFixture` and `LongSavedListIT`
    write there. `JobService` sets `DB_NAME=jobs_db`.
  - **`StatementCounter`** counts per database: the existing calls keep `project_db`, and a
    `jobs_db` variant is added. `pg_stat_statements` is cluster-wide, so this is a `dbid` filter.
  - **Compose:** `scripts/db-init/20-jobs-db.sh` creates `analytics_user`, `analytics_dev_user`
    and `jobs_db` on an empty volume. It sorts after `module-roles.sh`, which creates `jobs_user`,
    and `module-roles.sh` is renamed `10-module-roles.sh` so the order is explicit.
    job-service gets `DB_NAME: ${JOBS_DB_NAME:-jobs_db}`, and its `application.yaml` default
    becomes `jobs_db`. `module-roles.sh` drops its `project_db.analytics` lines (`:28-33`). A volume
    created earlier gets `jobs_db` from the same script, run by hand. The README says how;
    never with `down -v`.
  - **`db-setup.py`** creates `jobs_db` with the two analytics schemas and the grants above,
    and stops creating them in `project_db` on a fresh setup. The script "never changes existing
    state" (`db-setup.py:22-24`), so an existing `project_db`'s analytics schemas are dropped by
    the runbook (Track C), not by the script.
- **The tests the move touches, and what each becomes** (none is in `contract/`):

  | Test | Today | After |
  |---|---|---|
  | `TestHarnessTest`, `SecondHarnessTest` | read the mart from `project_db` | same assertions, via `jobsJdbc()` (choice 1) |
  | `SavedJobHydrationQueriesIT` | 1 statement on `fct_postings` in `project_db`; Javadoc says "expires Day 20" | counts in `jobs_db`; still 1, and the expiry line goes. It guards the batch against becoming a loop, which is still worth guarding |
  | `PostingBatchIT:113,130` | `isZero()` in `project_db` | counts in `jobs_db`. In `project_db` it would pass whatever job-service ran |
  | `ModuleConnectionsIT.jobServiceReadsTheMartAsJobsUser` | no `dbid` filter, so it would stay green in either database | filters to `jobs_db`'s `dbid` |
  | `ModuleConnectionsIT.jobsCannotWriteTheMart` | `jobs` pool on `project_db` | a `jobs` pool on `jobs_db` |
  | `ModuleConnectionsIT.noModuleCanReadAnothersSchema[jobs, …]` | the same `jobs` pool, reading `identity.user_credentials` | keeps a `jobs_user` pool on `project_db`. On `jobs_db` the table does not exist, and "permission denied for schema identity" becomes "relation does not exist" |
  | `BackendApplicationTests.martTablesAreOutsideTheAppSchema` | 3 tables in `project_db.analytics` | 3 tables in `jobs_db.analytics`, none in `project_db` (a `new` criterion below) |
- **The publish** (`data/`): a real-Postgres test of `sync.publish` into `jobs_db`, run in
  `data-ci-cd.yaml`'s `lint-and-test` job against a `postgres:18.4-alpine` service container.
  The database name lives in configuration, so the code does not change: `sync.py` reads
  `BACKEND_PG_DB`, which the DAGs take from an Airflow Variable (`pipeline_dag.py:308`,
  `pipeline_dag_dev.py:124`). Setting that Variable in Airflow is the maintainer's step at cutover.
  The runbook records it. These docs and defaults change: `data/.env.example:94`, `data/README.md:213,591`,
  `backend/docs/configuration.md:99,125`, `data/optional/streamlit/app.py`. So does
  `read_backend_table`'s docstring (`sync.py:62-74`), which assumes the mart and `app` share a
  database: they no longer do.
- **The runbook** `docs/runbooks/jobs-db.md`: cutover (run `db-setup.py`, set the Variable,
  publish, point job-service's `DB_NAME`, drop the analytics schemas from `project_db`) and
  rollback (`DB_NAME` back to `project_db`, recreate `analytics` there with `jobs_user`'s read,
  set the Variable back, republish). The rollback is rehearsed once in a separate compose
  project.

## Out of scope
- Separate Postgres servers. One server, several databases. Day 32's Terraform decides.
- A test harness inside job-service's own module. Job-service is still tested from the
  backend's harness (Day 17, choice 2).
- Running the cutover on a production database. job-service is not deployed anywhere yet
  (`job-service-ci-cd.yaml` has no deploy job). The runbook is written and rehearsed today, and
  Day 37 runs it.
- `saved_jobs` leaving `project_db` (`JobSavedCountQueriesIT` expires then): Day 25.

## Tracks

| Track | Owner | Work | Estimate |
|---|---|---|---|
| 0a | | `StatementCounter` per database; the `jobs_db` data source, `jobsJdbc()` and the second `jobs` pool, still onto `project_db`; the tests above retargeted through them. Nothing moves yet | 150–250 |
| 0b | | The harness creates `jobs_db` with its grants; the mart, reset, builders and `JobService` move there; `project_db` gets no analytics schema; the fixture's `CREATE SCHEMA` lines; `JobsDatabaseIT`; `BackendApplicationTests`' "none in `project_db`" half | 250–350 |
| A | | Compose (`db-init` script, job-service `DB_NAME`, `.env.example`), job-service's default, `db-setup.py`; README's note for existing volumes | 200–300 |
| B | | `data/`: the real-Postgres publish test and its CI service container; docs, defaults and the docstring above | 200–300 |
| C | | `docs/runbooks/jobs-db.md` and its rehearsal | 100–150 |

0a keeps every check green, with `jobsJdbc()` still pointing at `project_db`. The two `new`
harness criteria land in 0b. So 0b's move is a
change of URL, and each test's retarget has been seen passing before it. A and B need only 0b's
shape, not its code; C needs A and B. The estimates are guesses from the files listed. Day 17 ran
at almost 1.5× its rewritten estimate.

## Acceptance criteria
- [ ] **new** — Only job-service's and the publish's roles reach `jobs_db`. A new `JobsDatabaseIT`
      connects to `jobs_db` as `jobs_user` and as `analytics_user`, and is refused as
      `identity_user`, `applications_user` and `matching_user` (`permission denied for database
      "jobs_db"`, with the quotes). Red today: `jobs_db` does not exist, so the `jobs_user` connection fails. Broken
      on purpose once 0b lands: drop the `REVOKE CONNECT … FROM PUBLIC`, and `matching_user`
      connects. The auditor saw this in a throwaway container.
- [ ] **new** — `project_db` has no analytics schema, and `jobs_db` has the three mart tables.
      `BackendApplicationTests.martTablesAreOutsideTheAppSchema` asserts both. Red today:
      `project_db.analytics` has `fct_postings`, `fct_postings_cities` and `fct_postings_skills`.
- [ ] **new** — `jobs_user` can still read after a second publish. The publish test runs
      `sync.publish` twice into `jobs_db` as `analytics_user`, then reads as `jobs_user`.
      Red today: there is no `jobs_db` and no such test. Broken on purpose: drop the default
      privilege, and the read fails with "permission denied for table fct_postings".
- [ ] **hold** — The swap leaves no window. While `publish` sits between its drop and its rename,
      a second connection asking for the table with a short `lock_timeout` gets a lock timeout,
      not "relation does not exist". The same test uses a real Postgres. Today's 9 tests use a
      fake connection and stay green, the auditor found, with `autocommit=True` in `sync.py:108`.
      Broken on purpose: `autocommit=True`, and the new test fails with `UndefinedTable`.
- [ ] **hold** — The Day 1–4 `contract/` classes pass unedited, direct and through the gateway.
      Checked with `git diff <the spec-change PR's merge> HEAD -- backend/app/src/test/java/**/contract/`
      (empty), as Day 19 did, and CI's gateway run. Broken on purpose: point `aPosting()` back at
      `project_db` after 0b, and `JobSearchIT`, `MatchRankingIT` and `SavedJobHydrationIT` error
      in setup: `project_db` has no `analytics` any more.
- [ ] **hold** — The statement counts do not move. `SavedJobHydrationQueriesIT` sees 1 on
      `fct_postings` per page and 0 for an empty list, now in `jobs_db`. `PostingBatchIT` sees 0
      for an empty list and for 501 ids. Broken on purpose, in job-service, so each break needs
      `docker build -t jobmatch-job-service:harness services/job-service` before the run, or a stale
      image passes. First, a per-id loop in `JobsDirectory.byIds`: the hydration test must report
      more than 1. Second, remove its empty-list short-circuit (`JobsDirectory.java:50`):
      `PostingBatchIT`'s empty-list check must report 1.
- [ ] **new** — `db-setup.py` on an empty `postgres:18.4-alpine` creates `jobs_db` with the grants
      above and no analytics schema in `project_db`. Checked with `psql` as each module role
      (commands in the PR). Red today: `\l` lists no `jobs_db`.
- [ ] **new** — Compose serves job search from `jobs_db` (Verify below): `curl
      localhost:8080/api/jobs` answers 200 with the seeded postings, and `project_db` has no
      `analytics`. Red today: compose creates no `jobs_db`.
- [ ] **new** — The rollback has been run once. A reader can follow `docs/runbooks/jobs-db.md`
      from its commands alone. In a separate compose project, its rollback puts job search back
      on `project_db` (`/api/jobs` 200 with `DB_NAME=project_db`), and its cutover moves it back.
      The PR pastes the output. Red today: the file does not exist.

## Verify
```bash
docker build -t jobmatch-job-service:harness services/job-service
rm -rf backend/*/target/surefire-reports
cd backend && ./mvnw clean verify && ./mvnw -B checkstyle:check && cd ..
# and the gateway run of the contract classes, as CI's gateway job runs it
(cd data && uv run pytest -q tests/publishing)   # needs a Postgres, see the test's docstring

# Compose, in its own project and volume. Never the maintainer's.
docker compose -p day20check --env-file .env.example up -d --build --wait db backend job-service api-gateway
docker compose -p day20check exec -T db psql -U admin -d jobs_db -v ON_ERROR_STOP=1 \
  -c "SET ROLE analytics_user" -f - < backend/app/src/test/resources/fixtures/analytics-schema.sql
docker compose -p day20check exec -T db psql -U admin -d jobs_db -v ON_ERROR_STOP=1 \
  -c "SET ROLE analytics_user" -f - < backend/app/src/test/resources/fixtures/analytics-seed.sql
curl -s -o /dev/null -w '%{http_code}\n' localhost:8080/api/jobs          # 200
docker compose -p day20check exec -T db psql -U admin -d project_db -Atc \
  "SELECT count(*) FROM pg_namespace WHERE nspname = 'analytics'"         # 0
docker compose -p day20check down -v
```
`admin` is `.env.example`'s `POSTGRES_USER`; the gateway publishes 8080. Track A pins the exact
seed commands, and the closing PR records what ran.

## Notes
- **End of Phase 3.** One service is fully independent: own image, own API, own database. A
  stopping point: the plan-auditor runs on Phase 3 and Phase 4 when the day closes.
- **Rewritten on Day 20** from the spec-auditor's 12 findings. Fixed: no `new`/`hold` tags or
  commands; "all tests unedited" (false for six test classes, two of them Day 01
  self-tests); the job-service harness that does not exist; "the monolith's role cannot read the
  mart" (already true in the harness and compose for the module roles; `app_user` is a
  superuser, so the real gap is `CONNECT`); "no test coverage" of the pipeline (9 tests, on a
  fake connection); the Airflow *connection* (it is a Variable); `Depends on: Day 19` (the order
  is 18, 19, 17, 20); `Expected PRs: 3`.
- **The auditor was wrong about one test, and the rewrite corrects it.**
  `jobServiceReadsTheMartAsJobsUser` would not fail after the move. Without a `dbid` filter it
  sees `jobs_db`'s statements too, so it would pass for the wrong reason.
- **The auditor's second read** confirmed the swap test's design (intact: `LockNotAvailable`;
  `autocommit=True`: `UndefinedTable`) and the default-privilege break, in a throwaway Postgres.
  It found 4 must-fix and 6 should-fix issues, all fixed above: the fixture cannot run as
  `analytics_user`; the service is `api-gateway`; a seventh affected test case; the breaks in
  job-service need the image rebuilt.
- **`jobs_user` keeps `CONNECT` on `project_db`.** It no longer needs it, but `db-setup.py`
  grants it to every role and the harness's `PUBLIC` default allows it. Taking it away is not
  Day 20's. Day 25 or 28 decides, when `project_db` is split further.
- **Hand-offs received:** Day 17 (`SavedJobHydrationQueriesIT`'s expiry; fixtures and the
  missing job-service harness), Day 40 (`StatementCounter` per database; `jobs_db` in compose
  and the harness's seeding of it; "copied, not moved"). The last is superseded by choice 2: the
  monolith no longer reads the mart, so `aPosting()` writes where job-service reads, and nothing
  is copied.
- **The admin role is a superuser** in the harness (`app_user`) and in compose (`admin`), so no
  check can deny it `jobs_db`.
  In production it is not, and `db-setup.py` gives it no `CONNECT` there.
- A compose volume created before today keeps its `project_db.analytics` until someone drops it.
  The README's note says so. Nothing in compose reads it any more.
