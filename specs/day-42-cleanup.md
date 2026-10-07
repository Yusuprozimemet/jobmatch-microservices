# Day 42 — the cleanup day: what Phases 3–5 left behind

**Phase:** 5 (closing it; between the Day 28 stop and Phase 7) · **Depends on:** Day 28 ·
**Expected PRs:** 9

The day `plan.md`'s course correction after Phase 5 asks for before Phase 7: it removes what the
extractions left with no caller, makes the copies that should agree agree, and puts the five
services' logs and metrics in the shape Phase 7 will send to CloudWatch. It picks up the ten
hand-offs Day 28 left to it: H28.3, H28.6, H28.7, H28.9, H28.12, H28.13, H28.14, H28.15, H28.16
and H28.19. Nothing here adds a feature: each item removes something or adds a check of what is
already true.

## Goal
identity-service signs no service tokens and holds no client code for routes it no longer calls;
only `identity_user` is granted CONNECT on `identity_db`, in all three setups; every service writes
JSON logs in its image and none writes a user's email; Prometheus scrapes all five services; and
the working docs describe the five-service system.

## In scope
- **identity's service issuer goes (H28.6).** `ServiceTokens` (issuer `jobmatch-backend`),
  `ServiceSigningKey`, `ServiceJwksController`, the `ServiceToken` interface in `shared/internal`,
  `app.service-jwt.private-key-file` / `SERVICE_JWT_PRIVATE_KEY_FILE` (`application.yaml:56-57`,
  `services/identity-service/.env.example:22-28`, compose `:74`), and the `service.pem` the
  `jwt-key` service writes for it (compose `:34`). `SecurityConfig.java:106-107` stops permitting
  `GET /.well-known/service-jwks.json`, and `application.yaml:95-97` stops excluding it from the
  OpenAPI document. `InternalCallers` stops trusting its own tokens in process (`:42-50`) and
  trusts only its configured list; `ServiceTokens.AUDIENCE`, which it reads (`:86`), moves into it.
  identity calls no service, so nothing signs with the key. `ServiceJwksIT` and
  `ServiceSigningKeyStartupTest` retire with the key: H28.6 left that to this spec, and C42.4
  holds the opposite (no key set, no variable needed).
- **identity's client seam with no caller goes (H28.6, and Day 28's review row "identity's seam
  code with no caller").** `InternalClients` and its test `InternalClientsIT`; `support/StubUpstream`
  and `StubUpstreamTest`, which only `InternalClientsIT` uses; the posting interfaces in
  `shared/jobs` (`PostingLookup`, `PostingShortlist`, `PostingSummary`, `ShortlistedPosting`);
  `shared/dto/PageResponse`; `app.internal.jobs-url` (`application.yaml:72-75`,
  `application-test.yaml:32-37`) with `harness.job-service-url` (`IntegrationTest.java:72-74`); and
  compose's `INTERNAL_JOBS_URL` on identity (`:55`). Also `@CurrentUserId`, `CurrentUserIdResolver`
  and its registration in `IdentityWebConfig`, and `TokenSubject` with the line that writes it
  (`AccessTokenAuthentication.java:77`): since Day 25 no identity parameter carries the annotation
  and nothing reads the subject (application-service has its own copy). H28.6 did not list these
  three; they are the same kind. The comments that name them go too (`IdentityDirectory.java:18`,
  `PrincipalEmail.java:14`, `TopMatchesTrustTheSubjectIT.java:18`). `ProfileDirectory` and
  `ProfileSnapshot` stay: `InternalProfileController` serves them.
  - The harness's job-service container is started by the suppliers registered in
    `IntegrationTest` (`:63-65`, `:74`). The track that removes the last of them checks that the
    container still starts before a context asks for its key set or URL, and says how.
