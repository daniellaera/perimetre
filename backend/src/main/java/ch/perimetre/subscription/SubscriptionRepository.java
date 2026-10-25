package ch.perimetre.subscription;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import ch.perimetre.dossier.DossierRepository;
import ch.perimetre.dossier.Groupe;
import ch.perimetre.event.EventKind;

@Repository
public class SubscriptionRepository {

    private static final String VIEW_COLUMNS = """
            id, platform, ST_Y(geom) AS lat, ST_X(geom) AS lng, radius_m, categories, label, created_at, updated_at
            """;

    private static final String INSERT = """
            INSERT INTO subscription (push_token, platform, geom, radius_m, categories, label)
            VALUES (:pushToken, :platform, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326), :radiusM,
                    CAST(:categories AS text[]), :label)
            RETURNING""" + " " + VIEW_COLUMNS;

    private static final String UPDATE = """
            UPDATE subscription SET
                push_token = :pushToken,
                platform = :platform,
                geom = ST_SetSRID(ST_MakePoint(:lng, :lat), 4326),
                radius_m = :radiusM,
                categories = CAST(:categories AS text[]),
                label = :label,
                consecutive_failures = 0,
                updated_at = now()
            WHERE id = :id
            RETURNING""" + " " + VIEW_COLUMNS;

    // Same dossier columns as DossierRepository.WITHIN; geom::geography matches dossier_geog_gist.
    private static final String EVENTS = """
            SELECT e.id AS event_id, e.kind, e.old_groupe, e.new_groupe, e.occurred_at,
                   d.id_dossier, d.type_dossier, d.type_operation, d.operation, d.description,
                   d.groupe, d.date_depot, d.date_maj_sitg, d.flags, d.lien_sad,
                   ST_Y(d.geom) AS lat, ST_X(d.geom) AS lng,
                   ST_Distance(d.geom::geography, s.geom::geography) AS distance_m
            FROM subscription s
            JOIN dossier d ON ST_DWithin(d.geom::geography, s.geom::geography, s.radius_m)
            JOIN event e ON e.id_dossier = d.id_dossier
            WHERE s.id = :id
              AND e.new_groupe = ANY(s.categories)
            ORDER BY e.occurred_at DESC, e.id DESC
            LIMIT :limit
            """;

    private final JdbcClient jdbc;

    SubscriptionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    SubscriptionView create(SubscriptionRequest request) {
        return bind(jdbc.sql(INSERT), request).query(SubscriptionRepository::mapView).single();
    }

    Optional<SubscriptionView> find(UUID id) {
        return jdbc.sql("SELECT " + VIEW_COLUMNS + " FROM subscription WHERE id = :id")
                .param("id", id)
                .query(SubscriptionRepository::mapView)
                .optional();
    }

    /** Replaces every field (the push token may have been refreshed) and resets the failure counter. */
    Optional<SubscriptionView> update(UUID id, SubscriptionRequest request) {
        return bind(jdbc.sql(UPDATE), request).param("id", id).query(SubscriptionRepository::mapView).optional();
    }

    public boolean delete(UUID id) {
        return jdbc.sql("DELETE FROM subscription WHERE id = :id").param("id", id).update() > 0;
    }

    /** Events in the subscription's radius and categories, newest first. */
    List<SubscriptionEventView> events(UUID id, int limit) {
        return jdbc.sql(EVENTS)
                .param("id", id)
                .param("limit", limit)
                .query((rs, rowNum) -> new SubscriptionEventView(
                        rs.getLong("event_id"),
                        EventKind.valueOf(rs.getString("kind")),
                        rs.getString("old_groupe") == null ? null : Groupe.valueOf(rs.getString("old_groupe")),
                        Groupe.valueOf(rs.getString("new_groupe")),
                        rs.getObject("occurred_at", OffsetDateTime.class),
                        DossierRepository.mapView(rs, rowNum)))
                .list();
    }

    /** Increments and returns the consecutive invalid-token failure count. */
    public int recordTokenFailure(UUID id) {
        return jdbc.sql("UPDATE subscription SET consecutive_failures = consecutive_failures + 1 WHERE id = :id"
                        + " RETURNING consecutive_failures")
                .param("id", id)
                .query(Integer.class)
                .single();
    }

    public void resetTokenFailures(UUID id) {
        jdbc.sql("UPDATE subscription SET consecutive_failures = 0 WHERE id = :id AND consecutive_failures > 0")
                .param("id", id)
                .update();
    }

    private static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec spec, SubscriptionRequest request) {
        return spec
                .param("pushToken", request.pushToken())
                .param("platform", request.platform())
                .param("lat", request.lat())
                .param("lng", request.lng())
                .param("radiusM", request.radiusM())
                .param("categories", request.categoriesOrDefault().stream().map(Groupe::name).toArray(String[]::new))
                .param("label", request.label());
    }

    private static SubscriptionView mapView(ResultSet rs, int rowNum) throws SQLException {
        return new SubscriptionView(
                rs.getObject("id", UUID.class),
                rs.getString("platform"),
                rs.getDouble("lat"),
                rs.getDouble("lng"),
                rs.getInt("radius_m"),
                Arrays.stream((String[]) rs.getArray("categories").getArray()).map(Groupe::valueOf).toList(),
                rs.getString("label"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }
}
