package ch.perimetre.subscription;

import java.util.List;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

import ch.perimetre.dossier.Groupe;

/**
 * Body of the legacy POST and PUT /subscriptions (single-subscription app, 2026-10). The app sends the chosen
 * address as {@code label}: it is stored in {@code subscription.address}.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record LegacySubscriptionRequest(
        @NotBlank @Size(max = 4096) String pushToken,
        @Pattern(regexp = "ios|android") String platform,
        // Canton of Geneva bounding box (with a small margin).
        @NotNull @DecimalMin("46.12") @DecimalMax("46.38") Double lat,
        @NotNull @DecimalMin("5.94") @DecimalMax("6.32") Double lng,
        @NotNull @Min(100) @Max(1000) Integer radiusM,
        @Size(min = 1, max = 5) List<@NotNull Groupe> categories,
        @Size(max = 200) String label) {

    @AssertTrue(message = Categories.INVALID_MESSAGE)
    boolean isCategoriesValid() {
        return Categories.isValid(categories);
    }

    SubscriptionInput toInput() {
        return new SubscriptionInput(lat, lng, radiusM, categories, null, label);
    }

    DeviceRequest toDevice() {
        return new DeviceRequest(pushToken, platform);
    }
}
