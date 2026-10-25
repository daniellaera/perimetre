package ch.perimetre.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Used when FIREBASE_CREDENTIALS is not configured: logs the payload and reports success. */
final class LogPushSender implements PushSender {

    private static final Logger log = LoggerFactory.getLogger(LogPushSender.class);

    @Override
    public PushResult send(String token, PushMessage message) {
        log.info("PUSH (log only) token={} title=\"{}\" body=\"{}\" data={}",
                abbreviate(token), message.title(), message.body(), message.data());
        return PushResult.sent();
    }

    private static String abbreviate(String token) {
        return token.length() <= 24 ? token : token.substring(0, 24) + "…";
    }
}
