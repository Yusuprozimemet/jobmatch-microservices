# Day 09 — Remove the saved-jobs hydration join

**Phase:** 1 · **Depends on:** Day 07 · **Expected PRs:** 2

## Goal
`applications` stops reading `analytics.fct_postings`. It asks `jobs` for posting details.

## In scope
- `PostingLookup` interface in `shared`:
  `Map<String, PostingSummary> byIds(Collection<String> postingIds)`.
- `PostingSummary`: the fields `SavedJobResponse` actually uses — no more.
- Implement in `jobs` with one batched query.
- `SavedJobRepository.getSavedJobsWithDetails` drops its
  `LEFT JOIN analytics.fct_postings`: page over `saved_jobs` first, then hydrate.
- **Preserve the `LEFT JOIN` semantics.** A saved posting missing from the mart must still
  be returned, with empty detail fields. Day 04 pins this.
- Remove the `// TODO day-09` marker.

## Out of scope
- Making the call over HTTP — Phase 3/5.
- Changing the `SavedJobResponse` shape. The frontend must not need a change.

## Tracks

| Track | Owner | Work |
|---|---|---|
| A | | Interface + batched implementation in `jobs` |
| B | | Rewrite `SavedJobRepository`, keep paging correct |

## Acceptance criteria
- [ ] `grep -rn "analytics\." applications/` returns nothing.
- [ ] Day 04's hydration and missing-posting tests pass **unedited**.
- [ ] Paging is still computed from `saved_jobs`, so totals are unaffected by mart gaps.
- [ ] Two queries per page, regardless of page size.
- [ ] `grep -rn "TODO day-09"` returns nothing.

## Verify
```bash
cd backend && ./mvnw verify -Dtest='Saved*IT'
grep -rn "analytics\." applications/ || echo "clean"
```

## Notes
- Paging order is the trap. Order and page inside `saved_jobs`, then hydrate the page —
  never hydrate first and page after.
