# Day 11 — Split Flyway migrations per module

**Phase:** 1 · **Depends on:** Day 10 · **Expected PRs:** 5

## Goal
Each module owns its own migrations and its own schema. No module can write another's tables.

## In scope
- Move every table into its owner's schema. Today every table is in schema `app`, created by
  V1–V11 in `backend/app/src/main/resources/db/migration`:

  | Module | Schema | Tables |
  |---|---|---|
  | `identity` | `identity` | `users`, `user_credentials`, `user_profiles`, `password_reset_tokens` |
  | `applications` | `applications` | `saved_jobs` |
  | `matching` | `matching` | `job_match_scores` |
  | `jobs` | `analytics` | read-only; owned by the data pipeline, **no migrations** |

- **Do not edit the existing V1–V11 files.** Flyway checksums them, and an edited applied
  migration fails every existing database on its next start. Add new `V*__move_*.sql`
  migrations that `ALTER TABLE ... SET SCHEMA`.
- **The moves run where V1–V11 ran, as the role that ran them.** Only a table's owner can move
  it, and V1–V11's tables belong to whoever applied them: the container's admin in compose and
  the tests, `app_user` in production. So the moves are V12–V14 in the existing
  `db/migration`, applied by the existing Flyway instance (history `app.flyway_schema_history`)
  over a connection as that owner. Each move ends by handing its tables to the module's role
  with `ALTER TABLE ... OWNER TO`, which is what makes the schemas the modules' own.
- **Then one Flyway instance per module**, each with its own location (`db/identity`,
  `db/applications`, `db/matching`), its own history table in its own schema, and its module's
  connection. They start empty and run after the existing instance. Day 12's
  `identity.refresh_tokens` is the first migration one of them applies.
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
- **Roles are a precondition, not a migration.** They are cluster-wide and carry passwords, so a
  migration does not create them. Three places do: `db-setup.py` for production, an init script
  compose runs on an empty volume, and the test harness. The owner that applies the moves must
  be a member of each module role, or `OWNER TO` is refused; `db-setup.py` already grants its
  admin membership the same way. A move that finds its role missing stops, naming
  `db-setup.py`, rather than leaving a table behind.
- **Five logins instead of one.** `DB_USER`/`DB_PASSWORD` stay, as the owner that runs V1–V14.
  Each module gets `DB_<MODULE>_USER`/`DB_<MODULE>_PASSWORD`, and `jobs` a login that can only
  read. `DB_SCHEMA` goes: every module knows its schema. Update the README's table, compose, and
  `.env.example`.
- **Transactions follow the connections.** `identity`'s `AuthenticationService` has four
  `@Transactional` methods, the only ones in the codebase. With a `DataSource` per module, each
  needs its own transaction manager, and those methods must use `identity`'s.
- Update `db-setup.py`, `docker-compose.yml` and the test harness for the new schemas. The
  harness is where this day can quietly edit tests it must not: see the note on `support/`.
- **Account deletion keeps working under the new roles.** Checked on Postgres 18 before the
  work: after the move `identity`'s role is refused an `INSERT` into `applications.saved_jobs`,
  yet deleting a user still removes that user's saved jobs, because Postgres runs a cascade as
  the owner of the referencing table. So the strict roles and `fk_saved_jobs_user` can coexist.

## Out of scope
- Separate database instances — Phase 3+. One database, several schemas, for now.

## Tracks

| Track | Owner | Work |
|---|---|---|
| 0 | | Account-deletion contract test, landed first |
| A | | Per-module Flyway config + history tables |
| B | | V12–V14 `SET SCHEMA` moves and ownership, in dependency order |
| C | | Roles in `db-setup.py`, the compose init script and the harness; the harness's schema assumptions; the logins |
| D | | One `DataSource` per module, each as its own role, with its own transaction manager |

Track 0 is the criterion the spec already asked for "before the move": `DELETE /api/users/me`
removes the user's saved jobs. No Day 01–04 test calls that route. It lands first, passes on the
single schema, and is broken on purpose (a foreign key without the cascade) before it is trusted.

## Acceptance criteria
- [x] **hold** — No migration that existed before the day is modified or deleted: the second
      command in **Verify** prints nothing. It prints nothing on `main` at the close; V12–V14 are
      the only additions. Broken on purpose in #68: an edit to a comment in V1 showed at once.
