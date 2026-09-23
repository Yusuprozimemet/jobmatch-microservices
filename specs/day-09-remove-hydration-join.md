# Day 09 — Remove the saved-jobs hydration join

**Phase:** 1 · **Depends on:** Day 07 · **Expected PRs:** 4

## Goal
`applications` and `matching` stop reading `analytics`. They ask `jobs` — for posting details,
and for the match shortlist.

## In scope
- `PostingLookup` interface in `shared.jobs`, beside `shared.applications`' `SavedJobCounts`:
  `Map<String, PostingSummary> byIds(Collection<String> postingIds)`. Ids missing from the mart
  are **absent** from the map; the caller decides what that means.
- `PostingSummary` (also `shared.jobs`): the fields `SavedJobResponse` actually uses — no more.
  That is the twelve besides `postingId` and `jobState`.
- Implement in `jobs` with one batched query, in a package-private `@Component`, the way
  `ApplicationsDirectory` implements `SavedJobCounts`. An empty id list returns an empty map
  without querying — `IN ()` is a syntax error.
- **It reads the columns the join reads, not the ones job search reads.** `location` is
  `fct_postings.location`, the free-text column, not the normalised city bridge; `skills` is the
  `fct_postings.skills` column in its own order, not `fct_postings_skills` sorted. Day 04 pinned
  both divergences (`showsTheFreeTextLocationWhichCanNameAPlaceSearchWouldNot`, and the
  `containsExactly` on skill order), so reusing `JobRepository`'s search SQL fails the gate.
- `SavedJobRepository.getSavedJobsWithDetails` drops its `LEFT JOIN analytics.fct_postings`.
  It reads **every** saved row for the user (id and state, one query), hydrates them through
  `PostingLookup` (one call), sorts by `postedDate` descending with nulls last and `postingId`
  as the tie-break — the order the SQL has today — and pages in memory. `totalElements` is the
  number of saved rows, so the separate `COUNT(*)` goes.
- **Preserve the `LEFT JOIN` semantics.** A saved posting missing from the mart must still
  be returned, with null detail fields and an empty `skills` list. Day 04 pins this.
- **The match shortlist moves to `jobs`.** `PostingShortlist` in `shared.jobs`:
  `List<ShortlistedPosting> shortlist(String city, List<String> skills, int limit)`.
  `ShortlistedPosting` is today's `JobMatchRepository.JobMatchRow`, moved and renamed; its
  nine fields do not change. The SQL moves from `matching`'s `JobMatchRepository` into `jobs`
  **unchanged** — candidate filter, skill scoring, repost dedup, ordering. Implemented by the
  same package-private component as `PostingLookup`. `JobMatchRepository` is deleted.

## Out of scope
- Making the call over HTTP — Phase 3/5.
- Changing the `SavedJobResponse` shape. The frontend must not need a change.
- Changing the order. Newest posting first is what users see today. Day 04 pins only that a
  vanished posting sorts last, not the direction; see Notes.
- Changing the shortlist's SQL or ranking in any way. It moves; Day 18 exposes the same
  interface as `POST /internal/postings/shortlist`.
- Reconciling the two locations and the two skill orders. That is a behaviour change with a
  frontend-visible result and gets its own spec if anyone wants it.
- Moving `MartSkills` out of `shared`. After today only `jobs` uses it; that is its own change.

## Tracks

| Track | Owner | Work |
|---|---|---|
| 0 | | Query-count test, landed first |
| A | | `PostingLookup`, `PostingSummary` + batched implementation in `jobs` |
| B | | Rewrite `SavedJobRepository` and its call site |
| C | | `PostingShortlist` + `ShortlistedPosting`; move the shortlist SQL from `matching` to `jobs` |

Track 0 is Day 08's pattern again: `StatementCounter` counts the statements that mention
`fct_postings` during one `GET /api/saved-jobs`. It is **1 today** — the join — and 1 after,
now the batched lookup, at every page size and list length. It lands first and is broken on
purpose (a lookup per posting) before it is trusted. `SavedJobHydrationQueriesIT`, in
`app/src/test/.../queries/`.

## Acceptance criteria
- [x] **new** — `grep -rn "analytics\.\|fct_postings" applications/src/ matching/src/` returns
      nothing. Red before the work: it found the `LEFT JOIN` in `SavedJobRepository` and the
      shortlist in `JobMatchRepository`. Clean since #54.
- [x] **hold** — Day 04's `Match*IT` — 22 tests — pass **unedited**. 22 green on `main`, zero lines
      changed under `app/src/test` in #51–#54 but the new query test. Broken on purpose at close:
      the shortlist ranked by fewest matched skills failed 1 of the 22,
      `MatchTopMatchesIT.ranksTheShortlistByTheModelsScoreRatherThanBySkillOverlap`.
- [x] **hold** — Day 04's `SavedJob*IT` — 31 tests — pass **unedited**. In particular the vanished-posting
      tests, the order test, and both pinned divergences. 31 green, unedited. Broken on purpose
      at close: vanished postings sorted first failed `sortsAVanishedPostingAfterTheOnesStillInTheMart`.
      **Oldest posting first, with vanished postings still last, passed all 31**. See Notes.
- [x] **hold** — `totalElements` is the number of saved rows, whatever the mart holds.
      `SavedJobHydrationIT.keepsListingASavedJobWhosePostingHasLeftTheMart` (1 row, posting gone)
      and `SavedJobHydrationQueriesIT` (6 rows, one gone). Broken on purpose at close: a total
      counted from the lookup's map failed both, 4 tests.
