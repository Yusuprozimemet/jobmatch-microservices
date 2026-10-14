-- Day 38: only identity reads schema identity. V12:31 in app granted SELECT on its tables to the
-- other module logins, and the role sources granted them the schema and whatever identity_user
-- created here later. Before identity leaves the process, its data stops being readable by logins
-- that another container will hold.
--
-- Revoked from every grantee the catalogue lists other than the owner, as identity's V1 does,
-- rather than from a list of names the next role added would be missing from. identity_user
-- owns the schema and its tables, and so is the grantor of what was granted on them.
DO $$
DECLARE
    target RECORD;
BEGIN
    -- The schema first: without USAGE, nothing in it can be named, whatever else was granted.
    FOR target IN
        SELECT DISTINCT CASE WHEN acl.grantee = 0 THEN 'PUBLIC' ELSE quote_ident(pg_get_userbyid(acl.grantee)) END AS grantee
        FROM pg_namespace n, aclexplode(n.nspacl) acl
        WHERE n.nspname = 'identity' AND acl.grantee <> n.nspowner
    LOOP
        EXECUTE 'REVOKE ALL ON SCHEMA identity FROM ' || target.grantee;
    END LOOP;

    FOR target IN
        SELECT DISTINCT CASE WHEN c.relkind = 'S' THEN 'SEQUENCE' ELSE 'TABLE' END AS kind, quote_ident(c.relname) AS name,
               CASE WHEN acl.grantee = 0 THEN 'PUBLIC' ELSE quote_ident(pg_get_userbyid(acl.grantee)) END AS grantee
        FROM pg_class c, aclexplode(c.relacl) acl
        WHERE c.relnamespace = 'identity'::regnamespace AND acl.grantee <> c.relowner
    LOOP
        EXECUTE 'REVOKE ALL ON ' || target.kind || ' ' || target.name || ' FROM ' || target.grantee;
    END LOOP;

    -- What identity_user creates here later. Defaults registered for other creators (db-setup.py
    -- registered them for its admin and the analytics roles) are theirs to remove, not this role's;
    -- without USAGE on the schema they grant nothing that can be reached.
    FOR target IN
        SELECT DISTINCT CASE d.defaclobjtype WHEN 'r' THEN 'TABLES' WHEN 'S' THEN 'SEQUENCES'
                            WHEN 'f' THEN 'FUNCTIONS' WHEN 'T' THEN 'TYPES' END AS kind,
               CASE WHEN acl.grantee = 0 THEN 'PUBLIC' ELSE quote_ident(pg_get_userbyid(acl.grantee)) END AS grantee
        FROM pg_default_acl d, aclexplode(d.defaclacl) acl
        WHERE d.defaclnamespace = 'identity'::regnamespace AND d.defaclrole = current_user::regrole
          AND acl.grantee <> d.defaclrole
    LOOP
        EXECUTE 'ALTER DEFAULT PRIVILEGES IN SCHEMA identity REVOKE ALL ON ' || target.kind || ' FROM ' || target.grantee;
    END LOOP;
END
$$;
