# Day 43 — Close-out: the end-of-Phase-7 audits' findings

**Phase:** 7 (closing it; the close-out day) · **Depends on:** Day 35, and the plan change #407 · **Expected PRs:** 13

The last day (`plan.md`, *Phase 7 outcome*). Its backlog is [`docs/audit-findings.md`](../docs/audit-findings.md):
every row there owned by `43-<track>` is closed by a criterion below, which cites the row's ID.
Rows the maintainer owns (outcome c and d, the M rows) are not this day's.

## Goal
Every audit finding owned by the close-out day is fixed or recorded as a known limit where a reader
will find it, identity-service has its siblings' shape, and the ledger has no open row but the
maintainer's own actions.

## In scope
- **Track A1, the dashboard's misreads** (P7-13, WP-4, AR-7, SO-12, AR-8): `scripts/spec-drift.py`
  stops reading a criterion ID as a track name, expands hand-off ranges, does not list a code
  comment as a hand-off to the day whose own PR added it, reads only PRs whose author owns the
  repository, and carries a caveat on name coverage.
- **Track A2, token counts** (AE-4): `scripts/token-usage.py` counts a reply's last transcript line,
  where its usage is complete, not its first.
- **Track B, the gates** (AR-1, AR-3, AR-5): a gate job in `infra-ci.yaml`,
  `spec-drift-tests.yml` and `db-setup-tests.yml`, so each reports on every PR and can be made
  required (M-6); the three infra checks that read those workflows' path filters follow them; an
  invariants job that fails a PR editing an applied migration or a Day 1–4 test and passes pure
  renames; a test-count floor on the through-gateway run.
- **Track D1, the system's docs** (WP-14, AR-16): identity's `docs/` moves to `docs/system/` as
  renames, its stale lines corrected; identity's `README.md` goes, what still holds of it moved
  to the root README's "Running it" and `docs/system/configuration.md`. Before F, so F's moves
  find no doc linking into identity's modules by relative path.
- **Track F1, one module** (WP-15, AR-14, AR-15): identity's `shared/` and `identity/` fold into
  `app/` as renames; the ArchUnit module rules retire; `restClientsComeFromSpring` stays.
- **Track F2, the siblings' shape** (WP-15): `app/` lifts to the service root; one `pom.xml`, one
  `src/`; the Dockerfile builds like job-service's; no `-pl app -am`; every path naming
  `identity-service/app/` follows, and spec-drift's `MOVED` follows the move.
- **Track F3, the entry class and the dotfiles** (WP-15): the entry class becomes
  `nl.hackyourfuture.project.identityservice.IdentityServiceApplication`, scanning `backend`;
  identity's `.env.example`, `.gitignore` and `.dockerignore` go, the signing key folder ignored
  from the root `.gitignore`. Packages `backend.*` stay (`plan.md`, *Phase 7 outcome*), and so
  does `BackendApplicationTests`, a Day 01 harness self-test (Day 11).
- **Track C1, traces** (P7-9, WP-12): no trace starts from a scheduled task or a security filter
  chain.
- **Track C2, the frontend** (SO-5): Next.js and the frontend's high alerts (sharp, nanoid,
  source-map-js) upgraded to their patched versions.
- **Track D2, the outward docs** (P7-11, P7-12, WP-2, WP-6, WP-11, SO-6, SO-8–SO-11, SO-15, AR-11,
  AR-13): the README's status, Known limits, layout and "Not yet answered"; one precedence line
  in `CLAUDE.md`; one list of the settings outside the repository in `docs/runbooks/deploy.md`.
- **Track E, the reads and the Retrospective** (P7-8, WP-7, AE-1–AE-9, AR-4, AR-6, AR-9, AR-10):
  the Phase 4, 5 and 7 reads and the Retrospective in `docs/lab-notebook.md`.
- **The closing PR** fills the ledger's *Closed by* column.

## Out of scope
- The maintainer's rows (outcome c and d, M-1–M-15): their accounts, by hand.
- Renaming the `backend.*` packages, moving the contract suite out of identity's tree, the comments
  that still say "the monolith" (AR-17): `plan.md`, *Phase 7 outcome*; the ledger.
- Any deployment: nothing deploys again (`plan.md`, *Phase 7 outcome*). C35.22, the cost
  follow-up, is the maintainer's, around 2026-10-13.
- Phase 6 (Days 29–31): cut.

## Tracks

In the table's order. A1, A2 and B come first, so B's invariants check F's moves (B is F's
Track 0). D1 comes before F: identity's README and docs link into its modules by relative path
and are link-checked on every identity PR, so moving them first means F rewrites each link once,
where F2's grep sees it. F1, F2, F3 run in order, each moving what the one before left.

