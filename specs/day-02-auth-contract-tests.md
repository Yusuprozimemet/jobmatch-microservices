# Day 02 — Auth contract tests

**Phase:** 0 · **Depends on:** Day 01 · **Expected PRs:** 3

## Goal
Every auth behaviour is pinned by a test that asserts HTTP status, body and cookies —
never internal classes — so the tests survive the JWT rewrite in Phase 2 unchanged.

## In scope
- Register: success, duplicate email, weak password, terms not accepted.
- Login: success, wrong password, unknown email, case-insensitive email (see `V9`).
- Logout: clears the cookie, returns JSON.
- Password reset: request, redeem valid token, reject expired/reused token.
- `PATCH /api/auth/password` requires authentication.
- Google sign-in paths, with the OIDC provider stubbed:
  already-linked, new email, email-taken-not-linked (the `PendingGoogleLink` flow).

## Out of scope
- Profile, jobs, saved jobs, matching — Days 3–4.
- Any change to production auth code. If a test reveals a bug, file it; do not fix it here.

## Tracks

| Track | Owner | Work |
|---|---|---|
| A | | Register + login + logout |
| B | | Password reset lifecycle |
| C | | Google sign-in, all three branches + OIDC stub |

## Acceptance criteria
- [ ] Tests assert status codes, response bodies and `Set-Cookie` attributes only.
- [ ] No test imports `AuthenticationService`, `UserRepository` or any internal class.
- [ ] The three Google branches from `docs/hiearchy-backend.md` steps 3, 4 and 5 are covered.
- [ ] `test@x.com` and `TEST@X.com` resolve to the same account.
- [ ] A reused password-reset token is rejected.
- [ ] Every bug found is filed as an issue and linked in *Notes* below.

## Verify
```bash
cd backend && ./mvnw verify -Dtest='Auth*IT'
```

## Notes
- These tests are the safety net for Day 13, the riskiest change in the plan.
  Assert on the contract, not the mechanism — the mechanism changes.
