# Day 13 — Replace session auth with JWT

**Phase:** 2 · **Depends on:** Day 12 · **Expected PRs:** 5

## Goal
The backend authenticates from a token and holds no server-side session, and a browser stays
signed in past the access token's 15 minutes.

**Riskiest day in the plan. Ship it alone, behind its own PR, and be ready to revert.**

## In scope
- **Two cookies**, set at login, at password change and by Google sign-in:
  - `access_token`: Day 12's access token. `HttpOnly; SameSite=Lax; Path=/; Max-Age=900`, the
    token's own lifetime.
  - `refresh_token`: Day 12's refresh token. `HttpOnly; SameSite=Lax; Path=/api/auth;
    Max-Age=2592000`. Scoped to `/api/auth`, not to the refresh path alone: a browser sends a
    cookie only under its path, and logout must receive it to revoke it.
- **`spring-boot-starter-security-oauth2-resource-server`** (the Boot 4 name; the old
  `spring-boot-starter-oauth2-resource-server` is deprecated in 4.1.0). The decoder uses Day 12's
  `SigningKey` public key in process, not a `jwk-set-uri` back to the application, and checks
  `iss` = `jobmatch-identity` and `aud` = `jobmatch-api` as well as the timestamps. The token is
  read from the `access_token` cookie.
- **The principal stays the email.** A converter makes the token's `email` claim the
  authentication's name, so `PrincipalEmail`, its callers and `@AuthenticationPrincipal String
  email` in `AuthenticationController` read what they read today. A valid token for a deleted
  user still gets `SessionWithoutAUserIT`'s answers.
- **`SessionCreationPolicy.STATELESS`.** Login, password change and the Google success handler
  stop calling `establishSession` (the method stays until Day 16); logout and account deletion
  stop clearing `JSESSIONID`.
- **A stale cookie costs an anonymous route nothing.** On a route that allows anonymous access,
  an expired, tampered or otherwise invalid `access_token` is treated as no cookie. Without
  this, the resource server rejects the token before the route's permit is applied: a user whose
  access token has expired gets 401 from `GET /api/jobs`, from login, and from
  `POST /api/auth/refresh` itself.
- `POST /api/auth/refresh`: redeems the `refresh_token` cookie and revokes it in one statement
  (`UPDATE … WHERE revoked_at IS NULL RETURNING`, so two requests cannot both redeem one token),
  and sets both cookies anew. An unknown, revoked or expired refresh token gets 401 and the
  response deletes both cookies. No access token is needed.
- **Logout** revokes the refresh token it receives and deletes both cookies.
- **`DELETE /api/users/me`** deletes both cookies. The account's refresh tokens go with it
  through Day 12's `ON DELETE CASCADE`.
- **`PATCH /api/auth/password`** still requires authentication, revokes every refresh token the
  user has, and sets both cookies anew, so the caller stays signed in and nothing issued before
  the change is still in play (Day 02). **`POST /api/auth/reset-password`** revokes every refresh
  token too. `RefreshTokens` gains a revoke-all for one user.
- **The frontend refreshes once.** In `frontend/src/lib/api.ts`, `request()` and
  `getCurrentUser()` answer a 401 from any route outside `/api/auth/` by calling
  `POST /api/auth/refresh` once and retrying the call once. Concurrent 401s share one refresh:
  rotation revokes the token on first use, so a second refresh with the same cookie would sign
  the user out. If the refresh fails, the 401 stands and the page treats the user as signed out,
  as today.
- Every authorisation rule in `SecurityConfig` keeps its current behaviour, for a request with
  a valid cookie, with no cookie, and with a stale one.

## Out of scope
- **The `Secure` flag on the new cookies.** Decided by the maintainer: not on this day.
  `JSESSIONID`'s `Secure` follows `SESSION_COOKIE_SECURE` today (`application.yaml`), so a
  production that sets it loses `Secure` on its auth cookie from this day until a later day's
  spec adds it back; see Notes.
- Taking the Google flow's authorization request and pending link off the session — Day 14.
  The Google routes (`/api/oauth2/**`, `/api/login/oauth2/**`) still create a session for them.
- Revoking an access token before it expires (a denylist) — see Notes.
- The server-rendered `/users` page (`frontend/src/app/users/page.tsx`) forwards the browser's
  cookies from the Next.js server; it does not refresh. A client-side call refreshes first on
  any other page.
- The gateway — Day 15.

## Tracks

| Track | Owner | Work |
|---|---|---|
| 0 | | `hold` tests outside `contract/`: a stale auth cookie on anonymous routes; a tampered one on `/api/users/me` |
| B | | Login, password change and Google sign-in *also* set both cookies; the session untouched, `Cookies.AUTH` unchanged |
| C | | `POST /api/auth/refresh` with rotation; logout revokes; password change and reset revoke all |
| D | | The frontend's refresh-once-and-retry |
| A | | The switch: resource server, `STATELESS`, the email principal, stale cookies on anonymous routes, no more `establishSession`, account deletion's cookies, `Cookies.AUTH` flipped |

**Order: 0, B, C, D, A.** Each lands on a working `main`: B adds cookies nothing reads yet
(Day 12's auditor showed no test notices an extra cookie), C and D work while the session still
authenticates, and A switches last, so `main` never signs a browser out after 15 minutes. A alone
was tried: `STATELESS` with nothing else turned 15 of 55 auth tests red.

## Acceptance criteria
- [x] **new** — Login, password change and Google sign-in set `access_token`
      (`HttpOnly; SameSite=Lax; Path=/; Max-Age=900`) and `refresh_token`
      (`HttpOnly; SameSite=Lax; Path=/api/auth; Max-Age=2592000`), asserted on the `Set-Cookie`
      headers, not through `ApiClient`'s jar (`tokens/AuthCookiesIT`). `AuthCookiesIT` (#84). Red
      on `main`'s code, 3 of 3: `No Set-Cookie for 'access_token' in [JSESSIONID=…; Path=/;
      HttpOnly; SameSite=Lax]`; red again with the refresh cookie at `/api/auth/refresh` and with
      `HttpOnly` off.
- [x] **new** — No `Set-Cookie` names `JSESSIONID`, deletions included, on register, login,
      `/api/users/me`, password change, logout, account deletion and refresh
      (`tokens/NoSessionIT`). The Google routes are exempt until Day 14. `NoSessionIT` (#87),
      over one account's whole life. Red with `establishSession` put back in login.
- [x] **new** — An expired access cookie plus a valid refresh cookie: `POST /api/auth/refresh`
      returns 200 with both cookies anew, and the new access cookie reaches `/api/users/me`
      (`tokens/RefreshIT`). The expired token is signed by the test with the harness's key,
      more than 60 seconds past (the decoder allows 60 seconds of clock skew). `RefreshIT` (#85;
      the `/api/users/me` half in #87, once the session no longer authenticated). Red on `main`'s
      code: 404.
- [x] **new** — A refresh token used once gets 401 the second time, and that response deletes
      both cookies (`tokens/RefreshIT`). Same class (#85). Red with `rotate` ignoring
      `revoked_at`: `expected: 401 but was: 200`.
- [x] **new** — After logout, the refresh token it received no longer redeems (`redeem` is
      empty), and the logout response deletes both cookies (`tokens/LogoutRevokesIT`).
      `LogoutRevokesIT` (#85). Red on `main`'s code and with the logout handler removed.
- [x] **new** — After a password change, every refresh token the user held before it no longer
      redeems, and the caller is signed in with new ones; after a password reset, every one no
      longer redeems (`tokens/PasswordRevokesIT`). `PasswordRevokesIT` (#85). Red on `main`'s
      code and with `revokeAll` removed: both tests.
- [x] **new** — `DELETE /api/users/me` deletes both cookies (`tokens/AuthCookiesIT`).
      `AuthCookiesIT` (#87). Red with deletion clearing `JSESSIONID` only, and with it the
      contract's `AccountDeletionIT.endsTheSession` (`expected: 401 but was: 404`).
- [x] **new** — Restarting the backend does not sign a user out: the compose check in **Verify**
      prints `200` after the restart. `200`, `200` on `main` at the close and in #87; `200`, `401`
      on 02387dd (#82).
- [ ] **new** — The frontend calls `POST /api/auth/refresh` on a 401 and retries once:
      `grep -rn "api/auth/refresh" frontend/src` finds it in `api.ts`; `npm run lint` and
      `npm run build` pass; and in a browser, with `access_token` deleted by hand and the page
      reloaded, the user is still signed in and a new `access_token` is set (checked by the
      maintainer; there is no browser in CI). #86: the grep finds `api.ts:97` (0 before); build
      and lint pass (lint with `--line-ending=crlf` on a Windows checkout). A scratch script
      stubbing `fetch` showed one refresh for three concurrent 401s, no refresh for
      `/api/auth/`, and the 401 standing when refresh fails; broken on a copy, 3 refreshes and 1.
      **The browser check is not yet reported by the maintainer,** so this box stays open; the
      day is done when it is ticked.
- [x] **hold** — A stale auth cookie costs an anonymous route nothing: with an expired access
      token or garbage in `Cookies.AUTH`, `GET /api/jobs` returns 200, login with valid
      credentials 200, and register 201 (`tokens/StaleCookieIT`, Track 0). `StaleCookieIT` (#83),
      green before and after the switch. Broken on purpose in #83 (an invalid-session strategy
      answering 401: 9 of 10 red) and in #87 (the resolver passing stale tokens through: 9 of 10,
      `but was: 401`).
- [x] **hold** — A tampered auth cookie on `/api/users/me` returns 401, not 500
      (`tokens/StaleCookieIT`, Track 0). Same class. Broken on purpose in #83 and #87 (a stale
      token that throws: `expected: 401 but was: 500`).
- [x] **hold** — Day 02's 49 auth tests, and all 190 tests in the 23 `contract/` classes, pass,
      and nothing in `contract/` changes: the last command in **Verify** prints nothing. In the
      Day 01–04 suite only `support/` changes: `Cookies.AUTH` becomes `"access_token"`, with its
      Javadoc. At the close: 275 tests green, the command prints nothing, `Cookies.AUTH` changed in
      #87. **One departure:** `support/` also gained `ApiClient.withCookie` (#83),
      `GoogleSignIn` (#84) and `TestTokens` (#85), used only by the tests in `tokens/`; see Notes.
      Broken on purpose in #87 without the email-principal converter: 81 of 190 contract tests red.

## Verify
```bash
# The whole suite. Read the reports, not the exit code.
cd backend
rm -rf */target/surefire-reports
./mvnw clean verify
./mvnw -B checkstyle:check

