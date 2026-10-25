package ch.perimetre.notification;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Picks the push sender: FCM when the env var FIREBASE_CREDENTIALS points to an existing service-account
 * JSON file, otherwise the log-only sender.
 */
@Configuration
class PushConfig {

    private static final Logger log = LoggerFactory.getLogger(PushConfig.class);

    @Bean
    PushSender pushSender(@Value("${FIREBASE_CREDENTIALS:}") String credentials) throws IOException {
        if (credentials.isBlank()) {
            log.info("FIREBASE_CREDENTIALS not set: pushes are only logged (LogPushSender)");
            return new LogPushSender();
        }
        Path path = Path.of(credentials).toAbsolutePath();
        if (!Files.isRegularFile(path)) {
            log.warn("FIREBASE_CREDENTIALS file {} does not exist: pushes are only logged (LogPushSender)", path);
            return new LogPushSender();
        }
        log.info("Sending pushes through FCM with service account {}", path);
        return FcmPushSender.fromServiceAccount(path);
    }
}