- **identity's tests stop minting with identity's key (Track 0).** Eight test classes inject
  identity's `ServiceToken`: `JobServiceHarnessIT`, `JobsLeftTheMonolithIT`, `PostingBatchIT`,
  `PostingShortlistIT`, `ShortlistOrderIT`, `InternalRoutesIT`, `InternalUsersIT`, and
  `ServiceJwksIT` (retires in A1). Four of their methods assert the self-trust this day removes and
  retire with it, since a `TestServiceCaller` copy of each already exists:
  `InternalRoutesIT.theMonolithsOwnTokenPassesSecurity` (`:33`),
  `InternalUsersIT.theMonolithsOwnTokenIsTrustedToo` (`:36`),
  `JobServiceHarnessIT.theMonolithsOwnTokenGetsPastJobServicesChain` (`:42`) and
  `...theMonolithsOwnTokenGetsPostingsFromJobService` (`:46`). The rest mint with
  `TestServiceCaller`, which identity and job-service's container already trust
  (`IntegrationTest.java:61-62`, `support/JobService.java:186-188`). `ShortlistFixture` and
  `PostingBatchIT` get their own types in place of `shared/jobs`'.
- **job-service stops requiring identity as a caller (H28.6).** `BACKEND_KEY_SET_URL` and
  `app.internal.backend-key-set-url` go (`InternalCallers.java:31-60`, `application.yaml:14-17,35`,
  compose `:117`), with `TestCallers.BACKEND`, `JobServiceTest:27`, `InternalCallersTest:40,47`, and
  `InternalAccessTest:32,56,63`, where the `jobmatch-backend` token moves into `anyoneElseIs401`: it
  is then as unknown as any other issuer. The harness's key server for it goes
  (`support/JobService.java:13,45,164-165,184-185`).
- **identity stops trusting job-service (H28.6).** Compose's `APP_INTERNAL_TRUSTEDISSUERS_0_*` for
  `jobmatch-job-service` on identity (`:56-57`) goes, and the list renumbers: job-service calls only
  application-service. The harness entry (`IntegrationTest.java:63-64`) stays only if a test still
  sends identity a job-service token; the track says which.
- **The docs the removals make false** change in the PR that makes them false:
  `services/identity-service/README.md:112-115,142`, `docs/auth.md:274,307-318,325,434,508`,
  `docs/configuration.md:113,198,329,346`.
- **One CONNECT grant on `identity_db` (H28.3).** Today the three role-setup copies grant CONNECT on
  `identity_db` to three, four and six roles: `scripts/db-init/10-module-roles.sh:32`
  (`identity_user, matching_user, jobs_user`), `PostgresContainer.java:196` (those and
  `analytics_user`), and `scripts/db-setup.py:399-404` (every role but `applications_user`). After:
  `identity_user` alone, in all three. `app_user`, which runs the migrations in production, keeps
  connecting as a member of `identity_user` (`db-setup.py:390-392`); compose and the harness run
  them as the superuser. `db-setup.py` names the list in one constant and revokes CONNECT by name
  from every other role, as it already does for `applications_user` (`:399-403`): a rerun on a
  production database must take away what earlier runs granted. The roles, schemas and grants
  inside the database stay (H28.2, Phase 7).
- **A test compares the three copies**, in identity's suite (it reads the two scripts from the
  repository and `PostgresContainer`'s list). `identity-service-ci-cd.yaml`'s path filter (`:6-20`)
  gains `scripts/db-init/**` and `scripts/db-setup.py`, so a PR that changes one runs it.
- **`ModuleConnectionsIT` and `RefreshTokenGrantsIT`** stop logging in to `identity_db` as
  `jobs_user` and `matching_user` to show what they cannot read there, and assert instead that they
  cannot connect, as `applicationsCannotConnectToIdentityDb` does.
- **The pipeline's read of `identity_db` goes.** `data/src/publishing/sync.py`'s
  `read_backend_table` reads the `app` schema as `analytics_user`. It has no caller, and since
  V12–V14 `app` holds no application table. It goes, with what offers or describes it:
  `data/README.md:212-214`, `:632-645` ("Reading the app's data") and `:646-662`, and
  `services/identity-service/docs/configuration.md:289-296`. This takes an extension point away
  from the pipeline's trainees; a later need asks identity for an internal route.
- **A null reason does not drop a batch (H28.7).** A test stores 26 scores, one with a `null`
  reason, through `JobMatchScoreRepository.saveScores`, and reads every one back. The guard exists
  (`JobMatchScoreRepository.java:119-122`); nothing holds it.
