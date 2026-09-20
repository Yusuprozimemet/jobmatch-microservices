# Day 23 — Cut reads to NoSQL, delete the purge job

**Phase:** 4 · **Depends on:** Day 22 · **Expected PRs:** 3
**Status:** provisional — re-read and revise before starting.

## Goal
`matching-service` reads from NoSQL only, and a whole scheduled job disappears.

## In scope
- Reads switch to the NoSQL implementation, behind a config flag for one release.
- Stop the dual write. Postgres is no longer written.
- **Delete:** `JobMatchScoreCleanup`, `SchedulingConfig`, `deleteExpired()`, and the
  `job_match_scores` table (a new migration — do not edit `V10`).
- `matching-service` no longer needs a Postgres connection at all. Remove it.
- Dashboard: cache hit rate, so a regression is visible rather than merely expensive.

## Out of scope
- Moving any other data to NoSQL. Nothing else in this app is a key-value cache.

## Tracks

| Track | Owner | Work |
|---|---|---|
| A | | Read switch + config flag + rollback path |
| B | | Deletions: class, cron, table, datasource |
| C | | Cache hit-rate dashboard and alert |

## Acceptance criteria
- [ ] Two identical top-matches calls hit the LLM stub once (Day 04's test, unedited).
- [ ] `matching-service` starts with no Postgres configuration present.
- [ ] Expired rows disappear on their own, with no scheduled job anywhere.
- [ ] `grep -rn "JobMatchScoreCleanup\|SchedulingConfig"` returns nothing.
- [ ] Cache hit rate is on a dashboard and roughly matches the pre-cutover figure.

## Verify
```bash
cd services/matching-service && ./mvnw verify
grep -rn "JobMatchScoreCleanup\|SchedulingConfig" . || echo "clean"
```

## Notes
- This day removes code rather than adding it. That is the sign the NoSQL choice was
  right: the table was a key-value cache wearing a relational costume.
