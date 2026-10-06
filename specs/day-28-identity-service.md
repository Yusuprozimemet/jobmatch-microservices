# Day 28 — identity-service, and the monolith is gone

**Phase:** 5 · **Depends on:** Day 27 · **Expected PRs:** 7 (Tracks 0, A1, A2, B, C, E, D)
**Status:** read against the code by the spec-auditor on a410461; revised in the spec change
(KAN-76); after the plan-auditor's whole-plan report, Track E and the rest of the review
list added (KAN-83).

Last of Phase 5 (**26 → 27 → 25 → 28**), and the stopping point: the architecture is evaluated
before Phases 6–7 are started or rewritten (`plan.md`, "Course correction").

## Goal
The last module leaves `backend/`. Five services, no monolith.

## In scope
- **What is left of the monolith becomes `services/identity-service`,** by `git mv` of all of
  `backend/`: the `shared`, `identity` and `app` modules, their poms, the Dockerfile, `docs/`,
  and the test suite. The three modules stay three modules and the Java packages stay
  `nl.hackyourfuture.project.backend.*`: folding them or renaming packages turns a rename into
  thousands of counted lines, and is on the review list instead.
- **The Day 1–4 suite moves with it, unedited.** `contract/`, the Day 01 harness self-tests and
  `support/` live in `backend/app/src/test` (about 12,200 lines). The suite boots the
  application in process (`support/IntegrationTest.java:36`) and, with
  `-Dharness.gateway=true`, behind the gateway container (`backend-ci-cd.yaml:110-113`). Both
  modes keep running, from `services/identity-service`. `support/` may change (paths, the
  database name); `contract/` may not.
- **Build tooling leaves `backend/` first (Track 0).** Four service poms take checkstyle from
  `../../backend/checkstyle.xml` (`api-gateway:107`, `application-service:135`,
  `job-service:110`, `matching-service:165`); all five workflows run `backend/mvnw` and watch
  `backend/checkstyle.xml`; no service has its own wrapper. `mvnw`, `mvnw.cmd`, `.mvn/` and
  `checkstyle.xml` move to the repository root.
- **Every reader of identity's URLs and key sets** follows the move (Track A2):
  `BACKEND_KEY_SET_URL` (`docker-compose.yml:103`), `INTERNAL_IDENTITY_URL` (`:169`, `:214`),
  `BACKEND_URL` (`:253`, which also gives the gateway its `jwks-url`), the gateway's
  `depends_on` (`:276`), `observability/prometheus.yml:9-12` (`backend:9090`), and the harness's
  `support/JobService.java:184`. The compose service is named `identity-service`.
- **The gateway** sends `/api/**` and `/.well-known/jwks.json` to identity-service: today's
  catch-all route `backend` (`Routes.java:86-87`), renamed with its property
  (`gateway.backend-url` → `gateway.identity-service-url`). What is left under `/api/**` after
  job-, matching- and application-service took theirs is identity's (`auth`, `profile`, `users`,
  `oauth2`, `login/oauth2`, `docs`).
- **`identity_db` is `project_db` renamed** (Track B). The database name is configuration
  everywhere (`.env.example:2`, `db-setup.py:60`, `PostgresContainer.java:61,195-196`, the
  `db-init` scripts read `$POSTGRES_DB`). An existing database is renamed in place with
  `ALTER DATABASE project_db RENAME TO identity_db`, written as a runbook step, not run by any
  script. The leftover `applications` and `matching` schemas come with it; they are on the
  review list.
- **The Google redirect URI does not move.** It is `${APP_BASE_URL}/api/login/oauth2/code/google`
  (`backend/app/.../application.yaml:48,64`), reached through the frontend and the gateway's
  `/api/**`. The service behind the gateway changes, the URI does not: no Google console change.
  Compose forwards no Google credentials today (`docker compose config | grep -ci
  google_client` gives 0), so sign-in is off there (`GoogleOAuth2Config:20`); Track C forwards
  `GOOGLE_CLIENT_ID` and `GOOGLE_CLIENT_SECRET`, empty by default.
