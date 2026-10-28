package ch.perimetre.notification;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * The app's "Tester les notifications": one generic push to the device's token, so a user can check that alerts
 * reach the phone (and support can ask them to). No authentication, like the rest of /devices. At most one per
 * {@link #INTERVAL} per device: {@code device.last_test_push_at} is claimed by a single conditional UPDATE, so
 * concurrent requests cannot both send. Nothing else is written: an invalid token is reported, not counted.
 */
@RestController
class DeviceTestPushController {

    static final Duration INTERVAL = Duration.ofMinutes(1);
    static final String RATE_LIMITED_CODE = "TEST_PUSH_RATE_LIMITED";

    private static final Logger log = LoggerFactory.getLogger(DeviceTestPushController.class);

    private final JdbcClient jdbc;
    private final PushSender sender;

    DeviceTestPushController(JdbcClient jdbc, PushSender sender) {
        this.jdbc = jdbc;
        this.sender = sender;
    }

    /** {@code fcm_message_id}: null for LogPushSender and failed pushes. */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    record DeviceTestPushResult(PushResult.Status status, String fcmMessageId) {
    }

    /** 200 with the send status, 404 for an unknown device, 429 {@code TEST_PUSH_RATE_LIMITED} within a minute. */
    @PostMapping("/devices/{id}/test-push")
    ResponseEntity<?> send(@PathVariable UUID id) {
        Optional<String> token = claim(id);
        if (token.isEmpty()) {
            if (!exists(id)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "device not found");
            }
            return rateLimited();
        }
        PushResult result;
        try {
            result = sender.send(token.get(), PushMessages.test());
        } catch (RuntimeException e) {
            result = PushResult.failed(e.toString());
        }
        log.info("Device test push to {}: {} {}", id, result.status(),
                result.messageId() != null ? result.messageId() : result.error());
        return ResponseEntity.ok(new DeviceTestPushResult(result.status(), result.messageId()));
    }

    /** The device's push token, after stamping the send; empty when absent or within the interval. */
    private Optional<String> claim(UUID id) {
        return jdbc.sql("""
                        UPDATE device SET last_test_push_at = now()
                        WHERE id = :id AND (last_test_push_at IS NULL
                                            OR last_test_push_at <= now() - make_interval(secs => :seconds))
                        RETURNING push_token""")
                .param("id", id)
                .param("seconds", INTERVAL.toSeconds())
                .query(String.class)
                .optional();
    }

    private boolean exists(UUID id) {
        return jdbc.sql("SELECT 1 FROM device WHERE id = :id").param("id", id).query(Integer.class).optional()
                .isPresent();
    }

    private static ResponseEntity<ProblemDetail> rateLimited() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS,
                "Une notification de test vient d'être envoyée. Réessayez dans une minute.");
        problem.setTitle("Trop de notifications de test");
        problem.setProperty("code", RATE_LIMITED_CODE);
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(INTERVAL.toSeconds()))
                .body(problem);
    }
}
