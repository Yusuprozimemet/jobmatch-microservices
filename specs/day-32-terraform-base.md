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
- [x] C32.1 **new** — `terraform fmt -check -recursive infra/terraform` passes, and
      `terraform init -backend=false && terraform validate` passes in `bootstrap/`, the main
      root and `localstack/`. Red today: there is no `infra/` directory. Seen failing: a
      misindented block makes `fmt -check` exit 3 and the CI run red.
      Met on f99d62c: `fmt -check -recursive` exits 0, and `validate` passes in `bootstrap/`, the
      main root and `localstack/` (the Verify above, from a clean `.terraform`); infra-ci's `fmt` and
      `validate` steps are green on every run. #346.
      broken: `bucket` misindented in `bootstrap/main.tf` → `fmt -check` exit 3, naming `bootstrap/main.tf` (#346)
- [x] C32.2 **new** — The bootstrap plan has one S3 bucket with versioning `Enabled`, all four
      public-access blocks true and server-side encryption, and one `aws_budgets_budget`
      (`COST`, `MONTHLY`, its limit a variable) with a notification to an address that is a
      variable. `infra-checks.py` checks it on the plan JSON. Red today: no plan. Seen failing:
      versioning `Suspended` → the check names the bucket.
      `infra-checks.py`'s C32.2 on the bootstrap plan, in infra-ci since #347.
      broken: versioning `Suspended` → `C32.2 aws_s3_bucket_versioning.state (bucket jobmatch-microservices-tfstate): status Suspended, want Enabled` (#347)
- [x] C32.3 **new** — State is remote and locked. The main root's backend is `s3` with
      `use_lockfile = true` and its bucket is the bootstrap's (grep). On LocalStack, while one
      `terraform apply` in `localstack/` waits at its prompt, a second
      `terraform plan -lock-timeout=0s` there fails with `Error acquiring the state lock`, and
      succeeds once the first is answered `no` (that apply exits 1; the step expects it). Red
      today: no backend. Tried by the auditor on LocalStack 4.14.0 and Terraform 1.16.3: the
      second plan failed with `412 PreconditionFailed`, and passed after.
      `infra/terraform/versions.tf:11,15`: `bucket = "jobmatch-microservices-tfstate"`, the
      bootstrap's default, and `use_lockfile = true`; `infra-checks.py` compares the two and rejects
      `skip_*` flags in the main provider. infra-ci's lock test: the second plan failed with `Error
      acquiring the state lock`, the apply answered `no` printed `Apply cancelled`, and the plan after
      it passed. #346 (backends), #347 (checks, lock test).
      broken: `use_lockfile = false` in the main root → `C32.3 main root backend: use_lockfile false, want true` (#347)
      broken: bootstrap bucket default renamed → `C32.3 backend bucket jobmatch-microservices-tfstate is not the bootstrap's bucket jobmatch-other-tfstate` (#347)
      broken: lock test's apply run with `-lock=false` → `ERROR: the second plan got the state lock while apply held it` (#347)
- [x] C32.4 **new** — The main root's plan has one `aws_db_instance` with engine `postgres`,
      `engine_version` 18, `publicly_accessible = false`, `manage_master_user_password = true`,
      a null `password`, a subnet group of the private subnets only, and a security group whose
      only ingress is 5432 from the tasks' security group (no CIDR). `infra-checks.py` checks
      each. Red today: no plan. Seen failing: `publicly_accessible = true` → the check names it.
      `check_c32_4` follows the configuration's references from the instance to its subnets and its
      one ingress rule, since their ids are unknown at plan time. #348.
      broken: `publicly_accessible = true` → `C32.4 module.database.aws_db_instance.main: publicly_accessible True, want False` (#348)
      broken: database given `module.network.public_subnet_ids` → `C32.4 module.network.aws_route_table_association.public: puts a database subnet behind an internet gateway` (#348)
      broken: the 5432 rule from `cidr_ipv4 = "0.0.0.0/0"` → `cidr_ipv4 0.0.0.0/0, want None` and `source [], want the tasks security group only` (#348)
- [x] C32.5 **new** — No secret value is in a plan JSON or the LocalStack state:
      `infra-checks.py` sweeps them for AWS access-key ids (`AKIA`/`ASIA` followed by 16
      characters), private-key headers and any argument named `password` with a non-null value.
      It reads values, not `after_sensitive`, which marks `password` even when it is null; and
      it looks for secrets, not for the word. Red today: no script. Seen failing (B's PR): a
      literal `password = "..."` on the instance → the sweep names the attribute.
      The sweep reads values, not `after_sensitive`: Track B's null `password` passes it. #347
      (sweep), #348 (break).
      broken: an `AKIA…` string in a bucket tag → `C32.5 bootstrap-plan planned_values.root_module.resources[1].values.tags.note: AWS access key ID found` (#347)
      broken: a literal `password = "..."` on the instance → `C32.5 main-plan ...values.password: password has a non-null value (not printing value)` (#348)
- [x] C32.6 **new** — After the LocalStack apply, the topic, both queues, both DLQs, the redrive
      policies (`maxReceiveCount` 5), raw delivery on both subscriptions and the DLQ retention
      are what `bus-init` creates in compose's LocalStack, and the names and `maxReceiveCount`
      are `EventBus.java`'s. CI starts both LocalStacks; `infra-checks.py` lists any difference.
      Red today: no Terraform. Seen failing: `maxReceiveCount` 4 in `bus-init` → the check
      names the queue and the attribute.
      infra-ci starts compose's LocalStack beside the Terraform one; `infra-checks.py` checks each
      against `EventBus.java` and then the two against each other, DLQ retention included. #351
      (with the bus from #350).
      broken: `maxReceiveCount` 4 in bus-init → `C32.6 compose queue applications-user-deleted: maxReceiveCount 4, EventBus.java 5` and `C32.6 queue applications-user-deleted: maxReceiveCount terraform 5, compose 4` (#351)
      broken: `MAX_RECEIVES = 6` in EventBus.java → `C32.6 terraform queue applications-user-deleted: maxReceiveCount 5, EventBus.java 6` (#351)
- [x] C32.7 **new** — Each queue's policy allows `sqs:SendMessage` to the principal
      `sns.amazonaws.com` only with `aws:SourceArn` equal to the topic's ARN; checked on the
      plan JSON, since LocalStack does not enforce queue policies. Red today: no plan. Seen
      failing: the condition removed → the check names the queue.
      Met with a departure: checked on the LocalStack state, not the plan JSON. The policy holds
      the topic's ARN, unknown at plan time without an account (`policy` null, `after_unknown`); the
      state holds the same module's policy as applied. #350.
      broken: the queue policy's `Condition` removed, applied to LocalStack → `C32.7 terraform queue applications-user-deleted: Condition keys [], want ['ArnEquals']` (#350)
- [x] C32.8 **new** — One CloudWatch alarm per DLQ on `ApproximateNumberOfMessagesVisible`,
      threshold 0, `GreaterThanThreshold`, its action an SNS topic with an email subscription
      to an address that is a variable. Checked on the plan JSON. Red today: no plan. Seen
      failing: `GreaterThanOrEqualToThreshold` → the check names the alarm.
      Checked on the main plan; the action is followed through the configuration's references to a
      topic with an `email` subscription whose endpoint is a `var.`. #350.
      broken: comparison_operator `GreaterThanOrEqualToThreshold` → `C32.8 module.bus.aws_cloudwatch_metric_alarm.dlq["applications-user-deleted"]: comparison_operator GreaterThanOrEqualToThreshold, want GreaterThanThreshold` (#350)
- [x] C32.9 **new** — After the LocalStack apply, the score table has the name in matching's
      `application.yaml` and the key schema, attribute types, billing mode and enabled TTL
      attribute that `ScoreStoreConfig` creates. Red today: no table. Seen failing: `SORT_KEY`'s
      value changed in the source → the check names the key.
      Expected values are read from `ScoreStoreConfig.java` and matching's `application.yaml`, not
      written into the script. #349.
      broken: `SORT_KEY = "posting_scorer_v2"` in `ScoreStoreConfig.java` → `C32.9 table job_match_scores: RANGE key posting_scorer, want posting_scorer_v2` (#349)
      broken: `ttl { enabled = false }` in `modules/scores`, applied → `C32.9 table job_match_scores: TTL status DISABLED, want ENABLED` (#349)
- [x] C32.10 **new** — A second `terraform apply` in `localstack/` with nothing changed prints
      `No changes.`; CI fails otherwise. Red today: no root. Seen failing: a tag set to
      `timestamp()` → the second apply shows a change and the step fails.
      infra-ci's step "second apply makes no changes" greps for `No changes.` #349.
      broken: a tag `Applied = timestamp()` in `modules/scores` → the second apply showed `~ tags` and `Plan: 0 to add, 1 to change, 0 to destroy`, and the step's commands exited 1 (#349)
- [x] C32.11 **new** — `infra-ci.yaml` runs C32.1–C32.10 on a PR touching any path In scope
      lists, with no AWS secret in the workflow and no job that applies outside LocalStack
      (`grep -n "apply" .github/workflows/infra-ci.yaml` shows only the LocalStack steps). Red
      today: no workflow. Seen failing: the `fmt` break in C32.1 turns the run red; a PR that
      changes only `ScoreStoreConfig.java`'s `SORT_KEY` value runs it and fails C32.9.
      Met, with the "seen failing" run locally rather than as a red CI run: none of infra-ci's
      12 runs so far was red. Its `paths` name `ScoreStoreConfig.java`, matching's
      `application.yaml`, `EventBus.java`, `scripts/bus-init/**` and `docker-compose.yml`; its only
      credentials are `test`/`test`; `grep -n "apply"` shows only the lock test (`:125-183`) and the two
      LocalStack applies (`:194-202`). The breaks were run through the workflow's own `run:` steps
      (#347). #346 (fmt, validate), #347 (plans, lock test, apply), #349 (second apply), #351
      (compose's LocalStack).
- [x] C32.12 **hold** — Compose's event bus still comes up with the retention added to
      `bus-init`: with the maintainer's stack down (compose pins the network name
      `finalproject`, so `-p` does not isolate it), `docker compose -p day32 --env-file
      .env.example up -d --wait localstack` reports `SUCCESSFUL` at `/_localstack/init/ready`,
      and `awslocal sqs list-queues` lists the four queues; then `down` without `-v`. The
      harness builds its bus with the SDK, not `bus-init`, so no Java test covers this. No file
      under `services/` changes on Day 32 (`git diff --stat main...HEAD -- services/` is empty
      per PR). Passes today (auditor: four queues, `SUCCESSFUL`). The auditor's break, on a
      scratch copy: a stray `"` in the attribute JSON → `"state": "ERROR"`, exit code 2, no
      queues. C2's PR repeats it on the changed script.
      Held. The auditor before the day, and #351 after its change: `up -d --wait localstack` healthy,
      `/_localstack/init/ready` `SUCCESSFUL`, `awslocal sqs list-queues` the four queues,
      `matching-user-deleted-dlq`'s `MessageRetentionPeriod` 1209600; then `down` without `-v`. No
      track PR (#346–#351) changes a file under `services/`.
      broken: a stray `"` in bus-init's attribute JSON → init `"state": "ERROR"`, `container day32-localstack-1 is unhealthy`, one queue (the first DLQ) instead of four (#351)

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
- **Track order: A1 (#346), A2 (#347), B (#348), C1 (#349), C2a (#350), C2b (#351),** the spec's
  order. Estimated 4 PRs; took 6, in 2,103 track lines. A split at 944 lines into the Terraform
  files (A1) and the checks with the workflow (A2), and C2 at about 530 into the bus (C2a) and
  the comparison with compose (C2b). #347 merged with an `Oversized:` line (674 lines, all new,
  504 of them `infra-checks.py`), the maintainer's choice over a split into A2a and A2b: the
  seventh override. One plan change (#342) and one spec change (#345).
- **Departures, each recorded in its PR:**
  - **C32.7 is checked on the LocalStack state, not the plan JSON** (#350). The policy holds the
    topic's ARN, which the main plan without an account leaves unknown (`policy` null,
    `after_unknown: true`). The state holds the same module's policy as applied, with real
    ARNs; LocalStack still does not enforce it, and the check reads the document.
  - **C32.12's break left one queue, not none** (#351). The first DLQ is created before the
    broken line runs; the auditor's record said no queues.
  - **C32.11's "seen failing" was run locally, through the workflow's own `run:` steps** (#347),
    not as a red CI run: none of infra-ci's 12 runs to the close was red.
- **Defect** — found: Track A1 · cause: spec · the estimate: 4 PRs, and Track A alone came to 944
  lines; C2 to about 530. Took 6, and #347 still needed `Oversized:`.
- **Defect** — found: Track C2a · cause: spec · C32.7 asked for a check on the plan JSON, where
  the policy is unknown until apply; it could not pass as written.
- **Defect** — found: Track C2b (break) · cause: spec · C32.12 recorded the stray-quote break as
  leaving no queues; it left one.
- **Defect** — found: review (Track A1/A2) · cause: implementation · the draft's LocalStack state
  sweep passed silently on every error, the state bucket and key were hardcoded, the
  bootstrap-bucket check was missing, and the lock test could never reach the prompt (the fifo's
  write end first opened by the `echo no`).
- **Defect** — found: review (Track A2) · cause: implementation · the C32.5 sweep descended into
  `after_sensitive`, which marks a null `password`, so Track B's instance would have failed it;
  the `skip_*` regex stopped at the first `}` and missed a flag after `default_tags`; and
  whitespace matching through `re.escape` did nothing.
- **Defect** — found: review (Track A2) · cause: process · the implementer reported "no
  departures" with the lock test never run, then a fix done that was not (versioning
  `Suspended` still printed two failures).
- **Defect** — found: review (Track B) · cause: implementation · the draft's C32.4 found the
  database's subnets by "private" in their address, so the public subnets passed; it was also
  about 250 lines and took the PR over 400. Rewritten as a resolver of references (120).
- **Defect** — found: Track B (break) · cause: implementation · C32.4's first message printed the
  password value in the password break.
- **Defect** — found: review (Track C1) · cause: implementation · a repeated `boto3` guard and five
  copy-pasted regex blocks, now one pattern table.
- **Defect** — found: review (Track C2a) · cause: implementation · the draft's C32.7 passed when
  the topic was missing from the state (`None == None`); its C32.8 skipped alarms whose
  `QueueName` was not a DLQ; and it applied with a local backend, leaving a `terraform.tfstate`.
- **Defect** — found: review (Track C2b) · cause: implementation · the draft compared the two
  LocalStacks only with each other, never with `EventBus.java`, so a `maxReceiveCount` changed on
  both sides passed; it skipped a key when either side was `None`, so a missing queue passed;
  and a snapshot error went into a field nothing read. Its report twice said C32.6 was complete.
- **Defect** — found: Track C2b · cause: process · the main session's own edit left
  `infra-checks.py` with mixed line endings once; normalised to LF before the commit.
- **Defect** — found: Track C1 · cause: environment · the session's first local lock test fed
  the fifo through `docker run -i` from Git Bash; Terraform saw EOF and released the lock before
  the second plan. Rerun inside one long-lived container; CI runs Terraform natively.
- **Defect** — found: close · cause: process · Track A1 had no Jira sub-task and no key in its
  branch, and KAN-99 (the plan change, #342) stayed In Progress after its merge. The close added
  A1 as KAN-105.
- **Defect** — found: close · cause: tooling · `spec-drift.py` reads `C32.x` in #345's text as a
  track named "C32" and reports "Day 32 Track C32: announced in #345, no branch names it" as the
  next step.
- **The close's Verify, on f99d62c:** `fmt -check` and `validate` in all three roots as above;
  the rest is infra-ci's, green on #351 and on `main` after it. A `localstack/.terraform` left
  from a track's S3-backend run makes `init -backend=false` try the stopped LocalStack; Verify
  needs a clean one.
- **Hand-off** H32.3 → Day 35: the state bucket's name `jobmatch-microservices-tfstate` is
  literal in both backends and the bootstrap's default; S3 names are global, so if it is taken,
  all three change together.
- **Hand-off** H32.4 → Day 35: `alert_email` has no default in the main root or the bootstrap
  (CI passes `alerts@example.com`); the apply must supply the maintainer's address, and the
  budget's limit defaults to 20 USD.
