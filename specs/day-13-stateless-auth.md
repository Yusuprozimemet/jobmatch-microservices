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
- `PATCH /api/auth/password` still requires authentication and now revokes all refresh
  tokens for that user.
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
- [ ] No response anywhere sets `JSESSIONID`.
- [ ] Day 02's auth tests pass **unedited** — this is the whole point of writing them
      against the contract.
- [ ] An expired access token plus a valid refresh token yields a new access token.
- [ ] A refresh token cannot be used twice (rotation).
- [ ] Changing the password invalidates every existing refresh token.
- [ ] Restarting the backend does not log users out.
- [ ] A tampered token returns 401, not 500.

## Verify
```bash
cd backend && ./mvnw verify
docker compose restart backend
# reload the browser: still logged in
```

## Notes
- If Day 02's tests need editing, either the tests were written against internals or
  behaviour has actually changed. Both are stop-and-investigate.
- Keep the old session code on the branch until acceptance passes. Delete it on Day 16.
