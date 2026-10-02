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
- [x] **hold** — `contract/AccountDeletionIT`, unedited, 3 of 3 green after every track. Broken
      on purpose on Day 26 (`DELETE … AND false`: `expected: 0L but was: 2L`).
      3 of 3 after each backend track (#263, #264, #270) and at the close on 1ad4c63; the file
      unedited (`git diff c8bc2c4 HEAD -- backend/app/src/test/…/contract/` is empty).
- [x] **hold** — `events/UserDeletedRelayIT` 4 of 4 and `UserDeletedOutboxIT` 3 of 3 green after
      every track. Broken on purpose: the applications consumer on by default; the relay test
      reports no message for its user within 5000 ms.
      #263, #264, #270 and the close. Seen red in #263: the consumer on in the relay test's
      context, 2 of 4 failed, one with `no message for user … on applications-user-deleted
      within 5000 ms`.
- [x] **new** — The relay's envelope for a user who still exists, sent to the consumer's own
      queue, removes that user's saved jobs within 5 s and leaves another user's. Red today: no
      consumer; the rows stay (2 → 2).
      #263: `events/ApplicationsUserDeletedConsumerIT.theEventRemovesThatUsersSavedJobsAndNoOneElses`.
      Seen red: the consumer off, `expected: 0L but was: 2L`.
- [x] **new** — The same event sent three times: the queue's visible plus in-flight count is 0
      within 5 s, and its DLQ holds 0 at 10 s. Red today: no consumer; the messages stay on the
      queue. Broken on purpose: the consumer fails when it deleted 0 rows.
      #264: `theSameEventThreeTimesIsDrainedWithNothingDeadLettered`. Seen red: 0 rows treated as
      a failure, `expected: 0 but was: 2` (two copies still on the queue at 5 s).
- [x] **new** — An unreadable body is in the DLQ within 15 s, and a valid event sent behind it is
      handled within 5 s. Red today: nothing receives it, so it never reaches the DLQ. Broken on
      purpose: an exception ends the loop.
      #264: `unreadableBodiesAreDeadLetteredAndTheQueueGoesOn`, three bad bodies (not JSON, type
      `user.created`, version 2), each found in the DLQ by its marker. Seen red: an exception
      ends the loop, `expected: 0L but was: 2L` (the valid event never handled).
