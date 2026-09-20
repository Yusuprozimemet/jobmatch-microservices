-- int_postings.sql
-- One row per posting — same grain as stg_postings.
--
-- Responsibilities of this model:
-- * create the canonical posting_id
-- * deduplicate postings, keeping the latest ingested version
-- * clean job-description text
-- * normalize work_mode
-- * flag whether usable location information exists
--
-- Raw source fields are preserved alongside cleaned fields.
-- More domain-specific transformations belong in downstream
-- intermediate models or marts.
with
    postings as (select * from {{ ref("stg_postings") }}),

    keyed as (

        select md5(concat(original_source, '-', source_job_id)) as posting_id, *

        from postings

    ),

    -- Keep only the latest ingested version of each posting.
    -- This prevents duplicate posting_ids from reaching downstream models.
    deduplicated as (

        select *

        from keyed

        qualify
            row_number() over (partition by posting_id order by ingested_at desc) = 1

    ),

    -- Remove complete script/style blocks first.
    -- Removing only HTML tags would leave their contents behind.
    description_blocks_removed as (

        select
            *,

            case
                when description_raw is null
                then null
                else
                    regexp_replace(
                        description_raw,
                        '(?is)<(script|style)[^>]*>.*?</(script|style)>',
                        ' '
                    )
            end as description_without_blocks

        from deduplicated

    ),

    -- Strip the remaining HTML tags.
    description_tags_removed as (

        select
            *,

            case
                when description_without_blocks is null
                then null
                else regexp_replace(description_without_blocks, '<[^>]+>', ' ')
            end as description_without_tags

        from description_blocks_removed

    ),

    -- Decode common entities that occur in job descriptions.
    description_entities_decoded as (

        select
            *,

            case
                when description_without_tags is null
                then null
                else
                    replace(
                        replace(
                            replace(
                                replace(
                                    replace(
                                        replace(
                                            replace(
                                                replace(
                                                    replace(
                                                        description_without_tags,
                                                        '&nbsp;',
                                                        ' '
                                                    ),
                                                    '&amp;',
                                                    '&'
                                                ),
                                                '&quot;',
                                                '"'
                                            ),
                                            '&#39;',
                                            ''''
                                        ),
                                        '&#x27;',
                                        ''''
                                    ),
                                    '&apos;',
                                    ''''
                                ),
                                '&lt;',
                                '<'
                            ),
                            '&gt;',
                            '>'
                        ),
                        chr(160),
                        ' '
                    )
            end as description_decoded

        from description_tags_removed

    ),

    -- Second decoding pass for double-encoded HTML entities.
    -- Example: &amp;amp; -> &amp; -> &
    description_entities_decoded_again as (

        select
            *,

            case
                when description_decoded is null
                then null
                else
                    replace(
                        replace(
                            replace(
                                replace(
                                    replace(
                                        replace(
                                            replace(
                                                replace(
                                                    description_decoded, '&nbsp;', ' '
                                                ),
                                                '&amp;',
                                                '&'
                                            ),
                                            '&quot;',
                                            '"'
                                        ),
                                        '&#39;',
                                        ''''
                                    ),
                                    '&#x27;',
                                    ''''
                                ),
                                '&apos;',
                                ''''
                            ),
                            '&lt;',
                            '<'
                        ),
                        '&gt;',
                        '>'
                    )
            end as description_decoded_final

        from description_entities_decoded

    ),

    description_cleaned as (

        select
            *,

            nullif(
                trim(regexp_replace(description_decoded_final, '\\s+', ' ')), ''
            ) as description_clean

        from description_entities_decoded_again

    ),

    work_mode_cleaned as (

        select
            *,

            nullif(
                lower(trim(regexp_replace(source_work_mode, '\\s+', ' '))), ''
            ) as work_mode

        from description_cleaned

    ),

    flagged as (

        select
            *,

            (
                coalesce(
                    size(
                        filter(
                            cities_raw, city -> city is not null and trim(city) <> ''
                        )
                    ),
                    0
                )
                > 0

                or coalesce(work_mode in ('remote', 'hybrid'), false)
            ) as has_location_data

        from work_mode_cleaned

    )

select
    posting_id,
    source_job_id,
    original_source,
    public_slug,

    title,
    company_name,
    company_slug,
    source_url,

    description_raw,
    description_clean,

    cities_raw,
    location_raw,
    countries_raw,
    regions_raw,
    has_location_data,

    skills_raw,
    requirements_raw,

    work_mode,

    source_category,
    source_employment_type,
    source_experience_level,
    source_experience_years_min,
    source_education_level,
    source_posting_language,

    source_salary_min,
    source_salary_max,
    source_salary_currency,
    source_salary_period,

    source_company_type,
    source_company_size,
    source_is_tech,

    posted_at,
    source_created_at,
    updated_at,
    last_seen_at,
    closed_at,

    source_freshness_class,
    source_age_days,
    source_repost_count,
    source_mass_posting_count,
    source_fake_freshness,

    source_file,
    ingest_date,
    ingested_at

from flagged
