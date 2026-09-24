# Day 16 — Cutover and cleanup

**Phase:** 2 · **Depends on:** Day 15 · **Expected PRs:** 2

## Goal
All traffic goes through the gateway, and the backend has no public port.

## In scope
- `docker-compose.yml`: publish `api-gateway` on host 8080 (`"8080:8081"`; it keeps 8081 inside
  its container, so the harness image and `support/Gateway.java` do not change); **remove the
  backend's `ports:`**. The frontend's `BACKEND_API_URL` points at `http://api-gateway:8081`, in
  compose and in `frontend/Dockerfile:18`, whose default names the backend. The frontend
  `depends_on` the gateway, and `scripts/dev-up.sh:13` starts it.
- The rate limit stays keyed by the connecting address: `GATEWAY_TRUSTED_PROXIES` stays empty.
  The frontend's proxy passes a client's own `X-Forwarded-For` through unchanged
  (`frontend/src/proxy.ts`, Next's `x-forwarded-for ??=`), so trusting the frontend would let any
  client pick its own bucket; locally, every browser shares the frontend's one. A production
  ingress that overwrites the header is what may be trusted (Notes).
- No CORS to delete: the backend never had any, and the gateway allows no cross-origin requests
  (found on Day 15). The session code went on Day 14.
- Docs: `backend/docs/auth.md` and `api.md` for the gateway (the 429 on the four credential
  routes, 502/504, `X-User-Id`, preflights refused, the port); `configuration.md` for the stack
  with the gateway, and without the `env_file` it claims the backend has.
- Two architecture diagrams in `backend/docs/architecture.md`, as Mermaid so they render on
  GitHub and diff in review: **before** (browser → backend, session cookie) and **after**
  (browser → gateway → backend, JWT cookie, JWKS).
- `README.md`: the new local startup story.

## Out of scope
- Extracting any service — Phase 3, specs to be written after this day.
- The production ingress. No deployment configuration exists in the repository; the live host is
  set up outside it (Notes). Deployment as code is Phase 7.
- The release tag as a diff: a tag is not in a PR. The maintainer tags `main` after Track B.

## Tracks

| Track | Owner | Work |
|---|---|---|
| A | | Compose and frontend wiring: the gateway on 8080, the backend's port gone, `dev-up.sh` |
| B | | Docs, README and the two diagrams, after A |

Track B follows A: it documents A's wiring. No Track 0: the `hold` checks exist already.

## Acceptance criteria
- [x] **new** — Compose publishes no backend port: `docker compose -p day16check ps backend`
      shows no `->`, and `curl localhost:8080/api/docs/openapi.yaml` answers 200 from the
      gateway. Red today: the audit's `ps` showed `backend 0.0.0.0:8080->8080/tcp` and
      `api-gateway 0.0.0.0:8081->8081/tcp`.
      #109: `backend 8080/tcp, 9090/tcp`, `api-gateway 0.0.0.0:8080->8081/tcp`, docs 200. On
      `main` at 8dee1e1, `docker compose config` gives the backend no `ports` and the gateway
      `8080 -> 8081`.
- [x] **new** — The frontend reaches the backend only through the gateway: with the gateway
      stopped (`docker compose -p day16check stop api-gateway`), `localhost:3000/api/users/me`
      no longer answers 401. Red today: the frontend calls `backend:8080` directly, so it still
      answers 401.
      #109: 500 with the gateway stopped. The frontend's URL is `http://api-gateway:8081` in
      compose and in `frontend/Dockerfile`.
- [x] **new** — The limit holds through the frontend: in compose, the 11th wrong-password
      `POST localhost:3000/api/auth/login` in a minute is 429, and still 429 when every request
      sends a different `X-Forwarded-For`. Red today: the audit got 401 eleven times through
      3000. Red again with `GATEWAY_TRUSTED_PROXIES` set to the frontend: twelve spoofed logins
      were `200 ×12` in the audit's chain.
      #109: `401 ×10, 429, 429`, plain and with a new `X-Forwarded-For` each time. Red again in
      compose with the gateway trusting the frontend's address: spoofed `401 ×12`, while plain
      logins against that same gateway still hit 429 on the 11th.
- [x] **hold** — The flow works through the frontend in compose, by curl: register 201, login
      200, `/api/users/me` 200, `PATCH /api/auth/password` 200, logout 200 (**Verify**). True today,
      direct to the backend. Broken on purpose in Track A's PR (the frontend pointed at a
      host that does not answer).
      #109: 201, 200, 200, 200, 200 through the gateway. Broken with the gateway stopped: every
      step 500.
