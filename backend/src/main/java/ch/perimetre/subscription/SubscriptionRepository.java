package ch.perimetre.subscription;

import java.sql.Array;
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
import ch.perimetre.parcelle.ParcelleRepository;

@Repository
public class SubscriptionRepository {

    private static final String VIEW_COLUMNS = """
            id, device_id, label, address, ST_Y(geom) AS lat, ST_X(geom) AS lng, radius_m, categories,
            created_at, updated_at
            """;

    // clock_timestamp(), not now(): subscriptions created in one transaction keep their order in list().
    private static final String INSERT = """
            INSERT INTO subscription (id, device_id, geom, radius_m, categories, label, address, created_at, updated_at)
            VALUES (coalesce(:id, gen_random_uuid()), :deviceId, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326), :radiusM,
                    CAST(:categories AS text[]), :label, :address, clock_timestamp(), clock_timestamp())
            RETURNING""" + " " + VIEW_COLUMNS;

    private static final String UPDATE = """
            UPDATE subscription SET
                geom = ST_SetSRID(ST_MakePoint(:lng, :lat), 4326),
                radius_m = :radiusM,
                categories = CAST(:categories AS text[]),
                label = :label,
                address = :address,
                updated_at = now()
            WHERE id = :id AND device_id = :deviceId
            RETURNING""" + " " + VIEW_COLUMNS;

    // The single-subscription app sends its address as label and knows nothing of the new label.
    private static final String LEGACY_UPDATE = """
            UPDATE subscription SET
                geom = ST_SetSRID(ST_MakePoint(:lng, :lat), 4326),
                radius_m = :radiusM,
                categories = CAST(:categories AS text[]),
                address = :address,
                updated_at = now()
            WHERE id = :id
            """;

    private static final String LEGACY_FIND = """
            SELECT s.id, d.platform, ST_Y(s.geom) AS lat, ST_X(s.geom) AS lng, s.radius_m, s.categories,
                   coalesce(s.address, s.label) AS label, s.created_at, s.updated_at
            FROM subscription s
            JOIN device d ON d.id = s.device_id
            WHERE s.id = :id
            """;

