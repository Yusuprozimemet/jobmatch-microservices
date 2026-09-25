# Day 37 — Production cutover

**Phase:** 7 · **Depends on:** Day 36 · **Expected PRs:** 3
**Status:** provisional — re-read and revise before starting.

## Goal
Real users are served from the cluster, and the team can tell when something is wrong.

## In scope
- `prod` environment from the same Terraform, Pulumi and charts as `dev`. No bespoke prod.
- DNS and TLS cut over. **Update the Google OAuth redirect URI before the switch**, not after.
- Data migration: production Postgres and NoSQL, with a rehearsed restore, not just a backup.
- Telemetry to Grafana Cloud. Dashboards: gateway RED metrics, LLM latency and spend,
  **mart freshness** — the age of the last successful publish sync, which is the alert that
  tells you the job board has gone stale.
- Alerts with owners: DLQ depth, `user.deleted` failures, mart staleness, error rate,
  certificate expiry.
- A runbook: how to roll back, who to wake, where the logs are.
- Decommission the old deployment only after a week of clean running.

## Out of scope
- Autoscaling tuning beyond the KEDA and HPA defaults. Tune with real traffic.

## Tracks

| Track | Owner | Work |
|---|---|---|
| A | | prod environment, DNS, TLS, OAuth redirect |
| B | | Data migration + restore rehearsal |
| C | | Grafana Cloud, dashboards, alerts with owners |
| D | | Runbook, on-call agreement, decommission plan |

## Acceptance criteria
- [ ] Every user flow works in production from a real browser.
- [ ] A database restore has been performed into a scratch environment and verified.
- [ ] Each alert has a named owner and has been fired once deliberately.
- [ ] A rollback has been rehearsed in production during a quiet window.
- [ ] The mart-staleness alert fires when a pipeline run is skipped.
- [ ] The runbook is written and someone who did not write it has followed it.
- [ ] The old deployment is off, after a clean week.

## Verify
```bash
# full browser walkthrough against the production hostname
# deliberately skip a pipeline run and confirm the staleness alert fires
```

## Notes
- **End of Phase 7, and of the migration.**
- The criterion most likely to be skipped is the restore rehearsal. A backup nobody has
  restored is not a backup.
- From Day 38 (#123): the gateway tags a routed call's `http_server_requests` with the route's
  pattern, `uri="/api/**"`, not the path, so the gateway has one series for the whole API. A
  per-route view at the gateway needs a tag of its own; the backend keeps its per-path series.
- From Day 40: the gateway's job route (`/api/jobs`, `/api/jobs/filters`,
  `/api/jobs/{postingId}`) is tagged with its own patterns, so those three have series of their
  own at the gateway; the rest of the API is still one `uri="/api/**"` series.
