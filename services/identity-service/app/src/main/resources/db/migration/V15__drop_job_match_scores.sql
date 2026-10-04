-- Day 23: job_match_scores goes. Day 22 moved the scores cache to DynamoDB and nothing reads the
-- table since. Runs as the role that applied V1-V14, which owns or is a member of matching_user
-- (see V12). IF EXISTS: a database restored from before V10 has none. The role and the schema
-- stay: V14 raises on a fresh database without them.
--
-- It also carries the revokes of matching-service's own V1, which leaves with the service's
-- migrations. On a fresh database that V1 is what removes the grants the role sources give every
-- module schema; without it nothing would.
DROP TABLE IF EXISTS matching.job_match_scores;

DO $$
DECLARE
    target RECORD;
BEGIN
    -- The schema first: without USAGE, nothing in it can be named, whatever else was granted.
    FOR target IN
        SELECT DISTINCT CASE WHEN acl.grantee = 0 THEN 'PUBLIC' ELSE quote_ident(pg_get_userbyid(acl.grantee)) END AS grantee
        FROM pg_namespace n, aclexplode(n.nspacl) acl
        WHERE n.nspname = 'matching' AND acl.grantee <> n.nspowner
    LOOP
        EXECUTE 'REVOKE ALL ON SCHEMA matching FROM ' || target.grantee;
    END LOOP;

    -- Tables and sequences in matching schema. Schema-qualified because this migration runs
    -- with search_path app, not matching.
    FOR target IN
        SELECT DISTINCT CASE WHEN c.relkind = 'S' THEN 'SEQUENCE' ELSE 'TABLE' END AS kind, quote_ident(c.relname) AS name,
               CASE WHEN acl.grantee = 0 THEN 'PUBLIC' ELSE quote_ident(pg_get_userbyid(acl.grantee)) END AS grantee
        FROM pg_class c, aclexplode(c.relacl) acl
        WHERE c.relnamespace = 'matching'::regnamespace AND acl.grantee <> c.relowner
    LOOP
        EXECUTE 'REVOKE ALL ON ' || target.kind || ' matching.' || target.name || ' FROM ' || target.grantee;
    END LOOP;

    -- Default privileges registered for matching_user. Defaults registered for other creators
    -- (db-setup.py registered them for its admin and the analytics roles) are theirs to remove,
    -- not this role's; without USAGE on the schema they grant nothing that can be reached.
    FOR target IN
        SELECT DISTINCT CASE d.defaclobjtype WHEN 'r' THEN 'TABLES' WHEN 'S' THEN 'SEQUENCES'
                            WHEN 'f' THEN 'FUNCTIONS' WHEN 'T' THEN 'TYPES' END AS kind,
               CASE WHEN acl.grantee = 0 THEN 'PUBLIC' ELSE quote_ident(pg_get_userbyid(acl.grantee)) END AS grantee
        FROM pg_default_acl d, aclexplode(d.defaclacl) acl
        WHERE d.defaclnamespace = 'matching'::regnamespace AND d.defaclrole = 'matching_user'::regrole
          AND acl.grantee <> d.defaclrole
    LOOP
        EXECUTE 'ALTER DEFAULT PRIVILEGES FOR ROLE matching_user IN SCHEMA matching REVOKE ALL ON ' || target.kind || ' FROM ' || target.grantee;
    END LOOP;
END
$$;
