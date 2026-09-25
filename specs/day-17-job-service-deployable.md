# Day 17 — job-service becomes its own deployable

**Phase:** 3 · **Depends on:** Day 16 · **Expected PRs:** 3
**Status:** provisional — re-read and revise before starting.

## Goal
`job-service` runs as a separate container with its own image and pipeline. It still shares
the database and is still called in-process by nothing — the gateway routes to it directly.

## In scope
- Repo move: `backend/jobs` → `services/job-service`, with its own pom, Dockerfile,
  CI workflow and compose service. Copy the pattern from the Day 15 gateway.
- `shared` becomes a published artifact both services depend on, or is duplicated.
  **Decide today and write the decision down** — this choice repeats for every extraction.
- Gateway routes `/api/jobs`, `/api/jobs/filters`, `/api/jobs/*` to `job-service`.
  `/api/jobs/top-matches` still goes to the monolith.
- Micrometer tracing with `spring-boot-starter-opentelemetry`, actuator, JWT validation —
  same setup as every other service. Not the OTel Java agent; Day 05 removed it.
- **`SavedJobCounts` needs an implementation on its first day out.** The only one is
  `applications`' in-process `ApplicationsDirectory`, which stays in the monolith. So the
  monolith exposes the counts (`POST /internal/saved-counts`) and `job-service` calls it —
  today, not on Day 25 where the endpoint was first written. The alternative, `job-service`
  reading `saved_jobs` again because the database is still shared, undoes Day 08.
- Database unchanged: both containers still connect to the same Postgres.

## Out of scope
- Internal endpoints — Day 18.
- Its own database — Day 20.

## Tracks

| Track | Owner | Work |
|---|---|---|
| A | | Repo move, pom, Dockerfile, CI |
| B | | Compose service, gateway routes, health checks |
| C | | `shared` distribution decision + implementation |

## Acceptance criteria
- [ ] `docker compose ps` shows `job-service` running with no published port; only the
      gateway reaches it.
- [ ] Day 03's job tests pass **unedited** against the gateway, `JobSavedCountIT` included.
- [ ] A trace spans gateway → job-service.
- [ ] The monolith no longer serves `/api/jobs`: a request to it from inside the compose
      network returns 404.

## Verify
```bash
docker compose up -d --build
curl -s localhost:8080/api/jobs | head -c 200
# The backend logs no request lines, so grepping its logs for GET /api/jobs finds 0
# whether or not it served one. Ask it directly instead:
docker compose exec job-service curl -s -o /dev/null -w '%{http_code}' http://backend:8080/api/jobs   # 404
```

## Notes
- Easiest extraction in the plan: read-only, no user data. If this one is painful,
  stop and fix the process before attempting Day 21.
- The `shared` decision is the one that compounds. Prefer duplication of small DTOs
  over a shared artifact that couples deployments.
- **Corrected on Day 09 from a read of every remaining spec** — still provisional; this fixes
  what is already known to be wrong, not the phase review.
  Four things: the OTel agent; `SavedJobCounts` having no implementation once `job-service`
  leaves the process (Day 25 was the first day that gave it one); a log grep that returns 0
  whatever happens; and the same criterion written twice.
- From Day 39: job-service gets a service key of its own (`iss` its own name,
  `aud=jobmatch-internal`) and publishes its key set; the monolith adds it to its trusted issuers.
  A map keyed by hyphenated issuer names cannot be set from environment variables, so compose
  needs another form for that entry.
- From Day 40: the gateway already routes `/api/jobs`, `/api/jobs/filters` and
  `/api/jobs/{postingId}` to `gateway.job-service-url` (`JOB_SERVICE_URL`, the backend when
  unset), so Track B sets the variable in compose rather than writing routes. The harness has a
  route table in `support/` whose job-service entry defaults to the monolith. Today the harness
  also:
  - starts job-service's image, as `support/Gateway` starts the gateway's: built beforehand,
    never pulled, a failure that prints the build command, on the same test Postgres;
  - points the entry at it, and passes `JOB_SERVICE_URL` to the harness's gateway;
  - points the monolith's `PostingLookup`/`PostingShortlist` clients (Day 19) at the container's
    mapped port;
  - lets the container reach the monolith, for `/internal/saved-counts` and the user key set,
    through `host.testcontainers.internal` (`Testcontainers.exposeHostPorts` first);
  - has CI build job-service's image before the plain `./mvnw verify`, which until today needs no
    image (`backend-ci-cd.yaml` builds the gateway's only after it).

  The two middle items are a cycle at startup. The monolith's port is random and known only once
  its context has started, while a client that reads its base URL when its bean is made needs the
  container first. The gateway escapes it only because `Gateway.baseUrl` starts it lazily, after
  the context. Day 17 breaks it: the harness picks the monolith's port before the context starts
  (a free port as `server.port`), or the clients and the issuer list resolve the URL per request.

  What Day 40 built, for the items above (#145): the table is `support/Services` and its entry's
  default is `Services.url`; a class points a service elsewhere with
  `IntegrationTest#serviceUrls()`; `Gateway` keeps one gateway per application port and route
  table. `Gateway.insideContainer` takes only a `localhost` or `127.0.0.1` URL, which it reaches
  through `host.testcontainers.internal`. A job-service container is not on the host, so its
  entry needs a URL the gateway container can reach: a network the two share, or the host's
  mapped port.

  `ObservabilityIT` left `contract/` on Day 40, so job-service asserts its own `uri="/api/jobs"`
  on its own management port. Neither query-count test expires today: job-service still uses the
  test database, and asks the monolith for saved counts (`StatementCounter` counts per database).
