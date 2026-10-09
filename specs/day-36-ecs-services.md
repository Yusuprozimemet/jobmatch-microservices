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

- [x] C36.1 **new** — Seven `aws_ecr_repository`, one per image (the five services, `frontend`,
      `db-setup`), each with `force_delete = true`. Red today: none in the plan.
      Met (#366). `check_c36_1` finds the seven repositories, each `force_delete = true`; green in infra-ci on `main` at `9f501f7` (run 37996683202, `infra-checks: ok`).
      broken: `force_delete = false` in `ecr.tf` → `C36.1 aws_ecr_repository.image["api-gateway"]: force_delete False, want true` (and the other six) (#366)
- [x] C36.2 **new** — One `aws_ecs_cluster`; six `aws_ecs_service` (the five services and the
      frontend), each from `module.service`, with `launch_type = "FARGATE"`, `assign_public_ip
      = true`, the public subnets only and the tasks security group only; each task definition
      `requires_compatibilities = ["FARGATE"]`, `network_mode = "awsvpc"`. The `services`
      output has the same six names. Red today: none in the plan; no `services` output.
      Met (#366). `check_c36_2` reads the six services from `module.service` and the `services`
      output; green in infra-ci on `main` at `9f501f7` (run 37996683202, `infra-checks: ok`).
      broken: `assign_public_ip = false` in `modules/service` → `C36.2 module.service["api-gateway"].aws_ecs_service.service: assign_public_ip False, want true` (and the other five) (#366)
      broken: `subnet_ids = module.network.private_subnet_ids` → `C36.2 aws_ecs_service subnets ['module.network.aws_subnet.private'], want the public subnets` (#366)
- [x] C36.3 **new** — The fourteen secrets in In scope are `aws_secretsmanager_secret`s, and
      the plan has no `aws_secretsmanager_secret_version`. Every secret a service names in
      `services` is one of them, and every role password `db-setup` needs is named by it. No
      environment variable's name matches `PASSWORD|SECRET|PRIVATE_KEY|ACCESS_KEY` and no value
      is a PEM. Red today: no secrets, no `services` output.
      Met (#367); the environment sweep covers the one-off tasks since #373. Until #372 filled the
      environment, its name and PEM rules were seen failing only on a mutated plan (#367); since
      #372 they run on real values. Green in infra-ci on `main` at `9f501f7` (run 37996683202, `infra-checks: ok`).
      broken: an `aws_secretsmanager_secret_version` for `llm-api-key` → `C36.3 aws_secretsmanager_secret_version.llm: secret version in the plan, want none` (#367)
- [x] C36.4 **new** — Each task definition has an execution role and a task role. The execution
      role reads only the secrets that task names; its only `*` is `ecr:GetAuthorizationToken`.
      No task role has a `*` resource or action; identity's reaches only the topic, matching's
      only the scores table and `matching-user-deleted`, application-service's only
      `applications-user-deleted`; job-service, the gateway and the frontend have none. No
      environment variable is `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `*_ACCESS_KEY`,
      `*_SECRET_KEY` or `*_ENDPOINT` (H28.5). Red today: no roles.
      Met (#367); the three one-off tasks have both roles too (#373). Review added the check that
      `secret_arns` is built from `each.value.secrets`: the draft caught "every secret to every
      task" only through the policy count. Green in infra-ci on `main` at `9f501f7` (run 37996683202, `infra-checks: ok`).
      broken: `secret_arns` = every secret's ARN for every task (`ecs.tf`) → `C36.4 aws_iam_role_policy.secrets for [all six], want [the four]` and `C36.4 module service: secret_arns not built from each.value.secrets, want only that task's secrets` (#367)
      broken: the logs statement's resource `"*"` (`modules/service`) → `C36.4 execution policy: * resource for ['logs:CreateLogStream', 'logs:PutLogEvents'], want only ecr:GetAuthorizationToken` and `2 statements with a * resource, want 1` (#367)
      broken: matching's DynamoDB statement also on `user-deleted-topic` (`locals.tf`) → `C36.4 matching-service: task policy resources ['matching-user-deleted', 'scores-table', 'user-deleted-topic'], want ['matching-user-deleted', 'scores-table']` (#367)
- [x] C36.5 **new** — One `aws_lb` (application, internet-facing, the public subnets); a 443
      listener with `HTTPS` and the `aws_acm_certificate` for `var.domain`, validated by
      `aws_route53_record`s in `var.route53_zone_id`; an 80 listener whose default action
      redirects to 443; one target group, the frontend's, port 3000, `target_type = "ip"`,
      health check path `/`. Red today: no `aws_lb`.
      Met (#369). The target group is passed to `modules/service` as a list, so the frontend's
      `load_balancer` block is known in a plan without an account. Green in infra-ci on `main` at `9f501f7` (run 37996683202, `infra-checks: ok`).
      broken: the target group on port 8080 (`alb.tf`) → `C36.5 aws_lb_target_group.frontend: port 8080, target_type ip, health path /, want 3000, ip and /` (#369)
      broken: the 80 listener forwarding instead of redirecting → `C36.5 aws_lb_listener.http: default action forward, want redirect to 443 HTTPS` (#369)
      broken: the gateway on the target group too (`ecs.tf`) → `C36.5 module.service["api-gateway"].aws_ecs_service.service: load_balancer [('api-gateway', 8081)], want []` (#369)
- [x] C36.6 **new** — The ALB's security group admits 80 and 443 from `0.0.0.0/0` and nothing
      else. The tasks security group has exactly two ingress rules: 3000 from the ALB's group,
      and all ports from itself. C32.4 (RDS admits only the tasks) stays green. Red today: the
      tasks group has no ingress rule (`modules/network/main.tf:78-98`) and there is no ALB group.
      Met (#369); C32.4 stays green in the same run. Green in infra-ci on `main` at `9f501f7` (run 37996683202, `infra-checks: ok`). The rule "all ports
      from itself" is a known limit (Notes).
      broken: tasks-from-tasks by `cidr_ipv4 = "10.0.0.0/16"` (`modules/network`) → `C36.6 ...tasks_from_tasks: a CIDR source, want the security groups only` and `C36.6 ...aws_security_group.tasks: ingress sources ['alb'], want the ALB on 3000 and the tasks on all ports` (#369)
      broken: a third ALB ingress rule, 22 from anywhere → `C36.6 module.network.aws_security_group.alb: 3 ingress rules, want 2 (80 and 443)` and `ingress ports ['22', '443', '80'], want 80 and 443` (#369)
- [x] C36.7 **new** — Each of the five JVM services' health checks in `services` probes
      `/actuator/health/readiness` on 9090; the frontend's has none (the target group checks it).
      Red today: no `services` output.
      Met (#368). Each probe is compose's, byte for byte; interval and start period are wider (ECS's
      minimum is 5 s). Green in infra-ci on `main` at `9f501f7` (run 37996683202, `infra-checks: ok`).
      broken: the probe on port 9091 (`locals.tf`) → `C36.7 <service>: health_check [... /dev/tcp/127.0.0.1/9091 ...], want CMD running the readiness probe on 9090` for all five (#368)
      broken: `healthCheck` built from a fixed local instead of `var.service.health_check` (`modules/service`) → `C36.7 module service: container_definitions does not reference var.service.health_check` (#368)
- [x] C36.8 **new** — One Service Connect namespace; each of the five services is registered
      under its compose name and port. Every internal URL in `services` (`*_SERVICE_URL`,
      `INTERNAL_*_URL`, `JOB_SERVICE_KEY_SET_URL`, `BACKEND_API_URL`) is
      `http://<compose name>:<port>` of a registered service. Every variable compose sets for a
      service is in its `services` entry or on the compose-only list in `infra-checks.py`, each
      with its reason: the `*_FILE` key paths (the content variables replace them), the
      emulator endpoints and keys, `SCORES_CREATE_TABLE`, `TRACING_EXPORT_ENABLED` and
      `OTEL_TRACES_ENDPOINT` (Day 34, H36.6). The check reads `docker-compose.yml`. Red today:
      no `services` output.
      Met (#372). Review registered a service only after its own checks pass (the draft counted a
      service missing from the plan as registered) and added a check that the frontend is a
      Service Connect client. Green in infra-ci on `main` at `9f501f7` (run 37996683202, `infra-checks: ok`).
      broken: matching's `INTERNAL_JOBS_URL` on `job-service:8081` → `C36.8 matching-service INTERNAL_JOBS_URL: http://job-service:8081, want http://<registered name>:<its port>[/path]` (#372)
      broken: `EVENTS_CONSUMER_ENABLED` dropped from application-service → `C36.8 application-service: compose sets EVENTS_CONSUMER_ENABLED, not in its services entry or C36_COMPOSE_ONLY` (#372)
- [x] C36.9 **new** — In `services`: identity has `SESSION_COOKIE_SECURE = "true"`,
      `APP_BASE_URL = "https://${var.domain}"`, and `GOOGLE_REDIRECT_URI` unset or on that
      domain; identity and application-service have `MIGRATE_ON_START = "false"`; matching has
      no `SCORES_CREATE_TABLE` and a `PROFILE_CACHE_WINDOW`; the gateway's
      `GATEWAY_TRUSTED_PROXIES` matches an address in each public subnet and none in the private
      ones. Red today: no `services` output.
      Met (#372). Whether the gateway sees the frontend's address through Service Connect's proxy,
      so that the trusted-proxy match ever applies, is H36.5's. Green in infra-ci on `main` at `9f501f7` (run 37996683202, `infra-checks: ok`).
      broken: `GATEWAY_TRUSTED_PROXIES = "10.0.*"` → `C36.9 GATEWAY_TRUSTED_PROXIES matches 10.0.128.10 in private subnet 10.0.128.0/18` (and 10.0.192.10) (#372)
- [x] C36.10 **new** — Three one-off task definitions and no service for any of them:
      identity's and application-service's migrate (their service's image, `MIGRATE_ONLY =
      "true"`), and `db-setup` (the `db-setup` image, `--passwords-from-env`, the master
      secret's `:password::` as `POSTGRES_PASSWORD`, the seven `DB_PASSWORD_<ROLE>`).
      `db-setup-tests.yml` builds `scripts/db-setup.Dockerfile` and runs `--help` in it. Red
      today: no task definitions; no such Dockerfile.
      Met (#373), in `modules/task`, read from a `tasks` output. Green in infra-ci on `main` at `9f501f7` (run 37996683202, `infra-checks: ok`). The
      image step first ran green after the merge: `db-setup-tests` on `6088480` (run 37993731718)
      failed twice on Docker Hub before reaching it (a pull rate limit, then an `auth.docker.io`
      timeout) and passed on attempt 3, the `docker build` and the `--help` run both green.
      broken: `MIGRATE_ONLY = "false"` on application-service-migrate → `C36.10 application-service-migrate MIGRATE_ONLY false, want true` (#373)
      broken: `rds-master` mapped to `aws_secretsmanager_secret.secret["db-password-app_user"].arn` → `C36.10 module task: secret_arns_by_name does not reference module.database.master_user_secret_arn` (#373)
      broken: the `--help` step reduced to a bare `docker run` → `C36.10 db-setup-tests.yml: no step runs --help` (#373)
- [x] C36.11 **new** — The gateway's service has `desired_count = 1` and no
      `aws_appautoscaling_target`; matching's has one, and an `aws_appautoscaling_policy` of type
      `TargetTrackingScaling` on `ECSServiceAverageCPUUtilization`. Red today: no services.
      Met (#374). Matching scales from 1 to 3 tasks at 70% CPU. Green in infra-ci on `main` at `9f501f7` (run 37996683202, `infra-checks: ok`).
      broken: main's Terraform with this check → 4 failures (no target, no policy, matching scaling not set, plus the count of targets) (#374)
      broken: a `scaling` block on `api-gateway` → 5 failures (2 targets, 2 policies, a target and a policy under the gateway, the gateway's scaling set) (#374)
- [x] C36.12 **hold** — Day 32's criteria stay green in infra-ci: `fmt -check`, the plans
      without an account, the lock test, the LocalStack apply and the second apply's
      "No changes.", `infra-checks.py` on C32.2–C32.10. Break: a `timestamp()` tag in
      `modules/scores`, as C32.10 was broken; the second-apply step must fail.
      Held. Every step of infra-ci passed on each of the seven track PRs and on `main` at `9f501f7`
      (run 37996683202): fmt, validate, the plan without an account, the lock test, the LocalStack
      apply, the second apply, and `infra-checks.py`. The spec change took #349's break as this
      hold's; since #349 the LocalStack root gained the bus module (Day 32's C2a and C2b), so the
      close ran the break again, on a copy of `infra/terraform` against `localstack/localstack:4.14.0`
      with `hashicorp/terraform:1.16.3`, the step's commands unchanged.
      broken: a tag `Applied = timestamp()` in `modules/scores` → the second apply showed `~ tags` (`- "Applied" = "2026-10-09T22:08:54Z"`) and `Plan: 0 to add, 1 to change, 0 to destroy.`; the step's `grep -q "No changes."` exited 1 (close)

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
- **Track order: A (#366), B (#367), C1 (#368), C2 (#369), D1 (#372), D2a (#373), D2b (#374),**
  the spec's order. Estimated 8 PRs; took 7, in 1,858 track lines. The gate split C at 437 lines
  by criterion (C1: C36.7; C2: C36.5–C36.6), D as the spec planned (D1: C36.8–C36.9), and D2 once
  more at 447 lines (D2a: C36.10; D2b: C36.11). No `Oversized:`. One spec change (#364). The
  dashboard's Infrastructure panel (#370, #371, KAN-119) merged during the day, outside it; #371's
  queued second Dashboard run showed on `9f501f7` (runs 37996683173 and 37997073548, both green).
- **Departures, each recorded in its PR or tick:**
  - **C36.3's and C36.4's environment rules were first seen failing on a mutated plan** (#367):
    the environment was empty until Track D1. They run on real values since #372.
  - **#373 merged with `db-setup-tests` red,** at the maintainer's request: Docker Hub refused
    the `postgres` pull, the first rate limit in this repository. C36.10's image step first ran on
    `main`, green on attempt 3 of run 37993731718 (attempt 2 failed on an `auth.docker.io`
    timeout). If it recurs, a tooling PR: `docker/login-action` with a Docker Hub token in every
    workflow that pulls.
  - **C36.12's break was run again at the close** (its tick): the spec change had taken #349's.
- **Known limits, not changed on this day:**
  - **`tasks_from_tasks` admits all ports,** so any task, the frontend's included, can reach a
    service directly on its port and its actuator on 9090. That bypasses the gateway's rate
    limit, not authentication: since Day 41 every service verifies the user's JWT itself and no
    service behind the gateway reads `X-User-Id`; internal endpoints need a service JWT
    (`InternalCallers`); the actuator exposes only `health` and `prometheus`. Fixed only if
    Day 35's testing shows a real flaw.
  - **Matching's service has no `lifecycle { ignore_changes = [desired_count] }`** (#374), so an
    apply resets it to 1 after autoscaling has raised it; `lifecycle` cannot be made conditional
    per service in one module. Day 35's second apply on a real account shows whether it matters.
  - **Service Connect's proxy may present `127.0.0.1` to the gateway,** not the frontend's
    address, so `GATEWAY_TRUSTED_PROXIES` (the public subnets) may never match (#372). Part of
    H36.5's rate-limit check.
  - **Identity's `DB_PASSWORD` is `db-password-app_user`** (#367): `app_user` owns V1–V16 in
    production (`db-setup.py`). Flagged in the PR for review; `DB_USER` matches it (#372).
- **Defect** — found: Track C1 · cause: spec · C36.5–C36.7 in one track came to 437 lines, and
  the spec planned only D's split; C split by criterion (#368, #369).
- **Defect** — found: Track D2a · cause: process · the main session's brief for D2 did not plan
  the split the spec expected; the draft came to 447 lines and was split after it was written
  (#373, #374).
- **Defect** — found: close · cause: spec · the spec change took Day 32's #349 break as C36.12's,
  after the LocalStack root had changed; no PR of the day broke the hold until the close.
- **Defect** — found: review (Track B) · cause: implementation · the draft's C36.4 caught "every
  secret to every task" only through the count of inline policies; review added the check that
  `secret_arns` comes from `each.value.secrets` (#367).
- **Defect** — found: review (Track C) · cause: implementation · the draft keyed the frontend's
  `load_balancer` block on a nullable ARN, unknown without an account, so the plan hid the block
  and the check fell back to `after_unknown`; a list made it known (#369).
- **Defect** — found: review (Track D1) · cause: implementation · the draft's C36.8 counted a
  service missing from the plan as registered, so URLs naming it passed, and did not check the
  frontend as a Service Connect client (#372).
- **Defect** — found: break on purpose (Tracks D1, D2a) · cause: process · twice a break's checks
  read the plan file left by the previous break: in D1 Terraform rejected a single-backslash HCL
  string, in D2a the break needed no new plan. Both were caught by the extra line in the output.
- **Defect** — found: after merge · cause: environment · Docker Hub's pull limit, then a timeout,
  kept `db-setup-tests` red through three runs (#373, run 37993731718).
- **Defect** — found: after merge · cause: tooling · `spec-drift.py`'s next step skipped C2 after
  C1 ("Track D"), and after D1 read a criterion id as a track ("Track C32: announced in #366"),
  as on Day 32.
- **Rework:** review changed three of the five implementer drafts (B, C, D1); A and D2 merged as
  drafted. All were written on `claude-haiku-5-5`, its first day; for A, B and C the PRs record
  that its report matched what the main session reran.
