package ch.perimetre.subscription;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * V6 (devices) on a database holding V5-era data: a throwaway database next to the local one (same server as
 * the context tests, {@code DB_URL}), migrated to V5, seeded like prod (an app subscription whose label is its
 * address, a failing token, a null platform, a sent notification), then migrated to the latest version.
 */
class V6MigrationTest {

    private static final String URL = System.getenv().getOrDefault("DB_URL", "jdbc:postgresql://localhost:5433/perimetre");
    private static final String USER = System.getenv().getOrDefault("DB_USER", "perimetre");
    private static final String PASSWORD = System.getenv().getOrDefault("DB_PASSWORD", "perimetre");
    private static final String DATABASE = "perimetre_v6_migration_test";

    private static final UUID APP = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID FAILING = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID NO_PLATFORM = UUID.fromString("22222222-2222-4222-8222-222222222222");

    private static DriverManagerDataSource dataSource;

    @BeforeAll
    static void createDatabase() throws SQLException {
        admin("DROP DATABASE IF EXISTS " + DATABASE);
        admin("CREATE DATABASE " + DATABASE);
        dataSource = new DriverManagerDataSource(URL.substring(0, URL.lastIndexOf('/') + 1) + DATABASE, USER, PASSWORD);
    }

    @AfterAll
    static void dropDatabase() throws SQLException {
        admin("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)");
    }

    @Test
    void existingSubscriptionsKeepTheirIdsAndTokensOnTheirOwnDevice() {
        flyway("5").migrate();
        JdbcClient jdbc = JdbcClient.create(dataSource);
        jdbc.sql("""
                INSERT INTO dossier (id_dossier, type_dossier, groupe, date_maj_sitg, geom)
                VALUES ('DD 1/1', 'DD', 'DEPOSE', now(), ST_SetSRID(ST_MakePoint(6.1484, 46.1950), 4326))""").update();
        jdbc.sql("INSERT INTO event (id_dossier, kind, new_groupe) VALUES ('DD 1/1', 'NEW', 'DEPOSE')").update();
        jdbc.sql("""
                INSERT INTO subscription (id, push_token, platform, geom, radius_m, categories, label,
                                          consecutive_failures, created_at, updated_at)
                VALUES (:app, 'fcm-token-app', 'ios', ST_SetSRID(ST_MakePoint(6.1484, 46.1946), 4326), 500,
                        '{DEPOSE,AUTORISE,CHANTIER,RECOURS,REFUSE}', 'Rue du Rhône 1 1204 Genève', 0,
                        '2026-10-26 09:30+00', '2026-10-26 09:31+00'),
                       (:failing, 'stale-token', 'ios', ST_SetSRID(ST_MakePoint(6.14, 46.20), 4326), 200,
                        '{CHANTIER}', 'Quai du Mont-Blanc 1 1201 Genève', 2, now(), now()),
                       (:noPlatform, 'dev-token-x', NULL, ST_SetSRID(ST_MakePoint(6.12, 46.18), 4326), 1000,
                        '{DEPOSE,REFUSE}', NULL, 0, now(), now())""")
                .param("app", APP).param("failing", FAILING).param("noPlatform", NO_PLATFORM)
                .update();
        jdbc.sql("""
                INSERT INTO notification (subscription_id, event_id, status, sent_at, fcm_message_id)
                SELECT :app, id, 'SENT', now(), 'projects/p/messages/1' FROM event""").param("app", APP).update();

        flyway(null).migrate();

        assertThat(jdbc.sql("SELECT max(version::int) FROM flyway_schema_history").query(Integer.class).single())
                .isGreaterThanOrEqualTo(6);
        List<Map<String, Object>> rows = jdbc.sql("""
                SELECT s.id, s.device_id, s.label, s.address, s.radius_m, array_to_string(s.categories, ',') AS categories,
                       ST_X(s.geom) AS lng, extract(epoch FROM s.created_at)::bigint AS created_at,
                       d.push_token, d.platform, d.consecutive_failures
                FROM subscription s JOIN device d ON d.id = s.device_id
                ORDER BY s.id""").query().listOfRows();
        assertThat(rows).hasSize(3);
        assertThat(rows).allSatisfy(row -> assertThat(row.get("device_id")).isEqualTo(row.get("id")));
        assertThat(rows).allSatisfy(row -> assertThat(row.get("label")).isNull());

        Map<String, Object> failing = rows.get(0);
        assertThat(failing).containsEntry("id", FAILING).containsEntry("push_token", "stale-token")
                .containsEntry("consecutive_failures", 2).containsEntry("categories", "CHANTIER")
                .containsEntry("address", "Quai du Mont-Blanc 1 1201 Genève");
        Map<String, Object> noPlatform = rows.get(1);
        assertThat(noPlatform).containsEntry("id", NO_PLATFORM).containsEntry("platform", null)
                .containsEntry("address", null).containsEntry("radius_m", 1000);
        Map<String, Object> app = rows.get(2);
        assertThat(app).containsEntry("id", APP).containsEntry("push_token", "fcm-token-app")
                .containsEntry("platform", "ios").containsEntry("address", "Rue du Rhône 1 1204 Genève")
                .containsEntry("created_at", 1_793_007_000L);
        assertThat((Double) app.get("lng")).isEqualTo(6.1484);

        assertThat(jdbc.sql("SELECT count(*) FROM notification WHERE subscription_id = :app AND status = 'SENT'")
                .param("app", APP).query(Long.class).single()).isOne();
        // Deleting a device cascades to its subscriptions and their notifications.
        jdbc.sql("DELETE FROM device WHERE id = :app").param("app", APP).update();
        assertThat(jdbc.sql("SELECT count(*) FROM notification").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM subscription").query(Long.class).single()).isEqualTo(2);
    }

    private static Flyway flyway(String target) {
        var config = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration");
        if (target != null) {
            config.target(target);
        }
        return config.load();
    }

    private static void admin(String sql) throws SQLException {
        try (Connection c = DriverManager.getConnection(URL, USER, PASSWORD); Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }
}
