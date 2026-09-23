# Day 24 — matching-service talks to identity over HTTP

**Phase:** 4 · **Depends on:** Day 23 · **Expected PRs:** 3
**Status:** provisional — re-read and revise before starting.

## Goal
`matching-service` has no database and no shared code path with the monolith. It is
fully independent.

## In scope
- `identity` exposes `GET /internal/profiles/{userId}/skills`, returning the
  `ProfileSnapshot` shape (`ProfileDirectory`, Day 07). Service-token auth, same as Day 18.
- `matching-service` uses it through a Day 19-style client with timeout, retry and
  circuit breaker.
- Fallback: identity unreachable → 503 with a clear message. Matching without a profile
  is meaningless, so do not degrade silently.
- Short-lived local cache of profile skills (seconds), keyed by user, to avoid one call
  per request. Measure before adding it.
- `matching-service` removes every remaining dependency on `shared` persistence code.

## Out of scope
- Caching postings. Measure first.

## Tracks

| Track | Owner | Work |
|---|---|---|
| A | | Identity internal endpoint + tests |
| B | | Client, resilience, fallback |
| C | | Dependency cleanup + failure tests |

## Acceptance criteria
- [ ] `matching-service` has no datasource and no Flyway configuration.
- [ ] With `identity` stopped, top-matches returns 503 promptly, not a hang.
- [ ] The 422 too-few-skills behaviour is unchanged (Day 04's test, unedited).
- [ ] A user token is rejected on `/internal/profiles/**`.
- [ ] A top-matches trace spans four hops: gateway, matching, identity, jobs.

## Verify
```bash
docker compose stop backend
curl -s -o /dev/null -w '%{http_code}' localhost:8080/api/jobs/top-matches   # 503, fast
```

## Notes
- **End of Phase 4.** Two services are fully independent, and the highest-latency code
  path is isolated from everything else.
