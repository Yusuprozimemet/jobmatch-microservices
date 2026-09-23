#!/bin/sh
# The module roles and schemas, for compose: the counterpart of scripts/db-setup.py.
#
# Postgres runs this once, on an empty volume (docker-entrypoint-initdb.d). Day 11's migrations
# move each module's tables into its schema and hand them to its role, so the roles and schemas
# have to exist first. A volume created before Day 11 never ran this: start it again with
# `docker compose down -v`, or run db-setup.py against it.
#
# identity_user owns identity, and so on; jobs_user owns nothing and only reads. Every other role
# may use a schema but not create in it, the rule db-setup.py applies.
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

GRANT USAGE ON SCHEMA identity TO applications_user, matching_user, jobs_user;
GRANT USAGE ON SCHEMA applications TO identity_user, matching_user, jobs_user;
GRANT USAGE ON SCHEMA matching TO identity_user, applications_user, jobs_user;

-- db-setup.py's read-only rule for what each module creates in its own schema later, so compose
-- grants a new table what production does (identity's migrations revoke what must stay private).
ALTER DEFAULT PRIVILEGES FOR ROLE identity_user IN SCHEMA identity
    GRANT SELECT ON TABLES TO applications_user, matching_user, jobs_user;
ALTER DEFAULT PRIVILEGES FOR ROLE identity_user IN SCHEMA identity
    GRANT SELECT ON SEQUENCES TO applications_user, matching_user, jobs_user;
ALTER DEFAULT PRIVILEGES FOR ROLE applications_user IN SCHEMA applications
    GRANT SELECT ON TABLES TO identity_user, matching_user, jobs_user;
ALTER DEFAULT PRIVILEGES FOR ROLE applications_user IN SCHEMA applications
    GRANT SELECT ON SEQUENCES TO identity_user, matching_user, jobs_user;
ALTER DEFAULT PRIVILEGES FOR ROLE matching_user IN SCHEMA matching
    GRANT SELECT ON TABLES TO identity_user, applications_user, jobs_user;
ALTER DEFAULT PRIVILEGES FOR ROLE matching_user IN SCHEMA matching
    GRANT SELECT ON SEQUENCES TO identity_user, applications_user, jobs_user;

-- jobs reads the mart. In compose anything in analytics is created by this admin, so its future
-- tables are granted too; production gets the same from db-setup.py's read-only rule.
CREATE SCHEMA IF NOT EXISTS analytics;
GRANT USAGE ON SCHEMA analytics TO jobs_user;
GRANT SELECT ON ALL TABLES IN SCHEMA analytics TO jobs_user;
ALTER DEFAULT PRIVILEGES IN SCHEMA analytics GRANT SELECT ON TABLES TO jobs_user;
SQL
