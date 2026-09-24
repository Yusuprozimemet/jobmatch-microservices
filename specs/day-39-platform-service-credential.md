# Day 39 — A service proves who it is, and a deleted user stays deleted

**Phase:** 3 · **Depends on:** Day 38 · **Expected PRs:** 5

The second of the platform step's three days (38–40), before Phase 3's extractions. Written in
full on Day 39 from the outline in #118, against `main` at 760e119, and corrected from the
spec-auditor's read before any track.

## Goal
A call to `/internal/**` is accepted only with a short-lived service token from a trusted issuer,
the monolith can mint and publish its own, and identity answers whether a user still exists, so
a service that trusts a token's `sub` can refuse a deleted user.

## In scope
- **The monolith's service credential.** Own key, own JWKS, as the maintainer chose after
  Phase 2: each service signs its tokens with a key of its own and publishes the public half.
  - A second RSA key, `SERVICE_JWT_PRIVATE_KEY_FILE`, loaded with `SigningKey`'s parsing and
    checks, not a copy of them: never generated at startup, and the application does not start
    without it. Not identity's user key: the two have different jobs and rotate apart.
  - It is documented wherever `JWT_PRIVATE_KEY_FILE` is: `configuration.md` (the variable, the
    "what degrades" table, the production checklist), `auth.md`, `backend/README.md` and
    `backend/.env.example`.
  - `scripts/jwt-key.sh` keeps taking one path; compose's `jwt-key` service runs it twice, for
    `private.pem` and `service.pem` in the `jwt-keys` volume. The test harness makes its own
    (`support/`, as `TestSigningKey` does, passed by `IntegrationTest`).
  - A minter, `iss=jobmatch-backend`, `aud=jobmatch-internal`, RS256, at most 5 minutes. Its
    class is in `app`'s `..backend.config..` package, the one `nobodyDependsOnTheAssembly`
    covers, and other modules reach it through an interface in `shared`, so Day 19's clients
    attach it without depending on `app`.
  - The public half at `GET /.well-known/service-jwks.json`, unauthenticated, as
    `/.well-known/jwks.json` is for user tokens.
- **`/internal/**` takes service tokens and nothing else.**
  - A chain ordered after the actuator chain (`@Order(1)`) and before the application chain
    (which becomes 3), matching `/internal/**`. Stateless, with CSRF off, as a bearer-only chain
    is: Day 18's `POST` routes would otherwise get 403 before authentication.
  - The token is in `Authorization: Bearer`, never a cookie. Its `iss` picks the issuer's key
    set from a trusted-issuer list, and it must carry `aud=jobmatch-internal`. The list comes
    from `JwtIssuerAuthenticationManagerResolver`'s constructor that takes an issuer →
    `AuthenticationManager` resolver, not `fromTrustedIssuers` (that one does OIDC discovery on
    the issuer string).
  - The monolith trusts its own issuer in process, with the service key's public half, as
    `AccessTokenAuthentication` trusts user tokens. It is its own first caller: Day 19's clients
    call its `/internal/postings/*` with its own token before Day 17 moves them. Other issuers
    come from configuration, empty by default; Day 17 adds job-service's.
  - A user's `access_token` cookie is ignored there, and a user token in the header is refused
    (its key, `iss` and `aud` are identity's and the API's).
  - The gateway routes neither `/internal/**` nor `/.well-known/service-jwks.json`; it routes
    `/api/**` and `/.well-known/jwks.json` only (`Routes.java`), and that must hold.
  - The public OpenAPI (`/api/docs/openapi.yaml`, which the gateway routes) lists no internal
    path: springdoc excludes `/internal/**` and the service key set.
- **The deleted-user rule.** An access token outlives its user by up to 15 minutes
  (`backend/docs/auth.md:379-380`). The monolith refuses that user because
  `CurrentUserIdResolver` looks the user up by the token's email on every request
  (`contract/SessionWithoutAUserIT`: 404 on saved jobs, 422 on matches). A service that trusts
  `sub` has no `users` table to look in.
  - **It asks identity:** `GET /internal/users/{id}` answers 204 while the user exists and 404
    once deleted, behind the `/internal/**` chain. No cache: an answer cached for N seconds lets a
    deleted user in for N seconds, and Phase 4 decides that with a measurement, not now.
  - Not a record of deleted ids fed by `user.deleted`: the message bus is Phase 5's (Day 26), so
    nothing could feed it yet. Days 26–27 may replace the call with it.
  - `backend/docs/auth.md` states the rule for every service that trusts `sub`.
- Two Javadocs still expect Day 13 to have put the id in the principal; it kept the email.
  `queries/CurrentUserQueriesIT` ("expires on Day 13") stays at one statement: that lookup is
  the deleted-user check, so it does not expire. `shared/web/CurrentUserId.java:18-19` says the
  same. Both Javadocs say so; no count changes.

## Out of scope
- The internal endpoints for postings and saved counts: Day 18. The clients that call them, and
  attach the token: Day 19.
