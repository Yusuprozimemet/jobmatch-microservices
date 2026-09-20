# Day 08 — Remove the `saved_count` join

**Phase:** 1 · **Depends on:** Day 07 · **Expected PRs:** 2

## Goal
`jobs` stops reading the `saved_jobs` table. It asks `applications` for the counts instead.

## In scope
- `SavedJobCounts` interface in `shared`:
  `Map<String, Integer> countsFor(Collection<String> postingIds)`.
- Implement it in `applications` with one batched query — **not** one query per posting.
- `JobRepository` drops the correlated subquery
  (`SELECT COUNT(DISTINCT user_id) FROM saved_jobs WHERE posting_id = f.posting_id`)
  and fills `savedCount` from the interface after the page is fetched.
- Same for the three other places that subquery appears in `JobRepository`.
- A posting with no saved rows returns 0, not null.
- Remove the `// TODO day-08` marker.

## Out of scope
- Making the call over HTTP — that is the extraction in Phase 3. Today it is a Java call.
- Caching the counts. Measure first.

## Tracks

| Track | Owner | Work |
|---|---|---|
| A | | Interface + batched implementation in `applications` |
| B | | Rewrite `JobRepository`, all call sites |

## Acceptance criteria
- [ ] `grep -rn "saved_jobs" jobs/` returns nothing.
- [ ] Day 03's `savedCount` test passes **unedited**.
- [ ] One extra query per page, regardless of page size (assert the query count).
- [ ] A posting nobody saved reports `savedCount: 0`.
- [ ] `grep -rn "TODO day-08"` returns nothing.

## Verify
```bash
cd backend && ./mvnw verify -Dtest='Job*IT'
grep -rn "saved_jobs" jobs/ || echo "clean"
```

## Notes
- This is the first real proof the boundary works. If `savedCount` needs the test edited,
  the interface is wrong, not the test.
