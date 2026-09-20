# Day 15 — The API gateway

**Phase:** 2 · **Depends on:** Day 14 · **Expected PRs:** 3

## Goal
A Spring Cloud Gateway service fronts the backend, validates tokens and routes by path.

## In scope
- New `services/api-gateway`: own pom, own Dockerfile, own CI workflow.
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
- Per-IP rate limit on `/api/auth/**`.
- Correlation id: generate when absent, forward, and log it.
- OTel agent, same as Day 05.

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
curl -s localhost:8080/api/jobs | head -c 200               # public, 200
curl -s -o /dev/null -w '%{http_code}' localhost:8080/api/profile   # 401
curl -s -H 'X-User-Id: 00000000-0000-0000-0000-000000000000' localhost:8080/api/profile
```

## Notes
- Header stripping is the security-critical line. Without it, anyone can be anyone.
- The gateway validating *and* the services validating is deliberate. The gateway fails
  fast; the services never trust the network.
