package ch.perimetre.notification;

import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import ch.perimetre.dossier.Groupe;
import ch.perimetre.event.EventKind;

/**
 * Admin only (ADMIN_TOKEN filter), all profiles: sends one real push to a subscription's device token, to
 * check FCM end to end on prod. The message is built by {@link PushMessages#forEvent} for the dossier the
 * subscriber's {@code /dossiers} list shows first within the radius and categories (so tapping it opens its
 * details sheet), as if it had just reached its current groupe, and sent by the active {@link PushSender}.
 * Nothing is written: no event, no notification row, and an invalid token is reported, not counted.
 */
@RestController
class TestPushController {

    private static final Logger log = LoggerFactory.getLogger(TestPushController.class);

    // Same order as GET /dossiers, which the app uses to look a tapped dossier up.
    private static final String FIND = """
            SELECT s.device_id, s.id AS subscription_id, v.push_token, s.label, s.address,
                   (SELECT count(*) FROM subscription x WHERE x.device_id = s.device_id) AS device_subscriptions,
                   d.id_dossier, d.groupe, d.type_dossier, d.type_operation, d.description,
                   ST_Distance(s.geom::geography, d.geom::geography) AS distance_m
            FROM subscription s
            JOIN device v ON v.id = s.device_id
            JOIN dossier d ON ST_DWithin(d.geom::geography, s.geom::geography, s.radius_m)
                          AND d.groupe = ANY(s.categories)
            WHERE s.id = ?
            ORDER BY d.date_maj_sitg DESC, distance_m ASC, d.id_dossier ASC
            LIMIT 1""";

    private final JdbcClient jdbc;
    private final PushSender sender;

    TestPushController(JdbcClient jdbc, PushSender sender) {
        this.jdbc = jdbc;
        this.sender = sender;
    }

    record TestPushResult(UUID subscriptionId, String idDossier, String title, String body, PushResult.Status status,
            String fcmMessageId, String error) {
    }

    @PostMapping("/admin/test-push")
    TestPushResult send(@RequestParam("subscription_id") UUID subscriptionId) {
        PendingNotification n = find(subscriptionId).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "no subscription " + subscriptionId + " or no dossier in its radius"));
        PushMessage message = PushMessages.forEvent(n);
        PushResult result;
        try {
            result = sender.send(n.pushToken(), message);
        } catch (RuntimeException e) {
            result = PushResult.failed(e.toString());
        }
        log.info("Test push to subscription {} ({}): {} {}", subscriptionId, n.idDossier(), result.status(),
                result.messageId() != null ? result.messageId() : result.error());
        return new TestPushResult(subscriptionId, n.idDossier(), message.title(), message.body(), result.status(),
                result.messageId(), result.error());
    }

    private Optional<PendingNotification> find(UUID subscriptionId) {
        return jdbc.sql(FIND).param(subscriptionId).query((rs, rowNum) -> {
            Groupe groupe = Groupe.valueOf(rs.getString("groupe"));
            return new PendingNotification(0, rs.getObject("device_id", UUID.class),
                    rs.getObject("subscription_id", UUID.class), rs.getString("push_token"), rs.getString("label"),
                    rs.getString("address"), rs.getInt("device_subscriptions"), 0, rs.getString("id_dossier"), groupe == Groupe.DEPOSE ? EventKind.NEW : EventKind.GROUPE_CHANGE,
                    groupe, rs.getString("type_dossier"), rs.getString("type_operation"), rs.getString("description"),
                    rs.getDouble("distance_m"));
        }).optional();
    }
}
