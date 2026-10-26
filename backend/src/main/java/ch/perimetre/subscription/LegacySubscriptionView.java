package ch.perimetre.subscription;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

import ch.perimetre.dossier.Groupe;

/**
 * The legacy /subscriptions shape. {@code platform} is the device's; {@code label} is the address (what the
 * single-subscription app sent as label), or the label when there is no address. The push token is
 * deliberately not echoed back.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record LegacySubscriptionView(
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
