# applications's own migrations

Applied by applications's own Flyway instance, logging in as `applications_user`, with its history in
`applications.flyway_schema_history` (Day 11, see `app/.../config/Migrations.java`). It runs after
`app`'s V1–V14, which created this module's tables and moved them into schema `applications`.

The instance baselined at version 0, so the first migration here is `V1__<description>.sql`.
Name tables unqualified: `applications` is the search path.
