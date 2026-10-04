-- The three preferences the profile form already collects had no column, and they are
-- exactly the three that matter for matching against the mart, which carries
-- discipline, work_mode and employment_type of its own.
ALTER TABLE user_profiles
    ADD COLUMN IF NOT EXISTS discipline VARCHAR(255),
    ADD COLUMN IF NOT EXISTS work_mode VARCHAR(255),
    ADD COLUMN IF NOT EXISTS employment_type VARCHAR(255);
