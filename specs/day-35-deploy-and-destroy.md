# Day 35 — Deploy once, then `terraform destroy`

**Phase:** 7 · **Depends on:** Day 34 · **Expected PRs:** 11, and one per fix the deployment needs

Rewritten from the Helm draft `day-35-remaining-charts.md` for ECS (`plan.md`). Phase 7's last day.

## Goal
One real deployment, applied from `main` under the budget alarm, serves the five public API
surfaces at `https://<domain>` with `jobs_db` seeded from the test mart, every hand-off that needs
an account is checked on it, and `terraform destroy` leaves nothing billable but the hosted zone
and the bootstrap.

## How the day runs
First the code the deployment needs (Tracks A1–E, checked in CI without an account). Then the
deployment (no track), on `main` after E, by `docs/runbooks/deploy.md`; its evidence (commands and
output) goes in the closing PR. A defect it finds gets a PR `day-35/fix-<slug>`, `Day 35 fix: <what
was wrong>`, and the step is run again (`plan.md`: "again, if a fix needs it"). Priority if time or
budget runs short (2026-10-09): reachability, migrations, teardown; the rest is recorded as not
done. Cost per hour: the dashboard's $0.227 plus Day 34's metrics (~$0.03), about $6 a day.

## Preconditions the maintainer supplies
Not work for a track; the deployment does not start without them:
- An AWS account, with credentials on the maintainer's machine for `terraform apply`.
- A domain and its Route 53 zone (`domain`, `route53_zone_id`), NS records delegated to it, or the
  apply waits on `aws_acm_certificate_validation` until it times out. The zone is made outside
  Terraform and stays ($0.50 a month). Chosen later (2026-10-10); the code tracks do not wait.
- `alert_email` (H32.4): the maintainer's address, for the budget and the alarm topic. The budget's
  limit stays 20 USD.
- Google's OAuth client with `https://<domain>/login/oauth2/code/google` as a redirect URI, and an
  LLM API key (H36.1).
- A second network address for C35.18's rate-limit check (AWS CloudShell, or a phone's hotspot).

## In scope
- **The domain's record.** An `aws_route53_record` alias, `domain` → the ALB, in
  `route53_zone_id`. Today only the certificate's validation records exist, so the certificate
  would validate and `https://<domain>` would not resolve.
- **Rollback.** `deployment_circuit_breaker { enable = true, rollback = true }` on the service in
  `modules/service` (the maintainer's, 2026-10-09). Migrations stay forward-only, so each must work
  with the previous image: the rollback swaps images, never the schema.
- **Services start only when told.** A root variable `start_services` (bool, default `false`).
  While it is `false` every service's `desired_count` is 0 and matching has no scaling target or
  policy (its `min_capacity` of 1 would start a task). The first apply leaves it `false`; the
  images and the one-off tasks come first (H36.3, H36.4); the next apply sets it `true`. infra-ci
  plans the main root both ways; `infra-checks.py` gains `--started-plan` for the second plan, and
  C36.11's check and `infra-summary.py`'s cost read it (the default plan would price 0 tasks).
- **The `jobs_db` seed** (H32.1): a one-off task `jobs-seed` in `local.tasks` that runs
  `analytics-schema.sql` then `analytics-seed.sql` as `analytics_user` against `jobs_db`. It runs
  the `db-setup` image, which gains `scripts/seed-mart.py` (psycopg, already in the image) and the
  two files, taken at build time from `services/identity-service/app/src/test/resources/fixtures/`
  (a second build context), so the repository holds one copy of each. The image's `ENTRYPOINT` is
  `db-setup.py` and `modules/task` passes only `command`, so the module's `task` object gains an
  optional `entry_point`, and `jobs-seed` sets it. Like compose's `scripts/seed-jobs.sh`, from the
  same fixtures, the seed refuses to run when `analytics.fct_postings` already has rows. This
  changes two Day 36 checks on purpose: C36.10's task set (`C36_TASKS`) and its task-definition
  count go from three to four. `db-setup-tests.yml`'s paths gain the two fixtures and
  `scripts/seed-mart.py`.
- **The deploy's credentials.** In the main root (so the destroy removes them): GitHub's OIDC
  provider, `token.actions.githubusercontent.com`, and one role, `jobmatch-deploy`. Its trust
  policy has `StringEquals` on both `token.actions.githubusercontent.com:sub` =
  `repo:Yusuprozimemet/jobmatch-microservices:ref:refs/heads/main` and `:aud` =
  `sts.amazonaws.com`, so no deploy job may name an `environment:` (it would change `sub`). It may:
  - push to the seven `jobmatch/*` repositories: `ecr:BatchCheckLayerAvailability`,
    `ecr:InitiateLayerUpload`, `ecr:UploadLayerPart`, `ecr:CompleteLayerUpload`, `ecr:PutImage`,
    `ecr:BatchGetImage`; and `ecr:GetAuthorizationToken`, its only `*` resource;
  - call `ecs:UpdateService` and `ecs:DescribeServices` on the `jobmatch` cluster's services;
  - run the two migrate tasks: `ecs:RunTask` on their task definitions, `ecs:DescribeTasks` in the
    cluster, `iam:PassRole` on those two tasks' execution and task roles, and `logs:GetLogEvents`
    on their log groups.
  The trust and the policy are literal locals, read from a root output `deploy` as `services` is
  (Day 36, "How the checks read the plan"), and the check confirms from `configuration` that the
  role's documents refer to those locals (C36.4's pattern), so the output cannot say one thing and
  the role another. No `aws_iam_access_key` anywhere. If the account already has a GitHub OIDC
  provider (preflight), it is imported, not created twice.
