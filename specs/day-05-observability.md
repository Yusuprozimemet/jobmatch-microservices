# Day 05 — Observability baseline

**Phase:** 0 · **Depends on:** Day 01 · **Expected PRs:** 3

## Goal
Every request produces a trace, a metric and a correlated log line — before we split,
so we can compare before and after.

## In scope
- `spring-boot-starter-actuator`, `/actuator/health` and `/actuator/prometheus`.
- **`micrometer-registry-prometheus`** for the scrape endpoint, and `micrometer-registry-otlp`
  for push. Both: the first is what makes `/actuator/prometheus` exist at all.
- Expose the two endpoints explicitly — `management.endpoints.web.exposure.include` — since
  neither `prometheus` nor anything but `health` is exposed by default.
- Decide how Prometheus reaches the scrape endpoint. `SecurityConfig` ends in
  `anyRequest().authenticated()`, and this day may not make `/actuator/prometheus` public.
  Default to a separate `management.server.port` that compose and the ingress do not publish;
  if the team prefers scrape credentials instead, record that here before building it.
- **Tracing via `spring-boot-starter-opentelemetry`**, not the Java agent. The agent does not
  instrument HTTP on this stack — see the Notes. Traces are exported over OTLP with
  `management.opentelemetry.tracing.export.otlp.endpoint`, and the sampling rate is
  `management.tracing.probability`, which defaults to 0.10 and should be 1.0 while we are
  learning what the traces look like.
- **The agent is removed from `backend/Dockerfile`.** Keeping it was the plan until the work
  showed it actively suppresses Spring's HTTP instrumentation — see the Notes.
- Structured JSON logs carrying the trace identifiers. Spring Boot 4.1 does this on its own
  with `logging.structured.format.console: logstash`, which flattens MDC into the JSON — no
  encoder dependency and no `logback-spring.xml`.
- **The MDC keys are `traceId` and `spanId`**, camel case, because Micrometer's
  `Slf4JEventListener` writes them. The agent's were `trace_id` and `span_id`.
- Only lines logged *inside* the span scope carry them. Tomcat's own error log for a failed
  request is written after the scope closes and has no identifier, which is easy to mistake for
  correlation being broken. Assert against a line the application logs, not one the container
  does.
- `OTEL_EXPORTER_OTLP_ENDPOINT` configurable, and the app boots with it unset.
- **Turn OTLP metric export off in `application-test.yaml`.** It defaults to *enabled*, pointed
  at a local consumer, so an unset endpoint is not "telemetry off": every test run would post
  metrics at `localhost:4318` on each export interval and log the failures. The property is
  `management.otlp.metrics.export.enabled: false`, and the file already does the same thing for
  mail and the LLM.
- Local Grafana stack in `docker-compose.yml` under a `profiles: [obs]` profile,
  so `docker compose up` stays unchanged for everyone else.
- One dashboard: request rate, error rate, p95 latency by route.

## Out of scope
- Grafana Cloud, alerts, Kubernetes collectors — Phase 7.
- Custom spans. The agent's automatic instrumentation is enough today.

## Tracks

| Track | Owner | Work |
|---|---|---|
| A | | Actuator + Micrometer OTLP + health endpoint |
| B | | OTel agent in the Dockerfile + JSON logging with trace ids |
| C | | Compose `obs` profile + the dashboard |

## Acceptance criteria

Checkable by `./mvnw verify`, so the day leaves a gate behind rather than a walk-through:
- [x] `/actuator/health` returns 200 with no credentials.
- [x] `/actuator/prometheus` exposes `http_server_requests_seconds` after a request to
  `/api/jobs`, and is **not** reachable anonymously on the application port.
- [x] No other `/actuator/**` endpoint is public: `/actuator/env` returns 401.
- [x] With no OTLP endpoint configured the application still starts and serves `/api/jobs`,
  and the test run makes no outbound metric export.
- [x] Logs are JSON, and a line written during a request carries a `traceId` and `spanId`.

Checkable by hand, with the commands in Verify:
- [ ] `GET /api/jobs` produces one trace whose root is the request, with its JDBC calls beneath it.
  Half done: the request is the root and its security and dispatch spans sit beneath it, but the
  JDBC spans are gone with the agent. Restoring them needs datasource instrumentation, which is
  a decision this day did not take.
- [x] That trace is findable in Tempo by the `traceId` printed in the logs. Verified:
  `e0f889221dc82d39b76e66ec85a50162` from a log line returned the five spans of
  `http post /api/auth/forgot-password`.
