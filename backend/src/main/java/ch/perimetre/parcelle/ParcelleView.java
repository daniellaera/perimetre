package ch.perimetre.parcelle;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * A cadastral parcel as served in {@code parcelles} on dossiers: open data only (no owner). {@code lienRf}
 * is SITG's official ge.ch "extrait foncier" link for the parcel.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ParcelleView(String egrid, String commune, int numero, Integer surfaceM2, String lienRf) {
}
