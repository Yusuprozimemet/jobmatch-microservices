# Day 06 — Maven multi-module skeleton

**Phase:** 1 · **Depends on:** Day 05 · **Expected PRs:** 2

## Goal
`backend` becomes a parent pom with one module per future service. No code moves yet.

## In scope
- Parent `backend/pom.xml` with modules: `identity`, `jobs`, `applications`,
  `matching`, `shared`, `app`.
- `shared`: DTO conventions, `PageResponse`, `GlobalExceptionHandler`, and `MartSkills`.
  Nothing else — a fat shared module recreates the monolith. `MartSkills` is there under
  protest: `jobs` and `applications` both parse the mart's skills column today, and Day 09
  removes `applications`' reason to. Take it out of `shared` then.
- `app`: the only Spring Boot application. Depends on every module, builds the image, and
  keeps everything with no other home — `SecurityConfig`, `GoogleOAuth2Config`,
  `OpenApiConfig`, `SchedulingConfig`, `application.yaml`, and the whole test suite.
- Dependency rule in the parent: no module depends on another module except `shared`.
- **The build configuration has to move with the layout**, and two pieces break silently
  otherwise:
  - `maven-checkstyle-plugin` has `<configLocation>checkstyle.xml</configLocation>`, which
    each module resolves against its own directory. Every module but the parent fails, and
    CI runs `./mvnw -B checkstyle:check`.
  - `spring-boot-maven-plugin` must be bound in `app` alone, or every module tries to produce
    an executable jar.
- `backend/Dockerfile` needs the module poms copied before `dependency:go-offline`, not just
  the parent's. The dependency layer added in the Docker build fix exists to stop Maven
  Central rate-limiting the CI build; a multi-module layout that copies only `pom.xml`
  resolves nothing and quietly puts the full download back into every commit.
- Unchanged: `docker compose up`, the image name, every endpoint.

## Out of scope
- Moving any class into a feature module — Day 07. The modules are created empty; `app` keeps
  the code. `shared` is the exception, because `PageResponse` and `GlobalExceptionHandler`
  have to exist somewhere for the dependency rule to mean anything.
- Moving the tests. They boot the whole application and belong to `app` until there is
  something else to boot.
- Splitting migrations — Day 11.

## Tracks

| Track | Owner | Work |
|---|---|---|
| A | | Parent pom, module poms, dependency management |
| B | | `app` module: main class, config, Dockerfile path, CI build path |

## Acceptance criteria
- [ ] `./mvnw verify` builds all modules and runs the Day 1–5 suites: 196 tests, all green.
- [ ] `./mvnw -B checkstyle:check` passes from `backend/`, for every module.
- [ ] Exactly one module produces a Spring Boot jar.
- [ ] No module lists another feature module as a dependency (only `shared`).
- [ ] `docker compose up` works with no change to `docker-compose.yml`, and
  `curl localhost:8080/api/jobs` answers.
- [ ] A second image build, after touching one source file, does not re-download the
  dependency tree — the same check the Docker build fix introduced.
- [ ] Backend CI builds and pushes the same image as before.

## Verify
```bash
cd backend && ./mvnw verify
docker compose up -d --build backend && curl -s localhost:8080/api/jobs | head -c 200
```

## Notes
- Keep `shared` small and boring. Every class added to it becomes a deployment
  dependency between services later.
- **Spec corrected before the work, on the pattern of Days 3 to 5.** Three things had no home
  in the module list and would have been discovered mid-move:
  - `MartSkills` is used by `jobs` and by `savedjobs`, so it cannot live in either. It is in
    `shared` with an expiry date on it.
  - `PageResponse` is used by six classes across `jobs` and `savedjobs` — the spec was right to
    put it in `shared`, and this is the evidence for it.
  - the `config` package is five classes, only one of which the spec placed. The other four go
    to `app`.
- `app` depending on `identity` is normal and not a violation of the dependency rule:
  `SecurityConfig` wires `OAuth2LoginSuccessHandler`, and `app` is allowed to depend on
  everything. The rule is about feature modules depending on each other.
