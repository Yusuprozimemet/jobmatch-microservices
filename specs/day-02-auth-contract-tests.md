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
| B | | Password reset lifecycle + `PATCH /api/auth/password` |
| C | | Google sign-in, all three branches + OIDC stub |

## Acceptance criteria
- [x] Tests assert status codes, response bodies and `Set-Cookie` attributes only.
- [x] No test imports `AuthenticationService`, `UserRepository` or any internal class.
- [x] The three Google branches from [`docs/auth.md`](../backend/docs/auth.md#4-google-sign-in)
      — already linked, email free, email taken — are covered.
- [x] `test@x.com` and `TEST@X.com` resolve to the same account.
- [x] A reused password-reset token is rejected.
- [x] Every bug found is filed as an issue and linked in *Notes* below.

## Verify
```bash
cd backend && ./mvnw verify -Dtest='Auth*IT'
```

## Notes
- These tests are the safety net for Day 13, the riskiest change in the plan.
  Assert on the contract, not the mechanism - the mechanism changes.
- **49 tests over six classes**, in `backend/src/test/java/.../contract/`. Full suite (Day 01
  and Day 02 together) is 63 tests in ~45s.
- **The `docs/hiearchy-backend.md` this spec cited does not exist.** The three Google branches
  are enumerated in [`backend/docs/auth.md` section 4](../backend/docs/auth.md#4-google-sign-in);
  the criterion above now points there.
- **`mvnw verify` was not running any `*IT` class.** Surefire's default includes stop at
  `*Test` / `*Tests`, and no Failsafe is configured, so the suite this spec asks for would
  have been green in CI by never running. Fixed by listing `**/*IT.java` in the surefire
  `<includes>` in `backend/pom.xml` — and the defaults with it, because `<includes>` replaces
  them rather than adding to them.
- **Harness bug, fixed here:** `UserBuilder.googleAccount()` wrote `oauth_provider = 'google'`
  where the application writes `'GOOGLE'` and looks up by exact match, so the fixture built a
  row no Google sign-in could match. Day 01's own tests never exercised it.
- **`ApiClient` no longer follows redirects.** The Google flow's contract *is* the 302
  `Location`; following it would send the test at a frontend that is not running.
- The OIDC stub (`support/StubOidcProvider`) is a real signing provider — RS256 ID tokens plus
  a JWKS endpoint — not a mocked bean, so signature, issuer, audience, expiry and nonce are
  all still validated by the production decoder. It is wired in with a `@Primary`
  `ClientRegistrationRepository`, which leaves `GoogleOAuth2Config`'s verified-email check in
  place as the thing under test.
- Two flows are unreachable over HTTP and are deliberately not covered: `PATCH
  /api/auth/password` on a Google-only account (it cannot authenticate in the first place) and
  the `linkProvider` race where a second identity tries to displace one already attached.
- One production wart found, no issue filed yet: `POST /api/auth/register` echoes back the
  **un-normalised** email (`Alan@Example.Test`) while storing and later reporting the lowercase
  one. Cosmetic, and pinned by `registeringWithCapitalsCreatesAnAccountThatLogsInLowercase`.
  Nothing else was found — every assertion above passed against the monolith unchanged.
