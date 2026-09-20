-- int_postings_cities.sql
-- One row per posting and normalized city.
--
-- cities_raw is exploded because the grain changes from one posting
-- to one posting-city pair.
--
-- This model performs conservative normalization only:
-- * preserves the original source value as city_raw
-- * lowercases text
-- * normalizes whitespace
-- * normalizes Unicode dash variants
-- * removes duplicates within the same posting
--
-- It deliberately does not infer or rename cities.
-- Canonical aliases such as "den haag" -> "the hague" should only be
-- introduced later through an explicit mapping based on observed data.
with
    postings as (select * from {{ ref("int_postings") }}),

    exploded as (

        select posting_id, posted_at, city as city_raw

        from postings
        lateral view explode(cities_raw) as city

    ),

    cleaned as (

        select
            posting_id,
            posted_at,
            city_raw,

            nullif(
                trim(
                    regexp_replace(
                        regexp_replace(
                            regexp_replace(
                                lower(replace(city_raw, chr(160), ' ')), '[‐-‒–—−]', '-'
                            ),
                            '\\s*-\\s*',
                            '-'
                        ),
                        '\\s+',
                        ' '
                    )
                ),
                ''
            ) as city

        from exploded

        where city_raw is not null and trim(city_raw) <> ''

    ),

    deduplicated as (

        select posting_id, city_raw, city, posted_at

        from cleaned

        where city is not null

        qualify row_number() over (partition by posting_id, city order by posted_at) = 1

    )

select posting_id, city_raw, city, posted_at

from deduplicated
