# Day 36 — GitOps and secrets

**Phase:** 7 · **Depends on:** Day 35 · **Expected PRs:** 3
**Status:** provisional — re-read and revise before starting.

## Goal
Deploying means merging to `main`. No secret exists in git or in a values file.

## In scope
- Argo CD `ApplicationSet`: one Application per service, each tracking its chart path.
- Image tags are git SHAs. CI builds, pushes, and updates the tag in the deploy repo path;
  Argo CD syncs. **No `kubectl apply` from CI.**
- External Secrets: every credential comes from Key Vault into a `Secret`.
  Services authenticate to Postgres, NoSQL and storage by **workload identity** where the
  platform supports it, so there is no password to rotate.
- Remove every secret from `docker-compose.yml`, values files and CI variables. The
  `.env` pattern stays for local development only.
- Sync waves so migration hooks run before the services that need them.
- Rollback is `argocd app rollback`, and it is rehearsed today, not during an incident.

## Out of scope
- Progressive delivery. Plain rolling updates are enough.

## Tracks

| Track | Owner | Work |
|---|---|---|
| A | | ApplicationSet, sync waves, CI tag updates |
| B | | External Secrets, Key Vault, workload identity |
| C | | Secret sweep + rollback rehearsal |

## Acceptance criteria
- [ ] Merging a commit deploys it with no human running a command.
- [ ] `git grep` finds no password, key or connection string in the repo.
- [ ] A rotated Key Vault secret reaches the pods without a redeploy.
- [ ] Argo CD reports every Application as Synced and Healthy.
- [ ] A rollback to the previous release has been performed successfully.
- [ ] CI has no cluster credentials at all.

## Verify
```bash
argocd app list
argocd app rollback identity-service
git grep -iE 'password|secret|api[-_]key' -- ':!*.md' ':!*example*'
```

## Notes
- CI holding no cluster credentials is the main security win of GitOps. Do not keep a
  `kubectl apply` fallback "just in case" — it defeats the whole arrangement.
