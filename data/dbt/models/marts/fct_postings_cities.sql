-- Bridge mart for the backend: one row per posting and city, so "show me
-- open postings in Rotterdam" is a filter on this table instead of an
-- explode-and-join the backend has to do itself.
--
-- This is the exploded, normalized city from int_postings_cities -- not the
-- raw cities_raw array that fct_postings still passes through for display.
-- The two can disagree (e.g. cities_raw has an empty string that got
-- filtered out here); that's expected, not a bug to reconcile.
--
-- Grain matches int_postings_cities exactly, for the same reason as
-- fct_postings_skills: postings.posting_id is unique, so the join can't
-- duplicate or drop rows relative to int_postings_cities' own grain.
--
with
    cities as (select * from {{ ref("int_postings_cities") }}),

    postings as (
        select posting_id, original_source, title from {{ ref("int_postings") }}
    )

select
    cities.posting_id,
    cities.city,

    postings.original_source as source,
    postings.title,

    cities.posted_at,
    date(cities.posted_at) as posted_date

from cities
inner join postings on cities.posting_id = postings.posting_id
