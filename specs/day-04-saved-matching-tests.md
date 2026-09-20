# Day 04 — Saved jobs and matching contract tests

**Phase:** 0 · **Depends on:** Day 01 · **Expected PRs:** 3

## Goal
Saved jobs and top-matches are pinned, with the LLM stubbed, so Days 09 and 14+ can
change the plumbing without guessing at behaviour.

## In scope
- Saved jobs: save, duplicate save returns 409, list with posting details, update state,
  remove, stats grouped by `JobState`.
- **Posting detail hydration** — a saved job returns title, company and city from the mart.
  Pin it; Day 09 re-implements it without a join.
- A saved posting that has vanished from the mart still lists (the current `LEFT JOIN`
  behaviour). This is a real case after a mart republish.
- `GET /api/jobs/top-matches`: 422 when the profile has too few skills; ordering by score;
  `RESULT_LIMIT` respected; `matchPercent` floor from `MIN_PERCENT_DENOMINATOR`.
- **Stub `MatchScorer` at the HTTP boundary**, not with a mock bean — return canned
  chat-completion JSON so the real parsing runs.
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
- [ ] Saving the same posting twice returns 409.
- [ ] A saved job whose posting is absent from the mart is still returned.
- [ ] Stats sum equals the number of saved jobs.
- [ ] Top-matches returns 422 with fewer than `MIN_PROFILE_SKILLS` skills.
- [ ] With the LLM stub returning an error, top-matches still returns 200.
- [ ] Two identical top-matches calls hit the LLM stub exactly once.

## Verify
```bash
cd backend && ./mvnw verify -Dtest='Saved*IT,Match*IT'
```

## Notes
- Stub at the HTTP layer (WireMock or a `RestClient` test server). Mocking the bean
  hides the JSON parsing, which is where `MatchScorer` actually breaks.
