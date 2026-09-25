# Day 18 — The internal API job search and matching will need

**Phase:** 3 · **Depends on:** Day 40 · **Expected PRs:** 4

The first of Phase 3's seam-first days (18, 19, 17, 20). Rewritten on Day 18 from the provisional
draft, against `main` at c641439, after the plan-auditor's read of Phase 3 and the
spec-auditor's read of this day, with the maintainer's three choices below.

## Goal
The monolith answers, over HTTP and to service tokens only, the three questions its modules now
ask each other in process: posting details by id, the match shortlist, and saved counts by
posting. Nothing calls these routes yet (Day 19), so nothing changes for a user. When Day 17 moves
`jobs` out, the routes it needs are already serving.

## In scope
- **The maintainer's choices, made on Day 18:**
  1. **`POST /internal/saved-counts` is Day 18's** (Track C), not Day 17's. Day 39's Out of scope
     already hands it here, and the seam rule (`plan.md`, "Course correction") needs it serving
     before `jobs` leaves on Day 17, not built the same day.
  2. **The id cap is 500 distinct ids**, on both id routes. Saved jobs fetches a user's whole
     list in one `byIds` call (`SavedJobService.java:44-50`, "tens of rows") and nothing limits
     how many jobs a user saves, so Day 19's client splits a longer list into chunks of 500.
     Job search asks for counts one page at a time, at most 100 (`JobController.java:49`).
  3. **The internal routes stay out of the public OpenAPI.** Day 39 excluded `/internal/**`
     (`application.yaml:103`) because the gateway routes `/api/docs/**` to the public, and a
     springdoc group under that path lists them there too (tried by the spec-auditor). They
     are described in `backend/docs/api.md` instead, each track adding its own route.
- **Where they live.** The postings routes go in `jobs`, the counts route in `applications`, as
  package-private controllers next to `JobsDirectory` and `ApplicationsDirectory` and calling
  them only through `PostingLookup`, `PostingShortlist` and `SavedJobCounts`. Not in `app`: they
  leave with their module (`jobs` on Day 17, `applications` on Day 25).
- **Auth is Day 39's, unchanged.** The `/internal/**` chain (`SecurityConfig.java:66-83`) takes a
  bearer service token with `aud=jobmatch-internal` from a trusted issuer, the monolith's own
  first (`InternalCallers.java:39`), and nothing else. CSRF is already off for these POSTs. The
  gateway routes only `/api/**` and `/.well-known/jwks.json`, so none of this is reachable from
  outside.
- **`POST /internal/postings/batch`** (Track A). Body `{"ids": ["…"]}`. Answers a JSON object
  keyed by posting id, each value a `PostingSummary` (which carries no id of its own): what
  `PostingLookup.byIds` returns. An id the mart does not have is **absent**, as the interface
  promises and `SavedJobHydrationIT`'s vanished-posting tests rely on once Day 19 puts HTTP
  behind it. Duplicates count once. An empty list answers `{}` without a query. More than 500
  distinct ids, or no `ids`, answers 400 before any query.
- **`POST /internal/postings/shortlist`** (Track B). Body `{"city": "…", "skills": ["…"],
  "limit": n}`, `city` optional. Answers the `ShortlistedPosting` list `PostingShortlist` returns,
  in its order. `skills` empty or missing answers 400: in process it expands to `IN ()`, which
  Postgres rejects, so passing it through would be a 500. `limit` outside 1–100 answers 400
  (matching asks for 40, `JobMatchService.java:34`). Skills are passed as given; the interface
  asks for lowercase, and matching already sends them so.
- **`POST /internal/saved-counts`** (Track C). Body `{"ids": ["…"]}`. Answers a JSON object with
  one entry per distinct id, 0 for ids nobody saved: what `SavedJobCounts.countsFor` returns.
  Empty list, `{}` without a query; more than 500 distinct ids, or no `ids`, 400.
- **Validation by hand in `jobs`**, a 400 through `ResponseStatusException`, as `JobController`
  does: `jobs` has no `spring-boot-starter-validation`, and one check does not earn it.
- **The SQL does not move or change.** Day 09 already moved it into `jobs`; `JobsDirectory.java`
  and `ApplicationsDirectory.java` have no diff over the day.
- **Track 0 pins what the tracks must not change**: the gateway's refusal of the three new paths,
  the shortlist's order in process (pinned by 1 test of 22 so far, "thin for Day 18", Day 09's
  Notes), and the public OpenAPI's silence about every `/internal/` path, not only
  `/internal/users`.

