# Day 36 — The services on ECS

**Phase:** 7 · **Depends on:** Day 33 · **Expected PRs:** 8

Split from Day 33 by the course correction before Phase 7 (`plan.md`). It runs after Day 33 and
before Day 34: Day 36 reuses a deleted number so that no hand-off to Days 34 and 35 changes.

## Goal
`terraform plan` describes the five services and the frontend on ECS Fargate behind an HTTPS
ALB, with their secrets, task roles, images and one-off tasks, and every check of it runs in
infra-ci without an account.

## How the checks read the plan
Without an account, `terraform show -json` leaves a task definition's container definitions and
every IAM policy document unknown: they are built from ARNs that do not exist yet (the
spec-auditor's experiment on `0b962c1`; C32.7 hit the same wall). So each service is declared
once, as literal values in a `local.services` map in the main root: its port, environment,
the names of the secrets it reads, its IAM actions and the keys of the resources they apply to,
its health check. The task-definition module builds the container definitions, the `secrets`
entries (by the secret's full ARN) and the policies from that map, and a root output `services`
returns the map. Its value is known at plan time, so `infra-checks.py --main-plan` reads it
from `planned_values.outputs.services`, and checks that each module call takes its input from
`local.services` (`configuration`). What the plan cannot show (that the policies allow what the
services do, that the ARNs resolve) is H36.5.

## In scope
- ECR repositories, one per image: the five services, the frontend and `db-setup`, each with
  `force_delete` so that `terraform destroy` empties it.
- The ECS cluster, and one module (`modules/service`) for the task definitions and services:
  `FARGATE`, `awsvpc`, the public subnets with a public IP (Day 32 chose no NAT), the tasks
  security group. The image is the repository's URL and a tag variable; nothing is pushed.
- The `local.services` map and the root `outputs.tf` with `services`; the outputs the day needs
  from Day 32's modules: the queues' ARNs (`bus`), the database's address without the port
  (`database`).
- Secrets Manager secrets, created by Terraform with **no** `aws_secretsmanager_secret_version`,
  so no value reaches the state: `db-password-<role>` for the seven roles in `db-setup.py`'s
  `ROLES`, `jwt-private-key` (identity), `service-jwt-private-key-<service>` for job-,
  matching- and application-service, `google-client-id`, `google-client-secret`, `llm-api-key`.
  The admin password is RDS's managed master secret, read with its `:password::` key. The
  values are written on Day 35 (H36.1); a task whose secret has no value does not start.
- One execution role and one task role per task definition. The execution role reads only that
  task's secrets; its only `*` resource is `ecr:GetAuthorizationToken`, which cannot be scoped.
  Task roles: identity publishes to the `user-deleted` topic; matching reads and writes the
  scores table and consumes `matching-user-deleted`; application-service consumes
  `applications-user-deleted`; the others have no policy. No access keys and no emulator
  endpoints in any task definition (H28.5).
- The ALB: HTTPS on 443 to the frontend with an ACM certificate validated by DNS in a Route 53
  zone given as a variable (`route53_zone_id`), as is the domain (`domain`); 80 redirects to
  443. The frontend's target group checks `/` on port 3000: the frontend has no actuator.
- Container health checks on the JVM services: `/actuator/health/readiness` on the management
  port 9090, with the bash `/dev/tcp` probe compose already uses. This is what the "Day 34's
  probes" comments meant (Days 28, 38, 40, 42; `services/api-gateway/.../application.yaml:35`).
- Security groups: the ALB admits 80 and 443 from anywhere; the tasks admit the ALB on 3000 and
  one another; RDS stays tasks-only (C32.4).
- Service Connect with each service under its compose name (`identity-service`, `job-service`,
  `matching-service`, `application-service`, `api-gateway`), so every internal URL keeps its
  compose form.
- The variables that differ from compose (H28.5, Day 22, Day 24): `SESSION_COOKIE_SECURE=true`,
  `APP_BASE_URL=https://<domain>`, the Google redirect URI on that domain, `MIGRATE_ON_START=false`
  on identity and application-service, `SCORES_CREATE_TABLE` unset, `PROFILE_CACHE_WINDOW` set
  and its value recorded, `GATEWAY_TRUSTED_PROXIES` matching the public subnets.
- One gateway task; matching scales on CPU. The ALB serves only the frontend, so `plan.md`'s
  "or ALB request count" has no target group to count on matching.
- The one-off tasks: migrate for identity and application-service (`MIGRATE_ONLY=true`, Day 33),
  and `db-setup` (`--passwords-from-env`, Day 33) with its own small image
  (`scripts/db-setup.Dockerfile`: Python and `psycopg[binary]`), built in `db-setup-tests.yml`.
  No ECS service runs them.
- The stale "Day 33's tasks" comment in `modules/network/main.tf:62`.

## Out of scope
- The code changes the task definitions rely on — Day 33.
- The push from the service workflows to ECR, and the deploy job — Day 35.
- The ADOT collector sidecar; until then `TRACING_EXPORT_ENABLED` and `OTEL_TRACES_ENDPOINT` are
  compose-only — Day 34 (H36.6).
- Writing the secret values, and any apply against a real account — Day 35.

## Tracks

| Track | Owner | Work |
|---|---|---|
| A | | ECR, the cluster, `modules/service`, `local.services`, the outputs (C36.1, C36.2) |
| B | | Secrets and roles (C36.3, C36.4) |
| C | | ALB, ACM, security groups, health checks (C36.5–C36.7) |
| D | | Service Connect, the ECS-only variables, the one-off tasks, scaling (C36.8–C36.11) |

They run A, B, C, D, each from fresh `main`: B, C and D all edit A's module. Each track adds its
`check_c36_*` to `infra-checks.py` in the same PR, so each check is seen failing on the plan
before the Terraform it checks. A track over 400 lines splits into its Terraform and its
checks, as Day 32's A1/A2 did; D is expected to split (D1: names and variables; D2: one-off
tasks and scaling). No Track 0: the one hold, C36.12, is already in infra-ci.

## Acceptance criteria
Every check is a `check_c36_*` in `scripts/infra-checks.py`, run by infra-ci with
`--main-plan` on the plan made without an account (`-var domain=example.com
-var route53_zone_id=Z0000000000000000000` added to the existing flags). Red today, for all of
C36.1–C36.11: `grep -c "def check_c36" scripts/infra-checks.py` is 0, and the main plan on
`0b962c1` has 33 resources, none of them `aws_ecs_*`, `aws_ecr_*`, `aws_lb*`, `aws_acm_*`,
`aws_secretsmanager_*`, `aws_iam_*`, `aws_appautoscaling_*`, `aws_service_discovery_*` or
`aws_route53_*`. Each "only" or "no" check first asserts that what it inspects exists, so none
passes on an empty plan.

- [ ] C36.1 **new** — Seven `aws_ecr_repository`, one per image (the five services, `frontend`,
      `db-setup`), each with `force_delete = true`. Red today: none in the plan.
- [ ] C36.2 **new** — One `aws_ecs_cluster`; six `aws_ecs_service` (the five services and the
      frontend), each from `module.service`, with `launch_type = "FARGATE"`, `assign_public_ip
      = true`, the public subnets only and the tasks security group only; each task definition
      `requires_compatibilities = ["FARGATE"]`, `network_mode = "awsvpc"`. The `services`
      output has the same six names. Red today: none in the plan; no `services` output.
- [ ] C36.3 **new** — The fourteen secrets in In scope are `aws_secretsmanager_secret`s, and
      the plan has no `aws_secretsmanager_secret_version`. Every secret a service names in
      `services` is one of them, and every role password `db-setup` needs is named by it. No
      environment variable's name matches `PASSWORD|SECRET|PRIVATE_KEY|ACCESS_KEY` and no value
      is a PEM. Red today: no secrets, no `services` output.
- [ ] C36.4 **new** — Each task definition has an execution role and a task role. The execution
      role reads only the secrets that task names; its only `*` is `ecr:GetAuthorizationToken`.
      No task role has a `*` resource or action; identity's reaches only the topic, matching's
      only the scores table and `matching-user-deleted`, application-service's only
      `applications-user-deleted`; job-service, the gateway and the frontend have none. No
      environment variable is `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `*_ACCESS_KEY`,
      `*_SECRET_KEY` or `*_ENDPOINT` (H28.5). Red today: no roles.
- [ ] C36.5 **new** — One `aws_lb` (application, internet-facing, the public subnets); a 443
      listener with `HTTPS` and the `aws_acm_certificate` for `var.domain`, validated by
      `aws_route53_record`s in `var.route53_zone_id`; an 80 listener whose default action
      redirects to 443; one target group, the frontend's, port 3000, `target_type = "ip"`,
      health check path `/`. Red today: no `aws_lb`.
- [ ] C36.6 **new** — The ALB's security group admits 80 and 443 from `0.0.0.0/0` and nothing
      else. The tasks security group has exactly two ingress rules: 3000 from the ALB's group,
      and all ports from itself. C32.4 (RDS admits only the tasks) stays green. Red today: the
      tasks group has no ingress rule (`modules/network/main.tf:78-98`) and there is no ALB group.
- [ ] C36.7 **new** — Each of the five JVM services' health checks in `services` probes
      `/actuator/health/readiness` on 9090; the frontend's has none (the target group checks it).
      Red today: no `services` output.
- [ ] C36.8 **new** — One Service Connect namespace; each of the five services is registered
      under its compose name and port. Every internal URL in `services` (`*_SERVICE_URL`,
      `INTERNAL_*_URL`, `JOB_SERVICE_KEY_SET_URL`, `BACKEND_API_URL`) is
      `http://<compose name>:<port>` of a registered service. Every variable compose sets for a
      service is in its `services` entry or on the compose-only list in `infra-checks.py`, each
      with its reason: the `*_FILE` key paths (the content variables replace them), the
      emulator endpoints and keys, `SCORES_CREATE_TABLE`, `TRACING_EXPORT_ENABLED` and
      `OTEL_TRACES_ENDPOINT` (Day 34, H36.6). The check reads `docker-compose.yml`. Red today:
      no `services` output.
- [ ] C36.9 **new** — In `services`: identity has `SESSION_COOKIE_SECURE = "true"`,
      `APP_BASE_URL = "https://${var.domain}"`, and `GOOGLE_REDIRECT_URI` unset or on that
      domain; identity and application-service have `MIGRATE_ON_START = "false"`; matching has
      no `SCORES_CREATE_TABLE` and a `PROFILE_CACHE_WINDOW`; the gateway's
      `GATEWAY_TRUSTED_PROXIES` matches an address in each public subnet and none in the private
      ones. Red today: no `services` output.
- [ ] C36.10 **new** — Three one-off task definitions and no service for any of them:
      identity's and application-service's migrate (their service's image, `MIGRATE_ONLY =
      "true"`), and `db-setup` (the `db-setup` image, `--passwords-from-env`, the master
      secret's `:password::` as `POSTGRES_PASSWORD`, the seven `DB_PASSWORD_<ROLE>`).
      `db-setup-tests.yml` builds `scripts/db-setup.Dockerfile` and runs `--help` in it. Red
      today: no task definitions; no such Dockerfile.
- [ ] C36.11 **new** — The gateway's service has `desired_count = 1` and no
      `aws_appautoscaling_target`; matching's has one, and an `aws_appautoscaling_policy` of type
      `TargetTrackingScaling` on `ECSServiceAverageCPUUtilization`. Red today: no services.
- [ ] C36.12 **hold** — Day 32's criteria stay green in infra-ci: `fmt -check`, the plans
      without an account, the lock test, the LocalStack apply and the second apply's
      "No changes.", `infra-checks.py` on C32.2–C32.10. Break: a `timestamp()` tag in
      `modules/scores`, as C32.10 was broken; the second-apply step must fail.

## Verify
```bash
# in infra-ci, after the plans (.github/workflows/infra-ci.yaml):
python scripts/infra-checks.py --bootstrap-plan bootstrap-plan.json --main-plan main-plan.json \
  --localstack http://localhost:4566 --compose-localstack "$COMPOSE_LOCALSTACK"
```
Run with no arguments it checks only the backends: it is not a check of this day.

## Notes
- LocalStack community has no ECS, so nothing this day adds is applied before Day 35. CI
  applies only `infra/terraform/localstack/`; the ECS resources live in the main root, which CI
  only plans.
- **Rewritten by the spec change (KAN-115)** after the spec-auditor on `0b962c1` (KAN-40):
  14 findings, 8 must-fix. The maintainer chose literal declarations read from a known output
  (over applying IAM and Secrets Manager to LocalStack, which does not enforce IAM), empty
  secrets written on Day 35, the `db-setup` task on this day, and full ARNs in `secrets`.
- **Defect** — found: auditor · cause: spec · the draft's checks all read the plan JSON, where
  container definitions and IAM policies are unknown without an account; 4 of 8 criteria could
  not be checked. Now "How the checks read the plan".
- **Defect** — found: auditor · cause: spec · the criteria had no IDs, tags or Red today lines,
  and the "no"/"only" ones passed on today's empty plan. Now C36.1–C36.12, each asserting
  presence first.
- **Defect** — found: auditor · cause: spec · the draft had `db-setup.py` write the secret
  values; it only reads the environment, and writing needs an account. Now H36.1.
- **Defect** — found: auditor · cause: spec · Day 33 handed the `db-setup.py` task to this day
  (day-33:30-31); the draft dropped it, and no image packages the script. Now C36.10.
- **Defect** — found: auditor · cause: spec · the draft's target groups checked
  `/actuator/health/readiness`, which the Next.js frontend, the ALB's only target, does not
  have. Now C36.5 and C36.7.
- **Defect** — found: auditor · cause: spec · "no `*` resource" cannot hold for execution roles
  (`ecr:GetAuthorizationToken`), and matching is not behind the ALB, so it cannot scale on
  request count. Now C36.4 and C36.11.
- **Defect** — found: auditor · cause: spec · the estimate of 5 PRs for four tracks that cannot
  run in parallel; Day 32 estimated 4 and took 6, Day 33 estimated 5 and took 7. Now 8.
- **Hand-offs picked up.** Day 33's Out of scope (ECS, the migrate and `db-setup.py` tasks,
  the secrets, the domain and the redirect URI) and its Notes; H28.5 (task roles, no emulator
  endpoints, the profile cache window: C36.4, C36.9); Day 22 (task role in place of dummy keys,
  the create flag unset: C36.4, C36.9); Day 24 (`PROFILE_CACHE_WINDOW`: C36.9); Day 15 and
  `plan.md` (one gateway task: C36.11); "Day 34's probes" in Days 28, 38, 40, 42 and the
  gateway's `application.yaml` (C36.7). H32.1 (the `jobs_db` seed) stays Day 35's; H36.3 puts
  it in order.
- **Hand-off** H36.1 → Day 35: write the fourteen secret values before any service starts:
  the role passwords generated, the signing keys made with `scripts/jwt-key.sh`, Google's and
  the LLM's from the maintainer. No value is written through Terraform.
- **Hand-off** H36.2 → Day 35: the ACM certificate validated against the real domain and zone.
- **Hand-off** H36.3 → Day 35: the order of the first deploy: `db-setup`, then the two migrate
  tasks, then the `jobs_db` seed (H32.1), then the services' desired counts.
- **Hand-off** H36.4 → Day 35: the seven images in ECR, `db-setup`'s included, before the
  services scale up.
- **Hand-off** H36.5 → Day 35: what the plan cannot show: each task role allows what its
  service does and no more (the services run and deliver one `user.deleted`), every secret ARN
  resolves, and one client's address reaches the gateway's rate limit through the ALB and the
  frontend (`GATEWAY_TRUSTED_PROXIES`).
- **Hand-off** H36.6 → Day 34: the collector sidecar goes into `modules/service`; it moves
  `TRACING_EXPORT_ENABLED` and `OTEL_TRACES_ENDPOINT` off C36.8's compose-only list.
