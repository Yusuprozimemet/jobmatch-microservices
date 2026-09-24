# Day 15 — The API gateway

**Phase:** 2 · **Depends on:** Day 14 · **Expected PRs:** 5

## Goal
A Spring Cloud Gateway service fronts the backend, validates tokens and routes by path.

## In scope
- New `services/api-gateway`: own pom, own Dockerfile, own CI workflow (path filter
  `services/api-gateway/**`) with checkstyle. It publishes **8081** until Day 16 moves it to
  8080; the backend keeps 8080 until then.
  - **The WebMVC gateway** (`spring-cloud-starter-gateway-server-webmvc`), not WebFlux: servlet
    Spring Security as in the backend, and an in-memory Bucket4j filter built in. Decided by the
    maintainer.
  - **Spring Cloud 2025.1.3 on Spring Boot 4.0.x**, the newest GA pair; no GA Spring Cloud
    accepts the backend's Boot 4.1.0 yet (2026.0.0 is at M1). The gateway's pom is its own, so
    the two versions do not have to match.
  - **No copied Maven wrapper** (484 lines): built with `backend/mvnw -f
    services/api-gateway/pom.xml`, or with CI's and the Docker image's own Maven if the wrapper
    does not work from outside `backend/`. Track A says which.
- Everything routes to the backend (`BACKEND_URL`, `http://backend:8080` in compose). The access
  rules match today's `SecurityConfig` (`SecurityConfig.java:75-85`), in this order:

  | Path | Auth |
  |---|---|
  | `PATCH /api/auth/password` | required |
  | `/api/auth/**` | public |
  | `/api/docs/**` | public |
  | `/api/oauth2/**`, `/api/login/oauth2/**` | public |
  | `GET /.well-known/jwks.json` | public |
  | `GET /api/jobs/top-matches` | required |
  | `GET /api/jobs`, `/api/jobs/filters`, `/api/jobs/*` | public |
  | anything else, `/actuator/**` included | required: 401 with no token, as the backend answers today |

  `/actuator/**` is never routed: the backend's management port stays unpublished.
- **The token is the `access_token` cookie**, not an `Authorization` header
  (`AuthCookies.java`, `AccessTokenAuthentication.cookieResolver()`). The gateway verifies it
  against `${BACKEND_URL}/.well-known/jwks.json`, cached, and checks `iss` = `jobmatch-identity`
  and `aud` = `jobmatch-api` as the backend does. **A cookie that does not verify counts as no
  cookie**: public routes, login and refresh still work with a stale one, as `tokens/StaleCookieIT`
  and `tokens/RefreshIT` pin on the backend. No session, CSRF off, and 401 with no redirect.
- **`X-User-Id`:** stripped from every inbound request; for a verified token, set to its `sub`
  (the user id). The backend reads no such header today; Phase 3's services will.
- **The backend keeps validating tokens itself.** The gateway fails fast; the backend never
  trusts the network, and a request carrying only `X-User-Id` is not a login there.
- **CORS: the gateway allows no cross-origin requests.** The browser is same-origin through the
  Next.js proxy, and the backend has no CORS configuration to move (`backend/docs/api.md`).
  Decided by the maintainer.
- **Rate limit, in memory, on the credential routes:** `POST /api/auth/login`, `/register`,
  `/forgot-password` and `/reset-password`; not refresh, logout or the password change. 10 a
  minute per client, the 11th answered `429` without reaching the backend. The client is the
  connecting address, or, when the connection comes from a trusted proxy
  (`GATEWAY_TRUSTED_PROXIES`, the frontend from Day 16), the address that proxy put in
  `X-Forwarded-For`; a client's own `X-Forwarded-For` is ignored. The limit is configurable
  (`RATE_LIMIT_AUTH_PER_MINUTE`) and the test harness sets it high. Decided by the maintainer. In
  memory is correct for one gateway replica; more than one, from Phase 7, needs a shared store.
- **Tracing, and the correlation id is the trace id.** Micrometer tracing with
  `spring-boot-starter-opentelemetry`, exporting OTLP behind `TRACING_EXPORT_ENABLED` as the
  backend does since Day 05; **not the Java agent**, which Day 05 removed. The gateway starts or
  continues a W3C `traceparent`, forwards it, and logs the trace id on each request; the backend
  already logs it. No separate header. Decided by the maintainer.
