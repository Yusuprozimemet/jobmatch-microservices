-- Google users never choose a password, so there is no hash to store.
ALTER TABLE user_credentials
    ALTER COLUMN password_hash DROP NOT NULL;

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS oauth_provider VARCHAR(50),
    ADD COLUMN IF NOT EXISTS oauth_provider_id VARCHAR(255);

-- Either both columns are set or neither is; a half-linked account is meaningless.
ALTER TABLE users
    DROP CONSTRAINT IF EXISTS users_oauth_provider_pair_chk;
ALTER TABLE users
    ADD CONSTRAINT users_oauth_provider_pair_chk
        CHECK ((oauth_provider IS NULL) = (oauth_provider_id IS NULL));

-- One provider account maps to at most one user.
CREATE UNIQUE INDEX IF NOT EXISTS users_oauth_provider_id_idx
    ON users (oauth_provider, oauth_provider_id)
    WHERE oauth_provider_id IS NOT NULL;

-- Email links a Google sign-in to an existing account, so duplicates must be impossible.
CREATE UNIQUE INDEX IF NOT EXISTS users_email_idx ON users (email);
