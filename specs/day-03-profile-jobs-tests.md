# Day 03 — Profile and job search contract tests

**Phase:** 0 · **Depends on:** Day 01 · **Expected PRs:** 3

## Goal
Profile and job search are pinned, including the `saved_count` field that Day 08 has to
re-implement without a SQL join.

## In scope
- `GET/PUT /api/profile`: round-trip, minimum-skills validation, normalisation,
  unauthenticated returns 401.
- `GET /api/jobs`: pagination, filter by category, city, work mode, employment type;
  combined filters; empty result.
- `GET /api/jobs/filters`: returns categories and cities present in the mart.
- `GET /api/jobs/{id}`: found and not-found.
- **`saved_count` in search results** — pin the current number precisely. Day 08 changes
  how it is produced and this test must not need editing.
- Public vs authenticated routes per `SecurityConfig`: `/api/jobs` public,
  `/api/jobs/top-matches` authenticated.

## Out of scope
- Saved jobs and matching — Day 04.

## Tracks

| Track | Owner | Work |
|---|---|---|
| A | | Profile read/write/validation |
| B | | Job search, pagination, all filters |
| C | | Filters endpoint, job detail, `saved_count`, public/private route matrix |

## Acceptance criteria
- [ ] A posting saved by 3 users reports `savedCount: 3` in search results.
- [ ] Each filter is tested alone and in combination with one other.
- [ ] Paging past the last page returns an empty list, not an error.
- [ ] `GET /api/jobs` succeeds with no credentials; `/api/jobs/top-matches` returns 401.
- [ ] Profile validation rejects fewer than `UpdateProfileRequest.MIN_SKILLS` skills.

## Verify
```bash
cd backend && ./mvnw verify -Dtest='Profile*IT,Job*IT'
```

## Notes
- `saved_count` is the single most important assertion this day. It is the join that
  Day 08 deletes.
