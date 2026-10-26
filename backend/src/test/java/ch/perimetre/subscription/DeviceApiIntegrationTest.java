package ch.perimetre.subscription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.jayway.jsonpath.JsonPath;

import ch.perimetre.dossier.Dossier;
import ch.perimetre.dossier.DossierRepository;
import ch.perimetre.dossier.Groupe;

/**
 * /devices API, the per-device cap (lowered to 3 here), the legacy /subscriptions API mapped onto devices, and
 * one push per event and device. Runs against the local DB like the other context tests; rolled back.
 */
@SpringBootTest(properties = {"FIREBASE_CREDENTIALS=", "perimetre.subscriptions.max-per-device=3"})
@ActiveProfiles("dev")
@AutoConfigureMockMvc
@Transactional
@ExtendWith(OutputCaptureExtension.class)
class DeviceApiIntegrationTest {

    private static final String DOSSIER_ID = "TEST-DEVICE 000001/1";

    @Autowired
    MockMvc mvc;

    @Autowired
    DossierRepository dossiers;

    @Autowired
    JdbcClient jdbc;

    @Test
    void deviceAndSubscriptionsLifecycle() throws Exception {
        UUID device = createDevice("dev-token-lifecycle");
        mvc.perform(get("/devices/{id}", device))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.platform").value("ios"))
                .andExpect(jsonPath("$.subscription_count").value(0))
                .andExpect(jsonPath("$.max_subscriptions").value(3))
                .andExpect(jsonPath("$.push_token").doesNotExist());

        ResultActions createdHome = createSubscription(device, "Domicile", 46.1934, 6.1425, 300)
                .andExpect(jsonPath("$.device_id").value(device.toString()))
                .andExpect(jsonPath("$.label").value("Domicile"))
                .andExpect(jsonPath("$.address").value("Rue de test 1 1204 Genève"))
                .andExpect(jsonPath("$.categories.length()").value(5));
        UUID home = idOf(createdHome);
        assertThat(createdHome.andReturn().getResponse().getHeader("Location"))
                .isEqualTo("/devices/" + device + "/subscriptions/" + home);
        UUID work = idOf(createSubscription(device, null, 46.2044, 6.1432, 500));

        mvc.perform(get("/devices/{id}/subscriptions", device))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(home.toString()))
                .andExpect(jsonPath("$[1].id").value(work.toString()))
                .andExpect(jsonPath("$[1].label").doesNotExist());

