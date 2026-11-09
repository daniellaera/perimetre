package ch.perimetre.parcelle;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import ch.perimetre.dossier.Dossier;
import ch.perimetre.dossier.DossierRepository;
import ch.perimetre.dossier.Groupe;

/**
 * The dossier ↔ parcel spatial join on the local DB (rolled back). Two adjacent test parcels, A and B, share
 * the edge at lng 6.2001; they sit in the lake, so the real parcels never interfere (assertions only look at
 * the TEST parcels anyway). A degree of longitude is ~77 m here, so 1e-7° is ~1 cm.
 */
@SpringBootTest(properties = "FIREBASE_CREDENTIALS=")
@ActiveProfiles("dev")
@Transactional
class ParcelleLinkIntegrationTest {

    private static final double LAT = 46.30005;

    @Autowired
    ParcelleRepository parcelles;

    @Autowired
    DossierLinker linker;

    @Autowired
    DossierRepository dossiers;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void parcels() {
        parcel("TEST:A", 1, 6.2000, 6.2001);
        parcel("TEST:B", 2, 6.2001, 6.2002);
    }

    @Test
    void aDossierInsideOneParcelGetsThatParcel() {
        dossier("TEST-LINK 1/1", 6.20005, LAT);

        parcelles.relinkDossiers(List.of("TEST-LINK 1/1"));

        assertThat(linked("TEST-LINK 1/1")).containsExactly("TEST:A");
    }

    @Test
    void aDossierOnTheSharedEdgeGetsBothParcels() {
        dossier("TEST-LINK 2/1", 6.2001, LAT);

        parcelles.relinkDossiers(List.of("TEST-LINK 2/1"));

        assertThat(linked("TEST-LINK 2/1")).containsExactly("TEST:A", "TEST:B");
    }

    @Test
    void aDossierAFewCentimetresFromTheEdgeGetsBothButMetresAwayOnlyOne() {
        dossier("TEST-LINK 3/1", 6.2001 + 3e-7, LAT);   // ~2 cm into B: the 1 cm rounding of the outlines blurs it
        dossier("TEST-LINK 4/1", 6.2001 + 3e-6, LAT);   // ~23 cm into B: unambiguous

        parcelles.relinkDossiers(List.of("TEST-LINK 3/1", "TEST-LINK 4/1"));

        assertThat(linked("TEST-LINK 3/1")).containsExactly("TEST:A", "TEST:B");
        assertThat(linked("TEST-LINK 4/1")).containsExactly("TEST:B");
    }

    @Test
    void aDossierOutsideEveryParcelHasNone() {
        dossier("TEST-LINK 5/1", 6.20005, LAT + 0.00012);   // ~13 m north of A

        parcelles.relinkDossiers(List.of("TEST-LINK 5/1"));

        assertThat(linked("TEST-LINK 5/1")).isEmpty();
    }

    @Test
    void relinkingReplacesStaleLinks() {
        dossier("TEST-LINK 6/1", 6.20005, LAT);
        parcelles.relinkDossiers(List.of("TEST-LINK 6/1"));
        jdbc.sql("UPDATE dossier SET geom = ST_SetSRID(ST_MakePoint(6.20015, ?), 4326) WHERE id_dossier = ?")
                .params(LAT, "TEST-LINK 6/1").update();

        parcelles.relinkDossiers(List.of("TEST-LINK 6/1"));

        assertThat(linked("TEST-LINK 6/1")).containsExactly("TEST:B");
    }

    @Test
    void aChangedParcelRelinksTheDossiersItAffects() {
        dossier("TEST-LINK 7/1", 6.20005, LAT);
        dossier("TEST-LINK 8/1", 6.20015, LAT);
        parcelles.relinkDossiers(List.of("TEST-LINK 7/1", "TEST-LINK 8/1"));
        // A's outline grows over B's whole area (a merge); B's dossier is now in both parcels.
        jdbc.sql("UPDATE parcelle SET geom = ST_Multi(ST_MakeEnvelope(6.2000, 46.3000, 6.2002, 46.3001, 4326)) "
                + "WHERE id_parcelle = 'TEST:A'").update();

        int relinked = parcelles.relinkAffectedBy(List.of("TEST:A"));

        assertThat(relinked).isGreaterThanOrEqualTo(2);
        assertThat(linked("TEST-LINK 7/1")).containsExactly("TEST:A");
        assertThat(linked("TEST-LINK 8/1")).containsExactly("TEST:A", "TEST:B");
    }

    @Test
    void aParcelThatMovedAwayReleasesItsDossier() {
        dossier("TEST-LINK 9/1", 6.20005, LAT);
        parcelles.relinkDossiers(List.of("TEST-LINK 9/1"));
        jdbc.sql("UPDATE parcelle SET geom = ST_Multi(ST_MakeEnvelope(6.2100, 46.3000, 6.2101, 46.3001, 4326)) "
                + "WHERE id_parcelle = 'TEST:A'").update();

        parcelles.relinkAffectedBy(List.of("TEST:A"));

        assertThat(linked("TEST-LINK 9/1")).isEmpty();
    }

    @Test
    void anUnlinkedDossierIsPickedUpByTheNextParcelRun() {
        dossier("TEST-LINK 10/1", 6.20005, LAT);   // imported, its link step never ran

        parcelles.relinkAffectedBy(List.of("TEST:B"));

        assertThat(linked("TEST-LINK 10/1")).containsExactly("TEST:A");
    }

    @Test
    void theLinkerRelinksFreshlyImportedDossiers() {
        dossier("TEST-LINK 11/1", 6.20015, LAT);

        assertThat(linker.link(List.of("TEST-LINK 11/1"))).isEqualTo(1);

        assertThat(linked("TEST-LINK 11/1")).containsExactly("TEST:B");
    }

    @Test
    void deletingAParcelOrADossierCleansTheLinks() {
        dossier("TEST-LINK 12/1", 6.2001, LAT);
        parcelles.relinkDossiers(List.of("TEST-LINK 12/1"));

        jdbc.sql("DELETE FROM parcelle WHERE id_parcelle = 'TEST:A'").update();
        assertThat(linked("TEST-LINK 12/1")).containsExactly("TEST:B");

        jdbc.sql("DELETE FROM dossier WHERE id_dossier = 'TEST-LINK 12/1'").update();
        assertThat(jdbc.sql("SELECT count(*) FROM dossier_parcelle WHERE id_dossier = 'TEST-LINK 12/1'")
                .query(Long.class).single()).isZero();
    }

    @Test
    void parcelsOfADossierAreServedInOrderWithOnlyOpenFields() {
        dossier("TEST-LINK 13/1", 6.2001, LAT);
        parcelles.relinkDossiers(List.of("TEST-LINK 13/1"));

        List<ParcelleView> served = parcelles.findByDossiers(List.of("TEST-LINK 13/1")).get("TEST-LINK 13/1");

        assertThat(served).extracting(ParcelleView::numero).containsExactly(1, 2);
        assertThat(served.get(0)).isEqualTo(new ParcelleView("CHTEST-1", "Testville", 1, 100, "https://example.test/1"));
        assertThat(ParcelleView.class.getRecordComponents()).extracting(c -> c.getName())
                .containsExactly("egrid", "commune", "numero", "surfaceM2", "lienRf");
    }

    private List<String> linked(String idDossier) {
        return jdbc.sql("SELECT id_parcelle FROM dossier_parcelle WHERE id_dossier = ? AND id_parcelle LIKE 'TEST:%' "
                        + "ORDER BY id_parcelle")
                .param(idDossier).query(String.class).list();
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