- **Structured production logs in all five services (H28.9).** identity's image starts with the
  `prod` profile (`Dockerfile:40`), whose `application-prod.yaml:8-19` sets
  `logging.structured.format.console: logstash`. The gateway, job-, matching- and
  application-service get the same setting, active in their images and not in their tests or a
  local `mvn spring-boot:run`. How the image turns it on (a `prod` profile, as identity does, or an
  environment variable in the Dockerfile) is the track's choice; it is the same in all four.
  identity's comment that "the OpenTelemetry agent puts them in MDC" (`application-prod.yaml:13-14`)
  is wrong since the agent was dropped (`Dockerfile:42`); it says what does.
- **No user's email in identity's logs (H28.14).** The seven lines
  (`AuthenticationService.java:64,199`, `EmailService.java:45,47`,
  `OAuth2LoginSuccessHandler.java:58,67,94`) log the user's id, or nothing that names the user.
  `EmailService.java:32` logs the SMTP account, the service's own address; it stays.
- **Prometheus scrapes application-service (H28.13):** a fifth job in
  `observability/prometheus.yml`, `application-service:9090`, as the other four.
- **The working docs describe five services (H28.12).** `CLAUDE.md:120,126` and
  `.claude/agents/implementer.md:36` say V1–V16; `.claude/agents/spec-auditor.md:43` greps no
  `backend` directory; compose's comments stop calling identity "the monolith's" (`:26,184,229`).
  Comments that defer to a dropped or unwritten day say what holds instead:
  `services/api-gateway/pom.xml:55` and `ManagementPortTest.java:21` ("Day 34's probes", Day 37),
  identity's `application.yaml:129-132` (Kubernetes readiness, Day 31), matching-service's
  `UserExistenceClient.java:19` ("until Day 21").
- **identity's README and docs link to files that exist (H28.15).** 74 of 129 relative links in
  `services/identity-service/README.md` and `services/identity-service/docs/*.md` point nowhere.
  A script checks them, and identity's workflow runs it.
- **CI's through-the-gateway run adds `GoogleLinkClaimIT` and `PendingGoogleLinksIT` (H28.16)**
  (`identity-service-ci-cd.yaml:116`), and drops `ServiceJwksIT` with the class (A1).
- **The stale architecture diagram goes (H28.19, a departure).** `docs/target-architecture.svg`
  and `.png` show AKS, Helm, Argo CD and Phase 6's functions, and nothing links to them since #326.
  They are deleted, not redrawn: Phase 7's days are not rewritten yet, so a drawing of ECS today
  would be a guess at what they decide. H42.1 hands the drawing to the day that deploys.

## Out of scope
- The `matching` and `applications` roles and schemas in `identity_db`, and `MATCHING_DB_PASSWORD`
  — the Phase 7 rewrite (H28.2).
- Renaming identity's trace service name, `OTEL_SERVICE_NAME=jobmatch-backend` (`Dockerfile:48`),
  and the Grafana dashboard's `uid` — Phase 7's observability day (H42.2). It names no issuer, and
  C42.1's grep leaves Dockerfiles out for that reason.
- The service-token code in job-, matching- and application-service, which do call each other.
- Links outside identity-service's README and `docs/` (32 broken elsewhere, mostly in
  `docs/original-readme.md`, the monolith's README kept as history).
- The maintainer's local database: its grants change only when `db-setup.py` is rerun against it
  or they recreate the volume. Never `down -v` on their project (`CLAUDE.md`).

## Tracks

| Track | Owner | Work |
|---|---|---|
| 0 | | identity's tests mint with `TestServiceCaller`; the four self-trust methods retire; the null-reason batch test |
| B | | job-service drops `BACKEND_KEY_SET_URL`; the harness's backend key server goes |
| A2a | | `InternalClients`, `StubUpstream`, `shared/jobs`, `PageResponse`, `jobs-url`, compose's `INTERNAL_JOBS_URL` |
| A2b | | `@CurrentUserId`, `CurrentUserIdResolver`, `TokenSubject` |
| A1 | | identity's service issuer, minter and key; the permit; compose's identity trust list; their docs |
| C | | One CONNECT grant in the three copies, the comparing test and its CI path, the two ITs, `read_backend_table` and its docs |
| D | | JSON logs in four more images, no emails in logs, application-service scraped |
| E1 | | The working docs and stale day comments, CI's gateway list, the diagram |
| E2 | | identity's links and the script that checks them |

