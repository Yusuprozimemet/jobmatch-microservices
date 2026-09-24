# Day 26 — Event bus and transactional outbox

**Phase:** 5 · **Depends on:** Day 25 · **Expected PRs:** 3
**Status:** provisional — re-read and revise before starting.

## Goal
`identity` publishes events reliably: an event is emitted if and only if the database
change committed.

## In scope
- Message bus in compose (RabbitMQ) and in the deployment.
- `identity.outbox` table. Domain change and outbox row are written in **one transaction**.
- A relay publishes unsent rows and marks them sent. At-least-once delivery.
- Events, versioned, with a written schema:

  | Event | Payload |
  |---|---|
  | `user.registered` | userId, email, occurredAt |
  | `user.deleted` | userId, occurredAt |

- Consumers must be idempotent — document this as a rule, and test it on Day 27.
- Dead-letter queue plus an alert on depth.

## Out of scope
- Consuming the events — Day 27.
- Replacing any synchronous call with an event. Read paths stay HTTP.

## Tracks

| Track | Owner | Work |
|---|---|---|
| A | | Bus in compose and deployment, DLQ, alerting |
| B | | Outbox table, transactional write, relay |
| C | | Event schemas, versioning, publisher tests |

## Acceptance criteria
- [ ] A rolled-back registration publishes nothing.
- [ ] Killing the relay mid-publish loses no event; it is republished on restart.
- [ ] A duplicate delivery is possible by design and documented as such.
- [ ] DLQ depth is on a dashboard with an alert.
- [ ] Event payloads carry a version field.

## Verify
```bash
docker compose up -d --build
# register a user, then inspect the outbox table and the queue
docker compose restart identity-outbox-relay   # no events lost
```

## Notes
- At-least-once, not exactly-once. Every consumer must tolerate seeing an event twice.
  This is the rule that Day 27 depends on.
- From Day 39: once `user.deleted` flows, a record of deleted ids may replace the
  `GET /internal/users/{id}` call services make before acting for a user. Decided here or on
  Day 27, with the call's cost measured.
