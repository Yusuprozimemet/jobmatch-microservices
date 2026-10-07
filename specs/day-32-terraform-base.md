# Day 32 — Terraform base: state, network and the data stores

**Phase:** 7 · **Depends on:** Day 42 · **Expected PRs:** 4

## Goal
The long-lived resources the services' data lives in (RDS, the score table, the `user.deleted`
bus with its dead-letter queues and alarm) and the network around them are described in
Terraform with remote, locked state, and every one of them is checked without an AWS account.

## In scope
- `infra/terraform/bootstrap/`: the state bucket (versioned, public access blocked, encrypted)
  and a monthly AWS budget with an alarm. Local state: it is what the remote state lives in, and
  it is applied first and destroyed last (Day 35).
- `infra/terraform/` (the root Day 35 applies): an S3 backend in the bootstrap's bucket with
  `use_lockfile = true` (no DynamoDB lock table), Terraform and the AWS provider pinned, one
  environment. No workspaces: `plan.md` deploys one environment once. No `skip_*` flag in its
  provider block: those belong to CI's override (below), not to the deployment.
- Modules under `infra/terraform/modules/`, called from that root:
  - `network`: a VPC over two availability zones (given as a variable, not a data source, so a
    plan needs no account), public and private subnets, and the security groups the data stores
    need: the database's, and the tasks' that Day 33 attaches to services. No NAT gateway (see
    Notes).
  - `database`: one RDS for PostgreSQL instance, major version 18 as in compose, in the private
    subnets, not publicly accessible, `manage_master_user_password = true` so the master password
    lives in Secrets Manager and never in state; ingress on 5432 from the tasks' security group
    only. The three databases and their roles are not Terraform's: `db-setup.py` makes them, as
    it does today (H28.2, below).
  - `bus`: the `user-deleted` SNS topic; `applications-user-deleted` and
    `matching-user-deleted`, each with a `-dlq` and a redrive policy of `maxReceiveCount` 5; raw
    message delivery on both subscriptions; a queue policy on each queue that lets only that topic
    send (`aws:SourceArn`); a DLQ retention decided for a message that holds a `userId` (H28.4);
    one CloudWatch alarm per DLQ on `ApproximateNumberOfMessagesVisible > 0`, notifying an alarm
    topic with an email subscription.
  - `scores`: the `job_match_scores` DynamoDB table, `PAY_PER_REQUEST`, partition key
    `skills_hash` and sort key `posting_scorer` (strings), TTL on `ttl`: what
    `ScoreStoreConfig` creates in compose today.
