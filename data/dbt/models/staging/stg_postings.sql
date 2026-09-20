-- Staging does one job: read the raw files and clean them. No business logic.
--
-- `read_files` reads every file in the landing volume, so a new day's file is
-- picked up without you changing anything here. `_metadata.file_path` tells you
-- which file a row came from, which is the first thing you want when one day
-- looks wrong.
--
-- Change: rename this model and its columns to your own domain. The folder it
-- reads comes from LANDING_PATH in your .env, not from anything in here.
--
-- This model is a table, not a view, and that is a deliberate choice. A view
-- would re-read every file in the landing folder for each model and each test
-- that selects from it, which is more than a dozen full reads per `dbt build`.
-- As a table the files are read once and everything downstream reads the
-- result. See dbt_project.yml.
with
    source as (

        -- `schemaHints` names the type of every field this model uses. Without
        -- it, JSON types are guessed from the files, and the guess can change:
        -- the day your source sends "1" instead of 1, a column that was a
        -- number becomes a string and something downstream breaks for a reason
        -- that has nothing to do with the model that broke. Naming the types
        -- means the guess cannot drift.
        --
        -- Change: list your own fields here, using the names the source sends,
        -- not the names you rename them to below.
        select
            *,
            _metadata.file_path as source_file,
            _metadata.file_modification_time as ingested_at
        from
            -- You do not need a raw table. `read_files` reads the JSON straight
            -- out of the landing folder, so there is no CREATE TABLE step to
            -- write and nothing to keep in sync: this staging model is the
            -- first thing that touches the data.
            --
            -- It handles a folder whose files do not all have the same shape:
            -- it infers one unified schema across every file it reads. A field
            -- only present in newer files is simply empty for the older rows
            -- rather than failing the read, so a source that adds a field next
            -- month needs no backfill and no change here.
            --
            -- https://docs.databricks.com/aws/en/sql/language-manual/functions/read_files
            read_files(
                '{{ var("landing_path") }}/postings', format => 'json', schemahints => '

                public_slug string,
                external_id string,
                source string,
                url string,

                title string,
                company string,
                company_slug string,
                location string,
                description string,

                countries array<string>,
                regions array<string>,
                cities array<string>,
                skills array<string>,

                work_mode string,
                is_tech string,

                posted_at string,
                created_at string,
                updated_at string,
                last_seen_at string,
                closed_at string,

                enrichment struct<
                    category:string,
                    company_size:string,
                    company_type:string,
                    domains: array<string>,
                    education_level:string,
                    employment_type:string,
                    english_level:string,
                    experience_years_min:int,
                    posting_language:string,
                    relocation:boolean,
                    requirements: array<struct<priority:string, text:string>>,
                    salary_currency:string,
                    salary_max:double,
                    salary_min:double,
                    salary_period:string,
                    seniority:string,
                    summary:string,
                    timezone_note:string,
                    visa_sponsorship:boolean
                >,

                reality struct<
                    age_days:int,
                    class:string,
                    fake_freshness:boolean,
                    mass_posting_count:int,
                    repost_count:int
                >
                '
            )

    ),

    renamed as (

        select
            -- Change: replace these with your source' s fields.keep the pattern:
            -- rename to your own names here, so nothing downstream depends on
            -- what the API happened to call things.
            -- Job identity
            external_id as source_job_id,
            public_slug,

            -- Basic job information
            trim(title) as title,
            trim(company) as company_name,
            company_slug,
            description as description_raw,

            -- Source / lineage
            source as original_source,
            url as source_url,

            -- Location values from FreeHire.
            -- We keep them raw here and normalize city/country later.
            nullif(trim(location), '') as location_raw,
            countries as countries_raw,
            regions as regions_raw,
            cities as cities_raw,

            -- Matching-related source values.
            -- These are still FreeHire's values, not our final business values.
            skills as skills_raw,
            work_mode as source_work_mode,

            -- FreeHire enrichment
            enrichment.category as source_category,
            enrichment.employment_type as source_employment_type,
            enrichment.seniority as source_experience_level,
            enrichment.experience_years_min as source_experience_years_min,
            enrichment.education_level as source_education_level,
            enrichment.posting_language as source_posting_language,
            enrichment.requirements as requirements_raw,

            -- Salary values provided by FreeHire enrichment.
            -- They can be NULL even when salary exists in the description,
            -- so final salary logic belongs in Intermediate.
            enrichment.salary_min as source_salary_min,
            enrichment.salary_max as source_salary_max,
            enrichment.salary_currency as source_salary_currency,
            enrichment.salary_period as source_salary_period,

            -- Optional company enrichment
            enrichment.company_type as source_company_type,
            enrichment.company_size as source_company_size,

            -- FreeHire tech/non-tech classification
            is_tech as source_is_tech,

            -- The raw file holds exactly what the source sent, and Arbeitnow
            -- sends Unix seconds. Converting here rather than during ingestion is
            -- deliberate: the landed file stays a faithful copy, and the moment a
            -- source changes its date format you can see it in this one line
            -- instead of re-reading three weeks of files. If your source sends an
            -- ISO string, cast it instead.
            -- Source lifecycle timestamps.
            -- FreeHire sends ISO timestamps, so we cast them here.
            cast(posted_at as timestamp) as posted_at,
            cast(created_at as timestamp) as source_created_at,
            cast(updated_at as timestamp) as updated_at,
            cast(last_seen_at as timestamp) as last_seen_at,
            cast(closed_at as timestamp) as closed_at,

            -- FreeHire freshness ,reality signals
            reality.class as source_freshness_class,
            reality.age_days as source_age_days,
            reality.repost_count as source_repost_count,
            reality.mass_posting_count as source_mass_posting_count,
            reality.fake_freshness as source_fake_freshness,
            -- The day whose folder this row was read from. It comes from the
            -- `ingest_date=<date>/` directory the ingestion job writes, and
            -- read_files turns that folder name into a column. Not the same as
            -- posted_at, which is when the source says the job was posted:
            -- this is when you saw it.
            -- Ingestion metadata
            source_file,
            ingest_date,
            ingested_at
        from source

    ),

    deduplicated as (

        -- One row per posting, keeping the most recently ingested version.
        --
        -- This is not optional tidying. `read_files` reads every file in the
        -- landing folder, and most sources still list the same record
        -- tomorrow, so
        -- on day two a posting that is still open appears twice. The `unique`
        -- test
        -- on posting_id then fails, the DAG goes red, and nothing is actually
        -- wrong with the data.
        --
        -- Keeping the newest version also means a posting that changed (a title
        -- edit, a closing date) reflects what the source says today rather than
        -- what it said the first time you saw it.
        select *
        from renamed
        qualify
            row_number() over (
                partition by
                    original_source,
                    lower(trim(title)),
                    coalesce(
                        nullif(
                            regexp_extract(source_job_id, '[^:]+$', 0), source_job_id
                        ),
                        right(source_job_id, 6)
                    )
                order by ingested_at desc
            )
            = 1
    )

select *
from deduplicated
