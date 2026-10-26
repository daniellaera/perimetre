-- One device (app installation) holds any number of subscriptions (perimeters). The push token and the
-- invalid-token counter move from subscription to device.
--
-- Every existing subscription gets its own device with the SAME id, token, platform and counter, so the
-- installed single-subscription app keeps working through the legacy /subscriptions API, and a new app
-- version adopts its stored subscription id as its device id (see CLAUDE.md, "Subscriptions").
CREATE TABLE device (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    push_token           TEXT NOT NULL,
    platform             TEXT,
    -- Consecutive runs whose push failed with an invalid/unregistered token; deleted at 3.
    consecutive_failures INT NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO device (id, push_token, platform, consecutive_failures, created_at, updated_at)
SELECT id, push_token, platform, consecutive_failures, coalesce(created_at, now()), coalesce(updated_at, now())
FROM subscription;

ALTER TABLE subscription ADD COLUMN device_id UUID REFERENCES device ON DELETE CASCADE;
UPDATE subscription SET device_id = id;
ALTER TABLE subscription ALTER COLUMN device_id SET NOT NULL;
CREATE INDEX subscription_device_id_idx ON subscription (device_id);

-- The single-subscription app stored the chosen address in label. label is now the user's name for the
-- perimeter (Domicile, Travail, free text) and the address has its own column.
ALTER TABLE subscription ADD COLUMN address TEXT;
UPDATE subscription SET address = label, label = NULL;

ALTER TABLE subscription
    DROP COLUMN push_token,
    DROP COLUMN platform,
    DROP COLUMN consecutive_failures;
