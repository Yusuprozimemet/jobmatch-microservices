# Day 22 — NoSQL score store, dual write

**Phase:** 4 · **Depends on:** Day 21 · **Expected PRs:** 3
**Status:** provisional — re-read and revise before starting.

## Goal
Scores are written to both Postgres and the NoSQL store. Reads still come from Postgres,
so this day cannot break anything.

## In scope
- Provision the NoSQL store (Cosmos DB / DynamoDB) plus a local emulator for compose.
- Data model, straight from the existing primary key in `V10__job_match_scores.sql`:

  | Field | Role |
  |---|---|
  | `skills_hash` | partition key |
  | `postingId#scorerVersion` | sort key |
  | `score`, `reason`, `scored_at` | attributes |
  | `ttl` | expiry — the store purges, not us |

- `JobMatchScoreRepository` gains a NoSQL implementation behind the same interface.
- Dual write: Postgres first, then NoSQL. A NoSQL failure is logged, never thrown —
  scores are a cache.
- Metric: dual-write success rate, so Day 23 has evidence it is safe to switch.

## Out of scope
- Reading from NoSQL — Day 23.
- Deleting anything — Day 23.

## Tracks

| Track | Owner | Work |
|---|---|---|
| A | | Provisioning + local emulator in compose |
| B | | Data model + NoSQL repository implementation |
| C | | Dual write, failure handling, metrics |

## Acceptance criteria
- [ ] A fresh top-matches request writes the same score to both stores.
- [ ] A NoSQL outage does not fail the request (test with the emulator stopped).
- [ ] `ttl` is set so a row expires on the same schedule Postgres purging uses today.
- [ ] The dual-write success metric is visible on a dashboard.
- [ ] Day 04's tests pass **unedited**.

## Verify
```bash
docker compose up -d --build
# request top-matches, then compare both stores for the same skills_hash
docker compose stop nosql && curl -s localhost:8080/api/jobs/top-matches   # still 200
```

## Notes
- Dual write is deliberately boring and reversible. Run it for a few days and read the
  metric before Day 23.
