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
- [x] No classes remain in the old `backend.auth`, `.user`, `.profile`, `.jobs`,
      `.savedjobs`, `.matching` packages under `app`.
- [x] ArchUnit fails the build when an import crosses a module boundary. **The `api` part is
      not built**: no feature module publishes anything to another today — they speak through
      `shared` — so the rule would guard packages that do not exist. Add it the first time a
      module needs to publish a type another module names directly.
- [x] All Day 1–5 tests pass **unedited**: 196 green.
- [x] Exactly two `// TODO day-10` markers exist, one per temporary interface.
- [x] `grep -rn "saved_jobs" jobs/ ` and `grep -rn "fct_postings" applications/` still return
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
- **Five pull requests against the four this spec estimated**, and the extra one is the split
  between moving `applications`/`matching` and writing the rules: the rules need the final
  layout to have anything to assert against.
- **No test file was edited by any of the four moves.** Roughly seventy classes changed package
  and the 196 contract tests did not notice, because they speak HTTP and JDBC and never name an
  application class. That was the argument for writing them that way in Phase 0 and this is the
  first time it was tested.
- **The enforcer is the gate; ArchUnit is the record.** Adding a pom dependency and an import
  together fails at `maven-enforcer` before any test runs — so ArchUnit catches nothing the
  enforcer misses. What it adds is independence: with `-Denforcer.skip=true` the same violation
  still fails. Worth knowing which is which, and the two now use different wording so a red
  build says which fired.
- The two SQL joins are still in place, as this day's criteria require. `jobs` reads
  `saved_jobs` twice, `applications` reads the mart once. Days 08 and 09.
- `app` is five files: `BackendApplication` and four config classes.
- The minimum-skills floor was two constants that happened to agree, in `UpdateProfileRequest`
  and `JobMatchService`. Splitting the modules would have made them independent; it is one
  constant in `shared` now, because a form that accepts a profile matching then refuses to rank
  is a bug nobody would look for.
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