# Restart keeps a user signed in, in a compose project of its own (never `down -v` on your usual
# one). Prints 200 twice once the day is done; today the second is 401.
cd ..
docker compose -p day13 --env-file .env.example up -d --build db backend jwt-key
until curl -sf -o /dev/null localhost:8080/.well-known/jwks.json; do sleep 3; done
J=$(mktemp)
curl -s -o /dev/null -X POST localhost:8080/api/auth/register -H 'Content-Type: application/json' \
  -d '{"email":"day13@example.com","password":"Password1!","name":"Day 13","acceptedTerms":true}'
curl -s -o /dev/null -c "$J" -X POST localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"day13@example.com","password":"Password1!"}'
curl -s -o /dev/null -w '%{http_code}\n' -b "$J" localhost:8080/api/users/me
docker compose -p day13 restart backend
until curl -sf -o /dev/null localhost:8080/.well-known/jwks.json; do sleep 3; done
curl -s -o /dev/null -w '%{http_code}\n' -b "$J" localhost:8080/api/users/me
docker compose -p day13 down -v

# The frontend.
cd frontend && npm ci && npm run lint && npm run build && cd ..
grep -rn "api/auth/refresh" frontend/src

# The hold: prints nothing. BASE = 02387dd, the main commit Day 13 started from.
git diff --stat "$BASE" HEAD -- backend/app/src/test/java/nl/hackyourfuture/project/backend/contract
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
- **An access token outlives a password change, a logout and an account deletion** by up to
  its 15 minutes. Revoking refresh tokens caps the window there; closing it sooner needs a
  denylist, which this day does not build. An access token for a deleted account gets
  `SessionWithoutAUserIT`'s answers until it expires.