- **The Day 1–4 suite through the gateway, changing `support/` only.** A switch
  (`-Dharness.gateway=true`) makes `ApiClient` target a gateway container that Testcontainers
  starts from the image, routed at the test's own application through
  `host.testcontainers.internal`, once per application context. That keeps the in-JVM stubs
  (`StubOidcProvider`, `StubLlm`) and the JDBC fixtures working unchanged. The run is **in
  addition to** the direct run, never instead: the direct run is what proves the backend still
  validates. Backend CI builds the gateway image first, and its path filter gains
  `services/api-gateway/**`.

## Out of scope
- Routing to separate services — there is still only one backend. Phase 3 changes targets,
  not routes.
- Moving the frontend, Google's redirect URI or the backend's port to the gateway: Day 16. Until
  then a Google sign-in started on 8081 comes back through 3000 to the backend directly; the
  harness is what runs the flow through the gateway.

## Tracks

| Track | Owner | Work |
|---|---|---|
| 0 | | `hold` test on the backend, direct: `X-User-Id` alone is not a login |
| A | | Gateway skeleton: pom, Dockerfile, CI with checkstyle, compose service on 8081, routes to the backend |
| B | | Security: the cookie token, JWKS, `iss`/`aud`, stale as absent, the rules table, `X-User-Id` strip and forward; gateway tests with a recording upstream |
| D | | The suite through the gateway: the `support/` switch, the image in backend CI |
| C | | Rate limit, CORS, tracing |

**Order: 0, A, B, D, C.** Each needs the one before it: B secures what A routes, and D runs the
suite through B's rules before any limit exists. C adds the limit, configurable, and sets it high
in D's harness in the same PR: the suite logs in from one address far more than ten times a
minute (92 `authenticatedAs` call sites in `contract/`). Until B lands, `main`'s gateway checks
nothing; the backend still does. *Estimate 5; 6 if B splits.*

