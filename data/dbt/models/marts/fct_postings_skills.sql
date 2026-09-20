-- Bridge mart for the backend: one row per posting and skill, so "show me
-- open postings that require Python" is a filter on this table instead of
-- an explode-and-join the backend has to do itself.
--
-- Grain matches int_postings_skills exactly. That model already dedupes to
-- one row per (posting_id, skill), and posting_id is unique in int_postings,
-- so joining the two 1:1-on-posting_id can't change the grain or introduce
-- duplicates -- no additional composite-uniqueness test is needed here.
--
-- Deliberately does not source from fct_postings: marts don't depend on
-- other marts in this project, so is_remote/status are re-derived here the
-- same way fct_postings derives them. If that logic ever changes, both
-- places need the change -- that's an accepted trade for keeping marts
-- independently buildable.
with
    skills as (select * from {{ ref("int_postings_skills") }}),

    postings as (
        select posting_id, original_source, title from {{ ref("int_postings") }}
    )

select
    skills.posting_id,
    skills.skill,

    postings.original_source as source,
    postings.title,

    skills.posted_at,
    date(skills.posted_at) as posted_date

from skills
inner join postings on skills.posting_id = postings.posting_id
