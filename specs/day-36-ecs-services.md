# Day 36 — The services on ECS

**Phase:** 7 · **Depends on:** Day 33 · **Expected PRs:** 5
**Status:** provisional — re-read and revise before starting.

Split from Day 33 by the course correction before Phase 7 (`plan.md`). It runs after Day 33 and
before Day 34: Day 36 reuses a deleted number so that no hand-off to Days 34 and 35 changes.

## Goal
`terraform plan` describes the five services and the frontend on ECS Fargate behind an HTTPS
ALB, with their secrets, task roles and images, and every check of it runs without an account.

## In scope
- ECR repositories, one per image, that `terraform destroy` empties (`force_delete`).
- The ECS cluster, and one task-definition module for the five services and the frontend, in
  the public subnets with a public IP (Day 32 chose no NAT), in the tasks security group.
- Secrets Manager secrets created empty by Terraform; Day 33's non-interactive `db-setup.py`
  and a key-generation run write their values, so no secret value reaches Terraform state.
- One task role and execution role per service, each scoped to its own topic, queue or table;
  no access keys or emulator endpoints in any task definition (H28.5).
- The ALB: HTTPS to the frontend with an ACM certificate validated in a Route 53 zone given as
  a variable, as is the domain; port 80 redirects. `APP_BASE_URL` and the Google redirect URI on
  that domain, `SESSION_COOKIE_SECURE=true`.
- Security groups: the tasks admit only the ALB and one another; RDS stays tasks-only.
- Internal names (Service Connect or Cloud Map) matching compose's service names.
- One gateway task; matching scales on CPU or ALB request count; `PROFILE_CACHE_WINDOW` set
  and its window recorded; the gateway's trusted proxies match the frontend's path.
- The one-off migrate task for identity and application-service (Day 33's migrate-and-exit).

## Out of scope
- The code changes the task definitions rely on — Day 33.
- The push from the service workflows to ECR, and the deploy job — Day 35.
- The ADOT collector sidecar — Day 34.
- Any apply against a real account — Day 35.

## Tracks

| Track | Owner | Work |
|---|---|---|
| A | | ECR, the ECS cluster, the task-definition module |
| B | | Secrets and task roles, and their plan checks |
| C | | ALB, ACM, security groups |
| D | | Internal names, gateway count, scaling, environment parity with compose |

## Acceptance criteria
Provisional, from Day 33's spec-auditor: checked on `terraform show -json` by
`scripts/infra-checks.py` in infra-ci, with no account. Tagged and numbered when the day is
audited.
- [ ] Six ECS services and task definitions from one module, `FARGATE`, public subnets, the
      tasks security group.
- [ ] No task definition has a secret or access key in `environment`; they come from
      `secrets` by Secrets Manager ARN, and no secret version holds a value.
- [ ] Each role reaches only its own topic, queue or table; no `*` resource.
- [ ] The ALB listens on 443 with the certificate and redirects 80; target groups check
      `/actuator/health/readiness`.
- [ ] The tasks security group admits only the ALB's and its own; Day 32's RDS check stays
      green.
- [ ] Every internal URL resolves to an internal name; every variable compose sets is in the
      task definition or on a recorded compose-only list.
- [ ] One gateway task with no scaling target; matching has a scaling policy.
- [ ] Day 32's criteria stay green, including the second apply's "No changes."

## Verify
```bash
python scripts/infra-checks.py   # in infra-ci, on the plan JSON
```

## Notes
- LocalStack community has no ECS, so nothing this day adds is applied before Day 35.
