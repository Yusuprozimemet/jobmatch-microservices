-- Google identities waiting for a password login to prove the account (Day 14). Until then the
-- session held them. The browser holds the code in a cookie; only its SHA-256 is kept here, as for
-- refresh tokens.
CREATE TABLE pending_google_links (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    -- The account the Google email belongs to: only its password login can claim the link.
    user_id UUID NOT NULL,
    provider_id TEXT NOT NULL,
    code_hash TEXT NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    claimed_at TIMESTAMPTZ,
    CONSTRAINT fk_pending_google_links_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- Saving a link deletes the account's earlier ones, and the cascade finds them by user.
CREATE INDEX idx_pending_google_links_user ON pending_google_links (user_id);

-- Only identity reads this table: it ties emails to Google identities. Revoked from every grantee
-- the catalogue lists other than the owner, as V1 does for refresh_tokens.
DO $$
DECLARE
    grantee TEXT;
BEGIN
    FOR grantee IN
        SELECT DISTINCT CASE WHEN acl.grantee = 0 THEN 'PUBLIC' ELSE quote_ident(pg_get_userbyid(acl.grantee)) END
        FROM pg_class c, aclexplode(c.relacl) acl
        WHERE c.oid = 'pending_google_links'::regclass AND acl.grantee <> c.relowner
    LOOP
        EXECUTE 'REVOKE ALL ON TABLE pending_google_links FROM ' || grantee;
    END LOOP;
END
$$;
