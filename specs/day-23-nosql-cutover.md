# Day 23 — matching-service lets go of Postgres

**Phase:** 4 · **Depends on:** Day 22 · **Expected PRs:** 3
**Status:** provisional — re-read and revise before starting.

Day 22 took this day's first plan (the read switch, the purge deletions, the hit-rate metric):
with no dual write there was nothing to switch later. What is left is the Postgres side.

## Goal
`matching-service` starts with no Postgres configuration, and the scores table is gone.

## In scope
- Drop `matching.job_match_scores` in a `db/matching` V2, applied by the service as the table's
  owner, before its datasource goes. Not an app migration, and V10 is not edited.
- Remove the service's datasource, Flyway, the JDBC and Postgres dependencies, and its `DB_*` in
  compose, the harness (`support/MatchingService`) and `.env.example`.
- The `matching` role and schema: app's `V14__move_matching_tables.sql:22-27` raises when either
  is missing on a fresh database, so they cannot simply go from `PostgresContainer`,
  `scripts/db-init/` and `db-setup.py`. Decide here: keep them, or a new app migration.
- Resolve the overlap with Day 24's "no datasource and no Flyway configuration" criterion
  (`day-24:33`): one of the two days owns it.

## Out of scope
- Moving any other data to NoSQL. Nothing else in this app is a key-value cache.

## Tracks

| Track | Owner | Work |
|---|---|---|
| A | | `db/matching` V2 dropping the table |
| B | | Datasource, Flyway, dependencies, `DB_*` out |
| C | | Role and schema, per the decision above; docs |

## Acceptance criteria
- [ ] `matching-service` starts with no Postgres configuration present.
- [ ] `matching.job_match_scores` does not exist after the service has started on a fresh
      database.
- [ ] Day 04's matching tests pass **unedited**.

## Verify
```bash
backend/mvnw -B -f services/matching-service/pom.xml verify checkstyle:check
```

## Notes
- The table was a key-value cache wearing a relational costume; Day 22 moved the cache, this day
  takes the costume off.
