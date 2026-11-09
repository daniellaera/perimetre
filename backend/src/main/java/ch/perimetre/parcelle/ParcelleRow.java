package ch.perimetre.parcelle;

import java.time.LocalDate;

/** A parcel ready to upsert; {@code geometryJson} is the GeoJSON geometry (EPSG:4326). */
record ParcelleRow(String idParcelle, String egrid, String commune, int noComm, int noParcelle, Integer surfaceM2,
        LocalDate dateMut, String lienRf, String geometryJson) {
}
