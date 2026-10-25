package ch.perimetre.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
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
 * Subscription → dev emit → SENT notification through LogPushSender → visible in the events feed.
 * Like the other context tests it runs against the local DB; everything is rolled back afterwards.
 */
@SpringBootTest(properties = "FIREBASE_CREDENTIALS=")
@ActiveProfiles("dev")
@AutoConfigureMockMvc
@Transactional
@ExtendWith(OutputCaptureExtension.class)
class PushPipelineIntegrationTest {

    private static final String DOSSIER_ID = "TEST-PUSH 000001/1";

    @Autowired
    MockMvc mvc;

    @Autowired
    DossierRepository dossiers;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PushSender sender;

    @Test
    void emittedEventIsPushedToMatchingSubscription(CapturedOutput output) throws Exception {
        assertThat(sender).isInstanceOf(LogPushSender.class);
        // ~110 m north of the subscription point.
        dossiers.upsert(List.of(new Dossier(DOSSIER_ID, "APA", "--", "Non renseigné", "pose d'une pergola ",
                "INSTRUCTION", Groupe.DEPOSE, LocalDate.of(2026, 10, 1),
                OffsetDateTime.of(2026, 10, 20, 0, 0, 0, 0, ZoneOffset.UTC), null, 6.1425, 46.1944, List.of())));

        String created = mvc.perform(post("/subscriptions").contentType(MediaType.APPLICATION_JSON).content("""
                        {"push_token": "dev-token-integration", "platform": "ios", "lat": 46.1934, "lng": 6.1425,
                         "radius_m": 300, "categories": ["CHANTIER", "AUTORISE"], "label": "Test"}
                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.radius_m").value(300))
                .andExpect(jsonPath("$.categories.length()").value(2))
                .andExpect(jsonPath("$.push_token").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        UUID subscriptionId = UUID.fromString(JsonPath.read(created, "$.id"));

        mvc.perform(post("/admin/dev/emit").param("id_dossier", DOSSIER_ID).param("new_groupe", "CHANTIER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("GROUPE_CHANGE"))
                .andExpect(jsonPath("$.oldGroupe").value("DEPOSE"));

        String status = jdbc.sql("SELECT status FROM notification WHERE subscription_id = ?")
                .param(subscriptionId)
                .query(String.class)
                .single();
        assertThat(status).isEqualTo("SENT");
        assertThat(output).contains("PUSH (log only)", "Pose d'une pergola à 111 m",
                "Les travaux commencent. Pose d'une pergola", "new_groupe=CHANTIER");

        // Newest first, so the event just emitted leads even when the local DB holds other events nearby.
        mvc.perform(get("/subscriptions/{id}/events", subscriptionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id_dossier").value(DOSSIER_ID))
                .andExpect(jsonPath("$[0].kind").value("GROUPE_CHANGE"))
                .andExpect(jsonPath("$[0].old_groupe").value("DEPOSE"))
                .andExpect(jsonPath("$[0].new_groupe").value("CHANTIER"))
                .andExpect(jsonPath("$[0].groupe").value("DEPOSE"))
                .andExpect(jsonPath("$[0].distance_m").value(closeTo(111.0, 1.0)));
    }

    @Test
    void eventOutsideCategoriesIsNotNotified() throws Exception {
        dossiers.upsert(List.of(new Dossier(DOSSIER_ID, "DD", "VIL", null, "villa", "INSTRUCTION", Groupe.DEPOSE,
                null, OffsetDateTime.of(2026, 10, 20, 0, 0, 0, 0, ZoneOffset.UTC), null, 6.1425, 46.1944,
                List.of())));
        String created = mvc.perform(post("/subscriptions").contentType(MediaType.APPLICATION_JSON).content("""
                        {"push_token": "dev-token-integration", "lat": 46.1934, "lng": 6.1425, "radius_m": 300,
                         "categories": ["REFUSE"]}
                        """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID subscriptionId = UUID.fromString(JsonPath.read(created, "$.id"));

        mvc.perform(post("/admin/dev/emit").param("id_dossier", DOSSIER_ID).param("new_groupe", "CHANTIER"))
                .andExpect(status().isOk());

        assertThat(jdbc.sql("SELECT count(*) FROM notification WHERE subscription_id = ?")
                .param(subscriptionId).query(Long.class).single()).isZero();
    }

    @Test
    void invalidSubscriptionsAreRejected() throws Exception {
        String[] bodies = {
                // outside Geneva (Lausanne)
                """
                {"push_token": "t", "lat": 46.5197, "lng": 6.6323, "radius_m": 300}""",
                // radius out of range
                """
                {"push_token": "t", "lat": 46.1934, "lng": 6.1425, "radius_m": 50}""",
                // TERMINE is not a notifiable category
                """
                {"push_token": "t", "lat": 46.1934, "lng": 6.1425, "radius_m": 300, "categories": ["TERMINE"]}""",
                // missing token
                """
                {"lat": 46.1934, "lng": 6.1425, "radius_m": 300}"""
        };
        for (String body : bodies) {
            mvc.perform(post("/subscriptions").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
    }
}
