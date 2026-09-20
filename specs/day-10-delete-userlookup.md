# Day 10 — Delete `UserLookup`, resolve identity once at the edge

**Phase:** 1 · **Depends on:** Day 07 · **Expected PRs:** 3

## Goal
Only `identity` reads the `users` table. Everything else receives a `userId` it can trust.

## In scope
- Controllers resolve the authenticated principal to a `userId` **once**, at the edge,
  and pass it down. Services take `UUID userId`, never an email.
- Delete `UserLookup` and every `requireUserId(email)` call
  (`SavedJobService` has six; `JobMatchService` has its own).
- `matching` stops using `ProfileRepository` directly. New `ProfileSkills` interface in
  `shared`: `Optional<ProfileSkills> forUser(UUID userId)` returning skills, preferred city,
  work mode, employment type.
- Remove the `// TODO day-10` marker.

## Out of scope
- Putting `userId` in a JWT claim — Day 12. Today it still comes from the session principal.

## Tracks

| Track | Owner | Work |
|---|---|---|
| A | | `applications`: controllers and services take `userId` |
| B | | `matching`: `ProfileSkills` interface + implementation in `identity` |
| C | | Delete `UserLookup`, sweep remaining call sites |

## Acceptance criteria
- [ ] `UserLookup.java` is deleted.
- [ ] No service outside `identity` takes an email parameter.
- [ ] `grep -rn "FROM users" --include=*.java` matches only inside `identity`.
- [ ] All Day 1–4 tests pass **unedited**.
- [ ] Saving a job performs one fewer query than before.
- [ ] `grep -rn "TODO day-10"` returns nothing.

## Verify
```bash
cd backend && ./mvnw verify
test ! -f identity/src/main/java/**/UserLookup.java && echo "deleted"
```

## Notes
- This is the change that stops every future service needing a call to `identity` on
  every request. It is worth doing carefully.