    // Same dossier columns as DossierRepository.WITHIN; geom::geography matches dossier_geog_gist.
    private static final String EVENTS = """
            SELECT e.id AS event_id, e.kind, e.old_groupe, e.new_groupe, e.occurred_at, ARRAY[s.id] AS subscription_ids,
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

    // Every subscription of the device, one row per event: distance from the nearest matching subscription.
    private static final String DEVICE_EVENTS = """
            WITH matched AS (
                SELECT e.id AS event_id, s.id AS subscription_id,
                       ST_Distance(d.geom::geography, s.geom::geography) AS distance_m
                FROM subscription s
                JOIN dossier d ON ST_DWithin(d.geom::geography, s.geom::geography, s.radius_m)
                JOIN event e ON e.id_dossier = d.id_dossier AND e.new_groupe = ANY(s.categories)
                WHERE s.device_id = :deviceId
            ), per_event AS (
                SELECT event_id, min(distance_m) AS distance_m,
                       array_agg(subscription_id ORDER BY distance_m, subscription_id) AS subscription_ids
                FROM matched
                GROUP BY event_id
            )
            SELECT e.id AS event_id, e.kind, e.old_groupe, e.new_groupe, e.occurred_at, p.subscription_ids,
                   d.id_dossier, d.type_dossier, d.type_operation, d.operation, d.description,
                   d.groupe, d.date_depot, d.date_maj_sitg, d.flags, d.lien_sad,
                   ST_Y(d.geom) AS lat, ST_X(d.geom) AS lng, p.distance_m
            FROM per_event p
            JOIN event e ON e.id = p.event_id
            JOIN dossier d ON d.id_dossier = e.id_dossier
            ORDER BY e.occurred_at DESC, e.id DESC
            LIMIT :limit
            """;

    private final JdbcClient jdbc;
    private final ParcelleRepository parcelles;

    SubscriptionRepository(JdbcClient jdbc, ParcelleRepository parcelles) {
        this.jdbc = jdbc;
        this.parcelles = parcelles;
    }

    /** {@code id} null lets the database pick one. */
    SubscriptionView create(UUID id, UUID deviceId, SubscriptionInput input) {
        return bind(jdbc.sql(INSERT), input)
                .param("id", id)
                .param("deviceId", deviceId)
                .query(SubscriptionRepository::mapView)
                .single();
    }

    List<SubscriptionView> list(UUID deviceId) {
        return jdbc.sql("SELECT " + VIEW_COLUMNS + " FROM subscription WHERE device_id = :deviceId"
                        + " ORDER BY created_at, id")
                .param("deviceId", deviceId)
                .query(SubscriptionRepository::mapView)
                .list();
    }

    int count(UUID deviceId) {
        return jdbc.sql("SELECT count(*) FROM subscription WHERE device_id = :deviceId")
                .param("deviceId", deviceId)
                .query(Integer.class)
                .single();
    }

    Optional<SubscriptionView> find(UUID deviceId, UUID id) {
        return jdbc.sql("SELECT " + VIEW_COLUMNS + " FROM subscription WHERE id = :id AND device_id = :deviceId")
                .param("id", id)
                .param("deviceId", deviceId)
                .query(SubscriptionRepository::mapView)
                .optional();
    }

    /** Replaces every field. */
    Optional<SubscriptionView> update(UUID deviceId, UUID id, SubscriptionInput input) {
        return bind(jdbc.sql(UPDATE), input)
                .param("id", id)
                .param("deviceId", deviceId)
                .query(SubscriptionRepository::mapView)
                .optional();
    }

    boolean delete(UUID deviceId, UUID id) {
        return jdbc.sql("DELETE FROM subscription WHERE id = :id AND device_id = :deviceId")
                .param("id", id)
                .param("deviceId", deviceId)
                .update() > 0;
    }

    Optional<UUID> deviceOf(UUID id) {
        return jdbc.sql("SELECT device_id FROM subscription WHERE id = :id").param("id", id).query(UUID.class)
                .optional();
    }

    Optional<LegacySubscriptionView> findLegacy(UUID id) {
        return jdbc.sql(LEGACY_FIND)
                .param("id", id)
                .query((rs, rowNum) -> new LegacySubscriptionView(
                        rs.getObject("id", UUID.class),
                        rs.getString("platform"),
                        rs.getDouble("lat"),
                        rs.getDouble("lng"),
                        rs.getInt("radius_m"),
                        categories(rs.getArray("categories")),
                        rs.getString("label"),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("updated_at", OffsetDateTime.class)))
                .optional();
    }

    /** Legacy PUT: perimeter fields and address; the label is left alone. False if absent. */
    boolean updateLegacy(UUID id, LegacySubscriptionRequest request) {
        return bind(jdbc.sql(LEGACY_UPDATE), request.toInput()).param("id", id).update() > 0;
    }

    boolean deleteById(UUID id) {
        return jdbc.sql("DELETE FROM subscription WHERE id = :id").param("id", id).update() > 0;
    }

    /** Events in the subscription's radius and categories, newest first. */
    List<SubscriptionEventView> events(UUID id, int limit) {
        return withParcelles(
                jdbc.sql(EVENTS).param("id", id).param("limit", limit).query(SubscriptionRepository::mapEvent).list());
    }

    /** Events matching any subscription of the device, once each, newest first. */
    List<SubscriptionEventView> deviceEvents(UUID deviceId, int limit) {
        return withParcelles(jdbc.sql(DEVICE_EVENTS)
                .param("deviceId", deviceId)
                .param("limit", limit)
                .query(SubscriptionRepository::mapEvent)
                .list());
    }

    /** The events with their dossier's {@code parcelles} filled in (one query for the whole list). */
    private List<SubscriptionEventView> withParcelles(List<SubscriptionEventView> events) {
        var byDossier = parcelles.findByDossiers(events.stream().map(e -> e.dossier().idDossier()).distinct().toList());
        return events.stream()
                .map(e -> e.withDossier(e.dossier().withParcelles(byDossier.getOrDefault(e.dossier().idDossier(), List.of()))))
                .toList();
    }

    private static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec spec, SubscriptionInput input) {
        return spec
                .param("lat", input.lat())
                .param("lng", input.lng())
                .param("radiusM", input.radiusM())
                .param("categories", Categories.toSql(input.categories()))
                .param("label", input.labelOrNull())
                .param("address", input.addressOrNull());
    }

    private static SubscriptionEventView mapEvent(ResultSet rs, int rowNum) throws SQLException {
        return new SubscriptionEventView(
                rs.getLong("event_id"),
                EventKind.valueOf(rs.getString("kind")),
                rs.getString("old_groupe") == null ? null : Groupe.valueOf(rs.getString("old_groupe")),
                Groupe.valueOf(rs.getString("new_groupe")),
                rs.getObject("occurred_at", OffsetDateTime.class),
                List.of((UUID[]) rs.getArray("subscription_ids").getArray()),
                DossierRepository.mapView(rs, rowNum));
    }

    private static SubscriptionView mapView(ResultSet rs, int rowNum) throws SQLException {
        return new SubscriptionView(
                rs.getObject("id", UUID.class),
                rs.getObject("device_id", UUID.class),
                rs.getString("label"),
                rs.getString("address"),
                rs.getDouble("lat"),
                rs.getDouble("lng"),
                rs.getInt("radius_m"),
                categories(rs.getArray("categories")),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    private static List<Groupe> categories(Array array) throws SQLException {
        return Arrays.stream((String[]) array.getArray()).map(Groupe::valueOf).toList();
    }
}
