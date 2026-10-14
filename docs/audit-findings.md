# Audit findings: the end of Phase 7

Five audits ran on 2026-10-11, before `terraform destroy`, on Opus, each from a fresh context and
none reading another's report: the plan-auditor on Phase 7 (**P7**) and on the whole plan
(**WP**), and the security-ops (**SO**), agentic-engineering (**AE**) and architecture (**AR**)
auditors. Their reports are kept with the maintainer, outside the repository
(`day35-evidence/13-*.md`); the evidence column points into them by section or finding number.

Every finding ends as exactly one of:

- **a** fixed: in a track of the close-out day, in this plan change, or already in a Day 35 PR;
- **b** a known limit, with its reason;
- **c** an action for the maintainer, on their own accounts;
- **d** rejected by the maintainer, with their reason.

Rows marked c and d were decided by the maintainer on 2026-10-11.
*Owner* is the track (`43-A` is the close-out day's Track A), the plan line, or "maintainer".
*Closed by* is filled in by the close-out day's closing PR. The plan-auditor on the whole plan
checks at the end that no row is open. Nothing here names an account, an address or a secret.

## Plan-auditor, Phase 7

| ID | Finding | Evidence | Outcome | Owner | Closed by |
|---|---|---|---|---|---|
| P7-1 | The healthy-hosts alarm did fire (13:18Z, services at 0); the departure said it was not shown | P7 §1 | a | Day 35 | #406 (C35.19; `13-c3519-frontend-alarm-history.txt`) |
| P7-2 | The plain `curl` certificate failure for C35.16 was not saved | P7 §2 | a | Day 35 | #406 (`13-c3516-curl-cert.txt`) |
| P7-3 | No saved datapoint showed the metrics arrive as deltas | P7 §3 | a | Day 35 | #406 (`13-c3519-metric-deltas.txt`) |
| P7-4 | C35.18 expected matching's scores deleted; scores name no user since Day 27 | P7 §4 | a | Day 35 | #406 (Defect line, ticked on what happened) |
| P7-5 | C35.15's break line quoted a failed log fetch, not the migration's error; the runbook lacks the Git Bash settings | P7 §5 | b | Day 35 Notes | #406: the line corrected; the runbook left as is, nothing deploys again |
| P7-6 | No `phase-7` tag, though `specs/README.md` says phase ends are tagged | P7 §6 | c | maintainer | M-11 |
| P7-7 | `plan.md` still says a domain is needed, images go to ECR, the destroy leaves nothing; "provisional" | P7 §7 | a | plan, Phase 7 outcome | #407 |
| P7-8 | Phase 6 undecided; the Retrospective has no owner | P7 §8 | a | plan, Phase 7 outcome; 43-E | #407 (cut); #427 (the Retrospective) |
| P7-9 | 98% of X-Ray traces were the outbox relay and security spans, all sampled | P7 §9 | a | 43-C | #421 |
| P7-10 | Identity writes no log line for the traced request; only the gateway's carries the id | P7 §10 | b | Day 35 C35.19 | #406: identity logs no successful request, by design |
| P7-11 | README "Where it stands", status table and "Not yet answered" are stale; notebook's deployability answer due | P7 §11 | a | 43-D | #426; notebook part: #406 |
| P7-12 | The bad-image rollback and matching's `desired_count` drift were never exercised | P7 §12 | b | 43-D (README known limits) | #426: nothing deploys again |
| P7-13 | spec-drift reads the criterion id `C32.5` as a Track C32 (KAN-99) | P7 §13 | a | 43-A | #412 |
| P7-14 | Pace: 15 track PRs against 13, plus two fix PRs | P7 §14 | a | plan, Phase 7 outcome | #407 |
| P7-15 | Public ECR's throttle (Day 34's limit) did not occur | P7 hand-offs | b | Day 35 Notes | seen once, not ruled out |
| P7-16 | "And no more" (task roles allow nothing extra) cannot be shown by a deployment | P7 hand-offs, H36.5 | b | Day 36 checks | checked on the plan only |
| P7-17 | `DEPLOY_ENABLED` was still on before the destroy | P7 hand-offs | a | runbook step 14 | Day 35 step 14 (off) |

## Plan-auditor, whole plan

