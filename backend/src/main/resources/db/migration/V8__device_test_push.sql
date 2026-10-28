-- POST /devices/{id}/test-push sends at most one push per minute per device (DeviceTestPushController): the
-- time of the last one sent, null until the first.
ALTER TABLE device ADD COLUMN last_test_push_at TIMESTAMPTZ;
