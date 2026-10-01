# Day 27 — `user.deleted` reaches every store

**Phase:** 5 · **Depends on:** Day 26 · **Expected PRs:** 5 (the spec change, Tracks A and B,
Track 0 if the rewrite asks for one, the close)
**Status:** provisional — revised for the plan's order and the SNS/SQS bus in Day 26's spec
change. Re-read against the code, with the spec-auditor, before starting.

Second of Phase 5 (**26 → 27 → 25 → 28**). `application-service` does not exist yet: the
applications consumer is the monolith's `backend/applications`, and moves with it on Day 25.
`fk_saved_jobs_user` still deletes saved jobs on this day, beside the consumer.

## Goal
Every store that holds a user's data removes it on `user.deleted`, idempotently, without relying
on the foreign key.

## In scope
- The stores, as the code is after Day 24:

  | Store | Holds | On `user.deleted` |
  |---|---|---|
  | `identity`: users, credentials, profile, reset tokens, refresh tokens, pending Google links | the account | already gone: every table cascades from `identity.users` (V2, V4, identity V1–V2). Held, not rebuilt |
  | `identity.outbox` (Day 26) | a deleted user's id, until the relay publishes it | the relay deletes the row on publish |
  | `applications.saved_jobs` | saved jobs | the `backend/applications` consumer deletes the user's rows (queue `applications-user-deleted`) |
  | matching-service `ProfileCache` (Day 24 Track B) | a rankable user's skills, up to `PROFILE_CACHE_WINDOW` (10 s) | the matching-service consumer evicts the entry (queue `matching-user-deleted`) |
  | DynamoDB `job_match_scores` (Day 22; V10's table was dropped by V15 on Day 23) | scores keyed by skills hash, posting and scorer version | nothing: no item names a user. Proven by a test, not by `V10`'s comment |

- Both consumers follow `docs/events/user-deleted.md` (Day 26): a repeat is a success, an
  unreadable body goes to the DLQ after the redrive count, and one bad message does not stop the
  queue.
- **The consumer test bypasses the key.** While `fk_saved_jobs_user` exists, a real account
  delete removes the rows whether or not the consumer works, so `AccountDeletionIT` cannot show
  the consumer works. The test publishes `user.deleted` for a user who still exists in identity,
  and expects that user's saved jobs to go and another user's to stay.
- **Day 39's hand-off, decided here:** the existence call `GET /internal/users/{id}` stays. A
  record fed by `user.deleted` lets a deleted user in until the event arrives; the call does not,
  and Day 24 measured it inside top-matches with no error in 160 calls. The rewrite records the
  call's measured mean beside this decision.

## Out of scope
- Moving `saved_jobs`, and the key going with it — Day 25.
- The uploads bucket's consumer — Phase 6, when the bucket exists.
- A verification script and a deletion audit record (the provisional spec had both): cut by the
  scope rule (`plan.md`, "Course correction"). The consumer tests answer "is this user erased?"
  per store, and the consumers log the user id and a row count, no email or name.
- A UI for account deletion: `DELETE /api/users/me` exists, and the frontend is not in this
  phase.

## Tracks

| Track | Owner | Work |
|---|---|---|
| A | | The `backend/applications` consumer: SQS listener, idempotent delete, the bypass test, poison message to the DLQ |
| B | | The matching-service consumer: evict the profile cache entry; the DynamoDB item test; the emulator in matching-service's own tests (Day 26 built it only in `backend/`'s `support/`) |

## Acceptance criteria (provisional: tagged and given their red-today in the rewrite)
- [ ] **hold** — `contract/AccountDeletionIT`, unedited, 3 of 3 green after every track.
- [ ] **new** — `user.deleted` published for a user who still exists removes that user's saved
      jobs within 5 s and leaves another user's. Red today: no consumer; the rows stay.
- [ ] **new** — The same event delivered three times succeeds three times, and nothing reaches
      `applications-user-deleted-dlq`.
- [ ] **new** — An unreadable body reaches the DLQ after 5 receives, and a valid event behind it
      is still processed.
- [ ] **new** — In matching-service, a cached profile is gone after `user.deleted` for its user:
      the next top-matches fetches the profile again (`ProfileCacheTest`'s counting stub).
- [ ] **hold** — No `job_match_scores` item has an attribute that names a user: a test lists the
      attribute names of a written item. Broken on purpose: a `user_id` attribute added.
- [ ] **hold** — The existence answer is still never cached, and runs before the profile cache
      (Day 24 criterion 3, `theExistenceAnswerIsNotCached`).

## Verify
```bash
cd backend && rm -rf */target/surefire-reports && ./mvnw clean verify && ./mvnw -B checkstyle:check
cd ../services/matching-service && ./mvnw clean verify && ./mvnw -B checkstyle:check
```

## Notes
- This is a legal obligation, not a feature. A consumer that fails is an incident; the DLQ alarm
  is handed to the Day 28 review (Day 26's Notes).
- Library: the consumers may use Spring Cloud AWS SQS or SDK v2 polling. Decided in the rewrite,
  the same for both services.
