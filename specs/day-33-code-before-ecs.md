# Day 33 — The code before ECS

**Phase:** 7 · **Depends on:** Day 32 · **Expected PRs:** 5

## Goal
Every service can run as an ECS task without a file on disk or a person at a terminal: the
signing keys come from a variable's content, the migrations run as a separate run that exits,
and `db-setup.py` takes its passwords from the environment, while compose and the tests run as
they do today.

## In scope
- The four key loaders: identity's `SigningKey` (`JWT_PRIVATE_KEY_FILE`) and the three
  `ServiceSigningKey`s in job-, application- and matching-service
  (`SERVICE_JWT_PRIVATE_KEY_FILE`) also read the PEM from `JWT_PRIVATE_KEY` and
  `SERVICE_JWT_PRIVATE_KEY`. ECS hands a Secrets Manager secret to a task as a variable, not a
  file (`plan.md`, Phase 7).
- Migrate-and-exit in the two services that run Flyway: identity (`config/Migrations.java`, the
  owner then the module schemas) and application-service (Spring's Flyway). `MIGRATE_ONLY=true`
  migrates and exits; `MIGRATE_ON_START=false` starts without migrating. The defaults keep
  today's behaviour, so compose and the harness need no change.
- `db-setup.py --passwords-from-env`: every role's password from `DB_PASSWORD_<ROLE>`
  (`DB_PASSWORD_APP_USER`, ...), the admin's from `POSTGRES_PASSWORD`; no prompt, no generated
  password, nothing printed that a log would keep. Without the flag it behaves as today.
- A CI run of `db-setup.py` against Postgres. Today no job executes it:
  `IdentityDbConnectTest` only reads its source.
- H32.2, the stale docs Day 32 handed on.
- Each new variable in the service's `docs/configuration.md` (identity) or README.

## Out of scope
- Everything on ECS (task definitions, roles, secrets, the ALB, ECR, the one-off migrate and
  `db-setup.py` tasks, the domain and the Google redirect URI) is Day 36's (`plan.md`, course
  correction before Phase 7).
- Writing the passwords and keys into Secrets Manager is Day 36's. This day only reads them from
  the environment.
- The ECR push and the deploy job are Day 35's.

## Tracks

| Track | Owner | Work |
|---|---|---|
| 0 | | `db-setup.py` run against Postgres in CI, its result pinned |
| A | | Signing keys from a variable's content, in four services |
| B | | Migrate-and-exit in identity and application-service |
| C | | `db-setup.py --passwords-from-env` |
| D | | The H32.2 docs |

Track 0 lands before C. A, B and D are independent of each other and of C.

## Acceptance criteria
- [ ] C33.1 **new** — Each of the four loaders signs with the key in `JWT_PRIVATE_KEY` (identity)
      or `SERVICE_JWT_PRIVATE_KEY` (the other three) when its `_FILE` is unset. The key id is the
      same as for the same key read from a file, and the JWKS publishes it. Each service has a
      unit test for this. Red today: `grep -rlE '"(SERVICE_)?JWT_PRIVATE_KEY"' services --include=*.java`
      finds nothing.
- [ ] C33.2 **new** — When both the content and the file variable are set, or neither is, the
      service does not start, and the message names both variables. The same four tests cover
      it. Red today: the message for neither names only the `_FILE` variable
      (`read` in all four loaders, "is not set").
- [ ] C33.3 **hold** — The file still works. `SigningKeyStartupTest`, the three
      `ServiceSigningKeyTest`s and the Day 1–4 `contract/` suite pass, and
      `git diff main -- '*/contract/*'` is empty.
- [ ] C33.4 **new** — With `MIGRATE_ONLY=true`, identity and application-service apply their
      migrations (identity: the owner's V1–V16, then each module's), start no web server, and
      exit 0. A failed migration exits non-zero. Each has a test that runs it against the
      harness Postgres and reads the Flyway history afterwards. Red today:
      `grep -rn MIGRATE_ONLY services` finds nothing.
- [ ] C33.5 **new** — With `MIGRATE_ON_START=false`, the service starts and answers
      `/actuator/health/readiness` on an empty database without creating a Flyway history
      table. Red today: `grep -rn MIGRATE_ON_START services` finds nothing.
- [ ] C33.6 **hold** — With neither variable set, the services migrate at startup as today.
      `ModuleMigrationsIT` and the services' database tests pass unedited, and
      `docker-compose.yml` sets neither variable.
- [ ] C33.7 **hold** (Track 0) — A CI job runs `db-setup.py` against a Postgres container twice.
      The second run changes nothing, and the databases, schema owners, CONNECT grants and
      schema privileges equal a snapshot pinned in the test. The snapshot is taken from today's
      script.
- [ ] C33.8 **new** — With `--passwords-from-env`, a missing `DB_PASSWORD_<ROLE>` or admin
      password fails before connecting, and the message names each missing one. Each role is set
      to its given password, including roles that already exist. Each role then logs in with it,
      and no password appears on stdout or stderr. C33.7 stays green. Red today: the flag is
      unknown, and argparse exits 2.
- [ ] C33.9 **new** — The H32.2 docs, each grep at zero:
      - `grep -cE "monolith's remainder|until Track E1" services/api-gateway/src/main/resources/application.yaml`
        (today 2);
      - `grep -cE "(^|[^.])\.\./mvnw" services/identity-service/README.md` (today 9 lines).
      `DB_NAME`'s row in identity's `docs/configuration.md` gives `identity_db`, the default in
      `application.yaml:10` (today `project_db`). The other `project_db`, at :301, is history,
      and it stays.

## Verify
```bash
docker build -t jobmatch-job-service:harness services/job-service
cd services/identity-service && ../../mvnw clean verify && ../../mvnw -B checkstyle:check
cd services/application-service && ../../mvnw clean verify      # likewise job- and matching-service
python -m pytest -q scripts/test_db_setup.py                    # needs Docker or CI's Postgres
```

## Notes
- **Defect** — found: auditor · cause: spec · the draft was the Azure/Kubernetes one (Pulumi,
  Helm add-ons, a NetworkPolicy, a blob backend), which `plan.md` had ruled out since #342. The
  RemoteStateReference claim was false too: the state is S3, and the root has no outputs. None
  of the six criteria was tagged or could run without a cluster. The day plan.md asks for was
  about nine PRs. The rewrite is this PR, after the split in #353.
- **Defect** — found: after merge · cause: environment · after #353, `spec-drift.py` reported
  "Day 33 Track A: Pulumi project…": it counted the plan-change PR as the day's spec change and
  read the tracks from the old draft. This PR's tracks replace them.
- `db-setup.py` is already non-interactive when `POSTGRES_PASSWORD` is set and stdin is not a
  terminal (`confirm_password_reset` keeps the passwords). What blocks ECS is that it invents
  the role passwords and prints them, so the task's log would hold every one.
- **Moved to Day 36 by the split (#353):** from Day 32's Out of scope, ECR, the cluster, the
  task definitions and roles, the ALB with its domain and certificate, and the secrets. Also
  H28.5's task roles in place of the dummy keys, with `PROFILE_CACHE_WINDOW`; the Google
  redirect URI on the domain (day-32 Notes); the score table's `create-table` left unset in
  the task definition; and the tasks' security group admitting only the ALB.
