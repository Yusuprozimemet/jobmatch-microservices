# Deploy once, then destroy (Day 35)

This runbook makes one deployment from `main`, checks it, and destroys it. It is the work behind
[the Day 35 spec](../../specs/day-35-deploy-and-destroy.md). The evidence for C35.12 to C35.22 goes
in the closing PR, and the output of each command is kept with it. Save the output as you go; do
not write it up from memory.

Run the commands from the repository root. Terraform runs on the main root with `-chdir`, so the
working directory stays put.

## Before you start

The maintainer supplies:

- AWS credentials for the account, with rights to create the resources in `infra/terraform`.
- A domain, and a Route 53 public hosted zone for it, with the name servers delegated to that zone.
- The address that receives the alarms (`alert_email`).
- A Google OAuth client, with `https://<domain>/login/oauth2/code/google` as an authorised
  redirect URI.
- An LLM API key.
- A second network address for the rate-limit check in step 12 (AWS CloudShell, or a phone's
  hotspot).

```bash
export AWS_REGION=eu-west-1
```

The cost starts at step 4, when the load balancer and the database begin to bill. With the
services running it is about $0.26 an hour (the dashboard's $0.227 and Day 34's metrics), roughly
$6 a day. If time or budget runs
short, the priorities are reachability (step 12), the migrations (step 9) and the teardown (steps
14 and 15). Anything else is recorded as not done.

Each step has a **Check**. Do the check before the next step. If a check fails, stop. A defect
found on the way gets its own PR, `day-35/fix-<slug>`, titled `Day 35 fix: <what was wrong>`. Merge
it, then run the step again.

## 1. Preflight

```bash
cp infra/terraform/deploy.tfvars.example infra/terraform/deploy.tfvars
# Edit infra/terraform/deploy.tfvars: alert_email, domain and route53_zone_id
scripts/deploy-preflight.sh
```

**Check:** five `ok` lines and exit 0 (C35.12). The script reads `infra/terraform/deploy.tfvars` by
default. If its OIDC line says the account already has a GitHub OIDC provider, step 4 imports it.
The [deploy.tfvars.example](../../infra/terraform/deploy.tfvars.example) file explains each value.

## 2. The bootstrap

```bash
terraform -chdir=infra/terraform/bootstrap init
terraform -chdir=infra/terraform/bootstrap apply -var alert_email=<address>
terraform -chdir=infra/terraform/bootstrap output state_bucket
```

The bootstrap keeps its state locally, in `infra/terraform/bootstrap/terraform.tfstate`, which is
gitignored. Keep that file. The bootstrap is not destroyed with the main root: it holds the state
bucket and the budget.

**Check:** `state_bucket` names the bucket that the backend in
[versions.tf](../../infra/terraform/versions.tf) points at.

## 3. The budget

```bash
ACCOUNT="$(aws sts get-caller-identity --query Account --output text)"
aws budgets describe-budgets --account-id "$ACCOUNT"
aws budgets describe-notifications-for-budget --account-id "$ACCOUNT" --budget-name jobmatch-monthly-budget
aws budgets describe-subscribers-for-notification --account-id "$ACCOUNT" --budget-name jobmatch-monthly-budget \
  --notification NotificationType=ACTUAL,ComparisonOperator=GREATER_THAN,Threshold=80,ThresholdType=PERCENTAGE
```

**Check:** the limit is 20 USD, and the subscriber is the maintainer's address (C35.12). The
notification's email address is only in the last command, not in the first two.

## 4. The main root, services stopped

If preflight found a GitHub OIDC provider, import it before the plan. The destroy in step 14 deletes
it too. If something else in the account uses it, recreate it after the destroy.

```bash
terraform -chdir=infra/terraform init
# Only if preflight found the OIDC provider:
terraform -chdir=infra/terraform import -var-file=deploy.tfvars aws_iam_openid_connect_provider.github \
  "arn:aws:iam::<account>:oidc-provider/token.actions.githubusercontent.com"
terraform -chdir=infra/terraform plan -var-file=deploy.tfvars -out tfplan
terraform -chdir=infra/terraform apply tfplan
gh variable set AWS_DEPLOY_ROLE_ARN --body "$(terraform -chdir=infra/terraform output -raw deploy_role_arn)"
```

