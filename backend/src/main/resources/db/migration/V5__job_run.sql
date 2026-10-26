-- One row per import run (scheduled or POST /admin/import), written by ImportJob: RUNNING at the start,
-- then SUCCESS, FAILED (import failed, nothing written) or DISPATCH_FAILED (import committed, sending
-- the notifications failed). A RUNNING row left by a restart is marked FAILED at the next startup.
CREATE TABLE job_run (
    id            BIGSERIAL PRIMARY KEY,
    job_name      TEXT NOT NULL,        -- import-incremental | import-full
    trigger       TEXT NOT NULL,        -- SCHEDULED | MANUAL
    started_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at   TIMESTAMPTZ,
    status        TEXT NOT NULL CHECK (status IN ('RUNNING', 'SUCCESS', 'FAILED', 'DISPATCH_FAILED')),
    fetched       INT,                  -- features fetched from SITG
    changed       INT,                  -- events detected (NEW + GROUPE_CHANGE)
    notified      INT,                  -- notification rows created (subscription × event)
    pushes        INT,                  -- pushes sent successfully
    fcm_errors    INT,                  -- pushes that failed (invalid token or other)
    error_message TEXT
);

CREATE INDEX job_run_started_at_idx ON job_run (started_at DESC, id DESC);
