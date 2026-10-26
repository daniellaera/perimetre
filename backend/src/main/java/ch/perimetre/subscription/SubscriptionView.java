package ch.perimetre.subscription;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

import ch.perimetre.dossier.Groupe;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record SubscriptionView(
        UUID id,
        UUID deviceId,
        String label,
        String address,
        double lat,
        double lng,
        int radiusM,
        List<Groupe> categories,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
