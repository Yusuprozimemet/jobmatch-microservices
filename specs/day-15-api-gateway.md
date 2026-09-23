# Day 15 — The API gateway

**Phase:** 2 · **Depends on:** Day 14 · **Expected PRs:** 3

## Goal
A Spring Cloud Gateway service fronts the backend, validates tokens and routes by path.

## In scope
- New `services/api-gateway`: own pom, own Dockerfile, own CI workflow. It publishes
  **8081** until Day 16 moves it to 8080; the backend keeps 8080 until then.
- Routes, matching today's `SecurityConfig` rules exactly:

  | Path | Target | Auth |
  |---|---|---|
  | `/api/auth/**`, `/api/oauth2/**`, `/api/login/oauth2/**` | backend | public |
  | `PATCH /api/auth/password` | backend | required |
  | `GET /api/jobs`, `/api/jobs/filters`, `/api/jobs/*` | backend | public |
  | `GET /api/jobs/top-matches` | backend | required |
  | `/api/profile/**`, `/api/saved-jobs/**`, `/api/users/**` | backend | required |
  | `/api/docs/**` | backend | public |

- JWT validated at the gateway against the backend's JWKS, cached.
- Forwards `X-User-Id` from `sub`, and **strips any inbound `X-User-Id`** from the client.
- CORS handled at the gateway only, and removed from the backend on Day 16.
- Per-IP rate limit on `/api/auth/**`, **in memory** (for example Bucket4j). Spring Cloud
  Gateway's built-in `RequestRateLimiter` needs Redis, which the stack does not have. In-memory
  is correct for one gateway replica; more than one, from Phase 7, needs a shared store.
- Correlation id: generate when absent, forward, and log it.
- Tracing the way the backend has done it since Day 05: Micrometer tracing with
  `spring-boot-starter-opentelemetry`, exporting OTLP. **Not the Java agent**, which Day 05
  removed: on this stack it produced no HTTP server spans and suppressed Spring's own.
- A way to run the Day 1–4 suite through the gateway, changing `support/` only. Recommended:
  Testcontainers starts the gateway image, routed at the test's own application through
  `host.testcontainers.internal`, and `ApiClient` targets the gateway. That keeps the
  in-JVM stubs (`StubOidcProvider`, `StubLlm`) and the JDBC fixtures working unchanged, which
  pointing the suite at a compose stack would not.

## Out of scope
- Routing to separate services — there is still only one backend. Phase 3 changes targets,
  not routes.

## Tracks

| Track | Owner | Work |
|---|---|---|
| A | | Gateway skeleton, Dockerfile, CI, compose service |
| B | | Routes + JWT filter + header handling |
| C | | Rate limit, CORS, correlation id, tracing |

## Acceptance criteria
- [ ] The backend keeps validating tokens itself — the gateway is not the only check.
- [ ] A client-supplied `X-User-Id` never reaches the backend (test it explicitly).
- [ ] Public routes work with no cookie; private routes return 401 at the gateway.
- [ ] 11 failed logins from one IP inside a minute are rate limited.
- [ ] One trace spans gateway and backend as parent and child.
- [ ] The whole Day 1–4 suite passes when run through the gateway.

## Verify
```bash
docker compose up -d --build
# 8081 is the gateway today; 8080 is still the backend until Day 16.
curl -s localhost:8081/api/jobs | head -c 200               # public, 200
curl -s -o /dev/null -w '%{http_code}' localhost:8081/api/profile   # 401
curl -s -H 'X-User-Id: 00000000-0000-0000-0000-000000000000' localhost:8081/api/profile   # still 401
```

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
