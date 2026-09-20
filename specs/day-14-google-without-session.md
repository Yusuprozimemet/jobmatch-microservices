# Day 14 — Google sign-in without a session

**Phase:** 2 · **Depends on:** Day 13 · **Expected PRs:** 2

## Goal
The Google flow, including the account-linking case, works with no server-side session.

## In scope
- `OAuth2LoginSuccessHandler` issues a JWT cookie instead of calling `establishSession`.
- **`PendingGoogleLink` moves off the session.** New table `identity.pending_google_links`:
  one-time code, Google subject id, email, 10-minute expiry, claimed-at.
  The redirect carries the code; the password login that follows claims it.
- The terms-acceptance redirect (step 7 in `docs/hiearchy-backend.md`) works from a
  token claim, not a session attribute.
- All three branches keep their exact current behaviour:
  already-linked, new email, email-taken-not-linked.

## Out of scope
- Adding providers. Google only.

## Tracks

| Track | Owner | Work |
|---|---|---|
| A | | Success handler issues tokens; terms redirect from a claim |
| B | | `pending_google_links` table, one-time code, claim + expiry |

## Acceptance criteria
- [ ] Day 02's three Google-branch tests pass **unedited**.
- [ ] A pending link code works once; the second attempt is rejected.
- [ ] A code older than 10 minutes is rejected.
- [ ] Signing in with Google on a brand-new email lands on the terms screen.
- [ ] No code path reads or writes `HttpSession`.
- [ ] Expired pending links are cleaned up (a query filter is enough — no cron).

## Verify
```bash
cd backend && ./mvnw verify -Dtest='*Google*'
grep -rn "HttpSession" --include=*.java . || echo "clean"
```

## Notes
- The email-taken-not-linked branch is the one that breaks. It is the only flow that
  spans two separate requests and previously relied on the session to carry state.
