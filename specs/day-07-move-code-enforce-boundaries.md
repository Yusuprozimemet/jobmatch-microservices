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
  | `jobs` | `jobs` |
  | `savedjobs` | `applications` |
  | `matching` | `matching` |
  | `config` | `app` |

  `mart` and `GlobalExceptionHandler` are already in `shared`; Day 06 moved them.

- ArchUnit test per module: only a module's `api` package may be imported from outside it.
- Each module exposes an `api` package; everything else stays internal.
- **Expect exactly two compile failures, and both are the same problem.** Every cross-module
  import in the codebase today is a module reaching into `identity` to turn an email into a
  user:

  | Failure | What it imports | Owner day |
  |---|---|---|
  | `SavedJobService` | `user.UserRepository` | Day 10 |
  | `JobMatchService` | `user.UserRepository`, `profile.Profile`, `profile.ProfileRepository`, `profile.dto.UpdateProfileRequest` | Day 10 |

  Unblock both with temporary interfaces in `shared`, tagged `// TODO day-10`: one to resolve
  a user, one to read a profile. `SecurityConfig` importing `auth.OAuth2LoginSuccessHandler`
  is not a failure — it lands in `app`, which depends on everything by design.
- **The two SQL couplings are not compile failures and do not block this day.** `JobRepository`
  reads `saved_jobs` and `SavedJobRepository` reads `analytics.fct_postings`, both inside SQL
  strings that no compiler can see. The code moves and compiles with those joins intact. They
  are real coupling and they are Days 08 and 09; expecting them to surface today is how you
  conclude the boundary is clean while two modules still read each other's tables.

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
      `.savedjobs`, `.matching` packages under `app`.
- [ ] ArchUnit fails the build when an import crosses into a non-`api` package.
- [ ] All Day 1–5 tests pass **unedited**: 196 green.
- [ ] Exactly two `// TODO day-10` markers exist, one per temporary interface.
- [ ] `grep -rn "saved_jobs" jobs/ ` and `grep -rn "fct_postings" applications/` still return
      hits. They are Days 08 and 09's to remove, and finding none today means the code did not
      move rather than that the coupling is gone.

## Verify
```bash
cd backend && ./mvnw verify
grep -rn "TODO day-" --include=*.java . | wc -l   # expect 3
```

## Notes
- Four parallel moves will conflict in the poms. Merge Track D's skeleton first,
  then move code.
- If a test needs editing, the move changed behaviour. Stop and find out why.
- **Spec corrected before the work.** The cross-module imports were counted rather than
  estimated — there are two, both into `identity`, and the list above is the full set.
- **`UserLookup` does not exist.** The spec named it as the coupling to break; no class by that
  name is anywhere in the repository. The real classes are `UserRepository` and
  `ProfileRepository`. **Day 10 is titled "Delete `UserLookup`" and is built on the same
  premise — it needs its own correction before it is worked.**
- ArchUnit's job here is narrower than it looks. A feature module cannot import another's
  classes at all: `maven-enforcer` bans the dependency, so the import would not resolve. What
  ArchUnit adds is the `api` convention — that a module's own packages do not reach past each
  other's front door — and a second line of defence if somebody adds a module dependency and an
  import together.
- The Dockerfile copies the whole tree, so five modules gaining their first sources today does
  not need a build change. It would have, before Day 06 fixed it.
