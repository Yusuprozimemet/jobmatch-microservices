# Day 34 — Observability on ECS

**Phase:** 7 · **Depends on:** Day 36 · **Expected PRs:** 7

Rewritten from the Helm draft by the course correction before Phase 7 (`plan.md`): Phase 7's
third step, "Observability". Day 36 ran before it (same section), so this day builds on Day 36's
`modules/service` and `local.services`.

## Goal
`terraform plan` gives each JVM service an ADOT collector beside it that sends its traces to
X-Ray and its metrics to CloudWatch, each service names its own traces, and the deployment has
the alarms and the one dashboard Day 35 will watch. Every check runs in infra-ci without an
account.

## How the checks read the plan
As Day 36's ("How the checks read the plan"): without an account, container definitions, IAM
policies, alarm actions and every `arn`/`arn_suffix` are unknown in the plan, so what a check
needs is a literal in `locals.tf`, returned by an output the check reads, and the check also reads
`configuration` to see that a resource or module takes its input from that local or from the
reference it should. An output with any unknown part has no `value` in `planned_values` (the
spec-auditor's experiment on `4cce7a8`), so **nothing in `local.services` or in a local an
output returns refers to a computed attribute**: the metrics log group's name is a literal, and
the dashboard's ALB and target-group dimension values are merged in the
`aws_cloudwatch_dashboard` resource, not in the local. The collector's configuration is a file in
the repository, read by `file()` at plan time and parsed by the check. Real X-Ray, CloudWatch and
alarms are Day 35's (H34.1–H34.5).

## In scope
- **Trace names (H42.2).** Each JVM service's image names its spans after the service:
  `OTEL_SERVICE_NAME=jobmatch-<service>` in its Dockerfile (`jobmatch-identity-service`,
  `jobmatch-job-service`, `jobmatch-matching-service`, `jobmatch-application-service`,
  `jobmatch-api-gateway`). Today identity's is `jobmatch-backend`
  (`services/identity-service/Dockerfile:48`) and the gateway sets none, so its spans take
  `spring.application.name`, `api-gateway`. Spring Boot puts `OTEL_SERVICE_NAME` as
  `service.name` and uses the application name only when it is absent (`javap` on
  `OpenTelemetryResourceAttributes`, 4.0.8 and 4.1.0). Grafana's dashboard
  (`observability/grafana/jobmatch.json`) gets `uid` `jobmatch` and title `JobMatch`: it plots
  every service, not the backend. The gateway's `application.yaml:35` comment, "Day 34's probes
  use it too", is reworded to name ECS's container health check (Day 36, C36.7).
- **The collector's configuration**, one file:
  - an OTLP receiver bound to `localhost` (HTTP on 4318, which the services use; gRPC on 4317),
    so no other task can push into a service's collector through `tasks_from_tasks` (Day 36's
    known limit);
  - `awsxray` for traces, `awsemf` for metrics into the namespace `JobMatch`, and the
    `health_check` extension;
  - `awsemf` writes to a log group Terraform creates, with `dimension_rollup_option:
    NoDimensionRollup`. Without it awsemf adds a rollup set per attribute (`[]`, `[uri]`,
    `[method]`, `[status]`, `[telemetry.sdk.name]`… the auditor's run of ADOT `v0.50.0`) on top
    of the declared ones;
  - `metric_declarations` select `'^http\.server\.requests$'`, anchored so that
    `http.server.requests.active` is not taken, with the dimension sets `[service.name]`,
    `[service.name, uri]` and `[service.name, outcome]`. `resource_to_telemetry_conversion` is
    on, so `service.name` can be a dimension.

  About 30 mapped routes in the four services, about 8 patterns at the gateway, and
  `NOT_FOUND`/`UNKNOWN` per service make 70–90 metrics, about $0.03 an hour. Undeclared meters
  (`jvm.*`, Hikari) still reach the group as plain log events: log ingestion, no metrics.
- **The sidecar (H36.6).** Every JVM service's task gets a second container, `collector`:
  - the ADOT image at a pinned tag from `public.ecr.aws/aws-observability/aws-otel-collector`
    (public ECR: no Docker Hub limit, no ECR permission);
  - `essential = false`, so a collector crash does not stop the service, and
    `restartPolicy = { enabled = true }`, so ECS restarts it;
  - a hard `memory` limit inside the unchanged task size (the auditor measured 23.5 MiB idle);
  - its configuration from `AOT_CONFIG_CONTENT`, and `AWS_REGION`;
  - its own log lines in the service's log group under the prefix `collector`.

  The frontend and the one-off tasks get none: the frontend exports nothing, and a migrate run
  ends before a batch would leave.
- **The services' variables.** Each JVM service's entry sets:
  - `TRACING_EXPORT_ENABLED = "true"` and `OTEL_METRICS_ENABLED = "true"`;
  - `TRACING_PROBABILITY = "1.0"`;
  - `MANAGEMENT_OTLP_METRICS_EXPORT_AGGREGATIONTEMPORALITY = "delta"`. Micrometer's OTLP
    registry sends cumulative counts by default and awsemf passes them through, so a request
    rate would plot a running total (the auditor's run: `Count` 10, then 25). A collector-side
    `cumulativetodelta` gives deltas but zeroes Min and Max.

  The endpoints stay unset. Every service's defaults are `http://localhost:4318/v1/traces` and
  `http://localhost:4318` (C34.8), and in `awsvpc` the sidecar is `localhost`. That keeps C36.4's
  rule of no `*_ENDPOINT` variable as it is. `TRACING_EXPORT_ENABLED` leaves `C36_COMPOSE_ONLY`.
  `OTEL_TRACES_ENDPOINT` stays on it, with the reason changed to "the default is the sidecar":
  a departure from H36.6's wording, which had both leave the list. Identity's
  `application.yaml:129-130` comment ("Phase 7 is where sampling becomes a cost decision") is
  updated to point here.
- **Sampling** stays at every request, now written down. X-Ray records 100,000 traces a month
  free and charges $5 a million after that; one deployment's day of hand-made traffic is far below
  the free tier.
- **The task role's telemetry policy.** Each JVM service's task role gets:
  - `xray:PutTraceSegments` and `xray:PutTelemetryRecords` on `*`: X-Ray has no resource-level
    permission for them;
  - `logs:CreateLogStream`, `logs:PutLogEvents` and `logs:DescribeLogStreams` on
    `"<metrics group arn>:*"`, the stream form the execution role already uses
    (`modules/service/main.tf:154`);
  - no `logs:CreateLogGroup`, because Terraform owns the group.

  C36.4's check changes with it: those two X-Ray actions are the only `*` a task role may hold,
  and job-service's and the gateway's roles, which had no policy, now hold only this. The policy
  references the group, so the first apply orders the services after it.
- **Log groups.** The metrics log group is an `aws_cloudwatch_log_group` with a literal name and
  14 days' retention, as the services' are, so `terraform destroy` leaves no group the collector
  made.
- **Alarms** (the maintainer, in Day 36's architecture review on 2026-10-09, recorded in a
  session and not in a PR; this spec is its record):
  - on the ALB: `HTTPCode_Target_5XX_Count` and `HTTPCode_ELB_5XX_Count`;
  - on the frontend's target group, the only one: `HealthyHostCount < 1` with
    `treat_missing_data = "breaching"`. ECS deregisters a stopped task's target, so
    `UnHealthyHostCount` would stay 0 when the frontend is gone.

  The two 5xx alarms use `treat_missing_data = "notBreaching"`. Each alarm sends to the bus
  module's alarm topic (`alarm_topic_arn`).
- **One CloudWatch dashboard,** `jobmatch`, with:
  - the ALB's requests, 5xx and target response time;
  - each service's request rate and latency, from `JobMatch`;
  - each ECS service's CPU and memory, from `AWS/ECS`;
  - the depth of the DLQs.

  Its widgets (namespaces, metric names and dimension keys) are a literal local returned by an
  output. The ALB's `arn_suffix` is merged in the resource. Every `JobMatch` metric it plots is one
  that `metric_declarations` names.
- **A start check in infra-ci.** The pinned collector image runs with the file, fake credentials
  and a region, and `docker exec <container> /healthcheck` prints `STATUS: 200` within 60 s. The
  image has no shell, and the extension listens on `localhost:13133` inside the container, so the
  host cannot reach it. A misspelt component, or a key the image does not know, stops the
  collector before that.

## Out of scope
- **The gateway's per-route metric** (Day 38's "For Day 37", which `plan.md` sends here "or
  dropped there on record") is dropped. The gateway's routes are patterns (`Routes.java:51-89`):
  `/api/jobs`, `/api/jobs/{postingId}`, `/api/saved-jobs/**` and others. Only identity's
  catch-all is one series, `/api/**`. A tag per path behind that is a code change. Each service
  behind the gateway already sends its own per-route series (C34.2).
- **Container Insights:** off. It adds custom metrics per task; `AWS/ECS` already gives CPU and
  memory per service, which is what the dashboard plots.
- **Logs:** unchanged. Each task already logs to its own group with 14 days' retention (Day 36),
  and every line already carries the trace id (Day 05).
- **Compose:** Tempo, Prometheus and Grafana stay as they are, except Grafana's `uid` and title.
- **X-Ray sampling rules and groups:** the services sample (`TRACING_PROBABILITY`), not X-Ray.
- **A `filter` processor for the undeclared meters:** their log ingestion is cents for one day.
- Applying anything, and seeing a trace, a metric or an alarm for real — Day 35 (H34.1–H34.5).

## Tracks

| Track | Owner | Work |
|---|---|---|
| 0 | | The hold (C34.8), and every infra-ci path the day's checks read |
| A | | Trace names, Grafana's `uid`, the gateway's comment (C34.1) |
| B | | The collector's configuration and its start check (C34.2) |
| C | | The sidecar, the variables, the metrics log group (C34.3, C34.5); the telemetry policy (C34.4) |
| D | | Alarms (C34.6) and the dashboard (C34.7) |

The tracks run in the order 0, A, B, C, D, each from fresh `main`:
- Track 0 adds infra-ci's paths for the Dockerfiles, the five `application.yaml` files and
  `observability/**`, so that A's check runs on A's PR.
- C reads B's file, C2 needs C1's log group, and D2 plots what B declares.
- A and D1 depend on nothing else in the day.

Each track adds its `check_c34_*` to `infra-checks.py` in the same PR, so each check is seen
failing before the change it checks. C splits (C1: C34.3 and C34.5; C2: C34.4 with C36.4's
change), and so does D (D1: alarms; D2: dashboard). Day 36 estimated 8 and took 7. The
auditor's sizes (0 ≈60 lines, A ≈60, B ≈150, C1 ≈300, C2 ≈150, D1 ≈150, D2 ≈250) give an
estimate of 7.

## Acceptance criteria
Every check is a `check_c34_*` in `scripts/infra-checks.py`, run by infra-ci with `--main-plan`
on the plan made without an account, as Day 36's are. C34.1 and C34.8 read files under the
repository root (`repo_root` in `infra-checks.py`). Red today for all of C34.1–C34.7:
- `grep -c "def check_c34" scripts/infra-checks.py` is 0;
- `grep -rn --include='*.tf' "aws_cloudwatch_dashboard\|xray\|aws-otel" infra/terraform` finds
  nothing;
- the plan on `4cce7a8` has 141 resources, 2 alarms (both `module.bus…dlq`) and 9 log groups.

Each "only" or "no" check first asserts that what it inspects exists, so none passes on an empty
plan.

- [ ] C34.1 **new** — Each of the five JVM services' Dockerfiles sets `OTEL_SERVICE_NAME` to
      `jobmatch-<its directory under services/>`. No Dockerfile and nothing under
      `observability/` contains `jobmatch-backend`. `jobmatch.json`'s `uid` is `jobmatch`. Red
      today: identity's is `jobmatch-backend`, `services/api-gateway/Dockerfile` sets none, and
      the `uid` is `jobmatch-backend`.
- [ ] C34.2 **new** — The collector's file parses as YAML and has:
      - an `otlp` receiver with `http` on `localhost:4318` and `grpc` on `localhost:4317`;
      - a `traces` pipeline from `otlp` to `awsxray`, and a `metrics` pipeline from `otlp` to
        `awsemf`;
      - `awsemf` with `namespace: JobMatch`, `dimension_rollup_option: NoDimensionRollup`,
        `resource_to_telemetry_conversion.enabled: true` and a `log_group_name`;
      - `metric_declarations` whose only selector is `'^http\.server\.requests$'`, with exactly
        the three dimension sets of In scope;
      - the `health_check` extension in `service.extensions`.

      infra-ci starts the pinned image with the file, and `docker exec <container>
      /healthcheck` prints `STATUS: 200` within 60 s. The image's tag is not `latest`. Red
      today: no file, no step.
- [ ] C34.3 **new** — Each of the five JVM services declares the collector, in its `services`
      entry or through one `collector` local returned by an output, and the module call passes
      it from that local. The collector has:
      - the pinned image, `essential = false` and `restartPolicy` enabled;
      - a `memory` limit below the task's;
      - `AOT_CONFIG_CONTENT` from `file()` of C34.2's file, and `AWS_REGION`;
      - the service's log group, with the prefix `collector`.

      The frontend and the three one-off tasks have none. Each JVM service's environment has
      `TRACING_EXPORT_ENABLED = "true"`, `OTEL_METRICS_ENABLED = "true"`,
      `TRACING_PROBABILITY = "1.0"` and `MANAGEMENT_OTLP_METRICS_EXPORT_AGGREGATIONTEMPORALITY =
      "delta"`, and no `OTEL_*ENDPOINT`. `C36_COMPOSE_ONLY` no longer lists
      `TRACING_EXPORT_ENABLED`. Red today: no collector, and none of the four variables in any
      entry.
- [ ] C34.4 **new** — Each JVM service's task role has one telemetry policy:
      `xray:PutTraceSegments` and `xray:PutTelemetryRecords` on `*`, and the three `logs:`
      actions on the metrics log group's `arn` with `:*`, read from `configuration` references.
      The frontend's role has none. C36.4's check allows a task role's `*` for those two X-Ray
      actions and nothing else, and still finds identity's, matching's and
      application-service's own statements as before. Red today: no task role has an `xray:`
      action, and job-service's and the gateway's roles have no policy.
- [ ] C34.5 **new** — The `log_group_name` in C34.2's file is the literal name of an
      `aws_cloudwatch_log_group` in the plan with `retention_in_days = 14`. Red today: there is
      no such file and no such group.
- [ ] C34.6 **new** — Three `aws_cloudwatch_metric_alarm` beside Day 32's DLQ alarms, all in
      `AWS/ApplicationELB`:
      - `HTTPCode_Target_5XX_Count` and `HTTPCode_ELB_5XX_Count` on the ALB's `LoadBalancer`,
        each with `treat_missing_data = "notBreaching"`;
      - `HealthyHostCount` below 1 on the frontend's `TargetGroup` and the ALB's
        `LoadBalancer`, with `treat_missing_data = "breaching"`.

      The check reads the dimension values and `alarm_actions` as `configuration` references
      (`aws_lb.main.arn_suffix`, `aws_lb_target_group.frontend.arn_suffix`,
      `module.bus.alarm_topic_arn`), as C32.8 resolves its own. The track's PR pins thresholds
      and periods. C32.8 still finds only its DLQ alarms. Red today: the only alarms in the plan
      are the DLQ ones.
- [ ] C34.7 **new** — One `aws_cloudwatch_dashboard` named `jobmatch`. The check reads its
      widgets from an output, a literal local, and they include:
      - at least one widget in each of `AWS/ApplicationELB`, `AWS/ECS`, `AWS/SQS` and
        `JobMatch`;
      - only `JobMatch` metrics that C34.2's `metric_declarations` selects, each with one of its
        dimension sets.

      The dashboard's `dashboard_body` refers to that local (`configuration`). Red today: no
      dashboard.
- [ ] C34.8 **hold** — Each JVM service's `application.yaml` has these defaults:
      `OTEL_TRACES_ENDPOINT` is `http://localhost:4318/v1/traces`, `OTEL_EXPORTER_OTLP_ENDPOINT`
      is `http://localhost:4318`, and `TRACING_EXPORT_ENABLED` and `OTEL_METRICS_ENABLED` are
      `false`. The files are the four under `services/*/src/main/resources` and identity's under
      `app/`. Compose still sets `OTEL_TRACES_ENDPOINT` to Tempo. True today, in all five files.
      Break: point job-service's traces default at `localhost:4317`; the check must name
      job-service.
- [ ] C34.9 **hold** — Day 32's and Day 36's criteria stay green in infra-ci: every step, and
      `infra-checks.py` on C32.2–C32.10 and C36.1–C36.11, with C36.4 as C34.4 changes it.
      Break: as C36.12's, a `timestamp()` tag in `modules/scores`; the second-apply step must
      fail.

## Verify
```bash
# in infra-ci, after the plans (.github/workflows/infra-ci.yaml):
python scripts/infra-checks.py --bootstrap-plan bootstrap-plan.json --main-plan main-plan.json \
  --localstack http://localhost:4566 --compose-localstack "$COMPOSE_LOCALSTACK"
# and the collector start step C34.2 adds.
```

## Notes
- **Rewritten by the spec change** from the Helm draft, then audited by the spec-auditor on
  `4cce7a8` (KAN-38): 14 findings, 4 of them must-fix. All 14 are applied below or in the text,
  except the `filter` processor, which is now Out of scope. The auditor ran ADOT `v0.50.0`
  locally and a scratch plan without an account.
- **Defect** — found: auditor · cause: spec · the draft said `metric_declarations` bound the
  metric count; awsemf adds a rollup per attribute unless `NoDimensionRollup` is set. Now C34.2.
- **Defect** — found: auditor · cause: spec · the draft would have plotted running totals:
  Micrometer exports cumulative counts and awsemf passes them through. Now the delta variable in
  C34.3.
- **Defect** — found: auditor · cause: spec · the draft's dashboard output would refer to the
  ALB's `arn_suffix`, and an output with an unknown part has no value in `planned_values`. Now
  "How the checks read the plan" and C34.7.
- **Defect** — found: auditor · cause: spec · the draft's start check asked for 200 from the
  health port, which listens on `localhost` inside the container. Now `/healthcheck` via
  `docker exec`.
- **Defect** — found: auditor · cause: spec · `UnHealthyHostCount` stays 0 when a task stops,
  because ECS deregisters it. Now `HealthyHostCount` (C34.6, H34.3).
- **Defect** — found: auditor · cause: spec · four smaller ones:
  - the draft cited `--repo-root`, which `infra-checks.py` does not have;
  - the metric selector was unanchored;
  - the logs resource lacked the `:*` stream form;
  - the draft said the gateway tags every call `/api/**`.
- **Hand-offs picked up.**
  - H36.6, the sidecar in `modules/service`: C34.3. `OTEL_TRACES_ENDPOINT` stays on the
    compose-only list, a departure recorded in In scope.
  - H42.2, trace names and Grafana's `uid`: C34.1.
  - Day 38's per-route gateway metric, which `plan.md` sent here: dropped, Out of scope.
  - Day 32's Out of scope, the collector, CloudWatch logs and X-Ray: this day.
  - Day 05's alerts for Phase 7: C34.6.
  - The ALB alarms and the dashboard from Day 36's review: C34.6 and C34.7.
  - "Day 34's probes" (Days 28, 38, 40, 42) were Day 36's C36.7. The last comment that names
    them, the gateway's `application.yaml:35`, is reworded in Track A.
- **Left as they are:** `ObservabilityIT.java:99`'s "Kubernetes readiness probe" (a Day 1–4
  test) and identity's `application.yaml:122` "Phase 7's load balancer". Both are stale, because
  the ALB checks only the frontend; neither is this day's subject.
- **Hand-off** H34.1 → Day 35: one request through the ALB shows in X-Ray as one trace across
  the gateway and the service behind it, each under its C34.1 name, and the trace ids in the
  services' log lines find the same trace. The awsxray exporter took a W3C id without error
  (auditor); whether X-Ray itself accepts the id is what this shows.
- **Hand-off** H34.2 → Day 35: the `JobMatch` metrics arrive with C34.2's dimension sets as
  deltas, every dashboard widget shows data, and the count of custom metrics is recorded against
  the 70–90 estimate.
- **Hand-off** H34.3 → Day 35: an alarm reaches the topic's email. With the frontend's task
  stopped (as in the matching task-crash test), `HealthyHostCount` goes to alarm.
- **Hand-off** H34.4 → Day 35: after `terraform destroy`, no log group is left, because the
  collector made none of its own. X-Ray keeps traces for 30 days at no charge, and they cannot
  be deleted early.
- **Hand-off** H34.5 → Day 35: the collector's memory under the day's traffic, against its limit,
  and whether ECS's restart policy restarts a stopped collector.
