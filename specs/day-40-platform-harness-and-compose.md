# Day 40 — The suite runs across services, and compose runs them all

**Phase:** 3 · **Depends on:** Day 39 · **Expected PRs:** 3
**Status:** provisional — re-read and revise before starting.

The last of the platform step's three days (38–40), just before Phase 3's seam-first days
(18, 19, 17, 20). Written as an outline from `plan.md`'s course correction; rewritten against the
code, with the spec-auditor, when it is reached.

## Goal
The Day 1–4 suite can run against a service extracted from the monolith, directly and through the
gateway, without an edit to `contract/`, and `docker compose up` runs every service there is.

## In scope
- **The harness runs more than one application.** `support/IntegrationTest` boots
  `BackendApplication` in the test JVM and `support/Gateway` points at one `BACKEND_URL`. Add what
  runs an extracted service beside it (a second Spring context or a container on the same test
  Postgres) and routes its paths to it, in the direct run and through the gateway, with a
  self-test that shows the requests reached it. Changes to `support/` only.
- **The three `contract/` classes bound to the monolith's context:** `AuthGoogleSignInIT` (10
  tests, `@Import` and `@TestPropertySource`), `ObservabilityIT` (8, `@LocalManagementPort` and
  the monolith's `uri="/api/jobs"` metric) and `ObservabilityLoggingIT` (5). What each needs once
  job search leaves, including who owns the `/api/jobs` metric, is decided here, before any of
  them would need an edit. An edit to `contract/` is a stop-and-ask (CLAUDE.md).
- **The compose profile** `plan.md` asks for before Phase 3: every service in the default `up`, one
  Postgres with the databases they need, as the gateway joined on Day 16.
- The query-count tests' expiry notes (`JobSavedCountQueriesIT` "Day 25",
  `SavedJobHydrationQueriesIT` "Day 20") corrected to the day each will actually stop holding.

## Out of scope
- Extracting anything: Phase 3, from Day 18.

## Tracks
To be set when the day is written.

## Acceptance criteria
To be written when the day is reached, each `new` or `hold`, with the spec-auditor.

## Notes
- The mart fixtures are copied, not moved, when job-service gets its own database (Day 20): 11
  contract classes that stay with the monolith read them.
- From Day 38 (#123, #124): in compose the gateway can answer 500 for a few hundred milliseconds
  after its port opens, before Spring Cloud Gateway's proxy has its header filters. A compose
  healthcheck on the gateway's `/actuator/health/readiness` (port 9090, inside the network) and
  `depends_on: condition: service_healthy` for the frontend close it.