- [x] **hold** — Exactly one statement touches `fct_postings` per `GET /api/saved-jobs`, at page sizes 1, 2
      and 20, with more saved jobs than the page holds. `SavedJobHydrationQueriesIT` (#51), green
      before and after. Broken on purpose in #51: a lookup per posting gave 2, 3 and 7 statements.
- [x] **new** — A user with no saved jobs gets an empty page and no statement touches `fct_postings`.
      `doesNotAskTheMartAboutAnEmptyList` (#53). Red on the old code: expected 0 statements, was 1.

## Verify
```bash
cd backend
./mvnw clean verify -pl app -am -Dtest='Saved*IT,Match*IT' -Dsurefire.failIfNoSpecifiedTests=false
grep -rn "analytics\.\|fct_postings" applications/src/ matching/src/ || echo "clean"
./mvnw -B checkstyle:check
```
Check the surefire reports, not only the exit code.

## Notes
- **Spec corrected on Day 09, before the work.** Read against the code and against Day 04's
  tests, which are this day's gate:
  - **The central instruction could not be followed.** "Order and page inside `saved_jobs`, then
    hydrate the page — never hydrate first and page after." The list is ordered by
    `p.posted_date DESC NULLS LAST, sj.posting_id`: a mart column. `saved_jobs` has three
    columns — `user_id`, `posting_id`, `job_state` — and nothing to order by but the id. Paging
    inside it changes the order users see and fails
    `sortsAVanishedPostingAfterTheOnesStillInTheMart`. The note's real concern, that totals
    and vanished rows survive mart gaps, is kept: the rows come from `saved_jobs`, all of them,
    and the mart only supplies sort keys and detail.
  - The cost is that each request hydrates the user's whole saved list, not one page. It is a
    personal tracker, so tens of rows, not thousands. **Two alternatives, not chosen:** a
    second lookup method returning only posting dates, then hydrating the page — three
    statements and a wider interface for no gain at this size; or a `saved_at` column to order
    by — a migration with no value for existing rows, a changed order, and an edited Day 04
    test. Revisit when a user's list is long enough to measure, not before.
  - **The `// TODO day-09` marker does not exist,** so its criterion would have ticked itself —
    the same defect Day 08's spec had.
  - **The verify command had Day 08's defect:** `-Dtest` with no `-pl` fails on `shared` before
    reaching `app`. The corrected command was run before being written here: 31 tests,
    all green.
  - **The `grep` searched `applications/`, which includes `target/`:** the compiled
    `SavedJobRepository.class` matches, so a stale build fails the check after the source is
    clean. It is `applications/src/` now, and also looks for `fct_postings` without the schema.
  - **"Two queries per page"** counted the `COUNT(*)` and the joined page. The rewrite runs two
    statements as well, but a different two, and one of them is the whole list. The criterion
    now counts what the day is about: statements that reach the mart.
  - The fields `jobs` must read — the free-text location, the raw skill order — were written
    down by Day 04 for this day and were not in this spec.
- **Track C added on Day 09, before the work, from a read of every remaining spec.** `plan.md`
  counted two cross-schema joins; there are three cross-module reads. `matching`'s
  `JobMatchRepository` builds the shortlist from `analytics.fct_postings` and
  `fct_postings_cities` directly, and no Phase 1 day moved it. Days 18–19 would have fixed it
  over HTTP, but Phase 1's own "done when no SQL crosses a schema" could not have been true at
  Day 11, and Day 11's per-schema roles would have had to grant `matching` a read on the mart
  just to keep it working. It belongs here, beside `PostingLookup`: same front door, same
  pattern, no network. It is a move, not a rewrite, so the gate is the 22 `Match*IT` tests
  and no query-count test is added — one statement goes in, the same statement comes out.
- **Done on Day 09.** Spec changes #46 and #47, then #51 (Track 0, the query-count test), #52
  (Track A, `PostingLookup` and `JobsDirectory`), #53 (Track B, `applications` moved over) and
  #54 (Track C, the shortlist moved into `jobs`). 210 tests green, checkstyle clean.
  *Estimated 2 pull requests, took 4.* Both extra PRs were added by spec changes before the work:
  Track 0 by #46, Track C by #47, when `plan.md`'s count of cross-module reads turned out to be
  one short.
- **Criteria tagged `new`/`hold` at close,** the first day under #55's rule. Three of the four
  `hold` criteria had only ever been seen green on this day, so each was broken on purpose before
  it was ticked, and reverted. That is where the next note comes from.
- **The gate does not pin the order this spec said it pins.** Out of scope said "newest posting
  first ... Day 04 pins it". It pins less. Sorting oldest first, with vanished postings still
  last, passed all 31 `SavedJob*IT` tests. The only order test, `sortsAVanishedPostingAfterTheOnesStillInTheMart`,
  checks where a missing posting goes, not which way the rest run. Track B kept newest first
  (`NEWEST_POSTING_FIRST` in `SavedJobService`, by reading and by the SQL it replaced), so no
  behaviour changed. But after Track B moved the sort from SQL into Java, no test would notice it
  being reversed. Reading did not find this in two spec changes and four track PRs; breaking the
  code did. A contract test pinning newest first belongs in its own PR, proven by the same
  reversal.
- **The shortlist's order is pinned by one test out of 22.** Ranking by fewest matched skills
  changes which postings survive the `LIMIT`, and only
  `ranksTheShortlistByTheModelsScoreRatherThanBySkillOverlap` saw it. Enough for a move checked
  by diff, as #54 was. Thin for Day 18, which puts the same query behind HTTP.
- Phase 1's three cross-module reads are down to none in SQL: `jobs` no longer reads
  `saved_jobs` (Day 08), and `applications` and `matching` no longer read the mart (today).
  What is left crosses through Java calls on `shared` interfaces, and Days 10–11 finish the phase.
