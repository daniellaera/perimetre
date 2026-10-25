CREATE TABLE subscription (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    push_token           TEXT NOT NULL,
    platform             TEXT,
    geom                 geometry(Point, 4326) NOT NULL,
    radius_m             INT NOT NULL CHECK (radius_m BETWEEN 100 AND 1000),
    categories           TEXT[] NOT NULL DEFAULT '{DEPOSE,AUTORISE,CHANTIER,RECOURS,REFUSE}',
    label                TEXT,
    -- Consecutive runs whose push failed with an invalid/unregistered token; deleted at 3.
    consecutive_failures INT NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ DEFAULT now(),
    updated_at           TIMESTAMPTZ DEFAULT now()
);

-- Matched with ST_DWithin(geom::geography, …); keep the expression identical.
CREATE INDEX subscription_geog_gist ON subscription USING GIST ((geom::geography));

CREATE TABLE event (
    id          BIGSERIAL PRIMARY KEY,
    id_dossier  TEXT REFERENCES dossier,
    kind        TEXT NOT NULL, -- NEW | GROUPE_CHANGE
    old_groupe  TEXT,
    new_groupe  TEXT NOT NULL,
    occurred_at TIMESTAMPTZ DEFAULT now()
);

CREATE INDEX event_occurred_at_idx ON event (occurred_at);
CREATE INDEX event_id_dossier_idx ON event (id_dossier);

CREATE TABLE notification (
    id              BIGSERIAL PRIMARY KEY,
    subscription_id UUID REFERENCES subscription ON DELETE CASCADE,
    event_id        BIGINT REFERENCES event,
    status          TEXT NOT NULL, -- PENDING | SENT | FAILED | SKIPPED
    sent_at         TIMESTAMPTZ,
    error           TEXT,
    UNIQUE (subscription_id, event_id)
);

CREATE INDEX notification_event_id_idx ON notification (event_id);

-- false until the first successful import: that import records the baseline and emits no events.
ALTER TABLE sync_state ADD COLUMN bootstrapped BOOLEAN NOT NULL DEFAULT false;
