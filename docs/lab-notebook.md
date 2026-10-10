# Lab notebook

The full record of the migration, moved out of the [README](../README.md) so that the README
can stay short. What each day built and turned up, the read at the end of each phase, the
measurement table with its evidence, the defects by kind, and the retrospective once the last
phase closes. Every day's own record is in its spec's *Notes* in [`specs/`](../specs/); every
defect, by day, in [`defects.md`](defects.md).

The migration dashboard reads the phase reads and the measurement table from this file
(`scripts/spec-drift.py`), so their wording is kept as it is: `**Read ... end of Phase N.**` and
a two-column table under `## What is being measured`.

---

## Results to date

Recorded as they happen, including the ones that make the method look worse.

**Day 1 — integration test harness.** Testcontainers Postgres shared across the run, an
`IntegrationTest` base class, builders (`aUser()`, `aProfile()`, `aPosting()`), and fixtures for
the three `analytics` mart tables the pipeline owns and Flyway therefore does not create.

- *Estimated 3 pull requests, took 1, over the gate.* The harness is ~1,400 lines, and the pieces
  have a compile order; it merged whole with an `Oversized:` reason (#1, six commits). The
  estimate was mine and it was wrong. (Recorded until after Phase 2 as "took 6", counting commits.)
- The agent sourced the mart column types from `data/sql/job_schema.sql` rather than from the
  dbt model the spec pointed at, correctly noting that the model is Databricks SQL and the sync
  script remaps those types to Postgres on the way in. The spec was wrong; the correction is
  recorded in the spec.

**Day 2 — auth contract tests.** 49 tests across six classes covering register, login, logout,
the password-reset lifecycle, password change, and all three Google sign-in branches. The OIDC
provider is stubbed as a *real* RS256 signing provider with a JWKS endpoint rather than a mocked
bean, so issuer, audience, signature, expiry and nonce are still validated by the production
decoder. Full suite: 63 tests in ~45 seconds.

- **The suite the spec asked for would have been green by never running.** Surefire's default
  includes stop at `*Test`/`*Tests` and no Failsafe plugin was configured, so `mvnw verify`
  matched no `*IT` class. Found and fixed during the day. This is the exact failure mode the
  method exists to catch — a CI gate that passes because it is testing nothing.
- A harness bug from Day 1 surfaced: `UserBuilder.googleAccount()` wrote `oauth_provider =
  'google'` where the application writes and matches on `'GOOGLE'`, so the fixture built a row
  no Google sign-in could ever match. Day 1's own self-tests never exercised it.
- One production wart found in the monolith and pinned rather than fixed, per the spec's "if a
  test reveals a bug, file it; do not fix it here": `POST /api/auth/register` echoes back the
  un-normalised email while storing the lowercase one.
- Two flows were identified as unreachable over HTTP and deliberately left uncovered, with the
  reasoning written down — rather than covered with a test that reaches past the contract.
- **Reviewing the suite against Day 13 found it would not have survived.** Three contract classes
  named `JSESSIONID` outright, while Day 13 requires that nothing sets that cookie — two
  acceptance criteria that could not both hold. The name is now one line in `support/`, fixed in
  a spec-change pull request before the day rather than during it. The criterion it was measured
  by ("assert `Set-Cookie` attributes only") was satisfied to the letter throughout.

**Day 3 — profile and job search contract tests.** 68 tests across seven classes covering the
profile round trip and its skill normalisation, job search with paging and all four filters the
endpoint accepts, the filter options endpoint, job detail, the `savedCount` field, and which
`/api/jobs` routes a logged-out visitor may reach. Full suite: 131 tests in ~60 seconds.

- **The spec named a filter the endpoint does not have.** It asked for a filter on employment
  type and called the city filter "city"; the search takes `category`, `workMode`, `location` and
  `q`. One criterion could not be ticked without writing the feature it tested, and the free-text
  filter the search page leads with was missing from the spec entirely. Corrected in a
  spec-change pull request *before* the work, which is now the second time review of a spec
  against the code it describes has been worth more than the code review after it.
- **Another harness blind spot, same shape as Day 2's.** `ApiResponse` read JSON floats as
  doubles, so a response carrying `45000.00` parsed back to `45000.0` and no assertion on a
  number could have seen a currency scale change. The API was correct throughout; only the test
  client's view of it was lossy. Found by writing the assertion, not by reviewing the harness.
- **Two disagreements inside the monolith, pinned rather than fixed.** `/api/jobs/filters` offers
  employment type and experience level as options that the search silently ignores, because
  Spring drops a query parameter no `@RequestParam` declares. And search returns closed postings
  while matching excludes them. Both are product questions; pinning them turns the answer into a
  visible decision instead of a silent drift during the split.
- **A defect found in a spec three days ahead.** Checking what `savedCount` has to survive turned
  up two items in Day 8 that do not match the code: "the three other places" the join appears in
  is one other place, and a `// TODO day-08` marker it asks to remove does not exist, so that
  acceptance criterion ticks itself. The same failure mode as Day 2's CI gate — a check that
  passes because it is checking nothing.
- *Estimated 3 pull requests, took 4.* Track A came to 406 lines against the 400-line gate and
  was split. The gate has now bitten twice, with no override since #1–#3.

**Day 4 — saved jobs and matching contract tests.** 53 tests across six classes covering the
saved-job tracker, the posting details it hydrates, and `/api/jobs/top-matches` with the language
model replaced by a real HTTP stub. Full suite: 184 tests in ~90 seconds.

- **Three of the day's six acceptance criteria could not have been met, and one was a hazard.**
  The spec asked for the model to be stubbed, for a stub error to fall back to a 200, and for a
  repeat request to hit the stub exactly once. `application-test.yaml` blanks `app.llm.api-key`,
  which makes `MatchScorer` return before it makes any call — all three would have gone green
  testing nothing. Worse, the test profile sets no `app.llm.base-url`, so anyone enabling the key
  without also overriding the URL would have pointed the suite at a live provider, in a file whose
  own header says a test run must not reach the internet. Found by reading the spec against the
  code before starting, and fixed in a spec-change pull request.
- **The fixture is worse than production in the one path the day tests.** `MatchScorer` maps the
  model's reply back by the first eight characters of a posting id. Production ids are `md5(...)`
  and do not collide; every seeded id is `seed-00NN`, so `seed-0001` and `seed-0002` are the same
  id to the model. A test written against seed postings would have scored one and dropped the
  other without a word. Day 1 established that a fixture must not be *nicer* than production; this
  is the same rule from the other side.
- **Saved jobs and job search disagree about the same posting, twice** — on location, because one
  reads the free-text column and the other the normalised city bridge, and on skill order. Both
  are pinned so Day 9's shared `PostingLookup` has to make a decision rather than satisfy one view
  and quietly break the other.
- *Estimated 3 pull requests, took 5.* Track C came to 446 lines against the 400-line gate and was
  split. The gate has now forced a split three times, with no override since #1–#3.
- Unrelated to the day, and worth recording: the `build` job went red twice on Maven Central
  rate-limiting. The Dockerfile copies the whole source tree before `mvn package`, so every commit
  re-downloads the full dependency tree and the layer cache never hits. The tests were never
  involved.

**Day 5 — observability baseline.** Actuator on its own unpublished port, Prometheus and OTLP
registries, structured JSON logs, request tracing, and a Grafana stack behind a compose profile.
Seven of nine acceptance criteria hold. Full suite: 196 tests.

- **The day's central assumption was wrong, and finding out took four pull requests.** The spec
  said to trace with the OpenTelemetry Java agent. On Spring Framework 7 and Tomcat 11 the agent
  produces JDBC spans and no request span — and, measured as an A/B against the running stack, it
  *suppresses* Spring's own instrumentation, so attaching it makes things worse than leaving it
  off. The agent is gone; traces come from `spring-boot-starter-opentelemetry`. The trade is
  recorded rather than papered over: we gained the request span and lost the JDBC spans.
- **Two configuration traps, both silent.** The sampling property is
  `management.tracing.sampling.probability`; the name without `sampling` binds to nothing and
  leaves sampling at 0.1. And an OTLP endpoint set to the empty string does not mean "no
  exporter" — the exporter fails to build and the application does not start, which took out all
  195 tests at once.
- **The health endpoint would have taken the application out of the load balancer.** The mail
  indicator opens an SMTP connection on every check and reports DOWN when the relay is
  unreachable, dragging the whole endpoint down. Phase 7 makes that the Kubernetes readiness
  probe, so an undeliverable password-reset email would have stopped everyone browsing jobs.
- **`docker compose up` could never start the backend.** `DB_PORT` was read by the prod profile
  and set by nothing, so the container died on an invalid JDBC URL. Pre-existing, unrelated to
  this day, and found only because the day's Verify block required the stack to come up.
- **I got two readings wrong and corrected both in the open.** I reported that the starter
  instruments requests on evidence that turned out to come from the agent being attached too; and
  I reported that log correlation did not work, from a Tomcat error line written after the span
  scope closes. Both corrections are in the pull requests that made them.
- *Estimated 3 pull requests, took 7.*

**Phase 0 is complete.** 196 tests, a metrics endpoint, structured logs, and a trace you can find
from a log line. The contract tests that Days 2 to 4 built are the thing the rest of the migration
is measured against.

**Day 6 — Maven multi-module skeleton.** `backend` becomes a parent over six modules. The feature
modules are created empty and the code moves wholesale into `app`; Day 7 distributes it. All seven
criteria hold, 196 tests still green.

- **The dependency rule is enforced, not documented.** `maven-enforcer` fails the build when a
  feature module reaches for another, with a message saying to publish an interface instead. Proved
  by breaking it on purpose and watching it fail — a rule nobody has seen fail is a comment.
- **A green test suite does not mean the image builds.** The Dockerfile copied one module's sources,
  so the moment `shared` had code the container could not compile it, while `mvnw verify` — which
  builds the tree that is actually on disk — stayed green. Day 7 gives five more modules their first
  sources, so the bug was queued to recur five times.
- **Reading the spec against the code first found three classes with no module to go to**, including
  one used by two future services that therefore cannot live in either. It is in `shared` with an
  expiry date written into the pom.
- *Estimated 2 pull requests, took 2.* The first day that landed on its estimate.

**Day 7 — move the code into the modules.** Roughly seventy classes move into `identity`, `jobs`,
`applications` and `matching`, leaving `app` with five files. `applications` and `matching` stop
reading `identity`'s tables and ask through two interfaces in `shared`. 202 tests green.

- **Not one test file was edited.** The 196 contract tests never name an application class, so a
  seventy-class package move was invisible to them. That claim was made throughout Phase 0 and
  this is the first time it was put under load. It held.
- **The spec's central instruction was wrong in a way that would have looked like success.** It
  said to expect three compile failures; two of them were SQL strings, which no compiler can see.
  Following it, you would have moved the code, found one kind of failure instead of three, had
  nothing to unblock for Days 8 and 9, and concluded the boundary was clean while two modules
  still read each other's tables. The corrected spec has a criterion that runs backwards: after
  the move, those joins must **still be there**.
- **`UserLookup`, the class the spec said to break the coupling on, does not exist** anywhere in
  the repository. Day 10 is titled "Delete `UserLookup`" and rests on the same premise.
- **I tested my own justification for a file and it was false.** The ArchUnit rules were
  introduced as catching what `maven-enforcer` cannot; adding a dependency and an import together
  fails at the enforcer first. They earn their place as an independent record of the boundary —
  with the enforcer skipped they still fail — which is a smaller claim and the true one.
- *Estimated 4 pull requests, took 5.*

**Day 8 — remove the `saved_count` join.** `jobs` stops reading `saved_jobs` and asks
`applications` through `SavedJobCounts`, one call per page. The first of the two cross-module
joins is gone. 206 tests green.

- **The gate Day 3 wrote for this day passed unedited.** `JobSavedCountIT` was written five days
  earlier with a note that it "must not need editing"; the join under it was replaced and it did
  not. This is the first time a contract test stood between a refactor and a behaviour change
  instead of only recording one.
- **The spec's verify command ran no tests at all.** With `-Dtest` and no `-pl`, surefire applied
  the pattern to every module and stopped on `shared`, before `app`, where the tests live. It
  failed loudly, but `app/target` still held a green report from an earlier run, and anyone who
  read that instead of the exit code saw six passes that had not happened. A different route to
  Day 2's failure mode: a check reporting on tests it did not run.
- **One criterion asked for something no test could see.** "Assert the query count" had nothing
  to count with: the contract tests speak HTTP and JDBC, and neither shows how many statements a
  request ran. `pg_stat_statements` in the test container does, from the database side, without
  touching an application bean. The test it made passes before the change and after, so it was
  broken on purpose first: a per-posting loop failed it with 1 + page size statements.
- **Two slips of mine, caught before pushing:** a comment that put `saved_jobs` back under `jobs/`
  and failed the day's own grep, and a checkstyle violation that 206 green tests could not see,
  because `mvnw verify` does not run checkstyle.
- *Estimated 2 pull requests, took 3.* The third is the query-count test, which the spec change
  added before work started.

**Day 9 — remove the hydration join, and move the shortlist.** `applications` and `matching` stop
reading the mart. They ask `jobs` through `PostingLookup` for posting details and through
`PostingShortlist` for match candidates. No SQL crosses a module boundary any more. 210 tests
green.

- **The spec's central instruction could not be followed.** "Order and page inside `saved_jobs`"
  was impossible: the list is ordered by a mart column, and `saved_jobs` has nothing to order by.
  Following it would have changed the order users see and failed a Day 4 test. The fix hydrates
  the whole personal list and pages in memory.
- **`plan.md` counted two cross-module reads; there were three.** `matching` built its shortlist
  straight from the mart, and no Phase 1 day moved it. Found by reading every remaining spec
  against the code before starting, and added as Track C the same day.
- **Breaking the code on purpose found what reading had not.** Closing under the new rule that
  every check must be seen to fail, I reversed the saved-jobs sort. All 31 of Day 4's tests
  passed. They pin where a vanished posting goes, not which way the list runs. The spec had said
  Day 4 pins the order, and two spec changes and four track PRs had read past it. Nothing changed
  for users, since Track B kept newest first, but only by reading.
- *Estimated 2 pull requests, took 4.* Both extras came from spec changes before the work: the
  query-count test, and Track C.

**Day 10 — resolve the user once, at the edge.** Only `identity` turns an email into a user now.
`applications` and `matching` receive a user id from `@CurrentUserId` and never see an email;
`UserDirectory` is deleted. 217 tests green.

- **The first day run under the rule that every check must be seen to fail.** The rule's first
  catch was in the spec: it protected two modules' different answers for a session with no user
  behind it, and no test covered that case. A test pinning them landed before anything moved, and
  was broken on purpose twice across the day.
- **The resolver returns an empty value instead of throwing,** so each module keeps its own answer
  (404 and 422) and the order of answers is unchanged.
- **A branch copied into four controllers could never run.** Each copy accepted a principal type
  that nothing in the application creates. Removing it left every test green.
- **I set Day 8's trap again and caught it before pushing:** a comment in the day's own test named
  the class the day's grep checks is gone.
- *Estimated 3 pull requests, took 4.* The fourth is the tests-first track the spec change added.

**Day 11 — split the database by module.** Every table is in its module's schema, owned by its
module's role, and each module connects as that role: a write into another module's tables is
refused by Postgres. Each module has its own migrations and history. The end of Phase 1. 232
tests green.

- **The spec's plan could not have run.** It had each module's own migrations move its tables,
  but only a table's owner can move it. Tried on a throwaway Postgres before any work, and the
  moves now run as the owner and hand each table over.
- **Account deletion still works under the strict roles,** because Postgres runs a cascade as
  the owner of the table it deletes from. A test for it landed first; nothing had covered it.
- **Four more things only running found:** an enum that does not move with its table, a
  read-only pool setting that did nothing, an apostrophe that broke Flyway, and four default-sized
  pools that ran the test run out of connections.
- **One Day 1 test was rewritten, by decision:** it pinned every table to the old schema, the
  layout the day existed to change. `contract/` was not touched.
- *Estimated 3 pull requests, took 6.* Two came from the spec change (a tests-first track, and
  data sources as a track of their own), one from the 400-line gate splitting a track.

**Day 12 — JWT issuance and JWKS.** `identity` can mint RS256 access tokens, publish the key
they verify with at `/.well-known/jwks.json`, and issue, redeem and revoke refresh tokens stored
only as their hash. Nothing uses them yet: login is untouched until Day 13. 255 tests green.

- **The first day audited by agents with a fresh context** (#73). Before any work, the
  plan-auditor and the spec-auditor found that neither of the spec's checks could fail, that
  its key could come from nowhere without breaking the clean compose start, that identity's
  first migration would break two Day 1 harness tests, and that a missing `jti` would have made
  a Day 13 test flaky. All of it was fixed in the spec before the work (#74).
- **Breaking the code on purpose found one more:** the check that other modules cannot read the
  token hashes passed with the migration's revoke removed, as long as the test database lacked
  the default privileges production has. The test now asserts that they are there.
- **Two Day 1 harness tests changed, by decision before the work:** they pinned identity's
  table list and migration history, which the day's first migration grows. `contract/` was not
  touched.
- *Estimated 3 pull requests, took 3.* The spec change raised it to 4 for a split of the key
  track under the 400-line gate, which was not needed.

**Day 13 — replace session auth with JWT.** The backend authenticates from an access-token
cookie verified in process and holds no session; a refresh cookie, rotated on use and revoked at
logout and on a new password, keeps a browser signed in past the token's 15 minutes; the
frontend refreshes once on a 401. 275 tests green.

- **The test the plan was built around held.** The session-auth rewrite passed Day 02's 49 auth
  tests, and all 190 contract tests, with nothing in `contract/` edited: the auth cookie's name
  was the one line the contract suite reads that changed, as Day 02's spec change had arranged.
- **It held because of what the spec-auditor found first.** Its scratch build of the day, before
  any work, showed the spec as written would have turned the principal into a token object (81
  contract tests red without the fix), broken Google sign-in, left account deletion signing no
  one out, and let an expired cookie turn login, register and the job list into 401s with every
  contract test green. The frontend would have signed every user out after 15 minutes. All of it
  went into the spec before the work (#82).
- **The switch landed last,** after the tests, the cookies, refresh and the frontend, so `main`
  never signed a browser out early and reverting one PR restores session auth.
- *Estimated 3 pull requests, took 5.* The spec change added a tests-first track and a frontend
  track.

**Day 14 — Google sign-in without a session.** No step of any Google sign-in sets `JSESSIONID`,
and no backend code touches a session. The authorization request travels between the start and
the callback in a cookie signed with identity's key; a Google identity whose email is taken waits
in `identity.pending_google_links`, its claim code in a cookie the password login sends by itself.
295 tests green.

- **`contract/` held again, unedited,** through a change to the flow seven of its eight Google
  tests drive step by step, and the frontend did not change: the new cookies reach the callback and the
  login without anyone sending them.
- **The spec as first written would have broken it.** The spec-auditor found that the plan's
  "the redirect carries the code" would have failed `AuthGoogleSignInIT`, which compares that
  redirect exactly; that the largest item, the authorization request, had no track; and that
  nothing checked the new cookies' path, `SameSite`, signature or size, all of which left the
  Google tests green in its scratch copy (#90).
- **The checks outside `contract/` did the catching.** A header assertion is what fails on
  `SameSite=Strict`; a tampered-signature test is what fails when the signature is ignored; five
  paths, every step, are what fail when the failure handler makes a session.
- *Estimated 2 pull requests, took 4.* The spec change added a tests-first track; the 400-line
  gate split Track A in two.

**Day 15 — the API gateway.** `services/api-gateway`, a Spring Cloud Gateway on 8081, checks the
access-token cookie against the backend's key set, applies the backend's rules in its order,
replaces any `X-User-Id` with the token's user id, rate-limits the credential routes, refuses
cross-origin calls and carries the trace. The backend still checks every token itself. The whole
Day 1–4 suite also runs through it, in CI. 298 tests green direct, 203 through the gateway, 25 in
the gateway.

- **The spec as written would have passed with a gateway that checks nothing.** The spec-auditor
  put a plain proxy on 8081: every Verify curl and the "401 at the gateway" criterion came out
  as expected, because the backend answers them itself (#96). The gateway's checks now run
  against a recording upstream, where passing means nothing arrived.
- **`contract/` held, unedited, through the gateway:** all 190 tests, with 128 lines added to
  `support/` for the switch, and never instead of the direct run, which alone shows the backend
  still checks tokens.
- **Two framework defaults would have broken what the spec asked for.** Spring Boot 4 turns
  trace propagation off unless spans are exported, so the correlation id correlated nothing by
  default; and Spring's own CORS rejection refuses the frontend's login through its proxy. Both
  were caught by a test before they merged.
- *Estimated 3 pull requests, took 9* (7 at the close; #105 and #106 landed after it). The spec change added a tests-first track and the suite
  through the gateway; the gate split the last track; one test that raced turned `main` red.

**Day 16 — cutover.** The gateway is on 8080 and is the only way in: the backend has no published
port, and the frontend reaches it through the gateway. With the gateway stopped, the frontend's
`/api/users/me` is 500, not 401. The docs describe the gateway, `backend/docs/architecture.md`
draws the path before and after, and `phase-2` tags the rollback point. 299 tests green direct,
203 through the gateway, 27 in the gateway.

- **The spec would have let any client dodge the rate limit.** It said to trust the frontend's
  `X-Forwarded-For`; the frontend passes a client's own header through. The spec-auditor showed
  it with real containers (twelve spoofed logins, none limited), which reading `proxy.ts` did not.
  The header stays untrusted: locally every browser shares one bucket.
- **Its Verify began with `docker compose down -v`,** which deletes the maintainer's database, and
  its session grep could never pass: unscoped, it matched the tests that guard against sessions.
- **Two documents had been wrong since the initial commit:** compose never passed `backend/.env`
  to the backend, and a fresh database gives `/api/jobs` 500, not an empty list. Found by running
  compose, not by reading.
- *Estimated 3 pull requests, took 2.* The audit removed a track with nothing in it.

**Day 38 — each module reads only its own schema, and every call out is traced.** The first day
of the platform step, the corrected plan's work before any extraction (#115).
- **Grants.** Three migrations revoke every grant another login had on a module's schema: the
  schema itself, its tables, and what the module creates later. `jobs_user`, the login Day 17
  hands to a container of its own, is now refused `identity`.
- **Tracing.** The LLM call gets a metric and a client span, and an ArchUnit rule keeps later
  clients from being built untraced.
- **Gateway.** It has health and metrics on a port compose does not publish, and Prometheus
  scrapes it.

309 tests green direct, 203 through the gateway, 32 in the gateway.

- **The spec-auditor made two checks able to fail.** The public-port check passed with actuator
  on the public port, so it became a valid-token check that lands first (Track 0). The
  later-table check passed before any change, because the harness makes tables as a superuser.
  It also found 11 tests the day would turn red that the spec did not name.
- **The spec said the test harness should stop granting too,** like the two scripts. The suite
  would then have passed without the migrations. The harness kept its grants, so the migrations
  are what the tests check.
- **`main` went red after the gateway change, on a race the PR run did not show.** The gateway
  answers 500 for a moment after its port opens. The new management server made that moment
  about 300 ms long. The harness now waits for readiness (#124). I had run the gateway's own
  tests, not the backend suite through it.
- *Estimated 4 pull requests, took 5.* The fifth was that fix.

**Day 39 — a service proves who it is, and a deleted user stays deleted.** The second day of the
platform step.
- **Service tokens.** The monolith has a second key of its own, mints 5-minute service tokens with
  it (`aud=jobmatch-internal`), and publishes the public half at
  `/.well-known/service-jwks.json`. It does not start without the key.
- **`/internal/**`** has a chain of its own that takes those tokens, from the monolith itself or an
  issuer on a configured list, and nothing else: not the user's cookie, not a user token. The
  gateway routes none of it.
- **The deleted-user rule.** An access token outlives its user by up to 15 minutes. The monolith
  refuses that user because it looks the user up on every request; a service that trusts `sub`
  asks identity, `GET /internal/users/{id}`, 204 or 404, uncached.

334 tests green direct, 203 through the gateway, 33 in the gateway.

- **Every track was written by the implementer agent on Haiku** from a brief, the first day that
  was so; the reviews, the breaks, the commits and the pull requests stayed with the main session.
  Review changed something in four of the five tracks.
- **The spec-auditor found a criterion that could not fail, a break that did not compile, and a
  Verify whose curls checked nothing**, before any track (#127).
- **The spec said the OpenAPI had nothing internal to list before Track C.** From #131 it listed
  the service key set, on the document the gateway routes, until #133 excluded it.
- *Estimated 3 pull requests in the outline, 5 once written in full; took 5.*

**Day 40 — extracting job search becomes a change of URL.** The last day of the platform step.
- **The gateway** sends `/api/jobs`, `/api/jobs/filters` and `/api/jobs/{postingId}` to a URL of
  their own, `JOB_SERVICE_URL`, which is the backend until Day 17 sets it. `top-matches` shares
  the prefix but is matching's, and stays.
- **The test harness** sends each path to the service that owns it, from a table in `support/`,
  directly or through the gateway. Every service defaults to the monolith, so the Day 1–4 suite
  runs unchanged; a self-test points job search at a stub and sees it arrive.
- **`ObservabilityIT` left `contract/`**, by the maintainer's choice: it tests the monolith's
  own metrics, not the API, and could not have survived Day 17 unedited.
- **Compose** waits for the gateway to be ready before the frontend starts.

337 tests green direct, 206 through the gateway, 37 in the gateway.

- **Every track was written by the implementer agent on Haiku**, and review changed something in
  all four.
- **`up --wait` returns before the backend is ready:** the gateway's healthcheck is the only one
  compose waits on, and the first call through it answered 502, then 200 two seconds later.
- **I undid two breaks with `git checkout`**, which put back `main`'s file and lost the track's
  own change, and read a stopped Docker as 320 failing tests before reading the first error.
- *Estimated 3 pull requests in the outline, 4 once written in full; took 4.*

**Day 18 — the seams answer over HTTP before anything leaves.** The first day of Phase 3.
- **Three internal routes**, to service tokens only: posting details by id and the match
  shortlist from `jobs`, saved counts by posting from `applications`. Each answers what the
  in-process interface answers today, and none is called yet: Day 19's clients will be.
- **Saved counts came to Day 18** by the maintainer's choice. Job search needs them once `jobs`
  leaves on Day 17, and the seam rule asks for them to be serving before that, not built the
  same day.
- **Pinned first:** the gateway refuses all three, the public OpenAPI lists no `/internal/` path,
  and the shortlist's order is asserted on a fixture where every ranking rule decides a place.

366 tests green direct, 206 through the gateway, 37 in the gateway.

- **The provisional spec was written for the old order**, with a `job-service` that did not exist
  and a track Day 39 had already built; the plan-auditor found Days 17, 19 and 20 the same. Day 18
  was rewritten against the code (#148); the other three are rewritten on their own days.
- **The spec-auditor read the rewrite twice**, and the second read found five mistakes of mine,
  among them a fixture the database refuses and a break a fixture would have hidden.
- **Every track was written by the implementer agent on Haiku**, and review changed something in
  all four.
- *Estimated 3 pull requests in the provisional spec, 4 once rewritten; took 4.*

**Day 19 — the seams go over HTTP, inside one process.** The second day of Phase 3.
- **Three clients now serve:** saved jobs, top matches and job search reach `PostingLookup`,
  `PostingShortlist` and `SavedJobCounts` over HTTP, through Day 18's routes, with a service token,
  a 1 s connect and 2 s read timeout and a circuit breaker each. The routes still answer from the
  in-process code, so Day 17's extraction of job search is a change of URL.
- **Each outage has a declared answer:** saved jobs list with empty details, top matches answer
  503 rather than an empty list, job search reads `savedCount` 0. A 4xx is a bug, not an outage:
  it answers 500, and the breaker does not count it.
- **Every internal call is measured and traced** like the LLM call: a client metric with its route
  and status, and a span inside the request that made it.

390 tests green direct, 206 through the gateway, 37 in the gateway.

- **The provisional spec stopped a service that did not exist yet**; run as written, its Verify
  checked nothing. The spec-auditor's two reads before any track found 32 problems (#154).
- **One of the spec's reasons did not reproduce:** the request factory it rejected turned out to
  surface a timeout the same way. The choice stands; the claim is gone from the code.
- **Every track was written by the implementer agent on Haiku**, and review changed something in
  all six; its reports misstated test counts twice, which is why the PRs quote surefire.
- *Estimated 3 pull requests in the provisional spec, 6 once rewritten; took 6.*

**Day 17 — job-service becomes its own deployable.** The third day of Phase 3.
- **Job search runs in `services/job-service`,** its own image, workflow and compose service,
  with no published port. The monolith no longer serves `/api/jobs` or the postings routes, and
  `backend/jobs` is gone: the code moved with `git mv`, so the size gate counted it as renames.
- **The two services trust each other, and only whom they list:** job-service mints its own
  service tokens and publishes its key set, and both read their trusted issuers as a list that
  can be set from environment variables.
- **The test harness runs the real image,** one container per run, and the Day 1–4 suite passes
  against it unedited, directly and through the gateway. The query counts hold across the two
  processes, job-service reads the mart as `jobs_user`, and a trace crosses into it and back.

407 tests green direct, 218 through the gateway, 37 in the gateway, 54 in job-service.

- **The provisional spec moved `jobs` out with nothing serving in its place,** in three parallel
  tracks that no harness, CI image or service key backed. The spec-auditor's two reads found 30
  problems (#162); four of the second read's showed only by running, among them a base class's
  test property that silently wins over a subclass's.
- **Breaking the code found a gap the spec had named:** the query-count break it gave could not
  reach hydration, so the postings batch was broken on its own.
- **Linux found what Windows hid:** the harness gave the container a key file it could not read.
- **Every track but one was written by the implementer agent on Haiku**, and review changed
  something in twelve of thirteen. Once it deleted the check it could not make pass.
- *Estimated 3 pull requests in the provisional spec, 10 once rewritten; took 14,* three tracks
  splitting at the size gate.

**Day 20 — job-service gets its own database.** The last day of Phase 3.
- **The mart lives in `jobs_db`,** which only `jobs_user` and the two publish roles can connect
  to. `project_db` has no analytics schema any more, so a monolith path that still read the mart
  would fail, not pass on a stale copy. The harness, compose and `db-setup.py` build it the same
  way, and `jobs_user`'s read is a default privilege, so it survives the publish's drop and
  recreate.
- **The publish is tested against a real Postgres** in CI: `jobs_user` reads after a second
  publish, and the swap leaves no moment when the table is missing.
- **A runbook moves the mart and moves it back,** and both directions were rehearsed in a compose
  project of their own. Day 37 runs it for real.

412 tests green direct, 218 through the gateway, 37 in the gateway, 54 in job-service, 11 in the
publish's tests.

- **The provisional spec predated Days 17–19 and 38–40:** it claimed a job-service harness that
  does not exist, a pipeline with no tests (it had 9), and "all tests unedited", which could not
  hold once the mart left the database the harness reads. It was rewritten against the code and
  read twice by the spec-auditor (#183). The maintainer approved editing the two Day 01 self-tests;
  `contract/` stayed unedited.
- **One break reported something else than the spec said:** Postgres rejects an empty `IN ()`
  before `pg_stat_statements` counts it, so the check fails on a 500, not a count of 1.
- **The first merge to touch `data/` failed a CI job no day owned:** its Azure sign-in trusts the
  original repository, not this one. The push is now opt-in (#189).
- **Every track but the runbook was written by the implementer agent on Haiku**, and review
  changed something in all four.
- *Estimated 3 pull requests in the provisional spec, 5 once rewritten; took 5,* in 823 lines
  against an estimate of 900–1,350.

**Day 41 — matching reads identity over HTTP, still in one process.** The first day of Phase 4,
split out of Day 24 on Day 21 (#200): a matching container could neither read identity's schema
nor resolve the user by email.
- **Identity serves the profile** at `GET /internal/profiles/{userId}`, to service tokens only,
  and matching reads it through a client with its own breaker. The URL is empty, so the call
  stays in this process; Day 21's extraction is a change of URL.
- **The user id is the verified token's `sub`,** not an email lookup and not the gateway's
  `X-User-Id`, and before ranking matching asks identity whether the user still exists. Both
  calls are measured and traced by route, answer 503 when identity cannot be reached, and are
  not retried.

426 tests green direct.

- **Two spec mistakes were caught before any code** by the spec-auditor (#201): the new route's
  controller could have called itself through the client, and a 422 check could pass for the
  wrong reason.
- **Two checks do not see what the spec said they would:** the Day 04 "no user" test stays green
  when the existence call is ignored, and a red the spec wrote as 200 is 422. Both were only
  found by breaking the code.
- **Every track was written by the implementer agent on Haiku**; review changed something in all
  three, and rewrote none.
- *Estimated 5 pull requests; took 3,* in 600 lines.

**Day 21 — matching-service becomes its own deployable.** Matching left the monolith, as job
search did on Day 17, into a container with its own image, pipeline, service key and schema.
- **The gateway sends top-matches to matching-service,** which verifies the user's token
  itself, asks identity and job-service over HTTP, and alone holds the LLM key. The monolith has
  no matching module, no LLM settings and no matching datasource left.
- **A hung model now costs at most ten requests:** a bulkhead lets ten scoring calls wait on it,
  and the eleventh gets the skill-overlap order at once, marked unscored. The model's read is
  15 s, so three internal calls and the model fit inside the gateway's 30 s.
- **The Day 04 matching tests run against the container unedited,** directly and through the
  gateway.

425 tests green direct; through the gateway, all but `ServiceJwksIT`, which CI's gateway run
leaves out and no day owns. 63 in matching-service, 55 in job-service.

- **The spec was rewritten twice before any code** (#200, #206): the first draft still read
  identity in-process, and the second, after Day 41, had not listed what removing the module
  breaks. Two more spec changes followed during the day (#211, #222).
- **One test outside `contract/` was edited,** one word, by the maintainer's decision: it asked
  the monolith directly for a route the monolith no longer serves.
- **The 400-line gate was overridden for the first time since Day 3,** on the move itself (950
  lines, most of them deletions), as the spec allowed. Five other tracks were split to stay
  under it.
- **Review changed the implementer's first draft in seven tracks,** once because the harness
  would have stopped calling the model while every model test still passed.
- *Estimated 15 pull requests; took 22* (18 tracks, 3 spec changes, the close), in 5,252 track
  lines against an estimate of 2,900–4,240.

**Day 22 — matching-service keeps its scores in DynamoDB.** The scores moved from Postgres to
DynamoDB (`dynamodb-local` in compose, the harness and CI) in one step, with no dual write: the
table is a cache, and an empty cache costs only model calls.
- **An item's `ttl` replaces the hourly purge,** and the read still refuses an expired item,
  since DynamoDB deletes them only within days. The service has no scheduler left.
- **A store that is down or hung costs a model call, never the request:** every call has
  500 ms, and the model's read went from 15 s to 13 s to keep the sum inside the gateway's 30 s.
- **matching-service is scraped at last,** with a hit/miss counter and a hit-rate panel; it had
  had no metrics since Day 21.

429 tests green direct; through the gateway, all but `ServiceJwksIT`, as on Day 21. 72 in
matching-service.

- **The spec was rewritten before any code** (#232): the provisional one planned the dual write
  `plan.md` rules out, and an interface and a compose service that did not exist.
- **No test was edited,** and none needed an override: the one track over the gate was split
  as the spec planned (B2 at 399 lines).
- **Review changed the implementer's first draft in five tracks,** once because the read
  filtered on the wrong attribute. Two tests passed against a store they never reached until a
  break showed it.
- *Estimated 8 pull requests; took 9* (7 tracks, the spec change, the close), in 1,289 track
  lines against an estimate of 960–1,470.

**Day 23 — matching-service lets go of Postgres.** The scores table is dropped by the monolith's
own migration (V15), not the service's, because the service no longer has one: its datasource,
Flyway, JDBC and Postgres dependencies, and the `DB_*` keys in compose and the harness, are gone.
It starts with no database to reach.
- **V15 also carries the revokes** the service's first migration used to make. Without them the
  harness's grants on schema `matching` would have stayed, and the test that every module's
  schema is closed to the others failed when they were removed on purpose.
- **`ServiceJwksIT` passes through the gateway** after two days red there: it now asks the
  monolith directly, and CI's gateway run includes it.

428 tests green, directly and through the gateway. 69 in matching-service.

- **Two tests the spec did not name had to change,** and one dependency it did not name had to
  be added, each recorded in its PR.
- **One criterion could not be met as written:** its grep for `DB_` also matched
  `SCORES_DYNAMODB_ENDPOINT`. Its intent holds, checked with the grep anchored on the quote.
- *Estimated 6 pull requests with Track B split; took 6* (4 tracks, the spec change, the close),
  in 510 track lines.

**Day 24 — matching-service stands alone.** The last day of Phase 4, tagged `phase-4`.
- **Identity gone is a clear 503, fast:** with identity refusing the connection or hanging,
  top-matches answers "Your account could not be checked" in under 3 s, and asks identity again
  on every request; the answer that a user exists is never cached.
- **One request is one trace across four hops,** gateway, matching-service, identity and
  job-service, checked by a test that sends no trace id of its own, so a gateway that only
  forwards a header cannot pass.
- **The cache and retry questions were decided on numbers, by rules fixed before measuring.** In
  compose, without the model, the profile call was 16% of top-matches' server time, over the 10%
  bar, so a rankable profile is now reused for 10 s. No route showed an error on 160 calls each,
  so none of the three clients retries.

429 tests green, 76 in matching-service.

- **The spec was rewritten from the auditor's ten findings before any track** (#250): among them,
  its Verify would have shown a 401, not the 503 it promised.
- **A finding was lost between two tracks:** Track 0 saw the JDK client resend a GET on a dropped
  connection and left it to Track A, whose "no retry" Javadocs did not mention it. The close
  added it.
- *Estimated 4 pull requests, 5 if the measurement asked for the cache; it did, and took 5,* in
  483 track lines.

**Phase 4 is complete.** Matching runs in its own container with no database but DynamoDB, and
reaches identity and job-service only over HTTP, so the slowest path in the system, the call to
the model, is isolated from everything else.

**Day 26 — a deleted account is announced, once it is gone.** The first day of Phase 5, run
before Day 25 in the plan's deletion-first order.
- **The delete and its event commit together:** `DELETE /api/users/me` writes a `user.deleted`
  row to `identity.outbox` in the delete's transaction, so there is an event if and only if the
  account is gone, checked both ways with a failure planted on each statement.
- **A relay publishes it to SNS,** one topic fanning out to a queue per consumer
  (`applications-user-deleted`, `matching-user-deleted`), each with a dead-letter queue, on a
  pinned LocalStack in compose and the harness. With the bus paused the delete still answers 204
  and the event waits; a publish whose row delete fails is published again with the same
  `eventId`, a duplicate by design.
- **The event is a written contract,** `docs/events/user-deleted.md`: five fields, no email or
  name, and the rules Day 27's consumers are held to. Nothing reads the queues yet.

439 tests green; `AccountDeletionIT` unedited and 3 of 3 after every track.

- **The spec was rewritten before any track** (#255): the provisional day depended on Day 25,
  put RabbitMQ in compose and emitted `user.registered`, none of which `plan.md` asks for.
- **Review caught the event being lost:** the implementer's relay caught a failed publish inside
  its transaction, so the row's delete would have committed. That exact break is now what the
  bus-down test reports.
- *Estimated 6 pull requests; took 6,* in 1,000 track lines.

**Day 27 — `user.deleted` reaches every store.** The second day of Phase 5.
- **Two consumers read the bus,** each an SDK `SqsClient` polling loop on a queue of its own:
  `backend/applications` deletes the user's saved jobs, and matching-service evicts their cached
  profile. A message is deleted only after it was handled; a repeat is a success, an unreadable
  body reaches the dead-letter queue after five receives, and one bad message never stops the
  loop.
- **Tested without the foreign key's help:** while `fk_saved_jobs_user` still deletes the rows,
  the consumer's test sends the event for a user who still exists, on queues of its own with a
  1 s visibility timeout, so each failure path is seen within 15 s.
- **Nothing else holds the user:** a DynamoDB score item has exactly six attributes and none
  names a user, and the existence call still refuses a deleted user whose profile is cached. The
  call stays; no record of deleted ids is kept.

In compose, one `DELETE /api/users/me` drains both queues within 5 s with nothing dead-lettered;
`AccountDeletionIT` unedited and 3 of 3 after every track.

- **The audit rewrote the spec first** (#261): consumers in cached contexts would have taken the
  relay test's messages, and on the shared 30 s queues the failure criteria could not fail.
- **Both tracks split** to stay under 400 lines, which the estimate did not count.
- *Estimated 5 pull requests; took 8,* in 1,214 track lines.

**Day 25 — application-service becomes its own deployable.** The third day of Phase 5.
- **Saved jobs and the tracker left the monolith** for `services/application-service`, on Day
  21's pattern: its own image and CI gate, the route `/api/saved-jobs/**` at the gateway, a
  service key with its key set, the trust lists both ways, and `POST /internal/saved-counts` for
  job-service's search.
- **Its own database,** `apps_db`, migrated by its own Flyway as `applications_user`, who can no
  longer connect to `project_db`. V16 drops the old table, and `fk_saved_jobs_user` with it, so
  a deleted account's saved jobs now go only by `user.deleted`, through the consumer that moved
  with the table. `copy-saved-jobs.py` moves existing rows and skips any already there.
- **Unchanged from outside:** a deleted user still gets 404 "User not found", identity is asked
  once per request, search reads saved counts once per page, and with application-service down
  the search still answers, with counts of 0.

`AccountDeletionIT` 3 of 3 with its one approved edit; the other Day 3–4 saved-jobs tests
unedited, `JobSavedCountIT` through a `postgres_fdw` bridge in the harness.

- **The audit rewrote the spec first** (#278): three tracks for what Day 21 did in fifteen PRs,
  and a `savedCount` hold that could not pass once the table moved.
- **A track went missing:** E1c, announced in #298, never had a PR, and the dashboard read the
  day as done. The close's evidence-gathering found it, and it landed before the close (#305).
- *Estimated 15 pull requests; took 25,* in 7,022 track lines; E1a under `Oversized:`, as its
  spec allowed.

**Day 28 — identity-service, and the monolith is gone.** The last day of Phase 5, and the
stopping point, tagged `phase-5`.
- **`backend/` is now `services/identity-service`,** moved by rename (230 files, 45 counted
  lines): its workflow, compose service, gateway route and the harness follow it, and its
  database is `identity_db`. The Maven wrapper and `checkstyle.xml` moved to the root first, so
  every service builds from there.
- **The Day 1–4 suite moved with it, unedited:** 22 `contract/` files as renames, 0 lines
  changed, and 435 tests green in process after every track, the same count as before the move.
- **A fresh clone runs:** identity-service has the healthcheck the other services have, Google
  credentials reach it, and on a fresh volume, after the README's mart step, register, login,
  profile, jobs, save, top-matches and account deletion all answer through the gateway.
- **The stopping-point review** (#321): the plan-auditor on the whole plan found Phases 0–5
  delivered, Phase 7's drafts written for Azure and Kubernetes, and Phase 6 with nothing to move.
  The maintainer changed course: a cleanup day, Phase 7 rewritten for ECS, Phase 6 deferred.
  Twenty-one leftover items were each kept, dropped or handed on.

- **The audit found the spec would have deleted the suite:** "delete `backend/`" took the Day
  1–4 tests and the harness with it (#313). They move instead.
- **Not met:** C28.11, a stranger following "Running it" from a clone, timed; it waits for one.
- *Estimated 7 pull requests; took 7,* in 791 track lines.

**Day 42 — the cleanup day.** What Phases 3–5 left behind, before Phase 7; nothing added a
feature.
- **identity signs no service tokens and trusts only its list:** its issuer, key, key set and
  the client seam with no caller went (`InternalClients`, `StubUpstream`, `shared/jobs`,
  `@CurrentUserId`), and job-service stopped trusting it. Of the day's 2,395 track lines, most
  were deletions.
- **Only `identity_user` connects to `identity_db`,** in compose, the harness and production's
  setup script, and a test reads the three copies so they stay the same.
- **All five services log JSON in their images, and none logs a user's email.** Prometheus
  scrapes the fifth service, application-service.
- **identity's README and docs link to files that exist** (74 of 129 broken before), and CI
  checks it.
- **The checks moved with the deletions:** a test that sees a retired service token refused,
  one that a null reason does not drop a DynamoDB batch, one that no email reaches the logs.
- **Running found what the audit did not:** C42.1's grep matched the other services' keys,
  which have to stay, and a hold on job-service's JSON named a test that reads another
  service's logs. Four of nine implementer drafts had a defect review caught, one a test that
  could not fail.
- *Estimated 9 pull requests; took 11,* in 2,395 track lines; the gate split A1 and A2a.

**Day 32 — Terraform base.** The first day of Phase 7, rewritten for ECS on Fargate: the data
stores and the network in Terraform, checked without an AWS account.
- **State is remote and locked:** an S3 backend with a lockfile, its bucket versioned, private
  and encrypted from a bootstrap root with a monthly budget; on LocalStack, a second plan is
  refused the lock while an apply waits at its prompt.
- **RDS, the score table and the deletion bus are described once,** and checked on the plan or
  on LocalStack against the code that uses them: the table against `ScoreStoreConfig` and
  matching's `application.yaml`, the bus against `EventBus.java` and compose's `bus-init`, whose
  DLQs now keep messages 14 days as Terraform's do. RDS is private, admits only the tasks'
  group, and has no password in the plan.
- **No secret in a plan or the state,** swept by value, not by the provider's sensitive marks,
  which flag a null password.
- **What could not be checked as the spec said:** the queue policy holds the topic's ARN, unknown
  in a plan without an account, so it is read from the LocalStack state; and no CI run went red,
  so the breaks ran through the workflow's own steps locally.
- **Review changed every one of six implementer drafts,** two of them checks that passed on a
  missing resource; the implementer reported "complete" or "no departures" four times when it
  was not.
- *Estimated 4 pull requests; took 6,* in 2,103 track lines; the gate split A and C2, and A2
  merged `Oversized:`.

**Day 33 — the code before ECS.** Split from the services on ECS (now Day 36) and rewritten after
the audit found the Kubernetes draft: what ECS needs from the code before any task definition.
- **Keys come from a variable's content,** in identity's `SigningKey` and the three services'
  `ServiceSigningKey`s, with the same key id as from the file; both set, or neither, stops the
  service with a message naming both, and a value that is not a key is never repeated in it.
- **Migrations run as a separate run that exits:** `MIGRATE_ONLY=true` in identity and
  application-service starts a non-web context holding only Flyway and exits 0, or 1 on a
  failure; `MIGRATE_ON_START=false` starts without migrating. Compose and the harness set neither.
- **`db-setup.py` runs against Postgres in CI for the first time,** twice, its result pinned; with
  `--passwords-from-env` it takes every password from the environment and prints none. CI's
  Postgres moved from trust to password auth, because under trust the login check could not fail.
- **The holds were broken only at the close:** no track PR broke C33.3 or C33.6.
- **Review changed all five implementer drafts;** two were rewritten in the main session, and
  twice the implementer added an unreported `baselineOnMigrate` that would have skipped V1. The
  main session's own JWKS assertion did not compile, and two of its breaks did not run.
- *Estimated 5 pull requests; took 7,* in 1,751 track lines; the gate split A and B.

**Day 36 — the services on ECS.** Split from Day 33 and rewritten after the audit found checks
that could not read the plan: the five services and the frontend as Fargate tasks behind an
HTTPS load balancer, checked on a plan made without an AWS account.
- **The checks read what the plan cannot hide.** Container definitions and IAM policies are
  unknown without an account, so each service's variables, secrets, probes and policies are
  declared literally in `local.services` and read back from a `services` output; eleven
  `check_c36_*` run in infra-ci, each seen failing before its Terraform landed.
- **Fourteen secrets are created empty,** with no version in the plan; Day 35 writes their values.
  Each task's execution role reads only the secrets it names, and each task role reaches only its
  own topic, table or queue.
- **Only the frontend is behind the load balancer;** the services find each other through
  Service Connect under their compose names, and every variable compose sets is either in a
  service's entry or on a listed compose-only exception with its reason.
- **Migrations and role setup are one-off tasks:** the two `MIGRATE_ONLY` runs and `db-setup.py`
  in an image of its own. Matching scales on CPU; the gateway stays at one task.
- **Docker Hub's pull limit reached this repository** for the first time: one track merged with
  its image check red, and that check first passed on `main`, on the third attempt.
- **Review changed three of the five implementer drafts,** the first written on Haiku 5.5. Each
  fix was in a check that would have passed when it should not.
- *Estimated 8 pull requests; took 7,* in 1,858 track lines; the gate split C and, beyond the
  spec's planned split of D, D2.

**Day 34 — observability on ECS.** Rewritten from the Helm draft and audited before any track:
each JVM service's task gets an ADOT collector beside it that sends traces to X-Ray and metrics to
CloudWatch, with the ALB's alarms and one dashboard, all checked on a plan made without an AWS
account.
- **The audit ran the collector before the spec was merged,** and five of its findings would have
  shipped as plausible configuration: awsemf's rollups multiplying the metrics, Micrometer's
  cumulative counts plotted as running totals, a health port the host cannot reach, an alarm on
  `UnHealthyHostCount` that cannot fire once ECS deregisters a task, and a dashboard output with
  no value in the plan.
- **Each service names its own traces,** and Grafana's dashboard no longer says `jobmatch-backend`.
- **The collector is a file in the repository,** parsed by the check and started by infra-ci with
  its own `/healthcheck`; a misspelt key stops it there.
- **The services' defaults already pointed at `localhost:4318`,** which in `awsvpc` is the
  sidecar, so no endpoint variable was needed; the task roles gained only X-Ray's two `*` actions
  and the metrics group's streams.
- **The plan hid one more thing than the spec expected:** an alarm's whole `dimensions` map, so
  that check reads its keys from the Terraform text. Public ECR's throttle failed one CI run that
  the spec had said could not be limited.
- **Review changed one of the seven implementer drafts.** The implementer stopped on the alarm
  check rather than guess, and fixed its own `count_expression` slip before handing back.
- *Estimated 7 pull requests; took 7,* in 1,114 track lines, none over the gate; the spec had split
  C and D at the auditor's sizes.

**Read on the hypothesis at the end of Phase 0.** Across five days the agent's implementation has
been sound and its most useful output has been *disagreement with the spec*. The constraint is
specification quality and estimation, as predicted — but not in the way predicted. The expectation
was that specs would be found wanting once code was written against them. For Days 3 and 4 the
most valuable findings came earlier still, from reading a spec against the code before writing
anything.

Day 5 is the counter-example, and the more honest one. Its central assumption — trace with the
Java agent — could not be checked by reading anything. It needed the stack running, a request
made, and the result looked at; and the answer was that the tool the spec named does not work on
this stack and makes things worse when present. That cost four pull requests and produced two
intermediate conclusions that were themselves wrong and had to be corrected in public. **Days
written from documentation are checked by reading; days written from assumption are only checked
by running.**

What still has not happened: **no finding has come from reviewing the agent's code.** Every one
came from reading the system, checking a spec against it, or running the thing and looking. The
hard evidence comes at Day 13, when the session-auth rewrite must pass Day 2's 49 tests
unchanged.

**Read at the end of Phase 1.** The modules are separated three ways now: at compile time (the
Maven enforcer, Day 6), in the test suite (ArchUnit, Day 7), and in the database (one role and one
schema each, Day 11). Across Days 6–11, which moved about seventy classes, removed three
cross-module reads and split the database, no existing contract test was changed; `contract/`
only gained tests.

The shape of the findings changed after Day 9. Until then they came from reading a spec against
the code. Since then a second source has found as much: **breaking the code on purpose.** A check
has to be seen failing before it counts, and doing that found a gate that did not pin the order it
claimed to (Day 9), statuses the spec protected with no test behind them (Day 10), and a setting
that did nothing (Day 11). Still no finding has come from reviewing the agent's code. Day 13 is
the test the plan was built around, now with the database roles in place under it.

**Read at the end of Phase 2.** Auth is stateless and a gateway stands in front: the session went
on Days 13 and 14, the gateway came on Day 15, and on Day 16 it became the only way in. Day 13 was
the test the plan was built around, and it held: the session rewrite passed the Day 2 suite with
no line of `contract/` changed. Across the phase `contract/` gained no edits at all, and since Day
15 the same suite also passes through the gateway.

The findings moved again. In Phase 1 they came from reading specs against the code and from
breaking the code on purpose. In Phase 2 the ones that mattered most came from **running the
system as a stranger would**: a spec-auditor in a fresh context put a pass-through proxy where the
gateway would be and every Day 15 check passed; on Day 16 it chained real containers and a spoofed
header chose its own rate-limit bucket. Neither could be seen by reading. The estimates missed in
both directions: 14 track pull requests planned for the phase, 23 taken.

The measurement moved too. The dashboard said the spec closed as much of the spec-to-code gap as
the code did. Counting each merge only against the names known by then (#107), that was mostly an
artifact: code changes close the gap, and spec changes widen it by naming what is not built yet.
The spec leads and the code follows, which is what a spec-first process should look like.

**Read at the end of Phase 3.** One service is out: job search runs in its own image, answers over
its own API and reads its own database, and the monolith reaches it only over HTTP. The order the
plan change set (#115), seams first and the move second, held. Days 18 and 19 put job search
behind internal routes and clients while it still ran in-process, so Day 17's extraction and Day
20's database move were, for the tests, changes of URL. Across the phase `contract/` gained no
edits. The one approved departure was Day 20's: the two Day 01 harness self-tests read the mart
from its new database (+15 −11), with the same assertions.

The findings came from the specs first. All four provisional specs predated the platform step and
the new order, and each was rewritten against the code before any track, with the spec-auditor
reading it twice. On Days 19, 17 and 20 those reads found 32, 30 and 22 problems. Running found what reading could not:
a key file only Linux refused, an `IN ()` that Postgres rejects before anything counts it, and a
CI job that trusted another repository. Every track but two was written by the implementer agent
on Haiku, and review changed something in nearly all of them; once it deleted the check it could
not make pass. The estimates missed upward again: 12 track pull requests in the provisional specs,
25 once rewritten, 29 taken, 14 of them on Day 17.

## What is being measured

| Question | Evidence it will be judged on |
| --- | --- |
| Do contract tests written against the monolith survive the split? | Lines changed in `contract/` versus in `support/`. **Day 7 moved ~70 classes into modules: zero lines changed in `contract/`. Day 8 replaced a cross-module join: zero again, and 53 lines added to `support/` for a statement counter. Day 9 removed two more: zero again. Day 10 moved the user lookup to the edge: zero again. Day 11 split the database by module: zero again. Day 12 added token issuance: zero again, and 73 lines added to `support/` for a signing key and production's default privileges. Day 13 replaced session auth with tokens: zero again, with `Cookies.AUTH` the one line changed that the contract suite reads, and 131 lines added to `support/` for three helpers only the tests outside `contract/` use. Day 14 took Google sign-in off the session: zero again, and `support/GoogleSignIn` split into its two steps (+16 −3). Day 15 put a gateway in front: zero again, and the whole contract suite also runs through it, with 128 lines added to `support/` for the switch. Day 16 took the backend's port away: zero in `contract/` and zero in `support/`. Day 38
revoked the cross-module grants and traced the LLM call: zero in `contract/`, and `support/`
+13 −8 for the gateway harness's readiness wait and a comment. Day 39 put service tokens on
`/internal/**`: zero in `contract/`, and `support/` +136 for a stand-in service with a key set of
its own and the harness's service key. Day 40 sent job paths by a table in `support/`, +237 −12,
and changed `contract/` once, by approval: `ObservabilityIT` moved out (+30 −12 as a rename),
because it tests the monolith's own actuator, not the API, and two of its requests and one metric
were job search's. Day 18 put internal routes in front of three seams: zero in `contract/`, and
`support/` +82 for the shortlist fixture two tests share. Day 19 sent the suite's saved-jobs,
matching and search calls over HTTP: zero in `contract/`, and `support/` +193 for a stub upstream
and its self-test. Day 17 moved job search into its own container: zero in `contract/`, and
`support/` +381 −15, most of it the container itself (`JobService`, 257) and a span recorder moved
out of a test. Day 20 moved the mart into its own database: zero in `contract/`, `support/`
+96 −15 for the second database and its seams, and, by approval, the two Day 01 self-tests +15 −11
to read the mart there. Day 41 put matching's reads of identity behind HTTP: zero in `contract/`
and zero in `support/`. Day 21 moved matching into its own container: zero in `contract/`, and
`support/` +362 −42, most of it the container and its key (`MatchingService`, 99;
`MatchingServiceKey`, 119) and a model stub that can hang; outside both, one word in
`TopMatchesTrustTheSubjectIT`, by approval. Day 22 moved the scores to DynamoDB: zero in `contract/`,
and `support/` +194 −3 for the table, its reset and `ScoreStore.ageAll`; outside both, one new test. Day 23 took matching-service off Postgres: zero in `contract/`, and
`support/` +1 −7, the container's `DB_*` lines. Day 24 tested the network hops and cached the profile: zero in `contract/`, and
`support/` +13, a request line per request in the harness gateway and its `logs()`. Day 26 put an
event bus behind account deletion: zero in `contract/`, and `support/` +296, the bus container
(`EventBus`, 164) and its self-test. Day 27 put two consumers on the bus: zero in `contract/`,
and `support/` +43 −1, `EventBus`'s queue count and URL lookup and the matching consumer off in
the harness. Day 25 moved saved jobs to application-service: `contract/` +13 −13, the one approved
edit, `AccountDeletionIT` counting in `apps_db` and waiting for the event; and `support/` +372 −48,
`apps_db` and its grants, application-service's container on a queue of its own, `ServiceKey` in
place of `MatchingServiceKey`, and the `postgres_fdw` bridge. Day 28 moved the suite with
identity to `services/identity-service`: zero in `contract/`, its 22 files renames, and
`support/` +7 −7, the database's new name and the service's URL. Day 42 removed identity's service issuer and client seam: zero in `contract/`, and `support/` +28 −233, most of it `StubUpstream` and its self-test. Day 32 described the data stores in Terraform: no file under `services/` changed. Day 33 readied the code for ECS: zero in `contract/`, and `support/` +59 −3, `PostgresContainer`'s empty databases and per-database connections and `IntegrationTest`'s `useServices()`, for the migrate-and-exit tests. Day 36 put the services on ECS in Terraform: no file under `services/` changed. Day 34 added observability on ECS: zero in `contract/` and `support/`; under `services/`, two Dockerfiles and two comments.** Additions between refactors are counted apart: 32 lines pinning the saved-jobs order (#57), and one new file for Day 10's tests-first track. Days 17–28 and 42 are the remaining tests, run in the corrected order (#115) |
| How good are day-sized estimates for agent-implemented work? | Estimated vs actual pull requests per spec (currently 3→1, 3→1, 3→4, 3→5, 3→7, 2→2, 4→5, 2→3, 2→4, 3→4, 3→6, 3→3, 3→5, 2→4, 3→9, 3→2, 4→5, 3→5, 3→4, 3→4, 3→6, 3→14, 3→5, 5→3, 15→22, 8→9, 6→6, 5→5, 6→6, 5→8, 15→25, 7→7, 9→11, 4→6, 5→7, 8→7, 7→7; Days 1 and 2 were one oversized pull request each) |
| Does the agent catch defects in the system it is migrating? | Defects found and filed: 432 entries, Days 1–42. Only 20 were in the system being migrated; 412 were in the agent's own work, its specs, drafts and tooling, caught by an auditor, a review, a break on purpose or the close. Counted by kind in [Defects in the system](#defects-in-the-system) and [The agent's own failures](#the-agents-own-failures) below; every entry, by day, in [`docs/defects.md`](defects.md) |
| How often do specifications need revision once work starts? | Spec-change pull requests per day spec (currently 36 of 37 days worked; Day 38 needed one, the audit's (#119), made the day after it was written (#118); Days 39 and 40 one each, written in full and audited in the same PR (#127, #140), and Days 18 and 19
one each, rewritten against the code and audited twice in the same PR (#148, #154), and Day 17 two, the rewrite (#162) and a split recorded after the track that made it (#175), and Day 20 one, rewritten against the code and audited twice in the same PR (#183), and Day 41 one, the audit's (#201), after it was split out of Day 24 in Day 21's (#200), and Day 21 four, the seam-first rewrite (#200), the rewrite after Day 41 (#206), and two during the day (#211, #222), and Day 22 one, rewritten from the provisional dual write after the audit (#232), and Day 23 one, the drop moved into the monolith's migrations after the audit (#244), and Day 24 one, rewritten from the audit's ten findings (#250), and Day 26 one, rewritten for the plan's deletion-first order and SNS/SQS after the plan-auditor (#255), and Day 27 one, the consumers isolated from the relay tests and checks that can fail, after the audit's ten findings (#261), and Day 25 one, twelve tracks for three and the bridge that keeps `JobSavedCountIT` unedited (#278), and Day 28 two, the suite moved rather than deleted after the audit (#313) and the healthcheck track and the review list after the plan-auditor (#319), and Day 42 one, written and audited in the same PR (#329), and Day 32 one, rewritten for ECS on Fargate and audited (#345), after a plan change (#342), and Day 33 one, rewritten as the code before ECS (#354), after a plan change that split it (#353), and Day 36 one, rewritten from the audit's fourteen findings for checks that read declared values (#364), and Day 34 one, rewritten from the Helm draft and the audit's fourteen findings (#376); Days 5, 8, 9, 10, 11, 12 and 15 needed two and Days 13, 14 and 16 three, Day 8's first made on Day 3 while writing its gate, Day 13's first on Day 2, those of Days 10 to 16 on Day 9, Day 14's second on Day 13, Day 16's second on Day 14) |
| How much of the agent's work is redone? | Rework rate per day: tracks whose implementer draft review changed, out of the tracks the implementer wrote, and spec-change PRs (the row above). Days 39, 40, 18, 19, 17, 20 and 41 count any change, style included: 4/5, 4/4, 4/4, 6/6, 12/13, 4/4, 3/3, so 37 of 39 tracks. From Day 21 the Notes record only defects, so the numbers are not comparable with the earlier ones: Days 21, 22, 23, 24, 26, 27, 25, 28, 42, 32, 33, 36 and 34 had 7/14, 5/7, 1/4, 2/3, 2/4, 3/6, 9/23, 1/2, 4/9, 6/6, 5/5, 3/5, 1/7, so 49 of 95 tracks with a defect in the draft. Days 1–16 and 38 have no implementer and no rate (—) |
| Does the 400-line gate hold without override? | `Oversized:` overrides used (currently 7: #1 at 1,420 changed lines, #2 at 1,075, #3 at 712, all before Day 3, #224 at 950 on Day 21, the move itself, as its spec allowed, and #296 at 1,225 on Day 25, its move, as its spec allowed, and #326 outside the days, the README's results moved into this notebook, every line counted twice because a move out of a file that stays is no rename, and #347 at 674 on Day 32, all new, one checking script, by the maintainer's choice over a split; with the gate having forced a split forty-one times, the sixth Day 15's Track C, the seventh Day 39's Track A, the eighth the README's drawings, outside the days (#142, #143); the ninth to eleventh Day 17's Tracks B1, B2 and D, D at 453 lines (#166–#170, #174); the twelfth to fifteenth Day 21's Tracks A1, B1, B2 and E1 (#212–#218, #224–#225); the sixteenth Day 22's Track B, B2 at 399 lines (#237, #239); the seventeenth Day 23's Track B, as its spec planned (#246, #247); the eighteenth Day 27's Track A (#263, #264); the nineteenth and twentieth its Track B, once as its spec planned (#269) and once more (#270, #271); the twenty-first to thirtieth Day 25's Tracks A1, B1, B2 and E1 in three each, and B3 and F in two (#281–#292, #296, #298, #301, #302, #305); Day 28 needed none, its move counted 45 lines as renames (#315); the thirty-first and thirty-second Day 42's Tracks A2a and A1, A2a past the spec's own split (#332, #336; #334, #335); the thirty-third and thirty-fourth Day 32's Tracks A and C2 (#346, #347; #350, #351); the thirty-fifth and thirty-sixth Day 33's Tracks A and B (#356, #357; #358, #359); the thirty-seventh to thirty-ninth Day 36's Track C (#368, #369) and its Track D twice, once as its spec planned (#372) and once more (#373, #374); the fortieth and forty-first Day 34's Tracks C and D, as its spec planned (#380, #381; #382, #383). Recorded as 0 until after Phase 2. The checks did not block a merge until `main` was protected after Phase 2) |
| Is the finished system actually independently deployable? | Each service builds, tests and deploys from its own workflow. At Day 28 each of the five builds and tests from its own workflow, and none deploys: Phase 7 is the only phase left that answers this. Day 32 described the data stores and network in Terraform, planned without an account and applied only to LocalStack. Day 33 readied the code: keys and passwords from the environment, and the migrations as a run that exits. Day 36 described the services on ECS behind one load balancer, planned without an account, and Day 34 their collectors, alarms and dashboard: nothing is deployed before Day 35 |

**Attributing the gap to names, tested and dropped (2026-10-08).** Each name's share of the
spec and code vectors (log1p, masked to known names) was compared merge to merge, to say which
names moved the gap. On code PRs the top names (#1, #80, #52) were mostly names entering the code
for the first time, which name coverage and Jaccard already count; on spec PRs the output was
renormalization noise, and without log1p `postingId` and `userId` led every list. Every gap move
above 0.85° came in Days 1–14; the largest after merge 120 was 0.72° (#183), and the gap has
stayed below the chance band since #80. Attribution would have added a panel with nothing new in
it. One split is parked: each move into drift on known names and entry of new vocabulary,
computed by angling merge i under merge i−1's mask.

### Defects in the system

| Kind | Count | Examples |
| --- | --- | --- |
| CI and test harness | 8 | a CI gate, two harness defects, two fixtures unlike production, a CI job that trusted another repository, a startup race that turned `main` red |
| Production code | 4 | two production defects, a dead branch copied into four controllers, a framework default that made a session |
| Configuration | 3 | a setting that did nothing, a service nobody scraped, a dependency only a removed one brought in |
| Documentation | 2 | two documents wrong since the initial commit |
| Environment | 3 | a setup script that crashes on Windows output, a container path Git Bash rewrote, a Docker Hub pull limit |

### The agent's own failures

Kept apart from the defects above: these are errors in what the agent wrote, caught before they
merged or soon after. The kinds were assigned by reading each entry, so a borderline one could sit
in a neighbouring row; the split between the two tables is firmer than the rows.

| Kind | Count | Examples |
| --- | --- | --- |
| Spec and plan premises wrong about the code, or work they missed | 155 | eight specs that could not be met, three database objects a spec missed, a provisional spec built on the wrong bus |
| Spec checks that could not fail, could not pass, or proved the wrong thing | 108 | a grep criterion that could never pass, a hold that was red before any change, a TTL check that accepted `ENABLING` |
| Code drafts that review or a break changed, and lost work | 79 | a shutdown join of 7 ms, a publish failure caught inside its transaction, three reviewer reverts that lost work, two edits an implementer reverted |
| Tests a spec would break without naming, or behaviour it left untested | 32 | eleven tests a track would turn red that no spec named, protected behaviours with no test behind them |
| Spec Verify commands that ran nothing, the wrong thing, or something unsafe | 21 | a Verify that would have deleted the maintainer's database, four that ran no tests |
| Bugs in the migration's own tooling | 17 | six dashboard misreads of tracks and tests, a dashboard measure that credited the spec with gap it did not close, a wrong Jira key |

From Day 21 each day's Notes tag every defect with where it was found and its cause
([`specs/_template.md`](../specs/_template.md)). Across Days 21–28 and 41, 97 tagged defects: cause
spec 58, implementation 20, process 16, environment 2, tooling 1; found by an auditor 29, in review
or during a track 42, by a break on purpose 8, at the close 9, by the session that made them 5, in
a spec-change PR 3, by the dashboard 1. Their 94 spec, implementation and process causes are the
agent's own.

## Retrospective

Written when the last phase closes, not before. For each hypothesis in
[Why this repository exists](../README.md#why-this-repository-exists) and each question in
[What is being measured](#what-is-being-measured): a verdict of supported, partly supported or
rejected, with the evidence. It includes any service that should not have been extracted.

| Hypothesis or question | Verdict | Evidence |
| --- | --- | --- |
| The four hard parts can be migrated by an agent under written specs | — | — |
| The binding constraint is specification quality, not model capability | — | — |
| Each row of the measurement table | — | — |
| Services that should not have been extracted | — | — |
