# Day 24 — matching-service stands alone

**Phase:** 4 · **Depends on:** Day 23 · **Expected PRs:** 3
**Status:** provisional — re-read and revise before starting.

On Day 21 the profile endpoint, its client, the 503 fallback, the user id from `sub` and the
existence call moved to Day 41, which runs first in Phase 4 (`plan.md`). What is left here is
what makes matching-service independent once its scores have left Postgres (Day 23).

## Goal
`matching-service` has no database and no shared code path with the monolith. It is
fully independent.

## In scope
- Short-lived local cache of profile skills (seconds), keyed by user, to avoid one call
  per request. Measure before adding it. The existence answer is not cached
  (`InternalUserController`: a cached answer lets a deleted user in for as long).
- `matching-service` removes every remaining dependency on `shared` persistence code.
- The end-of-phase checks below, against compose, with every hop a network hop.

## Out of scope
- Caching postings. Measure first.
- The profile endpoint and client, their fallback, retry, and the user id — Day 41.

## Tracks

| Track | Owner | Work |
|---|---|---|
| A | | Measure; the profile cache if the measurement asks for it; whether Day 41's "no retry" still holds over the network |
| B | | Dependency cleanup + failure tests |

## Acceptance criteria
- [ ] **hold** (Day 23's, by its Track B) — `matching-service` has no datasource and no Flyway
      configuration: Day 23's `git grep` over `services/matching-service` finds nothing.
- [ ] With `identity` stopped, top-matches returns 503 promptly, not a hang.
- [ ] The 422 too-few-skills behaviour is unchanged (Day 04's test, unedited).
- [ ] A top-matches trace spans four hops: gateway, matching, identity, jobs.

## Verify
```bash
docker compose stop backend
curl -s -o /dev/null -w '%{http_code}' localhost:8080/api/jobs/top-matches   # 503, fast
```

## Notes
- **End of Phase 4.** Two services are fully independent, and the highest-latency code
  path is isolated from everything else.
