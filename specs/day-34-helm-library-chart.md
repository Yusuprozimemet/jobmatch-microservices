# Day 34 — Helm library chart and the first service chart

**Phase:** 7 · **Depends on:** Day 33 · **Expected PRs:** 3
**Status:** provisional — re-read and revise before starting.

## Goal
One shared set of templates, and `job-service` deployed from a chart that is almost
entirely `values.yaml`.

## In scope
- `charts/common`, a Helm **library** chart: Deployment, Service, HPA, PodDisruptionBudget,
  ServiceAccount, NetworkPolicy, and a Flyway `pre-upgrade` hook Job.
- `services/job-service/chart`: depends on `common`; each template is a one-line
  `include`. All variation lives in `values.yaml`.
- Sane defaults in `common`: resource requests and limits, liveness and readiness probes
  on `/actuator/health`, `securityContext` with a non-root user, topology spread.
- `job-service` deployed to `prod` and reachable through the ingress.
- Migrations run as a hook Job, **not at application startup** — a failed migration must
  abort the release rather than crash-loop three pods.

## Out of scope
- The other four services — Day 35.
- GitOps — Day 36. Today is `helm upgrade --install` by hand.

## Tracks

| Track | Owner | Work |
|---|---|---|
| A | | `charts/common` templates and helpers |
| B | | `job-service` chart + values |
| C | | Migration hook Job + failure behaviour |

## Acceptance criteria
- [ ] `job-service`'s chart contains no raw Kubernetes YAML, only `include` calls.
- [ ] `helm upgrade --install` deploys and `/api/jobs` answers through the ingress.
- [ ] A deliberately broken migration aborts the release and leaves the old pods serving.
- [ ] Pods run as non-root with resource limits set.
- [ ] `helm template` output is committed as a golden file so chart changes are reviewable.
- [ ] Adding a hypothetical sixth service would need only a values file (write one to prove it).

## Verify
```bash
helm upgrade --install job-service services/job-service/chart -n prod
kubectl -n prod get pods,svc,hpa
curl -s https://<host>/api/jobs | head -c 200
```

## Notes
- The golden-file criterion matters with eight contributors: a chart change is otherwise
  invisible in review until it reaches the cluster.
