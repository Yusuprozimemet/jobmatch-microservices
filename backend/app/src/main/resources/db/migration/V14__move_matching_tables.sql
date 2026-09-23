-- Day 11: job_match_scores leaves the shared app schema for matching's own, and becomes
-- matching_user's. Runs as the role that applied V1-V11; see V12 for why. After this, app holds
-- nothing but Flyway's history of V1-V14.
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'matching_user')
            OR NOT EXISTS (SELECT FROM pg_namespace WHERE nspname = 'matching') THEN
        RAISE EXCEPTION 'Day 11 moves matching''s tables into schema matching, owned by role '
            'matching_user, and they do not exist in this database. Run scripts/db-setup.py, or for '
            'compose start from a fresh volume: docker compose down -v && docker compose up';
    END IF;
END $$;

ALTER TABLE job_match_scores SET SCHEMA matching;
ALTER TABLE matching.job_match_scores OWNER TO matching_user;

GRANT SELECT ON ALL TABLES IN SCHEMA matching TO identity_user, applications_user, jobs_user;