- **The outbox relay (Day 26)** moves with identity; it is in `backend/identity` already.
- **CI:** `backend-ci-cd.yaml` becomes `identity-service-ci-cd.yaml`, and its image push
  (`:141-159`, `ghcr…/backend`) becomes `identity-service`. Its job keeps the name "Backend
  gate" until the maintainer swaps the required check (H28.1).
- **Docs and scripts** that name `backend/` or the `backend` service: `scripts/dev-up.sh:13`,
  `scripts/jwt-key.sh:8-12`, `scripts/README.md:16`, `CLAUDE.md` ("From `backend/`", "Where
  things live"), `.claude/agents/implementer.md:27`, `docs/runbooks/jobs-db.md:62`, and the
  root `README.md`'s "Running it" and "Repository layout" sections. The rest of the README is
  the measurement record and stays as it is.
- **The stopping-point review (Track D).** The plan-auditor on the whole plan, and this list of
  what no day owns, each kept, dropped or handed to the Days 29–37 rewrite:
  - the `matching` role and schema, `MATCHING_DB_PASSWORD` and the grants to `matching_user`
    in V12–V15 (Days 23–24); V14 raises without the role, so it cannot simply be dropped;
  - the `applications` role and schema left in `project_db` after `saved_jobs` moved: V13
    raises without them, V12 and V14 grant to the role (Day 25);
  - `jobs_user`'s CONNECT on `project_db` (`db-init/10-module-roles.sh:32`,
    `db-setup.py:395-404`, `PostgresContainer.java:196`) (Day 20);
  - the DLQ depth alarm, the DLQs' retention (SQS's default, 4 days, holds a failed message's
    `userId`), and the topic, queues, DynamoDB table and `apps_db` in Terraform (Days 22, 25,
    26, 27);
  - matching-service's `PROFILE_CACHE_WINDOW` and a task role in place of dummy AWS keys
    (Days 22, 24);
  - matching-service's and now identity-service's `nl.hackyourfuture.project.backend.*`
    packages, `ScoreTableStartupTest`'s package, and identity-service's three Maven modules
    (Days 23–24, this day);
  - the public `/api/docs` no longer lists Jobs (Day 17);
  - the service issuer `jobmatch-backend` and its key (Day 39): identity calls no service
    today (only the `jobs-url` property is left, `application.yaml:75`), yet job-service will not
    start without `BACKEND_KEY_SET_URL` (`InternalCallers.java:47-52`);
  - no test for a `null` reason dropping a 25-item DynamoDB batch (Day 22);
  - the missing `phase-3` tag (Day 20);
  - job-service's (20 files) and application-service's (22 files) `…project.backend` packages,
    beside matching's and identity's;
  - dummy AWS keys on identity-service (`docker-compose.yml:84-85`) and application-service
    (`:238-239`), beside matching-service's (`:181-182`, `:198-199`);
  - `matching_user`'s and the harness's `analytics_user`'s CONNECT on `identity_db`, beside
    `jobs_user`'s; the three role-setup copies (`db-init/10-module-roles.sh:32`,
    `PostgresContainer.java:196`, `db-setup.py:404`) do not grant the same roles;
  - `/api/docs` lacks Saved Jobs and top-matches as well as Jobs: only identity serves springdoc.
  - only identity-service has structured production logging (`application-prod.yaml`); the
    other four set only the correlation pattern (Day 40);
  - identity's seam code with no caller: the posting interfaces in `shared/jobs`,
    `InternalClients`, `PageResponse`, and compose's `INTERNAL_JOBS_URL` "for saved counts",
    which application-service has served since Day 25;
  - the hand-kept copies of the service-token, security and tracing classes across services
    (four of `ServiceTokens`, differing only by issuer), with no test comparing them;
  - the runbooks for an existing database (`docs/runbooks/jobs-db.md`, `identity-db.md`,
    `scripts/copy-saved-jobs.py`), which no day runs (Days 20, 25, this day);
  - the system suite lives in identity-service: its workflow builds every image and pushes
    identity's on any service's change.

## Out of scope
- Functions — Phase 6.
- ECS on Fargate — Phase 7, rewritten after this day's review.
- Renaming packages or folding the three Maven modules — on the review list.
- A harness that runs the suite against the compose stack: none exists, and the in-process and
  through-the-gateway runs already cover the contract.

## Tracks

