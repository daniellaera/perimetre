package ch.perimetre.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * On demand only ({@code PERIMETRE_PERF=1 ./gradlew test --tests '*DispatchPerformanceTest' -i}): seeds 10,000
 * synthetic subscriptions on 4,000 devices over Geneva's urban area, plus events for 200 recent dossiers of the
 * local DB, then times the nightly matching and dispatch (LogPushSender) and prints the matching query's plan.
 * Needs the local DB with imported dossiers; everything is rolled back.
 */
@EnabledIfEnvironmentVariable(named = "PERIMETRE_PERF", matches = "1")
@SpringBootTest(properties = {"FIREBASE_CREDENTIALS=", "logging.level.ch.perimetre.notification.LogPushSender=WARN"})
@ActiveProfiles("dev")
@Transactional
class DispatchPerformanceTest {

    private static final int DEVICES = 4_000;
    private static final int SUBSCRIPTIONS = 10_000;
    private static final int EVENTS = 200;

    // CREATE_PENDING's matching part (NotificationRepository), as a SELECT so EXPLAIN ANALYZE writes nothing.
    private static final String MATCHING = """
            SELECT s.id, e.id
            FROM event e
            JOIN dossier d ON d.id_dossier = e.id_dossier
            JOIN subscription s ON ST_DWithin(s.geom::geography, d.geom::geography, 1000)
                               AND ST_DWithin(s.geom::geography, d.geom::geography, s.radius_m)
            WHERE e.id = ANY(CAST(:eventIds AS bigint[]))
              AND e.new_groupe = ANY(s.categories)
            """;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    NotificationDispatcher dispatcher;

    @Test
    void nightlyMatchingAndDispatchOn10kSubscriptions() {
        jdbc.sql("SELECT setseed(0.42)").query().singleRow();
        jdbc.sql("""
                INSERT INTO device (id, push_token, platform)
                SELECT gen_random_uuid(), 'perf-token-' || i, 'ios' FROM generate_series(1, :n) i""")
                .param("n", DEVICES).update();
        // Uniform over lat 46.17–46.24, lng 6.09–6.20; radius 100–1000 m by 50; one in three with 2 categories.
        jdbc.sql("""
                WITH devices AS (SELECT id, row_number() OVER (ORDER BY id) AS n FROM device
                                 WHERE push_token LIKE 'perf-token-%')
                INSERT INTO subscription (device_id, geom, radius_m, categories, label)
                SELECT d.id,
                       ST_SetSRID(ST_MakePoint(6.09 + random() * 0.11, 46.17 + random() * 0.07), 4326),
                       100 + 50 * floor(random() * 19)::int,
                       CASE WHEN i % 3 = 0 THEN '{AUTORISE,CHANTIER}'::text[]
                            ELSE '{DEPOSE,AUTORISE,CHANTIER,RECOURS,REFUSE}'::text[] END,
                       CASE i % 4 WHEN 0 THEN 'Domicile' WHEN 1 THEN 'Travail' ELSE NULL END
                FROM generate_series(1, :n) i
                JOIN devices d ON d.n = 1 + (i % :devices)""")
                .param("n", SUBSCRIPTIONS).param("devices", DEVICES).update();
        List<Long> eventIds = jdbc.sql("""
                INSERT INTO event (id_dossier, kind, old_groupe, new_groupe)
                SELECT id_dossier, 'GROUPE_CHANGE', 'DEPOSE', (ARRAY['AUTORISE','CHANTIER','DEPOSE'])[1 + (rn % 3)]
                FROM (SELECT id_dossier, row_number() OVER () AS rn FROM dossier
                      WHERE geom && ST_MakeEnvelope(6.09, 46.17, 6.20, 46.24, 4326)
                      ORDER BY date_maj_sitg DESC, id_dossier LIMIT :n) recent
                RETURNING id""").param("n", EVENTS).query(Long.class).list();
        jdbc.sql("ANALYZE device").update();
        jdbc.sql("ANALYZE subscription").update();
        jdbc.sql("ANALYZE event").update();

        Long[] ids = eventIds.toArray(Long[]::new);
        List<String> plan = jdbc.sql("EXPLAIN (ANALYZE, BUFFERS) " + MATCHING).param("eventIds", ids)
                .query(String.class).list();
        System.out.println("=== Matching plan (" + SUBSCRIPTIONS + " subscriptions, " + eventIds.size() + " events)");
        plan.forEach(System.out::println);

        long start = System.nanoTime();
        NotificationDispatcher.DispatchResult result = dispatcher.dispatch(eventIds);
        long millis = (System.nanoTime() - start) / 1_000_000;
        long devicesNotified = jdbc.sql("""
                SELECT count(DISTINCT s.device_id) FROM notification n JOIN subscription s ON s.id = n.subscription_id
                WHERE n.event_id = ANY(CAST(:ids AS bigint[]))""").param("ids", ids).query(Long.class).single();
        System.out.printf("=== Dispatch: %d events, %d notifications, %d pushes (%d failed) to %d devices in %d ms%n",
                eventIds.size(), result.notifications(), result.pushes(), result.pushErrors(), devicesNotified, millis);

        assertThat(plan).anyMatch(line -> line.contains("subscription_geog_gist"));
        assertThat(result.pushErrors()).isZero();
        // At most 3 pushes per device and run.
        assertThat((long) result.pushes()).isLessThanOrEqualTo(3 * devicesNotified);
    }
}
