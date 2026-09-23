# Day 25 — application-service becomes its own deployable

**Phase:** 5 · **Depends on:** Day 24 · **Expected PRs:** 3
**Status:** provisional — re-read and revise before starting.

## Goal
Saved jobs and the tracker run as their own service with their own database.

## In scope
- `backend/applications` → `services/application-service`: own pom, Dockerfile, CI, compose.
- Gateway routes `/api/saved-jobs/**` to it.
- New `apps_db` holding `saved_jobs`, migrated with a `SET SCHEMA`-style move — do not
  edit existing migration files.
- Hydration already goes over HTTP from Day 19; verify it still does.
- `POST /internal/saved-counts` moves with it. The monolith has served it since Day 17;
  `job-service`'s client now points at `application-service`.
- Day 08's `JobSavedCountQueriesIT` expires today: it counts statements in the shared
  database, and `saved_jobs` leaves it. Delete it, or rewrite it against `apps_db` inside
  `application-service`'s own tests.
- `userId` comes from the JWT `sub` forwarded by the gateway. It never reads `users`.

## Out of scope
- Events — Day 26.

## Tracks

| Track | Owner | Work |
|---|---|---|
| A | | Repo move, pom, Dockerfile, CI, compose, gateway route |
| B | | `apps_db` + migration move + roles |
| C | | `/internal/saved-counts` + `job-service` client |

## Acceptance criteria
- [ ] Day 04's saved-jobs tests pass **unedited**.
- [ ] Day 03's `savedCount` test passes **unedited**, now over HTTP.
- [ ] `application-service` cannot read `analytics` or `identity` tables (assert roles).
- [ ] With `application-service` stopped, job search still returns results with
      `savedCount: 0`.
- [ ] No published port.

## Verify
```bash
docker compose up -d --build
docker compose stop application-service
curl -s localhost:8080/api/jobs | head -c 200        # still 200
```

## Notes
- Two services now call each other in a cycle: jobs ↔ applications. Confirm neither
  fallback can trigger the other, or a single outage cascades.
