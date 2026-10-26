package ch.perimetre.notification;

import static ch.perimetre.subscription.SubscriptionInput.MAX_RADIUS_M;
import static ch.perimetre.subscription.SubscriptionInput.MIN_RADIUS_M;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.jayway.jsonpath.JsonPath;

import ch.perimetre.dossier.Dossier;
import ch.perimetre.dossier.DossierRepository;
import ch.perimetre.dossier.Groupe;

/**
 * The matching prefilter (NotificationRepository.MATCHING) must not drop anything a subscription of the largest
 * allowed radius covers, and the radius bounds of the API, the prefilter and the database CHECK must agree.
 * Runs against the local DB like the other context tests; rolled back.
 */
@SpringBootTest(properties = "FIREBASE_CREDENTIALS=")
@ActiveProfiles("dev")
@AutoConfigureMockMvc
@Transactional
class MatchingRadiusIntegrationTest {

    private static final double LAT = 46.1934;
    private static final double LNG = 6.1425;

    @Autowired
    MockMvc mvc;

    @Autowired
    DossierRepository dossiers;

    @Autowired
    JdbcClient jdbc;

    @Test
    void anEventJustInsideTheMaximumRadiusMatches() throws Exception {
        UUID subscription = subscribe(MAX_RADIUS_M);
        dossierAt("TEST-RADIUS 000001/1", MAX_RADIUS_M - 1);
        dossierAt("TEST-RADIUS 000002/1", MAX_RADIUS_M + 1);

        emit("TEST-RADIUS 000001/1");
        emit("TEST-RADIUS 000002/1");

        assertThat(jdbc.sql("""
                        SELECT e.id_dossier FROM notification n JOIN event e ON e.id = n.event_id
                        WHERE n.subscription_id = ?""")
                .param(subscription).query(String.class).list())
                .containsExactly("TEST-RADIUS 000001/1");
    }

    @Test
    void theDatabaseCheckHasTheSameBounds() {
        String check = jdbc.sql("""
                        SELECT pg_get_constraintdef(oid) FROM pg_constraint
                        WHERE conrelid = 'subscription'::regclass AND contype = 'c'
                          AND pg_get_constraintdef(oid) LIKE '%radius_m%'""")
                .query(String.class).single();
        assertThat(check).contains("radius_m >= " + MIN_RADIUS_M, "radius_m <= " + MAX_RADIUS_M);
    }

    @Test
    void theApiRefusesARadiusAboveTheMaximum() throws Exception {
        UUID device = device();
        mvc.perform(post("/devices/{id}/subscriptions", device).contentType(MediaType.APPLICATION_JSON)
                        .content(body(MAX_RADIUS_M + 1)))
                .andExpect(status().isBadRequest());
    }

    /** A dossier exactly {@code metres} north of the subscription (geodesic, as ST_DWithin measures it). */
    private void dossierAt(String id, int metres) {
        double[] lngLat = jdbc.sql("""
                        SELECT ST_X(p::geometry), ST_Y(p::geometry)
                        FROM ST_Project(ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, ?, 0) p""")
                .params(LNG, LAT, metres)
                .query((rs, n) -> new double[] {rs.getDouble(1), rs.getDouble(2)}).single();
        dossiers.upsert(List.of(new Dossier(id, "DD", "VIL", null, "construction d'une villa", "INSTRUCTION",
                Groupe.DEPOSE, null, OffsetDateTime.of(2026, 10, 20, 0, 0, 0, 0, ZoneOffset.UTC), null, lngLat[0],
                lngLat[1], List.of())));
    }

    private void emit(String idDossier) throws Exception {
        mvc.perform(post("/admin/dev/emit").param("id_dossier", idDossier).param("new_groupe", "AUTORISE"))
                .andExpect(status().isOk());
    }

    private UUID subscribe(int radius) throws Exception {
        String created = mvc.perform(post("/devices/{id}/subscriptions", device())
                        .contentType(MediaType.APPLICATION_JSON).content(body(radius)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(created, "$.id"));
    }

    private UUID device() throws Exception {
        String created = mvc.perform(post("/devices").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"push_token\": \"dev-token-radius\", \"platform\": \"ios\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(created, "$.id"));
    }

    private static String body(int radius) {
        return """
                {"lat": %s, "lng": %s, "radius_m": %d}""".formatted(LAT, LNG, radius);
    }
}