- Job-service's own key and the monolith's trust of it: Day 17, when it exists.
- Key rotation beyond one key per service; caching the existence answer (Phase 4).
- Whether extracted services read the user from the token or from the gateway's `X-User-Id`:
  Day 21, the first extraction that needs a user.

## Tracks

| Track | Owner | Work |
|---|---|---|
| 0 | | Holds first: through the gateway, `/internal/users/x` and `/.well-known/service-jwks.json` answer 404 with a valid user token and 401 without, and the upstream receives nothing |
| A1 | | The service key: loading through `SigningKey`'s checks, compose's second `jwt-key.sh` run, the harness key, the docs and `.env.example` |
| A2 | | The minter and its `shared` interface, the service key set endpoint |
| B | | The `/internal/**` chain: in-process trust of the monolith's issuer, the configured issuer list, a test caller in `support/` with a key set of its own |
| C | | `GET /internal/users/{id}`, the OpenAPI exclusion, the rule in `auth.md`, the two Javadocs |

Track 0 lands first, then A1, A2, B and C in that order: each needs the one before.

## Acceptance criteria
- [ ] **new** — The monolith publishes a service key set: `GET /.well-known/service-jwks.json`
      without a token answers 200 with one RSA key whose `kid` is the RFC 7638 thumbprint, and a
      token from the minter verifies against it with `iss=jobmatch-backend`,
      `aud=jobmatch-internal` and `exp - iat` ≤ 300 s. The application does not start without
      `SERVICE_JWT_PRIVATE_KEY_FILE`, and says which variable to set. Red today: the path
      answers 401 without a token and 404 with a user cookie; the variable is read by nothing.
- [ ] **new** — `/internal/**` lets a trusted service token through and nothing else. On an
      unmapped `/internal/**` path (Track B has no route yet):
      - a valid token from the monolith's minter, and one from a test caller named in the
        configured list, pass security: 404, not 401;
      - a user's valid `access_token` cookie gets 401.

      Red today: a valid user cookie reaches the application chain and gets 404; there is no
      service token to pass. The refusals that already hold today are the next criterion.
- [ ] **hold** — `/internal/**` refuses every other token, each 401, on the same unmapped path as
      the criterion above:
      - no token;
      - a user token in the header;
      - the caller's token signed by another key;
      - the caller's token expired more than 60 s ago (past the default clock skew);
      - the caller's token for another audience;
      - a token signed by the test caller's key with an `iss` not on the list.

      Each passes today, refused by the application chain. Broken on purpose in Track B's PR, one
      guard at a time, each turning its case red alone:
      - `permitAll()` in place of `authenticated()` (no token: 404);
      - the `aud` validator removed (another audience);
      - the timestamp validator removed (expired);
      - the resolver returning the test caller's manager for any issuer, with no issuer validator
        on its decoder (unknown issuer).

      A user token and another key are refused by the key set itself, which no setting turns off.
- [ ] **new** — Identity answers whether a user exists: with a trusted caller's token,
      `GET /internal/users/{id}` answers 204 for a user, 404 once the `users` row is deleted, and
      404 for an id never seen; without a token, or with the user's own cookie, 401. And
      `/api/docs/openapi.yaml` lists neither `/internal/users/{id}` nor
      `/.well-known/service-jwks.json`. Red today: the route does not exist (401 without a cookie,
      404 with one). The OpenAPI clause passes today because there is nothing to list; it is seen
      red in Track C's PR with `springdoc.paths-to-exclude` removed.
- [ ] **hold** — The gateway routes nothing under `/internal/**` and not the service key set:
      with a valid user token, `GET /internal/users/<id>` and
      `GET /.well-known/service-jwks.json` through the gateway answer 404 and the upstream
      receives nothing; without one, 401 (Track 0). Green today. Broken on purpose in Track 0's
      PR: a route for `/internal/**` added to `Routes.java`.
