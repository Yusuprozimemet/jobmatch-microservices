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
  and fills `savedCount` from the interface after the rows are fetched.
- It appears **twice**: once in `searchJobs`, once in `getJobById`. Both go.
- A posting with no saved rows returns 0, not null.

## Out of scope
- Making the call over HTTP — that is the extraction in Phase 3. Today it is a Java call.
- Caching the counts. Measure first.

## Tracks

| Track | Owner | Work |
|---|---|---|
| A | | Interface + batched implementation in `applications` |
| B | | Rewrite `JobRepository`, all call sites |

## Acceptance criteria
- [ ] `saved_jobs` appears nowhere under the `jobs` module.
- [ ] Day 03's `JobSavedCountIT` passes **unedited** — all six tests.
- [ ] One extra query per page, regardless of page size (assert the query count).
- [ ] A posting nobody saved reports `savedCount: 0`.
- [ ] `getJobById` fetches its one count without a second round trip per posting.

## Verify
```bash
cd backend && ./mvnw verify -Dtest='Job*IT'
# Adjust the path to wherever Day 06 put the jobs module.
grep -rn "saved_jobs" backend/src/main/java/nl/hackyourfuture/project/backend/jobs/ || echo "clean"
```

## Notes
- This is the first real proof the boundary works. If `savedCount` needs the test edited,
  the interface is wrong, not the test.
- **Spec corrected on Day 03, before the work.** This spec asked for "the three other places"
  the subquery appears in `JobRepository` — there is one other place, not three — and to remove
  a `// TODO day-08` marker that does not exist anywhere in the repository. That marker had an
  acceptance criterion of its own, `grep -rn "TODO day-08"` returning nothing, which was already
  true and would have ticked itself. The same failure mode as Day 02's surefire gate: a check
  that passes because it is checking nothing. Found by reading the spec against the code while
  writing Day 03's `savedCount` tests.
- The `grep` path was `jobs/`, which is not where the code lives. Day 06 creates the Maven
  modules, so whoever works this day should point the check at whatever path Day 06 produced
  rather than trusting the one written here.
