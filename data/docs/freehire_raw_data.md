# FreeHire Raw Data

## Source

The job data is collected from the FreeHire API.

The ingestion pipeline retrieves job postings for the Netherlands and lands the
API response in the raw data layer before dbt performs cleaning,
standardisation, or enrichment.

The ingestion flow is:

FreeHire API
- fetch records
- validate records with Pydantic
- land original raw records as NDJSON
- dbt staging
- intermediate models
- marts

The source is fetched using paginated API requests. We set `MAX_OFFSET_LIMIT = 5000` as a project-level limit for each ingestion run.


## Raw data format

The raw data is stored as NDJSON (newline-delimited JSON).

Each line represents one original job record returned by the FreeHire API.

The raw records are not cleaned, normalised, enriched, or reshaped before they
are written to the landing layer. Transformations are performed later by dbt.

Important raw fields include:

## Job identity

- `public_slug`
- `source`
- `external_id`

`source` identifies the original source/provider of the posting.

`external_id` identifies the job within its original source.

`public_slug` is also used by the ingestion validation logic to identify
duplicate records within the current batch.

Downstream models use fields including:

- `original_source`
- `source_job_id`
- `posting_id`

`original_source` and `source_job_id` preserve the source identity from the raw
data for downstream processing.

`posting_id` is not a raw FreeHire field. It is a canonical identifier created
later in the dbt transformation layer and used by downstream posting models.

## Job information

Important job fields include:

- `title`
- `company`
- `url`
- `location`
- `work_mode`

## Geographic information

FreeHire records can contain several location-related fields:

- `location`
- `countries`
- `regions`
- `cities`

## Skills and requirements

Job records can contain information such as:

- `skills`
- requirement information within the source data/enrichment structures

## Job timestamps and lifecycle information

Important source timestamps include:

- `posted_at`
- `created_at`
- `updated_at`
- `last_seen_at`
- `closed_at`

## Enrichment and source-quality fields

FreeHire records can also contain information such as:

- `enrichment`
- `reality`


## Example raw fields

The raw FreeHire records contain multiple fields. Some examples include:


- public_slug
- source
- external_id
- url
- title
- company
- location
- countries
- regions
- cities
- work_mode
- skills
- posted_at
- created_at
- updated_at
- last_seen_at
- closed_at
- enrichment
- reality

## Validation

Incoming FreeHire records are validated by `parse_records()` using the
`Posting` Pydantic model.

For each record, the validation checks that:

- the record is a JSON object/dictionary;
- the record matches the expected FreeHire job structure;
- required fields are present;
- `public_slug` is not duplicated within the current batch.

Required fields are:

- public_slug
- title
- company
- url
- created_at

The following fields are preserved when available:

- location
- countries
- regions
- cities
- work_mode
- skills
- posted_at
- updated_at
- last_seen_at
- closed_at

Some FreeHire records can contain missing or empty values for these optional
fields. A missing optional field does not make the complete job record invalid.

Invalid or duplicate records are counted as rejected without stopping the
validation of the remaining records.

Pydantic validation is used as a quality gate, but the pipeline deliberately
lands the original FreeHire records rather than the parsed Pydantic objects.

## Validation result

The FreeHire validation is covered by automated tests within the project's
Python test suite.

Current Python test result:
- complete Python test suite: 78 passed

The ingestion tests cover:

- valid FreeHire records;
- missing required fields;
- invalid non-object records;
- optional missing or empty fields;
- datetime parsing;
- `closed_at = null`;
- batch processing with invalid records;
- duplicate `public_slug` values.

The validation has also been tested with real FreeHire job records through the
integrated ingestion pipeline.

## Ingestion metadata

The raw data is stored in environment-specific landing paths.

For local development, ingestion writes to a personal development prefix:

`dev/<LANDING_PREFIX>/postings`

For the shared ACA development ingestion, the raw posting files are available
to Databricks under:

`/Volumes/team_c/landing/dev/aca-dev/postings`

The pipeline logs additional ingestion information, including:

- number of received records
- number of parsed records
- number of rejected records
- number of landed records
- landing location

The original FreeHire job records are preserved as received from the API.

When dbt reads the raw files, the staging layer adds lineage fields such as:

- `source_file` — the raw file the record came from
- `ingest_date` — the ingestion date
- `ingested_at` — when the source file was ingested

These fields help trace transformed records back to the raw data and identify
the latest version when the same job appears in multiple ingestion snapshots.

## Data quality observations

During validation of the FreeHire data, several differences in data completeness
and representation were observed:

- `cities` can be empty.
- `skills` can be empty.
- `work_mode` can be missing.
- `location` is not always represented in the same format.
- `cities` may sometimes contain values that are not strictly city names.
- `closed_at` can be null.
- A job can have `closed_at = null` while its `reality.class` is `stale`.
- optional enrichment information can be missing;


All available values are preserved in the raw data layer.

Cleaning, standardisation, and fallback logic should be handled later in the
transformation or staging layer.

The ingestion layer is responsible for:

1. fetching source data.
2. validating incoming records.
3. preserving the original source representation.
4. landing the records reliably.
5. recording ingestion information.

The dbt transformation layers are responsible for:

1. cleaning and standardising the data.
2. creating the `posting_id` used by downstream posting models.
3. normalising nested structures such as cities, skills, and requirements.
4. handling duplicate records.
5. applying enrichment and business logic.
6. producing application-ready data.

This separation keeps the pipeline traceable and makes it possible to compare
the transformed data with the original source when debugging data-quality
issues.