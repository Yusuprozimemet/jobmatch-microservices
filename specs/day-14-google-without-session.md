# Day 14 — Google sign-in without a session

**Phase:** 2 · **Depends on:** Day 13 · **Expected PRs:** 2

## Goal
The Google flow, including the account-linking case, works with no server-side session.

## In scope
- `OAuth2LoginSuccessHandler` issues a JWT cookie instead of calling `establishSession`.
- **`PendingGoogleLink` moves off the session.** New table `identity.pending_google_links`:
  one-time code, Google subject id, email, 10-minute expiry, claimed-at.
  The redirect carries the code; the password login that follows claims it.
- **The authorization request moves off the session too.** Spring's `oauth2Login` stores
  it — with `state` and `nonce` — through `HttpSessionOAuth2AuthorizationRequestRepository`
  unless told otherwise. Replace it with a cookie-backed `AuthorizationRequestRepository`:
  a short-lived, signed, `HttpOnly` cookie scoped to the callback path, cleared once read.
  Without this, every other change on this day leaves the flow still creating a session.
- The terms-acceptance redirect (`backend/docs/auth.md` §7) works from a token claim, not a
  session attribute.
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
- [ ] No response sets `JSESSIONID` on any route, the Google flow included — asserted on
      the `Set-Cookie` headers of every step of `AuthGoogleSignInIT`'s three branches.
- [ ] No code path reads or writes `HttpSession`.
- [ ] Expired pending links are cleaned up (a query filter is enough — no cron).

## Verify
```bash
cd backend
./mvnw clean verify -pl app -am -Dtest='AuthGoogleSignInIT' -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -B checkstyle:check
grep -rn "HttpSession" --include=*.java . || echo "clean"
```

## Notes
- The email-taken-not-linked branch is the one that breaks. It is the only flow that
  spans two separate requests and previously relied on the session to carry state.
- **Spec corrected on Day 09, before the work, from a read of every remaining spec.**
  - **The grep criterion alone would have passed with the session still there.** The OAuth2
    authorization request lives in the session through a framework class, so our code can be
    free of `HttpSession` while every Google sign-in still creates one. The behavioural
    criterion on `Set-Cookie` is what proves it, and the repository swap is now in scope.
  - `docs/hiearchy-backend.md` does not exist; Day 02 found this. The terms step is
    `backend/docs/auth.md` §7.
  - The verify command had Day 08's defect: `-Dtest` with no `-pl` runs nothing.
