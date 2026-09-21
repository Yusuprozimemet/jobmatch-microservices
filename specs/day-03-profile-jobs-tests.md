# Day 03 — Profile and job search contract tests

**Phase:** 0 · **Depends on:** Day 01 · **Expected PRs:** 3

## Goal
Profile and job search are pinned, including the `saved_count` field that Day 08 has to
re-implement without a SQL join.

## In scope
- `GET/PUT /api/profile`: round-trip, minimum-skills validation, normalisation,
  unauthenticated returns 401.
- `GET /api/jobs`: pagination, and the four filters the endpoint accepts — `category`,
  `workMode`, `location`, and the free-text `q`; combined filters; empty result.
- `GET /api/jobs/filters`: returns the five option lists drawn from the mart — `locations`,
  `categories`, `workModes`, `experienceLevels`, `employmentTypes`.
- `GET /api/jobs/{id}`: found and not-found.
- **`saved_count` in search results** — pin the current number precisely. Day 08 changes
  how it is produced and this test must not need editing.
- Public vs authenticated routes per `SecurityConfig`: `/api/jobs` public,
  `/api/jobs/top-matches` authenticated.

## Out of scope
- Saved jobs and matching — Day 04.
- Making `experienceLevel` or `employmentType` filter anything. The filters endpoint offers
  both, the search endpoint accepts neither. Pin today's behaviour; do not fix it. See Notes.

## Tracks

| Track | Owner | Work |
|---|---|---|
| A | | Profile read/write/validation |
| B | | Job search, pagination, the four accepted filters |
| C | | Filters endpoint, job detail, `saved_count`, public/private route matrix |

## Acceptance criteria
- [x] A posting saved by 3 users reports `savedCount: 3` in search results.
- [x] `category`, `workMode`, `location` and `q` are each tested alone, and one of them in
  combination with another.
- [x] `location` matches a whole city case-insensitively rather than a substring: searching
  `Ede` does not return a posting in Enschede.
- [x] `q` matches a substring of title, company, city or skill.
- [x] Paging past the last page returns an empty list, not an error.
- [x] `GET /api/jobs?employmentType=<a value the filters endpoint offers>` returns the same
  results as the unfiltered search — an option offered but not implemented.
- [x] `GET /api/jobs/filters` returns all five lists, with the city list excluding the
  countries and provinces in `JobRepository.NON_CITY_LOCATIONS`.
- [x] `GET /api/jobs` succeeds with no credentials; `/api/jobs/top-matches` returns 401.
- [x] Profile validation rejects fewer than `UpdateProfileRequest.MIN_SKILLS` skills.

## Verify
```bash
cd backend && ./mvnw verify -Dtest='Profile*IT,Job*IT'
```

## Notes
- `saved_count` is the single most important assertion this day. It is the join that
  Day 08 deletes.
- **Spec change made before the work.** This day asked for a filter on employment type and
  called the city filter `city`. `JobController.searchJobs` takes `category`, `workMode`,
  `location` and `q`, and nothing else: the employment-type criterion was untickable and the
  city one named a parameter that does not exist. Both are corrected above, and `q` — which
  the spec left out entirely — is now in scope.
- **The filters endpoint advertises two filters the search does not implement.**
  `JobFiltersResponse` carries `experienceLevels` and `employmentTypes`, and passing either to
  `GET /api/jobs` is silently ignored, because Spring drops query parameters no `@RequestParam`
  declares. Pinned as today's behaviour rather than filed as a bug: Phase 3 moves this endpoint
  into job-service, and the choice between implementing the filters and dropping the options
  belongs to whoever owns that service.
- `location` is matched against the normalised `analytics.fct_postings_cities` table by
  case-insensitive equality, not against the free-text location column, and excludes
  `NON_CITY_LOCATIONS`. A fixture that sets a city on the posting row alone will match nothing.
- `JobController` caps `size` at 100 and `JobRepository.MAX_SEARCH_RESULTS` caps it again at
  200. The second cap is unreachable over HTTP; no test should depend on it.
- **68 tests over seven classes**, in `backend/src/test/java/.../contract/`. Full suite (Days 01
  to 03 together) is 131 tests in ~60s. Four pull requests against the three this spec
  estimated: Track A came to 406 lines and was split on the 400-line gate.
- **Harness bug, fixed here:** `ApiResponse` parsed JSON floats as doubles, so a response
  carrying `45000.00` came back through `json()` as `45000.0`. The API was right the whole time
  and only the test client's view of it was lossy — meaning no assertion on a number could have
  seen a currency scale change. It now reads floats as `BigDecimal`. Found by writing the
  assertion, not by review: `asString()` on the node said `"45000.0"` while the raw body said
  `45000.00`.
- **Search and matching disagree about closed postings.** `GET /api/jobs` has no status filter
  at all, so the closed `seed-0024` is returned by a search, while matching excludes closed
  postings. Pinned as it stands rather than filed: which of the two is right is a product
  question, and pinning it makes the answer a visible decision rather than a silent drift.
- `savedCount` is asserted from three different callers — logged out, the saver, a bystander —
  because it is a property of the posting rather than an answer to "did I save this". That is
  the part a per-user reimplementation would get wrong, and Day 08 is where it would happen.
- The saved-jobs rows for those tests are written straight to the table rather than through
  `POST /api/saved-jobs`, so Day 04 changing that endpoint cannot break Day 03's file.
- **`/api/jobs/top-matches` is private only because of rule order.** It also matches the
  `/api/jobs/*` rule that makes job detail public; the authenticated rule above it is the only
  thing keeping it private. `JobRoutesIT` pins that, because reordering the two looks harmless.
- **Day 08's spec does not match the code it describes**, found while checking what `savedCount`
  has to survive. It asks for "the three other places" the `saved_jobs` subquery appears in
  `JobRepository` — there is one other place — and to remove a `// TODO day-08` marker that does
  not exist anywhere in the repository, with an acceptance criterion that therefore ticks itself.
  Raised against Day 08 rather than fixed here.
