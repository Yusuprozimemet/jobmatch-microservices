#!/bin/sh
# The module roles and schemas, for compose: the counterpart of scripts/db-setup.py.
#
# Postgres runs this once, on an empty volume (docker-entrypoint-initdb.d). Day 11's migrations
# move each module's tables into its schema and hand them to its role, so the roles and schemas
# have to exist first. A volume created before Day 11 never ran this: start it again with
# `docker compose down -v`, or run db-setup.py against it.
#
# identity_user owns identity, and so on; jobs_user owns nothing and only reads the mart. No module
# reads another's schema (Day 38), the rule db-setup.py applies; a volume created before Day 38 had
# the grants, and each module's own migration revokes them.
set -eu

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
    -v identity_password="$IDENTITY_DB_PASSWORD" \
    -v applications_password="$APPLICATIONS_DB_PASSWORD" \
    -v matching_password="$MATCHING_DB_PASSWORD" \
    -v jobs_password="$JOBS_DB_PASSWORD" <<'SQL'
CREATE ROLE identity_user LOGIN PASSWORD :'identity_password';
CREATE ROLE applications_user LOGIN PASSWORD :'applications_password';
CREATE ROLE matching_user LOGIN PASSWORD :'matching_password';
CREATE ROLE jobs_user LOGIN PASSWORD :'jobs_password';

CREATE SCHEMA identity AUTHORIZATION identity_user;
CREATE SCHEMA applications AUTHORIZATION applications_user;
CREATE SCHEMA matching AUTHORIZATION matching_user;

-- jobs reads the mart. In compose anything in analytics is created by this admin, so its future
-- tables are granted too; production gets the same from db-setup.py's read-only rule.
CREATE SCHEMA IF NOT EXISTS analytics;
GRANT USAGE ON SCHEMA analytics TO jobs_user;
GRANT SELECT ON ALL TABLES IN SCHEMA analytics TO jobs_user;
ALTER DEFAULT PRIVILEGES IN SCHEMA analytics GRANT SELECT ON TABLES TO jobs_user;
SQL
