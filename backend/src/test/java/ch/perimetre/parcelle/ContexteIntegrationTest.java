package ch.perimetre.parcelle;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Buildings, zones and local names against the local PostGIS, on test parcels in the lake (rolled back).
 * Parcel A = lng 6.2000..6.2002 and B = 6.2002..6.2004, both lat 46.3000..46.3002 (~15 m × 22 m each).
 */
@SpringBootTest(properties = "FIREBASE_CREDENTIALS=")
@ActiveProfiles("dev")
@Transactional
class ContexteIntegrationTest {

    @Autowired
    ContexteRepository repo;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void parcels() {
        parcel("CTX:A", 1, 6.2000, 6.2002);
        parcel("CTX:B", 2, 6.2002, 6.2004);
    }

    @Test
    void aBuildingOnTwoParcelsGetsItsFootprintInEach() {
        // 6.20015..6.20025 straddles the shared edge at 6.2002: ~half in each parcel.
        repo.upsertBatiments(List.of(building(800001, "CHCTX-1,CHCTX-2", 6.20015, 6.20025)));

        repo.relinkBatiments(List.of(800001L));

        Map<String, Integer> byParcel = new java.util.TreeMap<>();
        jdbc.sql("SELECT id_parcelle, surface_sur_parcelle_m2 FROM parcelle_batiment WHERE egid = 800001")
                .query(rs -> { byParcel.put(rs.getString(1), rs.getInt(2)); });
        assertThat(byParcel).containsOnlyKeys("CTX:A", "CTX:B");
        assertThat(byParcel.get("CTX:A")).isEqualTo(byParcel.get("CTX:B")).isBetween(30, 60);
    }

    @Test
    void aBuildingIsLinkedOnlyToTheParcelsItsOfficialListNames() {
        repo.upsertBatiments(List.of(building(800002, "CHCTX-1", 6.20015, 6.20025)));

        repo.relinkBatiments(List.of(800002L));

        assertThat(jdbc.sql("SELECT id_parcelle FROM parcelle_batiment WHERE egid = 800002").query(String.class).list())
                .containsExactly("CTX:A");
    }

    @Test
    void anUnchangedBuildingIsNotReportedAsChanged() {
        var row = building(800003, "CHCTX-1", 6.20005, 6.20010);

        assertThat(repo.upsertBatiments(List.of(row))).containsExactly(800003L);
        assertThat(repo.upsertBatiments(List.of(row))).isEmpty();
        assertThat(repo.upsertBatiments(List.of(new ContexteRows.Batiment(800003, "Testville", "N3", "Dépôt", "5.1",
                120, null, null, null, "CHCTX-1", row.geometryJson())))).containsExactly(800003L);
    }

    @Test
    void relinkingAChangedParcelRebuildsItsBuildingsZonesAndLocalName() {
        repo.upsertBatiments(List.of(building(800004, "CHCTX-1", 6.20005, 6.20010)));
        repo.replaceZones(List.of(zone("5", "Zone 5", 6.1999, 6.2005)));
        repo.replaceLieuxDits(List.of(new ContexteRows.LieuDit("Au Lac", box(6.1990, 6.2010))));

        repo.relinkParcels(List.of("CTX:A", "CTX:B"));

        assertThat(jdbc.sql("SELECT egid FROM parcelle_batiment WHERE id_parcelle = 'CTX:A'").query(Long.class).list())
                .containsExactly(800004L);
        assertThat(repo.detailsOf(List.of("CTX:A")).get("CTX:A").restrictions()).singleElement()
                .satisfies(z -> assertThat(z.partPct()).isEqualTo(100));
        assertThat(jdbc.sql("SELECT lieu_dit FROM parcelle WHERE id_parcelle = 'CTX:B'").query(String.class).single())
                .isEqualTo("Au Lac");
    }

    @Test
    void aZoneOnlyTouchingOrSliveringAParcelIsIgnored() {
        // Zone 5 covers all of A and 0.3% of B (a sliver of 0.1 m): not shown for B; zone X only touches A's edge.
        repo.replaceZones(List.of(zone("5", "Zone 5", 6.1999, 6.2002015), zone("X", "Zone X", 6.1990, 6.2000)));

        repo.relinkZones(List.of("CTX:A", "CTX:B"));

        assertThat(repo.detailsOf(List.of("CTX:A", "CTX:B")).get("CTX:A").restrictions())
                .extracting(ContexteRepository.RestrictionRow::code).containsExactly("5");
        assertThat(repo.detailsOf(List.of("CTX:B")).get("CTX:B").restrictions()).isEmpty();
    }

