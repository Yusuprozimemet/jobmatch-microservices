# Day 19 — The seams go over HTTP, inside one process

**Phase:** 3 · **Depends on:** Day 18 · **Expected PRs:** 6

The second of Phase 3's seam-first days (18, 19, 17, 20). Rewritten on Day 19 from the provisional
draft, against `main` at 54283b8, after the spec-auditor's two reads, with the maintainer's four
choices below.

## Goal
Saved jobs, top matches and job search reach `PostingLookup`, `PostingShortlist` and
`SavedJobCounts` over HTTP: through clients that call the monolith's own `/internal/**` routes
(Day 18) with a service token, a timeout and a circuit breaker, and fall back as declared when the
call fails. The in-process implementations still answer behind those routes. So on Day 17 moving
`jobs` out is a change of URL, and the replacement is already serving.

## In scope
- **The maintainer's choices, made on Day 19:**
  1. **Each client lives with its caller** and is `@Primary`: the `PostingLookup` client in
     `applications` (saved jobs), the `PostingShortlist` client in `matching` (top matches), the
     `SavedJobCounts` client in `jobs` (job search). Each leaves with its caller: Day 17 keeps the
     counts client in job-service, Day 21 reuses the shortlist client, Day 25 the lookup client.
     Not in `app`, which no feature module may depend on (`ModuleBoundariesTest`,
     `nobodyDependsOnTheAssembly`).
  2. **Where a client sends its requests is a property, empty meaning this process.**
     `app.internal.jobs-url` (batch and shortlist) and `app.internal.applications-url` (saved
     counts). The client reads the property from `Environment` on its first call, never injects
     it; empty, the default, then resolves to `http://localhost:${local.server.port}`. The port is
     known only once the server has started (`IntegrationTest` uses `RANDOM_PORT`), and Day 17's
     harness sets the property from a container started after the context, so a URL read when the
     bean is made cannot work. Day 17
     sets `app.internal.jobs-url` to job-service. This is the startup cycle Day 17's Notes carried,
     solved before it arrives.
  3. **No retry; a fallback only for an outage.** Connect timeout 1 s, read 2 s, which leaves
     top-matches room under the gateway's 30 s read after the LLM's 5 + 20 s. No retry today: every
     call goes back into the same process, and all three routes are POST. A fallback runs for a
     connection or timeout error (`ResourceAccessException`), a 5xx (`HttpServerErrorException`)
     and an open breaker (`CallNotPermittedException`), and nothing else: a 4xx is a bug here (a
     missing token, a batch over the cap), so it is not counted by the breaker and surfaces as the
     caller's 500, not as a quiet 0.
  4. **Resilience4j 2.4.0's `resilience4j-spring-boot4`**, with `@CircuitBreaker` and fallback
     methods typed to the three exceptions above; needs `spring-boot-starter-aspectj`, whose
     version Boot's parent already manages. Resilience4j's version is pinned once in the parent's
     `dependencyManagement`. Built against Boot 4.0; the
     spec-auditor ran it on Boot 4.1 and Java 25, and the breaker opened and half-opened as
     configured.
