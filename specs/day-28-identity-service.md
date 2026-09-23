# Day 28 — identity-service, and the monolith is gone

**Phase:** 5 · **Depends on:** Day 27 · **Expected PRs:** 3
**Status:** provisional — re-read and revise before starting.

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

## Out of scope
- Functions — Phase 6.
- Kubernetes — Phase 7.

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
- [ ] `docker compose up` starts the gateway + four services + Postgres + bus + NoSQL emulator.
- [ ] A new teammate can follow the README from clone to running app in under 30 minutes.
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
