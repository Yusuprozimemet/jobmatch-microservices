# Day 12 — JWT issuance and JWKS

**Phase:** 2 · **Depends on:** Day 11 · **Expected PRs:** 3

## Goal
`identity` can mint and publish verifiable tokens. Nothing consumes them yet — sessions
still work exactly as before.

## In scope
- RSA keypair with a key id, loaded from config. Never generated at startup in prod —
  a restart would invalidate every token.
- Access token, RS256, 15 minutes: `sub` = userId, plus `email`, `iss`, `aud`, `iat`, `exp`.
- Refresh token: opaque random string, **stored hashed**, 30 days, revocable.
  New migration for `identity.refresh_tokens` (user id, token hash, expires at, revoked at).
- `GET /.well-known/jwks.json` — public, no auth, serves the public key only.
- Tokens are issued alongside the existing session and ignored. This day changes no behaviour.

## Out of scope
- Using the token to authenticate — Day 13.
- Google sign-in — Day 14.

## Tracks

| Track | Owner | Work |
|---|---|---|
| A | | Key loading, token minting, claims |
| B | | Refresh token table, hashing, revocation |
| C | | JWKS endpoint + tests |

## Acceptance criteria
- [ ] A minted token verifies against the JWKS response using a standard JWT library.
- [ ] `sub` is the user's UUID, not their email.
- [ ] JWKS never contains the private key (assert this explicitly).
- [ ] Refresh tokens are unreadable in the database — only a hash is stored.
- [ ] A revoked refresh token cannot be redeemed.
- [ ] With no keypair configured, the app fails to start with a clear message — it does
      not silently generate one.
- [ ] All Day 1–4 tests pass **unedited**.

## Verify
```bash
cd backend
# -pl app -am and failIfNoSpecifiedTests=false: without them surefire stops on `shared`,
# which has no matching test, and nothing runs (found on Day 08).
./mvnw clean verify -pl app -am -Dtest='Jwt*,Auth*IT' -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -B checkstyle:check
curl -s localhost:8080/.well-known/jwks.json | grep -c '"d"'   # expect 0
```

## Notes
- Put the keypair in the compose env for local, Key Vault later. Never in git.
- 15 minutes is short on purpose: it caps the damage from a token we cannot revoke.
- **Spec corrected on Day 09, before the work, from a read of every remaining spec.**
  The verify command ran no tests, for the reason Day 08 found: `-Dtest` with no `-pl` fails
  on `shared` before reaching `app`. Check the surefire reports, not only the exit code.
