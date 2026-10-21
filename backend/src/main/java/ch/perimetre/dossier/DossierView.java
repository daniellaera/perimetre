package ch.perimetre.dossier;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

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
        double distanceM) {
}
