-- analytics.fct_postings definition
-- Drop table
-- DROP TABLE analytics.fct_postings;
CREATE TABLE analytics.fct_postings (
	title text NULL,
	posting_id text NULL,
	"source" text NULL,
	source_job_id text NULL,
	company_name text NULL,
	"location" text NULL,
	countries text NULL,
	regions text NULL,
	cities text NULL,
	has_location_data bool NULL,
	work_mode text NULL,
	is_remote bool NULL,
	skills text NULL,
	skill_count int4 NULL,
	experience_level text NULL,
	education_level text NULL,
	employment_type text NULL,
	salary_min float8 NULL,
	salary_max float8 NULL,
	salary_currency text NULL,
	salary_period text NULL,
	category text NULL,
	description text NULL,
	posted_at timestamptz NULL,
	posted_date date NULL,
	updated_at timestamptz NULL,
	last_seen_at timestamptz NULL,
	closed_at timestamptz NULL,
	status text NULL,
	freshness_class text NULL,
	age_days int4 NULL,
	repost_count int4 NULL,
	fake_freshness bool NULL,
	source_url text NULL,
	ingest_date date NULL,
	ingested_at timestamptz NULL,
	discipline text NULL,
	enriched_at timestamptz NULL
);


-- analytics.fct_postings_cities definition
-- Drop table
-- DROP TABLE analytics.fct_postings_cities;
CREATE TABLE analytics.fct_postings_cities (
	posting_id text NULL,
	city text NULL,
	"source" text NULL,
	title text NULL,
	posted_at timestamptz NULL,
	posted_date date NULL
);

-- analytics.fct_postings_requirements definition
-- Drop table
-- DROP TABLE analytics.fct_postings_requirements;
CREATE TABLE analytics.fct_postings_requirements (
	posting_id text NULL,
	priority text NULL,
	requirement_text text NULL,
	"source" text NULL,
	title text NULL,
	posted_at timestamptz NULL,
	posted_date date NULL
);


-- analytics.fct_postings_skills definition
-- Drop table
-- DROP TABLE analytics.fct_postings_skills;
CREATE TABLE analytics.fct_postings_skills (
	posting_id text NULL,
	skill text NULL,
	"source" text NULL,
	title text NULL,
	posted_at timestamptz NULL,
	posted_date date NULL
);


-- analytics.fct_skill_popularity definition
-- Drop table
-- DROP TABLE analytics.fct_skill_popularity;
CREATE TABLE analytics.fct_skill_popularity (
	skill text NULL,
	postings int8 NULL,
	first_seen_at timestamptz NULL,
	last_seen_at timestamptz NULL
);
