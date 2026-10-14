# identity's own migrations

Applied by identity's own Flyway instance, logging in as `identity_user`, with its history in
`identity.flyway_schema_history` (Day 11, see `app/.../config/Migrations.java`). It runs after
`app`'s V1–V14, which created this module's tables and moved them into schema `identity`.

The instance baselined at version 0, so the first migration here is `V1__<description>.sql`.
Name tables unqualified: `identity` is the search path.