Order: 0 first (it holds the tests B and A1 change underneath). B before A1: the harness's
job-service reads identity's key through `ServiceSigningKey` until B
(`support/JobService.java:164`). A2a before A1: the `InternalClients` bean takes the `ServiceToken`
bean A1 removes (`InternalClients.java:29,39`). E2 after A1: `auth.md:317` links
`ServiceTokens.java`. C and D depend on none of them. Deleted lines count against the 400-line
gate (`pr-checks.yml:78-87`): A2 is split for that reason (about 650 lines of deletion together),
E1 carries the 280-line SVG, and if A1 passes 400 its docs go in a PR of their own.

## Acceptance criteria
- [ ] C42.1 **new** — `git grep -nE "ServiceTokens|ServiceSigningKey|ServiceJwks|service-jwks|ServiceToken\b|service-jwt|SERVICE_JWT_PRIVATE_KEY_FILE|jobmatch-backend" -- services/identity-service ':!*Dockerfile' ':!services/identity-service/app/src/test/java/nl/hackyourfuture/project/backend/tokens/RetiredServiceIssuerIT.java' | grep -v '\.withEnv("SERVICE_JWT_PRIVATE_KEY_FILE"'`
      finds nothing, and `grep -nE "/keys/service\.pem" docker-compose.yml` finds nothing. The
      excluded lines give the harness's job-, matching- and application-service containers their
      own keys (`support/JobService.java:177`, `MatchingService.java:74`, `ApplicationService.java:88`);
      `RetiredServiceIssuerIT` is C42.4's test. Red today: the first finds lines in 30 files,
      including `.md`, `.env.example` and the permit in `SecurityConfig`; the second finds compose
      `:34` and `:74`.
- [ ] C42.2 **new** — `git grep -nE "InternalClients|StubUpstream|shared\.jobs|PageResponse|jobs-url|job-service-url|INTERNAL_JOBS_URL|CurrentUserId|TokenSubject" -- 'services/identity-service/*/src/*' | grep -v '\.withEnv("INTERNAL_JOBS_URL"'`
      finds nothing, and `services/identity-service/shared/src/main/java/nl/hackyourfuture/project/backend/shared/jobs`
      does not exist. The excluded lines are `MatchingService.java:77` and
      `ApplicationService.java:96`, which point *those* containers at job-service. Red today: lines
      in 22 files, the comments named in In scope among them.