| ID | Finding | Evidence | Outcome | Owner | Closed by |
|---|---|---|---|---|---|
| WP-1 | Phase 6 undecided; Days 29–31 are Azure drafts; the dashboard would say "Day 29" next | WP §1 | a | plan, Phase 7 outcome | #407 |
| WP-2 | The deployed identity-service had no mail settings: no password-reset mail | WP §2 | b | 43-D (README known limits) | #426: Phase 6's mailer was the route, and it is cut |
| WP-3 | Rollback and the healthy-hosts alarm unproven before the destroy | WP §3 | b | as P7-12 | maintainer's departures, Day 35 |
| WP-4 | Dashboard next step wrong; `H34.1–H34.3` ranges not expanded; a day's own comment read as a hand-off | WP §4 | a | 43-A | #412 |
| WP-5 | `plan.md` not updated since Phase 7 began (verdict, provisional, pace, departures) | WP §5 | a | plan | #407 |
| WP-6 | README status, Known limits, layout, "Not yet answered", a retired runbook link; no link to the deployed architecture | WP §6 | a | 43-D | #426 |
| WP-7 | No phase reads for Phases 4, 5, 7; the Retrospective is empty | WP §7 | a | 43-E | #427 |
| WP-8 | H28.8: no `phase-3` tag, and the README does not say so | WP §8 | c | maintainer | M-11 |
| WP-9 | H28.18: nobody but the agent has followed "Running it" from a fresh clone | WP §9 | c | maintainer | M-12 |
| WP-10 | Hand-copied shared classes all differ (in comments), no test compares them | WP §10 | b | plan, course correction before Phase 7 | recorded there; comments are AR-17 |
| WP-11 | Settings outside the repository (variables, protection, Google redirect URI, bootstrap state) listed nowhere | WP §11 | a | 43-D (one list in the runbook) | #426; the removals are M-7, M-8, M-9 |
| WP-12 | Trace noise: 98% of traces are not requests | WP §12 | a | 43-C | #421, as P7-9 |
| WP-13 | The production seed reads identity's test fixtures | WP §13 | b | Day 35 In scope | one copy in the repository, chosen |
| WP-14 | identity-service's docs describe the monolith; root README links them as the system's | WP §14 | a | 43-D | #416, #417 |
| WP-15 | identity keeps the monolith's shape (three modules, its own dotfiles); renaming packages or moving the contract suite only if work continues | WP "The maintainer's question" | a | 43-F1–F3 (one module, the siblings' shape); packages and suite stay (plan, Phase 7 outcome) | #418, #419, #420 |

## Security-ops auditor

