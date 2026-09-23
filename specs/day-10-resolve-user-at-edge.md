# Day 10 — Resolve the user once, at the edge

**Phase:** 1 · **Depends on:** Day 07 · **Expected PRs:** 4

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
  a replacement that turned out to be the same interface, and its opening "Temporary, for the
  same reason as {@link UserDirectory}", which points at the class this day deletes.
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
| 0 | | The two tests below, landed first |
| A | | `@CurrentUserId` + resolver in `identity` |
| B | | `applications` and `matching` controllers and services take `UUID userId` |
| C | | Delete `UserDirectory`, remove the `TODO day-10` markers, sweep the helpers |

Track 0 lands two `hold` tests before anything moves. Both pass today and must pass after.

- **`SessionWithoutAUserIT`, in `contract/`.** A logged-in session whose `users` row has been
  deleted. Today `GET /api/saved-jobs`, `GET /api/saved-jobs/stats` and `POST /api/saved-jobs`
  answer 404 "User not found", `GET /api/jobs/top-matches` answers 422 "Fill in your profile",
  and `identity`'s `GET /api/users/me` and `GET /api/profile` answer 404 "User not found".
  The session survives the row being deleted, so the test makes the case with one `DELETE`.
  Nothing pins these answers today, and a single resolver is where they would be flattened.
- **`CurrentUserQueriesIT`, in `queries/`.** One statement mentioning `users` per
  `POST /api/saved-jobs` and per `GET /api/jobs/top-matches`, counted with `StatementCounter`
  (in `support/`). Today it is 1 each: `identity` loads the user by email, with
  `user_credentials` joined. Break it on purpose with a second lookup before trusting it.

## Acceptance criteria
- [ ] **new** — `UserDirectory.java` no longer exists, and `grep -rn "UserDirectory" --include=*.java .`
      returns nothing. Red today: 9 lines in 5 files, one of them `ProfileDirectory`'s Javadoc.
- [ ] **new** — No method under `applications/src/main` or `matching/src/main` has a `String email`
      parameter, and neither module mentions `@AuthenticationPrincipal`. Red today: the grep in
      Verify finds 19 lines in 4 files. It also finds `String email = ...` locals, which go too.
- [ ] **new** — `grep -rn "TODO day-10" --include=*.java .` returns nothing. Red today: two, both
      in `shared.identity`.
- [ ] **hold** — Day 01–04 tests pass **unedited**, as they stand after #57.
- [ ] **hold** — A session with no user behind it gets the answers listed under Track 0: 404
      "User not found" from `applications` and `identity`, 422 "Fill in your profile" from
      `matching`. `SessionWithoutAUserIT`, broken on purpose in its PR.
- [ ] **hold** — Exactly one statement reads `users` during `POST /api/saved-jobs` and during
      `GET /api/jobs/top-matches`, the same as today. `CurrentUserQueriesIT`, broken on purpose
      in its PR with a second lookup.

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
- **Spec corrected on Day 10, before the work.** Every check was run against the code first,
  the first spec to go through #55's rule before its tracks:
  - **The status codes the spec protects had no test.** In scope says each module keeps its
    answer for a principal with no user, and Out of scope forbids changing a status, but no
    Day 01–04 test makes that case. Day 04 covers "no profile" and "job never saved", not "no
    user". Measured with a throwaway test, then written into Track 0.
  - **The query-count test was due to "land with Track A".** A test that must pass before the
    change has to land before it, so it is Track 0, as on Days 08 and 09. `StatementCounter`
    is in `support/`, not `queries/`; the test goes in `queries/`. The count it guards was
    measured, not assumed: 1 statement on each route.
  - **Deleting `UserDirectory` also means editing `ProfileDirectory`'s Javadoc,** which links to
    it. The first criterion's grep would have found it.
  - **The 401s are Spring Security's, not the controllers'.** Removing both controllers'
    `anonymousUser` check left all 22 tests in `SavedJobsIT` and `JobRoutesIT` green, including
    every 401, so an anonymous request never reaches them. The resolver cannot lose a 401, and
    the Day 01–04 tests that pin 401s guard the filter chain, not this day's code.
  - **The four principal-to-email helpers have the same body,** differing only in name and
    comments. One resolver changes nothing for any kind of principal.
  - *Estimate 3 → 4,* for Track 0.
