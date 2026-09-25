# Day 20 — job-service gets its own database

**Phase:** 3 · **Depends on:** Day 19 · **Expected PRs:** 3
**Status:** provisional — re-read and revise before starting.

## Goal
The mart lives in a database only `job-service` and the data pipeline can reach.

## In scope
- New `jobs_db`. In compose, a second database on the same Postgres container is enough.
- Repoint `data/src/publishing/sync.py` at `jobs_db`. **The write-then-swap must keep
  working** — it is the only writer and the reason the mart is safe to replace wholesale.
- Update `scripts/db-setup.py`: `analytics_user` owns `jobs_db`, and the monolith's role
  loses access to it entirely.
- Airflow connection and the `data` CI workflow updated.
- Day 01 mart fixtures copied to `job-service`'s test harness (not moved: see Notes).
- Rollback plan written down before the cutover, and rehearsed once.

## Out of scope
- Separate Postgres servers. One server, several databases.

## Tracks

| Track | Owner | Work |
|---|---|---|
| A | | New database, roles, compose wiring |
| B | | Publish sync + Airflow repointing |
| C | | Test fixtures copied to job-service's harness; rollback rehearsal |

## Acceptance criteria
- [ ] The monolith's database role cannot read `analytics.fct_postings` (assert it).
- [ ] A full pipeline run publishes into `jobs_db` and job search reflects it.
- [ ] Write-then-swap still leaves no window where the mart is empty.
- [ ] All tests pass **unedited**.
- [ ] The rollback has been performed once on purpose, and documented.

## Verify
```bash
docker compose down -v && docker compose up -d --build
docker compose run --rm pipeline
curl -s localhost:8080/api/jobs | head -c 200
```

## Notes
- **End of Phase 3.** One service is fully independent: own image, own API, own database.
- The pipeline is the risk here, not the service. It is the one part of the system with
  no test coverage at all.
- From Day 40: `SavedJobHydrationQueriesIT` expires today, not before. `StatementCounter` counts
  per database, and job-service used the test database until now. The mart fixtures are copied
  to job-service's harness, not moved: the monolith's contract classes still seed postings
  through `PostingBuilder`, which from today has to write where job-service reads.