| ID | Finding | Evidence | Outcome | Owner | Closed by |
|---|---|---|---|---|---|
| SO-1 | The agent session holds an AWS administrator sign-in, refreshable without MFA | SO finding 1 | c | maintainer | M-1 |
| SO-2 | The agent session holds an admin GitHub token over every repository of the account | SO finding 2 | c | maintainer | M-2 |
| SO-3 | No required review, no CODEOWNERS, no deploy environment: merge, deploy and approval are one actor | SO finding 3 | d | maintainer: one person cannot approve their own PRs, and the agent uses their identity; possible only after M-2, and nothing deploys again | 2026-10-11 |
| SO-4 | Agent limits exist only in prompts: no deny rules, no hooks, "read-only" auditors have Bash | SO finding 4 | c | maintainer | M-4 |
| SO-5 | The public frontend ran Next.js 16.3.0 with critical RCE advisories; frontend has high alerts too (sharp, nanoid, source-map-js) | SO finding 5 | a | 43-C | #422 |
| SO-6 | RDS and DynamoDB cannot be recovered: no backups, no deletion protection, no PITR | SO finding 6 | b | 43-D (README known limits) | #426: one throwaway deployment, destroyed |
| SO-7 | No CloudTrail trail; agent and human actions carry one identity | SO finding 7 | c | maintainer | M-5 |
| SO-8 | ECR tags mutable, deploys use `latest`, image scans return nothing | SO finding 8 | b | 43-D (README known limits) | #426: nothing deploys again |
| SO-9 | Actions pinned by tag, `id-token: write` on build jobs, pip and base images unpinned | SO finding 9 | b | 43-D (README known limits) | #426: deploy switched off; recorded for whoever continues |
| SO-10 | identity-service runs as the owner role `app_user`, which inherits every module role | SO finding 10 | b | 43-D (README known limits) | #426: Day 36 Notes; nothing deploys again |
| SO-11 | `/internal/**` has no per-caller check, and tasks admit each other on every port | SO finding 11 | b | 43-D (README known limits) | #426: Day 36's recorded limit |
| SO-12 | Untrusted public text (PR bodies, issues, Jira) reaches agent context through spec-drift | SO finding 12 | a | 43-A (read only the maintainer's PRs) | #412 |
| SO-13 | The ALB's TLS key sat in plan JSON in the evidence folder | SO finding 13 | a | Day 35 | files deleted 2026-10-11, before #406 |
| SO-14 | No revocation or incident runbook | SO finding 14 | c | maintainer | M-13 |
| SO-15 | Thin edge: no ALB access logs, no WAF, no security headers, no per-user limit on LLM calls | SO finding 15 | b | 43-D (README known limits) | #426: one deployment, destroyed |
| SO-16 | Job text goes into the LLM prompt unescaped; output is validated (positive) | SO finding 16 | b | none needed | impact limited to ranking |
| SO-17 | `X-User-Id` is stripped and re-set from the verified token; the deploy role is tight (positive) | SO finding 17 | b | none needed | a control that holds |
| SO-18 | Memory and CLAUDE.md are agent-writable and loaded into every later session | SO summary item 7 | c | maintainer | M-15 |
| SO-19 | Open Dependabot alerts in `data/` (PyJWT, deepdiff critical), and security updates off | SO finding 5 | c | maintainer | M-14 |

## Agentic-engineering auditor

| ID | Finding | Evidence | Outcome | Owner | Closed by |
|---|---|---|---|---|---|
| AE-1 | The maintainer's role is asserted, not recorded: no GitHub reviews on any merged PR | AE §3 | b | 43-E (Retrospective) | #427 |
| AE-2 | H2 cannot separate "spec" from "agent": the agent wrote the specs; the verdict turns on an unregistered coding choice | AE §3 | b | 43-E (Retrospective) | #427 |
| AE-3 | H3 under-recorded: no `Rewrite` lines on Days 21–28 though review rewrote code | AE §3 | b | 43-E (Retrospective) | #427 |
| AE-4 | token-usage.py keeps the first transcript line per reply: Haiku 24–49× low | AE §3 | a | 43-A | #413 (Track A2) |
| AE-5 | Most important failure: checks that could not fail; only breaks and live runs found them | AE §4 | b | 43-E (Retrospective) | #427 |
| AE-6 | "Spec quality, not model capability" is weakened: the specs are the model's output | AE §6 | b | 43-E (Retrospective) | #427 |
| AE-7 | Autonomy: a denied tool call still had its side effect; plan, trust and cloud actions should stay constrained | AE §7 | b | 43-E (Retrospective) | #427 |
| AE-8 | Next experiments: unattended loop, spec-authorship ablation, blind planted-defect review | AE §8 | b | 43-E (Retrospective, future work) | #427: none runs here, the migration ends |
| AE-9 | Confounders: model, implementer and taxonomy changed mid-run; the gap angle is flat; 304 PRs have no break evidence | AE confounders | b | 43-E (Retrospective) | #427 |

## Architecture auditor

| ID | Finding | Evidence | Outcome | Owner | Closed by |
|---|---|---|---|---|---|
| AR-1 | infra-ci, spec-drift-tests and db-setup-tests are not required checks | AR finding 1 | a | 43-B (gate jobs); M-6 (protection) | #414 (gate jobs); M-6 (protection) |
| AR-2 | Gates can be rewritten inside the PRs they judge; merging rests on discipline | AR finding 2 | c | maintainer (M-2, M-4) | M-2, M-4 |
| AR-3 | Nothing checks that applied migrations and Day 1–4 tests stay unchanged | AR finding 3 | a | 43-B (invariants job) | #415 |
| AR-4 | `broken:` is an attestation; no break is replayed | AR finding 4 | b | 43-E (Retrospective) | #427: replayable breaks pay only if work continues |
| AR-5 | The through-gateway run can silently run fewer tests | AR finding 5 | a | 43-B (test-count floor) | #415 |
| AR-6 | Spec and evidence integrity is observed after merge, never gated | AR finding 6 | b | 43-E (Retrospective) | #427: spec-drift's tests can be required (AR-1); its integrity checks stay advisory |
| AR-7 | The dashboard's next step is wrong | AR finding 7 | a | 43-A | #412, as P7-13 |
| AR-8 | Name coverage counts tests and tooling as code (0.871; production only 0.433) | AR finding 8 | a | 43-A (a caveat on the dashboard) | #412 |
| AR-9 | H2's cause labels are self-assigned | AR finding 9 | b | 43-E (Retrospective) | #427, as AE-2 |
| AR-10 | The live checks carried their own copy of the API, unchecked until deploy day | AR finding 10 | b | 43-E (Retrospective) | #427: nothing deploys again |
| AR-11 | No network boundary between services: one security group for all | AR finding 11 | b | 43-D (README known limits) | #426: Day 36 C36.6, by design |
| AR-12 | Cross-service contracts copied by hand; the event has no schema | AR finding 12 | b | plan, course correction before Phase 7 | as WP-10 |
| AR-13 | No source of truth named between code, spec, plan, dashboard, Jira and memory | AR finding 13 | a | 43-D (one precedence line in CLAUDE.md) | #426 |
| AR-14 | identity's ArchUnit module rules now guard almost nothing | AR finding 14 | a | 43-F1 (module rules retire; `restClientsComeFromSpring` stays) | #418 |
| AR-15 | Folding identity's modules costs about zero counted lines; the recorded reason is wrong | AR "The maintainer's question" | a | plan, Phase 7 outcome; 43-F1–F3 | #407 (the reason); #418, #419, #420 |
| AR-16 | identity's `docs/` is the system's documentation filed under one service, and stale | AR "Leftovers" | a | 43-D | #416, #417, as WP-14 |
| AR-17 | 46 lines in 36 source files still say "the monolith" or `backend/` | AR "Leftovers" | b | none | comments only; editing four services' sources changes no behaviour |
| AR-18 | Other services' system tests sit in identity's test tree | AR "Leftovers" | b | plan, course correction before Phase 7 | identity's workflow is the system gate, recorded |

## The maintainer's accounts

Actions outside the repository that the findings above lead to, and the leftovers of the
deployment. Done by the maintainer, by hand; ticked here when done.

| ID | Action | From | Outcome | Closed by |
|---|---|---|---|---|
| M-1 | Remove the deploying IAM user's administrator policy, or close the AWS account after C35.22 (recorded 2026-10-14: 0.40 USD) | SO-1 | c | 2026-10-14: the administrator policy removed; the account kept |
| M-2 | Give agent sessions a fine-grained token for this repository only, without admin or workflow rights | SO-2, AR-2 | c | 2026-10-11: a fine-grained token for this repository; the old one revoked |
| M-3 | Require one review from an identity other than the agent's, and add CODEOWNERS (needs M-2 first) | SO-3, AR-2 | d: rejected, as SO-3 | 2026-10-11 |
| M-4 | Deny rules and a PreToolUse hook in Claude Code's settings; auditors without Bash | SO-4 | c | 2026-10-11: deny rules and a PreToolUse guard hook |
| M-5 | Agent commits from a separate identity; a CloudTrail trail if the account is kept | SO-7 | c | 2026-10-11: agent commits under their own name; 2026-10-14: a multi-region CloudTrail trail of management events (`management-events`) on the kept account |
| M-6 | Add the four gate jobs Track 43-B makes to branch protection's required checks: "Infra checks gate", "Dashboard metric tests gate", "db-setup tests gate" and "Applied migrations and the Day 1-4 tests stay unedited" (`invariants`) | AR-1 | c | 2026-10-14: the four gates are required checks on `main` (eleven in all), read back with `gh api` |
| M-7 | Delete the local secrets folder written for the deployment | WP-11 | c | 2026-10-11 |
| M-8 | Remove the Google OAuth client's redirect URI that names the destroyed load balancer | WP-11 | c | 2026-10-11 |
| M-9 | Destroy the bootstrap (state bucket and budget) once C35.22 is recorded (2026-10-14: 0.40 USD), or keep it on purpose | WP-11, P7-7 | c | 2026-10-14: the state bucket emptied and deleted, the budget deleted, a zero-spend budget in its place |
| M-10 | Close the unused AWS account made through the "projects" sign-up | Day 35 Notes | c | 2026-10-11 |
| M-11 | Tag `phase-7` on Day 35's closing merge and `phase-3` on Day 20's, or say why not | P7-6, WP-8 | c | 2026-10-11: `phase-3` on 5121a46 (#191), `phase-7` on 98a4c27 (#406) |
| M-12 | Ask someone else to follow "Running it" from a fresh clone, or record that nobody did | WP-9 | c | 2026-10-14: recorded, nobody yet; H28.18 stays open |
| M-13 | Revocation, written here: revoke the agent's GitHub token, attach a deny-all policy to the AWS user, set `DEPLOY_ENABLED` to false | SO-14 | c | 2026-10-11: token revoked (M-2), `DEPLOY_ENABLED` false; 2026-10-14: `AWSDenyAll` on the AWS user |
| M-14 | Turn on Dependabot security updates; `data/` is outside the migration | SO-19 | c | 2026-10-11 |
| M-15 | Read the memory folder for anything not written by the maintainer's sessions | SO-18 | c | 2026-10-11 |
