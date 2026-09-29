# Day 41 — matching reads identity over HTTP, still in one process

**Phase:** 4 · **Depends on:** Day 20 · **Expected PRs:** 5

Phase 4's seam-first day, as Day 18 and Day 19 were Phase 3's (`plan.md`, Phase 4). Split out of
Day 24 on Day 21, when the spec-auditor found Day 21 could not run as written: `ProfileDirectory`'s
one implementation, `IdentityDirectory`, needs identity's repository, `@CurrentUserId`'s resolver
needs `UserRepository`, and since Day 38 only `identity_user` reads the identity schema. A
matching container has neither. The number is the next free one: days keep their numbers.

## Goal
`matching` learns who is asking and what their profile says only through identity's
`/internal/**` routes, over HTTP, through Day 19-style clients whose URL is empty (this process)
until Day 21 points them elsewhere. Day 21's extraction is then a change of URL.

## In scope
- **identity serves `GET /internal/profiles/{userId}`**: 200 with the `ProfileSnapshot` shape,
  `{"skills": [...], "preferredCity": ...}`, or 404 for a user with no profile. Behind the
  existing `/internal/**` chain (`SecurityConfig.java:66-83`): service tokens only. The path is
  not `.../skills`, as Day 24's draft had it: the snapshot carries the city too. The controller
  sits in the `identity` package and takes `IdentityDirectory`, not `ProfileDirectory`, as
  job-service's `InternalPostingController` takes `JobsDirectory`: once Track B's client is
  `@Primary`, a controller taking the interface gets the client, which calls this same route
  until its read timeout.
- **`ProfileDirectoryClient` in `matching`**, `@Primary`, built like `PostingShortlistClient`:
  `InternalClients.forUrlProperty("app.internal.identity-url")` (`INTERNAL_IDENTITY_URL`, empty
  is this process), a breaker `profileDirectory` on the `internal` base config. 404 is
  `Optional.empty()` (the controller's 422); a connection or timeout error, a 5xx or an open
  breaker is 503. `IdentityDirectory` stays, as `JobsDirectory` stayed on Day 19: it serves the
  route.
- **Both new clients are measured and traced** (Day 38): their routes join `CALLED_BY` in
  `InternalCallsObservedIT`, and each call is made with a URI template, so the `uri` tag is
  `/internal/profiles/{userId}`, not one tag per user.
- **The user id comes from the verified token's `sub`, not from `@CurrentUserId`.** The
  hand-off Days 17 and 39 left to Day 21 (`day-39-...md:72-73`) is decided here, because it is
  part of the seam: the token's `sub`, not the gateway's `X-User-Id`.
  - A verified `sub` needs no assumption about who can reach the service. `X-User-Id` is only
    as good as the rule that nothing but the gateway reaches matching-service, and the harness
    and compose both call services directly.
  - The monolith keeps the email as the principal (`AccessTokenAuthentication.java:72-74`), so
    `PrincipalEmail` and identity's controllers are unchanged. The `sub` travels with the
    authentication and is read in `shared.web`, without identity's classes or tables. The track
    brief settles where it is carried; the criteria below only fix what it does.
- **Before acting for a user, matching asks identity `GET /internal/users/{id}`** (Day 39's
  rule, `InternalUserController`), through a client on the same URL property. 404 is answered as
  no user is today: 422 (`SessionWithoutAUserIT.topMatchesAsksForAProfile`). An outage is 503.
  Its own breaker, `userExistence`, on the `internal` base config, as every client has one
  (`application.yaml`, `resilience4j.circuitbreaker.instances`). Not cached:
  `InternalUserController`'s comment says why.
- **Retry: none**, the question Day 19 left to Day 24 (`day-19-http-clients.md:261`). Both calls
  are idempotent reads, but a retry on a 2 s read timeout doubles the wait on the path the phase
  exists to protect, and the breaker already covers an outage. Day 24's measurement (its
  Track A) says whether that still holds across the network.
- **The docs and config that describe the old path change with it:** `application.yaml` gains
  `identity-url: ${INTERNAL_IDENTITY_URL:}` beside `jobs-url`; `docs/configuration.md` a row
  for `INTERNAL_IDENTITY_URL` beside `INTERNAL_JOBS_URL`; `docs/auth.md`'s paragraph naming
  `CurrentUserIdResolver` as matching's deleted-user check (`:323-327`) names the existence
  call; `IdentityDirectory`'s Javadoc (`:16-19`) no longer says the modules take their id from
  the resolver.

