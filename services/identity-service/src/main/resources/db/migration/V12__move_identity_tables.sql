-- Day 11: identity's four tables leave the shared app schema for identity's own, and become
-- identity_user's. Runs as the role that applied V1-V11, because only a table's owner can move
-- it (the container's admin in compose and the tests, app_user in production).
--
-- The role and the schema are a precondition, not this migration's work: roles are cluster-wide
-- and carry passwords. scripts/db-setup.py creates them in production, scripts/db-init/ on a
-- fresh compose volume, and the test harness in tests.
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'identity_user')
            OR NOT EXISTS (SELECT FROM pg_namespace WHERE nspname = 'identity') THEN
        RAISE EXCEPTION 'Day 11 moves identity''s tables into schema identity, owned by role '
            'identity_user, and they do not exist in this database. Run scripts/db-setup.py, or for '
            'compose start from a fresh volume: docker compose down -v && docker compose up';
    END IF;
END $$;

-- Constraints, indexes and the foreign keys pointing here move with the tables.
ALTER TABLE users SET SCHEMA identity;
ALTER TABLE user_credentials SET SCHEMA identity;
ALTER TABLE user_profiles SET SCHEMA identity;
ALTER TABLE password_reset_tokens SET SCHEMA identity;

ALTER TABLE identity.users OWNER TO identity_user;
ALTER TABLE identity.user_credentials OWNER TO identity_user;
ALTER TABLE identity.user_profiles OWNER TO identity_user;
ALTER TABLE identity.password_reset_tokens OWNER TO identity_user;

-- Read-only for every other module, the rule db-setup.py applies. Grants do not follow a moved
-- table, so they are made here rather than by the default privileges db-setup.py sets.
GRANT SELECT ON ALL TABLES IN SCHEMA identity TO applications_user, matching_user, jobs_user;
