package ch.perimetre.subscription;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** An app installation: its push token and the invalid-token counter shared by its subscriptions. */
@Repository
public class DeviceRepository {

    private static final String VIEW_COLUMNS = """
            d.id, d.platform, d.created_at, d.updated_at,
            (SELECT count(*) FROM subscription s WHERE s.device_id = d.id) AS subscription_count
            """;

    private final JdbcClient jdbc;

    DeviceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** {@code id} null lets the database pick one. */
    UUID create(UUID id, DeviceRequest request) {
        return jdbc.sql("""
                        INSERT INTO device (id, push_token, platform)
                        VALUES (coalesce(:id, gen_random_uuid()), :pushToken, :platform)
                        RETURNING id""")
                .param("id", id)
                .param("pushToken", request.pushToken())
                .param("platform", request.platform())
                .query(UUID.class)
                .single();
    }

    Optional<DeviceView> find(UUID id, int maxSubscriptions) {
        return jdbc.sql("SELECT " + VIEW_COLUMNS + " FROM device d WHERE d.id = :id")
                .param("id", id)
                .query((rs, rowNum) -> mapView(rs, maxSubscriptions))
                .optional();
    }

    /** Locks the device row until the end of the transaction (serialises subscription creation); false if absent. */
    boolean lock(UUID id) {
        return jdbc.sql("SELECT id FROM device WHERE id = :id FOR UPDATE").param("id", id).query(UUID.class)
                .optional().isPresent();
    }

    /** New push token (refresh) and platform; resets the failure counter. False if absent. */
    boolean update(UUID id, DeviceRequest request) {
        return jdbc.sql("""
                        UPDATE device SET push_token = :pushToken, platform = :platform, consecutive_failures = 0,
                                          updated_at = now()
                        WHERE id = :id""")
                .param("id", id)
                .param("pushToken", request.pushToken())
                .param("platform", request.platform())
                .update() > 0;
    }

    /** Deletes the device and, by cascade, its subscriptions and their notifications. */
    public boolean delete(UUID id) {
        return jdbc.sql("DELETE FROM device WHERE id = :id").param("id", id).update() > 0;
    }

    /** Increments and returns the consecutive invalid-token failure count. */
    public int recordTokenFailure(UUID id) {
        return jdbc.sql("UPDATE device SET consecutive_failures = consecutive_failures + 1 WHERE id = :id"
                        + " RETURNING consecutive_failures")
                .param("id", id)
                .query(Integer.class)
                .single();
    }

    public void resetTokenFailures(UUID id) {
        jdbc.sql("UPDATE device SET consecutive_failures = 0 WHERE id = :id AND consecutive_failures > 0")
                .param("id", id)
                .update();
    }

    private static DeviceView mapView(ResultSet rs, int maxSubscriptions) throws SQLException {
        return new DeviceView(
                rs.getObject("id", UUID.class),
                rs.getString("platform"),
                rs.getInt("subscription_count"),
                maxSubscriptions,
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }
}
