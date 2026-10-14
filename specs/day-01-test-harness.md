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
- [x] A test class extending `IntegrationTest` starts with zero extra setup. `BackendApplicationTests` (#1)
- [x] One Postgres container is started per run, not per test class. `TestHarnessTest.startsOneContainerForTheWholeRun` with `SecondHarnessTest.sharesTheContainerWithEveryOtherTestClass` (#1)
- [x] `analytics.fct_postings` has ≥20 seeded rows with skills, cities and dates. 24 rows, 23 with skills, all with cities and dates: `TestHarnessTest.seedsTheMartWithPostingsCitiesAndSkills` (#1)
- [x] `authenticatedAs(user)` returns a client whose calls reach an authenticated endpoint. `TestHarnessTest.authenticatedAsReachesAnAuthenticatedEndpoint` and `anonymousIsRejectedByAnAuthenticatedEndpoint` (#1)
- [x] Each test starts from a clean `app` schema — no leakage between tests. `TestHarnessTest.startsFromACleanAppSchema` and `startsFromACleanAppSchemaEveryTime`, which pass together only if the reset runs between them (#1)
- [x] Full suite runs in under 3 minutes locally. 45 s at #1, on Day 01's suite; not a lasting bound, as the suite has since grown into each service's own
- [x] Backend CI runs the suite and fails the PR when a test fails. `./mvnw -B verify` in the backend workflow (#1), now `identity-service-ci-cd.yaml`

## Verify
```bash
cd backend && ./mvnw verify
```

## Notes
- The mart fixture is the piece most likely to be wrong. Mirror the real column types
  from `data/dbt/models/marts/fct_postings.sql`, not from guesses.
- `scripts/db-setup.py` documents the schema and role layout — read it before writing DDL.
- **Took 6 PRs, not the 3 estimated.** The harness is ~1,400 changed lines against the
  400-line gate in `pr-checks.yml`, and the pieces have a compile order, so three PRs would
  each have needed an `Oversized:` override. Split that keeps every PR green on its own:
  mart fixtures → container + reset + test profile → `PostingBuilder` + records →
  `UserBuilder` + `ProfileBuilder` → `IntegrationTest` + HTTP client → self-tests + CI + docs.
- The exact mart column *types* came from `data/sql/job_schema.sql`, not from the dbt model:
  the model is Databricks SQL, and `sync.py` maps those types to Postgres ones on the way in.
- **Boxes ticked late (2026-10-14, after the whole-plan audit).** PR #1 ticked each criterion
  with its evidence in its description, but the spec's boxes were left empty; they now carry
  #1's evidence. No break is recorded: Day 01 predates the `broken:` line.