- [x] `docker compose up` does not start Grafana; `--profile obs` does.
- [x] A slow `top-matches` request shows the LLM call as a distinct span. **Not checked.** The
  compose stack has no mart data, so the shortlist is empty and the model is never called. It
  needs a published mart or a seeded database, which is Day 17's ground rather than this day's.
  Ticked on Day 38 (#122): the call had no span at all, because `MatchScorer` built its client
  with `RestClient.builder()`. On Spring's builder, `LlmCallObservedIT` finds a client span for
  `/chat/completions`, with `StubLlm` answering and the real tracer.

## Verify
```bash
cd backend && ./mvnw verify -Dtest='Observability*IT'

# The compose profile: Grafana appears in the second list only.
docker compose config --services
docker compose --profile obs config --services

docker compose --profile obs up -d
curl -s localhost:8080/actuator/health
# then open Grafana and find the trace for a /api/jobs request
```

## Notes
- The LLM span is the payoff, and the thing that justifies this work to the team:
  after Phase 2 nobody can explain a slow request without it.
- Do not widen the public route list in `SecurityConfig` beyond `/actuator/health`.
- **Spec corrected before the work, on the Day 03 and Day 04 pattern.** Four things did not
  match the code or the framework:
  1. `/actuator/prometheus` needs `micrometer-registry-prometheus`. Only
     `micrometer-registry-otlp` was listed, and it does not provide that endpoint, so the
     criterion naming it could not have been met.
  2. The endpoint is not exposed by default and needs
     `management.endpoints.web.exposure.include`.
  3. "Unset means telemetry off" is not true of the Micrometer OTLP registry: export defaults
     to enabled against a local consumer. Without the test-profile switch above, every test run
     would try to ship metrics — the same trap as Day 04's LLM key, in the same file.
  4. A Logback JSON encoder dependency is no longer needed on Spring Boot 4.1.
- **The OpenTelemetry Java agent does not instrument HTTP on this stack, and the day changed
  because of it.** Measured against the running compose stack on Day 05:
  - agent 2.11.0 (the version first pinned, from 2024) produced no spans at all;
  - agent 2.31.1, current, produces JDBC spans and nothing else. Every database call arrives as
    its own trace with no parent, and a request touching no database produces no span whatever.
    The root span names over five minutes were `SELECT project_db`, `project_db`,
    `SELECT "app"."flyway_schema_history"` — no `GET /api/jobs` among them.
  - Tempo was ruled out: a span posted to `tempo:4318/v1/traces` by hand returns 200 and is
    immediately searchable.
  - Adding `spring-boot-starter-opentelemetry` instead instruments requests immediately. Proof
    without a collector: request-scoped log lines start carrying `traceId` and `spanId`, which
    they never did under the agent.
  - Two reasons the spike did not export, both found afterwards and both worth knowing. The
    sampling property is `management.tracing.sampling.probability`, not
    `management.tracing.probability`, so sampling silently stayed at the 0.1 default. And the
    agent suppresses Spring's HTTP instrumentation: with the agent attached, `/api/jobs`
    produces only loose JDBC spans; with it removed, the same request produces
    `GET /api/jobs`. That A/B is why the agent is gone rather than kept for JDBC spans.
  - **The trade is real and should be taken deliberately.** The agent gave JDBC spans and no
    request span; Spring gives the request span, the security filter chain and dispatch, and no
    JDBC span. `http get /api/jobs` now arrives as a root with eight children. Getting both needs
    datasource instrumentation on top, which is outside this day.
  - **An empty endpoint is not "no exporter".** `management.opentelemetry.tracing.export.otlp.endpoint`
    set to the empty string fails the exporter with "Invalid endpoint, must start with http:// or
    https://" and the application does not start — every test errored. Use
    `management.tracing.export.enabled` to switch export off instead; spans are still created and
    still correlate.
  - Log correlation does work with the starter alone, and the identifiers in the JSON come from
    Micrometer rather than from the agent. An earlier reading that said otherwise was taken from
    Tomcat's error log, which is written outside the span scope.
- **Seven of nine criteria hold; two do not and are left unticked.** The request trace is a
  real root with its security and dispatch spans beneath it, but without the JDBC spans the
  agent used to provide. The LLM span was never checkable here at all.
- **Estimated 3 pull requests, took 7.** Six of them were the tracing detour: the day assumed an
  agent that does not work on this stack, and finding that out, proving the alternative, and
  correcting two wrong readings along the way cost four pull requests on their own. The estimate
  was not unreasonable for the day as written; it was wrong about the day that actually existed.
- Four criteria moved from "open Grafana and look" to `./mvnw verify`. A Phase 0 day exists to
  leave behind repeatable evidence, and the original Verify block could only be run by a person
  with Docker and a browser — it would not have failed in CI if someone later removed the
  actuator dependency.
- `backend/Dockerfile` gains a `-javaagent` this day. It currently copies the whole source tree
  before `mvn package`, so every commit re-downloads the dependency tree and the layer cache
  never hits; that has already turned the `build` job red twice on Maven Central rate limits.
  Fixing it is not this day's job, but this is the day that touches the file.
- **JDBC spans are dropped** (Day 38), not restored: nothing has needed them since the agent went.
  Phase 4 may bring them back if it shows a query worth tracing. The first criterion stays
  unticked.
