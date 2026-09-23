# Day 13 — Replace session auth with JWT

**Phase:** 2 · **Depends on:** Day 12 · **Expected PRs:** 3

## Goal
The backend authenticates from a token and holds no server-side session.

**Riskiest day in the plan. Ship it alone, behind its own PR, and be ready to revert.**

## In scope
- `spring-boot-starter-oauth2-resource-server`, validating against the local JWKS.
- `SessionCreationPolicy.STATELESS`. No `JSESSIONID`, anywhere.
- Login sets the access token in a cookie: `HttpOnly; Secure; SameSite=Lax; Path=/`,
  and the refresh token in a second cookie scoped to the refresh path only.
- `POST /api/auth/refresh`: rotates the refresh token, issues a new access token.
- Logout revokes the refresh token and clears both cookies.
- `PATCH /api/auth/password` still requires authentication, revokes all refresh tokens
  for that user, and **re-issues the access cookie**. Day 02 pins that a credential
  captured before the change is no longer the one in play; the session rewrite should
  preserve that property, not drop it along with the session.
- Every authorisation rule in `SecurityConfig` keeps its exact current behaviour.

## Out of scope
- Google sign-in — Day 14. It keeps its session for one more day.
- The gateway — Day 15.

## Tracks

| Track | Owner | Work |
|---|---|---|
| A | | Resource server config, stateless policy, route rules unchanged |
| B | | Cookie handling on login and logout |
| C | | Refresh + rotation + revoke-on-password-change |

## Acceptance criteria
- [ ] No response sets `JSESSIONID`, except on the Google routes (`/api/oauth2/**`,
      `/api/login/oauth2/**`), which keep their session until Day 14 — see the note.
- [ ] No assertion in `backend/app/src/test/java/.../contract/` changes. The auth cookie's
      *name* is mechanism, not contract: it lives in `support/Cookies.AUTH`, and that one
      line is the only edit Phase 2 may make to the Day 01–04 suite.
- [ ] An expired access token plus a valid refresh token yields a new access token.
- [ ] A refresh token cannot be used twice (rotation).
- [ ] Changing the password invalidates every existing refresh token.
- [ ] Restarting the backend does not log users out.
- [ ] A tampered token returns 401, not 500.

## Verify
```bash
cd backend && ./mvnw clean verify && ./mvnw -B checkstyle:check
docker compose restart backend
# reload the browser: still logged in
```

## Notes
- If anything beyond `Cookies.AUTH` needs editing, either the tests were written against
  internals or behaviour has actually changed. Both are stop-and-investigate.
- **Why that criterion changed.** It used to read "Day 02's auth tests pass unedited",
  which contradicted the first criterion above: `JSESSIONID` was a literal in three
  contract classes, so four tests failed by construction the moment no response set that
  cookie any more. Hoisting the name to `support/Cookies.AUTH` — done in a spec-change PR
  before this day — is what makes both criteria satisfiable at once. The general rule it
  produced: a test may assert that a cookie exists, what it carries and what it reaches,
  but never its name.
- **The old access token outlives the password change.** Revoking refresh tokens caps the
  window at the access token's 15 minutes; killing it sooner needs a denylist, which is
  not in this day. Decide explicitly rather than by omission, and write the decision here.
- **The harness ignores `Path` and `Secure`.** `ApiClient`'s cookie jar keeps name and
  value only, so it will send the refresh cookie to every route and will not notice
  `Secure` over plain HTTP. The refresh cookie's path scoping therefore needs its own
  assertion on the `Set-Cookie` header, not a round-trip through the jar.
- Keep the old session code on the branch until acceptance passes. Delete it on Day 16.
- **Spec corrected on Day 09, before the work, from a read of every remaining spec.**
  - **"No response anywhere sets `JSESSIONID`" contradicted "Google keeps its session for
    one more day".** It is not only `establishSession`: Spring's `oauth2Login` keeps the
    authorization request — `state` and `nonce` — in the HTTP session by default
    (`HttpSessionOAuth2AuthorizationRequestRepository`), and `STATELESS` does not change that.
    `AuthGoogleSignInIT` follows `/api/oauth2/authorization/google` to the callback with the
    session cookie in between. So the Google routes still set a session cookie today, by
    design, and Day 14 removes it. The criterion is scoped to say so.
  - The contract path was `backend/src/test/...`; Day 06 moved it to `backend/app/src/test/...`.
