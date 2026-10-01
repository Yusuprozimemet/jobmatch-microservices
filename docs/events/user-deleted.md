# `user.deleted`, version 1

Identity publishes this event when an account has been deleted (Day 26). It is written to
`identity.outbox` in the transaction that deletes the user, so an event exists if and only if
the delete committed. The relay publishes it to the SNS topic `user-deleted`, which delivers it
raw to one SQS queue per consumer: `applications-user-deleted` and `matching-user-deleted`, each
with a dead-letter queue (`<queue>-dlq`) after 5 receives.

## Body

The SQS message body is this JSON object, with exactly these five fields:

| Field | Type | Meaning |
|---|---|---|
| `eventId` | UUID string | The outbox row's id. A republish of the same row keeps it. |
| `type` | string | Always `"user.deleted"`. |
| `version` | integer | Always `1` for this shape. |
| `userId` | UUID string | The deleted account's id. |
| `occurredAt` | ISO-8601 instant string | When the delete committed its outbox row. |

```json
{
  "eventId": "6f1c2a7e-3b0d-4c5e-9a8f-1d2e3f4a5b6c",
  "type": "user.deleted",
  "version": 1,
  "userId": "0b9d8c7a-6e5f-4a3b-2c1d-0e9f8a7b6c5d",
  "occurredAt": "2026-10-02T09:15:30.123456Z"
}
```

No email, no name, nothing else about the person: the account is gone, and a consumer needs only
the id to remove what it holds for it.

## Delivery

At least once. A relay pass that publishes and then fails before it deletes the outbox row
publishes the row again on its next pass, with the same `eventId`. Order between events is not
guaranteed.

## Consumer rules

Every consumer (Day 27 onwards) is held to these:

1. **Idempotent.** Handling the same event twice leaves the same state as handling it once.
2. **A repeat is a success.** An `eventId` already handled, or a `userId` with nothing left to
   remove, is acknowledged (the message deleted), never failed.
3. **An unreadable body goes to the dead-letter queue.** A body that is not this JSON, has
   another `type`, or a `version` the consumer does not know is not acknowledged; after 5
   receives the queue moves it to `<queue>-dlq`. It is never silently dropped.
