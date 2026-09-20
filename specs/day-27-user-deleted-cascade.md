# Day 27 — GDPR delete across four stores

**Phase:** 5 · **Depends on:** Day 26 · **Expected PRs:** 3
**Status:** provisional — re-read and revise before starting.

## Goal
Deleting a user removes their data everywhere. This replaces a single
`ON DELETE CASCADE` and is the hardest correctness problem in the split.

## In scope
- `user.deleted` consumers:

  | Service | Removes |
  |---|---|
  | `application-service` | all `saved_jobs` rows for the user |
  | `matching-service` | nothing — scores are keyed by skills hash, not user. **Verify and document this**, it is the privacy argument in `V10` |
  | `identity` | user, profile, reset tokens, pending links, refresh tokens |
  | uploads bucket | the user's files — Day 29 adds this consumer |

- Consumers are idempotent: deleting an already-deleted user is a success, not an error.
- A verification endpoint or script that answers "is user X fully erased?" across stores.
- Deletion is logged as an audit record that itself holds no personal data.

## Out of scope
- A UI for account deletion, unless one already exists.
- Bucket cleanup — Day 29, when the bucket exists.

## Tracks

| Track | Owner | Work |
|---|---|---|
| A | | `application-service` consumer + idempotency |
| B | | `identity` local cascade + audit record |
| C | | Verification script + the matching-service privacy check |

## Acceptance criteria
- [ ] After deletion, no store returns a row for that userId.
- [ ] Delivering `user.deleted` three times succeeds three times.
- [ ] The verification script reports "fully erased" and is run in a test.
- [ ] `matching-service` is proven to hold nothing user-identifying, in a test.
- [ ] With `application-service` down, the delete is retried and eventually completes.
- [ ] The audit record contains no email and no name.

## Verify
```bash
# create a user, save jobs, request matches, then delete and verify
./scripts/verify-erasure.sh <userId>
```

## Notes
- This is a legal obligation, not a feature. Treat a failing consumer as an incident,
  and alert on DLQ depth for this event specifically.