The apply waits for the certificate's DNS validation, so it can take a few minutes. `start_services`
is still `false` in `deploy.tfvars`.

**Check:** the apply completes. Then each service's desired count is 0:

```bash
aws ecs describe-services --cluster jobmatch \
  --services identity-service job-service matching-service application-service api-gateway frontend \
  --query 'services[].[serviceName,desiredCount]'
```

## 5. The alarm topic's subscription

Confirm the subscription from the email that AWS sends to `alert_email`. Then find the topic and
list its subscriptions. The topic is named `user-deleted-dlq-alarms`: the bus module's
`user-deleted` topic with `-dlq-alarms` added.

```bash
TOPIC="$(aws sns list-topics --query 'Topics[].TopicArn' --output text | tr '\t' '\n' | grep 'user-deleted-dlq-alarms$')"
aws sns list-subscriptions-by-topic --topic-arn "$TOPIC"
```

**Check:** the subscription has an ARN, not `PendingConfirmation`. The frontend's
`jobmatch-frontend-healthy-hosts` alarm fires now, and its email arrives. That is expected: no task
runs yet, and the alarm treats missing data as breaching.

## 6. The seven images

Turn the deploy job on, then start the seven image workflows on `main`.

```bash
gh variable set DEPLOY_ENABLED --body true
for wf in api-gateway-ci-cd.yaml identity-service-ci-cd.yaml job-service-ci-cd.yaml \
  matching-service-ci-cd.yaml application-service-ci-cd.yaml frontend-ci-cd.yaml db-setup-tests.yml; do
  gh workflow run "$wf" --ref main
done
```

The identity-service and application-service runs push their images, then fail at **Run the
migrations**. That failure is expected: the secrets and the database roles do not exist yet. Step
9 runs those two migrations and re-runs the two failed runs.

**Check:** each of the seven repositories has an image tagged `latest` (`jobmatch/api-gateway`,
`jobmatch/identity-service`, `jobmatch/job-service`, `jobmatch/matching-service`,
`jobmatch/application-service`, `jobmatch/frontend`, `jobmatch/db-setup`), and each run's link is
recorded (C35.13):

```bash
for repo in api-gateway identity-service job-service matching-service application-service frontend db-setup; do
  aws ecr describe-images --repository-name "jobmatch/$repo" --image-ids imageTag=latest \
    --query 'imageDetails[].imageDigest' --output text
done
```

## 7. The secrets

Make a directory outside the repository and write the three values into it, one file per value.
The files are named after their secrets: `google-client-id`, `google-client-secret` and `llm-api-key`.
[write-secrets.sh](../../scripts/write-secrets.sh) refuses a directory inside the repository.

```bash
mkdir -p "$HOME/jobmatch-secrets"
# Create google-client-id, google-client-secret and llm-api-key in it, then:
scripts/write-secrets.sh "$HOME/jobmatch-secrets"
```

When they are missing, the script makes the seven database passwords and the four signing keys itself.
It prints names only.

**Check:** the output names the fourteen secrets and no value (C35.14). Each of these shows
`AWSCURRENT`:

```bash
aws secretsmanager list-secret-version-ids --secret-id jobmatch/db-password-app_user
# ...and the same for db-password-analytics_user, db-password-analytics_dev_user,
# db-password-identity_user, db-password-applications_user, db-password-matching_user,
# db-password-jobs_user, jwt-private-key, service-jwt-private-key-job-service,
# service-jwt-private-key-matching-service, service-jwt-private-key-application-service,
# google-client-id, google-client-secret and llm-api-key
terraform -chdir=infra/terraform state list | grep -c secret_version
terraform -chdir=infra/terraform state pull > "$HOME/jobmatch-secrets/state.json"
python scripts/infra-checks.py --state "$HOME/jobmatch-secrets/state.json"
rm "$HOME/jobmatch-secrets/state.json"
```

The `grep -c` prints 0: no secret version is in the state. The last check passes, then the pulled
file is deleted.

## 8. db-setup

The first run is the break C35.15 asks for: the identity migration before `db-setup` has ever run.
It must fail.

```bash
scripts/run-once.sh identity-service-migrate
scripts/run-once.sh db-setup
```

**Check:** the first run exits non-zero. Record its exit code and the error it printed, as
`broken: identity-service-migrate run before the first db-setup → <exit code and error>` (C35.15).
The second run exits 0.

