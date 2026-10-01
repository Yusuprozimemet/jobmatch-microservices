# Day 25 — application-service becomes its own deployable

**Phase:** 5 · **Depends on:** Day 27 · **Expected PRs:** 5 (the spec change, Tracks A–C, the
close)
**Status:** provisional — revised for the plan's order in Day 26's spec change. Re-read against
the code, with the spec-auditor, before starting.

Third of Phase 5 (**26 → 27 → 25 → 28**). The `user.deleted` consumer already serves (Day 27),
so the table can move: the foreign key that deleted saved jobs with their user cannot cross into
a database of its own, and goes with the old table.

## Goal
Saved jobs and the tracker run as their own service with their own database, and deleting an
account still removes them, now only by `user.deleted`.

## In scope
- `backend/applications` → `services/application-service`: own pom, Dockerfile, CI, compose
  (no published port), gateway route `/api/saved-jobs/**`. The Day 27 consumer moves with it.
- **`apps_db`**, holding `saved_jobs` and the `job_state` type, migrated by application-service's
  own Flyway as `applications_user`; created by `db-setup.py`, `db-init/` and the harness, as
  Day 20 did `jobs_db`. Existing rows are copied from `project_db` by a re-runnable step that
  skips rows already there.
- **The key goes with the old table.** V13 says `fk_saved_jobs_user` "stays until Day 27"; the
  plan moved it to the table move. A new monolith migration drops `applications.saved_jobs` from
  `project_db`, and the key with it, in the last track: only after application-service serves
  from `apps_db` and the consumer reads its queue there. Never an edit to V13.
- `POST /internal/saved-counts` moves with it; job-service's `INTERNAL_APPLICATIONS_URL`
  (`docker-compose.yml:95`) points at application-service.
- **The deleted-user rule** (Day 39): `userId` comes from the token's `sub`, and
  application-service asks identity `GET /internal/users/{id}` before acting, as matching-service
  does. It never reads `users`.
- The harness (Day 40's hand-off): an entry in `support/`'s route table, a container from its
  image on the test Postgres, a gateway route with an upstream URL of its own.
- **`contract/AccountDeletionIT`, the one approved edit** (the maintainer, 2026-10-01, on the
  Phase 5 audit): after the move it reads a table that has left `project_db`, and deletion now
  arrives later through SQS. The edit changes only where and when rows are counted:
  `savedJobsOf` reads `apps_db` through a `support/` helper and waits up to 10 s for the expected
  count; `leavesEveryoneElsesSavedJobsAlone` first waits for the deleted user's rows to reach zero
  (Day 26's break showed it passes when nothing is deleted). Its three tests and their assertions
  stay.
- `queries/JobSavedCountQueriesIT` expires: it counts statements in `project_db`. Rewritten
  against `apps_db` in application-service's own tests.
- Day 41's hand-off: `queries/CurrentUserQueriesIT.savingAJobLooksTheUserUpOnce` goes through the
  monolith's resolver today. After the move, application-service's existence call is the one
  `users` statement, and the test stays unedited.

## Out of scope
- Replacing the existence call with an event-fed record — decided against on Day 27.
- identity-service and `identity_db` — Day 28.

## Tracks

| Track | Owner | Work |
|---|---|---|
| A | | application-service serving beside the monolith: repo move, `apps_db`, Flyway, harness, compose; the gateway still routes to the backend |
| B | | Cut over: gateway route, job-service's client, the copy step, `AccountDeletionIT`'s edit |
| C | | Remove `backend/applications`; the migration that drops the old table and the key |

Seam first: no track removes the monolith's applications code before application-service
serves (`plan.md`, "Course correction").

## Acceptance criteria (provisional: tagged and given their red-today in the rewrite)
- [ ] Day 04's saved-jobs tests and Day 03's `savedCount` test pass unedited, over HTTP.
- [ ] `AccountDeletionIT` passes with the approved edit and no other; its diff is that edit.
- [ ] `CurrentUserQueriesIT` passes unedited. Broken on purpose: two existence calls per request
      must report 2.
- [ ] `fk_saved_jobs_user` and `project_db`'s `applications.saved_jobs` are gone, by a new
      migration; `git log` shows V13 unedited.
- [ ] `application-service` cannot read `identity` or `analytics` tables (roles asserted).
- [ ] With `application-service` stopped, job search still returns results with `savedCount: 0`.
- [ ] The copy step run twice leaves the same row count as once.

## Verify
```bash
docker compose -p day25 --env-file .env.example up -d --build --wait
docker compose -p day25 stop application-service
curl -s localhost:8080/api/jobs | head -c 200        # still 200, savedCount 0
docker compose -p day25 down -v
```

## Notes
- Two services call each other: jobs ↔ applications. Confirm neither fallback can trigger the
  other, or one outage cascades.
- `backend/docs/schema.md` describes `saved_jobs` in `project_db`; it changes with the table.