## Acceptance criteria
- [x] **hold** — The backend keeps validating tokens itself. Direct to the backend, never
      through the gateway: a tampered `access_token` cookie on `/api/users/me` answers 401
      (`tokens/StaleCookieIT`), and a request carrying only `X-User-Id: <a real user's id>` on
      `/api/users/me` and `/api/profile` answers 401 (`tokens/UserIdHeaderIT`, Track 0). True
      today: nothing reads the header. The first half broken in the spec-auditor's scratch copy:
      with the decoder accepting tokens that fail verification,
      `StaleCookieIT.aTamperedCookieIsRefusedNotAFailure` went red (`expected: 401 but was:
      404`), the only one of 295. The second broken on purpose in Track 0's PR with a filter that
      authenticates from `X-User-Id`.
      `tokens/UserIdHeaderIT` (#97): red with that filter, both cases `expected: 401 but was: 200`.
      It uses `direct()` from #100. Both pass in the direct run on `main` at 855726b. Why direct: through a
      gateway built to hand stale cookies on unverified, `aTamperedCookieIsRefusedNotAFailure`
      stayed green while 9 of the class's 10 went red (#100).
- [x] **new** — A client-supplied `X-User-Id` never reaches the backend. Against a recording
      upstream: a valid cookie for user A plus `X-User-Id: <B's id>` arrives once, with A's id
      alone; a public request with `X-User-Id` arrives with none (a gateway test). Red today:
      there is no gateway. Red again with the strip removed.
      `SecurityTest.aClientsUserIdIsReplacedByTheTokens` and `…OnAPublicRouteIsDropped` (#99),
      lower-case header included. Red with the strip removed: the upstream got the token's id and
      the client's.
- [x] **new** — The gateway answers private routes itself. Against a recording upstream, every
      `required` row of the table with no cookie, and with a cookie that is expired, signed by
      another key or for another audience, gets 401 and the upstream records no request; every
      public row, with no cookie and with a stale one, reaches the upstream (a gateway test). Red
      today: there is no gateway. Red again with every rule `permitAll`: the backend's own 401
      would satisfy a check made through the whole stack, which is why this one has no backend.
      `SecurityTest` (#99), with two more stale kinds (another issuer, garbage). Red with every
      rule `permitAll` (`[PATCH /api/auth/password] expected: 401 but was: 200`), with
      `/api/auth/**` ahead of the password change, without the audience or issuer check, and with
      a stale cookie handed on unverified (`[POST /api/auth/login] expected: 200 but was: 401`).
- [x] **new** — The 11th login in a minute from one client is `429`. Against a recording
      upstream: ten `POST /api/auth/login` from one address reach it, the 11th gets 429 and does
      not; another address still reaches it; a client's own `X-Forwarded-For` does not change
      its bucket; twenty `POST /api/auth/refresh` are not limited (a gateway test). Red today:
      the backend answered eleven wrong-password logins with 401 in the audit. Red again with
      the limit removed.
      `RateLimitTest` and `RateLimitBehindProxyTest` (#101). Red with no limit, with
      `X-Forwarded-For` believed from anyone, with the first hop in place of the last, with
      refresh limited (`[refresh 1] expected: 200 but was: 429`), and with the header never
      believed behind the proxy. The four credential routes share a client's bucket (Notes).
- [x] **new** — One trace spans gateway and backend. Against a recording upstream, a request
      arrives with a `traceparent` whose trace id is the one the gateway logs for it (a gateway
      test); in compose with tracing exported, Tempo shows the backend's server span as the
      gateway span's child (**Verify**). Red today: there is no gateway.
      `TracingTest` (#102, made to wait for the log line in #103). Red with Boot's own no-op
      propagator (no `traceparent` sent), with the access log outside the tracing filter, and
      with no trace id in the log pattern. In compose on `main` at 855726b, with export on: the backend's
      `http get /api/docs/openapi.yaml` server span has the gateway's `http get` client span as
      parent, under the caller's `traceparent`.
- [x] **new** — A preflight from another origin gets no `Access-Control-Allow-Origin` (a gateway
      test). Red today: there is no gateway. Red again with any origin allowed.
      `CrossOriginTest` (#102): a preflight gets 403 and is not forwarded; an allow-origin set
      behind the gateway never leaves it; the frontend's own `POST` through its proxy is served.
      Red with preflights let through, and with `Access-Control-*` not stripped while the
      upstream sends `Access-Control-Allow-Origin: *`.
- [x] **new** — The Day 1–4 suite passes through the gateway: all 190 tests in the 23
      `contract/` classes, the 10 in `tokens/StaleCookieIT` and the 2 in `tokens/RefreshIT` (an
      expired access cookie refreshing), with `-Dharness.gateway=true`
      (**Verify**), and the reports show the requests went through it (a harness self-test that
      the client's base URL is the gateway container's). Red today: there is no switch. Red again
      with the gateway refusing a stale cookie on `/api/auth/refresh`.
      `support/Gateway` and `GatewayHarnessIT` (#100): 203 tests in 26 reports on `main` at 855726b, the
      self-test included. Red through a gateway handing stale cookies on unverified (both
      `RefreshIT`, `expected: 200 but was: 401`, and 9 of 10 `StaleCookieIT`), and, for the
      self-test, with the switch forced direct.
- [x] **new** — Compose runs the gateway on 8081 in front of the backend, and it answers private
      routes with the backend stopped (**Verify**). Red today: nothing listens on 8081.
      on `main` at 855726b: docs 200, `/api/profile` 401, with `X-User-Id` alone 401, and with the backend
      stopped 401 (#98, #99).

## Verify
```bash
# The gateway's own tests and checkstyle (or CI's Maven; see Track A). Read the reports.
backend/mvnw -B -f services/api-gateway/pom.xml clean verify checkstyle:check

# The backend, direct: the whole suite.
cd backend
rm -rf */target/surefire-reports
./mvnw clean verify
./mvnw -B checkstyle:check

# The Day 1-4 suite, StaleCookieIT, RefreshIT and the harness self-test through the gateway
# (Track D). The harness starts this image; Testcontainers cannot build it (BuildKit).
docker build -t jobmatch-api-gateway:harness ../services/api-gateway
rm -rf */target/surefire-reports
./mvnw -B verify -pl app -am -Dharness.gateway=true \
  -Dtest='nl.hackyourfuture.project.backend.contract.*IT,StaleCookieIT,RefreshIT,GatewayHarnessIT' \
  -Dsurefire.failIfNoSpecifiedTests=false

# Compose, in a project of its own. 8081 is the gateway; 8080 is still the backend until Day 16.
cd ..
docker compose -p day15check --env-file .env.example up -d --build
curl -s -o /dev/null -w '%{http_code}\n' localhost:8081/api/docs/openapi.yaml   # 200
curl -s -o /dev/null -w '%{http_code}\n' localhost:8081/api/profile             # 401
curl -s -o /dev/null -w '%{http_code}\n' -H 'X-User-Id: 00000000-0000-0000-0000-000000000000' \
  localhost:8081/api/profile                                                     # 401
docker compose -p day15check stop backend
curl -s -o /dev/null -w '%{http_code}\n' localhost:8081/api/profile             # 401: the gateway answered
docker compose -p day15check down -v

# Tracing: with TRACING_EXPORT_ENABLED=true and the obs profile, one request through 8081 shows
# in Grafana (3001) -> Tempo as a gateway span with the backend's server span as its child.
```
`/api/jobs` needs the analytics mart, which a fresh compose volume does not have (500), so the
compose check uses the API docs as its public route.

## Notes
- Header stripping is the security-critical line. Without it, anyone can be anyone.
- The gateway validating *and* the services validating is deliberate. The gateway fails
  fast; the services never trust the network.
- **Spec corrected on Day 09, before the work, from a read of every remaining spec.**
  - **"OTel agent, same as Day 05"** named the tool Day 05 removed.
  - **The verify commands tested the backend, not the gateway.** They curled 8080, which is
    the backend's port until Day 16. The gateway now has 8081 for the day.
  - **The rate limit had no store.** The gateway's own limiter is Redis-backed; the choice is
    now written down instead of discovered.
  - **"The whole suite passes through the gateway" had no mechanism.** The suite boots its own
    application on a random port with in-JVM stubs; nothing let it go through anything else.
- **Spec corrected on Day 15, before the work.** The spec-auditor, in a fresh context, ran every
  check on `main` at 1467716:
  - **No criterion was tagged `new` or `hold`.**
  - **Criterion 3 and all three curls passed through a proxy that checks nothing.** A
    pass-through proxy on 8081 answered 401 on `/api/profile`, the `X-User-Id` curl and every
    private route, because the backend answers them. With every `SecurityConfig` rule
    `permitAll`, only 2 of 295 tests went red: the controllers 401 an anonymous caller
    themselves. The criterion now needs a recording upstream, and compose stops the backend.
  - **One test guards the backend's own validation,** and it is outside `contract/`: with the
    decoder accepting unverifiable tokens, only `StaleCookieIT`'s tampered-cookie test went red,
    and routed through a gateway it would go green. Now a `hold`, run direct, with Track 0 adding
    the `X-User-Id` case.
  - **Nothing said where the token is.** It is a cookie; a stock resource server reads the
    `Authorization` header. Nor that a stale cookie counts as none, which the frontend's refresh
    relies on and `StaleCookieIT` and `RefreshIT`, not the Day 1–4 suite, pin; both now run
    through the gateway too.
  - **"Exactly" missed three rules**: the JWKS, `/error` and `anyRequest().authenticated()`.
    `ObservabilityIT` expects 401 for `/actuator/*`, which a gateway routing only the table
    would have answered 404.
  - **The suite-through-the-gateway work had no track,** backend CI would neither build the
    image nor run on a gateway change, and at least 7 application contexts each have their own
    port. Now Track D.
  - **The rate limit would have failed the suite through the gateway**, which logs in from one
    address far more than ten times a minute (92 `authenticatedAs` call sites), and "per IP" would have been one bucket for every browser from Day 16.
  - **Verify ran no tests,** printed a body where it meant a status, could not start Postgres
    without `--env-file`, and its public route answers 500 on a fresh volume.
  - **There is no CORS to move**, the correlation id had no header or check, and tracing had no
    command.
  - **Versions:** "the built-in limiter needs Redis" is true of the WebFlux gateway only.
  - Copying the Maven wrapper alone would have been 484 lines, over the gate. *Estimate 3 → 5,*
    for Track 0 and Track D.
- **Done on Day 15.** Spec change #96, then #97 (Track 0, `X-User-Id` is not a login), #98
  (Track A, the gateway routing on 8081), #99 (Track B, the token, the rules, `X-User-Id`), #100
  (Track D, the suite through the gateway), #101 (Track C1, the rate limit), #102 (Track C2,
  cross-origin and tracing) and #103 (C2's tracing test, which turned `main` red). On `main` at
  855726b: the gateway's 25 tests, the backend's 298 direct and 203 through the gateway, checkstyle
  clean on both; all 190 contract tests pass and `contract/` did not change. *Estimated 3 pull
  requests as first written, 5 after the spec change, took 7.*
- **Track C split in two under the 400-line gate** (244 and 333 lines), and #103 was a fix, not a
  planned track. The dashboard named the closing PR as the next step after C1: a split track
  counts as done at its first half, and the script cannot know a second is coming.
- **Departures, and choices the spec left open:**
  - **The four credential routes share one bucket per client:** 10 a minute across login,
    register and both reset steps, stricter than "the 11th login". The gateway's filter keys on
    the client alone; a bucket per route would give a guesser 40 tries a minute.
  - **The rules check two more stale tokens** than the criterion names: another issuer, and
    garbage.
  - **Verify's gateway run needed the image built first and the self-test named.** Corrected
    above. Testcontainers cannot build the image: the Dockerfile's `--platform=$BUILDPLATFORM`
    needs BuildKit (#100).
  - **The backend's wrapper builds the gateway** (`backend/mvnw -f services/api-gateway/pom.xml`),
    the question Track A was left to settle (#98).
  - **An unreachable backend comes back as 500**, not 502 (#98). Left as it is.
- **Cross-origin is not Spring's CORS rejection.** Through the Next.js proxy from Day 16 a
  same-origin request carries the browser's `Origin` with the gateway's host, and Spring refuses
  it: with its rejection in place the frontend's own login got 403 (#102). The gateway refuses
  preflights and strips `Access-Control-*` instead.
- **Spring Boot 4 turns trace propagation off while span export is off.** Its W3C propagator is
  `@ConditionalOnEnabledTracingExport`; otherwise it installs a no-op. The gateway declares the
  propagator itself (#102). **The backend had the same gap:** in default compose it ignored the
  gateway's `traceparent` and logged a trace id of its own, so "the backend already logs it" held
  only with export on. Fixed after the close in Track C3, with the same bean
  (`config/TracingConfig`): `tokens/TraceContinuedIT`, export off, logs the password change under
  the caller's trace; red before the bean (`expected: "4bf92f35…" but was: "eef593a8…"`).
- **Mistakes of mine, recorded in their PRs:**
  - #99: my first report of the breaks attached failures to the wrong test names (a pattern
    that missed self-closing test cases), and two breaks were invalid at first: one stopped the
    context loading, the other changed nothing because the test key server answers every path.
  - #100: the harness first had Testcontainers build the image, and every test errored for 16
    minutes. Stopping that run left its Maven and surefire JVMs writing into the same reports as
    the next run, and its first "203 passed" came from both; rerun clean.
  - #101: one break silently did not apply: Git Bash rewrote `/api/auth/...` in my edit into a
    Windows path.
  - #102: a hand-written logger named `log` failed checkstyle; and `TracingTest` read the access
    log before the line was written, which passed locally and on the pull request and failed on
    `main`. #103 waits for it, shown by delaying the line 300 ms: the old test failed every time,
    the new one passed.
- **For Day 16:** the frontend, Google's redirect URI and the backend's port move to the gateway;
  `GATEWAY_TRUSTED_PROXIES` must name the frontend, and its rewrite must send the browser's
  address, or every user shares one rate-limit bucket.
