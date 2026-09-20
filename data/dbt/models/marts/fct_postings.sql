-- This mart is the contract with the backend team.
--
-- Its columns are what backend/ reads to build API endpoints
--
-- Airflow copies this table into the backend's database after dbt succeeds, so
-- whatever you select here is what they get.
--
-- Sources from int_postings instead of stg_postings so this mart gets the
-- surrogate posting_id design, HTML-entity-decoded description, and
-- normalized work_mode without re-deriving any of it here.
with postings as (select * from {{ ref("int_postings") }})

select
    posting_id,

    original_source as source,
    source_job_id,

    postings.title as title,
    company_name,

    location_raw as location,
    countries_raw as countries,
    regions_raw as regions,
    cities_raw as cities,
    has_location_data,
    work_mode,

    -- work_mode is already lowercased/trimmed/null-coerced in int_postings,
    -- so this is a straight comparison instead of re-normalizing here.
    coalesce(work_mode = 'remote', false) as is_remote,

    skills_raw as skills,

    coalesce(size(array_distinct(skills_raw)), 0) as skill_count,

    source_experience_level as experience_level,
    source_education_level as education_level,
    source_employment_type as employment_type,

    source_salary_min as salary_min,
    source_salary_max as salary_max,
    source_salary_currency as salary_currency,
    source_salary_period as salary_period,
    -- Keep the source category when available. use the LLM classification
    -- only as a fallback when the source category is missing.
    coalesce(postings.source_category, llm.discipline) as category,

    -- description_clean instead of description_raw: HTML tags stripped and
    -- entities (&#39; etc.) decoded, so the backend stops receiving raw
    -- markup and mojibake-looking entity text.
    description_clean as description,

    posted_at,
    date(posted_at) as posted_date,

    updated_at,
    last_seen_at,
    closed_at,

    case when closed_at is null then 'open' else 'closed' end as status,

    source_freshness_class as freshness_class,
    source_age_days as age_days,
    source_repost_count as repost_count,
    source_fake_freshness as fake_freshness,

    source_url,

    ingest_date,
    ingested_at

from postings
left join {{ ref("fct_title_discipline") }} as llm on postings.title = llm.title