    @Test
    void aParcelWithASelfIntersectingOutlineDoesNotBreakTheRelink() {
        jdbc.sql("""
                INSERT INTO parcelle (id_parcelle, egrid, commune, no_comm, no_parcelle, geom)
                VALUES ('CTX:BOW', 'CHCTX-3', 'Testville', 999, 3, ST_Multi(ST_GeomFromText(
                  'POLYGON((6.2010 46.3000, 6.2012 46.3002, 6.2012 46.3000, 6.2010 46.3002, 6.2010 46.3000))', 4326)))
                """).update();
        repo.replaceZones(List.of(zone("5", "Zone 5", 6.2009, 6.2013)));
        repo.upsertBatiments(List.of(building(800005, "CHCTX-3", 6.20105, 6.20110)));

        repo.relinkZones(List.of("CTX:BOW"));
        repo.relinkBatiments(List.of(800005L));

        assertThat(repo.detailsOf(List.of("CTX:BOW")).get("CTX:BOW").restrictions()).hasSize(1);
    }

    @Test
    void detailsListTheLargestBuildingsFirstCapAtThirtyAndKeepTheRealCount() {
        List<ContexteRows.Batiment> many = new ArrayList<>();
        for (int i = 0; i < 35; i++) {
            // Growing widths, so that 810034 is the largest and 810000 (cut by the cap) the smallest.
            many.add(building(810000 + i, "CHCTX-1", 6.20001, 6.20001 + (i + 1) * 0.000005));
        }
        repo.upsertBatiments(many);
        repo.relinkBatiments(many.stream().map(ContexteRows.Batiment::egid).toList());
        jdbc.sql("INSERT INTO adresse (idpadr, egid, adresse, postale) VALUES ('CTX1', 810034, 'Rue B 2', true), "
                + "('CTX2', 810034, 'Rue A 1', true)").update();

        ContexteRepository.Details d = repo.detailsOf(List.of("CTX:A")).get("CTX:A");

        assertThat(d.nbBatiments()).isEqualTo(35);
        assertThat(d.batiments()).hasSize(ContexteRepository.MAX_BATIMENTS);
        assertThat(d.batiments().get(0).egid()).isEqualTo(810034L);
        assertThat(d.batiments()).extracting(ContexteRepository.BatimentRow::surfaceSurParcelleM2)
                .isSortedAccordingTo(java.util.Comparator.reverseOrder());
        assertThat(repo.detailsOf(List.of("CTX:A")).get("CTX:A").batiments().stream()
                .filter(b -> b.egid() == 810034).findFirst()).isPresent()
                .get().extracting(ContexteRepository.BatimentRow::adresses).isEqualTo(List.of("Rue A 1", "Rue B 2"));
    }

    @Test
    void aParcelWithNothingHasEmptyDetailsNotNulls() {
        ContexteRepository.Details d = repo.detailsOf(List.of("CTX:B")).get("CTX:B");

        assertThat(d.batiments()).isEmpty();
        assertThat(d.restrictions()).isEmpty();
        assertThat(d.nbBatiments()).isZero();
    }

    private void parcel(String id, int n, double west, double east) {
        jdbc.sql("""
                INSERT INTO parcelle (id_parcelle, egrid, commune, no_comm, no_parcelle, surface_m2, geom)
                VALUES (?, ?, 'Testville', 999, ?, 100, ST_Multi(ST_MakeEnvelope(?, 46.3000, ?, 46.3002, 4326)))""")
                .params(id, "CHCTX-" + n, n, west, east).update();
    }

    private static String box(double west, double east) {
        return "{\"type\":\"Polygon\",\"coordinates\":[[[%s,46.2995],[%s,46.2995],[%s,46.3010],[%s,46.3010],[%s,46.2995]]]}"
                .formatted(west, east, east, west, west);
    }

    private static ContexteRows.Zone zone(String code, String nom, double west, double east) {
        return new ContexteRows.Zone("ZONE", code, nom, "desc " + nom, "Testville", "En vigueur", box(west, east));
    }

    private static ContexteRows.Batiment building(long egid, String egrids, double west, double east) {
        String geom = "{\"type\":\"Polygon\",\"coordinates\":[[[%s,46.30005],[%s,46.30005],[%s,46.30015],[%s,46.30015],[%s,46.30005]]]}"
                .formatted(west, east, east, west, west);
        return new ContexteRows.Batiment(egid, "Testville", "N" + egid, "Hangar", "3.5.2", 100, null, null, null, egrids,
                geom);
    }
}
