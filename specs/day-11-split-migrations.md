# Day 11 — Split Flyway migrations per module

**Phase:** 1 · **Depends on:** Day 10 · **Expected PRs:** 3

## Goal
Each module owns its own migrations and its own schema. No module can write another's tables.

## In scope
- Split `backend/src/main/resources/db/migration` by owner:

  | Module | Schema | Tables |
  |---|---|---|
  | `identity` | `identity` | `users`, `user_profiles`, `password_reset_tokens` |
  | `applications` | `applications` | `saved_jobs` |
  | `matching` | `matching` | `job_match_scores` |
  | `jobs` | `analytics` | read-only; owned by the data pipeline, **no migrations** |

- One Flyway instance per module, each with its own history table and location.
- **Do not edit the existing V1–V11 files.** Flyway checksums them and commit `55dc5a9`
  exists because someone already learned this. Add new `V*__move_*.sql` migrations that
  `ALTER TABLE ... SET SCHEMA`.
- One Postgres role per schema, mirroring `scripts/db-setup.py`.
- Update `db-setup.py` and the test harness fixtures for the new schemas.

## Out of scope
- Separate database instances — Phase 3+. One database, several schemas, for now.

## Tracks

| Track | Owner | Work |
|---|---|---|
| A | | Per-module Flyway config + history tables |
| B | | `SET SCHEMA` migrations, in dependency order |
| C | | `db-setup.py` roles + Day 01 fixtures |

## Acceptance criteria
- [ ] No file under V1–V11 is modified (`git diff --stat` on those paths is empty).
- [ ] Each module has its own `flyway_schema_history` table.
- [ ] A clean `docker compose up` migrates from empty to current with no manual step.
- [ ] `identity`'s role cannot write `applications.saved_jobs` (test it).
- [ ] All Day 1–4 tests pass **unedited**.

## Verify
```bash
docker compose down -v && docker compose up -d --build
cd backend && ./mvnw verify
git diff --stat HEAD -- '*/V1__*' '*/V11__*'   # expect empty
```

## Notes
- **End of Phase 1.** At this point the modules are real services in everything but
  deployment, and everything is still one reversible process. Good place to stop if
  time runs out.
