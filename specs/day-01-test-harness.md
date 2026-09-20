# Day 01 — Integration test harness

**Phase:** 0 · **Depends on:** — · **Expected PRs:** 3

## Goal
Any test can boot the app against a throwaway Postgres that already has the app schema,
a populated mart, and a logged-in user — in one annotation.

## In scope
- Testcontainers Postgres, reused across the whole test run (not per class).
- `IntegrationTest` base class: `@SpringBootTest(webEnvironment = RANDOM_PORT)` + Flyway + fixtures.
- **Mart fixtures.** `analytics.fct_postings`, `fct_postings_cities`, `fct_postings_skills`
  are created by the data pipeline, *not* by Flyway. Tests must create and seed them.
- Builders: `aUser()`, `aProfile()`, `aPosting()` — small, chainable, no YAML fixtures.
- Helper that returns an authenticated HTTP client for a given user.
- Wire `verify` into `.github/workflows/backend-ci-cd.yaml`.

## Out of scope
- Tests of any actual endpoint — Days 2–4.

## Tracks

| Track | Owner | Work |
|---|---|---|
| A | | Container + `IntegrationTest` base class |
| B | | `analytics` schema DDL + seed SQL for the three mart tables |
| C | | Test data builders + authenticated-client helper |
| D | | CI: run integration tests on every PR |

## Acceptance criteria
- [ ] A test class extending `IntegrationTest` starts with zero extra setup.
- [ ] One Postgres container is started per run, not per test class.
- [ ] `analytics.fct_postings` has ≥20 seeded rows with skills, cities and dates.
- [ ] `authenticatedAs(user)` returns a client whose calls reach an authenticated endpoint.
- [ ] Each test starts from a clean `app` schema — no leakage between tests.
- [ ] Full suite runs in under 3 minutes locally.
- [ ] Backend CI runs the suite and fails the PR when a test fails.

## Verify
```bash
cd backend && ./mvnw verify
```

## Notes
- The mart fixture is the piece most likely to be wrong. Mirror the real column types
  from `data/dbt/models/marts/fct_postings.sql`, not from guesses.
- `scripts/db-setup.py` documents the schema and role layout — read it before writing DDL.
