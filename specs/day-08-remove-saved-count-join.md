# Day 08 — Remove the `saved_count` join

**Phase:** 1 · **Depends on:** Day 07 · **Expected PRs:** 3

## Goal
`jobs` stops reading the `saved_jobs` table. It asks `applications` for the counts instead.

## In scope
- `SavedJobCounts` interface in `shared.applications`, beside `shared.identity`'s two:
  `Map<String, Integer> countsFor(Collection<String> postingIds)`. The map has an entry for
  every id asked for, zero-filled, so no caller can read a null out of it.
- Implement it in `applications` with one batched query — **not** one query per posting —
  in a package-private `@Component`, the way `IdentityDirectory` implements identity's.
  Keep `COUNT(DISTINCT user_id)`, so the number means what it meant before.
- An empty id list returns an empty map **without querying**. A named parameter bound to an
  empty collection expands to `IN ()`, which Postgres rejects, and an empty page is an
  ordinary result: `JobSearchIT` asks for one twice (paging past the end, and a search that
  matches nothing).
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
| 0 | | Query-count test, landed first — see below |
| A | | Interface + batched implementation in `applications` |
| B | | Rewrite `JobRepository`, both call sites |

Track 0 exists because the query-count criterion had nothing to measure it with. The contract
tests talk to the application over HTTP and to the database over JDBC, and neither can see how
many statements a request ran. Turn on `pg_stat_statements` in the test container
(`shared_preload_libraries`, keeping Testcontainers' default `fsync=off`), reset it, make one
request, and count the statements whose text mentions `saved_jobs`.

That count is **1 today**: the search query itself carries the subquery. After Track B it is
1 again, now the batched count. It becomes N only if the batch is replaced by a loop, which is
the thing it guards against. So it lands first and passes before and after the change, the
same way `JobSavedCountIT` did on Day 03.

It is `JobSavedCountQueriesIT`, outside `contract/`, in `app/src/test/.../queries/`. It measures how the answer is
computed, not the answer, and it expires on Day 25, when `saved_jobs` moves to `apps_db` and
this container stops seeing that statement.

## Acceptance criteria
- [ ] `saved_jobs` appears nowhere under the `jobs` module.
- [ ] Day 03's `JobSavedCountIT` passes **unedited** — all six tests.
- [ ] Exactly one statement touches `saved_jobs` per search page, at page sizes 1, 20 and 100.
- [ ] Exactly one statement touches `saved_jobs` per `GET /api/jobs/{id}`.
- [ ] A posting nobody saved reports `savedCount: 0`.
- [ ] Paging past the end and a search matching nothing still return 200 with an empty page.

## Verify
```bash
cd backend
# -pl app -am: the Job*IT tests live in app. failIfNoSpecifiedTests=false: without it surefire
# stops on `shared`, which has no test matching the pattern, before app is ever reached.
./mvnw clean verify -pl app -am -Dtest='Job*IT' -Dsurefire.failIfNoSpecifiedTests=false
grep -rn "saved_jobs" jobs/src/ || echo "clean"
```
Check the surefire report, not only the exit code: `JobSavedCountIT` must show `tests="6"`.

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
- **Spec corrected again on Day 08, before the work.** Four things, found by reading it
  against the modules Days 06–07 produced:
  - The verify command did not run a single test. With `-Dtest` and no `-pl`, surefire applies
    the pattern to every module, fails on `shared` ("No tests matching pattern"), and the build
    stops before reaching `app`. It failed loudly, which is the better way to be wrong, but
    `app/target/surefire-reports` still held a green `JobSavedCountIT` from an earlier full
    run, so anyone checking the report instead of the exit code saw six passes that had not
    run. Hence `clean` in the command and the report check under it. The
    corrected command was run before being written here: 45 `Job*` tests, 6 of them
    `JobSavedCountIT`, all green.
  - The `grep` path was `backend/src/main/java/...` run from inside `backend/`, wrong twice.
    It is `jobs/src/` now, the path Day 06 produced.
  - "Assert the query count" named a measurement with no instrument. Track 0 adds one;
    `pg_stat_statements` was tried against `postgres:18.4-alpine` first and counts as expected.
  - `getJobById` "without a second round trip per posting" described a loop over one posting.
    It is now the same count as search: exactly one statement.

  Two things were left unsaid and are now in scope: an empty page must not reach the database
  as `IN ()`, and the map is zero-filled by the implementation rather than by every caller.
