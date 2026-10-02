# Day 27 — `user.deleted` reaches every store

**Phase:** 5 · **Depends on:** Day 26 · **Expected PRs:** 5 (the spec change, Tracks 0, A and B,
the close)

Second of Phase 5 (**26 → 27 → 25 → 28**). `application-service` does not exist yet: the
applications consumer is the monolith's `backend/applications`, and moves with it on Day 25.
`fk_saved_jobs_user` still deletes saved jobs on this day, beside the consumer.

## Goal
Every store that holds a user's data removes it on `user.deleted`, idempotently, without relying
on the foreign key.

## In scope
- The stores, as the code is after Day 26:

  | Store | Holds | On `user.deleted` |
  |---|---|---|
  | `identity`: users, credentials, profile, reset tokens, refresh tokens, pending Google links | the account | already gone: every table cascades from `identity.users` (V2, V4, identity V1–V2). Held, not rebuilt |
  | `identity.outbox` (Day 26) | a deleted user's id, until the relay publishes it | the relay deletes the row on publish (`OutboxRelay`, held by Day 26's `UserDeletedRelayIT`) |
  | `applications.saved_jobs` | saved jobs | the `backend/applications` consumer deletes the user's rows (queue `applications-user-deleted`) |
  | matching-service `ProfileCache` (Day 24 Track B) | a rankable user's skills, up to `PROFILE_CACHE_WINDOW` (10 s) | the matching-service consumer evicts the entry (queue `matching-user-deleted`) |
  | DynamoDB `job_match_scores` (Day 22; V10's table was dropped by V15 on Day 23) | scores keyed by skills hash, posting and scorer version | nothing: no item names a user. Proven by a test of the exact attribute set, not by `V10`'s comment |
  | the two DLQs | a failed message's `userId`, for SQS's default retention (4 days) | accepted on this day: a message there is an incident. Retention and the alarm go to the Day 28 review |

- **Library: the AWS SDK v2 `SqsClient`, a polling loop**, in both services. It matches Day 26's
  SNS publisher, and Spring Cloud AWS is unverified on Boot 4.1. The loop long-polls, deletes a
  message only after it was handled, and leaves a failed one to come back after the queue's
  visibility timeout; the redrive policy then moves it to the DLQ. An exception in one message
  never ends the loop.
- **SQS moves from `app`'s test scope to `applications`' compile scope** (`backend/app/pom.xml`
  declares it test-scoped; a direct test scope overrides a transitive compile one, and the jar
  ships without it: Day 26 hit this with SNS).
- **Each consumer is off by default** (`app.events.consumer.enabled`), as the relay is: the
  harness caches contexts, and a live consumer would take the messages `UserDeletedRelayIT` waits
  for on the shared queues. Only the consumer's own test context turns it on, with
  `@DirtiesContext(AFTER_CLASS)`.
- **The consumer tests use queues of their own**, created by the test: a queue and its DLQ,
  `VisibilityTimeout` 1 s, `maxReceiveCount` 5, the queue URL passed as a property. The shared
  queues keep the default 30 s, which makes a failed message take over 2 minutes to reach the
  DLQ, and the shared DLQ also receives other tests' messages.
- Both consumers follow `docs/events/user-deleted.md` (Day 26): a repeat is a success, an
  unreadable body goes to the DLQ after the redrive count, and one bad message does not stop the
  queue.
- **The consumer test bypasses the key.** While `fk_saved_jobs_user` exists, a real account
  delete removes the rows whether or not the consumer works, so `AccountDeletionIT` cannot show
  the consumer works. The test sends the relay's envelope for a user who still exists in
  identity to its own queue, and expects that user's saved jobs to go and another user's to stay.
- **matching-service's tests get a LocalStack container of their own.** Day 26 built it only in
  `backend/`'s `support/`, and the harness's matching-service container is not on its network.
  The matching consumer stays off in the backend harness.
- **Compose:** both consumers on, with their queue URLs; matching-service depends on
  `localstack`. The comments that say the queues have no reader until Day 27
  (`docker-compose.yml`, `support/EventBus`) are updated.
- **Day 39's hand-off, decided here:** the existence call `GET /internal/users/{id}` stays. A
  record fed by `user.deleted` lets a deleted user in until the event arrives; the call does not.
  Day 24 measured it inside top-matches with no error in 160 calls: a mean of 7.5 ms and 5.7 ms
  over two runs, against 43.9 ms and 34.9 ms for the whole request (Day 24's Notes).

## Out of scope
- Moving `saved_jobs`, and the key going with it — Day 25.
- The uploads bucket's consumer — Phase 6, when the bucket exists.
- A verification script and a deletion audit record (the provisional spec had both): cut by the
  scope rule (`plan.md`, "Course correction"). The consumer tests answer "is this user erased?"
  per store, and the consumers log the user id and a row count, no email or name.
- The DLQ alarm and the DLQs' retention — the Day 28 review (Day 26's Notes).
- A UI for account deletion: `DELETE /api/users/me` exists, and the frontend is not in this
  phase.

## Tracks

| Track | Owner | Work |
|---|---|---|
| 0 | | Two tests that pass before and after: the exact attribute set of a written `job_match_scores` item; a cached profile whose user identity then answers 404 for gets 422 |
| A | | The `backend/applications` consumer: SQS at compile scope, the polling loop behind its property, idempotent delete, the bypass test on its own queue, the poison message; compose wiring for the backend |
| B | | The matching-service consumer: evict the profile cache entry; repeat and poison tests; LocalStack in matching-service's own tests; compose wiring and the compose check |

Track B changes the matching-service image that the backend harness starts, so it runs after A.
If it passes 400 lines, the LocalStack test container splits off first.

## Acceptance criteria
- [ ] **hold** — `contract/AccountDeletionIT`, unedited, 3 of 3 green after every track. Broken
      on purpose on Day 26 (`DELETE … AND false`: `expected: 0L but was: 2L`).
- [ ] **hold** — `events/UserDeletedRelayIT` 4 of 4 and `UserDeletedOutboxIT` 3 of 3 green after
      every track. Broken on purpose: the applications consumer on by default; the relay test
      reports no message for its user within 5000 ms.
- [ ] **new** — The relay's envelope for a user who still exists, sent to the consumer's own
      queue, removes that user's saved jobs within 5 s and leaves another user's. Red today: no
      consumer; the rows stay (2 → 2).
- [ ] **new** — The same event sent three times: the queue's visible plus in-flight count is 0
      within 5 s, and its DLQ holds 0 at 10 s. Red today: no consumer; the messages stay on the
      queue. Broken on purpose: the consumer fails when it deleted 0 rows.
- [ ] **new** — An unreadable body is in the DLQ within 15 s, and a valid event sent behind it is
      handled within 5 s. Red today: nothing receives it, so it never reaches the DLQ. Broken on
      purpose: an exception ends the loop.
- [ ] **new** — In matching-service, a cached profile is gone after `user.deleted` for its user:
      the next top-matches fetches the profile again (`ProfileCacheTest`'s counting stub). Red
      today: profile calls stay at 1.
- [ ] **new** — In matching-service, the same event three times is drained with 0 in its DLQ,
      and an unreadable body reaches its DLQ while a valid one behind it is handled; the same
      waits as the applications criteria, on its own queues. Red today: no consumer.
- [ ] **new** — Compose, `-p day27`: after `DELETE /api/users/me`, both queues' visible plus
      in-flight count is 0 within 5 s, and both DLQs hold 0. Red today: no reader; the messages
      stay on both queues.
- [ ] **hold** — Every `job_match_scores` item has exactly the attributes `skills_hash`,
      `posting_scorer`, `score`, `scored_at`, `ttl`, `reason` (Track 0). Broken on purpose: a
      `user_id` attribute added. `contract/MatchScoreCacheIT.reusesAScoreAcrossUsersWithTheSameSkills`
      already shows the key carries no user.
- [ ] **hold** — The existence answer is never cached (`theExistenceAnswerIsNotCached`, Day 24:
      a one-entry cache gave `expected: 2 but was: 1`), and it runs before the profile cache: a
      user whose profile is cached and whom identity then answers 404 for gets 422 (Track 0).
      Broken on purpose: the existence call skipped on a cache hit.

## Verify
```bash
docker build -t jobmatch-job-service:harness services/job-service
docker build -t jobmatch-matching-service:harness services/matching-service
cd backend && rm -rf */target/surefire-reports && ./mvnw clean verify && ./mvnw -B checkstyle:check
./mvnw -B -f ../services/matching-service/pom.xml clean verify checkstyle:check
cd .. && docker compose -p day27 --env-file .env.example up -d --build --wait
docker compose -p day27 exec localstack awslocal sqs get-queue-attributes \
  --queue-url http://localhost:4566/000000000000/applications-user-deleted \
  --attribute-names ApproximateNumberOfMessages ApproximateNumberOfMessagesNotVisible
docker compose -p day27 down -v                 # a project of its own: never the maintainer's
```

## Notes
- This is a legal obligation, not a feature. A consumer that fails is an incident; the DLQ alarm
  is handed to the Day 28 review (Day 26's Notes).
- **Hand-off to Day 25:** `AccountDeletionIT`'s Javadoc says "Day 27 replaces the key"; the plan's
  order moved that to Day 25. Day 27 cannot edit the class (an unedited hold), so the line is
  corrected in Day 25's approved edit.
- **Spec change, before the tracks:** the spec-auditor (Opus) found the matching-service Verify
  line failing (`./mvnw: No such file or directory`: there is no wrapper there); consumers that
  would have taken the relay test's messages; criteria 3–4 unable to fail on the shared 30 s
  queues; a hold citing a test that did not exist; the matching consumer's failure paths, the
  compose wiring and SQS's Maven scope missing. The maintainer chose the SDK polling loop and
  accepted the DLQs' retention for this day.
