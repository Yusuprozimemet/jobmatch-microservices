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
- [ ] A posting saved by 3 users reports `savedCount: 3` in search results.
- [ ] `category`, `workMode`, `location` and `q` are each tested alone, and one of them in
  combination with another.
- [ ] `location` matches a whole city case-insensitively rather than a substring: searching
  `Ede` does not return a posting in Enschede.
- [ ] `q` matches a substring of title, company, city or skill.
- [ ] Paging past the last page returns an empty list, not an error.
- [ ] `GET /api/jobs?employmentType=<a value the filters endpoint offers>` returns the same
  results as the unfiltered search — an option offered but not implemented.
- [ ] `GET /api/jobs/filters` returns all five lists, with the city list excluding the
  countries and provinces in `JobRepository.NON_CITY_LOCATIONS`.
- [ ] `GET /api/jobs` succeeds with no credentials; `/api/jobs/top-matches` returns 401.
- [ ] Profile validation rejects fewer than `UpdateProfileRequest.MIN_SKILLS` skills.

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
