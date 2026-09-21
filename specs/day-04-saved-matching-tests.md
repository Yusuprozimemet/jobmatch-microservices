# Day 04 — Saved jobs and matching contract tests

**Phase:** 0 · **Depends on:** Day 01 · **Expected PRs:** 3

## Goal
Saved jobs and top-matches are pinned, with the LLM stubbed, so Days 09 and 14+ can
change the plumbing without guessing at behaviour.

## In scope
- Saved jobs: save, duplicate save returns 409, list with posting details, update state,
  remove, stats grouped by `JobState`.
- **Posting detail hydration** — a saved job returns title, company, location, work mode,
  skills, employment type, posted date, category and freshness from the mart. Pin it; Day 09
  re-implements it without a join.
- Hydration reads `fct_postings.location`, the free-text column, **not** the normalised city
  bridge that job search and job detail use. The same posting can therefore report a different
  place in saved jobs than in search. Pin what it does today.
- A saved posting that has vanished from the mart still lists (the current `LEFT JOIN`
  behaviour). This is a real case after a mart republish.
- `GET /api/jobs/top-matches`: both 422 branches — no profile at all, and a profile below
  `JobMatchService.MINIMUM_PROFILE_SKILLS`; ordering by score; `RESULT_LIMIT` respected;
  `matchPercent` floor from `MIN_PERCENT_DENOMINATOR`.
- **Stub `MatchScorer` at the HTTP boundary**, not with a mock bean — return canned
  chat-completion JSON so the real parsing runs.
- **Turning the scorer on is part of this day's work.** `application-test.yaml` sets
  `app.llm.api-key: ""`, which makes `MatchScorer.isEnabled()` false and means no HTTP call is
  ever attempted. The matching tests must override **both** `app.llm.api-key` (any non-blank
  value) and `app.llm.base-url` (the stub's address). See the Notes: overriding only the first
  sends the suite at a real provider.
- LLM failure falls back to SQL ordering and does not error (current documented behaviour).
- Score cache: a second identical request makes no further LLM call.

## Out of scope
- Any behaviour change. Tests only.

## Tracks

| Track | Owner | Work |
|---|---|---|
| A | | Saved jobs CRUD, states, stats |
| B | | Hydration + missing-posting case |
| C | | Top-matches, LLM stub, fallback, cache-hit |

## Acceptance criteria
- [x] Saving the same posting twice returns 409.
- [x] A saved job whose posting is absent from the mart is still returned.
- [x] Stats sum equals the number of saved jobs.
- [x] Top-matches returns 422 for a user with no profile, and 422 for a profile below
  `JobMatchService.MINIMUM_PROFILE_SKILLS`, which is 5.
- [x] With the LLM stub returning an error, top-matches still returns 200.
- [x] Two identical top-matches calls hit the LLM stub exactly once.
- [x] A test asserts the stub was called at least once, so the suite cannot pass with the
  scorer switched off.
- [x] A saved job and the same posting in job search agree on title and company. Where they
  disagree on location, that difference is asserted rather than left implicit.

## Verify
```bash
cd backend && ./mvnw verify -Dtest='Saved*IT,Match*IT'
```

## Notes
- Stub at the HTTP layer (WireMock or a `RestClient` test server). Mocking the bean
  hides the JSON parsing, which is where `MatchScorer` actually breaks.
- **Overriding `app.llm.api-key` without `app.llm.base-url` points the test suite at a real
  provider.** The test profile does not set a base URL, so it falls through to
  `application.yaml`'s default, which is Google's live endpoint. Today the suite is safe only
  because the key is blank. Whoever writes these tests must set both, and `StubOidcProvider` is
  the pattern to copy: a real server on a local port, registered through
  `@DynamicPropertySource`.
- **Three of the six original criteria were unreachable as written**, because a scorer that is
  switched off makes no call to stub, fail or count. They would have gone green while testing
  nothing — the same failure mode as Day 02's surefire gate. Hence the extra criterion that the
  stub was actually called.
- `MIN_PROFILE_SKILLS` did not exist. The constant is `JobMatchService.MINIMUM_PROFILE_SKILLS`,
  defined as `UpdateProfileRequest.MIN_SKILLS`, so the floor is the same 5 that Day 03 pinned on
  the profile form. Writing a profile below it needs `aProfile()`, since the API will not save
  one — which is exactly the case the production comment says it guards against.
- **`RESULT_LIMIT` is 25 and the seeded mart holds 24 postings**, of which the shortlist keeps
  only open ones in the profile's city with at least one matching skill, deduplicated by title
  and company. Testing the limit means creating 26+ postings with distinct title/company pairs
  in one city; the seed alone cannot reach it.
- Enabling the scorer changes `app.llm.*`, which Spring caches per property set: the matching
  tests will start a second application context. Expect the suite to get slower, and keep the
  overrides on as few classes as possible.
- **53 tests over six classes**, in `backend/src/test/java/.../contract/`. Full suite (Days 01
  to 04 together) is 184 tests in ~90s. Five pull requests against the three this spec
  estimated, plus the spec change: track A came to 340 lines and fit, track C came to 446 and
  was split on the 400-line gate.
- **The three unreachable criteria were real.** With `app.llm.api-key` blank, the first version
  of the top-matches tests would have asserted stubbing, failure and call counts against a
  scorer that never made a call. `MatchingTest` now overrides the key and the base URL together,
  with the reasoning written next to them, and `returnsAnEmptyListWhenNothingIsShortlisted`
  pins the one case where zero calls is the right answer.
- **`MatchScorer` maps the model's reply back by the first eight characters of a posting id.**
  Two postings whose ids agree that far are one id as far as the model is concerned, and only
  one of them can carry a score. Production ids come from `md5(...)` in `int_postings.sql` and
  do not collide, so this is pinned rather than filed — but **every seeded fixture id is
  `seed-00NN`, which collides by construction**. A test written against seed postings would have
  scored one and silently dropped the other. The postings in the matching tests therefore use
  ids that differ inside eight characters. This is a fixture that is *worse* than production in
  the one code path this day is about, which is the inverse of the rule Day 01 set for the mart
  schema.
- **Saved jobs and job search disagree about the same posting, twice.** Location: saved jobs read
  `fct_postings.location`, the free-text column, while search and detail read the normalised
  city bridge, so `seed-0021` is "Netherlands, Utrecht" in the tracker and "Utrecht" on the job
  page. Skill order: saved jobs return the mart's raw array order, job detail sorts them. Both
  orders are now pinned in their own files, so Day 09's single `PostingLookup` cannot quietly
  satisfy one and break the other.
- **Nothing checks a posting id against the mart on save.** `POST /api/saved-jobs` accepts an id
  that is not there and answers 201. That is what makes the vanished-posting case reachable,
  and it is pinned in `SavedJobsIT`.
- The stats response omits a state nobody is in rather than reporting zero. Pinned from both
  directions: a reimplementation that helpfully filled in zeroes would change what the
  dashboard renders.
- A failed scoring call stores nothing, so the next request retries rather than caching the
  outage. Worth keeping in mind at Day 22, when these rows move to NoSQL with a TTL.
