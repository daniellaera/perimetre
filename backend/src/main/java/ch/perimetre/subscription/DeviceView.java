package ch.perimetre.subscription;

import java.time.OffsetDateTime;
import java.util.UUID;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/** The push token is deliberately not echoed back. {@code maxSubscriptions}: the per-device cap. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record DeviceView(
        UUID id,
        String platform,
        int subscriptionCount,
        int maxSubscriptions,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
