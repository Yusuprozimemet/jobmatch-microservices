# Day 17 — job-service becomes its own deployable

**Phase:** 3 · **Depends on:** Day 16 · **Expected PRs:** 3
**Status:** provisional — re-read and revise before starting.

## Goal
`job-service` runs as a separate container with its own image and pipeline. It still shares
the database and is still called in-process by nothing — the gateway routes to it directly.

## In scope
- Repo move: `backend/jobs` → `services/job-service`, with its own pom, Dockerfile,
  CI workflow and compose service. Copy the pattern from the Day 15 gateway.
- `shared` becomes a published artifact both services depend on, or is duplicated.
  **Decide today and write the decision down** — this choice repeats for every extraction.
- Gateway routes `/api/jobs`, `/api/jobs/filters`, `/api/jobs/*` to `job-service`.
  `/api/jobs/top-matches` still goes to the monolith.
- OTel agent, actuator, JWT validation — same setup as every other service.
- Database unchanged: both containers still connect to the same Postgres.

## Out of scope
- Internal endpoints — Day 18.
- Its own database — Day 20.

## Tracks

| Track | Owner | Work |
|---|---|---|
| A | | Repo move, pom, Dockerfile, CI |
| B | | Compose service, gateway routes, health checks |
| C | | `shared` distribution decision + implementation |

## Acceptance criteria
- [ ] `docker compose ps` shows `job-service` running with no published port.
- [ ] Day 03's job tests pass **unedited** against the gateway.
- [ ] `job-service` has no published port; only the gateway reaches it.
- [ ] A trace spans gateway → job-service.
- [ ] The monolith no longer serves `/api/jobs`.

## Verify
```bash
docker compose up -d --build
curl -s localhost:8080/api/jobs | head -c 200
docker compose logs backend | grep -c "GET /api/jobs" # expect 0
```

## Notes
- Easiest extraction in the plan: read-only, no user data. If this one is painful,
  stop and fix the process before attempting Day 21.
- The `shared` decision is the one that compounds. Prefer duplication of small DTOs
  over a shared artifact that couples deployments.
