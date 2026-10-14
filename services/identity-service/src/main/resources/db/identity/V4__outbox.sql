-- Events waiting for the relay (Day 26). A row is written in the same transaction as
-- the change it reports, and deleted once published to SNS, so a deleted user's id leaves
-- identity then (Day 26 Track C1).
CREATE TABLE outbox (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    type TEXT NOT NULL,
    -- No key to users: the row is written as the user goes, and outlives them until published.
    user_id UUID NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- V3 removed identity_user's default privileges, so nothing is granted on this table.
