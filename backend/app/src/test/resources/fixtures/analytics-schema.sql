-- The mart tables the backend reads.
--
-- These are NOT created by Flyway: the data pipeline builds them in Databricks and
-- src/publishing/sync.py writes them into the backend's database. Tests therefore have
-- to create them themselves, or every mart query fails with "relation does not exist".
--
-- Column names and types mirror data/sql/job_schema.sql, which is the real published
-- shape. Do not "improve" the types here - a test that passes against a nicer schema
-- than production has tells you nothing. In particular `skills` and `cities` are text
-- holding a JSON array, not jsonb and not text[], because that is what sync.py writes
-- for a Databricks ARRAY<STRING>.

CREATE SCHEMA IF NOT EXISTS app;
CREATE SCHEMA IF NOT EXISTS analytics;

DROP TABLE IF EXISTS analytics.fct_postings;
CREATE TABLE analytics.fct_postings (
    title             text NULL,
    posting_id        text NULL,
    "source"          text NULL,
    source_job_id     text NULL,
    company_name      text NULL,
    "location"        text NULL,
    countries         text NULL,
    regions           text NULL,
    cities            text NULL,
    has_location_data bool NULL,
    work_mode         text NULL,
    is_remote         bool NULL,
    skills            text NULL,
    skill_count       int4 NULL,
    experience_level  text NULL,
    education_level   text NULL,
    employment_type   text NULL,
    salary_min        float8 NULL,
    salary_max        float8 NULL,
    salary_currency   text NULL,
    salary_period     text NULL,
    category          text NULL,
    description       text NULL,
    posted_at         timestamptz NULL,
    posted_date       date NULL,
    updated_at        timestamptz NULL,
    last_seen_at      timestamptz NULL,
    closed_at         timestamptz NULL,
    status            text NULL,
    freshness_class   text NULL,
    age_days          int4 NULL,
    repost_count      int4 NULL,
    fake_freshness    bool NULL,
    source_url        text NULL,
    ingest_date       date NULL,
    ingested_at       timestamptz NULL,
    discipline        text NULL,
    enriched_at       timestamptz NULL
);

DROP TABLE IF EXISTS analytics.fct_postings_cities;
CREATE TABLE analytics.fct_postings_cities (
    posting_id  text NULL,
    city        text NULL,
    "source"    text NULL,
    title       text NULL,
    posted_at   timestamptz NULL,
    posted_date date NULL
);

DROP TABLE IF EXISTS analytics.fct_postings_skills;
CREATE TABLE analytics.fct_postings_skills (
    posting_id  text NULL,
    skill       text NULL,
    "source"    text NULL,
    title       text NULL,
    posted_at   timestamptz NULL,
    posted_date date NULL
);
