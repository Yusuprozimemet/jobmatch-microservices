-- One row per skill: how often it appears and when it was last seen.
--
-- Sources from int_postings_skills instead of stg_postings. That model
-- already explodes skills_raw, lowercases/trims, and dedupes per posting —
-- this mart no longer needs to reimplement any of that, it just aggregates
-- the other way (by skill instead of by posting).
--
-- Not part of the backend contract. Airflow publishes fct_postings only, so
-- this mart is yours: query it in the warehouse, chart it, or add it to the
-- publish step if the product ends up needing it.
with skills as (select * from {{ ref("int_postings_skills") }})

select
    skill,
    count(distinct posting_id) as postings,
    min(posted_at) as first_seen_at,
    max(posted_at) as last_seen_at

from skills

group by skill