| Track | Owner | Work |
|---|---|---|
| A1 | | spec-drift: criterion IDs, hand-off ranges, a day's own comments, the owner's PRs only, the coverage caveat (C43.1–C43.6) |
| A2 | | token-usage counts a reply's last line (C43.7) |
| B | | Gate jobs on the three workflows and the infra checks that read them, the invariants job, the through-gateway test floor (C43.8–C43.10) |
| D1 | | identity's docs to `docs/system/`, corrected; identity's README goes (C43.20) |
| F1 | | Fold `shared/` and `identity/` into `app/`; module rules retire (C43.11, C43.12) |
| F2 | | Lift `app/` to the service root, one pom, the Dockerfile, every path, `MOVED` (C43.13) |
| F3 | | `IdentityServiceApplication`; identity's dotfiles go (C43.14–C43.16) |
| C1 | | No trace from scheduled tasks or security chains (C43.17, C43.18) |
| C2 | | The frontend's patched versions (C43.19) |
| D2 | | README status and Known limits, the precedence line, the outside-the-repository list (C43.21–C43.24) |
| E | | The Phase 4, 5 and 7 reads; the Retrospective (C43.25, C43.26) |

## Acceptance criteria

### Track A1 — the dashboard's misreads

- [x] C43.1 **new** — A criterion ID is not a track name (P7-13, AR-7, KAN-99): `unopened_tracks`
      given a Day 32 PR on `day-32/track-c1-…` whose body cites `C32.5` returns `[]`. A test in
      `scripts/test_spec_drift.py`. Red today: it returns `[{'track': 'C32', 'pr': 1}]`.
      Met (#412). `test_a_criterion_id_is_not_a_track` and `test_a_day_numbered_name_is_not_a_track`
      in `scripts/test_spec_drift.py`; 67 passed on `main` at `cf00d89`.
      broken: `TRACK_NAMED` without the lookahead → `test_a_criterion_id_is_not_a_track`: `[{'track': 'C32', 'pr': 1}] != []` (#412)
      broken: `TRACK_NAMED` with `\d+` → `test_a_day_numbered_name_is_not_a_track`: `[{'track': 'C32', 'pr': 1}] != []` (#412)
- [x] C43.2 **new** — A hand-off range cites every ID in it (WP-4): a fixture spec that writes a
      range of three hand-off IDs picks up the middle one, so `hand_offs` does not list it as
      unpicked. A test with a two-day fixture; the test is the evidence, since the live case
      (Day 35's Notes citing Day 34's range) is picked up once this spec names it. Red today:
      `HAND_OFF_ID.findall` on a range gives its two ends only, and a spec-drift run on `main`
      lists Day 34's middle ID as picked up by no later day.
      Met (#412). `hand_off_ids` expands a range; `test_a_hand_off_range_names_every_id_in_it` and
      `test_a_hand_off_range_picks_up_each_id_in_it`. The live run on a clean clone lists H34.2 as
      picked up.
      broken: hand-off ranges read as their two ends → `test_a_hand_off_range_picks_up_each_id_in_it`: `[{'to': None, 'id': 'H01.2', …}] != []` (#412)
- [x] C43.3 **new** — A code comment naming a day is not a hand-off to that day when the day's own
      PR added the line (WP-4: `infra-ci.yaml`'s "(Day 35)", written by Day 35). A test with a
      fixture repository. Red today: `hand_offs` lists every comment naming a day ahead, whoever
      wrote it.
      Met (#412). `written_by_day` reads the day from `git blame --first-parent`'s merge subject;
      `test_a_code_comment_is_not_handed_to_the_day_whose_pr_wrote_it`.
      broken: the writing-day skip removed → `test_a_code_comment_is_not_handed_to_the_day_whose_pr_wrote_it`: `[(2, 'src/Foo.java:1'), (3, 'src/Foo.java:2')] != [(3, 'src/Foo.java:2')]` (#412)
- [x] C43.4 **new** — Only PRs the repository's owner opened are read (SO-12): `gh pr list` asks for
      `author`, and a PR by another login contributes no break line, no track name, no cited PR
      and no title to the next step ("Waiting on #N title"). A test with a PR by another author.
      Red today: the `--json` fields at `spec-drift.py:1108` have no `author`, and every body and
      title is read.
      Met (#412). `gh pr list` asks for `author` and `owners_prs` keeps the owner's logins before
      any body or title is read; `test_only_the_owners_prs_are_kept`,
      `test_another_authors_pr_is_not_the_next_step`, `test_another_authors_body_names_no_track`.
      broken: `owners_prs` returns every PR → `test_another_authors_pr_is_not_the_next_step`: `'Waiting on #50 Day 25 track A, #51 Bump x' != 'Waiting on #50 Day 25 track A'` (#412)
- [x] C43.5 **new** — The dashboard's caveats say name coverage counts tests and tooling as code,
      and give the production-only figure the architecture audit measured (AR-8: 0.871, 0.433).
      Red today: no entry of `CAVEATS` names tests or tooling.
      Met (#412). `CAVEATS` says name coverage counts test and tooling files as code, 0.871 over
      all code and 0.433 over production code (AR-8); `test_the_coverage_caveat_says_tests_and_tooling_count`.
      #412 recorded no break for it; the close ran one.
      broken: "test and tooling" cut from the caveat → `test_the_coverage_caveat_says_tests_and_tooling_count` failed on its `assertTrue(any("test" in c and "tooling" in c …))`, 1 failed (#429)
- [x] C43.6 **hold** — `scripts/test_spec_drift.py`'s 56 tests pass unedited, and the dashboard's
      next step on `main` after the spec PR merges reads "Day 43 Track A1" (the spec-auditor
      simulated it). It needs local `main` to equal `origin/main` (Notes).
      Met (#412), with a departure: the 56 pass unedited, and the suite is 65 after A1 (9 new) and
      67 on `main` (D1a's and F2's `MOVED` tests). The hold was red on `main` before A1: three
      Dependabot PRs opened after the audit made the next step "Waiting on #409 … #411" (SO-12 in
      practice); on a clean clone with A1's script it read "Day 43 Track A1".
      broken: `hand_off_ids` made to read only ranges → `test_a_hand_off_with_an_id_is_picked_up_only_by_a_spec_citing_it` (unedited) and `test_a_hand_off_range_names_every_id_in_it` failed, 2 failed / 62 passed (#412)

### Track A2 — token counts

- [x] C43.7 **new** — A reply is counted from its last transcript line (AE-4): given two lines
      with the same message and request id, output 3 then 120, `count` gives 120. A new
      `scripts/test_token_usage.py`. Red today: `count` keeps the first line and gives 3. The
      closing PR runs `python scripts/token-usage.py` with the fix and records how far Haiku's
      counts rose.
      Met (#413). `test_a_reply_is_counted_from_its_last_line` in `scripts/test_token_usage.py` (4
      tests; run by spec-drift-tests since #414). This PR ran `python scripts/token-usage.py`:
      through 2026-10-11, Haiku's output rose from 54,463 to 2,321,219 tokens (42.6×; Haiku 4.5
      40,052 → 1,962,506, 49×; Haiku 5.5 14,411 → 358,713, 25×), inside AE-4's 24–49×. Opus 5.5
      rose 9.6% (8,676,047 → 9,510,032).
      broken: `count()` restored to `main`'s (first line kept) → `test_a_reply_is_counted_from_its_last_line`: `3 != 120`, 1 failed / 3 passed (#413)

### Track B — the gates

- [x] C43.8 **new** — `infra-ci.yaml`, `spec-drift-tests.yml` and `db-setup-tests.yml` each run on
      every pull request and end in a job named `<workflow> gate`, `if: always()`, that fails when
      a job before it failed or was cancelled (AR-1), the shape of identity's
      (`identity-service-ci-cd.yaml`, `gate`). The path filter moves to a `changes` job. The three
      infra checks that read those `pull_request: paths:` lists (`infra-checks.py:1774`, C36.10;
      `:2052-2055`, C35.4; `:2418-2422`, C35.6) read the `changes` job's filter instead and still
      fail when a path is dropped from it. Red today: none of the three workflows has a job named
      `… gate`, and each has `paths:` on `pull_request`, so on most PRs it never reports. Adding
      the gates to the required checks is M-6, the maintainer's.
      Met (#414). Infra checks gate, Dashboard metric tests gate and db-setup tests gate, each
      behind a `changes` job, reported SUCCESS on every PR from #416 to #421. Making them
      required is M-6, still open.
      broken: `scripts/db-setup.Dockerfile` dropped from db-setup-tests' PATHS → `C36.10 db-setup-tests.yml: the changes job's PATHS does not match scripts/db-setup.Dockerfile` (#414)
      broken: `seed-mart.py` and then `analytics-schema.sql` dropped from db-setup-tests' PATHS → `C35.4 … does not match scripts/seed-mart.py` and `… does not match …/fixtures/analytics-schema.sql` (#414)
      broken: `matching-service-ci-cd.yaml` dropped from infra-ci's PATHS → `C35.6 infra-ci.yaml pull_request: the changes job's PATHS does not match .github/workflows/matching-service-ci-cd.yaml` (#414)
- [x] C43.9 **new** — A PR job fails when the PR modifies or deletes an applied migration (an
      existing `V*__*.sql` under any `services/**/db/`), a file of identity's `contract/` package,
      `support/TestHarnessTest.java` or `BackendApplicationTests.java` (the Day 01 harness
      self-tests), naming each file, and passes additions and pure renames (similarity 100)
      (AR-3). The rule lives in a script with its own test, `scripts/test_invariants.py`, against a
      scratch git repository: an edit fails, an addition passes, a pure rename passes, a rename
      with an edit fails. Red today: no workflow compares these paths with the base (`grep -rn
      "contract" .github/workflows` finds only the through-gateway `-Dtest`).
      Met (#415). `scripts/invariants.py`, run by `pr-checks.yml`'s "Applied migrations and the
      Day 1-4 tests stay unedited"; `scripts/test_invariants.py`, 8 passed on `main`.
      broken: V1 edited and `AuthLoginIT.java` deleted on a scratch commit off `origin/main` → `modified: …/V1__init_schema.sql`, `deleted: …/contract/AuthLoginIT.java`, exit 1 (#415)
      broken: the deletion check removed from `invariants.py` → `test_a_deleted_contract_test_fails` FAILED, 1 of 8 (#415)
- [x] C43.10 **new** — The through-gateway run fails when it ran fewer tests than a pinned floor
      (AR-5): 229, its count in identity's run 38153863761 (at #406; #407 changed no code). Red
      today: narrowing its `-Dtest` list still passes.
      Met (#415). `GATEWAY_TEST_FLOOR: 229`; identity's run 38614022583 on `main` at `c68eb22`
      printed "Tests run through the gateway: 229 (floor 229)".
      broken: the gateway run narrowed to `-Dtest=RefreshIT` → `::error::2 tests ran through the gateway, fewer than the floor of 229.`, exit 1 (Maven itself exited 0) (#415)

### Track D1 — the system's docs

- [x] C43.20 **new** — identity's docs are the system's, filed at its root (WP-14, AR-16):
      `services/identity-service/docs/` moves to `docs/system/` as renames, and
      `services/identity-service/README.md` is gone, what still holds of it moved to the root
      README's "Running it" or `docs/system/configuration.md`.
      - `git grep -n -E "identity-service/(docs|README)" -- . ':!specs' ':!docs/lab-notebook.md'
        ':!docs/audit-findings.md' ':!docs/dashboard' ':!*.json' ':!scripts/spec-drift.py'
        ':!scripts/test_spec_drift.py'` prints nothing. Red today: 8 lines (the workflow and seven in
        the README).
      - `python scripts/check-links.py README.md docs/system/*.md` passes, and identity's workflow
        runs it on those paths, with `docs/system/` and `README.md` in its trigger and `PATHS`.
      - `git grep -n -E "(^|[^/])backend/" -- docs/system` prints nothing (source links through
        `nl/hackyourfuture/project/backend/` stay). Red today: 6 lines under identity's docs.
      - The stale lines the audits named are corrected or removed: identity's `architecture.md:4-5`
        ("still one backend and one database"; the file merges into `docs/architecture.md` or
        goes), `schema.md:3`, `configuration.md:3` ("the three services").
      - spec-drift's `MOVED` gets `services/identity-service/docs` → `docs/system`, with a test,
        so Day 16's removal checks still find the docs.
      Met (#416, #417). On `main` the first grep prints nothing, check-links reports 0 of 157
      broken, the `backend/` grep prints nothing, identity's `architecture.md` is gone and the
      stale lines are fixed; `MOVED` has #416's entry, with its test.
      broken: the `MOVED` entry for #416 removed → `test_the_day_43_move_of_identity_docs_follows_them_to_docs_system` failed: `['services/identity-service/docs'] != ['docs/system']` (#416)
      broken: a `docs/system/configuration.md` link set to `../scripts/db-setup.py` → `check-links.py` reported `1 of 151 relative links broken`, naming `configuration.md:294` (#416)
      broken: a link to `services/identity-service/README.md` appended to `README.md` → check-links reported `1 of 155 relative links broken` (#417)

### Track F1 — one module

- [x] C43.11 **new** — identity-service builds as one module (WP-15, AR-15): `git ls-files
      services/identity-service/shared services/identity-service/identity` prints nothing, and
      the parent `pom.xml` lists no `shared` or `identity` module. Every moved file shows as a
      rename (`git diff -M --stat main` on the branch). `sharedDependsOnNobody` and
      `nobodyDependsOnTheAssembly` are gone (AR-14). Red today: the two directories hold 50
      tracked files, and the pom lists three modules.
      Met (#418). `git ls-files` on the two directories prints nothing; the 48 moved files are
      R100; both module rules are gone. `ModuleBoundariesTest`, renamed `RestClientRuleTest`,
      shows as D + A: dropping two of its three rules put it below git's similarity threshold.
- [x] C43.12 **hold** — `restClientsComeFromSpring` still fails a `RestClient.create()` call in
      identity's code (AR-14).
      Met (#418). `RestClientRuleTest.restClientsComeFromSpring`.
      broken: `RestClient.create()` added to `IdentityDirectory` (a moved identity class) → `RestClientRuleTest.restClientsComeFromSpring` failed, "Method <…IdentityDirectory.brk()> calls method <…RestClient.create()>" (#418)

### Track F2 — the siblings' shape

- [x] C43.13 **new** — identity-service is laid out like job-service: `pom.xml`, `src/`,
      `Dockerfile` at its root and no `app/`; the Dockerfile copies one pom and `src/`.
      - `git grep -n -E "identity-service/(app|identity|shared)/|-pl app" -- . ':!specs'
        ':!docs/lab-notebook.md' ':!docs/audit-findings.md' ':!docs/dashboard' ':!*.json'
        ':!scripts/spec-drift.py' ':!scripts/test_spec_drift.py'` prints nothing. Red today: 17
        lines in 11 files (workflows, `infra-checks.py`, the seed scripts, `CLAUDE.md`, the
        README, the architecture auditor's brief, a runbook).
      - The service-relative forms follow too: `CLAUDE.md`'s migration paths
        (`app/src/main/resources/db/migration`, `app/.../config/Migrations.java`), the
        implementer's brief (`.claude/agents/implementer.md`, `app/src/test/.../contract/`), the
        README's layout.
      - Tests that reach the repository by relative path find it one level up:
        `IdentityDbConnectTest` (`../../../scripts/...`) and `UserDeletedRelayIT`
        (`../../../docs/events/...`) pass.
      - spec-drift's `MOVED` gets `services/identity-service/app` → `services/identity-service`,
        with a test, so Day 14's and Day 16's removal checks still reach identity.
      Met (#419). On `main` the grep prints nothing and identity has `Dockerfile`, `pom.xml` and
      `src/`; `IdentityDbConnectTest` and `UserDeletedRelayIT` pass; `MOVED` has #419's entry
      with its test.
      broken: the `MOVED` entry removed → `test_the_day_43_lift_of_identity_app_follows_it_to_the_service_root` failed, `['services/identity-service/app/src/main/java'] != ['services/identity-service/src/main/java']` (#419)
      broken: `UserDeletedRelayIT`'s path put back to `../../../docs/events/user-deleted.md` → 1 error in 4, `NoSuchFileException: ..\..\..\docs\events\user-deleted.md` (#419)

### Track F3 — the entry class and the dotfiles

- [x] C43.14 **new** — The entry class is
      `nl.hackyourfuture.project.identityservice.IdentityServiceApplication` with
      `scanBasePackages` naming `identityservice` and `backend`, as job-service's; `git grep -n -E
      "class BackendApplication |BackendApplication\.class|import [a-z.]*\.BackendApplication;" --
      services` prints nothing. `BackendApplicationTests` keeps its name (a Day 01 harness
      self-test, C43.9). Red today: it prints 6 lines.
      Met (#420). The grep prints nothing on `main` at `cf00d89`.
      broken: `"nl.hackyourfuture.project.backend"` removed from `scanBasePackages` → `BackendApplicationTests` 3 errors (UnsatisfiedDependencyException creating `healthEndpoint`) (#420)
- [x] C43.15 **new** — identity-service has none of the monolith's dotfiles: `git ls-files
      services/identity-service/.env.example services/identity-service/.gitignore
      services/identity-service/.dockerignore` prints nothing, and nothing links them; `git
      check-ignore services/identity-service/.jwt/private.pem` still matches, from the root
      `.gitignore` (`scripts/jwt-key.sh` writes the signing key there). Red today: the first
      prints three, and the match comes from identity's own `.gitignore:39`.
      Met (#420). `git ls-files` prints nothing on `main`; `git check-ignore -v` answers
      `.gitignore:74:.jwt/`, the root's.
      broken: `.jwt/` removed from the root `.gitignore` → `git check-ignore services/identity-service/.jwt/private.pem` exit 1, no match (#420)
- [x] C43.16 **hold** — Through D1, F1, F2 and F3 the contract suite, the Day 01 self-tests and
      V1–V16 move unedited: C43.9's job passes on each of the four PRs, and identity's full
      `verify` runs 443 tests (1 skipped) before F1 and 441 from F1 on (two module rules retired),
      and the through-gateway run 229, as on `main`.
      Met (#416–#420). The invariants job passed on each of the five PRs (twice on #418);
      identity's `verify` ran 443 (1 skipped) before F1 and 441 on #418–#420; the gateway run 229
      throughout. C1 then added four (445 on `main`).
      broken: `-- break` appended to identity's applied `V1__refresh_tokens.sql` on the branch → `scripts/invariants.py origin/main HEAD` exit 1, "renamed with an edit (R099) … These files are frozen" (#418)

### Track C1 — traces

- [x] C43.17 **new** — No trace starts from a scheduled task or a security filter chain (P7-9,
      WP-12: 1,785 of 1,813 traces on Day 35). In each Spring service (api-gateway,
      identity-, job-, matching- and application-service) a test finds
      `Observation.createNotStarted(name, registry).isNoop()` true for `tasks.scheduled.execution`,
      `spring.security.filterchains` and `spring.security.http.secured.requests`, and false for
      `http.server.requests`. Red today: no service's configuration names an observation to
      refuse (`grep -rn "observations"` over the five `application.yaml` files prints nothing),
      and Day 35's traces were 98% `task outboxRelay.relay` and `security filterchain
      before/after`.
      Met (#421). `management.observations.enable` refuses `tasks.scheduled` and `spring.security`
      in the five services, each with an `ObservationFilterTest` (identity's an IT); identity's
      `verify` on `main` at `c68eb22` ran 445 (1 skipped).
      broken: job-service without the `"[spring.security]": false` line → `ObservationFilterTest` 2 of 4 failed ("Expecting value to be true but was false", the two `spring.security.*` names) (#421)
- [x] C43.18 **hold** — A request is still traced across services: `TopMatchesTracedIT`,
      `ObservabilityIT` and the gateway's `TracingTest` pass unedited.
      Met (#421). The three pass unedited.
      broken: identity with an over-broad `"[all]": false` → `TopMatchesTracedIT.oneRequestIsOneTraceAcrossEveryHop` (identity's log line carried no trace id) and `ObservabilityIT.exposesRequestTimingsForPrometheusToScrape` failed (#421)

### Track C2 — the frontend

- [x] C43.19 **new** — The frontend runs the patched versions (SO-5): `frontend/package-lock.json`
      resolves `next` ≥ 16.3.8, `sharp` ≥ 0.35.5, `nanoid` ≥ 3.3.18 and `source-map-js` ≥ 1.2.2;
      `npm audit --audit-level=high` in `frontend/` exits 0; the frontend workflow's lint and build
      pass; after the merge, Dependabot lists no open alert on `frontend/package-lock.json`. Red
      today: 13 open alerts on it, three critical (`next` 16.3.0), and the audit exits 1.
      Met (#422). The lock resolves `next` 16.3.8, `sharp` 0.35.5, `nanoid` 3.3.20 and
      `source-map-js` 1.2.2; the audit exits 0; after the merge Dependabot lists no open alert on
      `frontend/package-lock.json` (#35–#47 fixed). The 34 open on `data/uv.lock` are not this
      criterion's.
      broken: nanoid 3.3.17 put back alone in a copy of the new lock → `npm audit --audit-level=high` exit 1, "nanoid <3.3.18, 1 high severity vulnerability" (#422)

### Track D2 — the outward docs

- [x] C43.21 **new** — The README says where the migration ended (P7-11, WP-6): "What runs today"
      says Phase 7 deployed once and was destroyed, Phase 6 was cut and the close-out day was the
      last; no row of its status table says "next" or "planned"; "Not yet answered" is answered
      or gone; it links `docs/architecture.md`, `docs/runbooks/deploy.md` and
      `docs/audit-findings.md`; "Running it" no longer sends readers to the retired
      `docs/runbooks/identity-db.md`; the layout lists `infra/` and `docs/system/`. Red today: it
      describes the state at Day 33, its layout has no `infra/`, and `README.md:309` cites the
      retired runbook.
      Met (#426). Checked with a scratchpad script for C43.21–C43.24: 32 red lines on `main`
      before, none on the branch.
      broken: the Phase 6 row back to `deferred` → `C43.21 status row: deferred` (#426)
- [x] C43.22 **new** — The README's Known limits cite each limit the ledger gives it, by ID:
      P7-12, WP-2, SO-6, SO-8, SO-9, SO-10, SO-11, SO-15, AR-11; and the limits Day 35's Notes
      carried: the profile cache's 10 s window, one gateway task, the self-signed certificate, the
      alarm topic's name. Red today: it cites no ledger ID.
      Met (#426).
      broken: AR-11 dropped from the Known limits → `C43.22 no AR-11` (#426)
- [x] C43.23 **new** — `CLAUDE.md` has one line naming which wins when the code, a spec,
      `plan.md`, the dashboard, Jira and memory disagree (AR-13). Red today: `grep -n -i
      "precedence\|source of truth" CLAUDE.md` prints nothing.
      Met (#426). `CLAUDE.md`'s "Source of truth, when they disagree" line.
      broken: the line renamed "Who wins" → `C43.23 0 precedence lines` (#426)
- [x] C43.24 **new** — `docs/runbooks/deploy.md` has a section headed `## Outside the repository`
      listing every setting the deployment needs that the repository does not hold (WP-11): the
      repository variables (`DEPLOY_ENABLED`), branch protection, the Google OAuth client's
      redirect URI, the bootstrap's state bucket and budget, the local secrets folder. Red today:
      no such heading.
      Met (#426).
      broken: the heading as `## Outside the repo` → `C43.24 no heading` (#426)

### Track E — the reads and the Retrospective

- [x] C43.25 **new** — `docs/lab-notebook.md` has a read at the end of Phases 4, 5 and 7, in the
      form `PHASE_READ` matches (WP-7). Red today: `PHASE_READ` finds Phases 0–3 only.
      Met (#427). `PHASE_READ` on `main` finds Phases 0–5 and 7 (Phase 6 was cut).
      broken: the Phase 7 read's heading changed to "Read at the close of Phase 7." → `C43.25: no read for Phase 7` (phases read [0, 1, 2, 3, 4, 5]) (#427)
- [x] C43.26 **new** — The Retrospective has a verdict and evidence in every row, and cites each of
      AE-1–AE-9, AR-4, AR-6, AR-9 and AR-10 by ID (P7-8, WP-7). Red today: four rows read
      "— | —", and no ledger ID appears.
      Met (#427). 11 rows, 13 IDs cited.
      broken: one verdict blanked to "—" and "AR-10" written "AR 10" → `C43.26: empty row: Can the workflow take more agent autonomy?` and `C43.26: AR-10 not cited` (#427)

### The closing PR

- [x] C43.27 **new** — In the ledger's five audit tables, every row whose Owner names `43-` cites
      in *Closed by* the PR that closed it (`#NNN`), and every row with outcome c names its M row.
      Red today: 23 rows owned by 43 have an empty *Closed by*, 21 more hold a reason without a
      PR, and no c row names an M row.
      Met (#428). A check reading the five tables (in the PR) gives 54 red rows on `origin/main`
      at `cdcbc2a` (23 empty, 20 with a reason and no PR, 11 c rows with no M row; the spec's 21st
      is P7-11, which already cited #406 for its notebook part) and 0 on the branch. The rows that
      said "this PR" now name #407, the plan change that wrote them; the M rows done are dated.
      broken: AR-8's `#412` removed → `AR-8: owned by 43, Closed by has no PR: ''` (#428)
      broken: SO-7's `M-5` removed → `SO-7: outcome c, Closed by names no M row: ''` (#428)

## Verify
```bash
python -m pytest scripts/test_spec_drift.py scripts/test_token_usage.py scripts/test_invariants.py -q
python scripts/spec-drift.py --out spec-drift.json     # next step, unpicked hand-offs
docker build -t jobmatch-job-service:harness services/job-service
(cd services/identity-service && ../../mvnw clean verify && ../../mvnw -B checkstyle:check)
(cd frontend && npm ci && npm audit --audit-level=high && npm run lint && npm run build)
git grep -n -E "identity-service/(app|identity|shared|docs|README)|-pl app" -- . ':!specs' \
  ':!docs/lab-notebook.md' ':!docs/audit-findings.md' ':!docs/dashboard' ':!*.json' \
  ':!scripts/spec-drift.py' ':!scripts/test_spec_drift.py'
```

## Notes
- The ledger row IDs in each criterion are the hand-off: `docs/audit-findings.md` is where they
  came from, and the closing PR writes the PR that closed each into *Closed by*.
- `spec-drift.py` reads local `main` (`git log --first-parent main`), not `HEAD`. Local `main` on
  the maintainer's machine has a stray commit (`b4e9605 x`, Day 35) and prints "Day 29" until it
  is reset to `origin/main`; until then, run it from a clean clone. Check a branch's change to it
  by calling its functions, as C43.1–C43.4's tests do.
- `BackendApplicationTests` keeps its name: renaming a Day 01 harness self-test is a "stop and
  ask" (`CLAUDE.md`), and nothing the audits found needs it. The maintainer can ask for it.
- F1–F3 and D1 move hundreds of files. Git shows them as renames, and the diff check counts
  renames as nothing; F2 merges two poms (about 350 lines) and may still pass 400 counted lines.
  Split where each part stays green, or record an `Oversized:` line.
- After the closing PR merges, the plan-auditor reads the whole plan and checks that no ledger row
  is open but the maintainer's (`plan.md`, step 8). Then the migration ends.
- **Defect** — found: auditor · cause: spec · the first draft of this spec: wrong counts (C43.13,
  C43.14, C43.20, C43.27), F3 ordered before the README that names its files, a criterion that
  would un-ignore the signing key, three infra checks Track B would break, a `broken:`
  placeholder the parser read as a break, and a live hand-off check this spec would have ticked by
  naming the ID. Fixed in this spec-change PR.
- **Defect** — found: live run · cause: spec · C43.6 was red on `main` before A1: three Dependabot
  PRs (#409–#411), opened after the audit simulated the step, were the next step. A1's owner
  filter (C43.4) is what turned it green.
- **Defect** — found: review · cause: spec · C43.7 asked for `scripts/test_token_usage.py` and no
  workflow ran it; B1 (#414) added it to spec-drift-tests.
- **Defect** — found: the close · cause: spec · C43.27's 21 rows with a reason and no PR were 20:
  P7-11 already cited #406.
- **Defect** — found: review · cause: implementation · four of the nine implementer drafts (A1,
  A2, B1, B2, D1a, F1, F2, F3, C1): A1 missed the live C32 case and kept an unused constant, B1
  had two dead `workflow_dispatch` branches, F2 left one `app/` path (`db/identity/README.md`),
  F3's javadoc said the `backend` packages would move. C2, D1b, D2, E and the close were written
  in the main session.
- **Defect** — found: review · cause: spec · two stale lines earlier days left in the system's
  docs: the README said the contract suite needs job-service's image alone, not all three (D1b),
  and `auth.md` sent the Google id and secret to identity's own `.env`, which compose does not
  read (F3).
- **Defect** — found: the close · cause: tooling · once part 1 of the close (#428) merged, the
  dashboard's next step read "Every day is done." with C43.1–C43.26 unticked: spec-drift takes a
  merged `day-NN/close-the-day` branch as the close, and this one was split. #429, the second
  part, ended the day. Fixed after the day (KAN-172): a close titled "part N" ends a day only once
  every box is ticked.
- **CI failures that were not the code:** a GitHub incident (HTTP 502/504 on `gh pr list`, #414's
  dashboard run), public ECR's anonymous pull limit (#416) and Maven Central 502s (#418, #420).
  The maintainer reran each; the agent's token cannot rerun Actions. Locally, `npm run lint`
  fails on CRLF alone; lint an LF copy (`git -c core.autocrlf=false archive`).
- **Left to the maintainer:** M-1 (done after the day, 2026-10-14), M-6 (the four new gates as required checks, `invariants`
  among them), M-9 and M-12
  (`docs/audit-findings.md`); the 34 Dependabot alerts on `data/uv.lock` and their PRs; C35.22's
  cost read (done after the day: 0.40 USD in all).
- *Estimated 13 pull requests; took 16:* the spec PR (#408), 13 track PRs (#412–#422, #426,
  #427) and the close in two (#428, #429), the gate splitting B, D1 and the close; F2
  (#419, 516 lines) took the eighth `Oversized:` line, as these Notes expected.
- **The whole-plan audit (2026-10-14, Opus), after the close:** the plan delivered, no code
  defects. Its record findings were fixed after the day: `plan.md`'s Epilogue (#430), and Day 01's
  boxes, `specs/README.md`'s status column, the README's verdict line and M-6's four gates in the
  PR that adds this item. Two of Day 28's hand-offs to the maintainer were done and never cited, so
  the dashboard still listed them: H28.1 ("Identity Service gate" and "Application Service gate"
  are required checks) and H28.8 (`phase-3` tags Day 20's close, #191, M-11). H28.18, someone
  else following "Running it" from a fresh clone, is not done: the ledger carries it as M-12.