- **The deploy job** (H28.11, H36.4, `plan.md`'s "behind a switch"): the six service workflows
  (api-gateway, identity, job, matching, application, frontend) and `db-setup-tests.yml`, each with
  a `workflow_dispatch` trigger. On a push to `main` or a dispatch on `main`, with the repository
  variable `DEPLOY_ENABLED` set to `true`, the image goes to ECR as `jobmatch/<name>:<short sha>`
  and `:latest`, through the role (`id-token: write`, the role's ARN in the repository variable
  `AWS_DEPLOY_ROLE_ARN`). Then:
  - identity and application-service run their migrate task with the new image and wait for exit
    0 (`plan.md`: "Flyway runs as a one-off ECS task before the deploy");
  - each of the six runs `aws ecs update-service --force-new-deployment`.
  With the switch off, the six push to GHCR as today, so merges after the destroy still publish and
  do not fail; `db-setup-tests.yml` pushes nothing, as today. Terraform's `image_tag` stays
  `latest`, so a deploy changes no task definition and a later plan shows no drift; `variables.tf`'s
  "Day 35's deploy sets it" is reworded. Identity's image is pushed only on identity's own paths:
  today its build runs on changes to any of the five JVM services, because its suite is the contract
  suite (`identity-service-ci-cd.yaml`'s `PATHS`). infra-ci's two path lists gain the seven
  workflows, so the check reruns when one changes.
- **The deployment's scripts** (D1a, D2a), each with `--help`, and each tested in infra-ci against
  a stub `aws` on the `PATH` where it has logic:
  - `scripts/deploy-preflight.sh`, five answers: PostgreSQL 18 is orderable on `db.t4g.micro` in
    the region (`aws rds describe-orderable-db-instance-options`, Day 32's Notes); the state
    bucket's name is free or already the maintainer's (H32.3); whether the account has a GitHub
    OIDC provider; `alert_email` is not `alerts@example.com`; the domain's NS records are the
    zone's.
  - `scripts/write-secrets.sh`: writes the fourteen values (H36.1) with `aws secretsmanager
    put-secret-value`, from a file outside the repository: role passwords generated
    (`openssl rand`), the four signing keys made with `scripts/jwt-key.sh`, Google's and the LLM's
    from the maintainer. It prints names, never values.
  - `scripts/run-once.sh <task>`: runs a one-off task in the tasks' subnets and security group,
    waits for it to stop, prints its container's exit code and the last log lines, and exits
    non-zero unless the code is 0.
  - `infra-checks.py --state <state.json>`: the C32.5 secret sweep on a pulled state, and nothing
    else.
  - `scripts/live-checks.py --domain <d> --out docs/dashboard/deployment.json`: the checks of C35.16
    and C35.17, each a result and a time.
  - `scripts/teardown-check.sh`: lists what is left in the account and region that bills (C35.21),
    and exits non-zero if anything is.
- **The runbook** `docs/runbooks/deploy.md` (D1b), in this order, each step with how to check it
  before the next: preflight; the bootstrap; the budget checked; the main root with
  `start_services=false`; the alarm topic's email subscription confirmed (the frontend's
  `HealthyHostCount` alarm fires now, as expected: `treat_missing_data = "breaching"`); the seven
  images in ECR (`DEPLOY_ENABLED=true`, each workflow dispatched); the secrets; `db-setup`; the two
  migrate tasks; `jobs-seed`; `start_services=true`; the live checks; the audits; the destroy; the
  teardown check. A `deploy.tfvars.example` holds the variables; the real `deploy.tfvars` is
  gitignored.
- **The Deployment panel** (D2b, 2026-10-09): `spec-drift.py` reads `deployment.json` into its
  data; `dashboard.js` shows each check's result and time, or "nothing deployed".
- **Docs** (E). `jobs-db.md`, `identity-db.md` and `copy-saved-jobs.py` are retired (H28.10,
  2026-10-10): each opens with a line that they apply to installations from before Days 20, 25 and
  28, and the deployment is a fresh RDS; nothing is deleted. `docs/architecture.md` draws what was
  deployed from the Terraform (H42.1), each box linking the `.tf` that declares it; a fix PR that
  changes the Terraform updates it. `bootstrap/main.tf`'s "destroyed last (Day 35)" becomes "kept
  after the main root's destroy" (the budget keeps watching; 2026-10-09).
- **The code comments that name Day 35** are picked up where their work is: `ecs.tf:57` and
  `locals.tf:222` (the one-off tasks before the services) by C35.15, `secrets.tf:16` (the values
  written on Day 35) by C35.14, `dashboard.tf:1` (the dashboard Day 35 watches) by C35.19,
  `variables.tf:19` by C35.6, `bootstrap/main.tf:1` by C35.8.

## Out of scope
- Phase 6's functions, with the Helm draft's `cv-parse` path and Day 39's key-set note: after
  Phase 7, or cut (`plan.md`).
- A shared rate-limit store, a second gateway task, NAT, Container Insights, ECS Exec (`plan.md`;
  Days 32, 34); the gateway logging client addresses (C35.18 uses two addresses instead).
- A destroy and re-create from nothing (doubles the cost): not done; C35.20's plan is the check.
- The three new auditor agents: one PR outside the day, after E, before the deployment
  (2026-10-10). Renaming the alarm topic (Day 34's known limit).

## Tracks

| Track | Owner | Work |
|---|---|---|
| A1 | | `start_services`, `--started-plan`, infra-ci's second plan, the summary's cost (C35.3) |
| A2 | | The domain's alias record and the circuit breaker (C35.1, C35.2) |
| B | | `jobs-seed`, `seed-mart.py`, `entry_point`, the image's second build context (C35.4) |
| C1 | | The OIDC provider, the deploy role, the `deploy` output (C35.5) |
| C2a | | The deploy job in the four workflows without migrations and `db-setup-tests.yml`; `check_c35_6`; infra-ci's paths (C35.6) |
| C2b | | Identity's and application-service's deploy jobs with their migrate step; identity on its own paths; `check_c35_6` over all seven (C35.6) |
| D1a | | Preflight, `write-secrets.sh`, `run-once.sh`, `--state`, their stub tests (C35.7) |
| D1b | | `docs/runbooks/deploy.md`, `deploy.tfvars.example` (C35.7) |
| D2a | | `live-checks.py`, `teardown-check.sh`, their stub tests (C35.7) |
| D2b | | The Deployment panel (C35.9) |
| E | | Runbooks retired, the architecture drawing, the bootstrap comment (C35.8) |

In the table's order: A1 first (A2 and C1 edit what it edits), C1 before C2a (the role's ARN), B
and C2b before D1b. Then the deployment, its fixes, and the closing PR.

## Acceptance criteria

### The code (checked in CI without an account)

- [ ] C35.1 **new** — The plan has one `aws_route53_record` of type `A` named by `var.domain` in
      `var.route53_zone_id`, whose `alias` refers to the ALB (`configuration`). `infra-checks.py
      --main-plan` (`check_c35_1`). Red today: the plan's only record is
      `certificate_validation["example.com"]`.
- [ ] C35.2 **new** — Every `aws_ecs_service` in the plan has `deployment_circuit_breaker` with
      `enable` and `rollback` both `true` (`check_c35_2`). Red today: all six have
      `deployment_circuit_breaker: []`.
- [ ] C35.3 **new** — On the default plan every `aws_ecs_service` has `desired_count` 0 and there
      is no `aws_appautoscaling_target` or `_policy`; on the `--started-plan` (`-var
      start_services=true`) the desired counts equal `local.services`' and C36.11 passes
      (`check_c35_3`); infra-ci's summary prices the started plan. Red today: `start_services` is
      not a variable and all six desired counts are 1.
- [ ] C35.4 **new** — `local.tasks` has `jobs-seed` on the `db-setup` image with an `entry_point`
      to `seed-mart.py`, as `analytics_user` on `jobs_db`, reading `db-password-analytics_user`
      (`check_c35_4`; C36.10 updated to four tasks). `db-setup-tests.yml` builds the image with the
      fixtures context, runs `db-setup.py` then the seed against a Postgres container, and
      `jobs_user` counts 24 rows in `analytics.fct_postings`; a second run of the seed exits
      non-zero and leaves 24. Red today: the tasks output is the three Day 36 tasks.
- [ ] C35.5 **new** — The plan has one `aws_iam_openid_connect_provider` for
      `token.actions.githubusercontent.com` and the role `jobmatch-deploy`; the `deploy` output's
      trust has `StringEquals` on `:sub` (only `main` of this repository) and `:aud`, its actions are
      exactly In scope's list, `*` only on `ecr:GetAuthorizationToken`, and the role's documents
      refer to the locals (`configuration`). No `aws_iam_access_key` in any root (`check_c35_5`).
      Red today: no provider in the plan.
- [ ] C35.6 **new** — `check_c35_6` reads the seven workflows: each has `workflow_dispatch`; its
      ECR push, migrate step (identity, application-service) and `update-service` step run only on
      `refs/heads/main` (push or dispatch) with `vars.DEPLOY_ENABLED == 'true'`, and the migrate
      step comes before `update-service`; the job has `id-token: write` and no `environment:`; no
      step passes `aws-access-key-id`; for the six services the GHCR push runs when the switch is
      off, and `db-setup-tests.yml` pushes nothing then; identity's push depends on identity's own
      paths. infra-ci's paths name all seven workflows. Red today: no workflow names
      `DEPLOY_ENABLED`, none of the seven has `workflow_dispatch`, and a change to
      `services/job-service/` alone pushes identity's image.
- [ ] C35.7 **new** — infra-ci runs each new script's `--help`, and its stub tests: with a stub
      `aws`, `write-secrets.sh`'s output holds no value from its input file; `run-once.sh` exits
      non-zero when the stub reports exit code 1 and prints the code; `teardown-check.sh` exits 1 on
      a non-empty listing and 0 on an empty one; `deploy-preflight.sh` prints five answers and exits
      non-zero when one fails; `infra-checks.py --state` fails on a state fixture holding a secret's
      value and passes on one without. `docs/runbooks/deploy.md` has every step In scope lists, in
      that order (`check-links.py` over it). Red today: none of the scripts exists.
- [ ] C35.8 **new** — The three retired files each open with the retired line; `docs/architecture.md`
      exists and every `\b[\w/.-]+\.tf\b` it names exists under `infra/terraform/` (a check in
      infra-ci, as well as `check-links.py`); `bootstrap/main.tf` no longer says "destroyed last".
      Red today: `check-links.py docs/architecture.md` → `no such file`, exit 2; no retired line.
- [ ] C35.9 **new** — `test_spec_drift.py`: with a `deployment.json` fixture the built data's
      `deployment` holds its checks, and without the file it is `None`; `dashboard.js` has the
      panel's "nothing deployed" text (grep). Red today: `grep -ic deployment scripts/spec-drift.py
      scripts/test_spec_drift.py` → 0 and 0.
- [ ] C35.10 **hold** — Every check `infra-checks.py` ran before the day (C32.*, C33.*, C36.*,
      C34.*) still runs and passes in infra-ci, each on the plan it read before, except C36.11 on
      the started plan and C36.10's task count, both changed above; `terraform fmt` and `validate`
      pass for the three roots.
      broken: (each track that touches `infra/terraform/` breaks one existing check once) → (what
      it reported)
- [ ] C35.11 **hold** — No Day 1–4 test is edited, and the five services' suites pass unedited in
      their workflows; C2a and C2b change the workflows that run them, and their PRs show the suites
      ran.
      broken: (in the first fix PR that touches service code; if none does, `broken: none → no PR
      touched service code`)

### The deployment (on the account, evidence in the closing PR)

- [ ] C35.12 **new** — Before the main root's apply: `deploy-preflight.sh` exits 0 with its five
      answers, and `aws budgets describe-budgets` shows the bootstrap's budget at 20 USD notifying
      the maintainer's address (`plan.md`: "under a budget alarm"). Red today: no account.
- [ ] C35.13 **new** — Images before tasks (H36.4, H28.11): with `DEPLOY_ENABLED=true`, each of the
      seven workflows is dispatched on `main` and its run is green; `aws ecr describe-images` shows
      `latest` in all seven repositories; the seven run links are recorded. Red today: no workflow
      has the job.
- [ ] C35.14 **new** — Secrets (H36.1, `secrets.tf:16`): `aws secretsmanager
      list-secret-version-ids` shows an `AWSCURRENT` version for each of the fourteen, written by
      `write-secrets.sh`; `terraform state list | grep -c secret_version` prints 0, and
      `infra-checks.py --state` on the pulled state passes. Red today: no account.
- [ ] C35.15 **new** — Migrations before services (H36.3, `ecs.tf:57`, `locals.tf:222`): `run-once.sh
      db-setup` exits 0; then `identity-service-migrate` exits 0 with Flyway at V16 (`db/migration`)
      and V4 (`identity`), and `application-service-migrate` at V1; then `jobs-seed` exits 0; only
      then `start_services=true`. Red today: no account.
      broken: `identity-service-migrate` run before the first `db-setup` → (its exit code and the
      error `run-once.sh` printed)
- [ ] C35.16 **new** — Reachability (H36.2): `aws acm describe-certificate` shows `ISSUED`;
      `https://<domain>/` returns 200 with the certificate verified; `https://<domain>/api/jobs?size=100`
      returns `totalElements` 24; `http://<domain>/` redirects to HTTPS; each task's public IP on
      3000, 8080, 8081 and 9090 does not answer from outside (`live-checks.py`). Red today: the
      domain does not resolve.
      broken: `live-checks.py` pointed at the ALB's 443 as if it were a task → (it reports the port
      open)
- [ ] C35.17 **new** — The five surfaces (`plan.md`, Phase 0) through the ALB, for a new user
      (`live-checks.py`): `POST /api/auth/register` then `POST /api/auth/login` 200 with a cookie
      `Secure; HttpOnly; SameSite=Lax`; `GET /api/profile` 200 and `PUT /api/profile` 200; `GET
      /api/jobs?q=<a seeded title word>` 200 with results; `POST /api/saved-jobs` then `GET
      /api/saved-jobs` holding it and `PATCH /api/saved-jobs/{postingId}` 200; `GET
      /api/jobs/top-matches` 200. Google sign-in completes at `https://<domain>` by hand. Red
      today: no account.
- [ ] C35.18 **new** — What the plan could not show (H36.5): every service reaches `RUNNING` with
      its desired count, so every secret ARN resolved; `DELETE /api/users/me` publishes one
      `user.deleted` that both consumers process (matching's scores and the saved jobs gone, both
      DLQs empty), so the task roles allow what the services do; client A past
      `RATE_LIMIT_AUTH_PER_MINUTE` (10) logins gets 429, and within that minute client B, from the
      second address, does not (Day 36's Service Connect limit: one shared bucket fails this). A
      denied call or a shared bucket is a fix PR. Red today: no account.
- [ ] C35.19 **new** — Observability (H34.1–H34.5, `dashboard.tf:1`): one request shows in X-Ray as
      one trace across the gateway and the service behind it under their C34.1 names, and its trace
      id in the logs finds it; the `JobMatch` metrics arrive with C34.2's dimensions as deltas and
      every dashboard widget has data, the custom-metric count recorded against the 70–90 estimate;
      with the frontend's task stopped, `HealthyHostCount` alarms and the email arrives; the
      collector's memory is bounded by the task's peak `MemoryUtilization` against the task's
      memory, recorded. Whether a stopped collector is restarted cannot be checked without ECS Exec
      (one container of a task cannot be stopped by API): recorded as not checked. Red today: no
      account.
- [ ] C35.20 **new** — Resilience: matching's task stopped by hand is replaced and top-matches
      answers again (the time recorded); an image that fails its health check, pushed by hand to
      `jobmatch/job-service:latest` and rolled out with `update-service`, is rolled back by the
      circuit breaker while `/api/jobs` stays up, then `latest` is restored to the good image's
      digest; `terraform plan -detailed-exitcode` with the same variables exits 0, or each change is a
      Defect line and a fix PR, or a named departure (matching's `desired_count` after a scale-out,
      Day 36). Red today: no account.
- [ ] C35.21 **new** — Teardown (H34.4): `terraform destroy` of the main root completes, and
      `teardown-check.sh` exits 0: no ECS cluster, service or task, load balancer or target group,
      RDS instance, snapshot or automated backup, Elastic IP, VPC or ENI, ECR repository, secret,
      DynamoDB table, SNS topic, SQS queue, CloudWatch alarm or dashboard, `/ecs/jobmatch/*` or
      `/jobmatch/*` log group, or OIDC provider is left. What stays: the hosted zone, the bootstrap's
      bucket and budget, X-Ray's traces (30 days, free, not deletable). Red today:
      `teardown-check.sh` does not exist.
      broken: `teardown-check.sh` run while the deployment is up → (what it listed, and its exit
      code)
- [ ] C35.22 **new** — Cost: Cost Explorer's daily cost for each day of the deployment, by service,
      recorded against the per-hour estimate, and the first full day after the destroy shows no ECS,
      ELB, RDS, EC2 or VPC charge. Red today: no account.

## Verify
```bash
# Without an account; the plans as infra-ci's "Plan" step makes them (+ -var start_services=true).
python scripts/infra-checks.py --bootstrap-plan bootstrap-plan.json --main-plan main-plan.json \
  --started-plan started-plan.json
python scripts/test_spec_drift.py
python scripts/check-links.py docs/architecture.md docs/runbooks/*.md

# The deployment half: docs/runbooks/deploy.md, step by step. Its checks:
scripts/deploy-preflight.sh
scripts/run-once.sh db-setup
python scripts/live-checks.py --domain "$DOMAIN" --out docs/dashboard/deployment.json
scripts/teardown-check.sh
```

## Notes
- **Decisions (the maintainer's, 2026-10-10):** an OIDC role, not access keys; runbooks retired,
  not rehearsed; the domain later, a variable until then; the audit agents' PR outside the day.
- **Departures from `plan.md`, recorded here:**
  - "The service workflows push there instead of GHCR" (`plan.md`, course correction before
    Phase 7): they push to ECR while the switch is on and to GHCR while it is off, since "the push
    from the service workflows switches with Day 35's deploy job" (same section) and an
    unconditional ECR push would fail after the destroy.
  - "`terraform destroy` leaves nothing billable behind" (Phase 7's Done when): the hosted zone
    ($0.50 a month) and the bootstrap's bucket stay, and the budget with them (2026-10-09).
- **The five end-of-phase audits run before the destroy** (2026-10-09), on Opus: the plan-auditor
  on Phase 7 and on the whole plan (CLAUDE.md's step 6), then the three new auditors.
- **`start_services` defaults to `false`** so a bare apply cannot start services before their
  migrations; every later apply passes `true`, from `deploy.tfvars`.
- **The deploy job pushes `latest`** rather than a task definition per sha, which Terraform would
  see as drift; a task's image is then read from ECR's digest. C35.20 tests the rollback with it.
- **Known limits carried:** `tasks_from_tasks` admits all ports (Day 36: the rate limit, not
  authentication); the profile cache window; one gateway task; the alarm topic's name; public ECR's
  throttle (Day 34). C35.18–C35.20 show whether any is a real flaw.
- **Hand-offs picked up:** H28.10 (C35.8), H28.11 (C35.6, C35.13), H32.1 (C35.4, C35.15), H32.3 and
  H32.4 (C35.12, Preconditions), H34.1–H34.3 and H34.5 (C35.19), H34.4 (C35.21), H36.1 (C35.14),
  H36.2 (C35.16), H36.3 (C35.3, C35.15), H36.4 (C35.6, C35.13), H36.5 (C35.18), H42.1 (C35.8).
  Day 36's Notes: the `tasks_from_tasks` limit, matching's `desired_count` (C35.20), Service
  Connect's address (C35.18), identity's `DB_PASSWORD` as `app_user`'s (C35.15 runs it). Day 32's
  Notes: PostgreSQL 18 on `db.t4g.micro` (C35.12).
- **Stale when Phase 7 ends:** identity's `application.yaml:122`; the closing PR answers
  `docs/lab-notebook.md:953` ("independently deployable") from C35.13.
- **Audit (spec-auditor, Opus, on this rewrite):** 27 findings, 6 blocking, all taken into this
  text; the closing PR lists them. Blocking: the images came after the tasks that run them; the
  `db-setup` image's `ENTRYPOINT` would have swallowed the seed; one client's 429 passes on a shared
  bucket; infra-ci never ran on the workflows `check_c35_6` reads; deploys skipped migrations;
  `db-setup-tests.yml` has no GHCR push to keep.
- **Defect** — found: auditor · cause: spec · the draft's six blocking findings. Fixed here.
