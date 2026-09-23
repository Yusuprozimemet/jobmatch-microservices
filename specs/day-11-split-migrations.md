# Day 11 — Split Flyway migrations per module

**Phase:** 1 · **Depends on:** Day 10 · **Expected PRs:** 4

## Goal
Each module owns its own migrations and its own schema. No module can write another's tables.

## In scope
- Split `backend/app/src/main/resources/db/migration` by owner. Today every table is in
  schema `app`:

  | Module | Schema | Tables |
  |---|---|---|
  | `identity` | `identity` | `users`, `user_credentials`, `user_profiles`, `password_reset_tokens` |
  | `applications` | `applications` | `saved_jobs` |
  | `matching` | `matching` | `job_match_scores` |
  | `jobs` | `analytics` | read-only; owned by the data pipeline, **no migrations** |

- One Flyway instance per module, each with its own history table and location.
- **Do not edit the existing V1–V11 files.** Flyway checksums them, and an edited applied
  migration fails every existing database on its next start. Add new `V*__move_*.sql`
  migrations that `ALTER TABLE ... SET SCHEMA`.
- **Keep `fk_saved_jobs_user`.** `saved_jobs.user_id` references `users(id)` with
  `ON DELETE CASCADE`; after the move that key crosses from `applications` into `identity`.
  It stays: it is how deleting an account removes saved jobs until Day 27 replaces it with
  the `user.deleted` event. Moving a table with `SET SCHEMA` keeps its constraints.
- **One role per schema, and each module connects as its own.** One `DataSource` per module,
  logging in as that module's role with `search_path` set to its schema, so unqualified SQL
  keeps resolving and a write into another module's schema is refused by Postgres rather than
  by convention. With a single shared connection, the role criterion below would hold in the
  database and prove nothing about the application. `jobs` connects read-only to `analytics`.
- Roles mirror `scripts/db-setup.py`, which grants a role full access to the schemas it owns
  and **read-only access to all the others**. That keeps reads across schemas possible at the
  database level; Days 08–09 are what removed them from the code.
- Update `db-setup.py`, `docker-compose.yml` and the test harness for the new schemas. The
  harness is where this day can quietly edit tests it must not: see the note on `support/`.

## Out of scope
- Separate database instances — Phase 3+. One database, several schemas, for now.

## Tracks

| Track | Owner | Work |
|---|---|---|
| A | | Per-module Flyway config + history tables |
| B | | `SET SCHEMA` migrations, in dependency order |
| C | | `db-setup.py` roles, compose, and the test harness's schema assumptions |
| D | | One `DataSource` per module, each as its own role |

## Acceptance criteria
- [ ] No migration that existed before the day is modified or deleted: the second command in
      **Verify** prints nothing.
- [ ] Each module has its own `flyway_schema_history` table.
- [ ] A clean `docker compose up` migrates from empty to current with no manual step.
- [ ] `identity`'s role cannot write `applications.saved_jobs` (test it).
- [ ] Each module's `DataSource` logs in as its own role (assert `current_user` per module).
- [ ] `DELETE /api/users/me` still removes the user's saved jobs, across the schema boundary.
      No Day 01–04 test covers account deletion, so this needs one: write it **before** the
      move, see it pass on the single schema, and keep it passing after.
- [ ] All Day 1–4 tests pass **unedited**.

## Verify
```bash
docker compose down -v && docker compose up -d --build
cd backend && ./mvnw clean verify && ./mvnw -B checkstyle:check
# BASE = the main commit Day 11 started from. Only additions are allowed:
git diff --stat --diff-filter=MDR "$BASE" HEAD -- app/src/main/resources/db/migration
```

## Notes
- **End of Phase 1.** At this point the modules are real services in everything but
  deployment, and everything is still one reversible process. Good place to stop if
  time runs out.
- **The test harness assumes schema `app` in two places,** and both are in `support/`, which
  may change: `TestDatabase.reset()` truncates only tables in `app`, and the harness connects
  with `currentSchema=app`. Two contract test files write `saved_jobs` and
  `password_reset_tokens` unqualified. Give the harness connection a `search_path` covering
  every module schema and reset every module schema; then `contract/` needs no edit. If a
  contract test does need one, that is the stop-and-investigate signal, not a fix.
- **Spec corrected on Day 09, before the work, from a read of every remaining spec:**
  - The migration path was `backend/src/...`; Day 06 moved it to `backend/app/src/...`.
  - `user_credentials` was missing from the table list.
  - Commit `55dc5a9` is not in this repository — its history starts from a squashed snapshot —
    so the reason is stated instead of the hash.
  - The migration check compared the working tree with `HEAD`, which is empty after any commit,
    and matched only `V1` and `V11`. It now compares with the day's starting commit, over every
    file, and only additions pass.
  - The cross-schema foreign key, the single shared connection that would have made the role
    criterion decorative, and the harness's hardcoded schema were not mentioned.
  - Expected PRs 3 → 4: per-module data sources are a track of their own.
  - **Account deletion has no contract test.** `DELETE /api/users/me` exists and nothing in
    Days 01–04 exercises it, so a broken cascade here would go unseen until Day 27, which
    rebuilds exactly that path. The criterion above adds the missing test first.
