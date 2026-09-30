# Day 22 — matching-service keeps its scores in DynamoDB

**Phase:** 4 · **Depends on:** Day 21 · **Expected PRs:** 8 (6 tracks, the spec change, the close)

The third of Phase 4's days (41, 21, 22, 23, 24). Day 21 moved scoring, `JobMatchScoreRepository`
and the hourly purge into `services/matching-service`; the scores still sit in Postgres, in
`matching.job_match_scores`. This day moves them to DynamoDB in one step. There is no dual write
(`plan.md`, "No dual write for the NoSQL move"): the table is a cache, and a cache that starts
empty only costs model calls.

## Goal
matching-service reads and writes its scores in DynamoDB (`amazon/dynamodb-local` in compose, the
harness and CI; no test needs an AWS account). The Postgres table is no longer read or written,
and the hourly purge is gone: an item's `ttl` expires it, and the read still refuses an expired
one. A store that is down or hung costs a model call, never the request. The Day 04 matching
contract classes pass unedited.

## In scope
- **The store.** `software.amazon.awssdk:dynamodb` in matching-service's pom;
  `amazon/dynamodb-local:3.3.1` (the tag pinned) wherever a store runs. One table, from V10's
  primary key:

  | Attribute | Role |
  |---|---|
  | `skills_hash` (S) | partition key |
  | `posting_scorer` (S), `<postingId>#<scorerVersion>` | sort key |
  | `score` (N), `reason` (S), `scored_at` (N, epoch seconds) | attributes |
  | `ttl` (N, epoch seconds) = `scored_at` + retention × 86 400 | TTL attribute |

  Retention stays `app.llm.score-retention-days` (default 1, clamped to at least 1,
  `JobMatchScoreRepository.java:22-37`).
- **The repository is replaced, not wrapped.** `JobMatchScoreRepository` keeps its name and the
  two methods `JobMatchService` calls (`findScores` at `:82`, `saveScores` at `:93`); the JDBC
  body goes. There is no interface today and none is added.
  - `findScores`: one `BatchGetItem` for at most 40 keys (`SHORTLIST_SIZE`,
    `JobMatchService.java:34`; the limit is 100); `UnprocessedKeys` are misses. **Items with
    `ttl <= now` are dropped on read:** DynamoDB deletes expired items lazily (AWS documents
    "within a few days"), so the TTL frees space and the read filter decides what is served, as
    `scored_at > now() - retention` (`:54`) does today.
  - `saveScores`: `BatchWriteItem`, 25 items a call, so 40 scores are two calls. A put
    overwrites, as `ON CONFLICT DO UPDATE` does (`:84-87`). Called only with fresh model scores,
    so a failed scoring call stores nothing (Day 04's hand-off, `day-04:116-117`).
  - **Both catch.** A store failure on read is logged and treated as all misses; on write, logged
    and dropped. Today only the write catches (`:95-98`); a read failure is a 500, because the
    service's `GlobalExceptionHandler` handles only `ResponseStatusException`.
- **Timeouts inside the gateway's 30 s.** The client gets an `apiCallTimeout` of 500 ms (the
  whole call, retries included), in `application.yaml`. On the request path: three internal
  calls at 1 + 2 s, one read, the model at 5 s connect plus its read, two writes. The model's
  read goes from 15 s to **13 s** (`LLM_TIMEOUT_SECONDS`): 9 + 0.5 + 5 + 13 + 1 = 28.5 s, written
  beside the values as Day 21 wrote its sum. `ConfigurationTest:41-45` (a service-module test)
  pins 13.
