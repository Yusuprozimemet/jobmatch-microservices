# Day 05 — Observability baseline

**Phase:** 0 · **Depends on:** Day 01 · **Expected PRs:** 3

## Goal
Every request produces a trace, a metric and a correlated log line — before we split,
so we can compare before and after.

## In scope
- `spring-boot-starter-actuator`, `/actuator/health` and `/actuator/prometheus`.
- `micrometer-registry-otlp`.
- OpenTelemetry Java agent as `-javaagent` in `backend/Dockerfile` (no code changes).
- Logback JSON encoder with `trace_id` and `span_id` from MDC.
- `OTEL_EXPORTER_OTLP_ENDPOINT` configurable; unset means telemetry off, app still boots.
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
- [ ] `GET /api/jobs` produces one trace spanning the controller and its JDBC calls.
- [ ] Every log line for that request carries the same `trace_id`.
- [ ] `/actuator/prometheus` exposes `http_server_requests_seconds`.
- [ ] `/actuator/health` returns 200 and is reachable without authentication.
- [ ] With `OTEL_EXPORTER_OTLP_ENDPOINT` unset, the app starts and serves traffic.
- [ ] `docker compose up` does not start Grafana; `--profile obs` does.
- [ ] A slow `top-matches` request shows the LLM call as a distinct span.

## Verify
```bash
docker compose --profile obs up -d
curl -s localhost:8080/actuator/health
# then open Grafana and find the trace for a /api/jobs request
```

## Notes
- The LLM span is the payoff, and the thing that justifies this work to the team:
  after Phase 2 nobody can explain a slow request without it.
- Do not widen the public route list in `SecurityConfig` beyond `/actuator/health`.
