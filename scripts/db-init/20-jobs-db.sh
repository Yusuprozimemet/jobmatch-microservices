#!/bin/sh
# job-service's own database (Day 20): jobs_db, holding the marts, for compose. The counterpart of
# scripts/db-setup.py, and the shape the test harness gives it.
#
# Postgres runs this once, on an empty volume, after 10-module-roles.sh has created jobs_user. A
# volume created before Day 20 never ran it; after `docker compose up -d db` (so the container has
# the analytics passwords), run it once by hand:
#
#   docker compose exec -T db sh -c 'sh /docker-entrypoint-initdb.d/20-jobs-db.sh'
#
# (quoted, so Git Bash on Windows does not rewrite the path).
#
# Only jobs_user and the publish's two roles connect. analytics_user owns analytics and creates its
# tables, so jobs_user's read is a default privilege for that role: the publish drops and recreates
# each table, and a plain GRANT would be gone after the first swap. The admin creates the schemas:
# analytics_user has no CREATE on the database.
set -eu

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
    -v analytics_password="$ANALYTICS_DB_PASSWORD" \
    -v analytics_dev_password="$ANALYTICS_DEV_DB_PASSWORD" <<'SQL'
CREATE ROLE analytics_user LOGIN PASSWORD :'analytics_password';
CREATE ROLE analytics_dev_user LOGIN PASSWORD :'analytics_dev_password';

CREATE DATABASE jobs_db;
REVOKE CONNECT ON DATABASE jobs_db FROM PUBLIC;
GRANT CONNECT ON DATABASE jobs_db TO jobs_user, analytics_user, analytics_dev_user;
SQL

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname jobs_db <<'SQL'
CREATE SCHEMA analytics AUTHORIZATION analytics_user;
CREATE SCHEMA analytics_dev AUTHORIZATION analytics_dev_user;

GRANT USAGE ON SCHEMA analytics TO jobs_user;
ALTER DEFAULT PRIVILEGES FOR ROLE analytics_user IN SCHEMA analytics GRANT SELECT ON TABLES TO jobs_user;
SQL
