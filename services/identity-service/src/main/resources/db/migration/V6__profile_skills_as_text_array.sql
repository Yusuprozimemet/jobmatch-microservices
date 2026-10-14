-- Restores the migration that ran on 2026-08-26 and was lost before it reached the
-- repository: user_profiles.skills moves from the jsonb V2 created to a real Postgres
-- array, which is what array overlap against the mart's skills needs.
--
-- The values are not carried across, because there are none: nothing wrote this table
-- until /api/profile existed, which is later than this migration.
ALTER TABLE user_profiles
    ALTER COLUMN skills TYPE text[] USING '{}'::text[];
