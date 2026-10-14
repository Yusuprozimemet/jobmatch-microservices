-- Day 25: saved_jobs goes from project_db. application-service serves it from apps_db and
-- deletes a deleted account's rows on user.deleted, so fk_saved_jobs_user, which V13 kept,
-- goes with the table. Existing rows must be copied first with scripts/copy-saved-jobs.py.
-- The role and the schema stay: V13 raises without them, and V12 and V14 grant to the role.
-- It also carries the revokes of applications' own V1, which left with the module (Day 25
-- track E1a), as V15 carries matching's.

DROP TABLE IF EXISTS applications.saved_jobs;

-- saved_jobs' column type, and nothing else uses it.
DROP TYPE IF EXISTS applications.job_state;

DO $$
DECLARE
    target RECORD;
BEGIN
    -- The schema first: without USAGE, nothing in it can be named, whatever else was granted.
    FOR target IN
        SELECT DISTINCT CASE WHEN acl.grantee = 0 THEN 'PUBLIC' ELSE quote_ident(pg_get_userbyid(acl.grantee)) END AS grantee
        FROM pg_namespace n, aclexplode(n.nspacl) acl
        WHERE n.nspname = 'applications' AND acl.grantee <> n.nspowner
    LOOP
        EXECUTE 'REVOKE ALL ON SCHEMA applications FROM ' || target.grantee;
    END LOOP;

    -- Tables and sequences in applications schema. Schema-qualified because this migration runs
    -- with search_path app, not applications.
    FOR target IN
        SELECT DISTINCT CASE WHEN c.relkind = 'S' THEN 'SEQUENCE' ELSE 'TABLE' END AS kind, quote_ident(c.relname) AS name,
               CASE WHEN acl.grantee = 0 THEN 'PUBLIC' ELSE quote_ident(pg_get_userbyid(acl.grantee)) END AS grantee
        FROM pg_class c, aclexplode(c.relacl) acl
        WHERE c.relnamespace = 'applications'::regnamespace AND acl.grantee <> c.relowner
    LOOP
        EXECUTE 'REVOKE ALL ON ' || target.kind || ' applications.' || target.name || ' FROM ' || target.grantee;
    END LOOP;

    -- Default privileges registered for applications_user. Defaults registered for other creators
    -- (db-setup.py registered them for its admin and the analytics roles) are theirs to remove,
    -- not this role's; without USAGE on the schema they grant nothing that can be reached.
    FOR target IN
        SELECT DISTINCT CASE d.defaclobjtype WHEN 'r' THEN 'TABLES' WHEN 'S' THEN 'SEQUENCES'
                            WHEN 'f' THEN 'FUNCTIONS' WHEN 'T' THEN 'TYPES' END AS kind,
               CASE WHEN acl.grantee = 0 THEN 'PUBLIC' ELSE quote_ident(pg_get_userbyid(acl.grantee)) END AS grantee
        FROM pg_default_acl d, aclexplode(d.defaclacl) acl
        WHERE d.defaclnamespace = 'applications'::regnamespace AND d.defaclrole = 'applications_user'::regrole
          AND acl.grantee <> d.defaclrole
    LOOP
        EXECUTE 'ALTER DEFAULT PRIVILEGES FOR ROLE applications_user IN SCHEMA applications REVOKE ALL ON ' || target.kind || ' FROM ' || target.grantee;
    END LOOP;
END
$$;