- [x] **new** — Each module has its own `flyway_schema_history` table. `ModuleMigrationsIT` (#71):
      one per module, owned by the module's role, starting at `0 BASELINE`. Red on `main` before
      #71 (6 of 7); a probe `V1` in `db/identity` was applied by identity's own instance.
- [x] **hold** — A clean `docker compose up` migrates from empty to current with no manual step.
      Run at the close in its own project and volume: 14 migrations, three module baselines,
      backend, db and frontend running. Broken on purpose in #68: on a database without the
      module roles, V12 stops with its message naming `db-setup.py` and `docker compose down -v`.
- [x] **new** — `identity`'s role cannot write `applications.saved_jobs` (test it).
      `ModuleConnectionsIT` (#70): `permission denied for table saved_jobs`, through the
      application's own pool. Red before #69; red again with identity's pool given
      `applications_user`.
- [x] **new** — Each module's `DataSource` logs in as its own role (assert `current_user` per
      module). Same test: `identity_user identity`, `applications_user applications`,
      `matching_user matching`, `jobs_user analytics`.
- [x] **hold** — `DELETE /api/users/me` still removes the user's saved jobs, across the schema
      boundary. `AccountDeletionIT` (#66), green before the move, after it (#68), and deleting as
      `identity_user`, which cannot write `saved_jobs` (#69). Broken on purpose in #66 by
      dropping the foreign key: 2 rows left.
- [x] **hold** — All Day 1–4 tests pass **unedited**, with one departure. No existing file in
      `contract/` changed. `BackendApplicationTests.flywayHasMigratedTheAppSchema`, a Day 01
      harness self-test outside `contract/`, asserted every table was in `app`, the layout this
      day exists to change; it was rewritten in #68 to assert the new one, by decision, and is
      recorded below.

## Verify
```bash
docker compose down -v && docker compose up -d --build
cd backend && ./mvnw clean verify && ./mvnw -B checkstyle:check
# BASE = 4b1c897, the main commit Day 11 started from. Only additions are allowed:
git diff --stat --diff-filter=MDR "$BASE" HEAD -- app/src/main/resources/db/migration
```

## Notes
- **End of Phase 1.** At this point the modules are real services in everything but
  deployment, and everything is still one reversible process. Good place to stop if
  time runs out.
- **The test harness assumes schema `app` in two places,** and both are in `support/`, which
  may change: `TestDatabase.reset()` truncates only tables in `app`, and the harness connects
  with `currentSchema=app`. Three contract test files write tables unqualified:
  `AuthPasswordResetIT` (`password_reset_tokens`), `JobSavedCountIT` (`saved_jobs`) and Day 10's
  `SessionWithoutAUserIT` (`users`). Give the harness connection a `search_path` covering
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
- **Spec corrected on Day 11, before the work.** Read against the code, and the ownership
  questions tried on a throwaway Postgres 18 container, since reading could not settle them:
  - **The moves could not have run where the spec implied.** It gave each module its own Flyway
    and had the moves as that module's migrations. A module role moving a table it does not own
    gets `permission denied for schema app`: only the owner can move a table, and V1–V11's
    tables belong to the role that applied them. The moves now run where V1–V11 did, as that
    role, and hand each table over with `OWNER TO`.
  - **Nothing created the roles in compose.** The backend logs in as `POSTGRES_USER`, and
    `db-setup.py` is a manual step compose never runs, so the clean-start criterion and the role
    criteria could not both hold. Roles are now a precondition with three named creators.
  - **The cascade question had to be tried, not read.** Would `identity`'s role, forbidden to
    write `saved_jobs`, still delete them through `fk_saved_jobs_user`? Yes: Postgres runs the
    cascade as the referencing table's owner. The deletion criterion is kept as written.
  - **Four things the spec did not mention:** `identity`'s four `@Transactional` methods need a
    transaction manager per `DataSource`; the logins grow from one to five, and `DB_SCHEMA` goes;
    the harness has three unqualified writers, not two, the third added on Day 10; and Day 12's
    `identity.refresh_tokens` is the first migration a module instance applies.
  - Criteria tagged `new` or `hold` under #55's rule. Track 0 made explicit: the spec asked for
    the deletion test "before the move" without a track to land it in. *Estimate 4 → 5.*
- **Done on Day 11.** Spec change #65, then #66 (Track 0, account deletion), #67 (Track C, the
  roles and schemas in three places), #68 (Track B, the moves), #69 and #70 (Track D, a
  connection per module and the tests that prove it, split under the 400-line gate) and #71
  (Track A, a Flyway per module). 232 tests green, checkstyle clean.
  *Estimated 3 pull requests as first written, 5 after the spec change, took 6.* The sixth is the
  gate's split of Track D.
- **The tracks ran C → B → D → A, not in the table's order.** Each depends on the one before:
  the moves hand tables to roles that must exist, the connections log in as those roles, and
  the module migrations run on those connections.
- **The one departure from "unedited", by decision.** `BackendApplicationTests` pinned the schema
  layout, not behaviour. It was stopped at, not patched: the choice between rewriting it,
  replacing it, and amending the criterion first went to review, and rewriting it won.
- **Found by running what reading could not settle:**
  - Only a table's owner can move it, so the moves run as the owner that ran V1–V11 and hand
    each table over (#65, tried on Postgres 18 before the work).
  - A cascade runs as the owner of the referencing table, so `identity_user`, forbidden to write
    `saved_jobs`, still deletes them through `fk_saved_jobs_user` (#65, then #69 in the app).
  - V2's `job_state` enum does not move with `saved_jobs`, and the repository casts to it
    unqualified. V13 moves it too (#68). Neither the spec nor #65 mentioned it.
  - A read-only Hikari pool does not stop an autocommit write; the driver applies read-only only
    inside explicit transactions. The role is the guard, and the flag is gone (#69).
  - Flyway writes its baseline description into its history unescaped; an apostrophe failed
    every context (#71).
  - Four pools of Hikari's default ten, opened at startup by the module migrations, ran the test
    run's cached contexts out of Postgres's connection slots. Each module pool is now at most
    five, one kept idle (#71).
- **Deployment changes.** Five logins instead of one: `DB_USER`/`DB_PASSWORD` are the migration
  owner, and each module has `DB_<MODULE>_USER`/`_PASSWORD`; `DB_SCHEMA` is gone. A compose
  volume from before this day stops at V12 with its message and needs `docker compose down -v`.
  Production needs `db-setup.py` re-run first, for the roles, the schemas, and `app_user`'s
  membership in the module roles.
- `db-setup.py`'s final report crashes on a Windows console using cp1252, after all its work is
  done, because it prints an emoji. Found in #67, not changed; `PYTHONIOENCODING=utf-8` avoids it.
