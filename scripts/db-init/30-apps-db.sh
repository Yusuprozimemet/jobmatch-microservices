#!/bin/sh
# application-service's own database (Day 25): apps_db, holding saved_jobs, for compose. The
# counterpart of scripts/db-setup.py, and the shape the test harness gives it.
#
# Postgres runs this once, on an empty volume, after 10-module-roles.sh has created
# applications_user. A volume created before Day 25 never ran it; after `docker compose up -d db`,
# run it once by hand:
#
#   docker compose exec -T db sh -c 'sh /docker-entrypoint-initdb.d/30-apps-db.sh'
#
# (quoted, so Git Bash on Windows does not rewrite the path).
#
# Only applications_user connects. The admin creates the schema: applications_user has no CREATE
# on the database.
set -eu

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<'SQL'
CREATE DATABASE apps_db;
REVOKE CONNECT ON DATABASE apps_db FROM PUBLIC;
GRANT CONNECT ON DATABASE apps_db TO applications_user;
SQL

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname apps_db <<'SQL'
CREATE SCHEMA applications AUTHORIZATION applications_user;
SQL
