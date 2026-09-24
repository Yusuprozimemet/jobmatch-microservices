# Day 18 — job-service internal API and service-to-service auth

**Phase:** 3 · **Depends on:** Day 17 · **Expected PRs:** 3
**Status:** provisional — re-read and revise before starting.

## Goal
Other services can ask `job-service` for postings over HTTP, authenticated as services
rather than as users.

## In scope
- `POST /internal/postings/batch` — body: posting ids; returns the `PostingSummary` fields
  from Day 09. Caps the id count; rejects oversized requests.
- `POST /internal/postings/shortlist` — body: city, skills, limit; returns the ranked
  shortlist `PostingShortlist` produces. Day 09 moved that SQL into `jobs`, so it is already
  in `job-service`; this puts HTTP in front of it.
- Service tokens: `identity` issues a JWT with `aud: internal` and a service subject.
  Services validate against the same JWKS from Day 12 — no new mechanism.
- `/internal/**` is rejected at the gateway, so it is never reachable from outside.
- OpenAPI documented, same as the public API.

## Out of scope
- Callers using these endpoints — Day 19.
- Caching. Measure first.

## Tracks

| Track | Owner | Work |
|---|---|---|
| A | | Batch endpoint |
| B | | Shortlist endpoint, SQL moved intact |
| C | | Service tokens + gateway blocks `/internal/**` |

## Acceptance criteria
- [ ] `/internal/**` returns 404 or 403 through the gateway, from any client.
- [ ] A user token is rejected on `/internal/**`; only `aud: internal` is accepted.
- [ ] The shortlist endpoint returns the same ordering as the in-process query today
      (assert against a fixed fixture).
- [ ] A batch request above the id cap returns 400, not a slow query.
- [ ] Both endpoints appear in the OpenAPI document.

## Verify
```bash
curl -s -o /dev/null -w '%{http_code}' localhost:8080/internal/postings/batch   # 403/404
cd services/job-service && ./mvnw verify
```

## Notes
- Reusing the Day 12 JWKS for service tokens avoids a second auth system. The `aud`
  claim is what separates user traffic from service traffic.
- **Superseded in part by Day 39.** Service tokens are not issued by identity on Day 12's key set:
  each service signs its own (`aud=jobmatch-internal`, its own `iss`) and publishes a key set,
  and the monolith's `/internal/**` chain already exists. Track C and its two criteria come out
  when this day is rewritten; the gateway's refusal of `/internal/**` is Day 39's Track 0.
