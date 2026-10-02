# Day 26 — `user.deleted` leaves identity through an outbox

**Phase:** 5 · **Depends on:** Day 24 (Phase 4 closed) · **Expected PRs:** 6 (the spec change,
Tracks A, B, C1 and C2, the close)

The first of Phase 5's days, in the plan's order: **26 → 27 → 25 → 28** (`plan.md`, Phase 5,
"Deletion first"). The events and their consumers run while `fk_saved_jobs_user` still deletes a
user's saved jobs; only then does the table move (Day 25). Today a deleted account's saved jobs
go by that key (V2, carried across schemas by V13) and nothing else knows a user was deleted.

## Goal
Deleting an account publishes one versioned `user.deleted` event to an SNS topic, with one SQS
queue per consumer, if and only if the delete committed; no event is lost when the bus or the
relay is down.

## In scope
- **The bus, local only** (`plan.md`, "Target cloud"): `localstack/localstack:4.14.0`, pinned, in
  compose (no published port) and in the harness (a `support/` container, as `ScoreTable` is).
  One topic, `user-deleted`; two queues subscribed to it with raw message delivery,
  `applications-user-deleted` and `matching-user-deleted`, each with a dead-letter queue
  (`<queue>-dlq`, redrive after 5 receives). Compose creates them with a `ready.d` hook script
  (LF, `.gitattributes`); the harness with the SDK. No reader until Day 27.
- **Why that image:** the spec-auditor ran 4.14.0 with no auth token: SNS → SQS with raw delivery
  arrived raw, and redrive moved a message to the DLQ. It warns of a "unified image in March
  2026", so the tag stays pinned. If Track A finds it fails any of the three, stop and ask.
- **The outbox:** `identity.outbox`, a new identity migration (`db/identity/V4__*.sql`, applied
  as `identity_user`). `UserService.deleteUserByEmail` writes the outbox row and the `DELETE` in
  **one transaction**. Today it has none: two autocommit statements (`UserService.java:62-68`).
  No transaction manager is primary, so it is `@Transactional("identityTransactionManager")`
  (`IdentityDatabase.java:48`), as `AuthenticationService.java:42` does.
- **The relay**, in the monolith beside identity: a scheduled pass (1 s) that takes unpublished
  rows (`FOR UPDATE SKIP LOCKED`), publishes each, and deletes the row once SNS has accepted it.
  At-least-once: a pass that publishes and fails before the delete publishes again. The SNS
  call has a timeout (2 s) shorter than any test's wait.
- **The relay is off in the harness by default** (a property): the run caches several contexts,
  and each one's relay would take another test's row. Only the context for criteria 4–7 turns it
  on, at the cost of its two pools (identity, applications).
- **Tests on the shared queues** match their own `userId` and `eventId`, delete what they
  receive, and never count another test's message or a DLQ arrival. A receive raises the count
  of every message it sees.
- **The event, v1**, in `docs/events/user-deleted.md`: the body is exactly `eventId` (the row's
  id, so a republish keeps it), `type` (`"user.deleted"`), `version` (`1`), `userId`,
  `occurredAt`. No email, no name. The page states the consumer rules Day 27 is held to:
  idempotent, a repeat is a success, an unreadable body goes to the DLQ.
- The SDK v2 SNS client in `identity` (compile scope; the backend has SDK 2.46.7 for DynamoDB at
  test scope, `app/pom.xml:169`), and SQS at test scope.

## Out of scope
- Consuming the event — Day 27. Until then both queues fill and nothing reads them.
- `user.registered`: it has no consumer, so it is not emitted (`plan.md`, Phase 5).
- DLQ depth on a dashboard with an alert: the local Prometheus does not scrape the emulator; the
  alarm belongs in CloudWatch. Handed to the Day 28 review for the Days 29–37 rewrite.
