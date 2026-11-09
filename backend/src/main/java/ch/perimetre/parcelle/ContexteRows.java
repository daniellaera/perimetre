package ch.perimetre.parcelle;

import java.time.LocalDate;

/** Rows of the open context tables, ready to write ({@code geometryJson} = GeoJSON, EPSG:4326). */
final class ContexteRows {

    private ContexteRows() {
    }

    record Batiment(long egid, String commune, String noBatiment, String destination, String nomenclature,
            Integer surfaceM2, Integer anneeConstruction, Integer niveauxHorsol, LocalDate dateMut,
            String egridListe, String geometryJson) {
    }

    record Adresse(String idpadr, Long egid, String adresse, Integer noPostal, String npa, boolean postale) {
    }

    record Zone(String source, String code, String nom, String description, String commune, String statut,
            String geometryJson) {
    }

    record LieuDit(String texte, String geometryJson) {
    }
}
