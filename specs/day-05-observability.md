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
- The agent stays in `backend/Dockerfile` for its JDBC spans, disabled unless an endpoint is
  configured. Removing it is a separate decision and not this day's.
- Structured JSON logs carrying the trace identifiers. Spring Boot 4.1 does this on its own
  with `logging.structured.format.console: logstash`, which flattens MDC into the JSON — no
  encoder dependency and no `logback-spring.xml`.
- **The MDC keys are `traceId` and `spanId`**, camel case, because Micrometer Tracing writes
  them. The agent's are `trace_id` and `span_id`. `ObservabilityLoggingIT` asserts on the agent's
  spelling today and has to change with this.
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
- [ ] `/actuator/health` returns 200 with no credentials.
- [ ] `/actuator/prometheus` exposes `http_server_requests_seconds` after a request to
  `/api/jobs`, and is **not** reachable anonymously on the application port.
- [ ] No other `/actuator/**` endpoint is public: `/actuator/env` returns 401.
- [ ] With no OTLP endpoint configured the application still starts and serves `/api/jobs`,
  and the test run makes no outbound metric export.
- [ ] A log line written during a request carries `trace_id`, and JSON logging is on.

- [ ] A request's log lines carry a `traceId`, and two lines from one request carry the same one.

Checkable by hand, with the commands in Verify:
- [ ] `GET /api/jobs` produces one trace whose root is the request, with its JDBC calls beneath it.
- [ ] That trace is findable in Tempo by the `traceId` printed in the logs.
- [x] `docker compose up` does not start Grafana; `--profile obs` does.
- [ ] A slow `top-matches` request shows the LLM call as a distinct span.

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
  - **Not verified in that spike: the OTLP export itself.** Spans were created but did not reach
    Tempo, and the exporter logged nothing. Finishing that wiring is part of this day's work, not
    a settled fact — treat the endpoint property as the first thing to check.
- Four criteria moved from "open Grafana and look" to `./mvnw verify`. A Phase 0 day exists to
  leave behind repeatable evidence, and the original Verify block could only be run by a person
  with Docker and a browser — it would not have failed in CI if someone later removed the
  actuator dependency.
- `backend/Dockerfile` gains a `-javaagent` this day. It currently copies the whole source tree
  before `mvn package`, so every commit re-downloads the dependency tree and the layer cache
  never hits; that has already turned the `build` job red twice on Maven Central rate limits.
  Fixing it is not this day's job, but this is the day that touches the file.