- **Who creates the table.** Production is Terraform (Day 32). Locally the service creates it
  when `app.scores.create-table` is true (compose, the service's tests): create if absent, then
  `UpdateTimeToLive` on `ttl`. The harness creates its own table, without TTL, and sets the flag
  false (see *Harness*).
- **Deletions**, which leave no scheduler in the service: `JobMatchScoreCleanup`,
  `SchedulingConfig`, `deleteExpired`, `app.llm.score-purge-cron` (`application.yaml:61`) and
  `LLM_SCORE_PURGE_CRON` wherever it is set. The service's `@EnableScheduling` and only
  `@Scheduled` go with them.
- **Harness** (`backend/app/src/test/.../support/`, which Day 1–4 tests may rely on without an
  edit to them):
  - a dynamodb-local container, started once, on the same network as matching-service's;
  - `MatchingService.java:64-90` passes the endpoint, a region and dummy credentials, and
    `app.scores.create-table=false`;
  - the harness creates the table (no TTL, so the emulator's sweep cannot hide a missing read
    filter, which it does within about 10 s) and `TestDatabase.reset()` empties it. Without the
    reset, `MatchScoreCacheIT`'s six tests share one skill set (`MatchingTest.java:38`) and
    posting, and the second finds the first's score;
  - a `support/ScoreStore.ageAll(Duration)` helper that moves every stored score's
    `scored_at` (and `ttl`) into the past: on Postgres in Track 0, on DynamoDB after the switch.
- **The service's own test base** (`MatchingServiceTest`) gets a dynamodb-local container beside
  its `PostgresContainer`, with the create flag on.
- **CI** pulls the image where the tests run: `backend-ci-cd.yaml` (the harness) and
  `matching-service-ci-cd.yaml` (the service's tests).
- **The metric Day 21 left out.** matching-service registers nothing of its own and is not
  scraped: `observability/prometheus.yml` has 3 `job_name`s (backend, gateway, job-service), and
  Grafana's "top-matches latency" panel (`jobmatch.json:94`) has had no data since #226. This day
  adds a counter `jobmatch.scores.lookups`, tagged `result` = `hit` | `miss`, one per posting
  looked up; a `jobmatch-matching-service` scrape job on `matching-service:9090`; and a hit-rate
  panel. The panel at `:95` says the model timeout is 20 s; it becomes 13 s.
- **Compose:** a `dynamodb` service on the pinned tag, unpublished; matching-service gets
  `SCORES_DYNAMODB_ENDPOINT`, `AWS_REGION`, dummy `AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY`
  (emulator only; Phase 7 uses a task role) and the create flag.
- **Docs that promise the hourly purge or name Cosmos DB:** `backend/docs/privacy-data.md:167,
  206,212-216`, `matching-profile.md:265,319-320`, `api.md:465-470`, `configuration.md:140-141`,
  `schema.md:28`, `docs/target-architecture.svg:192,238,249`. Expired scores are unreadable at
  once and deleted within days; the privacy text says so.

## Out of scope
- Dropping `matching.job_match_scores`, the service's datasource, Flyway, the JDBC and Postgres
  dependencies, its `DB_*` in compose and the harness, the `matching` role and schema — Day 23.
  The table drop is a `db/matching` V2 applied by the service, not an app migration; and app's
  `V14__move_matching_tables.sql:22-27` raises if the `matching` role or schema is missing, so
  the harness keeps creating them. Day 23's spec-change PR decides both, and its overlap with
  Day 24's "no datasource" criterion (`day-24:33`).
- A span on the store call. Days 05 and 38 left JDBC spans to "Phase 4, if it shows a query worth
  tracing"; for scores the JDBC path goes, and the SDK call is not instrumented. Recorded as a
  decision, not a gap: `TopMatchesTracedIT` checks the model and job-service, which is where
  the time goes.
- A profile cache, retry, caching the existence answer — Day 24.

## Tracks

| Track | Owner | Work | Estimate |
|---|---|---|---|
| 0 | | `support/ScoreStore.ageAll` on Postgres; criterion 2's test on today's code | 80–150 |
| A | | Harness: dynamodb-local container, table, `reset()`, `MatchingService` env; the service test base's container; CI pulls. No behaviour change | 250–350 |
| B | | Client, timeouts (13 s), table creation, the repository on DynamoDB with both catches; `ageAll` on DynamoDB; criteria 1, 4, 5 | 300–400 |
| C | | Deletions: cleanup, scheduling, `deleteExpired`, the cron property; criterion 6 | 60–120 |
| D | | Counter, scrape job, hit-rate panel, the 13 s label; criterion 7 | 120–200 |
| E | | Compose and docs; criterion 8 | 150–250 |

A lands before B: without the reset, criterion 3 goes red. B and C may merge into one PR if B
is small; B splits along client-and-creation / repository if it passes 400.

## Acceptance criteria
- [ ] **new** — After one top-matches request in the service's tests, the table holds one item per
      scored posting, keyed and attributed as the table above, with `ttl` = `scored_at` + 86 400
      at the default retention, and `DescribeTimeToLive` reports `ENABLED` on `ttl`. Red today:
      no table, and `git grep -il dynamodb -- backend services docker-compose.yml .github scripts`
      finds 0 files.
- [ ] **hold** (Track 0) — A score older than the retention window is not served: after one
      request, `ScoreStore.ageAll(2 days)`, the same request asks the model again (`callCount` 2,
      not 1). Broken on purpose: the read filter removed, on Postgres (line 54) and again on
      DynamoDB; the test should report 1 against 2 both times.
- [ ] **hold** — `MatchScoreCacheIT` (6), `MatchTopMatchesIT` (8) and `MatchRankingIT` (8) pass
      unedited, directly and through the gateway (`-Dharness.gateway=true`). Broken on purpose:
      `reset()` not emptying the table, and `findScores` returning `Map.of()`; record what each
      reported.
- [ ] **new** — With the store unreachable (endpoint on a closed port), and again with it hung
      (the container paused), top-matches answers 200 with `aiScored` true and the model called,
      and the hung case within 3 s of the unreachable one. Red: the repository with the read's
      catch removed answers 500; with a 30 s `apiCallTimeout`, the hung case takes over 30 s.
- [ ] **hold** — `HungModelIT` answers under 30 s with the new sum (model read 13 s). Broken on
      purpose: `LLM_TIMEOUT_SECONDS` 30; it should report over 30 s.
- [ ] **new** — `git grep -n "JobMatchScoreCleanup\|SchedulingConfig\|deleteExpired\|@EnableScheduling\|@Scheduled\|score-purge-cron" -- '*.java' '*.yaml' '*.yml'`
      finds nothing. Red today: 8 lines in 4 files (`git grep -c` with the same pattern).
- [ ] **new** — After two identical requests for a shortlist of n postings, matching-service's
      `/actuator/prometheus` shows `jobmatch_scores_lookups_total{result="miss"}` n and
      `{result="hit"}` n; `grep -c job_name observability/prometheus.yml` is 4; `jobmatch.json`
      has a panel querying `jobmatch_scores_lookups_total`. Red today: no counter, 3 jobs.
- [ ] **new** — `docker compose --env-file .env.example config --format json` has a `dynamodb`
      service on `amazon/dynamodb-local:3.3.1` with no `ports`, and matching-service's
      environment has `SCORES_DYNAMODB_ENDPOINT`. Red today: no `dynamodb` key under `services`.

## Verify
```bash
docker build -t jobmatch-job-service:harness services/job-service
docker build -t jobmatch-matching-service:harness services/matching-service
cd backend && rm -rf */target/surefire-reports \
  && ./mvnw clean verify && ./mvnw -B checkstyle:check \
  && ./mvnw -B -pl app verify -Dharness.gateway=true && cd ..
backend/mvnw -B -f services/matching-service/pom.xml verify checkstyle:check
git grep -n "JobMatchScoreCleanup\|SchedulingConfig\|deleteExpired\|@EnableScheduling\|@Scheduled\|score-purge-cron" -- '*.java' '*.yaml' '*.yml' || echo clean
grep -c job_name observability/prometheus.yml    # 4
# compose, in a project of its own, never the maintainer's:
docker compose -p day22 --env-file .env.example up -d --build
docker compose -p day22 --env-file .env.example config --format json \
  | python -c "import json,sys; s=json.load(sys.stdin)['services']; print(s['dynamodb']['image'], 'ports' in s['dynamodb'])"
docker compose -p day22 down -v
```

## Notes
- **Rewritten from the provisional spec** after the spec-auditor (14 findings). The provisional
  day was a dual write with a success-rate metric, then Day 23 switching reads; `plan.md` has
  ruled out the dual write since the course correction, so this day takes the switch, the
  deletions and the hit-rate metric from Day 23, and Day 23 keeps the Postgres side.
- **Defect** — found: auditor · cause: spec · the provisional spec's premises were false on
  today's code: an interface `JobMatchScoreRepository` does not have; a `nosql` compose service
  that does not exist, so `docker compose stop nosql && curl` checked nothing (`stop` of an
  unknown service exits 0, and a curl without a cookie gets 401); TTL on "the schedule Postgres
  purging uses", when the read filter, not the purge, decides what is served; a dashboard metric
  in a service nobody scrapes.
- **Defect** — found: auditor · cause: spec · Day 21 dropped Day 17's pattern of a scrape job
  per extracted service (`day-17:116`): matching-service has none, and the top-matches latency
  panel has had no data since #226. Criterion 7 fixes it.
- Day 04's contract comments (`MatchScoreCacheIT.java:15-17`, `MatchTopMatchesIT.java:16-17`)
  name Days 22 and 23. They stay: what they assert is when a model call happens, not where the
  score was kept.
