# Day 19 — In-process interfaces become HTTP clients

**Phase:** 3 · **Depends on:** Day 18 · **Expected PRs:** 3
**Status:** provisional — re-read and revise before starting.

## Goal
`PostingLookup` and the shortlist call go over the network, with timeouts and a
circuit breaker, and the system still behaves the same.

## In scope
- HTTP implementations of `PostingLookup` and `PostingShortlist` (Day 09), used by the
  monolith to call `job-service`, using `RestClient`. `SavedJobCounts` goes the other way —
  `job-service` calling the monolith — and has been HTTP since Day 17; it gets the same
  resilience treatment here.
- Resilience4j on every internal call: connect and read timeouts, bounded retry on
  idempotent GETs only, circuit breaker, and a **declared fallback**:

  | Call | Depends on | Fallback when that is down |
  |---|---|---|
  | `PostingLookup` (saved jobs) | `job-service` | Return saved jobs with empty detail fields — the Day 04 missing-posting case already covers this shape |
  | `PostingShortlist` (matching) | `job-service` | 503 with a clear message. Matching without postings is meaningless |
  | `SavedJobCounts` (job search) | the monolith | `savedCount: 0`, search still returns results |

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
- [ ] With the **monolith** stopped, job search — which is `job-service` — still returns
      results with `savedCount: 0`.
- [ ] With `job-service` stopped, saved jobs still list, with empty details.
- [ ] With `job-service` stopped, top-matches returns 503, not a 30-second hang.
- [ ] The circuit breaker opens after a configured failure count and recovers by itself.
- [ ] Every internal call shows as a child span with a latency metric.

## Verify
```bash
docker compose stop backend
curl -s localhost:8080/api/jobs | head -c 200            # still 200, savedCount 0
docker compose start backend
docker compose stop job-service
curl -s -o /dev/null -w '%{http_code}' localhost:8080/api/jobs/top-matches   # 503, fast
docker compose start job-service
```

## Notes
- This is where a monolith becomes a distributed system, and where partial failure
  becomes real. The fallback table is the most important part of this spec —
  agree on it before writing code.
- **Corrected on Day 09 from a read of every remaining spec** — still provisional; this fixes
  what is already known to be wrong, not the phase review.
  **The table had a dependency backwards.** It listed `SavedJobCounts` under "when
  `job-service` is down", and a criterion stopped `job-service` and expected job search to
  keep answering. From Day 17, job search *is* `job-service`; the counts come from
  `applications`, in the monolith. Stopping `job-service` takes search down with it. The table
  now names what each call depends on; Day 25 already had this right.