| Track | Owner | Work |
|---|---|---|
| 0 | | `mvnw`, `mvnw.cmd`, `.mvn/`, `checkstyle.xml` to the repository root; the backend and four service poms and five workflows point at them. `backend/` builds as before. |
| A1 | | `git mv backend services/identity-service`; the workflow renamed and repointed; the suite runs from there in both modes. |
| A2 | | Compose service `identity-service`, every URL reader listed above, the gateway's route and property, prometheus, the harness's `JobService`. |
| B | | `project_db` → `identity_db`: `.env.example`, `db-setup.py`, `PostgresContainer`, the runbook step. |
| C | | Google credentials forwarded in compose; scripts, `CLAUDE.md`, `implementer.md`, the runbook path, the README's two sections; the job renamed "Identity Service gate" once H28.1 is done. |
| E | | identity-service gets the compose healthcheck the other services have (readiness on its management port), and the gateway waits for it healthy, not started. |
| D | | The plan-auditor on the whole plan; the review list decided; `plan.md` changed or confirmed (a plan-change PR if it changes). |

In order: each depends on the one before, except B, which needs only A1, and E, added after C
merged, which lands before D so that C28.5 can be run at the close. A1 is mostly renames,
which the size gate counts as their changed lines only (`git diff --numstat`, `pr-checks.yml:79`);
if the path changes in poms, workflows and `support/` still pass 400, it takes an `Oversized:`
line, as Days 21 and 25 did.

## Acceptance criteria
- [ ] C28.1 **new** — `backend/` no longer exists: `git ls-files backend | wc -l` gives 0. Red
      today: 232.
- [ ] C28.2 **hold** — Every Day 1–4 test passes **unedited** from `services/identity-service`:
      `./mvnw clean verify` 0 failures, 0 errors (435 tests, 1 skipped on a410461), and CI's
      through-the-gateway run 0 failures (229 tests on a410461); `git diff a410461 -M --stat --
      '*/contract/*'` shows renames only.
      broken: `ProfileResponse.userId` serialised as `"user"` → `ProfileIT` 11 run, 4 failures
      (`eachUserReadsOnlyTheirOwnProfile`, expected "6d24…") (auditor, a410461).
- [ ] C28.3 **new** — No pom or workflow reads from `backend/`: `grep -rn "backend/" services/*/pom.xml
      .github/workflows` finds nothing. Red today: 4 poms and 5 workflows.
- [ ] C28.4 **new** — `docker compose --env-file .env.example config --services` lists
      `identity-service`, `api-gateway`, `job-service`, `matching-service`,
      `application-service`, `frontend`, `db`, `localstack`, `dynamodb`, `jwt-key`, and not
      `backend`; and `grep -rn "backend:" docker-compose.yml observability/` finds nothing. Red
      today: `backend` listed, no `identity-service`.
- [ ] C28.5 **new** — `docker compose -p day28 --env-file .env.example up -d --build`: every
      service healthy, and through the gateway (`localhost:${GATEWAY_PORT}`) register 201, login
      200, `GET /api/profile` 200, `GET /api/jobs` 200, `POST /api/saved-jobs` 201, top-matches
      200, `DELETE /api/users/me` 204, and `GET /.well-known/jwks.json` 200 with one RSA key.
      Red today: no `identity-service` to start.
- [ ] C28.6 **hold** — `tokens/ServiceJwksIT` green in process (5 run, 0 failures on a410461);
      the gateway still does not route `/.well-known/service-jwks.json` (its `SecurityTest`).
      broken: to be recorded in Track A2, the service key set path routed by the gateway.
- [ ] C28.7 **new** — In compose, the database is `identity_db`: `psql -U admin -lqt`
      (`.env.example:3`) on a fresh `-p day28` volume lists `identity_db`, `jobs_db`, `apps_db` and not
      `project_db`; the harness's `PostgresContainer` names `identity_db`. Red today:
      `project_db`.
- [ ] C28.8 **hold** — Google sign-in, including the account-linking branch:
      `contract/AuthGoogleSignInIT`, `GoogleLinkClaimIT`, `PendingGoogleLinksIT` green in process
      and through the gateway after every track. The redirect URI is unchanged
      (`/api/login/oauth2/code/google` under `APP_BASE_URL`).
      broken: to be recorded in Track A1, the redirect path changed.
