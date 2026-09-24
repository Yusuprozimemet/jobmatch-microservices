# Day 39 — A service proves who it is, and a deleted user stays deleted

**Phase:** 3 · **Depends on:** Day 38 · **Expected PRs:** 3
**Status:** provisional — re-read and revise before starting.

The second of the platform step's three days (38–40). Written as an outline from `plan.md`'s
course correction; rewritten against the code, with the spec-auditor, when it is reached.

## Goal
A service calling another's `/internal/**` route carries a token the receiver can verify, and a
service that trusts a user token's `sub` still refuses a user who has been deleted.

## In scope
- **Own key, own JWKS** (the maintainer's choice after Phase 2): each service signs short-lived
  tokens with its own key, `aud=internal`, `iss` its own name, and publishes its key set, as the
  backend has done for users since Day 12. A receiver accepts a caller only from a list of
  issuers and their key-set URLs.
- The monolith accepts these tokens on `/internal/**` and nothing else does: user tokens are
  refused there, service tokens everywhere else. The gateway still routes no `/internal/**`.
- **The deleted-user rule:** a user's access token outlives the user by up to 15 minutes
  (`backend/docs/auth.md`). A service that trusts `sub` without reading `users` must still refuse
  that user, as `contract/SessionWithoutAUserIT` requires of the monolith today (404 on saved
  jobs, 422 on matches). How (asking identity, or a record of deleted ids kept from
  `user.deleted`) is decided when this day is written.

## Out of scope
- The internal endpoints themselves: Day 18. The clients that call them: Day 19.
- Key rotation beyond what Day 12's signing key already supports.

## Tracks
To be set when the day is written.

## Acceptance criteria
To be written when the day is reached, each `new` or `hold`, with the spec-auditor.

## Notes
- `CurrentUserQueriesIT` still says it "expires on Day 13, when the principal carries the user's
  id"; Day 13 kept the email principal on purpose. This day decides what that test becomes.
