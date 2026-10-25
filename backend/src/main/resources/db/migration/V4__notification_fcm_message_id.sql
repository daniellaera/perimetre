-- FCM's answer to a successful send ("projects/<id>/messages/<uuid>"); null for LogPushSender and failures.
ALTER TABLE notification ADD COLUMN fcm_message_id TEXT;
