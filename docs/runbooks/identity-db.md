# Runbook: `project_db` renamed `identity_db`, and back

Day 28 made what was left of the monolith identity-service, and its database `identity_db`. A
fresh setup gets the new name by itself (`.env.example`, `scripts/db-setup.py`,
`scripts/db-init/`). An installation set up before Day 28 still has `project_db`, and is renamed
in place with this runbook. No script runs it.

A rename keeps everything inside the database: schemas, tables, Flyway's history, roles' CONNECT
grants and default privileges. Nothing is copied and nothing is deleted.

**Rename before you re-run `scripts/db-setup.py`.** The script creates `identity_db` when it is
missing, so run against an unrenamed installation it makes an empty one beside `project_db`. If
that has happened, the new one is empty: check, then drop it and rename as below.

```bash
pg identity_db -Atc "SELECT count(*) FROM pg_tables WHERE schemaname IN ('app', 'identity')"   # 0
pg postgres -c "DROP DATABASE identity_db"
```

## Before you start

Define `pg`, which runs `psql` as the admin against one database. Use one of these:

```bash
# Production: the admin role db-setup.py ran as, from the same environment variables.
pg() { db=$1; shift; psql "host=$POSTGRES_HOST port=${POSTGRES_PORT:-5432} user=$POSTGRES_USER dbname=$db sslmode=require" -v ON_ERROR_STOP=1 "$@"; }

# Compose: your project's name (the directory's, unless you passed -p).
pg() { db=$1; shift; docker compose -p "$PROJECT" exec -T db psql -U admin -d "$db" -v ON_ERROR_STOP=1 "$@"; }
```

Only identity-service connects to this database. job-service, application-service and
matching-service have their own (`jobs_db`, `apps_db`, DynamoDB), and the pipeline publishes to
`jobs_db`. Which databases exist, and who is connected to the old one:

```bash
pg postgres -Atc "SELECT datname FROM pg_database WHERE datname IN ('project_db', 'identity_db')"
pg postgres -Atc "SELECT usename, count(*) FROM pg_stat_activity WHERE datname = 'project_db' GROUP BY 1"
```

## Rename: `project_db` → `identity_db`

**1. Stop identity-service.** Postgres will not rename a database anyone is connected to. In
compose:

```bash
docker compose -p "$PROJECT" stop identity-service
```

The second query above must now print nothing. If a session is left (a `psql` someone forgot),
end it: `pg postgres -c "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname =
'project_db'"`.

**2. Rename it,** connected to `postgres`, not to the database being renamed:

```bash
pg postgres -c "ALTER DATABASE project_db RENAME TO identity_db"
pg postgres -Atc "SELECT datname FROM pg_database WHERE datname IN ('project_db', 'identity_db')"   # identity_db
```

**3. Point identity-service at it.** Set its `DB_NAME` to `identity_db`. In compose, `DB_NAME`
comes from `POSTGRES_DB` in your `.env`: change `POSTGRES_DB=project_db` to
`POSTGRES_DB=identity_db` there, then start everything again (the database's healthcheck reads
the same variable):

```bash
docker compose -p "$PROJECT" up -d --wait
pg postgres -Atc "SELECT DISTINCT datname FROM pg_stat_activity WHERE usename = 'identity_user'"   # identity_db
```

The check can print nothing for a few seconds after the service reports healthy, before its
connection pools fill; run it again.

**4. Check sign-in.** Log in through the gateway, or ask for the key set: `200` means
identity-service is up on the renamed database.

```bash
curl -s -o /dev/null -w '%{http_code}\n' "$GATEWAY/.well-known/jwks.json"   # compose: GATEWAY=localhost:8080
```

## Rollback: `identity_db` → `project_db`

The same steps the other way: stop identity-service, `ALTER DATABASE identity_db RENAME TO
project_db`, set `DB_NAME` (in compose, `POSTGRES_DB`) back to `project_db`, start it, check.
Nothing was copied or dropped, so nothing needs restoring.
