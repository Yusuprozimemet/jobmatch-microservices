-- Bridge mart for the backend: one row per posting and requirement (split
-- required vs preferred), so "show me open postings that require a driver's
-- license" is a filter on this table instead of an explode-and-join the
-- backend has to do itself.
--
-- Grain matches int_postings_requirements exactly, for the same reason as
-- fct_postings_skills: postings.posting_id is unique, so the join can't
-- duplicate or drop rows relative to int_postings_requirements' own grain.
--
-- Deliberately does not source from fct_postings -- see fct_postings_skills
-- for why marts here don't depend on other marts.
with
    requirements as (select * from {{ ref("int_postings_requirements") }}),

    postings as (
        select posting_id, original_source, title from {{ ref("int_postings") }}
    )

select
    requirements.posting_id,
    requirements.priority,
    requirements.requirement_text,

    postings.original_source as source,
    postings.title,

    requirements.posted_at,
    date(requirements.posted_at) as posted_date

from requirements
inner join postings on requirements.posting_id = postings.posting_id
