# Day 35 — Charts for the remaining services

**Phase:** 7 · **Depends on:** Day 34 · **Expected PRs:** 4
**Status:** provisional — re-read and revise before starting.

## Goal
All four services plus the gateway run in the cluster, with the frontend.

## In scope
- Charts for `api-gateway`, `identity-service`, `application-service`, `matching-service`,
  and the frontend. Each is a values file over `charts/common`.
- Per-service specifics:

  | Service | Specific |
  |---|---|
  | `api-gateway` | the only Ingress; TLS; rate-limit config |
  | `identity-service` | migration hook; JWKS reachable by the others; Google redirect URI |
  | `application-service` | migration hook |
  | `matching-service` | no database; KEDA `ScaledObject` on queue depth, not CPU |
  | `frontend` | points at the gateway's internal Service |

- NetworkPolicies per service: only the callers in the chart may connect.
- The functions stay **outside** the cluster. `cv-parse` reaches identity through the
  ingress with a service token — confirm the network path actually works.

## Out of scope
- GitOps — Day 36.
- Production traffic — Day 37. This is the `dev` cluster.

## Tracks

| Track | Owner | Work |
|---|---|---|
| A | | Gateway chart + Ingress + TLS |
| B | | identity + application charts, migration hooks |
| C | | matching chart + KEDA scaling |
| D | | Frontend chart + NetworkPolicies |

## Acceptance criteria
- [ ] The full Day 1–4 suite passes against the `dev` cluster.
- [ ] Google sign-in works against the cluster hostname.
- [ ] `matching-service` scales up under queue depth and back down to its floor.
- [ ] A NetworkPolicy violation is provable: `application-service` cannot reach
      `identity_db` directly.
- [ ] `cv-parse` successfully calls identity from outside the cluster.
- [ ] No service has a published port except through the gateway's Ingress.

## Verify
```bash
kubectl -n prod get pods
# run the integration suite against the cluster ingress hostname
```

## Notes
- The function-to-ingress path is the thing most likely to be broken and least likely
  to be noticed, because CV upload is not on the login path.
- From Day 39: `cv-parse`'s service token is signed by its own key (see Day 30's Notes), and
  identity must be able to fetch that key set from inside the cluster.
