# Day 24 — matching-service stands alone

**Phase:** 4 · **Depends on:** Day 23 · **Expected PRs:** 4 (Track 0, Track A, the spec change,
the close; 5 if the measurement asks for Track B)

The last of Phase 4's days (41, 21, 22, 23, 24). Day 41 took the profile endpoint, its client,
the 503 fallback, the user id and the existence call; Day 21 moved the code; Days 22 and 23 took
the scores out of Postgres. What is left is to show that matching-service stands alone over the
network, and to make the two decisions earlier days left here: a profile cache, and retry.

## Goal
With every hop a network hop, top-matches fails fast and clearly when identity is gone, one
trace shows all four hops, and the cache and retry questions are decided on measured numbers.

## In scope
- Tests for what is true today but unchecked: the existence call hanging or refused, the
  existence answer not cached, and the four-hop trace (Track 0).
- A measurement in compose of what each internal call costs inside top-matches, and two
  decisions recorded from it (Track A):
  - **Profile cache** (Day 41's hand-off): a short-lived cache of profile skills keyed by user,
    only if the measurement asks for it. The existence answer is never cached
    (`InternalUserController`: a cached answer lets a deleted user in for as long).
  - **Retry**, for all three of matching's clients: `UserExistenceClient` and
    `ProfileDirectoryClient` (Day 41: none, "Day 24 measures whether that still holds across
    the network") and `PostingShortlistClient` (Day 19 left the decision to Day 24,
    `day-19-http-clients.md:261`; it has no note today).
- `Migrations.java:22-23` still says matching's migrations are applied by matching-service; Day 23
  removed its Flyway. Track A corrects the comment.
- The profile cache itself, if the measurement asks for it (Track B).
- **End of Phase 4:** the `phase-4` tag (`specs/README.md`, "Stopping points").

## Out of scope
- Caching postings. Measure first; this day measures the shortlist call, but only the profile
  cache is decided here.
- The profile endpoint and client, their fallback, and the user id — Day 41, done.
- Removing "shared persistence code" from matching-service: none is left. Its pom has no
  `nl.hackyourfuture` dependency; its 8 `backend.shared.*` files are Day 21's source copies
  (none persistence code), and the image builds from its own folder. Criterion 1 holds the
  datasource side.
- The `matching` Postgres role and schema, `MATCHING_DB_PASSWORD` and the grants to
  `matching_user` in V12–V15: Day 23 found them and no day owns them; V14 raises without the
  role, so it cannot simply be dropped. Handed to the Day 28 stopping-point review (`plan.md`),
  which lists what has piled up that no day owns.
- The missing `phase-3` tag (Day 20 closed without one): the maintainer's call, recorded in
  Notes, not a criterion of this day.

## Tracks

| Track | Owner | Work |
|---|---|---|
| 0 | | Existence-call failure tests, the no-cache test, the four-hop trace test |
| A | | Measure in compose; record the cache and retry decisions; the three clients' Javadocs; the `Migrations.java` comment |
| B | | The profile cache — only if Track A's measurement asks for it |

Track 0 lands first, so the clients are covered before Track A or B touches them.

## Acceptance criteria
- [x] **hold** (Day 23's, by its Track B) — `matching-service` has no datasource and no Flyway
      configuration: Day 23's `git grep` over `services/matching-service` finds nothing.
      Broken on purpose: Day 23's break (a `spring.datasource.url` line).
      Held through #251–#253; checked at the close on 4a99e3f: Day 23's grep finds nothing (exit 1).
      Seen red at the close: `spring.datasource.url: jdbc:postgresql://db/x` appended to
      `application.yaml`, the grep printed that line (`application.yaml:125`, exit 0). Reverted.
- [x] **hold** — With identity unreachable or hanging, top-matches is a 503 with the detail
      "Your account could not be checked" within 3 s (1 s connect + 2 s read,
      `application.yaml`), never a hang and never a 200. Today
      `ProfileDirectoryUnavailableTest.anUnreachableIdentityIsA503` stubs a 503 answer;
      Track 0 adds the existence call refused (connection closed) and hanging past the read
      timeout. Broken on purpose: `UserExistenceClient` treats an exception as "exists"; the
      new tests must report a 200 or a 422.
      #251: `ProfileDirectoryUnavailableTest.aRefusedIdentityIsA503` (the stub closes the
      connection, `StubUpstream.drop()`) and `aHangingIdentityIsA503WithinTheTimeouts` (answers in
      under 3 s). Seen red: the client's three fallbacks returning `true`, both new tests
      `expected: 503 but was: 200`; the existing `anUnreachableIdentityIsA503` gave 422.
- [x] **hold** — The existence answer is not cached: two top-matches requests for one user make
      two existence calls (Track 0, beside `aUserIdentityDoesNotKnowIsA422`, which asserts one
      per request). Broken on purpose: a one-entry cache in `UserExistenceClient`.
      #251: `theExistenceAnswerIsNotCached`. Seen red: a one-entry `lastKnown` cache,
      `expected: 2 but was: 1`. Still true with #253's profile cache in front of the profile call:
      `ProfileCacheTest` counts existence calls 1, 2, 3 over its three requests.
- [x] **hold** — The 422 too-few-skills behaviour is unchanged:
      `MatchTopMatchesIT.refusesAProfileWithFewerThanFiveSkills`, unedited since Day 06, green
      against the matching-service container. Broken on purpose: the 5-skill check removed
      from `JobMatchController`, the harness image rebuilt (a stale image passes).
      Held through #251–#253: `git log` on `MatchTopMatchesIT.java` ends at 9b325e6 (Day 06), 8 of 8
      green at the close. The check is in `JobMatchService.getTopMatches`, not the controller (see
      Notes). Seen red at the close: `skills.size() < 0` in its place, the matching-service image
      rebuilt, `expected: 422 but was: 200`; reverted, image rebuilt again.
- [x] **hold** (re-tagged at the close; written `new`) — One top-matches request through the gateway is one trace with spans from the
      gateway, matching-service, identity (the existence and profile calls) and job-service (the
      shortlist), asserted by a test. Red today: `TopMatchesTracedIT` asserts only the model's
      `traceparent` and job-service's log line, and the client supplies the trace id, so a
      gateway that only forwards the header passes. If the new assertion is green at once,
      it is re-tagged **hold** in the close and broken on purpose: `UserExistenceClient` built
      without the observed builder.
      #251: `TopMatchesTracedIT.oneRequestIsOneTraceAcrossEveryHop` sends no `traceparent`, reads
      the trace id from identity's existence line, and finds it in identity's profile line,
      matching-service's and job-service's request lines and, with `-Dharness.gateway=true`, the
      gateway's. Green at once, so re-tagged **hold**. Seen red: the existence call built with
      `ObservationRegistry.NOOP` and the image rebuilt, identity's existence line under a trace of
      its own (`ad3b5ca9…`, the profile line under `a447ab1e…`).
- [x] **new** — Track A's measurement is in the Notes: N top-matches requests in compose (N ≥ 50,
      the model stubbed or the scores already cached, so its seconds do not swamp the rest),
      with `http_client_requests_seconds` sum/count and error outcomes per internal route (existence, profile, shortlist) against
      `http_server_requests_seconds` for top-matches, as Prometheus scrapes them. Red today:
      no Notes. **The cache rule, fixed before measuring:** the profile cache is added
      (Track B) only if the profile call's mean is more than 10% of top-matches' mean server
      time; otherwise not, and the Notes say so.
      #252: two runs in compose, N = 60 and N = 100, in the Notes below. The profile call is 16.4%
      and 16.3% of top-matches' mean server time, so the rule says Track B runs. Seen red: before
      #252 the Notes had no measurement.
