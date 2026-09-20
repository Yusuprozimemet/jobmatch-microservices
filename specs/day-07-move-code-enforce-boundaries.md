# Day 07 — Move code into modules, enforce boundaries

**Phase:** 1 · **Depends on:** Day 06 · **Expected PRs:** 4

## Goal
Every class lives in its service module, and the build fails if a module reaches into
another module's internals.

## In scope
- Move packages:

  | From | To |
  |---|---|
  | `auth`, `user`, `profile` | `identity` |
  | `jobs`, `mart` | `jobs` |
  | `savedjobs` | `applications` |
  | `matching` | `matching` |
  | `config` | `app` (except `GlobalExceptionHandler` → `shared`) |

- ArchUnit test per module: only a module's `api` package may be imported from outside it.
- Each module exposes an `api` package; everything else stays internal.
- **Expect exactly three compile failures.** They are the coupling this phase exists to
  remove. Unblock each with a temporary interface in `shared` tagged `// TODO day-NN`:

  | Failure | Owner day |
  |---|---|
  | `JobRepository` reads `saved_jobs` | Day 08 |
  | `SavedJobRepository` reads `analytics.fct_postings` | Day 09 |
  | `matching`/`applications` use `UserLookup`, `ProfileRepository` | Day 10 |

## Out of scope
- Removing the SQL joins — Days 08 and 09. Today only moves code.

## Tracks

| Track | Owner | Work |
|---|---|---|
| A | | Move `identity` |
| B | | Move `jobs` |
| C | | Move `applications` + `matching` |
| D | | ArchUnit rules + the three temporary interfaces |

## Acceptance criteria
- [ ] No classes remain in the old `backend.auth`, `.user`, `.profile`, `.jobs`,
      `.savedjobs`, `.matching`, `.mart` packages.
- [ ] ArchUnit fails the build when an import crosses into a non-`api` package.
- [ ] All Day 1–4 tests pass **unedited**.
- [ ] Exactly three `// TODO day-` markers exist, each naming its owner day.

## Verify
```bash
cd backend && ./mvnw verify
grep -rn "TODO day-" --include=*.java . | wc -l   # expect 3
```

## Notes
- Four parallel moves will conflict in the poms. Merge Track D's skeleton first,
  then move code.
- If a test needs editing, the move changed behaviour. Stop and find out why.
