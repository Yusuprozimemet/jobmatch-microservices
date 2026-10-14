-- Normalize all existing user emails to lowercase
UPDATE users SET email = LOWER(email);

-- Drop the old case-sensitive unique index from V3
DROP INDEX IF EXISTS users_email_idx;

-- Create a new case-insensitive unique expression index
CREATE UNIQUE INDEX users_email_idx ON users (LOWER(email));