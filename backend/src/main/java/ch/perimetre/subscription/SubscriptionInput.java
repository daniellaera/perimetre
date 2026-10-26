package ch.perimetre.subscription;

import java.util.List;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

import ch.perimetre.dossier.Groupe;

/**
 * Body of POST and PUT /devices/{id}/subscriptions. {@code label} is the user's name for the perimeter
 * (Domicile, Travail, free text), {@code address} the address it was placed at; both optional.
 * {@code categories} defaults to all five notifiable groupes.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record SubscriptionInput(
        // Canton of Geneva bounding box (with a small margin).
        @NotNull @DecimalMin("46.12") @DecimalMax("46.38") Double lat,
        @NotNull @DecimalMin("5.94") @DecimalMax("6.32") Double lng,
        @NotNull @Min(MIN_RADIUS_M) @Max(MAX_RADIUS_M) Integer radiusM,
        @Size(min = 1, max = 5) List<@NotNull Groupe> categories,
        @Size(max = 40) String label,
        @Size(max = 200) String address) {

    /**
     * Radius bounds in metres, the single source for request validation (here and in the legacy request) and
     * the matching prefilter ({@code NotificationRepository}). The CHECK on {@code subscription.radius_m} must
     * say the same: {@code MatchingRadiusIntegrationTest} fails when it does not.
     */
    public static final int MIN_RADIUS_M = 100;
    public static final int MAX_RADIUS_M = 1000;

    @AssertTrue(message = Categories.INVALID_MESSAGE)
    boolean isCategoriesValid() {
        return Categories.isValid(categories);
    }

    /** Blank strings are stored as null. */
    String labelOrNull() {
        return label == null || label.isBlank() ? null : label.strip();
    }

    String addressOrNull() {
        return address == null || address.isBlank() ? null : address.strip();
    }
}