- [x] **new** — In matching-service, a cached profile is gone after `user.deleted` for its user:
      the next top-matches fetches the profile again (`ProfileCacheTest`'s counting stub). Red
      today: profile calls stay at 1.
      #270: `UserDeletedConsumerTest.theEventEvictsThatUsersCachedProfile`, with a 1 h cache
      window, and another user's profile still cached (1). Seen red: `evict` a no-op,
      `expected: 2 but was: 1`.
- [x] **new** — In matching-service, the same event three times is drained with 0 in its DLQ,
      and an unreadable body reaches its DLQ while a valid one behind it is handled; the same
      waits as the applications criteria, on its own queues. Red today: no consumer.
      #271: `theSameEventThreeTimesIsDrainedWithNothingDeadLettered` and
      `unreadableBodiesAreDeadLetteredAndTheQueueGoesOn`, on B1's `SqsContainer` queues (#269).
      Seen red: `evict` throws when nothing is cached, `expected: 0 but was: 2`; an exception
      ends the loop, `expected: 2 but was: 1`.
- [x] **new** — Compose, `-p day27`: after `DELETE /api/users/me`, both queues' visible plus
      in-flight count is 0 within 5 s, and both DLQs hold 0. Red today: no reader; the messages
      stay on both queues.
      #270, and again at the close on 1ad4c63 (see Notes). Red on Track A's run:
      `matching-user-deleted` kept 1 message.
- [x] **hold** — Every `job_match_scores` item has exactly the attributes `skills_hash`,
      `posting_scorer`, `score`, `scored_at`, `ttl`, `reason` (Track 0). Broken on purpose: a
      `user_id` attribute added. `contract/MatchScoreCacheIT.reusesAScoreAcrossUsersWithTheSameSkills`
      already shows the key carries no user.
      #262: `ScoresStoredTest.anItemHoldsExactlyTheScoreAttributesAndNoUser`. Seen red: `user_id`
      added in `JobMatchScoreRepository`, `the following elements were unexpected: [user_id]`.
- [x] **hold** — The existence answer is never cached (`theExistenceAnswerIsNotCached`, Day 24:
      a one-entry cache gave `expected: 2 but was: 1`), and it runs before the profile cache: a
      user whose profile is cached and whom identity then answers 404 for gets 422 (Track 0).
      Broken on purpose: the existence call skipped on a cache hit.
      #262: `ProfileCacheTest.aCachedProfileDoesNotLetAUserIdentityNoLongerKnowsIn`. Seen red:
      `users.exists` skipped on a cache hit, `expected: 422 but was: 200`, and
      `aSecondRequestWithinTheWindowDoesNotAskForTheProfile` `expected: 2 but was: 1`.

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
- **Track order: 0 (#262), A (#263), A2 (#264), B1 (#269), B2 (#270), B3 (#271),** the spec's,
  with both tracks split. Estimated 5 PRs; took 8 with this close.
- **Departures, each recorded in its PR:**
  - **Track A split in two** (#263, #264): 493 changed lines. The repeat and poison tests and the
    two `EventBus` helpers only they use went to A2; the consumer and compose stayed in A, since
    the consumer is on by default and will not start without its queue URL.
  - **Track B split in three** (#269–#271): the LocalStack container first, as the spec allowed;
    then the consumer at 493 lines, so its repeat and poison tests went to B3, as A2 followed A.
  - **SQS joined matching-service at compile scope in B1** (#269), a PR before the consumer that
    uses it, so no test scope had to be moved later (Day 26's SNS trap).
  - **The matching consumer is on by default** in its `application.yaml` (#270), as the
    backend's is: a deployment without its queue URL fails to start rather than keep a deleted
    user's skills. It is off in every matching-service test context and in the backend harness's
    matching-service container (`support/MatchingService`).
  - **Tooling PRs ran between A2 and B1** (#265–#268, the agent guard and the dev loop, both
    reverted the same day; #272, spec-drift's cache, after B3). None is Day 27's work.
- **Defect** — found: review (Track A) · cause: implementation · the implementer's consumer
  joined its thread for 7 ms instead of 7 s on shutdown, read `"version": "1"` and `1.5` as
  version 1, and its test checked the DLQ 1 s after the drain instead of at 10 s from the first
  send. Fixed in #263 before the breaks.
- **Defect** — found: review (Track B2) · cause: implementation · the eviction test asserted the
  other user's profile count without a second request for them, so a consumer that evicted
  everyone would have passed. Fixed in #270.
- **Defect** — found: review (Tracks B1, B2) · cause: implementation · Javadoc that spoke of
  shared queues matching-service's tests do not have. Removed in #269 and #270.
- **Defect** — found: Track A · cause: spec · the spec planned a split for Track B only; Track A
  passed 400 lines too, and the estimate of 5 PRs counted neither split.
- **Defect** — found: Track A2 · cause: process · my own: #263's description and commit message
  called the work KAN-6, Day 01's issue. It is KAN-46. The commit on `main` keeps the wrong key.
- **Defect** — found: Track B2 · cause: spec · compose's `finalproject` network again (Day 26's
  Notes): `down -v` on `day27` removes it. Nothing else was running either time.
- **The close's Verify, on 1ad4c63:** backend 442 tests, 0 failures, 0 errors, 1 skipped,
  checkstyle clean; matching-service 83 tests, 0 failures, checkstyle clean. Compose, `-p day27`:
  every service healthy; after `DELETE /api/users/me` (204), both consumers logged the same event
  id 8 ms apart (`removed 0 saved jobs`, `evicted cached profile`), and both queues and both DLQs
  read 0 visible and 0 in flight at the first reading, 4.9 s after the delete, and at 19.5 s.
  `down -v` after; no container outside `day27` was running.
- **Hand-offs this day leaves:**
  - **Day 25:** `backend/applications`' consumer and `ApplicationsUserDeletedConsumerIT` move
    with `saved_jobs` to application-service, and `fk_saved_jobs_user` goes, leaving the
    consumer as the only thing that removes a deleted user's saved jobs. `AccountDeletionIT`'s
    Javadoc line, in Day 25's approved edit (above).
  - **Day 28:** the DLQ alarm and the DLQs' 4-day retention of a `userId`.
  - **Day 39:** decided here: the existence call stays, and no record of deleted ids is kept.