## 9. The migrations

```bash
scripts/run-once.sh identity-service-migrate
scripts/run-once.sh application-service-migrate
gh run rerun <run-id> --failed
```

Run `gh run rerun` once for each of the two runs that failed in step 6. The run id is in the link
recorded there, or in `gh run list --workflow identity-service-ci-cd.yaml`.

**Check:** both migrate runs exit 0. The identity-service log shows Flyway at V16 for
`db/migration` and V4 for `identity`. The application-service log shows V1 (C35.15). The two re-runs
are green, and their links replace the failed ones in step 6's record.

## 10. jobs-seed

```bash
scripts/run-once.sh jobs-seed
scripts/run-once.sh jobs-seed
```

**Check:** the first run exits 0. The second run must refuse with a non-zero exit, because
`analytics.fct_postings` already has rows (C35.15, and C35.4 on the real database). The
[seed-mart.py](../../scripts/seed-mart.py) is the script that refuses: the fixture would drop the mart.

## 11. Services started

In `deploy.tfvars`, set `start_services = true`. Then plan and apply as in step 4.

```bash
terraform -chdir=infra/terraform plan -var-file=deploy.tfvars -out tfplan
terraform -chdir=infra/terraform apply tfplan
aws ecs describe-services --cluster jobmatch \
  --services identity-service job-service matching-service application-service api-gateway frontend \
  --query 'services[].[serviceName,status,desiredCount,runningCount]'
```

**Check:** every service is `ACTIVE` with a running count equal to its desired count. That means
every secret ARN resolved (C35.18, first part). Services are started only now, after the migrations
(C35.15).

## 12. The live checks

```bash
DOMAIN="$(sed -n 's/^domain *= *"\(.*\)"/\1/p' infra/terraform/deploy.tfvars)"
python scripts/live-checks.py --domain "$DOMAIN" --out docs/dashboard/deployment.json
```

The script writes `docs/dashboard/deployment.json`, which does not exist before this step. Run
the break C35.16 asks for once: point the check at the ALB's port 443 as if it were a task's port.
It must report that port open. Record that result as `broken: live-checks against the ALB's 443 as
a task → <what it reported>`.

Then check by hand, and record each result with its time:

- Google sign-in at `https://<domain>`, and the other surfaces C35.17 lists (C35.17).
- `DELETE /api/users/me` from the app, with both dead-letter queues empty afterwards. The rate limit
  from two addresses (C35.18).
- A trace in X-Ray, the metrics and the dashboard. The frontend task stopped by hand, and the alarm
  email that follows. The collector's memory (C35.19).
- The matching task stopped by hand and replaced. A bad image rolled back by the circuit breaker,
  then `latest` restored to the good digest. Then `terraform -chdir=infra/terraform plan
  -var-file=deploy.tfvars -detailed-exitcode` exits 0 (C35.20).
- The break C35.21 asks for: `scripts/teardown-check.sh` run while the deployment is up. Record
  what it listed and its exit code.

**Check:** each criterion's evidence is recorded, or named as not done.

## 13. The audits

Before the destroy, run the audits on Opus: the plan-auditor on Phase 7, then the plan-auditor on
the whole plan, then the three end-of-phase auditors. Keep their reports.

**Check:** the reports are in hand, and the maintainer has said to go on to the destroy. Nothing is
destroyed until then.

## 14. The destroy

The destroy is the main root only. The bootstrap stays, with the budget.

```bash
terraform -chdir=infra/terraform destroy -var-file=deploy.tfvars
gh variable set DEPLOY_ENABLED --body false
terraform -chdir=infra/terraform state list
```

Turning `DEPLOY_ENABLED` off makes later merges push to GHCR again. The `AWS_DEPLOY_ROLE_ARN`
variable stays. It names a role that the destroy removes.

**Check:** the destroy completes, and `terraform state list` prints nothing (C35.21).

## 15. The teardown check

```bash
scripts/teardown-check.sh
```

**Check:** the script exits 0. What stays is the hosted zone, the bootstrap's bucket and budget, and
X-Ray's traces (C35.21). On the first full day after the destroy, Cost Explorer by service shows no
ECS, ELB, RDS, EC2 or VPC charge (C35.22).
