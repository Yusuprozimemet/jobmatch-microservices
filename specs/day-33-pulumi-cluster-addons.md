# Day 33 — Pulumi cluster add-ons

**Phase:** 7 · **Depends on:** Day 32 · **Expected PRs:** 3
**Status:** provisional — re-read and revise before starting.

## Goal
The cluster has everything the applications need, installed as code, in the right order.

## In scope
- `infra/pulumi/`: ingress-nginx, cert-manager, External Secrets Operator, KEDA,
  Argo CD, Grafana Alloy — all as Helm releases with explicit dependency ordering.
- Reads Terraform's outputs via `pulumi-terraform`'s `RemoteStateReference`, pointed at
  the same blob backend. **Pulumi never creates a resource Terraform owns.**
- Namespaces: `prod`, `platform`. Default-deny NetworkPolicy in `prod`.
- Remote state for Pulumi too, with its own locking.
- Write down the boundary rule in `infra/README.md`: *does this change on every
  `git push`?* No → Terraform or Pulumi. Yes → Helm via Argo CD.

## Out of scope
- Application charts — Days 34 and 35.
- Argo CD Applications — Day 36. Today installs Argo CD; it configures nothing yet.

## Tracks

| Track | Owner | Work |
|---|---|---|
| A | | Pulumi project, state, Terraform state reference |
| B | | ingress-nginx, cert-manager, TLS issuer |
| C | | ESO, KEDA, Argo CD, Alloy |

## Acceptance criteria
- [ ] `pulumi up` on a fresh Terraform cluster succeeds with no manual step in between.
- [ ] A second `pulumi up` reports no changes.
- [ ] cert-manager issues a real certificate for the test hostname.
- [ ] Default-deny is active: a pod in `prod` cannot reach another without a policy.
- [ ] No resource appears in both Terraform and Pulumi state (check both).
- [ ] `infra/README.md` states the boundary rule.

## Verify
```bash
cd infra/pulumi && pulumi up --diff        # second run: no changes
kubectl get pods -n platform
```

## Notes
- This is the one place the two-tool split earns its keep: add-ons are Helm releases with
  real ordering, which Pulumi expresses better than Terraform.
- If the seam causes more pain than it saves, collapsing into Terraform alone is a
  legitimate outcome. Record the decision either way.
