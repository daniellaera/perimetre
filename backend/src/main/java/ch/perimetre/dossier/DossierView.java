package ch.perimetre.dossier;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import ch.perimetre.parcelle.ParcelleView;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record DossierView(
        String idDossier,
        String typeDossier,
        String typeOperation,
        String operation,
        String description,
        Groupe groupe,
        LocalDate dateDepot,
        OffsetDateTime dateMajSitg,
        List<String> flags,
        String lienSad,
        double lat,
        double lng,
        double distanceM,
        List<ParcelleView> parcelles,
        String lienRegistreFoncier) {

    /**
     * Official ge.ch page to consult the land registry (not a deep link); each parcel has its own
     * {@code lien_rf} extrait foncier link. Present on every dossier, with or without parcels.
     */
    public static final String REGISTRE_FONCIER_URL = "https://www.ge.ch/consulter-registre-foncier";

    public DossierView withParcelles(List<ParcelleView> parcelles) {
        return new DossierView(idDossier, typeDossier, typeOperation, operation, description, groupe, dateDepot,
                dateMajSitg, flags, lienSad, lat, lng, distanceM, parcelles, lienRegistreFoncier);
    }
}
