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
- [x] **new** — After one top-matches request in the service's tests, the table holds one item per
      scored posting, keyed and attributed as the table above, with `ttl` = `scored_at` + 86 400
      at the default retention, and `DescribeTimeToLive` reports `ENABLED` on `ttl`. Red today:
      no table, and `git grep -il dynamodb -- backend services docker-compose.yml .github scripts`
      finds 0 files.
      #237 and #239: `ScoreTableCreatedTest` (key schema; `DescribeTimeToLive` `ENABLED` on `ttl`)
      and `ScoresStoredTest` (one item per scored posting, its attributes, `ttl` = `scored_at` +
      86 400). Red before: no table, and the grep found no file (#232, at c091bed). Broken on purpose:
      TTL never enabled (#237), `expected: ENABLED but was: DISABLED`; `ttl` without the retention
      (#239), off by exactly 86 400.
- [x] **hold** (Track 0) — A score older than the retention window is not served: after one
      request, `ScoreStore.ageAll(2 days)`, the same request asks the model again (`callCount` 2,
      not 1). Broken on purpose: the read filter removed, on Postgres (line 54) and again on
      DynamoDB; the test should report 1 against 2 both times.
      `matching/ExpiredScoreIT`, written on Postgres in Track 0 (#233). Broken on purpose: the
      `scored_at` line replaced by `AND :retentionDays > 0` (#233), `expected: 2 but was: 1`; the same
      again in Track C (#238), the same report; the `ttl` filter removed on DynamoDB (#239), expected
      2, was 1.
- [x] **hold** — `MatchScoreCacheIT` (6), `MatchTopMatchesIT` (8) and `MatchRankingIT` (8) pass
      unedited, directly and through the gateway (`-Dharness.gateway=true`). Broken on purpose:
      `reset()` not emptying the table, and `findScores` returning `Map.of()`; record what each
      reported.
      #236 (the table, its reset) and #239 (the repository); the three classes unedited, as all of
      `contract/` (0 lines since Day 21). Through the gateway in #236, #239 and #242: all green but
      `ServiceJwksIT` (see Notes). Broken on purpose: `ScoreTable.empty()` out of `reset()` (#236),
      `ScoreTableTest.theResetEmptiesIt` `expected: 0 but was: 3`; `findScores` returning `Map.of()`
      (#239), 4 of `MatchScoreCacheIT`'s 6 red, expected 1 model call, was 2.
- [x] **new** — With the store unreachable (endpoint on a closed port), and again with it hung
      (the container paused), top-matches answers 200 with `aiScored` true and the model called,
      and the hung case within 3 s of the unreachable one. Red: the repository with the read's
      catch removed answers 500; with a 30 s `apiCallTimeout`, the hung case takes over 30 s.
      #239: `ScoreStoreUnreachableTest` and `ScoreStoreHungTest`; the hung case asserts under 3 s
      in total, stricter than "within 3 s of the unreachable one". Broken on purpose: the read's catch
      removed, both `top-matches answered 500`; `api-call-timeout` 30 s, `ScoreStoreHungTest` 60 645 ms
      against 3 000 (a 30 s read and a 30 s write).
- [x] **hold** — `HungModelIT` answers under 30 s with the new sum (model read 13 s). Broken on
      purpose: `LLM_TIMEOUT_SECONDS` 30; it should report over 30 s.
      #237: `LLM_TIMEOUT_SECONDS` 13, the sum beside it; `ConfigurationTest` pins 13. Broken on
      purpose: 30 again, `HungModelIT` reported `45.7318191S` against less than `30S`; at 13, 3 of 3
      passed.
- [x] **new** — `git grep -n "JobMatchScoreCleanup\|SchedulingConfig\|deleteExpired\|@EnableScheduling\|@Scheduled\|score-purge-cron" -- '*.java' '*.yaml' '*.yml'`
      finds nothing. Red today: 8 lines in 4 files (`git grep -c` with the same pattern).
      #238 deleted them; on b00abd9 the grep finds nothing (exit 1). Red before: 8 lines in 4 files
      (#232, at c091bed). Broken on purpose at the close: `import …EnableScheduling;` and `@EnableScheduling` on
      `MatchingServiceApplication`: the grep found that one line (exit 0); reverted, nothing (exit
      1). Written fully qualified, the grep missed it; see Notes.
- [x] **new** — After two identical requests for a shortlist of n postings, matching-service's
      `/actuator/prometheus` shows `jobmatch_scores_lookups_total{result="miss"}` n and
      `{result="hit"}` n; `grep -c job_name observability/prometheus.yml` is 4; `jobmatch.json`
      has a panel querying `jobmatch_scores_lookups_total`. Red today: no counter, 3 jobs.
      #241: `ScoreLookupsCountedTest`, two identical requests for 3 postings, 3 misses then 3 hits
      and 1 model call; the `jobmatch-matching-service` scrape job (4 `job_name`s, promtool `SUCCESS`);
      the hit-rate panel; the latency panel's label 13 s. Broken on purpose: `hits.increment` removed,
      expected 3, was 0; the counting moved after the early return, expected 3, was 0. Red before: 3
      jobs (#232).
- [x] **new** — `docker compose --env-file .env.example config --format json` has a `dynamodb`
      service on `amazon/dynamodb-local:3.3.1` with no `ports`, and matching-service's
      environment has `SCORES_DYNAMODB_ENDPOINT`. Red today: no `dynamodb` key under `services`.
      #242: the check prints `amazon/dynamodb-local:3.3.1 False`. Red before: no `dynamodb`
      service (#232, at c091bed). Broken on purpose at the close: `ports: ["8000:8000"]` on `dynamodb`: the check printed
      `amazon/dynamodb-local:3.3.1 True`; reverted, `False`, and `SCORES_DYNAMODB_ENDPOINT` present.

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
- **Track order: B1 (#237), C (#238), B2 (#239).** B2's repository drops `deleteExpired`, which
  C's cleanup job called, so C went between the halves. B split along client-and-creation /
  repository, as the tracks section allowed; B2 is 399 changed lines.
- **Defect** — found: self · cause: process · #237's branch was `track-b-…`, not `track-b1-…`,
  so `spec-drift.py` counted it as all of Track B and reported C next with B half done. #239 is
  `track-b2-…`.
- **Two departures from the spec, both the main session's call, each recorded in its PR:**
  - **#240 fixed in Track E (#242), not in a PR of its own.** Table creation shared the request
    path's 500 ms `apiCallTimeout`; a cold dynamodb-local failed the context load (3 of 10
    single-class runs locally, 32 errors of 70 in one full run; CI passed every run). Logged
    during Track D (#241) for a later PR; compose, which E added, is where it would show first.
    Creation's calls now carry 5 s each and retry the describe for up to 30 s on
    `SdkClientException`; the request path stays at 500 ms. `ScoreTableStartupTest` pins both:
    without the 5 s, `ApiCallTimeoutException … 500 millis`; without the retry,
    `SdkClientException: Unable to execute HTTP request`.
  - **Criterion 4's hung case asserts under 3 s in total** (#239), stricter than "within 3 s of
    the unreachable one".
- **Defect** — found: self · cause: spec · #232's first draft pinned criterion 6's grep at 9
  lines; it finds 8. Corrected before the PR.
- **Defect** — found: review · cause: implementation · five tracks' first drafts, all by the
  implementer on Haiku: an order-dependent reset test, at 390 lines (#236); the TTL status
  compared as a string, accepting `ENABLING` (#237); the read filtered on `scored_at`, not `ttl`,
  and a `null` reason, which DynamoDB refuses and which would drop a whole 25-item batch (#239,
  no test covers it); `ScoreLookupsCountedTest` loosened to "some hits" instead of finding why
  only 1 of 3 scores was stored, which was `MatchScorer.shortId` cutting `lookups-1/2/3` to one
  id (#241); and the doc edits reverted in the working tree after being told not to touch them
  (#242).
- **Defect** — found: break on purpose · cause: implementation · both criterion-4 tests first set
  `app.scores.endpoint`, which the test base overrides, so they talked to the healthy store and
  stayed green with the read's catch removed (#239; now `test.scores.*`). #242's first
  slow-store test delayed only the first answer, and its late-store test started the store at
  1.5 s, inside the SDK's own retries: both passed without the fix. Each was rewritten before the PR.
- **A store with no span**, as Out of scope decided: `TopMatchesTracedIT` still sees the model
  and job-service.
- **The close's Verify, on b00abd9:** the job-service, matching-service and api-gateway harness images
  rebuilt; `clean verify` 429 tests, 0 failures, 0 errors, 1 skipped, counted from the reports
  after deleting them; `checkstyle:check` 0 violations; through the gateway 429, only
  `ServiceJwksIT` red (3 failures, 1 error); matching-service `verify checkstyle:check` 72, 0
  failures; the deletions grep nothing (exit 1); `job_name` 4; the compose check
  `amazon/dynamodb-local:3.3.1 False`; `up -d --build --wait` in a project of its own (`day22`):
  every service running, matching-service healthy after "Created the score table
  job_match_scores", then `down -v` on that project.
- **Found, not owned:**
  - `ServiceJwksIT` still fails through the gateway (3 failures, 1 error, 401 on
    `/.well-known/service-jwks.json`), the same on `main` before each of #233, #236, #239 and
    #242; CI's gateway run leaves it out. Day 21 left it to "any day that touches the gateway's
    key sets"; still none does.
  - `ScoreTableStartupTest` (#242) is in package `…backend.matching`, the service's other tests
    in `…matchingservice`. It runs (the name ends in `Test`); the package is a slip.
- **The estimate:** 8 PRs expected (6 tracks, the spec change, the close). Took 7 track PRs
  (#233, #236, #237, #238, #239, #241, #242), 1 spec change (#232) and this close, 9. The tracks
  came to 1,289 changed lines against the table's 960–1,470. No `Oversized:` line.
- **Tests:** `contract/` unchanged; `support/` +194 −3 (`ScoreTable` 94, its self-test 53,
  `ScoreStore` 32, `MatchingService` +12 −1, `TestDatabase` +3 −2); outside both, one new class,
  `ExpiredScoreIT` (36). No existing test was edited.
- **Hand-offs this day leaves. Day 23's, 24's and 32's specs name none of them yet:**
  - **Day 23:** `matching.job_match_scores` (a `db/matching` V2, applied by the service), the
    service's datasource, Flyway, JDBC and Postgres dependencies, its `DB_*` in compose and the
    harness, and the `matching` role and schema, which `V14__move_matching_tables.sql:22-27`
    raises without. Its spec is still the provisional "nosql-cutover" draft, written for a dual
    write this day did not do; its spec-change PR starts from these.
  - **Day 24:** the profile cache, retry, the existence answer; and its "no datasource"
    criterion (`day-24:33`), which holds only after Day 23.
  - **Day 32:** the scores table in Terraform, partition `skills_hash`, sort `posting_scorer`,
    TTL on `ttl`, with `app.scores.create-table` false in production.
  - **Phase 7:** a task role in place of compose's dummy `AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY`.
  - **Any day that touches the gateway's key sets:** `ServiceJwksIT` through the gateway.
- **Defect** — found: close · cause: spec · criterion 6's grep cannot see a fully qualified
  annotation. The close's first break inserted `@org.springframework.scheduling.annotation.EnableScheduling`
  on `MatchingServiceApplication` and the grep still found nothing (exit 1). Imported and written
  `@EnableScheduling`, it found the line (exit 0). Code normally imports it, so the check stands;
  the blind spot is recorded here.
- **Defect** — found: close · cause: process · criteria 6 and 8 had a red before the change (#232)
  but no break in any track; both were first broken at the close, as recorded above.
