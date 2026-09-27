# Runbook: job-service onto `jobs_db`, and back

Day 20 moved the mart (`analytics`, and `analytics_dev` beside it) out of `project_db` into
`jobs_db`, job-service's own database. Only `jobs_user`, `analytics_user` and `analytics_dev_user`
can connect to it. This runbook moves a running installation across (**cutover**) and, if job
search breaks, puts it back (**rollback**). Day 37 runs the cutover in production; the rollback
was rehearsed on Day 20 in a throwaway compose project (the Day 20 Track C PR has the output).

Each step says how to check it before going on. Nothing here deletes data except cutover step 4,
which drops a copy the pipeline can rebuild.

## Before you start

Define `pg`, which runs `psql` as the admin against one database. Use one of these:

```bash
# Production: the admin role db-setup.py ran as, from the same environment variables.
pg() { db=$1; shift; psql "host=$POSTGRES_HOST port=${POSTGRES_PORT:-5432} user=$POSTGRES_USER dbname=$db sslmode=require" -v ON_ERROR_STOP=1 "$@"; }

# Compose (a rehearsal: use your own project name, never the one holding your data).
pg() { db=$1; shift; docker compose -p "$PROJECT" exec -T db psql -U admin -d "$db" -v ON_ERROR_STOP=1 "$@"; }
```

Two checks are used throughout. Which database job-service is connected to:

```bash
pg postgres -Atc "SELECT DISTINCT datname FROM pg_stat_activity WHERE usename = 'jobs_user'"
```

and whether job search answers (`200`; a missing or unreadable mart gives `500`):

```bash
curl -s -o /dev/null -w '%{http_code}\n' "$GATEWAY/api/jobs"   # compose: GATEWAY=localhost:8080
```

## Cutover: `project_db` → `jobs_db`

**1. Create `jobs_db`.** In production, run `scripts/db-setup.py` (it creates `jobs_db`, the two
schemas and their grants, and changes nothing that exists). In compose, the database comes from
`scripts/db-init/20-jobs-db.sh`: an empty volume runs it by itself; an older one needs
`docker compose exec -T db sh -c 'sh /docker-entrypoint-initdb.d/20-jobs-db.sh'`. Check:

```bash
pg jobs_db -Atc "SELECT nspname, pg_get_userbyid(nspowner) FROM pg_namespace WHERE nspname LIKE 'analytics%' ORDER BY 1"
# analytics|analytics_user
# analytics_dev|analytics_dev_user
```

**2. Publish into it.** In Airflow, **Admin → Variables**, set `BACKEND_PG_DB` to `jobs_db`. Both
DAGs read it, so `analytics_dev` moves too. Trigger `final_project_pipeline` and wait for
`publish_to_backend` to succeed. (A rehearsal has no pipeline: load the test suite's mart as the
publish's role instead, with the loop below and `DB=jobs_db`.) Check that the three tables are
there:

```bash
pg jobs_db -Atc "SELECT count(*) FROM pg_tables WHERE schemaname = 'analytics'"   # 3
```

```bash
# The rehearsal's stand-in for a publish, run from the repository root.
for f in analytics-schema analytics-seed; do
  pg "$DB" -c "SET ROLE analytics_user" -f - < backend/app/src/test/resources/fixtures/$f.sql
done
```

**3. Point job-service at it.** Set job-service's `DB_NAME` to `jobs_db` and restart it. In
compose, `DB_NAME` comes from `JOBS_DB_NAME` (default `jobs_db`):

```bash
JOBS_DB_NAME=jobs_db docker compose -p "$PROJECT" up -d --no-deps --wait job-service
```

Check: the `jobs_user` query above prints `jobs_db`, and `/api/jobs` answers `200`.

**4. Drop the old copy.** Only once step 3's checks pass. A stale copy would let anything that
still reads the mart from `project_db` go on working unnoticed:

```bash
pg project_db -c "DROP SCHEMA IF EXISTS analytics, analytics_dev CASCADE"
```

Check: `/api/jobs` still answers `200`, and

```bash
pg project_db -Atc "SELECT count(*) FROM pg_namespace WHERE nspname LIKE 'analytics%'"   # 0
```

## Rollback: `jobs_db` → `project_db`

If cutover step 4 has not run, `project_db` still has its mart: skip to step 3.

**1. Recreate the schemas in `project_db`,** in the shape `jobs_db` has. The publish drops and
recreates each table, so `jobs_user`'s read has to be a default privilege of the role that
creates them, `analytics_user`; a plain `GRANT` would be gone after the first publish.

```bash
pg project_db <<'SQL'
CREATE SCHEMA analytics AUTHORIZATION analytics_user;
CREATE SCHEMA analytics_dev AUTHORIZATION analytics_dev_user;
GRANT USAGE ON SCHEMA analytics TO jobs_user;
ALTER DEFAULT PRIVILEGES FOR ROLE analytics_user IN SCHEMA analytics GRANT SELECT ON TABLES TO jobs_user;
SQL
```

**2. Publish into it.** Set the Airflow Variable `BACKEND_PG_DB` back to `project_db` and trigger
`final_project_pipeline` (a rehearsal: the loop above with `DB=project_db`). Check:

```bash
pg project_db -Atc "SELECT count(*) FROM pg_tables WHERE schemaname = 'analytics'"   # 3
```

**3. Point job-service back.** Set its `DB_NAME` to `project_db` and restart it:

```bash
JOBS_DB_NAME=project_db docker compose -p "$PROJECT" up -d --no-deps --wait job-service
```

Check: the `jobs_user` query prints `project_db`, and `/api/jobs` answers `200`.

Leave `jobs_db` as it is: nothing reads it, and the next cutover starts from its step 2.

## What goes wrong

- **`/api/jobs` answers 500 after a restart.** Look in job-service's log. `relation
  "analytics.fct_postings" does not exist`: the database it now points at has not been published
  to (step 2). `permission denied for schema analytics`: rollback step 1 ran without its `GRANT
  USAGE` (the Day 20 rehearsal broke it this way on purpose). `permission denied for table
  fct_postings`: the tables were created by a role
  other than `analytics_user`, so the default privilege did not apply; publish again as
  `analytics_user`.
- **`permission denied for database "jobs_db"`.** Only the three roles above may connect. Any
  other role is refused on purpose.
- **The Variable was not set back.** After a rollback the next scheduled run publishes into
  whichever database `BACKEND_PG_DB` names; if that is still `jobs_db`, `project_db`'s mart goes
  stale without any error.
