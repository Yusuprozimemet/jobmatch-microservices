-- One row per posting and normalized skill.
-- Explodes skills_raw from int_postings, cleans each skill value,
-- applies a small controlled alias mapping, and removes duplicates
-- within the same posting.
with
    postings as (select * from {{ ref("int_postings") }}),

    exploded as (

        -- Empty/null arrays produce no skill rows, which is correct
        -- for this grain: one row per posting and skill.
        select posting_id, posted_at, skill as skill_raw

        from postings
        lateral view explode(skills_raw) as skill

    ),

    cleaned as (

        select
            posting_id,
            skill_raw,

            -- Basic normalization:
            -- 1. lowercase
            -- 2. trim outer whitespace
            -- 3. normalize Unicode dash variants to "-"
            -- 4. remove spaces around "-"
            -- 5. collapse repeated internal whitespace
            nullif(
                trim(
                    regexp_replace(
                        regexp_replace(
                            regexp_replace(lower(skill_raw), '[‐-‒–—−]', '-'),
                            '\\s*-\\s*',
                            '-'
                        ),
                        '\\s+',
                        ' '
                    )
                ),
                ''
            ) as skill_clean,

            posted_at

        from exploded

        where skill_raw is not null and trim(skill_raw) <> ''

    ),

    normalized as (

        select
            posting_id,
            skill_raw,

            -- Controlled aliases for known equivalent values.
            -- Keep this list small and evidence-based.
            case
                when skill_clean = 'machine learning'
                then 'machine-learning'

                when skill_clean = 'data science'
                then 'data-science'

                else skill_clean
            end as skill,

            posted_at

        from cleaned

        where skill_clean is not null

    ),

    deduplicated as (

        -- After normalization, values such as
        -- "Python" and " python " collapse to the same skill.
        select posting_id, skill_raw, skill, posted_at

        from normalized

        qualify
            row_number() over (partition by posting_id, skill order by posted_at) = 1

    )

select posting_id, skill_raw, skill, posted_at

from deduplicated
