package ch.perimetre.dossier;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public record Dossier(
        String idDossier,
        String typeDossier,
        String typeOperation,
        String operation,
        String description,
        String statutSitg,
        Groupe groupe,
        LocalDate dateDepot,
        OffsetDateTime dateMajSitg,
        String lienSad,
        double lng,
        double lat,
        List<String> flags) {
}
