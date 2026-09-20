# Day 16 — Cutover and cleanup

**Phase:** 2 · **Depends on:** Day 15 · **Expected PRs:** 3

## Goal
All traffic goes through the gateway, the backend has no public port, and the session
code is gone.

## In scope
- `docker-compose.yml`: publish `api-gateway` on 8080; **remove the backend's `ports:`**.
  Frontend `BACKEND_API_URL` points at the gateway.
- Delete the dead session code left behind on Day 13, and the backend's CORS config now
  that the gateway owns it.
- Deployment: gateway gets the public ingress, backend goes internal-only.
- Docs: update `backend/docs/auth.md`, `api.md` and `hiearchy-backend.md` for JWT.
  Add both architecture charts to `backend/docs/`.
- `README.md`: the new local startup story.
- Tag the release. This is the Phase 2 boundary and a sensible rollback point.

## Out of scope
- Extracting any service — Phase 3, specs to be written after this day.

## Tracks

| Track | Owner | Work |
|---|---|---|
| A | | Compose + deployment wiring, backend port removal |
| B | | Delete session and CORS code |
| C | | Docs, README, charts, release tag |

## Acceptance criteria
- [ ] `docker compose ps` shows no published port for the backend.
- [ ] The app works end to end from a browser: register, login, Google login, search,
      save a job, view matches, change password, log out.
- [ ] `grep -rn "JSESSIONID\|HttpSession\|establishSession"` returns nothing.
- [ ] `backend/docs/auth.md` describes JWT, not sessions.
- [ ] Both charts are committed and render on GitHub.
- [ ] A tagged release exists and the team knows it is the rollback point.

## Verify
```bash
docker compose down -v && docker compose up -d --build
docker compose ps                    # backend: no published port
cd backend && ./mvnw verify
# then walk the full browser flow above
```

## Notes
- **End of Phase 2, and the recommended stopping point in `plan.md`.**
  Boundaries are proven, auth is stateless, tests are real. Phases 3–7 can start whenever,
  and none of this work needs redoing.
- Write the Phase 3 specs only after this day ships. What we learn here changes them.