- [ ] C28.9 **new** — Compose forwards `GOOGLE_CLIENT_ID` and `GOOGLE_CLIENT_SECRET` to
      identity-service: `docker compose --env-file .env.example config | grep -c GOOGLE_CLIENT`
      gives 2. Red today: 0.
- [ ] C28.10 **new** — No doc or script outside `specs/`, `docs/paper/`, the README's
      measurement record and the moved `docs/` names `backend/` or the `backend` service: `grep
      -rln --include='*.sh' --include='*.md' "backend/\|compose.*backend\b\|db, backend" scripts
      CLAUDE.md .claude/agents docs/runbooks` finds nothing (`spec-drift.py` reads past paths
      from git history and is not a doc). Red today: 6 files, `dev-up.sh`, `jwt-key.sh`,
      `scripts/README.md`, `CLAUDE.md`, `implementer.md`, `jobs-db.md`.
- [ ] C28.11 **new** — The README's "Running it" takes a fresh clone to a running app: a person
      who did not write it follows it from `git clone` on a machine with Docker and nothing of
      this project, timed, under 30 minutes, steps and time recorded in Notes. Red today: it
      starts `backend`.
- [ ] C28.12 **new** — The review is in this day's Notes: the plan-auditor's report on the whole
      plan, and each item on the list above kept, dropped or handed on (a `Hand-off` line each),
      with `plan.md` changed or confirmed. Red today: no review.
- [ ] C28.13 **new** — Tag `phase-5` on the closing merge. Red today: `git tag` lists phase-2,
      phase-2.1, phase-4.
- [ ] C28.14 **new** — identity-service has a compose healthcheck and the gateway waits for it:
      `docker compose --env-file .env.example config` shows a `healthcheck` under
      `identity-service` and `condition: service_healthy` for it under `api-gateway`, and on
      `-p day28` `docker inspect --format '{{.State.Health.Status}}'` gives `healthy`. Red today:
      no healthcheck (`docker-compose.yml:39`), the gateway waits on `service_started` (`:284`).

## Verify
```bash
git clone <repo> fresh && cd fresh
docker compose -p day28 --env-file .env.example up -d --build
docker compose -p day28 ps                 # every service healthy
# through the gateway: register 201, login 200, profile 200, jobs 200, save 201, matches 200,
# delete account 204 (C28.5)
docker compose -p day28 exec db psql -U admin -lqt | cut -d'|' -f1   # identity_db, no project_db
docker compose -p day28 down -v            # this project's volume only, never the maintainer's
```
The compose network has a fixed name, `finalproject` (`docker-compose.yml:373-375`): stop the
maintainer's stack first, or the two share it.

## Notes
- **End of Phase 5.** The microservice system in the chart now exists.
- The 30-minute onboarding criterion is not a nicety. Five services is where local
  development quietly stops working, and the team stops testing before pushing.
- From Day 39: the monolith has two keys, identity's user key and its own service key. The
  remainder that becomes identity-service takes both; the service issuer's name
  (`jobmatch-backend` today) is on the review list, and every service that trusts it follows.
- From Day 40: `contract/AuthGoogleSignInIT` and `contract/ObservabilityLoggingIT` stay bound to
  the application context they test today: it moves whole and becomes identity-service's.
  Whether the logging configuration belongs to every service is on the review list's rewrite.
- `queries/JobSavedCountQueriesIT.java:24` still says "Expires on Day 25"; Day 25 kept it on
  purpose (`day-25…md:77`). Its comment is corrected when it moves (it is not in `contract/`).
- **Defect** — found: auditor · cause: spec · "Delete `backend/`" deleted the Day 1–4 suite and
  the harness with it, and "against the full compose stack" named a harness that does not
  exist. The suite now moves with identity (C28.2).
- **Defect** — found: auditor · cause: spec · "`ServiceJwksIT` fails through the gateway (3
  failures, 1 error)" was stale: Day 23 Track C asked it with `direct()`; 5 of 5 green. Now a
  `hold` (C28.6).
- **Defect** — found: auditor · cause: spec · the poms, the workflows and the required "Backend
  gate" check depend on `backend/`; the spec did not say. Track 0 and H28.1.