## Out of scope
- The clients that call these routes, the token they attach, and the 500-id chunking: Day 19.
- Job-service's own `/internal/**` chain and its trust of the monolith's issuer: Day 17.
- Caching the answers. No day owns it; Day 19's clients measure the calls first.
- Day 03's search filters that advertise options the query ignores (`experienceLevels`,
  `employmentTypes`, `JobSearchIT.java:184-186`, "Phase 3 decides"), and moving `MartSkills` out
  of `shared` (Day 06): Day 17, in its Notes from today.

## Tracks

| Track | Owner | Work |
|---|---|---|
| 0 | | Gateway refuses the three paths; shortlist order pinned in process; public OpenAPI lists no `/internal/` |
| A | | `POST /internal/postings/batch` in `jobs` |
| B | | `POST /internal/postings/shortlist` in `jobs` |
| C | | `POST /internal/saved-counts` in `applications` |

Track 0 lands first. A, B and C are independent; B adds its method to A's controller if A has
merged, or makes it.

## Acceptance criteria
- [ ] **hold** — The gateway routes none of the three: `POST /internal/postings/batch`,
      `POST /internal/postings/shortlist` and `POST /internal/saved-counts` through the gateway
      answer 401 without a cookie and 404 with a valid one, and the upstream receives nothing;
      the existing `GET /internal/users/…` and service key set cases unchanged. Track 0 adds the
      three to the gateway's
      `SecurityTest.internalRoutesAndTheServiceKeySetAreNotRoutedEvenWithAValidCookie`. Green
      today (the spec-auditor's scratch test: 401, 404, nothing received). Broken on purpose in
      Track 0's PR: a route for `/internal/postings/**` in `Routes.java`, which the existing
      `GET /internal/users/…` case does not catch (the auditor's scratch: 200, one request
      received, the existing test green).
- [ ] **hold** — The shortlist's order, in process: `postings/ShortlistOrderIT` calls
      `PostingShortlist.shortlist` on a fixed fixture and asserts an explicit list of ids. The
      fixture makes each rule decide at least one place: more matched skills first; then
      `posted_date DESC NULLS LAST`, with a null date (set by an `UPDATE`: `PostingBuilder`
      always writes a date); then `posting_id`; a repost pair (same title and company, different
      case) tied on matched skills and differing only in date, whose losing row would rank
      **inside** `limit` if it were kept, so that keeping it changes the list; a posting in
      another city left out when `city` is given; and `limit` cutting the list. The fixture
      uses a city no `seed-*` posting has, as `MatchingTest` does, so `TestDatabase.reset()`'s
      30 seeds stay out of it. Green today. Broken on purpose in Track 0's PR, each alone: each
      of the final `ORDER BY`'s three keys reversed, and the repost `PARTITION BY` without
      `lower(...)`. (The spec-auditor's scratch copy of the SQL: all four red on a fixture of
      this shape; the repost break stayed green when the losing row ranked below the cut.)
- [ ] **hold** — The public OpenAPI lists no `/internal/` path. Track 0 widens
      `tokens/InternalUsersIT.theOpenApiListsNoInternalPath` from `/internal/users` to
      `/internal/`. Green today. Broken on purpose in Track 0's PR: `paths-to-exclude` cut to the
      service key set. Through Tracks A–C it covers the new routes as well.
- [ ] **new** — Posting details by id: with a service token, from the monolith's own minter and
      from `TestServiceCaller`, `POST /internal/postings/batch` with two ids in the mart, one not,
      and one repeated answers 200 with exactly the two present ids as keys, each value equal to
      `PostingLookup.byIds` in process for the same ids. `{"ids": []}` answers `{}` and
      `StatementCounter.statementsMentioning("fct_postings")` is 0. Red today: 404 with a service
      token (the route does not exist).
- [ ] **new** — The batch cap: 501 distinct ids answer 400 and no statement mentions
      `fct_postings`; 500 distinct ids, sent with duplicates, answer 200. A body without `ids`
      answers 400. Red today: 404.
- [ ] **new** — The shortlist over HTTP: with a service token, `POST /internal/postings/shortlist`
      on Track 0's fixture answers Track 0's expected ids in the same order, each entry with the
      fields of `ShortlistedPosting`. `skills` empty or missing, and `limit` 0 or 101, answer
      400. Red today: 404.
- [ ] **new** — Saved counts: with a service token, `POST /internal/saved-counts` with an id
      saved by two users, one saved by one user, and one nobody saved, the last also sent twice,
      answers one entry per distinct id (2, 1, 0), equal to `SavedJobCounts.countsFor` in
      process. (A user cannot save a posting twice: `saved_jobs`' primary key is
      `(user_id, posting_id)`.)
      `{"ids": []}` answers `{}`, and 501 distinct ids answer 400, both with no statement
      mentioning `saved_jobs`. Red today: 404.
- [ ] **hold** — The `/internal/**` chain guards the new routes: on each of the three, no token,
      a user's `access_token` cookie, and a user token in the `Authorization` header each answer
      401. Green today (Day 39's chain; `InternalRoutesIT` pins it on an unmapped path). Broken
      on purpose in each track's PR, each alone: the chain's `securityMatcher` narrowed to
      `/internal/users/**`, which sends the cookie to the application chain and through (the
      cookie case red); and `permitAll()` in place of `authenticated()` (the no-token case red).
      The user token in the header is refused by the key set itself, which no setting turns off,
      as Day 39 recorded.
- [ ] **hold** — Nothing else moves: `JobsDirectory.java`, `ApplicationsDirectory.java` and
      `contract/` have no diff between this spec change's merge and the close; the Day 1–4 suite
      passes, direct and through the gateway; the backend and the gateway are green with no
      checkstyle violation. Green today (the spec-auditor's baseline on c641439: 337 tests, 0
      failures, 1 skipped, the opt-in `GatewayHarnessIT`; the gateway's 37; checkstyle clean in
      all modules). The diff check broken on purpose in the spec-change PR: a blank line added to
      `JobsDirectory.java` and committed on a scratch branch, and `git diff <base> HEAD --stat`
      printed `1 file changed, 1 insertion(+)`; without it, nothing.

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

# Nothing else moved. <base> is this spec change's merge commit. Prints nothing.
git diff <base> HEAD -- backend/jobs/src/main/java/nl/hackyourfuture/project/backend/jobs/JobsDirectory.java \
  backend/applications/src/main/java/nl/hackyourfuture/project/backend/applications/ApplicationsDirectory.java \
  backend/app/src/test/java/nl/hackyourfuture/project/backend/contract

# Compose, in a project of its own. Never `down -v` on the maintainer's.
docker compose -p day18check --env-file .env.example up -d --build --wait db backend api-gateway
curl -s -o /dev/null -w '%{http_code}\n' -X POST localhost:8080/internal/postings/batch   # 401
curl -s -o /dev/null localhost:8080/api/auth/register -H 'Content-Type: application/json' \
  -d '{"email":"d18@example.test","password":"Password-18!","name":"Day 18","acceptedTerms":true}'
curl -s -c jar -o /dev/null localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"d18@example.test","password":"Password-18!"}'
for p in postings/batch postings/shortlist saved-counts; do                               # 404 x3
  curl -s -b jar -o /dev/null -w '%{http_code}\n' -X POST localhost:8080/internal/$p; done
docker compose -p day18check --env-file .env.example down -v; rm -f jar
```

## Notes
- **Rewritten on Day 18** from the provisional draft, which described the old order (17 before
  18) and a `job-service` that does not exist yet. What the audits found wrong with it:
  - Track C (identity issuing service tokens with `aud: internal` on Day 12's key set) was
    superseded by Day 39: each service signs its own, `aud=jobmatch-internal`, and the
    monolith's `/internal/**` chain exists. Its two criteria were wrong as worded as well: the
    gateway answers 401 without a cookie and never 403, and a bearer token at the gateway is
    ignored (it reads the cookie only), so "from any client" did not hold. They are now holds
    on the new paths.
  - "Both endpoints appear in the OpenAPI document" contradicted Day 39's
    `theOpenApiListsNoInternalPath`, and now is its opposite.
  - The saved-counts route Day 39 handed here was missing; no Phase 3 day owned it.
  - The cap had no number, and would have turned a long saved list into a 400.
  - The Verify ran `services/job-service/mvnw`, which does not exist.
- **For Day 19**, from today (also in its Notes): its client for `PostingLookup` splits ids into chunks of 500; its
  `SavedJobCounts` client calls the monolith's own `/internal/saved-counts`, and is the client
  job-service keeps on Day 17 with only its URL changed; all three routes are POST, so
  "bounded retry on idempotent GETs only" applies to none of them.
- **For Day 17**, from today: the saved-counts route it built in the draft is served from Day 18.
  Day 03's ignored filters and `MartSkills`' place in `shared` are its to decide or drop.
