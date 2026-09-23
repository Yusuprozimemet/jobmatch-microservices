# Day 31 — mailer function

**Phase:** 6 · **Depends on:** Day 30 · **Expected PRs:** 2
**Status:** provisional — re-read and revise before starting.

## Goal
Email leaves the request path entirely. `identity-service` never talks to SMTP again.

## In scope
- `functions/mailer`: queue-triggered, consumes password-reset events. **Not
  `user.registered`:** registration sends no email today — `EmailService` has one method,
  `sendPasswordResetEmail` — so a welcome email would be a new email type, which is out of
  scope below.
- Templates move out of `EmailService`; delete it from `identity-service` along with
  `spring-boot-starter-mail`.
- Password reset becomes an event. **The token must still be single-use and expiring** —
  the Day 02 tests pin this and must pass unedited.
- Retries with backoff; dead-letter after a configured count; alert on DLQ depth.
- Idempotent: a duplicate event must not send a second email (dedupe on event id).
- SMTP credentials live only in the function's configuration.

## Out of scope
- New email types. Digests and notifications are a product decision, not this migration.

## Tracks

| Track | Owner | Work |
|---|---|---|
| A | | Function, trigger, templates, deploy pipeline |
| B | | Events from identity, delete `EmailService`, dedupe |

## Acceptance criteria
- [ ] Day 02's password-reset tests pass **unedited**.
- [ ] `identity-service` has no mail dependency (check the pom).
- [ ] Requesting a password reset returns before the email is sent.
- [ ] A duplicate event sends exactly one email.
- [ ] With SMTP down, events retry and eventually deliver; nothing is lost.
- [ ] Login latency is unchanged or better (measure it).

## Verify
```bash
# register a user, confirm a 200 returns before the mail is sent
# stop SMTP, register, restart SMTP, confirm the mail arrives
```

## Notes
- **End of Phase 6.** One always-on container has become a function that costs nothing
  when idle, and registration no longer waits on SMTP.
