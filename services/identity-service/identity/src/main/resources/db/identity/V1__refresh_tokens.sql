-- Refresh tokens (Day 12). Only the SHA-256 of a token is kept. The token is 32 random bytes held
-- by the client, so a slow hash would buy nothing, and would rule out finding a row by its hash.
CREATE TABLE refresh_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL,
    token_hash TEXT NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    -- Deleting an account deletes its tokens (backend/docs/auth.md, section 8).
    CONSTRAINT fk_refresh_tokens_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- The cascade, and revoking every token of one user, both look rows up by user.
CREATE INDEX idx_refresh_tokens_user ON refresh_tokens (user_id);

-- Only identity reads this table. Production's db-setup.py registers default privileges that give
-- every other role SELECT on whatever identity_user creates here, so the table has just been
-- granted to them. Revoke from every grantee the catalogue lists other than the owner, rather
-- than from a list of names that the next role added would be missing from.
DO $$
DECLARE
    grantee TEXT;
BEGIN
    FOR grantee IN
        SELECT DISTINCT CASE WHEN acl.grantee = 0 THEN 'PUBLIC' ELSE quote_ident(pg_get_userbyid(acl.grantee)) END
        FROM pg_class c, aclexplode(c.relacl) acl
        WHERE c.oid = 'refresh_tokens'::regclass AND acl.grantee <> c.relowner
    LOOP
        EXECUTE 'REVOKE ALL ON TABLE refresh_tokens FROM ' || grantee;
    END LOOP;
END
$$;
