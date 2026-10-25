package ch.perimetre.notification;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import ch.perimetre.dossier.Groupe;
import ch.perimetre.event.EventKind;

@Repository
class NotificationRepository {

    // geom::geography on both sides matches the subscription_geog_gist / dossier_geog_gist expression indexes.
    private static final String CREATE_PENDING = """
            INSERT INTO notification (subscription_id, event_id, status)
            SELECT s.id, e.id, 'PENDING'
            FROM event e
            JOIN dossier d ON d.id_dossier = e.id_dossier
            JOIN subscription s ON ST_DWithin(s.geom::geography, d.geom::geography, s.radius_m)
            WHERE e.id = ANY(CAST(:eventIds AS bigint[]))
              AND e.new_groupe = ANY(s.categories)
            ON CONFLICT (subscription_id, event_id) DO NOTHING
            """;

    private static final String FIND_PENDING = """
            SELECT n.id AS notification_id, s.id AS subscription_id, s.push_token,
                   e.id AS event_id, e.id_dossier, e.kind, e.new_groupe,
                   d.type_dossier, d.type_operation, d.description,
                   ST_Distance(s.geom::geography, d.geom::geography) AS distance_m
            FROM notification n
            JOIN subscription s ON s.id = n.subscription_id
            JOIN event e ON e.id = n.event_id
            JOIN dossier d ON d.id_dossier = e.id_dossier
            WHERE n.status = 'PENDING'
              AND n.event_id = ANY(CAST(:eventIds AS bigint[]))
            ORDER BY s.id, e.id
            """;

    private final JdbcClient jdbc;

    NotificationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserts a PENDING row for every (matching subscription, event); returns how many were new. */
    int createPending(List<Long> eventIds) {
        return jdbc.sql(CREATE_PENDING).param("eventIds", eventIds.toArray(Long[]::new)).update();
    }

    /** PENDING notifications for these events, grouped by subscription (ordered by subscription, event). */
    List<PendingNotification> findPending(List<Long> eventIds) {
        return jdbc.sql(FIND_PENDING)
                .param("eventIds", eventIds.toArray(Long[]::new))
                .query((rs, rowNum) -> new PendingNotification(
                        rs.getLong("notification_id"),
                        rs.getObject("subscription_id", UUID.class),
                        rs.getString("push_token"),
                        rs.getLong("event_id"),
                        rs.getString("id_dossier"),
                        EventKind.valueOf(rs.getString("kind")),
                        Groupe.valueOf(rs.getString("new_groupe")),
                        rs.getString("type_dossier"),
                        rs.getString("type_operation"),
                        rs.getString("description"),
                        rs.getDouble("distance_m")))
                .list();
    }

    /** A summary push marks all its rows with the same {@code messageId}. */
    void markSent(List<Long> notificationIds, String messageId) {
        jdbc.sql("UPDATE notification SET status = 'SENT', sent_at = now(), error = NULL, fcm_message_id = :messageId"
                        + " WHERE id = ANY(CAST(:ids AS bigint[]))")
                .param("messageId", messageId)
                .param("ids", notificationIds.toArray(Long[]::new))
                .update();
    }

    void markFailed(List<Long> notificationIds, String error) {
        mark(notificationIds, "FAILED", error);
    }

    /** Not attempted, e.g. because an earlier push of the same run found the token invalid. */
    void markSkipped(List<Long> notificationIds, String reason) {
        mark(notificationIds, "SKIPPED", reason);
    }

    private void mark(List<Long> notificationIds, String status, String error) {
        if (notificationIds.isEmpty()) {
            return;
        }
        jdbc.sql("UPDATE notification SET status = :status, error = :error WHERE id = ANY(CAST(:ids AS bigint[]))")
                .param("status", status)
                .param("error", error)
                .param("ids", notificationIds.toArray(Long[]::new))
                .update();
    }
}