- [ ] **hold** — A deleted user is still refused by the monolith: `contract/SessionWithoutAUserIT`
      and `queries/CurrentUserQueriesIT` (one statement per request) pass, `contract/` unedited.
      Broken on purpose (the spec-auditor, repeated in Track C's PR): `CurrentUserIdResolver`
      reading `sub` from the cookie instead of the lookup. `savedJobsSayTheUserIsNotFound` went
      red (`expected: 404 but was: 200`), 1 of 3, and `CurrentUserQueriesIT` 2 of 2
      (`expected: 1L but was: 0L`).

## Verify
```bash
# Backend, direct and through the gateway, and the gateway. Read the reports.
cd backend && rm -rf */target/surefire-reports && ./mvnw clean verify && ./mvnw -B checkstyle:check
cd .. && backend/mvnw -B -f services/api-gateway/pom.xml clean verify checkstyle:check
docker build -t jobmatch-api-gateway:harness services/api-gateway
cd backend && ./mvnw -B verify -pl app -am -Dharness.gateway=true \
  -Dtest='nl.hackyourfuture.project.backend.contract.*IT,StaleCookieIT,RefreshIT,GatewayHarnessIT' \
  -Dsurefire.failIfNoSpecifiedTests=false && cd ..

# Compose, in a project of its own. Never `down -v` on the maintainer's; stop theirs first,
# the network name (finalproject) is pinned.
docker compose -p day39check --env-file .env.example up -d --build --wait db backend api-gateway
# Inside the network, retried until the backend answers: 401 before Track A2, then 200 and one
# RSA key.
for i in $(seq 30); do
  docker run --rm --network finalproject curlimages/curl -s -w '\n%{http_code}\n' \
    http://backend:8080/.well-known/service-jwks.json && break; sleep 2; done
# From the host, logged in through the gateway: 404 for both, since neither is routed.
curl -s -o /dev/null localhost:8080/api/auth/register -H 'Content-Type: application/json' \
  -d '{"email":"d39@example.test","password":"Password-39!","name":"Day 39","acceptedTerms":true}'
curl -s -c jar -o /dev/null localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"d39@example.test","password":"Password-39!"}'
curl -s -b jar -o /dev/null -w '%{http_code}\n' localhost:8080/.well-known/service-jwks.json
curl -s -b jar -o /dev/null -w '%{http_code}\n' localhost:8080/internal/users/00000000-0000-0000-0000-000000000000
docker compose -p day39check --env-file .env.example down -v; rm -f jar
```

## Notes
- Outlined in #118 from `plan.md`'s course correction; written in full on Day 39 with the
  maintainer's choice of mechanism for the deleted-user rule: ask identity now, move to a record
  of deleted ids once the bus exists (Days 26–27).
- `aud=jobmatch-internal`, not the outline's `internal`: named as the user token's
  `jobmatch-api` is.
- **Deployment:** production needs `SERVICE_JWT_PRIVATE_KEY_FILE` and its file before the deploy
  that carries Track A1, or the backend will not start, as Day 12 recorded for the user key.
- **Corrected on Day 39, before the work, from the spec-auditor's read** of this spec on
  760e119. It tried what reading could not:
  - **A criterion that could not fail.** "A service token is refused on `/api/**`" passed before
    any change, and its named break left it green. The application chain refuses such a token
    four ways: the signature (another key), `iss`, `aud`, and a principal with no `email`. So it
    is not a criterion; this line records it.
  - **A `hold` whose break did not compile.** A feature module cannot import the minter in `app`:
    Maven stops it (`jobs --> app --> jobs`, a cyclic reference) before `ModuleBoundariesTest`
    runs. The reactor is the guard, and the minter is pinned to `..backend.config..`, the
    package the ArchUnit rule covers.
  - **The Verify's curls checked nothing.** `--wait` returns before the backend answers, so the
    in-network curl printed nothing. The host curl got 401, which every path that needs a login
    gets, routed or not. Now the in-network curl retries and prints its status, and the host
    check logs in first: 404 tells "not routed" apart.
  - **Track B had no route to answer 2xx.** Its check is "404, not 401" on an unmapped path; the
    2xx is Track C's route.
  - **The deleted-user break was not reachable as worded.** The principal is the email, and the
    resolver has no `sub`. The break reads it from the cookie; which tests went red is recorded
    in the criterion.
  - **Track A would not fit the gate.** Day 12 did the same work in #75 (337 lines) and #76 (238).
    It is split in two, and the key reuses `SigningKey`'s checks.
  - **The first caller is the monolith itself** (Days 18 → 19 → 17), and a trust by URL cannot
    know a test's random port, so its own issuer is trusted in process.
  - Also taken: every place `JWT_PRIVATE_KEY_FILE` is documented; CSRF off on the chain for
    Day 18's `POST`s; OpenAPI excluding internal paths, since the gateway routes it; the second
    stale Javadoc; a break for each refusal the chain adds, and the test token that makes the
    unknown-issuer break visible. A second read of the corrected spec ran the new Verify
    (`401`, then `404` twice when logged in) and found the Notes promising hand-off lines that
    had not been written.
  - For Day 17: a map keyed by issuer names with hyphens cannot be set from environment
    variables, so job-service's entry needs another form in compose.
- **Hand-offs this day creates**, each also noted in that day's spec:
  - Day 17: job-service gets a key of its own and publishes its key set; the monolith adds it to
    its trusted issuers.
  - Day 18: its Track C (identity-issued service tokens on Day 12's key set) is replaced by this
    day.
  - Day 19: every internal client attaches the token through the `shared` interface.
  - Days 21 and 25: a service that trusts `sub` calls `GET /internal/users/{id}` before acting
    for a user, as `SessionWithoutAUserIT` requires of the monolith.
  - Day 24: service-token auth is this day's, not Day 18's.
  - Days 26–27: whether the call gives way to a record fed by `user.deleted`.
  - Day 28: the monolith's remainder becomes identity-service and takes both keys; the service
    issuer's name is decided there.
  - Days 30 and 35: a function calling back with a service token needs a key and key set of its
    own under this design.
- Day 38's findings for Days 37 and 40 are in those days' Notes.