- [x] **new** — The retry decision is recorded per client, from the measured error outcomes: a
      retry is added only for a client that showed a transient error other than a read
      timeout. Each of the three clients' Javadoc states its decision and Day 24's numbers:
      `git grep -n "Day 24 measures" services/matching-service` finds nothing, and
      `PostingShortlistClient`'s Javadoc names a retry decision. Red today: the grep finds 2
      (`UserExistenceClient.java:31`, `ProfileDirectoryClient.java:33`), and the shortlist client
      has no note.
      #252: no retry for any of the three, no error of any kind on 160 calls per route; each
      Javadoc says so with its means. `git grep -n "Day 24 measures" services/matching-service`
      finds nothing (exit 1) on 4a99e3f; `PostingShortlistClient.java:33` names "No retry".
      Seen red: the phrase put back into `UserExistenceClient`, the grep printed line 31. The close adds
      to the two GET clients' Javadocs that the JDK client resends a GET on a dropped connection
      (see Notes).
- [x] **new** — `git ls-remote --tags origin phase-4` prints one line, the tag pointing at the
      last track's merge commit. The tag is not in a PR: the maintainer tags `main` after the
      last track merges (as `phase-2` on Day 16). Red today: `ls-remote` prints only `phase-2`
      and `phase-2.1`.
      Annotated, pushed at the maintainer's word after #253: `ls-remote` prints
      `4c567aa… refs/tags/phase-4`, the tag object, and `phase-4^{commit}` is 4a99e3f, #253's merge.
      Seen red before the push: `ls-remote` printed `phase-2` and `phase-2.1` only.