- Terraform for the topic and queues — Day 32, rewritten after Day 28.
- Replacing `GET /internal/users/{id}` with a record of deleted ids (Day 39's hand-off): Day 27.
- The relay leaving the monolith: it moves with identity on Day 28.

## Tracks

| Track | Owner | Work |
|---|---|---|
| A | | The emulator in compose and the harness; topic, queues and DLQs; criterion 8 |
| B | | `identity.outbox`, the transactional write; criteria 2 and 3 |
| C1 | | The relay (off in the harness by default), the SNS client, the event page; criteria 4 and 5 |
| C2 | | The failure modes: criteria 6 and 7 |

Order A, B, C1, C2; B needs no bus and may go first. C1's relay-off default keeps B's tests
reading their rows. No Track 0: the test that holds through the day exists.

## Acceptance criteria
- [x] **hold** — `contract/AccountDeletionIT`, unedited, 3 of 3 green after every track: the
      key still deletes the saved jobs, and the outbox write must not break the delete or end
      the session differently (`plan.md`: green "through every day of the phase"). Broken on
      purpose in this spec change: `UserRepository.deleteUser` ran `DELETE ... AND false` and
      answered as if it had deleted; `deletingTheAccountRemovesItsSavedJobs` reported
      `expected: 0L but was: 2L`. The other two stayed green (see Notes). Reverted.
- [x] **new** — Deleting an account through `DELETE /api/users/me` leaves exactly one outbox row
      for that user with type `user.deleted` (relay off). Red today:
      `relation "identity.outbox" does not exist`.
- [x] **new** — The row and the delete commit together, tested both ways: a `DELETE` that fails
      (a test-only table keyed to `identity.users` without cascade, as
      `ModuleConnectionsIT.norATableAModuleCreatesLater` creates one) leaves no outbox row; an
      outbox insert that fails (a test-only trigger) leaves the user and their saved jobs. Red
      today: no outbox table. Broken on purpose: the `@Transactional` removed; one case must fail
      whichever statement runs first.
- [x] **new** — After a delete, a message for that `userId` arrives on both queues within 5 s,
      and the outbox row is gone. Red today: no topic, no relay.
- [x] **new** — The message body has exactly the five v1 fields, `version` is `1`, and
      `docs/events/user-deleted.md` names them and the consumer rules. Red today: no message,
      no page. Broken on purpose: `email` added to the body; the test must name it.
- [x] **new** — With the emulator container paused, the delete still answers 204 and the row
      stays; unpaused, the message arrives on both queues. Red today: no relay.
- [x] **new** — A relay pass that publishes and then fails before deleting the row publishes it
      again on the next pass: two messages with one `eventId`, a duplicate by design. Red today:
      no relay.
- [x] **new** — `docker compose -p day26 config --images | grep -x 'localstack/localstack:4.14.0'`
      prints one line, the service publishes no port, and after `up` the commands in Verify list
      the topic and the four queues. Red today: the grep prints nothing (8 images, none SNS/SQS).
      #256 (`localstack` alone); at the close, on 25d561d, the full `up -d --build --wait` in
      `-p day26`: all services healthy, the grep one line of 9 images, `docker port` empty, the
      topic `user-deleted` and the two queues with their two DLQs listed. Torn down after.
      #256, #257, #258, #259: 3 of 3 after each track, the file unedited
      (`git diff 120cfdf^1 HEAD -- .../contract/` is empty). Seen red in #255 as above.
      #257: `events/UserDeletedOutboxIT.deletingTheAccountLeavesOneUserDeletedRow`. Seen red:
      `outbox.add` removed, the row count `0`, and the trigger case `expected: 500 but was: 204`.
      #257: `aDeleteThatFailsLeavesNoOutboxRow` and
      `anOutboxInsertThatFailsLeavesTheUserAndTheirSavedJobs`. Seen red, `@Transactional`
      removed: delete first, the insert case (`Expecting value to be true but was false`, the
      user gone); insert moved first, the delete case (`Expecting empty but was:
      ["user.deleted"]`).
      #258: `events/UserDeletedRelayIT.aDeletedAccountArrivesOnBothQueuesAndLeavesTheOutbox`,
      5 s on each queue. Seen red: the row never deleted, `expected: 0L but was: 1L`; a missing
      topic, `no message for user … on applications-user-deleted within 5000 ms`.
      #258: `theMessageIsVersionOneOfTheEventPage` reads the body and
      `docs/events/user-deleted.md`. Seen red: `email` added to the body, listed under "keys
      not expected".
      #259: `aDeleteWhileTheBusIsDownIsPublishedOnceItIsBack` (`EventBus.pause()`). Seen red:
      the publish failure swallowed inside the transaction, `expected: 1L but was: 0L`, the row
      gone and the event lost.
      #259: `aPublishThatFailsBeforeTheDeleteIsPublishedAgainWithTheSameEventId` (a trigger
      that refuses one delete, counted by a sequence). Seen red: a new `eventId` per publish,
      `expected: "ce94e453-…" but was: "2f47de5b-…"`; the row deleted before the publish,
      `Expected size: 2 but was: 1`.

