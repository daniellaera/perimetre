package ch.perimetre.parcelle;

import java.util.List;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * A cadastral parcel as served in {@code parcelles} on dossiers: open data only (no owner). {@code lienRf}
 * is SITG's official ge.ch "extrait foncier" link for the parcel (where the non-open data is).
 * <p>
 * The first five fields are the original contract and never change. The rest is additive: {@code type} is
 * the parcel's category (privé, DP communal, chemin vicinal...), {@code planRf} the land-registry plan number,
 * {@code lieuDit} the local name, {@code batiments} the buildings on it (the {@code MAX_BATIMENTS} largest on
 * the parcel, {@code nbBatiments} is their real count) and {@code restrictions} the land-use zones covering it.
 * All of them may be null or empty; clients must tolerate their absence.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ParcelleView(String egrid, String commune, int numero, Integer surfaceM2, String lienRf,
        String type, String planRf, String lieuDit, List<BatimentView> batiments, int nbBatiments,
        List<RestrictionView> restrictions) {

    /** A building of the official survey: {@code surfaceSurParcelleM2} is its footprint inside this parcel. */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record BatimentView(long egid, String numero, String destination, Integer surfaceM2,
            Integer surfaceSurParcelleM2, Integer anneeConstruction, List<String> adresses) {
    }

    /**
     * A land-use zone (RDPPF) over the parcel; {@code type} = ZONE (affectation) or ZONE_DEVELOPPEMENT;
     * {@code partPct} = share of the parcel it covers.
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record RestrictionView(String type, String code, String nom, String description, String statut,
            Integer surfaceM2, Integer partPct) {
    }
}