- **Defect** — found: auditor · cause: spec · "Expected PRs: 3" for 232 files and three kinds of
  work; now 6, and A1 may take `Oversized:`.
- **Defect** — found: auditor · cause: spec · `docker-compose.yml:94` was `job-service:`; the key
  set URL is at `:103`, and five other readers were missing.
- **Defect** — found: auditor · cause: spec · "`identity_db` is its own database" had no method
  and no criterion (C28.7); `DB_JOBS_USER` was no longer in the backend's configuration table;
  `/api/docs` (Day 17), `apps_db` in Terraform (Day 25) and the issuer (Day 39) were missing
  from the review list; Track C missed six files; no criterion had an ID or a tag; the Verify
  could not run on a fresh clone.
- **Defect** — found: auditor · cause: spec · C28.5 asked for "every service healthy", but
  identity-service had no healthcheck: Day 40 handed it to "Day 34's probes", and Day 34 is a
  Helm day that will be rewritten. Now Track E and C28.14. The plan-auditor's whole-plan report
  (Track D) also found five items missing from the review list; added above.
- **Hand-off** H28.1 → the maintainer: once Track A1 is merged, replace the required "Backend
  gate" with "Identity Service gate" when Track C renames the job, and add "Application Service
  gate", which Day 25 left with the maintainer.
- **Defect** — found: auditor · cause: spec · The plan-auditor's second whole-plan run (Track D,
  on 7a0607f) found five more items missing from the review list: logging, dead seam code,
  hand-kept copies, runbooks for an existing database, and where the system suite lives. They
  are added above. Two citations had gone stale after the moves (`:228-230`, `db-setup.py:393-402`).

### The stopping-point review (Track D)

**The plan-auditor, the whole plan, on 7a0607f.** Its verdict: 9 findings, none blocking the
close.
- **Phases 0–5 delivered what `plan.md` asked.** `git ls-files backend | wc -l` gives 0.
  `contract/` has one approved edit, Day 25's.
- **Days 32–37 contradict `plan.md`.** They are Azure and Kubernetes drafts: Pulumi in Day 33,
  Helm in Day 34, `argocd app rollback` in Day 36.
- **Phase 6 fails the scope rule.** No upload or CV code exists, and `EmailService` is already
  `@Async`.
- **Pace.** 192 track PRs against 132 estimated over 31 days. Phase 3 ran 2.4× its
  provisional estimates. The provisional Days 29–37 add up to 27 PRs, about 65 at that rate.
- **Stale status claims.** `plan.md`'s status lines are stale. The README's phase table and
  its infrastructure row still describe Kubernetes, and none of the five service workflows
  deploys anything ("independently deployable", README).
- **Not checked:** the dashboard and branch protection (the `gh` token was invalid in the
  session), and calendar pace (the clone was shallow).

**Continue, change course, or stop.** The auditor gave three cases:
- **Stop at `phase-5`:** the deployability question stays untested.
- **Continue as written:** not viable.
- **Change course:** a cleanup day, then Phase 7 for ECS.

