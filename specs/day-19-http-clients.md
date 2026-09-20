# Day 19 — In-process interfaces become HTTP clients

**Phase:** 3 · **Depends on:** Day 18 · **Expected PRs:** 3
**Status:** provisional — re-read and revise before starting.

## Goal
`PostingLookup` and the shortlist call go over the network, with timeouts and a
circuit breaker, and the system still behaves the same.

## In scope
- HTTP implementations of the Day 08–10 interfaces, using `RestClient`.
- Resilience4j on every internal call: connect and read timeouts, bounded retry on
  idempotent GETs only, circuit breaker, and a **declared fallback**:

  | Call | Fallback when job-service is down |
  |---|---|
  | `PostingLookup` (saved jobs) | Return saved jobs with empty detail fields — the Day 04 missing-posting case already covers this shape |
  | Shortlist (matching) | 503 with a clear message. Matching without postings is meaningless |
  | `SavedJobCounts` (job search) | `savedCount: 0`, search still returns results |

- Timeouts shorter than the gateway's, so a stuck dependency surfaces as a fast error.
- Every internal call is a span and a metric.

## Out of scope
- Separate databases — Day 20.

## Tracks

| Track | Owner | Work |
|---|---|---|
| A | | HTTP clients + serialisation |
| B | | Resilience4j config + the three fallbacks |
| C | | Failure tests: kill `job-service`, assert each fallback |

## Acceptance criteria
- [ ] Day 03 and Day 04 tests pass **unedited**.
- [ ] With `job-service` stopped, job search still returns results with `savedCount: 0`.
- [ ] With `job-service` stopped, saved jobs still list, with empty details.
- [ ] With `job-service` stopped, top-matches returns 503, not a 30-second hang.
- [ ] The circuit breaker opens after a configured failure count and recovers by itself.
- [ ] Every internal call shows as a child span with a latency metric.

## Verify
```bash
docker compose stop job-service
curl -s localhost:8080/api/jobs | head -c 200            # still 200
curl -s -o /dev/null -w '%{http_code}' localhost:8080/api/jobs/top-matches   # 503
docker compose start job-service
```

## Notes
- This is where a monolith becomes a distributed system, and where partial failure
  becomes real. The fallback table is the most important part of this spec —
  agree on it before writing code.
