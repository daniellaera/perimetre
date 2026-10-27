-- Source freshness: the newest date_maj_2 among the features a run fetched (null when it fetched none or
-- failed), and STALE_SOURCE for a run that completed while that date is more than 3 days old, i.e. SITG
-- has stopped publishing (ImportJob).
ALTER TABLE job_run ADD COLUMN max_date_maj_sitg DATE;

ALTER TABLE job_run DROP CONSTRAINT job_run_status_check;
ALTER TABLE job_run ADD CONSTRAINT job_run_status_check
    CHECK (status IN ('RUNNING', 'SUCCESS', 'FAILED', 'DISPATCH_FAILED', 'STALE_SOURCE'));
