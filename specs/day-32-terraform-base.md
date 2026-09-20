# Day 32 — Terraform base infrastructure

**Phase:** 7 · **Depends on:** Day 31 · **Expected PRs:** 3
**Status:** provisional — re-read and revise before starting.

## Goal
Every long-lived cloud resource is described in Terraform, and nothing is clicked into
existence by hand.

## In scope
- `infra/terraform/`: network, Kubernetes cluster, Postgres (three databases), NoSQL
  account, storage account with both containers, Key Vault, container registry,
  managed identities.
- Remote state in a blob backend with locking. **Set this up first** — local state with
  eight contributors is a guaranteed conflict.
- Outputs: resource ids, endpoints and Key Vault references. Day 33 consumes these.
- Environments as workspaces or directories: `dev` and `prod`, same code.
- CI: `plan` on every PR touching `infra/`, `apply` only on merge to `main`.
- Import anything already created by hand rather than recreating it.

## Out of scope
- Cluster add-ons — Day 33.
- Application deployment — Days 34 to 36.

## Tracks

| Track | Owner | Work |
|---|---|---|
| A | | Remote state, backend, workspaces, CI plan/apply |
| B | | Cluster, network, registry, identities |
| C | | Postgres, NoSQL, storage, Key Vault, outputs |

## Acceptance criteria
- [ ] `terraform apply` from an empty subscription produces a working `dev` environment.
- [ ] A second `apply` with no changes reports no changes.
- [ ] State is remote and locked; two concurrent applies conflict safely.
- [ ] No secret value appears in state outputs — only Key Vault references.
- [ ] `plan` runs on PRs and `apply` never runs from a PR branch.
- [ ] `destroy` on `dev` works, so the environment is genuinely reproducible.

## Verify
```bash
cd infra/terraform && terraform workspace select dev && terraform plan   # no changes
```

## Notes
- Two sharp edges: Postgres firewall rules for the pipeline in Databricks, and the
  Google OAuth redirect URI, which is registered against a hostname that must not change.