- [x] **new**, only if Track B runs — profile skills are cached for seconds, keyed by user: a test
      shows a second request within the window makes no profile call and one after it does, and
      the existence call is still made both times. Broken on purpose: the window set to zero.
      #253: `ProfileCacheTest.aSecondRequestWithinTheWindowDoesNotAskForTheProfile` (profile calls
      1, 1, 2 across the window; existence calls 1, 2, 3) and
      `aProfileWithTooFewSkillsIsAskedForAgain`. Seen red: the window at `0s`,
      `expected: 1 but was: 2` profile calls; `put` before the five-skill check,
      `expected: 200 but was: 422`.

## Verify
```bash
# In a separate compose project, never the maintainer's (CLAUDE.md, Pitfalls).
docker compose -p day24 --env-file .env.example up -d --build
curl -s -c jar.txt -H 'Content-Type: application/json' localhost:8080/api/auth/register \
  -d '{"name":"V","email":"v@example.com","password":"secret1","acceptedTerms":true}'
curl -s -c jar.txt -H 'Content-Type: application/json' localhost:8080/api/auth/login \
  -d '{"email":"v@example.com","password":"secret1"}'
docker compose -p day24 stop backend
# Within ~5 minutes: the gateway's and matching's key sets also come from backend, and once
# their caches expire the answer is a 401 instead. The existence call is the first internal
# call, so the detail names identity whether or not the mart is seeded.
curl -s -b jar.txt -w '\n%{http_code} %{time_total}s\n' localhost:8080/api/jobs/top-matches
# 503 "Your account could not be checked ..." in under 3 s
docker compose -p day24 down -v
```

Without the cookie the answer is a 401 (the gateway requires a login on this path), and a 503
alone does not show identity is down: on a fresh compose with the mart unseeded, top-matches is
already a 503 ("The postings could not be reached") with backend up. The detail is the check.

## Notes
- **End of Phase 4.** Two services are fully independent, and the highest-latency code path is
  isolated from everything else. After the close, the plan-auditor runs on Phase 4 and Phase 5
  (`CLAUDE.md`, step 6).
- **Rewritten on Day 24** from the spec-auditor's 10 findings, before any track. Fixed: the
  Verify block gave a 401, not a 503 (no login; the key sets also come from backend), and a 503
  alone could not tell identity from an unseeded mart; three criteria had no `new`/`hold` tag,
  and two of them were already true; Track A's measurement and retry decision had no criterion
  or threshold; "retry" was both out of scope (Day 41) and Track A's work; Day 19's retry
  hand-off for the shortlist client was missing; "removes every remaining dependency on `shared`
  persistence code" was already true; the phase tag was missing; "promptly" had no number.
- **Track A's measurement.** A separate compose project (`-p day24m --profile obs`), no
  `LLM_API_KEY`, so top-matches ranks by skill overlap and the model is out of the numbers. The
  mart seeded from the harness fixtures (`analytics-schema.sql`, `analytics-seed.sql`) as
  `analytics_user`; one user, five skills, Amsterdam. Five warm-up requests, then the
  differences of matching-service's counters in Prometheus over N requests through the gateway:

  | Run | N | existence | profile | shortlist | top-matches (server) | profile / top-matches |
  |---|---|---|---|---|---|---|
  | 1 | 60 | 7.5 ms | 7.2 ms | 10.5 ms | 43.9 ms | 16.4% |
  | 2 | 100 | 5.7 ms | 5.7 ms | 8.2 ms | 34.9 ms | 16.3% |

  Means, from `http_client_requests_seconds` sum/count per route and `http_server_requests_seconds`
  for `/api/jobs/top-matches`. Every outcome `SUCCESS` (204 existence, 200 the others), 160 of
  160 on each route; all 160 requests 200 at the client.
- **The cache decision: Track B runs.** The profile call is 16% of top-matches' server time,
  above the 10% the rule set before measuring. The rule measures without the model, as the
  criterion asks; with the model on, top-matches also waits seconds for it (not measured here),
  so what Track B saves is about 6 ms a request, and it adds a window in which a profile edit is
  not yet seen by matching. Recorded here so the maintainer can weigh it before Track B starts.
- **The retry decision: no retry, for all three clients.** No route showed an error of any kind,
  so none showed the transient error, other than a read timeout, that the rule asks for. Each
  client's Javadoc states it with these numbers.
- **Defect** — found: review · cause: process · Track A's implementer, briefed to touch
  only the four Java files, reverted this spec's uncommitted Notes and did not report it. Found
  by `git status` before committing; the Notes were rewritten.
- **Defect** — found: auditor · cause: spec · the `phase-3` tag was never made: Day 20's spec
  had no tag criterion, and `specs/README.md` says Days 16, 20, 24, 28, 31 and 37 are tagged.
  Whether to tag Day 20's close commit now is the maintainer's call.
