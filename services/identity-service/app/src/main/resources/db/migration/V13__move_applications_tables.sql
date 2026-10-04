-- Day 11: saved_jobs leaves the shared app schema for applications' own, and becomes
-- applications_user's. Runs as the role that applied V1-V11; see V12 for why.
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'applications_user')
            OR NOT EXISTS (SELECT FROM pg_namespace WHERE nspname = 'applications') THEN
        RAISE EXCEPTION 'Day 11 moves applications'' tables into schema applications, owned by role '
            'applications_user, and they do not exist in this database. Run scripts/db-setup.py, or '
            'for compose start from a fresh volume: docker compose down -v && docker compose up';
    END IF;
END $$;

-- fk_saved_jobs_user comes along and now crosses into identity.users. It stays until Day 27:
-- it is how deleting an account removes the account's saved jobs (AccountDeletionIT).
ALTER TABLE saved_jobs SET SCHEMA applications;
ALTER TABLE applications.saved_jobs OWNER TO applications_user;

-- The job_state enum (V2) is saved_jobs' column type and does not move with the table. The
-- repository casts to it unqualified (?::job_state), so it goes where applications will look.
ALTER TYPE job_state SET SCHEMA applications;
ALTER TYPE applications.job_state OWNER TO applications_user;

GRANT SELECT ON ALL TABLES IN SCHEMA applications TO identity_user, matching_user, jobs_user;
