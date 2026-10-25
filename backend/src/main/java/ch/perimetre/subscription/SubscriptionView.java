package ch.perimetre.subscription;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

import ch.perimetre.dossier.Groupe;

/** The push token is deliberately not echoed back. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record SubscriptionView(
        UUID id,
        String platform,
        double lat,
        double lng,
        int radiusM,
        List<Groupe> categories,
        String label,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
