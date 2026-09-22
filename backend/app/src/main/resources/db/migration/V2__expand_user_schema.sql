ALTER TABLE users
    ADD COLUMN IF NOT EXISTS name VARCHAR(255),
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT now();

CREATE TABLE user_credentials (
    user_id UUID PRIMARY KEY,
    password_hash VARCHAR(255) NOT NULL,
    refresh_token_hash TEXT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_user_credentials FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE TABLE user_profiles (
    user_id UUID PRIMARY KEY,
    preferred_role VARCHAR(255),
    preferred_city VARCHAR(255),
    preferred_country VARCHAR(255),
    education_level VARCHAR(255),
    experience_level VARCHAR(255),
    salary NUMERIC(10, 2),
    skills JSONB,
    CONSTRAINT fk_user_profile FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE TYPE job_state AS ENUM (
    'SAVED',
    'APPLIED',
    'REJECTED',
    'ACCEPTED',
    'DECLINED'
);

CREATE TABLE saved_jobs (
    user_id UUID NOT NULL,
    posting_id TEXT NOT NULL,
    job_state job_state NOT NULL DEFAULT 'SAVED',
    PRIMARY KEY (user_id, posting_id),
    CONSTRAINT fk_saved_jobs_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);
