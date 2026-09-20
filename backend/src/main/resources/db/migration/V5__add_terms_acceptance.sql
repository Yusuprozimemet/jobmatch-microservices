-- GDPR: personal data is only stored once the user has been told so and agreed.
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS terms_accepted_at TIMESTAMPTZ;
