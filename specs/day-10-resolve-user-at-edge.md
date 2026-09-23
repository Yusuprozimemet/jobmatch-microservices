# Day 10 — Resolve the user once, at the edge

**Phase:** 1 · **Depends on:** Day 07 · **Expected PRs:** 3

## Goal
Only `identity` turns an email into a user. Every other module receives a `UUID userId` from
its controller and never learns that users have emails, or a table.

## Where this starts
Day 07 already did part of what this spec was first written to do. There is no `UserLookup`
and no `requireUserId` — neither ever existed. What exists:

- `UserDirectory.findUserIdByEmail(email)` in `shared.identity`, implemented by `identity`'s
  `IdentityDirectory`. `applications` calls it once per request (`SavedJobService`), and so
  does `matching` (`JobMatchService.loadProfile`).
- `ProfileDirectory.forUser(userId)` returning `ProfileSnapshot(skills, preferredCity)` —
  already the interface this spec used to call `ProfileSkills`, with the two fields `matching`
  actually reads.
- The same "principal to email" helper, copied four times: `SavedJobController`,
  `JobMatchController`, `ProfileController`, `UserController`.

## In scope
- A `@CurrentUserId` annotation in `shared.web`, and its `HandlerMethodArgumentResolver` in
  `identity`, registered through a `WebMvcConfigurer` there. It reads the principal, resolves
  the email against `identity`'s own repository, and hands the controller a `UUID`. `identity`
  is the one module allowed to know how.
- `SavedJobController` and `JobMatchController` take `@CurrentUserId UUID userId`, and their
  services take `UUID userId`. No method in `applications` or `matching` takes an email.
- **Keep each module's answer for a principal with no user behind it.** `applications` says
  404 "User not found"; `matching` says 422 "Fill in your profile", treating no account like no
  profile. The resolver must let each keep its status — for instance by resolving to an empty
  value the controller maps — not replace both with one of its own. Unifying them is a
  behaviour change and is out of scope.
- Delete `UserDirectory` and its implementation in `IdentityDirectory`. Nothing outside
  `identity` needs it once the resolver exists.
- `ProfileDirectory` stays, unchanged. Remove its `// TODO day-10` sentence, which planned
  a replacement that turned out to be the same interface.
- `ProfileController` and `UserController` may use the resolver too, but do not have to: they
  are inside `identity`.

## Out of scope
- Taking `userId` from a JWT claim — Day 12 issues it, Day 13 reads it. Today the principal is
  still the session's email, so the resolver still makes one query.
- Adding profile fields `matching` does not read. The earlier draft listed work mode and
  employment type; nothing uses them.
- Changing any status code, including the two above.

## Tracks

| Track | Owner | Work |
|---|---|---|
| A | | `@CurrentUserId` + resolver in `identity` |
| B | | `applications` and `matching` controllers and services take `UUID userId` |
| C | | Delete `UserDirectory`, remove the `TODO day-10` markers, sweep the helpers |

## Acceptance criteria
- [ ] `UserDirectory.java` no longer exists, and `grep -rn "UserDirectory" --include=*.java .`
      returns nothing.
- [ ] No method under `applications/src/main` or `matching/src/main` has a `String email`
      parameter, and neither module mentions `@AuthenticationPrincipal`.
- [ ] `grep -rn "TODO day-10" --include=*.java .` returns nothing — today it finds two.
- [ ] Day 01–04 tests pass **unedited**.
- [ ] Exactly one statement reads `users` during `POST /api/saved-jobs` and during
      `GET /api/jobs/top-matches` — the same as today. Moving the lookup must not add a second
      one on the way (`StatementCounter`, in `queries/`, landed with Track A and passing before
      the change).

## Verify
```bash
cd backend
./mvnw clean verify
./mvnw -B checkstyle:check
grep -rn "UserDirectory" --include=*.java . || echo "gone"
grep -rn "TODO day-10" --include=*.java . || echo "clean"
grep -rn "String email\|@AuthenticationPrincipal" applications/src/main matching/src/main || echo "clean"
```

## Notes
- This is the change that stops every future service needing a call to `identity` on
  every request. It is worth doing carefully.
- **Spec rewritten on Day 09, before the work, from a read of every remaining spec.** The old
  version was written before Day 07 and did not survive it:
  - **It was built on a class that never existed.** `UserLookup` and `requireUserId(email)`
    ("`SavedJobService` has six") were not in the repository; Day 07's spec change found this
    and left Day 10 to be corrected when it came up.
  - **"`matching` stops using `ProfileRepository`" was already done** by Day 07, through
    `ProfileDirectory`. The `ProfileSkills` interface it planned is that interface with two
    fields `matching` never reads.
  - **Two criteria would have ticked themselves.** `grep "FROM users"` already matches only
    `identity` (and two test-harness files), and `test ! -f .../UserLookup.java` passes for a
    file that was never there.
  - **"Saving a job performs one fewer query" could not be met.** The principal is the email,
    so resolving it at the edge moves the query, it does not remove it. The query goes when the
    principal carries the id, which is Day 13. The criterion now guards the thing that can
    actually go wrong today: the move adding a second lookup.
  - **The two modules' different answers for a missing user** were not mentioned, and a single
    resolver is exactly the place that would quietly flatten them.