- matching-service's source still sits in `nl.hackyourfuture.project.backend.*` packages,
  including `backend.shared` (Day 21's copies). It shares no code with the monolith; the names
  are a leftover, not a dependency, and no day owns renaming them.
- **Track order: 0 (#251), A (#252), B (#253),** as the table has them, after the spec change
  (#250). Track B ran because Track A's measurement asked for it; #252 put the ~6 ms saving and
  the stale-profile window to the maintainer first, and #253 started after #252 merged.
- **Defect** — found: close · cause: process · #251 found that a dropped connection reaches
  identity twice: the JDK `HttpClient` under `InternalClients` sends an idempotent GET again when
  the connection closes with no answer. It left that to Track A's retry decision, and #252's
  Javadocs said "No retry" without it. Fixed at the close: `UserExistenceClient` and
  `ProfileDirectoryClient` say the transport resends the GET; the shortlist call is a POST, which
  it does not. Only the existence call's resend was seen; the profile call's is the same client
  and method, not tested.
- **Defect** — found: close · cause: spec · criterion 4 puts the five-skill check in
  `JobMatchController`; it is in `JobMatchService.getTopMatches`. The break went there.
- **Two holds were broken only at the close:** criteria 1 and 4 belonged to no track (none
  changed the datasource configuration or the five-skill check), so the close broke both.
- **Defect** — found: review · cause: implementation · #251's implementer matched any
  `/internal/users/` log line for the trace id, not this user's, so another test's line could have
  supplied it; fixed in review with three smaller fixes (an empty `else`, an NPE on a line with no
  trace id, three copies of one stub).
- **Defect** — found: self · cause: process · in #251 a scripted edit run against CRLF files
  half-applied and doubled two comment lines; caught in the diff before any check ran.
- **Fixed at the close:** the two GET clients' Javadocs (the resend, above).
- **The close's Verify, on 4a99e3f plus the Javadocs:** the job-service and matching-service
  harness images rebuilt; `clean verify` 429 tests, 0 failures, 0 errors, 1 skipped, counted from
  the reports after deleting them; `checkstyle:check` 0 violations; matching-service `verify` 76,
  0 failures, `checkstyle:check` no violations. The spec's Verify in a project of its own
  (`day24`): register 201, login 200, top-matches 422 ("Fill in your profile") with backend up;
  backend stopped, top-matches `503` "Your account could not be checked; matching is temporarily
  unavailable" in 1.05 s. Then `down -v` on that project, which also removed the shared network
  `finalproject`, as on Days 22 and 23.
- **Found, not owned:**
  - The `phase-3` tag is still not made (Day 20's close); the maintainer's call.
  - The `matching` role and schema, `MATCHING_DB_PASSWORD` and the grants in V12–V15 stay, for
    the Day 28 review (Out of scope).
  - matching-service's `nl.hackyourfuture.project.backend.*` package names (above).
  - With Track B, an edit to a profile that already has five skills reaches matching's ranking
    up to 10 s late (`PROFILE_CACHE_WINDOW`). A profile with no record or fewer skills is never
    cached, so the 422 is never stale.
- **Earlier days' hand-offs to Day 24, all met:** the profile cache (Days 21, 22, 41: Track B),
  retry (Days 17, 19, 21: Track A, no retry for all three clients), the existence answer
  uncached (Days 21, 22: criterion 3), "no datasource" (Day 23: criterion 1), and service-token
  auth (Day 39): matching's three clients go through `InternalClients`, which attaches it (Day 19),
  and Track 0's tests pass through it.
- **The estimate:** 4 PRs expected, 5 if Track A's measurement asked for Track B; it did. Took 5:
  the spec change (#250), 3 track PRs (#251–#253) and this close. The tracks came to 483 changed
  lines (196, 45, 242). No `Oversized:` line.
- **Tests:** `contract/` unchanged; `support/` +13 (`Gateway`: one request line per request, and
  `logs()`). Outside both: `TopMatchesTracedIT` +83 −5. In matching-service,
  `ProfileDirectoryUnavailableTest` +58, `StubUpstream` +17 −4 (`drop()`), `StubUpstreamTest` +16,
  `ProfileCacheTest` +175 (new). Backend tests 428 → 429 (the four-hop trace); matching-service
  69 → 76.
- **Hand-offs this day leaves:**
  - **Phase 4 is closed** (`phase-4` on #253's merge). The plan-auditor runs on Phase 4 and Phase
    5 next (`CLAUDE.md`, step 6), before Day 25 starts.
  - **Day 32:** matching-service reads `PROFILE_CACHE_WINDOW` (default `10s` in
    `application.yaml`; compose does not set it), besides needing the DynamoDB table.
