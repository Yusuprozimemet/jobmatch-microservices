# Defects found during the migration

Every defect the migration has filed, Days 1–42, grouped by kind. Until Day 27 these were kept as one
running list in the README's measurement table; this page holds it in full and the README keeps the
counts. The kinds were assigned by reading each entry's wording, so a borderline entry could sit in a
neighbouring kind; the totals per side (system versus the agent's own work) are firmer than the
totals per kind. "Days 1–13" are the entries filed before the list was kept day by day.

From Day 21 each day's Notes also tag every defect with where it was found and its cause
(`specs/_template.md`); those tags are the more precise record for the days that have them.

## In the system being migrated (19)

### CI and test harness (8)

- Days 1-13: 1 CI gate
- Days 1-13: 2 harness
- Days 1-13: 2 fixtures unlike production
- Days 1-13: 1 CI build that re-downloaded the world
- Day 38: 1 framework startup race that turned `main` red
- Day 20: 1 CI job that trusted another repository

### Production code (4)

- Days 1-13: 2 production
- Days 1-13: 1 dead branch copied into four controllers
- Day 14: 1 framework default that made a session

### Configuration (3)

- Days 1-13: 1 setting that did nothing
- Day 22: 1 service nobody scraped
- Day 23: 1 dependency only a removed one brought in

### Documentation (2)

- Day 16: 2 documents wrong since the initial commit

### Environment (2)

- Day 38: 1 setup script that crashes on Windows output
- Day 20: 1 container path Git Bash rewrote

## In the agent's own work (382)

### Spec and plan premises wrong about the code, or work they missed (141)

- Days 1-13: 8 specs that could not be met
- Days 1-13: 1 plan that missed a cross-module read
- Days 1-13: 1 plan that could not have run as written
- Days 1-13: 3 database objects a spec missed (a moved enum, a cascade, a revoke
- Days 1-13: 1 principal a spec would have changed by accident
- Days 1-13: 1 frontend regression a spec missed
- Day 14: 1 spec item written for code that never existed
- Day 14: 1 new table without its revoke
- Day 15: 1 token location no spec named
- Day 15: 3 access rules a spec missed
- Day 15: 1 track that did not exist
- Day 15: 1 rate limit that would have failed the suite
- Day 15: 1 CORS configuration that never existed
- Day 15: 2 framework defaults that broke what the spec asked for
- Day 16: 1 trusted header that would have let any client pick its rate-limit bucket
- Day 16: 1 deployment item with nothing in the repository
- Day 38: 1 security chain a spec missed
- Day 38: 1 hand-off only a spec knew
- Day 38: 1 harness rule that would have hidden the migrations
- Day 39: 1 track with no route to answer
- Day 39: 1 track too big for the gate
- Day 39: 1 first caller the outline missed
- Day 39: 1 map no environment variable can set
- Day 39: 1 spec claim a track made stale
- Day 39: 1 doc row a track left out
- Day 40: 1 job request a spec missed
- Day 40: 3 stale doc paragraphs
- Day 40: 1 overclaim
- Day 40: 1 hand-off missing what a container forces
- Day 40: 1 startup cycle a later day inherits
- Day 40: 1 fixture miscount
- Day 40: 1 stale Javadoc hand-off
- Day 40: 1 compose wait that stops at the gateway
- Day 18: 1 track an earlier day had built
- Day 18: 1 hand-off no day owned
- Day 18: 1 cap with no number that would have refused a long saved list
- Day 18: 3 later specs still written for the old order
- Day 18: 2 decisions no Phase 3 day owned
- Day 18: 1 fixture the database refuses
- Day 18: 1 hand-off left in one spec's Notes
- Day 19: 1 fallback that would have thrown in two routes
- Day 19: 1 retry rule that matched no call
- Day 19: 1 dependency the spec assumed
- Day 19: 1 request factory claim that did not reproduce
- Day 19: 1 breaker that would have counted a 4xx
- Day 19: 1 parent span that was Spring Security's
- Day 19: 1 URL read too early for a later day
- Day 19: 1 track over the gate as written
- Day 17: 1 spec that moved code out with nothing serving
- Day 17: 1 list a higher-priority source replaces whole
- Day 17: 1 public chain that hid a service's own key set
- Day 17: 1 test the spec missed that looked up a moved bean
- Day 20: 1 harness a spec claimed that did not exist
- Day 20: 1 pipeline a spec called untested that had 9 tests
- Day 20: 1 Airflow connection that is a Variable
- Day 20: 1 fixture the publish role could not run
- Day 20: 1 rollback grant a republish would lose
- Day 20: 1 restart that would recreate the database
- Day 20: 1 docstring with two false claims
- Day 20: 1 doc row stale since Day 17
- Day 41: 1 controller that would have called itself
- Day 41: 1 red the spec wrote as 200 that is 422
- Day 41: 1 track order that made the next track edit a test
- Day 41: 2 false test Javadocs
- Day 21: 1 spec that named nothing removing a module breaks
- Day 21: 1 timeout budget 4 s over the gateway's
- Day 21: 1 token hand-off only a Note carried
- Day 21: 1 harness gateway no track owned
- Day 21: 1 track that could not be one PR
- Day 21: 1 header the spec said is read that nothing reads
- Day 21: 1 harness rewrite that would have tested no model
- Day 21: 1 gateway key-set test no day owns
- Day 22: 1 spec built on a dual write the plan rules out
- Day 22: 1 interface the spec assumed
- Day 23: 1 doc stale since Day 21
- Day 24: 2 of them already true
- Day 24: 1 item both in and out of scope
- Day 24: 1 retry hand-off a spec missed
- Day 24: 1 phase tag never made
- Day 24: 1 time limit with no number
- Day 24: 1 transport resend a "no retry" Javadoc missed
- Day 26: 1 provisional spec built on the wrong order and the wrong bus
- Day 27: 1 relay test's messages a cached consumer would take
- Day 27: 1 track split the estimate missed
- Day 25: 1 spec with three tracks for an extraction Day 21 did in fifteen PRs
- Day 25: 1 approved edit that could not pass with the relay and consumer off
- Day 25: 1 estimate that counted none of six track splits
- Day 28: 1 spec step that would have deleted the Day 1–4 suite with `backend/`
- Day 28: 1 hold that was stale, `ServiceJwksIT` already green through the gateway
- Day 28: 1 build that depended on `backend/` without the spec saying
- Day 28: 1 estimate of 3 PRs for 232 files and three kinds of work
- Day 28: 1 compose line and five URL readers a spec missed
- Day 28: 1 database rename with no method or criterion, and a review list missing items
- Day 28: 1 criterion asking a service with no healthcheck to be healthy
- Day 28: 2 review lists missing items, five and then two
- Day 28: 1 cleanup day numbered so the dashboard would name the wrong next step
- Day 28: 1 review that named hand-offs outside `Hand-off` lines, about twenty phantoms
- Day 28: 1 flagged set of broken links no track took
- Day 42: 1 set of counts and line numbers written before they were run
- Day 42: 1 permit no item removed, under a criterion that needed it gone
- Day 42: 1 estimate of 7 PRs, then 9, for a day that took 11
- Day 42: 1 scope that left out six things the removals needed
- Day 42: 1 red count of three lines where there were two, and a list entry that was never there
- Day 42: 1 split of A2 that still left a half over the gate
- Day 32: 1 track A that could not meet C32.1 and C32.3 without C's root
- Day 32: 1 table name placed in `ScoreStoreConfig`, where `application.yaml` holds it
- Day 32: 1 rewrite that cited neither H42.3 nor the Day 20–26 items naming Day 32
- Day 32: 1 `.gitignore` for Terraform's working files that no track owned
- Day 32: 1 estimate the audit corrected
- Day 32: 1 plan by `init -backend=false`, which the override files cannot give
- Day 32: 1 scope of a seed hand-off that named one file of two
- Day 32: 1 estimate of 4 PRs for a day that took 6, one still `Oversized:`
- Day 33: 1 a draft still the Azure and Kubernetes one, which `plan.md` had ruled out
- Day 33: 1 RemoteStateReference claim, where the state is S3 and the root has no outputs
- Day 33: 1 draft that cited none of Day 32's code hand-offs (keys, Flyway, `db-setup.py`, H32.2)
- Day 33: 1 draft that cited none of Day 32's ECS hand-offs (task roles, the ALB's group, the redirect URI)
- Day 33: 1 draft that would have put secrets into Terraform's state
- Day 33: 1 estimate of 3 PRs for about 17 criteria
- Day 33: 1 ECR repository and push that no day owned
- Day 33: 1 estimate of 5 PRs for a day that took 7
- Day 33: 1 line number one off, and a broken `docker build` path the docs criterion missed

### Spec checks that could not fail, could not pass, or proved the wrong thing (101)

- Days 1-13: 1 spec check that could not fail
- Days 1-13: 1 gate that does not pin what its spec says
- Day 14: 1 grep criterion that could not print clean
- Day 14: 4 cookie properties no criterion checked
- Day 15: 2 checks a pass-through proxy passed
- Day 15: 1 hold a gateway would have silenced
- Day 16: 1 grep criterion that could never pass
- Day 16: 1 criterion that did not prove its goal
- Day 16: 1 check by hand that could not run on a fresh volume
- Day 38: 2 checks that could not fail
- Day 38: 1 hold that named a test that does not notice the break
- Day 38: 1 grep that printed 106 lines
- Day 39: 1 check that could not fail
- Day 39: 1 break that did not compile
- Day 39: 1 break not reachable as worded
- Day 39: 1 break that turned two cases red
- Day 40: 1 hold that was red before any change
- Day 40: 1 diff check a move broke
- Day 40: 1 route order that caught nothing
- Day 40: 1 metric check that passed without its request
- Day 40: 1 test that could not tell a dropped header from one never sent
- Day 18: 1 criterion that contradicted an earlier day's test
- Day 18: 2 criteria wrong as worded
- Day 18: 1 break a fixture could hide
- Day 18: 1 hold with two cases no break could reach
- Day 18: 1 break proved on the wrong command
- Day 19: 1 break a metric series would have survived
- Day 19: 1 self-test that could not fail
- Day 17: 1 break that could not fail
- Day 17: 1 criterion that said a map binds nothing
- Day 17: 1 grep criterion the ignored `target/` kept red
- Day 17: 1 query-count break that could not reach hydration
- Day 17: 1 monolith unit test a criterion asked for and no track wrote
- Day 20: 1 test that would have passed in either database
- Day 20: 1 break that needed an image rebuild to mean anything
- Day 20: 1 break that reports a 500 where the spec said a count
- Day 20: 1 probe check that could not tell never-ran from passed
- Day 41: 1 criterion that could pass on the wrong 422
- Day 41: 1 criterion with no red for its 404 cases
- Day 41: 1 hold that cannot see the existence call
- Day 41: 1 status check a 204 broke
- Day 41: 1 test that passed with no client
- Day 21: 1 break that failed at setup
- Day 21: 1 criterion no track broke
- Day 22: 1 compose service a check stopped that did not exist
- Day 22: 1 grep pinned at the wrong count
- Day 22: 1 TTL check that accepted ENABLING
- Day 22: 2 tests that never reached the store they broke
- Day 22: 2 startup tests that passed without the fix
- Day 23: 1 dependency break that passed without `clean`
- Day 23: 1 grep criterion that could not print clean
- Day 23: 1 grep a comment would have doubled
- Day 24: 1 503 that could not tell identity from an unseeded mart
- Day 24: 3 criteria with no `new`/`hold` tag
- Day 24: 2 decisions with no criterion or threshold
- Day 24: 1 check a criterion put in the wrong class
- Day 26: 1 rollback check that could not fail
- Day 27: 2 failure criteria that could not fail on the shared queues
- Day 27: 1 hold that named a test that did not exist
- Day 27: 1 consumer's failure paths with no criterion
- Day 27: 1 compose wiring with no check
- Day 25: 5 criteria without a command
- Day 25: 1 `savedCount` hold that could not pass once the table moved
- Day 25: 1 row count that cannot see an overwrite
- Day 28: 1 grep criterion that could never pass, Tempo's `backend: local`
- Day 28: 1 "through the gateway" hold CI's gateway run does not include
- Day 42: 1 grep that matched the lines that give other services their keys
- Day 42: 1 criterion red count that named the wrong line
- Day 42: 1 hold that named a test reading the wrong logs, and a Verify that would have kept text logs
- Day 42: 1 exception in two criteria that could not be told from its absence
- Day 42: 1 set of expected reports wrong, and greps that counted build output
- Day 42: 1 pathspec that matched no file, so two greps could not fail
- Day 42: 1 grep that still could not print nothing after the audit, met by a narrower one
- Day 42: 1 hold on JSON logs whose second test reads another service
- Day 32: 1 hold that named a test that does not exist (`UserDeletedFanOutIT`)
- Day 32: 1 workflow whose paths missed the code the checks read, so a code change could not fail it
- Day 32: 1 redrive comparison of `"5"` against `5`
- Day 32: 1 set of criteria with no break to see them fail
- Day 32: 1 queue-policy check on a plan where the policy is unknown, which could not pass
- Day 32: 1 expected report of no queues where the break left one
- Day 33: 1 set of six criteria with no `new` or `hold` tag
- Day 33: 1 set of criteria that could not run without a cluster
- Day 33: 1 login check that could not fail on a trust-auth Postgres
- Day 33: 1 grep that could not reach zero, since `../../mvnw` contains `/../mvnw`
- Day 33: 1 pair of holds that no PR broke until the close

### Code drafts that review or a break changed, and lost work (73)

- Day 38: 1 metric tag unlike the backend's
- Day 40: 1 default interval that made a wait slow
- Day 19: 1 stub one hang would have blocked
- Day 19: 1 client retry that turned a hang into a 404
- Day 17: 1 key file the container could not read
- Day 41: 1 test helper already there
- Day 41: 1 build run on another JVM's stale jar
- Day 41: 1 run on IDE-compiled classes
- Day 21: 1 draft that kept a read in-process a container cannot make
- Day 21: 1 span collector that records database URLs
- Day 21: 1 hang a stub never cleared
- Day 21: 1 test name the module never runs
- Day 21: 1 counter that was not atomic
- Day 21: 3 reviewer reverts that lost work
- Day 22: 1 table creation that shared the request timeout
- Day 22: 1 read filtered on the wrong attribute
- Day 22: 1 null attribute that would drop a batch
- Day 22: 1 test loosened over an id collision
- Day 22: 1 doc edit an implementer reverted
- Day 22: 1 test in the wrong package
- Day 23: 1 migration comment that said a role was going
- Day 24: 1 spec edit an implementer reverted
- Day 24: 1 trace test that would take another user's log line
- Day 26: 1 relay every cached context would run
- Day 26: 1 cleanup that deleted another test's message
- Day 26: 1 started guard set too early
- Day 26: 1 publish failure caught inside its transaction
- Day 26: 1 emulator ARN as the production default
- Day 26: 1 test profile in the jar
- Day 26: 1 break script that never started Maven
- Day 27: 1 Maven scope that would leave SQS out of the jar
- Day 27: 1 Verify line with no wrapper
- Day 27: 1 shutdown join of 7 ms
- Day 27: 1 version check that read "1" and 1.5 as 1
- Day 27: 1 DLQ check made at the wrong time
- Day 27: 1 eviction test an evict-everything bug would pass
- Day 25: 1 restored test with three of its checks dropped
- Day 25: 1 test helper that let the consumer-off break pass
- Day 25: 2 script bugs only a real database showed
- Day 25: 1 revoke that missed the role's own grant
- Day 25: 3 losses of unstaged work
- Day 25: 1 literal test password
- Day 25: 2 breaks run together that hid each other
- Day 28: 1 healthcheck start period shorter than the service's start
- Day 28: 1 draft that left three stale database-name comments
- Day 42: 1 startup test that read an environment variable null in every run
- Day 42: 1 grant test that checked the table and not the schema
- Day 42: 1 log test whose wait could pass without checking, and a dropped `Locale.ROOT`
- Day 42: 1 draft that repointed two working links to the wrong file
- Day 32: 1 state sweep that passed on every error, and a lock test that could never reach its prompt
- Day 32: 1 secret sweep that read `after_sensitive`, and a regex that stopped at the first brace
- Day 32: 1 implementer report of no departures, and of a fix that was not made
- Day 32: 1 subnet check by address that let the public subnets pass
- Day 32: 1 check message that printed the password it guarded
- Day 32: 1 repeated import guard and five copy-pasted regex blocks
- Day 32: 1 policy check that passed on a missing topic, and an alarm check that skipped alarms
- Day 32: 1 comparison of two LocalStacks never made against the source, which skipped missing keys
- Day 32: 1 file the main session left with mixed line endings
- Day 32: 1 local lock test fed through `docker run -i`, which released the lock early
- Day 33: 1 pin test that filtered PUBLIC out of the grants it pinned, rewritten
- Day 33: 1 key loader that changed the file messages it had to keep, rewritten
- Day 33: 1 unreported `baseline-on-migrate` that would have skipped V1, and a helper reading one row
- Day 33: 1 subclassed test that ran on the migrated database, reported as confirmed
- Day 33: 1 second unreported `baselineOnMigrate`, every auto-configuration loaded, and a check on its own connection
- Day 33: 1 implementer report of no departures, twice, over an ignored `--admin-password` and a match of `.*`
- Day 33: 1 JWKS assertion of the main session's that did not compile
- Day 33: 1 two breaks of the main session's that did not run, a syntax error and a compile error

### Tests a spec would break without naming, or behaviour it left untested (32)

- Days 1-13: 2 protected behaviours with no test behind them
- Days 1-13: 1 missing claim that would have made a test flaky
- Days 1-13: 1 migration that broke two harness tests by construction
- Day 14: 1 spec change that would have broken a contract test
- Day 14: 1 protected behaviour with no test behind it
- Day 38: 11 tests a track would turn red that no spec named
- Day 40: 1 gateway test a track would turn red
- Day 40: 1 test class misdescribed
- Day 17: 1 test property a base class silently wins
- Day 17: 1 test a move would turn red that no track owned
- Day 20: 1 "unedited" claim false for six test classes
- Day 20: 1 test case a move would turn red that no spec named
- Day 21: 1 existence test a move would have dropped
- Day 21: 1 test premise that the monolith serves what it no longer does
- Day 23: 2 test assertions a migration broke that the spec did not name
- Day 26: 2 harness tests a migration broke that the spec did not name
- Day 25: 1 grants test a revoke broke that no spec named
- Day 42: 1 track that would have copied four tests that already had twins
- Day 32: 1 harness copy of the bus left uncompared

### Spec Verify commands that ran nothing, the wrong thing, or something unsafe (20)

- Days 1-13: 3 spec verify commands that ran no tests
- Day 15: 1 verify that ran no tests
- Day 16: 1 verify that would have deleted the maintainer's database
- Day 38: 1 verify command with an empty variable
- Day 39: 1 verify whose curls checked nothing
- Day 40: 1 verify that ran in the wrong directory and overwrote its own reports
- Day 18: 1 verify path that did not exist
- Day 18: 1 verify missing four of CI's classes
- Day 19: 1 verify that stopped a service that did not exist
- Day 17: 1 Verify on the maintainer's own project
- Day 20: 1 service a Verify misnamed
- Day 24: 1 Verify that gave a 401 where it promised a 503
- Day 26: 1 shared network a Verify could remove
- Day 25: 1 Verify that could not fail
- Day 28: 1 Verify that could not pass on a fresh clone without the mart step
- Day 28: 1 close run that reused a three-hour-old test volume as fresh
- Day 32: 1 Verify that failed in Git Bash and held a placeholder tag
- Day 32: 1 compose check that `-p` would not have isolated from the maintainer's stack

### Bugs in the migration's own tooling (dashboard, commit keys) (15)

- Day 39: 1 dashboard that could not read a split track
- Day 17: 1 dashboard that could not read a three-way split
- Day 20: 1 dashboard that could not read tracks 0a and 0b
- Day 21: 1 track the dashboard counted merged half-done
- Day 21: 1 dashboard that lost a moved test
- Day 22: 1 branch name the dashboard read as a whole track
- Day 27: 1 wrong Jira key on a commit
- Day 27: 1 dashboard measure that credited the spec with gap it did not close, #107
- Day 25: 1 track announced and never opened, which the dashboard read as done
- Day 28: 1 track branch the dashboard could not read, so it named a merged track as next
- Day 42: 1 track with no Jira sub-task and no key in its branches
- Day 32: 1 track with no Jira sub-task, and a plan-change issue left open after its merge
- Day 32: 1 dashboard next step that read a criterion id as a track
- Day 33: 1 dashboard that read the plan change as the day's spec change and printed the old draft's track
- Day 33: 1 dashboard next step that read tracks A1 and B1 as all of A and B, twice
