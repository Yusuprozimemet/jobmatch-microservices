# Day 21 — matching-service becomes its own deployable

**Phase:** 4 · **Depends on:** Day 20 · **Expected PRs:** 3
**Status:** provisional — re-read and revise before starting.

## Goal
The LLM call runs in its own container, so a slow model can no longer consume threads
that serve job search.

## In scope
- `backend/matching` → `services/matching-service`: own pom, Dockerfile, CI, compose service.
- Gateway routes `GET /api/jobs/top-matches` to `matching-service`.
- It calls `job-service`'s shortlist endpoint using the Day 19 client.
- `ProfileSkills` still resolves in-process via the shared database, for one more day.
- The LLM API key moves to this service alone. No other service gets it.
- Dedicated thread pool and a hard request timeout below the gateway's.

## Out of scope
- NoSQL — Days 22 and 23.
- `ProfileSkills` over HTTP — Day 24.

## Tracks

| Track | Owner | Work |
|---|---|---|
| A | | Repo move, pom, Dockerfile, CI, compose |
| B | | Gateway route, shortlist client wiring |
| C | | Config split: LLM key, timeouts, thread pool |

## Acceptance criteria
- [ ] Day 04's matching tests pass **unedited** against the gateway.
- [ ] The monolith no longer holds the LLM key (grep the config).
- [ ] With the LLM stub hanging, `/api/jobs` latency is unchanged — this is the point
      of the whole phase, so measure it.
- [ ] A trace spans gateway → matching-service → job-service → LLM.
- [ ] `matching-service` has no published port.

## Verify
```bash
docker compose up -d --build
# with a hanging LLM stub configured:
time curl -s localhost:8080/api/jobs > /dev/null      # unaffected
```

## Notes
- The latency measurement is the acceptance criterion that matters. Take a baseline
  before the split so the comparison is real.
