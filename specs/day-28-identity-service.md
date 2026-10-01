# Day 28 — identity-service, and the monolith is gone

**Phase:** 5 · **Depends on:** Day 27 · **Expected PRs:** 3
**Status:** provisional — Phase 5's order, bus and review list revised in Day 26's spec change.
Re-read against the code, with the spec-auditor, before starting.

Last of Phase 5 (**26 → 27 → 25 → 28**), and the stopping point: the architecture is evaluated
before Phases 6–7 are started or rewritten (`plan.md`, "Course correction").

## Goal
The last module leaves `backend/`. Five services, no monolith.

## In scope
- `backend/identity` + `app` → `services/identity-service`. **Delete `backend/`.**
- Gateway routes `/api/auth/**`, `/api/profile/**`, `/api/users/**`, `/api/oauth2/**`,
  `/api/login/oauth2/**` to it.
- It keeps: JWKS, service-token issuing, the OAuth redirect URI (registered with Google,
  so it cannot move without updating the Google console — check this first).
- `identity_db` is its own database.
- The repo settles into the `plan.md` layout: `services/*`, `frontend/`, `data/`.
- Root `README.md` rewritten: five services, how to run them, how to run one.
- The outbox relay (Day 26) moves with identity.
- The key sets move: the JWKS endpoints and every URL that reads them (`BACKEND_KEY_SET_URL`,
  `docker-compose.yml:94`). `tokens/ServiceJwksIT` fails through the gateway (3 failures, 1
  error) because the gateway publishes `/.well-known/jwks.json` and not the service key set
  (Day 21 and 22 Notes); this day owns it.
- **The stopping-point review.** The plan-auditor on the whole plan, and this list of what no
  day owns, each kept, dropped or handed to the Days 29–37 rewrite:
  - the `matching` role and schema, `MATCHING_DB_PASSWORD` and the grants to `matching_user`
    in V12–V15 (Days 23–24); V14 raises without the role, so it cannot simply be dropped;
  - the DLQ depth alarm, and the topic, queues and DynamoDB table in Terraform (Days 22, 26);
  - matching-service's `PROFILE_CACHE_WINDOW` and a task role in place of dummy AWS keys
    (Days 22, 24);
  - matching-service's `nl.hackyourfuture.project.backend.*` packages and
    `ScoreTableStartupTest`'s package (Days 23–24);
  - no test for a `null` reason dropping a 25-item DynamoDB batch (Day 22);
  - the missing `phase-3` tag (Day 20).

## Out of scope
- Functions — Phase 6.
- ECS on Fargate — Phase 7, rewritten after this day's review.

## Tracks

| Track | Owner | Work |
|---|---|---|
| A | | Repo move, delete `backend/`, gateway routes |
| B | | `identity_db`, Google redirect URI, OAuth verification |
| C | | README, docs, compose tidy-up, developer onboarding path |

## Acceptance criteria
- [ ] `backend/` no longer exists.
- [ ] Every Day 1–4 test passes **unedited** against the full compose stack.
- [ ] Google sign-in works end to end, including the account-linking branch.
- [ ] `docker compose up` starts the gateway + four services + Postgres + the SNS/SQS
      emulator + `dynamodb-local`.
- [ ] A new teammate can follow the README from clone to running app in under 30 minutes.
- [ ] `ServiceJwksIT` is green through the gateway.
- [ ] The review is in this day's Notes: the plan-auditor's report on the whole plan, and each
      item on the list above kept, dropped or handed on, with `plan.md` changed or confirmed.
- [ ] Release tagged. Phase 5 boundary.

## Verify
```bash
git clone <repo> fresh && cd fresh
docker compose up -d --build
# walk the full browser flow: register, Google login, search, save, match, delete account
```

## Notes
- **End of Phase 5.** The microservice system in the chart now exists.
- The 30-minute onboarding criterion is not a nicety. Five services is where local
  development quietly stops working, and the team stops testing before pushing.
- From Day 39: the monolith has two keys, identity's user key and its own service key. The
  remainder that becomes identity-service takes both; the service issuer's name
  (`jobmatch-backend` today) is decided here, and every service that trusts it follows.
- From Day 40: `contract/AuthGoogleSignInIT` (`@Import`, `@TestPropertySource`) and
  `contract/ObservabilityLoggingIT` (`@TestPropertySource`, a log line captured in the test JVM)
  are still bound to the monolith's context, on purpose. Google sign-in is identity's; four of
  the logging class's five tests log from the test JVM and test the logging configuration, and
  one calls `forgot-password`. What each binds to once the monolith becomes identity-service is
  decided here: the logging configuration may belong to every service.