## Verify
```bash
docker build -t jobmatch-job-service:harness services/job-service
docker build -t jobmatch-matching-service:harness services/matching-service
cd backend && rm -rf */target/surefire-reports && ./mvnw clean verify && ./mvnw -B checkstyle:check
cd .. && docker compose -p day26 --env-file .env.example up -d --build --wait
docker compose -p day26 config --images | grep -x 'localstack/localstack:4.14.0'
docker compose -p day26 exec localstack awslocal sns list-topics    # user-deleted
docker compose -p day26 exec localstack awslocal sqs list-queues    # two queues, two DLQs
docker compose -p day26 down -v                 # a project of its own: never the maintainer's
```

## Notes
- **The order** is the plan's: Day 26 first, while the key still deletes, so `AccountDeletionIT`
  holds unedited today and on Day 27, where the consumer tests bypass the key. Day 25 moves the
  table, the key goes with it, and the test gets its one approved edit there.
- **What the break showed:** with a delete that does nothing, only
  `deletingTheAccountRemovesItsSavedJobs` failed; `leavesEveryoneElsesSavedJobsAlone` passes
  whether or not anything was deleted. Day 25's edit makes it wait for the deleted user's rows.
- **Why delete the row on publish,** not mark it: a published row would keep a deleted user's id
  in identity with nothing to remove it. Until it is published, it does (Day 27's store table).
- **Defect** — found: auditor (plan-auditor, Phases 4–5) · cause: spec · the provisional Day 26
  depended on Day 25, put RabbitMQ in compose and emitted `user.registered`; `plan.md` runs 26
  first, on SNS → SQS, with no `user.registered`. This spec change.
- **Defect** — found: auditor (spec-auditor, on this rewrite) · cause: spec · the rollback check
  could not fail if the code deleted before inserting; every cached context would run a relay;
  "four module pools" (two: identity, applications) hid the named transaction manager; the
  compose check had no command. Fixed before this spec change opened.
- **Track order: A (#256), B (#257), C1 (#258), C2 (#259),** the spec's. Six PRs as estimated.
- **Departures, each recorded in its PR:**
  - **The SNS SDK moved from `app`'s test scope to `identity`'s compile scope** (#258), not
    added beside it: `app`'s direct `<scope>test</scope>` would have overridden the transitive
    compile scope and left SNS out of the packaged jar.
  - **The topic ARN has no default** (#258): with the relay on, the application refuses to start
    without one, so production never falls back to a LocalStack ARN. Compose sets it.
  - **`UserDeletedRelayIT` closes its context after the class** (`@DirtiesContext`, #258), which
    the spec did not name: a cached context would keep its scheduler and take later classes'
    rows.
  - **Track A checked compose with `localstack` alone;** the full `up --build --wait` was left to
    the close, which ran it (criterion 8).
- **Defect** — found: Track B · cause: spec · In scope did not name the two harness tests that
  pin identity's tables and migration history, `BackendApplicationTests` and
  `ModuleMigrationsIT`; V4 turned both red, and #257 added the outbox to each. Neither is a Day
  1–4 test.
- **Defect** — found: review (Track A) · cause: implementation · the implementer's queue cleanup
  deleted the next message on the queue, not the one it had received, which the spec forbids on
  shared queues; a client read before it was started; a "started" guard set before the topic and
  queues existed. Fixed in #256.
- **Defect** — found: review (Track C1) · cause: implementation · the implementer caught the
  publish exception inside the transaction, so a failed publish would commit the row's delete and
  lose the event; it also made a LocalStack ARN the production default, put a test profile in
  `src/main/resources`, and matched the `userId` on one queue of two. Fixed in #258; the first
  is the break #259 records for criterion 6.
- **Defect** — found: break on purpose · cause: process · #257's first two scripted runs of the
  breaks never started Maven: Python's `shell=True` went through cmd.exe, then WSL's bash. A
  third, from Git Bash, produced the record above.
- **Defect** — found: Track A · cause: spec · compose's default network has the fixed name
  `finalproject`, so a `-p day26` project shares it with the maintainer's. The Verify's
  `down -v` touches no volume of theirs, but removes the network if their stack is down.
- **What the event costs:** the relay's context is one more cached context in the run, at two
  pools; every other context runs with the relay off, so a row written in a test stays until the
  test reads it.
- **Hand-offs this day leaves:**
  - **Day 27:** the consumers, held to `docs/events/user-deleted.md`; the poison-message path;
    the decision on Day 39's record of deleted ids.
  - **Day 28:** the relay moves with identity; the review lists the DLQ alarm and the topic and
    queues in Terraform for the Days 29–37 rewrite.