- **The harness ignores `Path` and `Secure`.** `ApiClient`'s cookie jar keeps name and
  value only, so it sends the refresh cookie to every route. The cookie attributes are therefore
  asserted on the `Set-Cookie` headers, not through a round trip in the jar.
- **The Google routes keep their session for the authorization request** until Day 14, but the
  success handler moves to the token cookies on this day: under `STATELESS`, Spring no longer
  reads the security context `establishSession` writes into the session, so three
  `AuthGoogleSignInIT` tests got 401 on `/api/users/me` after sign-in. Issuing the cookies from
  the success handler made all eight pass unedited. The pending link still lives in the session
  and is claimed at the password login that follows; Day 14 moves it.
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
- **Spec corrected on Day 13, before the work.** The spec-auditor, in a fresh context, ran every
  check on `main` at 02387dd and built a minimal version of this day in a scratch copy (about 54
  lines of main code, then deleted). All 190 contract tests passed with only `Cookies.AUTH`
  changed on the test side, but only after three changes the spec did not ask for, and with a
  regression no test noticed:
  - **The principal would have been a `Jwt`.** `PrincipalEmail` expects the email: 89 of 257
    tests red. Decided by the maintainer: a converter makes the `email` claim the principal.
  - **"Google keeps its session for one more day" could not hold** under `STATELESS` (above).
    Decided by the maintainer: the success handler's token cookies move from Day 14 to this day.
  - **Account deletion cleared only `JSESSIONID`** (`UserController`), so
    `AccountDeletionIT.endsTheSession` got 404 instead of 401.
  - **A refresh cookie scoped to the refresh path never reaches logout,** and logout cannot fall
    back on the access token: `LogoutFilter` runs before the bearer-token filter. Now
    `Path=/api/auth`, with a criterion that logout revokes.
  - **An expired or tampered access cookie turned anonymous routes into 401s,** including
    login, register and the refresh endpoint, and all 190 contract tests stayed green. Now in
    scope, with a Track 0 `hold`.
  - **The frontend never refreshed** (`frontend/src/lib/api.ts` treats a 401 as signed out), so
    every browser would have been signed out 15 minutes after login, where today the session
    lasts until 30 minutes idle. Decided by the maintainer: a frontend track on this day.
  - **Password change revoked the refresh tokens but re-issued only the access cookie,** which
    would sign the caller out 15 minutes later; password reset was not mentioned. Decided by the
    maintainer: reset revokes all too.
  - **`Secure`** was in the cookie line but configurable today and unchecked by any criterion.
    Decided by the maintainer: out of scope for this day.
  - No criterion was tagged `new` or `hold`; four in-scope items had no criterion (logout's
    revoke, the refresh cookie's path, account deletion, stale cookies); the tampered-token
    criterion named no route; the "only edit Phase 2 may make" claim was false after Day 12's two
    harness edits; the restart check was manual, ran against the maintainer's own project and
    passed only within 15 minutes; the starter's name is deprecated in Boot 4.1.0.
  - **The tracks could not run in parallel:** Track A alone turned 15 of 55 auth tests red, and
    refresh needs the refresh cookie. *Estimate 3 → 5*, for Track 0 and the frontend track.
- **Deployment changes.** From Track A on, every signed-in user is signed out once, at the
  deploy: their `JSESSIONID` no longer authenticates. Production that sets `SESSION_COOKIE_SECURE`
  loses `Secure` on the auth cookie until a later day adds it; Day 16's cutover is the last point
  it can.
- **Done on Day 13.** Spec change #82, then #83 (Track 0, stale-cookie holds), #84 (Track B, the
  cookies), #85 (Track C, refresh, rotation and revocation), #86 (Track D, the frontend) and #87
  (Track A, the switch). 275 tests green, checkstyle clean; all 190 contract tests pass and
  `contract/` did not change. *Estimated 3 pull requests as first written, 5 after the spec
  change, took 5.*