- [ ] C42.3 **new** — `git grep -n "BACKEND_KEY_SET_URL\|backend-key-set-url" -- services/job-service docker-compose.yml services/identity-service`
      finds nothing, `git grep -n "jobmatch-backend" -- services/job-service/src/main services/identity-service/app/src/test/java/nl/hackyourfuture/project/backend/support`
      finds nothing, and job-service's `InternalAccessTest.anyoneElseIs401` sends a
      `jobmatch-backend` token signed by a key it served before and gets 401. A test in job-service
      starts its context with no `BACKEND_KEY_SET_URL`. Red today: the first grep finds 8 files
      (identity's `auth.md` and `configuration.md` among them, which B updates), the second 3 lines; the context fails, "BACKEND_KEY_SET_URL is not set".
- [ ] C42.4 **new** — `tokens/RetiredServiceIssuerIT`: identity starts with
      `SERVICE_JWT_PRIVATE_KEY_FILE` unset; `GET /.well-known/service-jwks.json` answers 401 (the
      permit gone, `anyRequest().authenticated()`); and `/internal/users/{id}` answers 401 to a
      token with issuer `jobmatch-backend`, audience `jobmatch-internal`, signed with the key
      `TestSigningKey.servicePath()` holds. Red today: 200 with one RSA key; 204 to the token
      (identity trusts its own tokens in process, `InternalUsersIT:36`); without the variable the
      context does not start (`ServiceSigningKeyStartupTest`).
- [ ] C42.5 **new** — `docker compose --env-file .env.example config identity-service | grep -E "INTERNAL_JOBS_URL|TRUSTEDISSUERS_[0-9]+_NAME"`
      prints `jobmatch-matching-service` and `jobmatch-application-service` and nothing else. Red
      today: `INTERNAL_JOBS_URL` and three names.
- [ ] C42.6 **hold** — identity's `/internal/**` answers its trusted callers and refuses the rest:
      `InternalRoutesIT`, `InternalUsersIT`, `InternalProfilesIT`, `MatchingServiceTrustIT` and
      `ApplicationServiceTrustIT` pass, as do job-service's `InternalAccessTest` and identity's
      `JobServiceHarnessIT`, `PostingBatchIT` and `PostingShortlistIT`. After Track 0,
      `git grep -lE "private ServiceToken |ServiceToken serviceToken" -- services/identity-service/app/src/test`
      finds only `ServiceJwksIT`, and after A1 nothing (8 files today).
      broken: identity's `InternalCallers` built with an empty trusted list → 12 of 32 failed in
      the five identity classes, e.g. `ApplicationServiceTrustIT.applicationServicesTokenReachesIdentitysUserRoute`
      expected 204 but was 401; reverted, 32 of 32 (spec-change PR, on 3481dea). The job-service
      side of the hold is broken by Track B, which edits `InternalAccessTest`.
- [ ] C42.7 **new** — the three role-setup copies grant CONNECT on `identity_db` to `identity_user`
      alone. A test in identity's suite reads the list from `scripts/db-init/10-module-roles.sh`,
      `scripts/db-setup.py` and `PostgresContainer`, fails if any differs from `identity_user`, and
      runs when a PR changes either script (`identity-service-ci-cd.yaml`'s path filter). Red
      today: the lists are 3, 4 and 6 roles; no test reads them; the filter names neither script.
- [ ] C42.8 **new** — in the harness, `jobs_user` and `matching_user` cannot connect to
      `identity_db`: `ModuleConnectionsIT` and `RefreshTokenGrantsIT` assert it, as
      `applicationsCannotConnectToIdentityDb` does for `applications_user`. Red today: both log in
      (`ModuleConnectionsIT.java:35,38`).
- [ ] C42.9 **new** — the grants on the database itself name only `identity_user`:
      `SELECT string_agg(a.grantee::regrole::text, ',' ORDER BY 1) FROM pg_database d, aclexplode(d.datacl) a WHERE d.datname = 'identity_db' AND a.privilege_type = 'CONNECT' AND a.grantee <> d.datdba`
      prints `identity_user` on a fresh compose volume (`-p day42`, as the admin), and on a scratch
      Postgres after `db-setup.py` has run from `main` and then from Track C
      (`PYTHONIOENCODING=utf-8` on Windows). Red today: `identity_user,jobs_user,matching_user` in
      compose; six roles after db-setup.
- [ ] C42.10 **new** — `git grep -n "read_backend_table\|Reading the app's data" -- data/` finds
      nothing. Red today: 3 lines (`sync.py:62`, `README.md:632,636`).
- [ ] C42.11 **hold** — `saveScores` with 26 scores, one with a `null` reason, stores all 26 items,
      the null one without a `reason` attribute (a matching-service test on `DynamoDbContainer`).
      Broken by the spec-auditor on a scratch test: the guard removed
      (`JobMatchScoreRepository.java:120`), DynamoDB rejected the batch and nothing was stored,
      "Expected size: 26 but was: 0". broken: to be recorded in Track 0 on the committed test.
- [ ] C42.12 **new** — each of the five services writes JSON in its image: in a compose project
      started with `up --wait`, for each of `identity-service`, `api-gateway`, `job-service`,
      `matching-service`, `application-service`,
      `docker compose -p day42 logs --no-log-prefix <service> | grep -m1 "Started "` is one JSON
      object with `@timestamp`, `level` and `message`. Red today: 1 of 5, identity's.
- [ ] C42.13 **hold** — the harness's tests that read container logs pass unedited with the
      rebuilt images: `TopMatchesTracedIT`, `JobServiceObservedIT`, `MatchingServiceHarnessIT` and
      `ApplicationServiceHarnessIT`, in process and through the gateway. They match a line by the
      trace id and the path it contains (`TopMatchesTracedIT.java:105-115`), which a JSON line also
      holds. Only with all four harness images rebuilt from Track D (Verify) does this check JSON;
      a stale image passes on text logs.
      broken: to be recorded in Track D — job-service's JSON leaving out the trace id
      (`logging.structured.json.exclude`).
- [ ] C42.14 **new** — `git grep -nE 'log\.\w+\(.*,\s*(email|toEmail|normalizedEmail)\b' -- 'services/identity-service/*/src/main/*'`
      finds nothing, and a test with captured output requests a password reset for a password
      account, waits for the asynchronous send, and changes the password with
      `PATCH /api/auth/password`, and finds the account's email in no line logged. Red today: the
      grep finds 7; the test finds the email at `EmailService.java:47` (the test profile's SMTP at
      `localhost:1025` refuses) and `AuthenticationService.java:199`.
- [ ] C42.15 **new** — with `docker compose -p day42 --env-file .env.example --profile obs up -d --build --wait`,
      `curl -s localhost:9091/api/v1/targets` lists `jobmatch-application-service` with
      `"health":"up"`, and five targets in all; `grep -c job_name observability/prometheus.yml`
      gives 5. Red today: 4, and no application-service target. (Prometheus is published on 9091,
      compose `:355`.)
- [ ] C42.16 **new** — `git grep -n "V1–V14" -- CLAUDE.md .claude/agents` finds nothing,
      `.claude/agents/spec-auditor.md:43`'s grep names no `backend` directory, and
      `git grep -ni "monolith" -- docker-compose.yml` finds nothing. Red today: 3, 1 and 3 lines.
- [ ] C42.17 **new** — a script reports 0 broken relative links in
      `services/identity-service/README.md` and `services/identity-service/docs/*.md`, exits
      non-zero on one, and runs in identity's workflow. Red today: 74 of 129 broken (each `](path)`
      that is not a URL or an anchor, resolved against its file's directory).
- [ ] C42.18 **new** — `identity-service-ci-cd.yaml`'s gateway run names `GoogleLinkClaimIT` and
      `PendingGoogleLinksIT`, and both pass with `-Dharness.gateway=true`. Red today: the workflow
      names neither.
- [ ] C42.19 **new** — `git ls-files docs/target-architecture.*` prints nothing. Red today: two
      files.
- [ ] C42.20 **hold** — the Day 1–4 `contract/` suite passes unedited:
      `git diff <spec-change merge> <close> -- services/identity-service/app/src/test/java/nl/hackyourfuture/project/backend/contract/`
      is empty, in process and through the gateway. `ObservabilityLoggingIT` is in it and keeps
      setting the JSON format itself (`:34`).
      broken: `ObservabilityLoggingIT`'s format `logstash` → `ecs` → `writesEachLogLineAsJson`
      NPE at `:46` on `get("level")` (ECS writes `log.level`; it writes `@timestamp` too). Run by
      the spec-auditor on 3481dea, reverted.
- [ ] C42.21 **hold** — every suite and checkstyle pass, counted from fresh surefire reports:
      identity's `clean verify` and `checkstyle:check`, and `verify` in job-, matching-,
      application-service and the gateway. Baseline on 3481dea, identity: 435 tests in 82 classes,
      0 failures, 1 skipped; checkstyle 0. The close states the count and what moved it: retired
      `ServiceJwksIT` (5), `ServiceSigningKeyStartupTest`, `InternalClientsIT`, `StubUpstreamTest`,
      `InternalCallersTest`'s self-trust cases and the four self-trust methods; added C42.4's,
      C42.7's, C42.11's and C42.14's tests.

## Verify
```bash
for s in job-service matching-service application-service api-gateway; do docker build -t jobmatch-$s:harness services/$s; done
cd services/identity-service && rm -rf */target/surefire-reports && ../../mvnw clean verify && ../../mvnw -B checkstyle:check
../../mvnw -B verify -pl app -am -Dharness.gateway=true -Dtest='nl.hackyourfuture.project.backend.contract.*IT,GoogleLinkClaimIT,PendingGoogleLinksIT,TopMatchesTracedIT,JobServiceObservedIT' -Dsurefire.failIfNoSpecifiedTests=false
cd ../.. && for s in job-service matching-service application-service api-gateway; do (cd services/$s && ../../mvnw -B verify); done
# C42.1–C42.3, C42.10, C42.14, C42.16, C42.19: the greps above print nothing
docker compose -p day42 --env-file .env.example --profile obs up -d --build --wait   # C42.9, C42.12, C42.15
docker compose -p day42 down -v                                                       # its own volume only
```

## Notes
- Hand-offs this day takes: H28.3, H28.6, H28.7, H28.9, H28.12, H28.13, H28.14, H28.15, H28.16,
  H28.19 (the last as a departure, above).
- **Pace.** Nine track PRs. Most of the day is deletion, and deletions count against the gate;
  the risk is in what a deletion takes with it in the harness (Track 0, B before A1).
- **Audit, before the spec-change PR.** The plan-auditor on the day against `plan.md`: everything
  the course correction asks is covered, the additions are justified, and nothing conflicts with
  Phase 7 (the Lambdas reach identity through its trusted list, which stays). The spec-auditor on
  3481dea: identity 435 tests, 0 failures, 1 skipped, checkstyle 0; every grep red as stated once
  corrected; C42.11's and C42.20's breaks run. What they changed is in the Defect lines below.
- **Defect** — found: spec-change PR (self-check) · cause: spec · the draft's counts were written
  before they were run: C42.1 said 17 files (23), C42.2 16 (21), C42.3 6 (8); compose lines
  `:52-54` were `:55-57`; and C42.1's `service.pem` grep matched the other services' keys.
- **Defect** — found: auditor · cause: spec · C42.1 could not pass: its grep matched the harness
  lines that give job-, matching- and application-service their own keys, and C42.4's test needs
  the name `jobmatch-backend` in the tree C42.1 and C42.3 grepped. Both now exclude by name.
- **Defect** — found: auditor · cause: spec · C42.4 said "any status but 200", but
  `SecurityConfig.java:106-107` still permitted the path and no item removed it. Now in scope;
  C42.4 asks for 401.
- **Defect** — found: auditor · cause: spec · Track 0 said all seven classes move to
  `TestServiceCaller`; four methods assert the self-trust the day removes and would have become
  copies of tests that exist. They retire.
- **Defect** — found: auditor · cause: spec · C42.14's red named `AuthenticationService.java:199`
  for the reset path; `:199` is the password change. The test now changes the password too.
- **Defect** — found: auditor · cause: spec · C42.13 named `TraceContinuedIT`, which reads identity's
  in-process output and sets the format itself, left out `JobServiceObservedIT`, and Verify built
  only job-service's image, so the hold would have passed on text logs.
- **Defect** — found: auditor · cause: spec · C42.7 and C42.9 kept `app_user` as an exception;
  it connects through its membership of `identity_user` (`db-setup.py:390-392`), so the
  exception could not be told apart from its absence. C42.9 now reads the database's own grants.
- **Defect** — found: auditor · cause: spec · "Expected PRs: 7" and "A1 is the largest": A2's
  deletions alone pass 400 lines, and E carries the SVG. Now 9, with A2 and E split.
- **Defect** — found: auditor · cause: spec · out of scope by omission: `StubUpstream`, the
  service key set's permit and OpenAPI exclusion, job-service's `InternalAccessTest`, the docs each
  removal falsifies, the CI path filter C42.7 needs, and `ServiceTokens.AUDIENCE`'s new home.
- **Defect** — found: auditor · cause: spec · C42.20's expected report said `@timestamp`; the
  `ecs` format writes it too, and the test failed on `level`. C42.11's said "a null attribute";
  DynamoDB rejected the batch and stored nothing. C42.19's grep half printed nothing already, and
  C42.1, C42.3 and C42.10 counted build output (`target/`, `__pycache__`): all now `git grep` or
  `git ls-files`.
- **Defect** — found: spec-change PR (self-check) · cause: spec · the first `git grep` rewrite of
  C42.2 and C42.14 used the pathspec `*/src`, which matches the directory and none of the files
  in it: both printed nothing on today's code, so they could not fail. Now `*/src/*`, and run: 22
  files and 7 lines.
- **Hand-off** H42.1 → Day 35: draw the architecture that was deployed, from the Terraform, in
  place of the diagram this day deletes. Day 35's file is still a Helm draft; its rewrite carries
  this.
- **Hand-off** H42.2 → Day 34: identity's trace service name is still `jobmatch-backend`
  (`Dockerfile:48`), and so is the Grafana dashboard's `uid`; Phase 7's observability names them
  after the service. Day 34's file is still a Helm draft; its rewrite carries this.