        mvc.perform(put("/devices/{id}/subscriptions/{s}", device, work).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"lat": 46.2044, "lng": 6.1432, "radius_m": 800, "categories": ["CHANTIER"],
                                 "label": " Travail ", "address": "Place de test 2 1204 Genève"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.label").value("Travail"))
                .andExpect(jsonPath("$.radius_m").value(800))
                .andExpect(jsonPath("$.categories[0]").value("CHANTIER"));

        mvc.perform(put("/devices/{id}", device).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"push_token": "dev-token-refreshed", "platform": "ios"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subscription_count").value(2));
        assertThat(token(device)).isEqualTo("dev-token-refreshed");

        // A subscription is only reachable through its own device.
        UUID other = createDevice("dev-token-other");
        mvc.perform(get("/devices/{id}/subscriptions/{s}", other, home)).andExpect(status().isNotFound());
        mvc.perform(delete("/devices/{id}/subscriptions/{s}", other, home)).andExpect(status().isNotFound());

        mvc.perform(delete("/devices/{id}/subscriptions/{s}", device, home)).andExpect(status().isNoContent());
        mvc.perform(get("/devices/{id}/subscriptions/{s}", device, home)).andExpect(status().isNotFound());

        mvc.perform(delete("/devices/{id}", device)).andExpect(status().isNoContent());
        mvc.perform(get("/devices/{id}", device)).andExpect(status().isNotFound());
        assertThat(jdbc.sql("SELECT count(*) FROM subscription WHERE id = ?").param(work).query(Long.class).single())
                .isZero();
        mvc.perform(post("/devices/{id}/subscriptions", device).contentType(MediaType.APPLICATION_JSON)
                        .content(subscriptionBody(null, 46.1934, 6.1425, 300)))
                .andExpect(status().isNotFound());
    }

    @Test
    void theCapAnswers409WithACodeAndAFrenchMessage() throws Exception {
        UUID device = createDevice("dev-token-cap");
        for (int i = 0; i < 3; i++) {
            createSubscription(device, "P" + i, 46.1934, 6.1425, 300);
        }
        mvc.perform(post("/devices/{id}/subscriptions", device).contentType(MediaType.APPLICATION_JSON)
                        .content(subscriptionBody("Un de trop", 46.1934, 6.1425, 300)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SUBSCRIPTION_LIMIT_REACHED"))
                .andExpect(jsonPath("$.max").value(3))
                .andExpect(jsonPath("$.detail").value("Vous avez atteint le maximum de 3 périmètres sur cet appareil. "
                        + "Supprimez-en un pour en ajouter un autre."));

        UUID first = UUID.fromString(JsonPath.read(
                mvc.perform(get("/devices/{id}/subscriptions", device)).andReturn().getResponse().getContentAsString(),
                "$[0].id"));
        mvc.perform(delete("/devices/{id}/subscriptions/{s}", device, first)).andExpect(status().isNoContent());
        createSubscription(device, "De nouveau", 46.1934, 6.1425, 300);
    }

    @Test
    void invalidInputIsRejected() throws Exception {
        mvc.perform(post("/devices").contentType(MediaType.APPLICATION_JSON).content("{\"platform\": \"ios\"}"))
                .andExpect(status().isBadRequest());
        UUID device = createDevice("dev-token-invalid");
        String[] bodies = {
                subscriptionBody("x".repeat(41), 46.1934, 6.1425, 300),
                subscriptionBody(null, 46.5197, 6.6323, 300),
                subscriptionBody(null, 46.1934, 6.1425, 1001),
                """
                {"lat": 46.1934, "lng": 6.1425, "radius_m": 300, "categories": ["IGNORE"]}"""
        };
        for (String body : bodies) {
            mvc.perform(post("/devices/{id}/subscriptions", device).contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());
        }
    }

    /** The first app build: POST, GET, PUT (token refresh), events, DELETE, all on /subscriptions. */
    @Test
    void legacySubscriptionApiKeepsWorkingOnTopOfDevices() throws Exception {
        String created = mvc.perform(post("/subscriptions").contentType(MediaType.APPLICATION_JSON).content("""
                        {"push_token": "dev-token-legacy", "platform": "ios", "lat": 46.1934, "lng": 6.1425,
                         "radius_m": 300, "categories": ["CHANTIER", "AUTORISE"], "label": "Rue de test 1 1204 Genève"}
                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.label").value("Rue de test 1 1204 Genève"))
                .andExpect(jsonPath("$.platform").value("ios"))
                .andExpect(jsonPath("$.push_token").doesNotExist())
                .andExpect(jsonPath("$.device_id").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(JsonPath.read(created, "$.id"));

        // Its device has the same id: that is how the new app adopts it.
        mvc.perform(get("/devices/{id}/subscriptions", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(id.toString()))
                .andExpect(jsonPath("$[0].label").doesNotExist())
                .andExpect(jsonPath("$[0].address").value("Rue de test 1 1204 Genève"));

        mvc.perform(put("/subscriptions/{id}", id).contentType(MediaType.APPLICATION_JSON).content("""
                        {"push_token": "dev-token-legacy-refreshed", "platform": "ios", "lat": 46.1934, "lng": 6.1425,
                         "radius_m": 400, "categories": ["CHANTIER", "AUTORISE"], "label": "Rue de test 1 1204 Genève"}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.radius_m").value(400));
        assertThat(token(id)).isEqualTo("dev-token-legacy-refreshed");

        // A label set by the new app does not leak into the legacy view, which keeps showing the address.
        mvc.perform(put("/devices/{id}/subscriptions/{s}", id, id).contentType(MediaType.APPLICATION_JSON)
                        .content(subscriptionBody("Domicile", 46.1934, 6.1425, 400)))
                .andExpect(status().isOk());
        mvc.perform(get("/subscriptions/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.label").value("Rue de test 1 1204 Genève"));
        mvc.perform(get("/subscriptions/{id}/events", id)).andExpect(status().isOk());

        mvc.perform(delete("/subscriptions/{id}", id)).andExpect(status().isNoContent());
        mvc.perform(get("/subscriptions/{id}", id)).andExpect(status().isNotFound());
        mvc.perform(get("/devices/{id}", id)).andExpect(status().isNotFound());
    }

    @Test
    void oneEventMatchingTwoSubscriptionsOfADeviceIsOnePush(CapturedOutput output) throws Exception {
        // ~110 m north of "Domicile"; "Travail" is ~250 m east of Domicile, its 500 m radius covers it too.
        dossiers.upsert(List.of(new Dossier(DOSSIER_ID, "DD", "VIL", null, "construction d'une villa", "INSTRUCTION",
                Groupe.DEPOSE, null, OffsetDateTime.of(2026, 10, 20, 0, 0, 0, 0, ZoneOffset.UTC), null, 6.1425,
                46.1944, List.of())));
        UUID device = createDevice("dev-token-dedupe");
        UUID home = idOf(createSubscription(device, "Domicile", 46.1934, 6.1425, 300));
        UUID work = idOf(createSubscription(device, "Travail", 46.1934, 6.1458, 500));
        // A second device covering the same dossier gets its own push.
        UUID neighbour = createDevice("dev-token-neighbour");
        createSubscription(neighbour, null, 46.1934, 6.1425, 300);

        // The totals of the run also count whatever subscriptions the local DB holds there: check ours only.
        mvc.perform(post("/admin/dev/emit").param("id_dossier", DOSSIER_ID).param("new_groupe", "AUTORISE"))
                .andExpect(status().isOk());

        assertThat(Pattern.compile("PUSH \\(log only\\) token=dev-token-dedupe ").matcher(output.getOut()).results()
                .count()).isOne();
        assertThat(Pattern.compile("PUSH \\(log only\\) token=dev-token-neighbour ").matcher(output.getOut())
                .results().count()).isOne();
        assertThat(output).contains("title=\"Nouvelle villa à 111 m · Domicile, Travail\"",
                "token=dev-token-neighbour title=\"Nouvelle villa à 111 m\"");
        List<String> statuses = jdbc.sql("""
                        SELECT n.status FROM notification n JOIN subscription s ON s.id = n.subscription_id
                        WHERE s.device_id = ?""")
                .param(device).query(String.class).list();
        assertThat(statuses).containsExactly("SENT", "SENT");

        // The device feed lists the event once, nearest subscription first.
        mvc.perform(get("/devices/{id}/events", device))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id_dossier").value(DOSSIER_ID))
                .andExpect(jsonPath("$[0].new_groupe").value("AUTORISE"))
                .andExpect(jsonPath("$[0].subscription_ids.length()").value(2))
                .andExpect(jsonPath("$[0].subscription_ids[0]").value(home.toString()))
                .andExpect(jsonPath("$[0].subscription_ids[1]").value(work.toString()))
                .andExpect(jsonPath("$[?(@.id_dossier == '" + DOSSIER_ID + "')]", hasSize(1)));
        mvc.perform(get("/devices/{id}/subscriptions/{s}/events", device, work))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id_dossier").value(DOSSIER_ID))
                .andExpect(jsonPath("$[0].subscription_ids[0]").value(work.toString()));
    }

    private UUID createDevice(String token) throws Exception {
        String body = mvc.perform(post("/devices").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"push_token\": \"" + token + "\", \"platform\": \"ios\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private ResultActions createSubscription(UUID device, String label, double lat, double lng, int radius)
            throws Exception {
        return mvc.perform(post("/devices/{id}/subscriptions", device).contentType(MediaType.APPLICATION_JSON)
                        .content(subscriptionBody(label, lat, lng, radius)))
                .andExpect(status().isCreated());
    }

    private static UUID idOf(ResultActions created) throws Exception {
        return UUID.fromString(JsonPath.read(created.andReturn().getResponse().getContentAsString(), "$.id"));
    }

    private static String subscriptionBody(String label, double lat, double lng, int radius) {
        return """
                {"lat": %s, "lng": %s, "radius_m": %d, "label": %s, "address": "Rue de test 1 1204 Genève"}"""
                .formatted(lat, lng, radius, label == null ? "null" : "\"" + label + "\"");
    }

    private String token(UUID device) {
        return jdbc.sql("SELECT push_token FROM device WHERE id = ?").param(device).query(String.class).single();
    }
}
