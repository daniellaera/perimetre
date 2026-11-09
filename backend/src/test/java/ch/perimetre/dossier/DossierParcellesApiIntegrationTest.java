package ch.perimetre.dossier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
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

import ch.perimetre.parcelle.DossierLinker;

/**
 * {@code parcelles} on /dossiers and on the device feed: added to the response, every existing field kept.
 * Two adjacent test parcels in the lake (see ParcelleLinkIntegrationTest); local DB, rolled back.
 */
@SpringBootTest(properties = "FIREBASE_CREDENTIALS=")
@ActiveProfiles("dev")
@AutoConfigureMockMvc
@Transactional
class DossierParcellesApiIntegrationTest {

    private static final double LAT = 46.30005;
    private static final double LNG = 6.2001;   // the edge shared by the two parcels

    @Autowired
    MockMvc mvc;

    @Autowired
    DossierRepository dossiers;

    @Autowired
    DossierLinker linker;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void parcelsAndDossiers() {
        parcel("TEST:A", 1, 6.2000, 6.2001);
        parcel("TEST:B", 2, 6.2001, 6.2002);
        dossier("TEST-API 1/1", LNG, LAT);                 // on the shared edge: both parcels
        dossier("TEST-API 2/1", 6.20005, LAT + 0.0003);    // ~33 m north of the parcels: none
        linker.link(List.of("TEST-API 1/1", "TEST-API 2/1"));
    }

    @Test
    void dossiersCarryTheirParcels() throws Exception {
        String json = dossiersAround();

        List<Map<String, Object>> parcelles = JsonPath.read(json, "$[?(@.id_dossier=='TEST-API 1/1')].parcelles[*]");
        assertThat(parcelles).hasSize(2);
        assertThat(parcelles.get(0)).containsOnlyKeys("egrid", "commune", "numero", "surface_m2", "lien_rf")
                .containsEntry("egrid", "CHTEST-1").containsEntry("commune", "Testville")
                .containsEntry("numero", 1).containsEntry("surface_m2", 100)
                .containsEntry("lien_rf", "https://example.test/1");
        assertThat(parcelles.get(1)).containsEntry("numero", 2);
    }

    @Test
    void aDossierWithoutParcelHasAnEmptyArrayNotNull() throws Exception {
        List<List<Object>> none = JsonPath.read(dossiersAround(), "$[?(@.id_dossier=='TEST-API 2/1')].parcelles");

        assertThat(none).singleElement().satisfies(parcelles -> assertThat(parcelles).isEmpty());
    }

    @Test
    void everyDossierExposesTheOfficialRegistryPortalLink() throws Exception {
        List<String> links = JsonPath.read(dossiersAround(), "$[*].lien_registre_foncier");

        assertThat(links).hasSizeGreaterThanOrEqualTo(2).containsOnly("https://www.ge.ch/consulter-registre-foncier");
    }

    @Test
    void existingFieldsAreUnchanged() throws Exception {
        List<Map<String, Object>> found = JsonPath.read(dossiersAround(), "$[?(@.id_dossier=='TEST-API 1/1')]");

        assertThat(found).hasSize(1);
        Map<String, Object> dossier = found.get(0);
        assertThat(dossier).containsKeys("id_dossier", "type_dossier", "type_operation", "operation", "description",
                "groupe", "date_depot", "date_maj_sitg", "flags", "lien_sad", "lat", "lng", "distance_m");
        assertThat(dossier).containsEntry("groupe", "DEPOSE").containsEntry("type_dossier", "DD");
    }

    @Test
    void noOwnerOrOtherRegistryDataIsStoredOrExposed() throws Exception {
        String json = dossiersAround().toLowerCase();

        assertThat(json).doesNotContain("proprio", "proprietaire", "propriétaire", "owner");
        assertThat(jdbc.sql("""
                        SELECT column_name FROM information_schema.columns
                        WHERE table_name IN ('parcelle', 'dossier_parcelle')""")
                .query(String.class).list())
                .noneMatch(c -> c.contains("propri") || c.contains("owner") || c.contains("nom"));
    }

    @Test
    void theDeviceFeedCarriesParcelsToo() throws Exception {
        UUID device = UUID.fromString(JsonPath.read(mvc.perform(post("/devices").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"push_token\": \"dev-token-parcelles\", \"platform\": \"ios\"}"))
                .andReturn().getResponse().getContentAsString(), "$.id"));
        mvc.perform(post("/devices/{id}/subscriptions", device).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lat\": %s, \"lng\": %s, \"radius_m\": 200}".formatted(LAT, LNG)))
                .andExpect(status().isCreated());
        mvc.perform(post("/admin/dev/emit").param("id_dossier", "TEST-API 1/1").param("new_groupe", "AUTORISE"))
                .andExpect(status().isOk());

        String feed = mvc.perform(get("/devices/{id}/events", device)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<Map<String, Object>> parcelles = JsonPath.read(feed, "$[?(@.id_dossier=='TEST-API 1/1')].parcelles[*]");
        assertThat(parcelles).extracting(p -> (Integer) p.get("numero")).containsExactly(1, 2);
        List<String> portal = JsonPath.read(feed, "$[?(@.id_dossier=='TEST-API 1/1')].lien_registre_foncier");
        assertThat(portal).containsExactly("https://www.ge.ch/consulter-registre-foncier");
    }

    private String dossiersAround() throws Exception {
        return mvc.perform(get("/dossiers").param("lat", String.valueOf(LAT)).param("lng", String.valueOf(LNG))
                        .param("radius", "200"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private void parcel(String id, int number, double west, double east) {
        jdbc.sql("""
                INSERT INTO parcelle (id_parcelle, egrid, commune, no_comm, no_parcelle, surface_m2, lien_rf, geom)
                VALUES (?, ?, 'Testville', 999, ?, 100, ?, ST_Multi(ST_MakeEnvelope(?, 46.3000, ?, 46.3001, 4326)))""")
                .params(id, "CHTEST-" + number, number, "https://example.test/" + number, west, east).update();
    }

    private void dossier(String id, double lng, double lat) {
        dossiers.upsert(List.of(new Dossier(id, "DD", "VIL", null, "test", "INSTRUCTION", Groupe.DEPOSE, null,
                OffsetDateTime.of(2026, 10, 20, 0, 0, 0, 0, ZoneOffset.UTC), null, lng, lat, List.of())));
    }
}