- `infra/terraform/localstack/`: a root that calls `bus` and `scores` with the provider pointed at
  a LocalStack 4.14.0 (compose's pin) running `s3,sns,sqs,dynamodb,cloudwatch,sts,iam`, and keeps
  its state in an S3 bucket on that LocalStack with the main root's backend settings plus the
  endpoint and `use_path_style`. A CI step makes the bucket (`awslocal s3 mb`) before `init`.
  Track A lands the root with its backend and provider only; Track C adds the module calls.
- `scripts/infra-checks.py` (boto3 for the LocalStacks): reads a plan's JSON
  (`terraform show -json`) and, given LocalStack endpoints, the applied resources, and fails on
  any criterion below it covers. It reads what it expects from where the code keeps it, so a
  change on either side fails it:
  - names, `maxReceiveCount` and raw delivery from `scripts/bus-init/10-user-deleted.sh`
    (applied in compose's own LocalStack) and from the harness's `support/EventBus.java`
    (`TOPIC`, the queue names, `MAX_RECEIVES`);
  - the queue names in compose's `EVENTS_USER_DELETED_QUEUE_URL`s;
  - the keys and TTL attribute from `ScoreStoreConfig.java` (`PARTITION_KEY`, `SORT_KEY`,
    `TTL_ATTRIBUTE`), and the table name from matching's `application.yaml`
    (`${SCORES_TABLE:job_match_scores}`).

  Attributes are compared after parsing: the redrive policy as JSON with numbers normalised
  (bus-init stores `"5"`, Terraform `5`), and only the attributes the criteria list.
- `scripts/bus-init/10-user-deleted.sh` sets the same DLQ retention, so compose and Terraform
  agree.
- `.gitignore`: `.terraform/`, `*.tfstate*`, plan files and the CI override files;
  `.terraform.lock.hcl` is committed.
- `.github/workflows/infra-ci.yaml`, on PRs and pushes touching `infra/**`, `scripts/bus-init/**`,
  `scripts/infra-checks.py`, `docker-compose.yml`, matching's `ScoreStoreConfig.java` and
  `application.yaml`, identity's `support/EventBus.java` or the workflow itself:
  - `fmt -check`, and `validate` on all three roots;
  - a plan of the main root and the bootstrap with no account: CI writes uncommitted
    `ci_override.tf` files (`backend "local" {}` where there is a backend, and a `provider "aws"`
    with `skip_credentials_validation`, `skip_requesting_account_id`, `skip_metadata_api_check`),
    runs `init -reconfigure` and plans with fake keys;
  - the LocalStack apply twice, and the lock test;
  - `infra-checks.py` on all of it.

  No AWS credentials, and no job that applies to AWS.

## Out of scope
- ECR, the ECS cluster, task definitions and roles, the ALB, its domain and ACM certificate, the
  application secrets in Secrets Manager — Day 33.
- The code changes ECS needs first: signing keys from the secret's content, Flyway as a
  migrate-and-exit task, a non-interactive `db-setup.py` — Day 33.
- The ADOT collector, CloudWatch logs and X-Ray — Day 34.
- Any apply against AWS, the mart seed, the deployment and `terraform destroy` — Day 35.
- The uploads bucket — Phase 6, which joins this Terraform if it runs.
- The data platform. It stays on Azure; nothing crosses into the VPC.

## Tracks

| Track | Owner | Work |
|---|---|---|
| A | | `bootstrap/`, the main root's backend and versions, the `localstack/` root's backend and provider, `.gitignore`, `infra-ci.yaml`, `infra-checks.py` with its plan reader and secret sweep (C32.1–C32.3, C32.5's sweep) |
| B | | `network` and `database`, their plan checks (C32.4, C32.5's break) |
| C1 | | `scores` in both roots, the second apply (C32.9, C32.10) |
| C2 | | `bus` in both roots, the retention in `bus-init`, the compose LocalStack in CI (C32.6–C32.8, C32.12) |

In this order, each from fresh `main`. No Track 0: no Java test is involved, and C32.12's proof
goes in C2's PR with the `bus-init` change.

## Acceptance criteria
- [ ] C32.1 **new** — `terraform fmt -check -recursive infra/terraform` passes, and
      `terraform init -backend=false && terraform validate` passes in `bootstrap/`, the main
      root and `localstack/`. Red today: there is no `infra/` directory. Seen failing: a
      misindented block makes `fmt -check` exit 3 and the CI run red.
- [ ] C32.2 **new** — The bootstrap plan has one S3 bucket with versioning `Enabled`, all four
      public-access blocks true and server-side encryption, and one `aws_budgets_budget`
      (`COST`, `MONTHLY`, its limit a variable) with a notification to an address that is a
      variable. `infra-checks.py` checks it on the plan JSON. Red today: no plan. Seen failing:
      versioning `Suspended` → the check names the bucket.
- [ ] C32.3 **new** — State is remote and locked. The main root's backend is `s3` with
      `use_lockfile = true` and its bucket is the bootstrap's (grep). On LocalStack, while one
      `terraform apply` in `localstack/` waits at its prompt, a second
      `terraform plan -lock-timeout=0s` there fails with `Error acquiring the state lock`, and
      succeeds once the first is answered `no` (that apply exits 1; the step expects it). Red
      today: no backend. Tried by the auditor on LocalStack 4.14.0 and Terraform 1.16.3: the
      second plan failed with `412 PreconditionFailed`, and passed after.
- [ ] C32.4 **new** — The main root's plan has one `aws_db_instance` with engine `postgres`,
      `engine_version` 18, `publicly_accessible = false`, `manage_master_user_password = true`,
      a null `password`, a subnet group of the private subnets only, and a security group whose
      only ingress is 5432 from the tasks' security group (no CIDR). `infra-checks.py` checks
      each. Red today: no plan. Seen failing: `publicly_accessible = true` → the check names it.
- [ ] C32.5 **new** — No secret value is in a plan JSON or the LocalStack state:
      `infra-checks.py` sweeps them for AWS access-key ids (`AKIA`/`ASIA` followed by 16
      characters), private-key headers and any argument named `password` with a non-null value.
      It reads values, not `after_sensitive`, which marks `password` even when it is null; and
      it looks for secrets, not for the word. Red today: no script. Seen failing (B's PR): a
      literal `password = "..."` on the instance → the sweep names the attribute.
- [ ] C32.6 **new** — After the LocalStack apply, the topic, both queues, both DLQs, the redrive
      policies (`maxReceiveCount` 5), raw delivery on both subscriptions and the DLQ retention
      are what `bus-init` creates in compose's LocalStack, and the names and `maxReceiveCount`
      are `EventBus.java`'s. CI starts both LocalStacks; `infra-checks.py` lists any difference.
      Red today: no Terraform. Seen failing: `maxReceiveCount` 4 in `bus-init` → the check
      names the queue and the attribute.
- [ ] C32.7 **new** — Each queue's policy allows `sqs:SendMessage` to the principal
      `sns.amazonaws.com` only with `aws:SourceArn` equal to the topic's ARN; checked on the
      plan JSON, since LocalStack does not enforce queue policies. Red today: no plan. Seen
      failing: the condition removed → the check names the queue.
- [ ] C32.8 **new** — One CloudWatch alarm per DLQ on `ApproximateNumberOfMessagesVisible`,
      threshold 0, `GreaterThanThreshold`, its action an SNS topic with an email subscription
      to an address that is a variable. Checked on the plan JSON. Red today: no plan. Seen
      failing: `GreaterThanOrEqualToThreshold` → the check names the alarm.
- [ ] C32.9 **new** — After the LocalStack apply, the score table has the name in matching's
      `application.yaml` and the key schema, attribute types, billing mode and enabled TTL
      attribute that `ScoreStoreConfig` creates. Red today: no table. Seen failing: `SORT_KEY`'s
      value changed in the source → the check names the key.
- [ ] C32.10 **new** — A second `terraform apply` in `localstack/` with nothing changed prints
      `No changes.`; CI fails otherwise. Red today: no root. Seen failing: a tag set to
      `timestamp()` → the second apply shows a change and the step fails.
- [ ] C32.11 **new** — `infra-ci.yaml` runs C32.1–C32.10 on a PR touching any path In scope
      lists, with no AWS secret in the workflow and no job that applies outside LocalStack
      (`grep -n "apply" .github/workflows/infra-ci.yaml` shows only the LocalStack steps). Red
      today: no workflow. Seen failing: the `fmt` break in C32.1 turns the run red; a PR that
      changes only `ScoreStoreConfig.java`'s `SORT_KEY` value runs it and fails C32.9.
- [ ] C32.12 **hold** — Compose's event bus still comes up with the retention added to
      `bus-init`: with the maintainer's stack down (compose pins the network name
      `finalproject`, so `-p` does not isolate it), `docker compose -p day32 --env-file
      .env.example up -d --wait localstack` reports `SUCCESSFUL` at `/_localstack/init/ready`,
      and `awslocal sqs list-queues` lists the four queues; then `down` without `-v`. The
      harness builds its bus with the SDK, not `bus-init`, so no Java test covers this. No file
      under `services/` changes on Day 32 (`git diff --stat main...HEAD -- services/` is empty
      per PR). Passes today (auditor: four queues, `SUCCESSFUL`). The auditor's break, on a
      scratch copy: a stray `"` in the attribute JSON → `"state": "ERROR"`, exit code 2, no
      queues. C2's PR repeats it on the changed script.

## Verify
```bash
# Terraform is not installed on the maintainer's machine: run the image infra-ci pins.
# Git Bash needs MSYS_NO_PATHCONV and a Windows path for the mount.
tf() { MSYS_NO_PATHCONV=1 docker run --rm -v "$(pwd -W 2>/dev/null || pwd)":/w -w /w/"$1" \
  hashicorp/terraform:1.16.3 "${@:2}"; }
tf infra/terraform fmt -check -recursive
for root in infra/terraform/bootstrap infra/terraform infra/terraform/localstack; do
  tf "$root" init -backend=false -input=false && tf "$root" validate
done
# The plans, the LocalStack applies, the lock test and infra-checks.py are what
# infra-ci.yaml runs; its steps are the commands to copy.
```

## Notes
- **Rewritten before Day 32** for ECS on Fargate (`plan.md`, course corrections after Phase 5
  and before Phase 7). The Azure and Kubernetes draft (Key Vault, a blob backend, a cluster,
  `dev` and `prod`) is gone. Its two sharp edges: the Databricks firewall no longer applies
  (nothing crosses from Azure), and the Google redirect URI goes to Day 33 with the domain.
- **Hand-offs picked up.**
  - H28.2: no consolidated baseline (`plan.md`, before Phase 7). A fresh RDS starts from
    `db-setup.py` and V1–V16 as compose does, so the `matching` and `applications` roles and
    `MATCHING_DB_PASSWORD` stay. Day 33 makes `db-setup.py` non-interactive.
  - H28.4: the topic, queues, DLQs, their retention, the depth alarm and the score table are
    C32.6–C32.9. `apps_db` is a database on the one RDS instance (C32.4), created by
    `db-setup.py`, not by Terraform: a departure from H28.4's wording.
  - H28.17: decided before Phase 7: the deployed `jobs_db` is seeded from the test mart. The
    seed runs at deployment (H32.1).
  - H42.3 (stale docs under `services/`): passed to Day 33 as H32.2, because this day changes
    nothing under `services/` (C32.12) and Day 33 edits those services anyway.
  - Items from before Day 28's IDs that name Day 32: Day 20's separate database servers (one
    instance, decided below); Day 22's table in Terraform (`scores`) and `create-table` false
    in production (already the default, `application.yaml:37`; Day 33's task definition keeps
    it unset); Day 23's matching needs no database (three databases, none for matching); Day
    24's `PROFILE_CACHE_WINDOW` (Day 33, with H28.5); Day 26's topic, queues and DLQ alarm in
    Terraform (`bus`). `ScoreStoreConfig.java:30`'s comment that production's table is
    Terraform's is `scores`.
- **Decisions for the maintainer in the spec-change PR:**
  - DLQ retention: 14 days (1,209,600 s, the SQS maximum), so a failed deletion can be
    redriven after a weekend; the message holds only the `userId` of a user already deleted
    from identity. The main queues keep the 4-day default.
  - No NAT gateway (about $35 a month before traffic: $0.048 an hour). Day 33's tasks run in the
    public subnets with a public IP and a security group that admits only the ALB; RDS stays
    private. The alternative is one NAT gateway, or VPC endpoints for ECR, Secrets Manager and
    CloudWatch, which still leave the LLM API and Google unreachable.
  - One RDS instance, `db.t4g.micro`, single-AZ, no deletion protection and a skipped final
    snapshot: it lives for one deployment and `destroy` must leave nothing billable (Day 35).
    PostgreSQL 18 on that class is not verified (it needs an account): Day 35 checks it with
    `aws rds describe-orderable-db-instance-options` before the apply.
  - H42.3 to Day 33 rather than a docs track here.
- **For Day 33:** RDS admits only the tasks' security group, so `db-setup.py` and Flyway reach
  it only as ECS tasks in that group, not from the maintainer's machine.
- **Audit (spec-auditor, on this rewrite):** 13 findings, 4 blocking, all taken into this text:
  A could not meet C32.1 and C32.3 without C's root (A now lands the root's skeleton); the
  code-side paths never triggered infra-ci, and the table name is in `application.yaml`, not
  `ScoreStoreConfig`; H42.3 and the Day 20–26 items were not cited; Verify failed in Git Bash
  and had a placeholder tag. Also: `init -backend=false` cannot plan (override files), the
  redrive policy's `"5"` against `5`, the harness copy uncompared, missing breaks, `.gitignore`,
  the estimate, compose's fixed network name, and H32.1 naming one file of two.
- **Defect** — found: auditor · cause: spec · the rewrite's C32.12 named a test that does not
  exist (`UserDeletedFanOutIT`), caught before the audit by the main session; and the 13
  findings above. Fixed in this spec-change PR.
- **Hand-off** H32.1 → Day 35: seed `jobs_db` with `analytics-schema.sql` then
  `analytics-seed.sql`, as `analytics_user` (identity's README, "Running it"), as a one-off
  task before the services start (H28.17's decision), so `GET /api/jobs` answers from data.
- **Hand-off** H32.2 → Day 33: H42.3's docs: the gateway's `application.yaml` comments ("the
  monolith's remainder", "until Track E1"), identity's README `../mvnw` (the wrapper is
  `../../mvnw`), and `project_db` in identity's `docs/configuration.md`.
