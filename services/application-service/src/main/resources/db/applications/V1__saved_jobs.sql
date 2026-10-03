-- Day 25: saved_jobs and its job_state type in apps_db, the database of application-service,
-- as project_db in the monolith holds them (V2 in app, moved into schema applications by V13).
-- There is no fk_saved_jobs_user: users stays in the identity database, and the rows of a deleted user
-- go by the Day 27 consumer. Nothing to revoke as the applications V1 in project_db does:
-- only applications_user connects to apps_db. Unqualified names: Flyway sets the search path to
-- schema applications, and the repository casts to job_state unqualified.

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
    PRIMARY KEY (user_id, posting_id)
);