- [x] **hold** — The Day 1–4 suite, `StaleCookieIT` and `RefreshIT` pass through the gateway with
      `-Dharness.gateway=true` (**Verify**), and the gateway's own 27 tests pass. Seen red on
      Day 15 (#100, #101).
      #109: 203 tests in 26 reports through the gateway, 0 failures; the gateway 27, 0 failures;
      the backend direct 299, 0 failures, 1 skipped by design. `contract/` and `support/`
      unchanged across the day.
- [x] **hold** — No session code: from `backend/`,
      `grep -rn "JSESSIONID\|HttpSession\|establishSession" */src/main docs ../services/api-gateway/src ../frontend/src`
      prints nothing; `tokens/NoSessionIT` and `tokens/GoogleNoSessionIT` are the behavioural
      half. True today (exit 1). Broken on purpose in this spec change: with
      `import jakarta.servlet.http.HttpSession;` added to `BackendApplication.java`, it printed
      that line; reverted.
      #108 for the break. It then caught a real one in #110's first draft: `architecture.md`
      named the old session cookie, and the grep printed both lines. Exit 1 on `main` at 8dee1e1.
- [x] **hold** — `backend/docs/auth.md` describes tokens, not sessions:
      `grep -cE "JSESSIONID|HttpSession" backend/docs/auth.md` gives 0. True since Day 13 (#89);
      the same grep gives 4 on the `b178e90` version.
      0 on `main` at 8dee1e1, after #110 added the gateway to it.
- [x] **new** — The docs know the gateway: `grep -n "No rate limiting" backend/docs/api.md
      backend/docs/auth.md` prints nothing, and `grep -n "env_file" backend/docs/configuration.md`
      no longer says compose passes `backend/.env` to the backend. Red today: `api.md:649`,
      `auth.md:428` and `configuration.md:31`.
      #110: the first grep prints nothing; `configuration.md:31` now says compose does not read
      `backend/.env`, and the only other `env_file` line is about older Compose versions.
- [x] **new** — `backend/docs/architecture.md` has two `mermaid` blocks, before and after, and each
      renders with `minlag/mermaid-cli`. Red today: the file does not exist.
      #110: both render, exit 0, and were looked at. Red with the gateway node left unclosed:
      exit 1, `Parse error on line 4`.
- [x] **new** — The rollback point is tagged: `git ls-remote --tags origin phase-2` prints one
      line, pointing at Track B's merge commit. Red today: `git ls-remote --tags origin` is empty.
      `phase-2`, annotated, pushed after #110 at the maintainer's word: `ls-remote` prints
      `7e6247f… refs/tags/phase-2`, the tag object, and `phase-2^{commit}` is 8dee1e1, #110's
      merge.

## Verify
```bash
# The gateway's own tests, and the backend direct. Read the reports.
backend/mvnw -B -f services/api-gateway/pom.xml clean verify checkstyle:check
cd backend
rm -rf */target/surefire-reports
./mvnw clean verify
./mvnw -B checkstyle:check

# The Day 1-4 suite through the gateway, as on Day 15.
docker build -t jobmatch-api-gateway:harness ../services/api-gateway
rm -rf */target/surefire-reports
./mvnw -B verify -pl app -am -Dharness.gateway=true \
  -Dtest='nl.hackyourfuture.project.backend.contract.*IT,StaleCookieIT,RefreshIT,GatewayHarnessIT' \
  -Dsurefire.failIfNoSpecifiedTests=false

# Compose, in a project of its own: never `down -v` on the maintainer's. The network is pinned to
# `finalproject`, so stop the maintainer's stack first.
cd ..
docker compose -p day16check --env-file .env.example up -d --build
docker compose -p day16check ps backend                                     # no ->
curl -s -o /dev/null -w '%{http_code}\n' localhost:8080/api/docs/openapi.yaml  # 200, the gateway
# The flow through the frontend: register, login, /api/users/me, password change, logout.
# The 11th wrong-password login through localhost:3000 is 429, with and without X-Forwarded-For.
docker compose -p day16check stop api-gateway
curl -s -o /dev/null -w '%{http_code}\n' localhost:3000/api/users/me       # not 401
docker compose -p day16check down -v

# After Track B merges, the maintainer tags it.
git ls-remote --tags origin phase-2
```
A fresh volume has no analytics mart (search is 500), and compose passes no Google credentials
(sign-in is 404), so the browser walk is a check by hand on the maintainer's own stack: register,
login, Google login, search, save a job, view matches, change password, log out.

## Notes
- **End of Phase 2, and the recommended stopping point in `plan.md`.**
  Boundaries are proven, auth is stateless, tests are real. Phases 3–7 can start whenever,
  and none of this work needs redoing.
- Write the Phase 3 specs only after this day ships. What we learn here changes them.
- **Spec corrected on Day 09, before the work, from a read of every remaining spec.**
  `hiearchy-backend.md` does not exist — Day 02 found this, and it was never taken out here.
  "Both architecture charts" named nothing that exists or is described anywhere; the two
  diagrams are now defined.
- **Spec corrected on Day 14, before that day's work.** Deleting `establishSession` and the rest of
  the session code moved to Day 14, whose grep (`HttpSession|getSession|changeSessionId`) could not
  pass while it survived; the `JSESSIONID|HttpSession|establishSession` criterion here should hold
  from then on, and `backend/docs/auth.md` was rewritten for tokens on Day 13 (#89). Both are for
  this day's own spec change to re-tag.
- **Spec corrected on Day 16, before the work, from the spec-auditor's read.**
  - The Verify ran `docker compose down -v` on the maintainer's project, which deletes their
    database, and passed no `--env-file` with no root `.env`, so it could not start. It now runs
    a project of its own, as Day 15's does.
  - The session grep had no path, so it matched this spec, `plan.md`, the README and the two
    tests that assert `JSESSIONID` is absent: it could never pass without deleting those guards.
    It is now scoped to main code, docs and the frontend, and tagged `hold`.
  - "No published port" alone did not prove the goal: the backend stays reachable at
    `backend:8080` inside compose, and `frontend/Dockerfile:18` defaults to it. The
    gateway-stopped check says whether the frontend goes through the gateway.
  - Trusting the frontend's `X-Forwarded-For` would have let any client change its bucket per
    request (the audit's chain: twelve spoofed logins, `200 ×12`). The maintainer chose to keep
    the header untrusted, one shared bucket locally, over a proxy container in front. The
    production ingress must overwrite `X-Forwarded-For` before `GATEWAY_TRUSTED_PROXIES` names it.
  - The browser walk could not be checked on a fresh volume (no mart: search 500; no Google
    credentials: 404). Its parts are the curl flow and the Day 1–4 suite through the gateway;
    the walk stays a check by hand.
  - `auth.md` had been rewritten for tokens on Day 13; what is stale is Day 15's gateway
    (`api.md:649`, `auth.md:428` "No rate limiting") and `configuration.md:31`, which claims an
    `env_file` for the backend that compose has never had.
  - No deployment configuration exists in the repository, so "the gateway gets the public
    ingress" moved out of scope: it is the maintainer's change on the live host.
  - The tag is named (`phase-2`), checked by `git ls-remote`, and made by the maintainer on
    Track B's merge commit, so the closing PR can tick it. "The team knows" is not checkable.
  - Track B was "None" (no CORS to delete); the docs track is now B, after A. Two PRs.
  - Google's redirect URI does not move: it defaults to
    `http://localhost:3000/api/login/oauth2/code/google` and reaches the gateway through the
    frontend once `BACKEND_API_URL` changes.
  - Next's rewrite timeout (30 s) equals `GATEWAY_READ_TIMEOUT`, so a slow backend may reach the
    browser as the frontend's proxy error rather than the gateway's 504. Recorded, not changed.
- **Done on Day 16.** Spec change #108, then #109 (Track A: the gateway on 8080, the backend's
  port gone, the frontend through the gateway) and #110 (Track B: the docs, the README and the two
  diagrams), and the `phase-2` tag on #110's merge. Estimated 3 pull requests as first written,
  2 after the audit; took 2.
- **The audit changed more than the checks.** Of its 14 findings, the one with a design in it was
  the rate limit: the spec said to trust the frontend's `X-Forwarded-For`, and the frontend passes
  a client's own header through, so every request could pick its own bucket. It took a chain of
  real containers to see it; reading `proxy.ts` shows a one-line rewrite and nothing wrong. The
  maintainer chose one shared bucket locally over a proxy container in front.
- **Two documents had been wrong since the initial commit.** `configuration.md` said compose passes
  `backend/.env` into the backend; the backend service has never had an `env_file`, which is why
  compose runs with Google sign-in off. The README said a fresh database shows an empty job list;
  `/api/jobs` is 500 until the pipeline publishes. Both corrected in #110, from a compose run, not
  a read.
- **Departures and choices:**
  - The gateway keeps 8081 inside its container and compose maps `8080:8081`, so the harness image
    and `support/Gateway.java` did not change.
  - The production ingress is not in the repository; pointing it at the gateway, and trusting it
    only if it overwrites `X-Forwarded-For`, is the maintainer's change on the live host.
  - Track B also wrote the gateway's settings table and production checklist lines into
    `configuration.md`: the spec named the file, not those sections.
- **Mistakes of mine, recorded in their PRs:**
  - #109: my first trust-the-frontend override had `"\."` in double-quoted YAML; compose refused
    it, the gateway did not restart, and that run hit a spent bucket. Rerun after checking the
    variable inside the container.
  - #110: my first `architecture.md` named the old session cookie, and the day's session grep
    printed it. The grep was scoped in #108 to include `docs`; it did its job.
  - #108 first gave the password change a 204; the contract suite says 200. Fixed before the PR.
- **For Phase 3:** the backend is reachable only through the gateway, which routes `/api/**` and
  the key set to one upstream. Extracting job-service means a second route and a second
  `BACKEND_URL`; the gateway's access table is a copy of the backend's, so a rule changed in one
  must change in the other. The Day 1–4 suite already runs
  through the gateway, in CI.