- **One place builds an internal client:** a class in `shared.internal`, next to `ServiceToken`.
  - From the `RestClient.Builder` Spring injects (Day 38's rule, `restClientsComeFromSpring`),
    `clone()`d per client: the builder is one bean, and each client has its own base URL.
  - **`JdkClientHttpRequestFactory`**, with `HttpClient`'s connect timeout 1 s and a read timeout of
    2 s. Not `MatchScorer`'s `SimpleClientHttpRequestFactory`: with it a read timeout surfaces as a
    `RestClientException` while extracting the body, not a `ResourceAccessException`, so a hanging
    upstream gets no fallback and the caller a 500 (the spec-auditor's scratch run; the JDK factory
    fell back at 2003 ms).
  - The `RestClient` is built on the first call, when the base URL can be resolved (above), and
    published safely: two first calls can arrive at once.
  - `Authorization: Bearer <ServiceToken.mint()>` on every request (Day 39).
- **The circuit breakers**, one per client, named `postingLookup`, `postingShortlist` and
  `savedJobCounts`, in `application.yaml`: count-based window of 10, at least 5 calls, open at 50 %
  failures, 10 s open, 2 calls half-open. `record-exceptions` names `ResourceAccessException` and
  `HttpServerErrorException`, and `ignore-exceptions` names `HttpClientErrorException`: without the
  first a 4xx counts as a failure, and without the second as a success that dilutes the rate (both
  seen in the scratch run). Tests shorten the open wait for themselves.
- **`PostingLookup` client** (Track A2). Splits the distinct ids into chunks of 500, the batch
  route's cap (Day 18), and asks for each in turn; a user's whole saved list is one `byIds` call
  (`SavedJobService`), and nothing limits its length. An empty collection gets `{}` without a
  call. Fallback: `{}` for the whole call, even if one chunk succeeded, so saved jobs list with
  empty details, Day 04's vanished-posting shape (`SavedJobService`, `SavedJobHydrationIT`). In
  that state the list is not newest first (the sort key is the posting's date), and a user cannot
  tell an outage from postings that left the mart; the answer is still a 200.
- **`PostingShortlist` client** (Track B). Fallback: a 503 with a ProblemDetail saying matching is
  unavailable. Never `[]`: `MatchTopMatchesIT.returnsAnEmptyListWhenNothingIsShortlisted` treats
  that as "no matches". Today a failure there is a 500 (`GlobalExceptionHandler` has no catch-all);
  the frontend shows a 503 as its generic error (`MatchesContent.tsx`).
- **`SavedJobCounts` client** (Track C). Fallback: every requested id mapped to 0, as the interface
  promises one entry per id. Search and job detail both use it: `JobService` passes
  `counts.get(id)` to `withSavedCount(int)`, so a missing entry is a `NullPointerException`.
- **The internal controllers take the in-process class, not the interface:**
  `InternalPostingController` takes `JobsDirectory` and `InternalSavedCountsController` takes
  `ApplicationsDirectory` (same package, both package-private). Otherwise, once the clients are
  `@Primary`, a route calls the client that calls the route.
- **The in-process tests take the in-process bean:** `internal/PostingBatchIT`,
  `internal/PostingShortlistIT`, `internal/SavedCountsIT` and `postings/ShortlistOrderIT` get
  `@Qualifier("jobsDirectory")` or `@Qualifier("applicationsDirectory")`, as the module JDBC beans
  are chosen. Otherwise Day 18's equality checks compare the client with itself, and
  `ShortlistOrderIT` stops pinning the order in process. None of them is in `contract/`.
- **A stub upstream in `support/`** that can answer, refuse with a status, or hang past the read
  timeout, and counts the calls it gets: the failure tests point the clients' URLs at it with
  `@DynamicPropertySource`. It runs its handlers on an executor of their own (virtual threads),
  unlike `StubLlm` and the other stubs, whose single default thread a hang would block for every
  later request. One failure test class per client at most: a class with its own
  properties starts its own context, four module pools each.
- **Docs:** `backend/docs/configuration.md` §3 gets the two URL properties and what empty means;
  `backend/docs/api.md` §7 the 503 on top-matches, §8 the degraded saved-jobs answer, §6 that
  `savedCount` reads 0 while the counts are unavailable.
- **Dependencies:** `spring-boot-starter-restclient` in `jobs` and `applications` (only `matching`
  has it), and Resilience4j and the AspectJ starter in all three.

## Out of scope
- The job-service container, pointing `app.internal.jobs-url` at it, and failure tests across
  containers: Day 17.
- Retrying: Day 24 decides it for its own client, which crosses a network the Day 19 ones do not.
- Caching: no day owns it. The client metric below measures the calls first.
- Frontend copy for the 503: no day owns it.
- Separate databases: Day 20.

## Tracks

| Track | Owner | Work |
|---|---|---|
| 0 | | Controllers and in-process tests take the in-process bean; a 501-job saved list with details; the stub upstream |
| A1 | | `shared.internal` client builder, Resilience4j wiring and dependencies |
| A2 | | The `PostingLookup` client in `applications` |
| B | | The `PostingShortlist` client in `matching` |
| C | | The `SavedJobCounts` client in `jobs` |
| D | | The breaker opens and recovers; every call observed |

Track 0 first, then A, which the others build on. B and C in either order. D last. A is likely
over 400 lines, so it splits: the builder and the Resilience4j wiring first (A1), then the lookup
client (A2). Six PRs.

## Acceptance criteria
- [x] **hold** — The Day 1–4 suite passes unedited, direct and through the gateway (CI's class
      list), with the clients serving from Track A2 on. Green today (366 direct, 206 through the
      gateway on 54283b8). Broken on purpose in Track A2's PR: the client's fallback made
      unconditional (`{}` for every call). The spec-auditor's scratch `@Primary` lookup returning
      `{}` turned 8 of `SavedJobHydrationIT`'s 11 red.
      #157: the fallback made unconditional turned 8 of 11 `SavedJobHydrationIT` red, e.g.
      `expected: "Backend Developer" but was: ""`. At the close, on `main` at ebc1061: 390 direct,
      0 failures, 1 skipped (`GatewayHarnessIT`); 206 through the gateway; `contract/` unchanged
      since this spec change (`git diff 2fddeaf HEAD` printed nothing).
- [x] **hold** — A saved list longer than the batch cap still lists with details: a user with 501
      saved postings gets all 501 from `GET /api/saved-jobs` (six pages of at most 100), each with
      its title. Track 0 adds it as `internal/LongSavedListIT`, not in `contract/`, which the
      Verify's diff keeps unchanged; green today, when `byIds` has no cap. Broken
      on purpose in Track A2's PR: the chunking removed, so the route answers 400, which is not an
      outage and surfaces as a 500.
      #155: `internal/LongSavedListIT`, its 501 postings one builder posting copied in a
      statement (501 builder calls took 53 s). Red on #155 with `byIds` answering only the first
      500 (`expected: "Long 500"`), and on #157 with the chunking removed (`expected: 200 but was:
      500`).
- [x] **hold** — The statement counts do not move: `SavedJobHydrationQueriesIT` (one statement on
      `fct_postings` for a whole list, none for an empty one) and `JobSavedCountQueriesIT` (one on
      `saved_jobs` per page) pass unedited. The route runs the same in-process implementation, and
      `StatementCounter` counts per database, whichever process sends the statement. Broken on
      purpose in Track A2's PR: the chunk size set to 1 (one statement per saved posting).
      #157: chunk size 1 turned `readsTheMartOncePerPageWhateverItsSize` red, `expected: 1L but
      was: 6L`, 3 of 4 (the empty list rightly stays at 0). Both classes unedited, green at the
      close.
- [x] **hold** — Every `RestClient` comes from Spring's builder:
      `ModuleBoundariesTest.restClientsComeFromSpring` passes. Broken on purpose in Track A1's PR:
      `RestClient.builder()` in the `shared.internal` builder.
      #156: red as named, an architecture violation from `restClientsComeFromSpring`.
- [x] **hold** — The routes still answer from the in-process implementations: Day 18's
      `PostingBatchIT`, `PostingShortlistIT`, `SavedCountsIT` and `ShortlistOrderIT` pass, with
      `JobsDirectory.java` and `ApplicationsDirectory.java` unchanged over the day. Track 0 gives
      the controllers and the tests the in-process bean; green today. Broken on purpose in Track
      A2's PR: `InternalPostingController` back on the `PostingLookup` interface, so the route calls
      the client that calls the route, run with `-Dtest=PostingBatchIT` alone (the breaker it opens
      stays open for 10 s, for every class sharing the context). In the spec-auditor's scratch run
      the route was hit about 700 times in 2 s, the breaker opened on the first timeouts, and the
      outer call fell back to `{}`: `answersWhatTheLookupAnswersInProcess…` red with two entries
      expected.
      #155 gave the controllers `JobsDirectory` and `ApplicationsDirectory` and the four tests their
      qualifiers. #157: the break as named, `PostingBatchIT` alone, `expected: 2 but was: 0`.
- [x] **new** — Saved jobs survive the postings being unavailable: with `app.internal.jobs-url` at
      the stub refusing with 503, `GET /api/saved-jobs` answers 200 with every saved posting
      listed, its state kept and its details empty; with the stub answering 400, it answers 500.
      Red today: the property does nothing, so the details are present. Track A2.
      #157: `internal/PostingLookupUnavailableIT`, 503 and a hang (2.3 s) give empty details, a 400
      a 500. Red without the client bean, as on `main`: 3 of 3.
- [x] **new** — Top matches fail fast and say so: with `app.internal.jobs-url` at the stub hanging,
      `GET /api/jobs/top-matches` answers 503 with a ProblemDetail, in under 3 s. Red today: 200
      with matches. Track B.
      #158: `internal/PostingShortlistUnavailableIT`, a hang a 503 in 2.3 s with the `detail`
      saying the postings could not be reached, rendered as the route's 422 is; an upstream 503 a
      503; a 400 a 500. Red without the client bean: 3 of 3 (`expected: 503 but was: 200`); with
      the fallback returning `[]`, both outage cases 200; with the client failing every call,
      `MatchTopMatchesIT` and `MatchRankingIT` 13 of 16.
- [x] **new** — Job search survives the counts being unavailable: with
      `app.internal.applications-url` at the stub refusing with 503, `GET /api/jobs` and
      `GET /api/jobs/{postingId}` answer 200 with `savedCount` 0 on a posting one user saved. Red
      today: 1. Track C.
      #159: `internal/SavedJobCountsUnavailableIT`, search found by title and detail both 0; a hang
      0 in 2.2 s; a 400 a 500. Red without the client bean (`expected: 0 but was: 1`); with the
      fallback returning an empty map, `expected: 200 but was: 500`, the `NullPointerException`
      named above; with the client answering 0 always, `JobSavedCountIT` 5 of 6.
- [x] **new** — The breaker opens, recovers by itself, and ignores a 4xx. Driven through
      `GET /api/jobs` and the counts client, the breakers reset between tests:
      - with the stub refusing with 503, after 5 calls the stub's count stops rising (the scratch
        run: 1, 2, 3, 4, 5, then 5, 5, 5) and the callers get the fallback at once;
      - after the open wait (shortened by the test), with the stub answering, the next two calls
        reach it and the breaker's state, read from `CircuitBreakerRegistry`, is `CLOSED`;
      - with the stub answering 400, six calls all reach it and the breaker stays `CLOSED` with no
        call recorded.

      Red today: no breaker. Track D. Broken on purpose there: the `ignore-exceptions` rule
      removed, and the 400 case fails.
      #160: `internal/CircuitBreakerIT`, 3 tests, the sequence 1, 2, 3, 4, 5, 5, 5, 5 as the
      scratch run had it. Driven through `GET /api/jobs/{postingId}`, not `/api/jobs` as written:
      job detail asks for one id, which the stub can answer, where a search page's ids would all
      need to be in its answer. Red as named: `itIgnoresA4xx` `expected: 0 but was: 6`, the 400s
      recorded as successes. Red too with `@CircuitBreaker` removed from the counts client
      (`expected: 200 but was: 500`).
- [x] **new** — The clients serve, and every call is observed: `GET /api/saved-jobs`,
      `GET /api/jobs/top-matches` and `GET /api/jobs` each answer 200; the monolith's
      `/actuator/prometheus` then has `http_client_requests_seconds_count` with `status="200"` and
      `uri` `/internal/postings/batch`, `/internal/postings/shortlist` and
      `/internal/saved-counts`; and each client span is in its `/api` request's trace, with that
      request's server span as an ancestor (its direct parent is Spring Security's
      `secured request` span). Reuses `LlmCallObservedIT.Spans` to avoid another context. Red
      today: no such series. Track D. Broken on purpose there: the `Authorization` header dropped,
      so the route answers 401, a 4xx that surfaces as a 500 (the series alone would stay: a 401 is
      recorded too, with `status="401"`).
      #160: `matching/InternalCallsObservedIT`, in `matching` to reach `LlmCallObservedIT`'s
      package-private span collector, and sharing its context. The server ancestor is named: `http
      get /api/saved-jobs` for the batch call. Red as named: both tests `expected: 200 but was:
      500`; and with the route-to-caller map swapped, `"http get /api/saved-jobs"` does not end
      with `/api/jobs`.

## Verify
```bash
# Backend direct, then the gateway, then the backend through the gateway (CI's class list).
# Read each run's reports before the next: the gateway run overwrites them.
(cd backend && rm -rf */target/surefire-reports && ./mvnw -B clean verify && ./mvnw -B checkstyle:check)
backend/mvnw -B -f services/api-gateway/pom.xml clean verify checkstyle:check
docker build -t jobmatch-api-gateway:harness services/api-gateway
(cd backend && rm -rf */target/surefire-reports && ./mvnw -B verify -pl app -am -Dharness.gateway=true \
  -Dtest='nl.hackyourfuture.project.backend.contract.*IT,StaleCookieIT,RefreshIT,GatewayHarnessIT,ServiceRoutingIT,ObservabilityIT' \
  -Dsurefire.failIfNoSpecifiedTests=false)

# The in-process implementations unchanged. <base> is this spec change's merge. Prints nothing.
git diff <base> HEAD -- backend/jobs/src/main/java/nl/hackyourfuture/project/backend/jobs/JobsDirectory.java \
  backend/applications/src/main/java/nl/hackyourfuture/project/backend/applications/ApplicationsDirectory.java \
  backend/app/src/test/java/nl/hackyourfuture/project/backend/contract
```
No compose check: a fresh compose volume has no mart, so `/api/jobs` answers 500 before any change
(the spec-auditor's run), and the failure cases are harness tests against the stub.

## Notes
- **Rewritten on Day 19** from the provisional draft. What the spec-auditor's first read found
  wrong with it:
  - Its failure criteria and Verify stopped a `job-service` that does not exist until Day 17. Run
    as written in `-p day19check`: `stop job-service` checked nothing, `start job-service` failed,
    top-matches answered 401 (no login) where the spec expected 503, and `/api/jobs` answered 500
    before anything was stopped, because a fresh volume has no mart.
  - It never said where the clients live, how they are chosen over the in-process beans, or where
    they send requests. A second `PostingLookup` bean without `@Primary` fails the context; with
    it, the internal controller takes the client too.
  - The counts fallback as worded (search only, "0") would have thrown a
    `NullPointerException` on a partial map, and missed job detail.
  - "Bounded retry on idempotent GETs only" matched nothing: all three routes are POST.
  - No timeouts, no breaker numbers, no metric or test named; no criterion tagged; Resilience4j
    not a dependency.
- **For Day 17**, from today: its startup cycle is solved here (the URL is resolved on the first
  call, the port known by then). It sets `app.internal.jobs-url` to job-service in compose and the
  harness; `app.internal.applications-url` stays empty in the monolith and is set in job-service to
  the monolith. Job-service also needs what the monolith gives the counts client today: a component
  scan of `shared.internal`, a `ServiceToken` implementation of its own, a `RestClient.Builder`
  (the restclient starter, which `jobs` gets today), and the `savedJobCounts` breaker's settings in
  its own `application.yaml`.
- **For Day 24:** no retry on Day 19, for calls that never leave the process. Its client crosses a
  network, so it decides retry for itself.
- **The second read of the rewrite** found 11 more, each fixed above; five would have met the
  implementer only at a run: the request factory whose read timeout gets no fallback, a breaker
  that counted a 4xx, a parent span that is Spring Security's and a break the series would have
  survived, a URL property read too early for Day 17, and a stub one hang would have blocked.
- **Closed on Day 19.** Estimated 3 in the provisional draft, 6 once rewritten (0, A1, A2, B, C,
  D); took 6, plus the spec change (#154) and this close.
- **A spec claim that did not reproduce.** The rewrite chose `JdkClientHttpRequestFactory` because,
  in the spec-auditor's scratch run, the `SimpleClientHttpRequestFactory` turned a read timeout
  into a `RestClientException` that no fallback caught. In #156 the Simple factory swapped in left
  `InternalClientsIT` green, and a throwaway test tried three hangs (no headers; JSON headers then
  a stalled body; headers with no content type): both factories gave `ResourceAccessException` in
  about 2 s every time. The JDK factory stays; the `InternalClients` Javadoc no longer makes the
  claim, and the hang tests pin what the fallbacks need.
- **Every track was written by the implementer agent on Haiku**, and review changed something in
  all six, twice rewriting a file: Track 0's stub and its test (446 lines, over the gate; and a
  test that could not fail), and Track D's observation test (labels pointing the wrong way, no 200
  check, an ancestor check that only asked for "api"). Its reports misstated counts twice
  (`PostingBatchIT` "18", `SavedJobsIT` "7"; "9 postings") and a departure once (Track B's test
  extends `MatchingTest`, kept), and left out findings it was asked for once (Track D's metric
  lines and span names). The reports' numbers in the PRs are from surefire, not from the agent.
- **My mistakes.** My first `StubUpstreamTest` could not fail: `failsWithin` passes on a timeout,
  and its answer went before the hang arrived (#155). A break script stopped with a break still in
  the file (#156, restored). One break did not apply and ran green on unbroken code, which I nearly
  read as the suite not noticing (#158). Shell quoting mangled break patches twice more (#159, #160), each
  caught by a dry run before a break ran; the patches are files now.
- **What the harness learned:** the JDK `HttpClient` retries a GET once when a connection closes
  with no answer, so a hang is tested with a POST, as the clients send; and `StubUpstream` runs on
  virtual threads, since one hang blocks a single-threaded stub.
