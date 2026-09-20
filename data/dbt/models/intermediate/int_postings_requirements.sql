-- int_postings_requirements.sql
-- One row per posting and normalized requirement.
--
-- requirements_raw is an array of structs containing priority and text.
-- Exploding it changes the grain from one posting to one posting-requirement.
--
-- This model performs conservative cleaning:
-- * preserves raw priority and requirement text
-- * normalizes priority casing/whitespace
-- * removes HTML/script/style content from requirement text
-- * decodes common HTML entities
-- * normalizes whitespace
-- * removes duplicate requirements within the same posting
--
-- It deliberately does not infer new priority values or rewrite
-- the semantic content of requirements.
with
    postings as (select * from {{ ref("int_postings") }}),

    exploded as (

        select
            posting_id,
            posted_at,
            req.priority as priority_raw,
            req.text as requirement_text_raw

        from postings
        lateral view explode(requirements_raw) as req

    ),

    priority_cleaned as (

        select
            *,

            nullif(
                lower(trim(regexp_replace(priority_raw, '\\s+', ' '))), ''
            ) as priority

        from exploded

    ),

    requirement_blocks_removed as (

        select
            *,

            case
                when requirement_text_raw is null
                then null
                else
                    regexp_replace(
                        requirement_text_raw,
                        '(?is)<script[^>]*>.*?</script>|<style[^>]*>.*?</style>',
                        ' '
                    )
            end as requirement_without_blocks

        from priority_cleaned

    ),

    requirement_tags_removed as (

        select
            *,

            case
                when requirement_without_blocks is null
                then null
                else regexp_replace(requirement_without_blocks, '<[^>]+>', ' ')
            end as requirement_without_tags

        from requirement_blocks_removed

    ),

    requirement_entities_decoded as (

        select
            *,

            case
                when requirement_without_tags is null
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
                                                        requirement_without_tags,
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
            end as requirement_decoded

        from requirement_tags_removed

    ),

    cleaned as (

        select
            posting_id,
            posted_at,
            priority_raw,
            priority,
            requirement_text_raw,

            nullif(
                trim(regexp_replace(requirement_decoded, '\\s+', ' ')), ''
            ) as requirement_text

        from requirement_entities_decoded

    ),

    with_dedup_key as (

        select
            *,

            -- Used only for duplicate detection.
            -- Keeps requirement_text itself in readable/original casing.
            lower(requirement_text) as requirement_dedup_key

        from cleaned

        where requirement_text is not null

    ),

    deduplicated as (

        select
            posting_id,
            priority_raw,
            priority,
            requirement_text_raw,
            requirement_text,
            posted_at

        from with_dedup_key

        qualify
            row_number() over (
                partition by posting_id, priority, requirement_dedup_key
                order by posted_at
            )
            = 1

    )

select
    posting_id,
    priority_raw,
    priority,
    requirement_text_raw,
    requirement_text,
    posted_at

from deduplicated