The maintainer chose to change course. `plan.md` changes (this PR, "Course correction after
Phase 5"):
1. A cleanup day, Day 42.
2. Phase 7 rewritten as Days 32–35; Days 36–37 dropped.
3. Phase 6 deferred until after Phase 7, or cut.

**The review list, decided:**

| Item | Decision |
|---|---|
| `matching` role, schema, `MATCHING_DB_PASSWORD`, grants in V12–V15 | Kept: V14 raises without the role. H28.2 |
| `applications` role and schema in `identity_db` | Kept; no CONNECT on `identity_db`, harmless. H28.2 |
| `jobs_user`'s CONNECT on `identity_db` | No service connects with it. H28.3 |
| DLQ depth alarm, DLQ retention, Terraform for the topic, queues, table and `apps_db` | Local emulator only today; retention decided with the real queues. H28.4 |
| `PROFILE_CACHE_WINDOW`, task roles in place of dummy AWS keys (matching) | Matters only on AWS. H28.5 |
| `…project.backend` packages (matching, identity, `ScoreTableStartupTest`), identity's three Maven modules | Dropped: a rename moves every file and changes no behaviour |
| `/api/docs` lacks Jobs | Dropped with the claim: the closing PR's README says `/api/docs` documents identity's endpoints only |
| Service issuer `jobmatch-backend` and its key | Removed, not renamed: identity calls no service. H28.6 |
| No test for a `null` reason dropping a 25-item batch | H28.7 |
| The missing `phase-3` tag | H28.8 |
| job- and application-service's `…backend` packages | Dropped, as above |
| Dummy AWS keys on identity and application-service | Emulators need them; with task roles. H28.5 |
| CONNECT on `identity_db` for `matching_user` and `analytics_user`; the three role-setup copies disagree | H28.3 |
| `/api/docs` lacks Saved Jobs and top-matches | Dropped, as for Jobs |
| Structured production logging in identity only | H28.9 |
| Identity's seam code with no caller, compose's `INTERNAL_JOBS_URL` | With the issuer. H28.6 |
| Hand-kept copies across services | Kept, recorded as a known limitation in `plan.md`; a shared library only if a later day needs one |
| Runbooks for an existing database | H28.10 |
| The system suite in identity-service's workflow | Kept, recorded in `plan.md`; H28.11 for the deploy half |

The auditor also found stale working docs. `CLAUDE.md` and `implementer.md` say "V1–V14", but
V15 and V16 have been applied. `spec-auditor.md:43` greps `backend`. `docker-compose.yml:184,229`
say identity "is still the monolith's". These go to the cleanup day, H28.12. The README's phase
table and its Kubernetes line go to this day's closing PR.

- **Hand-off** H28.2 → the Phase 7 rewrite (Day 32): a fresh RDS starts identity from a
  consolidated baseline, so the `matching` and `applications` roles, schemas and
  `MATCHING_DB_PASSWORD` can go; until then they stay.
- **Hand-off** H28.3 → Day 42: the three role-setup copies (`db-init/10-module-roles.sh`,
  `PostgresContainer.java`, `db-setup.py`) grant CONNECT on `identity_db` to the same roles,
  neither `jobs_user` nor `matching_user` among them, and a test compares the copies.
- **Hand-off** H28.4 → the Phase 7 rewrite (Day 32): the `user.deleted` topic, queues, DLQs
  with a retention decided for a message that holds a `userId` (SQS keeps it 4 days by default),
  the DLQ depth alarm, the DynamoDB table and `apps_db`, in Terraform.
- **Hand-off** H28.5 → the Phase 7 rewrite (Day 33): task roles in place of the dummy AWS keys on
  identity-, matching- and application-service, and `PROFILE_CACHE_WINDOW` set per environment.
- **Hand-off** H28.6 → Day 42: identity's service key, `ServiceTokens` minter,
  `SERVICE_JWT_PRIVATE_KEY_FILE`, `jobs-url`, `InternalClients`, the posting interfaces in
  `shared/jobs` and `PageResponse` are removed, and job-service's mandatory
  `BACKEND_KEY_SET_URL` with them; compose's `INTERNAL_JOBS_URL` on identity goes too.
  `ServiceJwksIT` (C28.6) retires with the key: the maintainer's call in that day's spec.
- **Hand-off** H28.7 → Day 42: a test that a stored score with a `null` reason does not drop its
  25-item DynamoDB batch (`JobMatchScoreRepository`).
- **Hand-off** H28.8 → the maintainer: tag `phase-3` on Day 20's closing merge, or record in the
  README that it is missing.
- **Hand-off** H28.9 → Day 42: one structured production logging configuration for all five
  services, as identity's `application-prod.yaml` has.
- **Hand-off** H28.10 → the Phase 7 rewrite (Day 35): the runbooks for an existing database
  (`jobs-db.md`, `identity-db.md`, `copy-saved-jobs.py` with a `db-setup.py` rerun) are rehearsed
  against a restored copy, or retired as not applicable to a fresh deployment.
- **Hand-off** H28.11 → the Phase 7 rewrite (Day 35): a deploy job per service, so "each service
  builds, tests and deploys from its own workflow" has evidence, and identity-service's image is
  pushed only on its own changes.
- **Hand-off** H28.12 → Day 42: the working docs say V1–V16 where they say V1–V14 (`CLAUDE.md`,
  `implementer.md`), `spec-auditor.md` greps no `backend` directory, and compose's comments stop
  calling identity "the monolith's".
