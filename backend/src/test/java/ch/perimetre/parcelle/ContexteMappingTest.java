package ch.perimetre.parcelle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import ch.perimetre.sitg.ImportMode;
import tools.jackson.databind.json.JsonMapper;

/** Row mapping of the four context layers (missing and placeholder values) and the shared import rules. */
class ContexteMappingTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String POLY = "{\"type\":\"Polygon\",\"coordinates\":[[[6.1,46.2],[6.1,46.3],[6.2,46.3],[6.1,46.2]]]}";

    private static List<LayerPage.Feature> features(String... features) {
        return JSON.readValue("{\"features\":[" + String.join(",", features) + "]}", LayerPage.class).features();
    }

    private static String feature(String geometry, String properties) {
        return "{\"type\":\"Feature\",\"geometry\":" + geometry + ",\"properties\":" + properties + "}";
    }

    @Test
    void buildingsKeepOpenAttributesAndTreatPlaceholdersAsNull() {
        var rows = BatimentImporter.toRows(features(
                feature(POLY, "{\"EGID\":295154214,\"COMMUNE\":\"Cologny\",\"NO_BATIMENT\":\"A12\",\"DESTINATION\":\"Hangar\","
                        + "\"SURFACE\":1013,\"ANNEE_CONSTRUCTION\":null,\"NIVEAUX_HORSOL\":1,\"DATEDT\":1793059200000,"
                        + "\"EGRID_LISTE\":\"CH1,CH2\",\"NOMBAT\":\"Ecole X\"}"),
                feature(POLY, "{\"EGID\":2,\"NO_BATIMENT\":\"<Nul>\",\"EGRID_LISTE\":null}"),
                feature(POLY, "{\"COMMUNE\":\"no egid\"}"),
                feature("null", "{\"EGID\":3}")));

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).satisfies(r -> {
            assertThat(r.egid()).isEqualTo(295154214L);
            assertThat(r.noBatiment()).isEqualTo("A12");
            assertThat(r.surfaceM2()).isEqualTo(1013);
            assertThat(r.anneeConstruction()).isNull();
            assertThat(r.egridListe()).isEqualTo("CH1,CH2");
            assertThat(r.dateMut()).isEqualTo(LocalDate.of(2026, 10, 27));
        });
        assertThat(rows.get(1).noBatiment()).isNull();
        assertThat(rows.get(1).egridListe()).isNull();
    }

    @Test
    void buildingNamesAreNotRequested() {
        assertThat(BatimentImporter.OUT_FIELDS).doesNotContain("NOMBAT", "PROPRI", "NO_AUTOR");
        assertThat(AdresseImporter.OUT_FIELDS).doesNotContain("PROPRI");
        assertThat(ZoneImporter.OUT_FIELDS).doesNotContain("LIEN_", "PROPRI");
    }

    @Test
    void addressesNeedAnIdAndAStreet() {
        var rows = AdresseImporter.toRows(features(
                feature("null", "{\"IDPADR\":\"1\",\"EGID\":7,\"ADRESSE\":\"Rue de Graman 99A\",\"NO_POSTAL\":1241,"
                        + "\"NOM_NPA\":\"Puplinge\",\"POSTALE\":\"Oui\"}"),
                feature("null", "{\"IDPADR\":\"2\",\"EGID\":null,\"ADRESSE\":\"Chemin X 1\",\"POSTALE\":\"Non\"}"),
                feature("null", "{\"IDPADR\":\"3\"}"),
                feature("null", "{\"ADRESSE\":\"no id\"}")));

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).postale()).isTrue();
        assertThat(rows.get(1).egid()).isNull();
        assertThat(rows.get(1).postale()).isFalse();
    }

    @Test
    void zonesAndLocalNamesNeedAGeometryAndAName() {
        var zones = ZoneImporter.toRows("ZONE", features(
                feature(POLY, "{\"ABRV_ZONE\":\"5\",\"NOM_ZONE\":\"Zone 5\",\"DESC_ZONE\":\"Villas\",\"STATUT_JUR\":\"En vigueur\"}"),
                feature(POLY, "{\"ABRV_ZONE\":\"X\"}"),
                feature("null", "{\"NOM_ZONE\":\"no geometry\"}")));
        var names = LieuDitImporter.toRows(features(
                feature(POLY, "{\"TEXTE\":\"La Couve\"}"), feature(POLY, "{\"TEXTE\":null}"),
                feature("null", "{\"TEXTE\":\"nowhere\"}")));

        assertThat(zones).singleElement().satisfies(z -> assertThat(z.nom()).isEqualTo("Zone 5"));
        assertThat(names).extracting(ContexteRows.LieuDit::texte).containsExactly("La Couve");
    }

    @Test
    void onlyLayersWithADateRunIncrementally() {
        assertThat(LayerImporter.effectiveMode(ImportMode.INCREMENTAL, true, true)).isEqualTo(ImportMode.INCREMENTAL);
        assertThat(LayerImporter.effectiveMode(ImportMode.INCREMENTAL, false, true)).isEqualTo(ImportMode.FULL);
        assertThat(LayerImporter.effectiveMode(ImportMode.INCREMENTAL, true, false)).isEqualTo(ImportMode.FULL);
        assertThat(LayerImporter.effectiveMode(ImportMode.FULL, true, true)).isEqualTo(ImportMode.FULL);
    }

    @Test
    void theCursorIsThreeDaysBackFromMidnightUtc() {
        assertThat(LayerImporter.cursor(Instant.parse("2026-11-09T14:22:10Z")))
                .isEqualTo(Instant.parse("2026-11-06T00:00:00Z"));
    }

    @Test
    void aFullImportThatWouldWipeTheTableIsRefused() {
        assertThatThrownBy(() -> LayerImporter.checkNotShrunk("building", 83_088, 100))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("refused");
        assertThatCode(() -> LayerImporter.checkNotShrunk("building", 83_088, 80_000)).doesNotThrowAnyException();
        assertThatCode(() -> LayerImporter.checkNotShrunk("building", 0, 0)).doesNotThrowAnyException();
    }
}