## Out of scope
- Moving `matching` out of the process — Day 21.
- A cache of profiles — Day 24, after measuring. The existence answer is not cached at all
  (above, and Day 24's In scope).
- `applications`' user id. It keeps `@CurrentUserId` until Day 25 extracts it.

## Tracks

| Track | Owner | Work |
|---|---|---|
| A | | identity's `GET /internal/profiles/{userId}` and its test |
| B | | `ProfileDirectoryClient`: breaker, 404 as empty, 503 on outage, observed |
| C | | The id from `sub`; the existence call, its breaker, observed; `@CurrentUserId` out of `matching`; the docs |

B depends on A (the client needs the route to call in-process). C depends on B only for the
shared URL property; it may land second. Criterion 2 belongs to whichever of B and C lands
first. Criteria 2 and 3 may share one test class, with `StubUpstream.refuse(..., 503)` in place
of a closed port: every URL override is another cached Spring context, and each holds four
module pools. C is the largest track; if it passes 400 lines, the docs go in a PR of their own.

## Acceptance criteria
- [ ] **new** — `GET /internal/profiles/{id}` with a service token answers 200 and the user's
      skills and city for a user with a profile, and 404 for one without; the cookie and a user
      token in the header are 401 (`InternalProfilesIT`, modelled on `tokens/InternalUsersIT`,
      identity's other internal route). Red today: 404 for a user with a profile, no handler.
- [ ] **new** — with `app.internal.identity-url` pointed at a closed port, top-matches answers 503
      within 5 s, not 500 and not a hang. Red today: 200, the profile is read in-process.
- [ ] **new** — with `app.internal.identity-url` pointed at a `StubUpstream` that answers 404 to
      `/internal/users/{id}` and 200 with a five-skill profile to `/internal/profiles/{id}`,
      top-matches answers 422, its `detail` says "Fill in your profile", and the stub saw
      exactly one call to `/internal/users/{id}`. The last two are there because 422 is also
      the answer for too few skills (`JobMatchService.java:51-54`): a client that misreads the
      profile passes on the status alone. Red today: 200, the user is looked up in-process by
      email.
- [ ] **new** — with a user token whose `sub` is user A (five skills in their profile) and
      whose `email` is user B's (no profile), top-matches answers 200: the id is the `sub`, not
      an email lookup. The token comes from a `TestTokens` helper beside `expired` (`support/`
      may change). Red today: 422, `CurrentUserIdResolver` finds B by email
      (`CurrentUserIdResolver.java:55-57`).
- [ ] **new** — `InternalCallsObservedIT` passes with `/internal/profiles/{userId}` and
      `/internal/users/{id}` in `CALLED_BY`, each called by `/api/jobs/top-matches`: one
      client span and one `http.client.requests` timer, tagged with the template. Red today:
      nothing calls either route, so no span.
- [ ] **new** — `grep -rn "CurrentUserId" backend/matching/src/main` finds nothing. Red today:
      finds 2, both in `JobMatchController.java`.
- [ ] **hold** — the Day 04 matching and session contract classes pass unedited:
      `MatchTopMatchesIT`, `MatchRankingIT`, `MatchScoreCacheIT`, `SessionWithoutAUserIT`.
      Broken on purpose in the spec-change PR: `JobMatchController` answering 200 and an empty
      list for a missing user. `SessionWithoutAUserIT.topMatchesAsksForAProfile` failed,
      expected 422 but was 200; the three `Match*IT` classes stayed green, 22 of 22.
- [ ] **hold** — `CurrentUserQueriesIT.topMatchesLooksTheUserUpOnce` stays at one statement on
      `users`: the existence call replaces the resolver's lookup, it does not add a second.
      Broken on purpose in the spec-change PR: `CurrentUserIdResolver` reading the user twice.
      Both tests failed, expected 1 but was 2. After Track C top-matches no longer goes
      through the resolver, so Track C breaks it again on the new path, the existence call made
      twice, and records what it reported.
- [ ] **hold** — `ModuleBoundariesTest` passes: `matching` depends on nothing in `identity`.
      The Maven graph refuses it first (`matching`'s pom has no `identity`), so the break is a
      class in `matching`'s package inside `app`. Broken on purpose in the spec-change PR: such a
      class calling `PrincipalEmail.of`. `matchingKeepsToItself` failed, naming
      `BreakBoundary.anyone()` and `PrincipalEmail.of`.

## Verify
```bash
docker build -t jobmatch-job-service:harness services/job-service
cd backend && ./mvnw clean verify && ./mvnw -B checkstyle:check
grep -rn "CurrentUserId" matching/src/main        # nothing
```

## Notes
- Hand-offs this day takes: `sub` vs `X-User-Id` (Days 17 and 39), the existence call for
  matching (Day 39), retry (Day 19). What it leaves: the profile cache (Day 24), `applications`'
  id and existence call (Day 25).
- `ProfileDirectory`'s Javadoc (`:10`) names Day 24 for the move behind HTTP; Track B updates
  it. (The draft said `CurrentUserQueriesIT` did too; it names Days 10, 13 and 39.)
- **Audit, spec-change PR.** The plan-auditor on Phase 4 found nothing blocking this day; its
  Day 21 and Days 22–23 findings go to those days' spec-change PRs. The spec-auditor ran every
  check on 62aa31c: `clean verify` 412 tests, 0 failures, 1 skipped; checkstyle 0; every count
  and hold as written. What it changed: Track A's controller takes `IdentityDirectory` (the
  interface would loop through the client); criterion 3 checks the detail and the call, not
  only 422; the existence client's breaker is named; both clients join the observation test;
  the `sub` criterion; the docs in scope; the retry revisit given to Day 24.
- **Defect** — found: auditor · cause: spec · the draft left Track A's controller free to
  take `ProfileDirectory`, which after Track B would call itself over HTTP, and let criterion 3
  pass on a 422 from too few skills. Fixed in the Day 41 spec-change PR.
- **Defect** — found: spec-change PR · cause: environment · the first break runs built `app`
  on `identity` classes the IDE's compiler had written into `target/classes` ("Unresolved
  compilation problems"), and every request answered 500. Run without `clean`, against
  `CLAUDE.md`; the baseline with `clean` was green, 33 of 33, and the breaks were rerun on it.
