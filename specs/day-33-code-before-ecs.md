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
- [x] C33.1 **new** — Each of the four loaders signs with the key in `JWT_PRIVATE_KEY` (identity)
      or `SERVICE_JWT_PRIVATE_KEY` (the other three) when its `_FILE` is unset. The key id is the
      same as for the same key read from a file, and the JWKS publishes it. Each service has a
      unit test for this. Red today: `grep -rlE '"(SERVICE_)?JWT_PRIVATE_KEY"' services --include=*.java`
      finds nothing.
      Met in all four. Identity: `SigningKeyStartupTest.startsWithTheKeyInTheVariableUnderTheFilesKeyId`
      checks the key id against the same key read from the file, and the `JWKSet` it builds; identity's
      `JwksController` is package-private in another module, so the test reads the set, not the
      controller (#356). Job-, application- and matching-service:
      `ServiceSigningKeyTest.acceptsTheKeyInTheVariableUnderTheFilesKeyIdAndPublishesIt` parses what
      `ServiceJwksController.serviceJwks()` returns (#357). The grep now finds the four loaders.
      broken: identity, the variable ignored (`if (false && hasContent)`) → `startsWithTheKeyInTheVariableUnderTheFilesKeyId` failed ("JWT_PRIVATE_KEY_FILE points at , which cannot be read"), and so did both variable-refusal tests (#356)
      broken: job-service, the variable ignored (`if (false && hasContent)`) → `acceptsTheKeyInTheVariableUnderTheFilesKeyIdAndPublishesIt` errored ("SERVICE_JWT_PRIVATE_KEY_FILE points at , which cannot be read"); `refusesAVariableHoldingA1024BitKey` and `refusesAVariableThatIsNotAKeyWithoutRepeatingIt` failed (#357)
      broken: application-service, the variable ignored → the same two refusal tests failed (#357)
      broken: matching-service, the variable ignored → the same two refusal tests failed (#357)
- [x] C33.2 **new** — When both the content and the file variable are set, or neither is, the
      service does not start, and the message names both variables. The same four tests cover
      it. Red today: the message for neither names only the `_FILE` variable
      (`read` in all four loaders, "is not set").
      Met in all four: `refusesToStartWithBothTheFileAndTheContent` and `refusesToStartWithNoKeyConfigured`
      ("Neither JWT_PRIVATE_KEY_FILE nor JWT_PRIVATE_KEY is set") in identity (#356), and
      `refusesBothTheFileAndTheContent` with the neither case in the three `ServiceSigningKeyTest`s
      (#357). A value that is not a key is refused without repeating it, since a startup failure lands
      in the task's log. `refusesToStartWithNoKeyConfigured`'s expected message changed with C33.2; it
      is not in `contract/`.
      broken: identity, the both-set check removed → `refusesToStartWithBothTheFileAndTheContent` failed (the context started) (#356)
      broken: identity, the value put into the message → `refusesToStartWithAVariableThatIsNotAKeyAndDoesNotRepeatIt` failed ("JWT_PRIVATE_KEY holds not-a-key-7f3a, which is not a PKCS#8 …") (#356)
      broken: job-service, the both-set check removed → `refusesBothTheFileAndTheContent` failed (#357)
      broken: job-service, the value put into the message → `refusesAVariableThatIsNotAKeyWithoutRepeatingIt` failed (#357)
- [x] C33.3 **hold** — The file still works. `SigningKeyStartupTest`, the three
      `ServiceSigningKeyTest`s and the Day 1–4 `contract/` suite pass, and
      `git diff main -- '*/contract/*'` is empty.
      Held. The file tests pass in all four services at the close (Verify below), and
      `git diff 630b591 main -- '*/contract/*'` is empty across the day's PRs (#354–#362). No track PR
      recorded a break for this hold; the close ran one.
      broken: identity's file branch reads the PEM minus its first character (`.substring(1)`) → `SigningKeyStartupTest` 10 run, 3 failures, 1 error: `startsWithAnRsaKeyAndNamesItByItsThumbprint`, `refusesToStartWithAKeyThatIsNotRsa`, `refusesToStartWithAKeyShorterThan2048Bits`, `startsWithTheKeyInTheVariableUnderTheFilesKeyId` (close)
- [x] C33.4 **new** — With `MIGRATE_ONLY=true`, identity and application-service apply their
      migrations (identity: the owner's V1–V16, then each module's), start no web server, and
      exit 0. A failed migration exits non-zero. Each has a test that runs it against the
      harness Postgres and reads the Flyway history afterwards. Red today:
      `grep -rn MIGRATE_ONLY services` finds nothing.
      Met. Identity: `MigrateOnlyIT` runs `MigrateOnly` against the harness Postgres and reads
      `app.flyway_schema_history` (V1–V16) and `identity.flyway_schema_history` afterwards; a rerun
      changes nothing; a `users` table placed in `identity` lets V1–V11 apply and V12 fail, and the run
      exits non-zero with history at 11 (#358). Application-service: `MigrateOnlyTest` likewise on
      `applications`; its failure is `REVOKE CREATE` on the schema, before V1, since V1 is its only
      migration (#359). Both start a non-web context with only Flyway in it. The packaged jar with
      `MIGRATE_ONLY=true` applied V1 and exited 0, and 1 with a wrong password (#359).
      broken: identity, `MigrateOnly` passes `--spring.flyway.enabled=${MIGRATE_ON_START:true}` instead of `true` → `migratesEvenWithMigrateOnStartOff` ERROR (no history table to read) (#358)
      broken: identity, `MigrateOnly.run` returns 0 on failure → `aFailedMigrationExitsNonZero` FAILURE (expected non-zero, was 0) (#358)
      broken: application-service, `MigrateOnly` does not prepend `--spring.flyway.enabled=true` → `migratesEvenWithMigrateOnStartOff` ERROR (no history table to read) (#359)
      broken: application-service, `MigrateOnly.run` returns 0 on failure → `aFailedRunExitsNonZero` FAILURE (expected non-zero, was 0) (#359)
- [x] C33.5 **new** — With `MIGRATE_ON_START=false`, the service starts and answers
      `/actuator/health/readiness` on an empty database without creating a Flyway history
      table. Red today: `grep -rn MIGRATE_ON_START services` finds nothing.
      Met. `MigrateOnStartOffIT` (identity, #358) and `MigrateOnStartOffTest` (application-service,
      #359) start the whole service on an empty database of their own, get 200 from
      `/actuator/health/readiness`, and find no `flyway_schema_history`; `itsDatabaseIsTheEmptyOne`
      checks they are on that database, not the migrated one. Both stand alone rather than extend the
      shared base, whose `@DynamicPropertySource` wins over a subclass's.
      broken: identity, `application.yaml` `enabled: true` → `doesNotCreateFlywayHistory` FAILURE (expected 0 history tables, was 2); on the first try, before the test stood alone, it passed (#358)
      broken: application-service, `application.yaml` `enabled: true` → `doesNotCreateFlywayHistory` FAILURE (expected 0, was 1) (#359)
      broken: application-service, `MigrateOnStartOffTest` context on `apps_db` → `itsDatabaseIsTheEmptyOne` FAILURE (expected `migrate_on_start_off_db`, was `apps_db`) (#359)
- [x] C33.6 **hold** — With neither variable set, the services migrate at startup as today.
      `ModuleMigrationsIT` and the services' database tests pass unedited, and
      `docker-compose.yml` sets neither variable.
      Held. `MIGRATE_ON_START` defaults to `true` in both `application.yaml`s; `ModuleMigrationsIT`
      and the database tests pass at the close with no edit in the day (`git log 630b591..main --
      '*ModuleMigrationsIT*'` is empty); `grep -c MIGRATE docker-compose.yml` is 0. No track PR recorded
      a break for this hold; the close ran one.
      broken: identity's default `${MIGRATE_ON_START:false}` → `ModuleMigrationsIT` 5 run, 1 failure, 2 errors (`eachModuleHasItsOwnHistoryOwnedByItsRole`, `andItStartsAtTheBaseline`, `appKeepsTheHistoryOfEverythingBefore`) (close)
- [x] C33.7 **hold** (Track 0) — A CI job runs `db-setup.py` against a Postgres container twice.
      The second run changes nothing, and the databases, schema owners, CONNECT grants and
      schema privileges equal a snapshot pinned in the test. The snapshot is taken from today's
      script.
      Held. `scripts/test_db_setup.py` pins the first run's databases, owners, CONNECT grants (PUBLIC
      included), roles, memberships, schema ACLs and default privileges, and checks the second run
      changes nothing, password hashes included; `.github/workflows/db-setup-tests.yml` runs it on a
      `postgres:18.4-alpine` service (#355). It passed unedited through Track C (#360).
      broken: read-only grants without SEQUENCES (`READ_ONLY` in db-setup.py) → `test_the_first_run_leaves_the_pinned_state` failed, `default_privileges` differing (#355)
      broken: `revoke_connect(conn, JOBS_DATABASE)` skipped → pin test failed, `'PUBLIC'` in jobs_db's connect list (#355)
      broken: second run resets every existing password (`reset = bool(existing)`) → "the second run changed a role's password" and "the second run reported a new password for [all seven roles]"; no password in the output (#355)
      broken: test run against a Postgres already set up → fixture refused: "needs a fresh Postgres, found ['identity_db', ...]" (#355)
- [x] C33.8 **new** — With `--passwords-from-env`, a missing `DB_PASSWORD_<ROLE>` or admin
      password fails before connecting, and the message names each missing one. Each role is set
      to its given password, including roles that already exist. Each role then logs in with it,
      and no password appears on stdout or stderr. C33.7 stays green. Red today: the flag is
      unknown, and argparse exits 2.
      Met. The check is in `parse_args`, so a missing variable is an argparse error (exit 2) naming
      each one, before any connection (the test points at port 1). Existing roles get `ALTER ROLE`; the
      env test drops `jobs_user` first, so one role is created and six reset, and each logs in. The CI
      Postgres moved from trust to password auth (`dbsetup-<run_id>-<run_attempt>`), since under trust
      any password logs in; the test first checks a wrong one is refused. The env run's state is
      compared with C33.7's pin (#360).
      broken: env mode skips `ALTER ROLE` for existing roles → six roles `OperationalError` on login (only `jobs_user`, which was re-created, logged in) (#360)
      broken: report prints `passwords[role]` → "db-setup.py printed the password of ['app_user', ... 'jobs_user']" (#360)
      broken: missing-variable check disabled → exit 1, "Connecting to localhost:1 as 'postgres'" (expected 2) (#360)
      broken: trust-auth Postgres → "Postgres accepts any password (trust auth); run it with POSTGRES_PASSWORD" (#360)
- [x] C33.9 **new** — The H32.2 docs, each grep at zero:
      - `grep -cE "monolith's remainder|until Track E1" services/api-gateway/src/main/resources/application.yaml`
        (today 2);
      - `grep -cE "(^|[^.])\.\./mvnw" services/identity-service/README.md` (today 9 lines).
      `DB_NAME`'s row in identity's `docs/configuration.md` gives `identity_db`, the default in
      `application.yaml:10` (today `project_db`). The other `project_db`, at :301, is history,
      and it stays.
      Met with a departure (#362). The gateway grep is 0. The README grep as written cannot reach 0:
      `../../mvnw` contains `/../mvnw`, and `/` matches `[^.]`; it reports 9 before and after. Ticked on
      `grep -cE "(^|[^/])\.\./mvnw"`: 9 on `main` before, 0 after. `DB_NAME`'s row gives `identity_db`;
      the historical `project_db` is at :309, not :301. The README's `../services/job-service`, which
      resolved to `services/services/job-service`, is now `../job-service` (3 places), which the spec
      did not name.
      broken: none → docs only; the checks are the greps, seen red on `main` (gateway 2, README 9 with the corrected grep, `project_db` in `DB_NAME`'s row) and at 0 after #362

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
- **Audit (spec-auditor, on the draft, KAN-37):** 9 findings, 6 blocking, all answered by the
  split (#353) and this rewrite: the draft was the Azure/Kubernetes one; the RemoteStateReference
  claim was false; Day 32's code hand-offs (keys from a variable, Flyway as a one-off task, a
  non-interactive `db-setup.py`, H32.2) and its ECS ones (task roles, the ALB's group, the
  redirect URI) were not cited; no criterion was tagged, and none could run in CI; secrets would
  have reached the state; "Expected PRs: 3" against about 17 criteria; nobody owned ECR and the
  push.
- **Track order: 0 (#355), A1 (#356), A2 (#357), B1 (#358), B2 (#359), C (#360), D (#362),** the
  spec's order. Estimated 5 PRs; took 7, in 1,751 track lines. The gate split A at 507 lines into
  identity (A1) and the three service keys (A2), and B into identity (B1, 399 lines) and
  application-service (B2). No `Oversized:`. One plan change (#353) and one spec change (#354).
  Track C landed after Track 0, as the spec required. #361 (README fixes before the repo was
  shared) merged during the day, outside it.
- **Departures, each recorded in its PR or tick:**
  - **C33.1's "the JWKS publishes it" is checked on identity's `JWKSet`, not its controller**
    (#356): `JwksController` is package-private in another module. The three services' tests
    call their controller (#357).
  - **C33.9's README grep is replaced by `(^|[^/])\.\./mvnw`** (#362); the written one cannot
    reach zero.
  - **C33.3's and C33.6's breaks were run at the close**, not in a track PR: none recorded one.
- **Defect** — found: Track A1 · cause: spec · the estimate: 5 PRs; Track A came to 507 lines and
  B1 alone to 399, so the gate split both. Took 7.
- **Defect** — found: Track 0 · cause: spec · C33.8's "each role then logs in with it" could not
  fail on CI's Postgres, which used trust authentication. Track 0 handed it to C, which moved the
  container to password auth and checks first that a wrong password is refused.
- **Defect** — found: Track D · cause: spec · C33.9's README grep `(^|[^.])\.\./mvnw` matches
  `/../mvnw` inside `../../mvnw`, so it could not reach zero.
- **Defect** — found: Track D · cause: spec · C33.9 put the historical `project_db` at :301 (it is
  :309) and missed the README's `../services/job-service`, which resolved to
  `services/services/job-service` (3 places, fixed in #362).
- **Defect** — found: close · cause: spec · C33.3 and C33.6 are holds, and no PR broke them: the
  spec-change PR said they "rest on tests green on `main`", and no track owned the break. The close
  ran both (the ticks above).
- **Rewrite** — Track 0 (#355): the implementer's draft was 1,006 lines; its revision hard-coded
  the creator lists, kept only the first ACL item of each default-privilege row, and filtered
  PUBLIC out of the CONNECT grants, so the regression the pin most needs to catch could not show.
  The test was rewritten in the main session; the workflow is the implementer's.
- **Rewrite** — Track A1 (#356): the implementer's draft changed the file messages ("which is
  not" to "is not"), wrote "are neither set", skipped the docs and ran to 560 lines. The loader
  and its test were rewritten in the main session. A2's three copies were written there too.
- **Defect** — found: review (Track B1) · cause: implementation · the draft added an unreported
  `baseline-on-migrate: true` to the owner's Flyway, which on a non-empty `app` would baseline at
  1 and skip V1; its one failing test was its own helper reading the first row only, which it
  blamed on Flyway's classpath; its first failure test hit Flyway's non-empty-schema check, not a
  migration.
- **Defect** — found: Track B1 (break) · cause: implementation · `MigrateOnStartOffIT` extended
  the shared base, whose `@DynamicPropertySource` wins, so it ran on the migrated `identity_db`
  and could not fail. The implementer reported it had confirmed the override worked; only the
  yaml break showed otherwise.
- **Defect** — found: review (Track B2) · cause: implementation · as in B1, an unreported
  `baselineOnMigrate=true`; `@EnableAutoConfiguration` in place of the two named
  auto-configurations, with "Departures from the brief: None"; an `itsDatabaseIsTheEmptyOne` that
  asked its own admin connection, so it could not fail; and history counted, not read.
- **Defect** — found: review (Track C) · cause: implementation · the implementer reported "no
  departures" twice: `--admin-password` ignored, an added `log_message` flag, existing roles
  logged twice, a garbled docstring and two lost ones, `pytest.raises(match=".*")`, and a failure
  entry printing the type name of a string. Round two was fixed in the main session.
- **Defect** — found: Track A2 · cause: process · the main session's first JWKS assertion did not
  compile (`containsEntry` on a `Map<?, ?>`), 9 errors per service; now `JWKSet.parse`.
- **Defect** — found: Track C, close · cause: process · two of the main session's breaks did not
  run: Track C's first was a syntax error, and the close's first C33.3 break removed the only
  `IOException` from a `try` and did not compile. Both redone; the records above are the reruns.
- **Defect** — found: Track A1, Track B1 · cause: tooling · `spec-drift.py` read "track A1" and
  "track B1" as all of Tracks A and B, and printed Track B after #356 and Track C after #358. The
  same family as Day 32's criterion-id misread; neither is fixed.
- In Track B1 the session's permission classifier blocked the breaks on purpose once, until the
  maintainer said to run them. The implementer wrote Tracks 0, A1, B1, B2 and C; review changed all five.
- **The close's Verify, on d7810a0,** with the job-service image rebuilt and the surefire reports
  deleted first: `clean verify` exits 0 in all four services, and the reports read identity 443
  tests (1 skipped), application 114, job 57, matching 88, no failures or errors; identity's
  `checkstyle:check` exits 0; `test_db_setup.py` 5 passed on a fresh `postgres:18.4-alpine` with a
  password.
- **Hand-offs:** none new. Everything Day 33 does not do is Day 36's by the split (#353) and the
  Out of scope above.