- **The plan's central test held.** The session-auth rewrite passed Day 02's 49 auth tests and
  the rest of `contract/` unedited, with `Cookies.AUTH` the only line the contract suite reads
  that changed. It held because of three things the spec as first written did not ask for,
  found by the spec-auditor's scratch build before the work: the email principal (81 contract
  tests red without it, #87), Google's cookies moving here from Day 14, and account deletion's
  cookies.
- **The tracks ran 0, B, C, D, A,** so each landed on a working `main` and the switch came
  last. Reverting #87 alone restores session auth.
- **The departure from "only `Cookies.AUTH`" in `support/`.** Three additions, each for tests
  outside `contract/`: `ApiClient.withCookie` (a cookie no server set), `GoogleSignIn` (a copy
  of `AuthGoogleSignInIT`'s private stub flow, which that test keeps) and `TestTokens` (an
  expired token signed with the application's key). CLAUDE.md allows `support/` changes; the
  criterion's wording was narrower than the rule, and nothing in `contract/` uses them.
- **Mistakes of mine, recorded in their PRs:**
  - #82: the restart script's first version sent `acceptTerms`, not `acceptedTerms`; caught
    before it ran.
  - #85: a break made with `sed` also hit Day 12's `redeem`; rerun on `rotate` alone.
  - #87: a checkstyle line-length violation, hidden because my command piped checkstyle into
    `tail` and printed `tail`'s exit code. The same pipe was in #84's and #85's runs, which
    printed nothing and so passed; CI ran checkstyle on all three.
- **`npm run lint` fails on a Windows checkout** (57 formatter errors on this branch, 58 on
  `main` before #86), all of them CRLF line endings from `core.autocrlf`. CI checks out LF.
  `npx biome check --line-ending=crlf` is clean.
- **Not done here:** `backend/docs/auth.md` still describes session auth; a docs PR rewrites it,
  by the maintainer's decision. `Secure` is still off the new cookies (Out of scope).
