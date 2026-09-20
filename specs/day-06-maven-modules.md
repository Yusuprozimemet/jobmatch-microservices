# Day 06 — Maven multi-module skeleton

**Phase:** 1 · **Depends on:** Day 05 · **Expected PRs:** 2

## Goal
`backend` becomes a parent pom with one module per future service. No code moves yet.

## In scope
- Parent `backend/pom.xml` with modules: `identity`, `jobs`, `applications`,
  `matching`, `shared`, `app`.
- `shared`: DTO conventions, `PageResponse`, `GlobalExceptionHandler`. Nothing else —
  a fat shared module recreates the monolith.
- `app`: the only Spring Boot application. Depends on every module, builds the image.
- Dependency rule in the parent: no module depends on another module except `shared`.
- Unchanged: `docker compose up`, the image name, every endpoint.

## Out of scope
- Moving any class — Day 07.
- Splitting migrations — Day 11.

## Tracks

| Track | Owner | Work |
|---|---|---|
| A | | Parent pom, module poms, dependency management |
| B | | `app` module: main class, config, Dockerfile path, CI build path |

## Acceptance criteria
- [ ] `./mvnw verify` builds all modules and runs the Day 1–4 suites, all green.
- [ ] Exactly one module produces a Spring Boot jar.
- [ ] No module lists another feature module as a dependency (only `shared`).
- [ ] `docker compose up` works with no change to `docker-compose.yml`.
- [ ] Backend CI builds and pushes the same image as before.

## Verify
```bash
cd backend && ./mvnw verify
docker compose up -d --build backend && curl -s localhost:8080/api/jobs | head -c 200
```

## Notes
- Keep `shared` small and boring. Every class added to it becomes a deployment
  dependency between services later.
