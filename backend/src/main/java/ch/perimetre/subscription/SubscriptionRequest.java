package ch.perimetre.subscription;

import java.util.List;
import java.util.Set;

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

/** Body of POST and PUT /subscriptions. {@code categories} defaults to all five notifiable groupes. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record SubscriptionRequest(
        @NotBlank @Size(max = 4096) String pushToken,
        @Pattern(regexp = "ios|android") String platform,
        // Canton of Geneva bounding box (with a small margin).
        @NotNull @DecimalMin("46.12") @DecimalMax("46.38") Double lat,
        @NotNull @DecimalMin("5.94") @DecimalMax("6.32") Double lng,
        @NotNull @Min(100) @Max(1000) Integer radiusM,
        @Size(min = 1, max = 5) List<@NotNull Groupe> categories,
        @Size(max = 200) String label) {

    public static final List<Groupe> NOTIFIABLE =
            List.of(Groupe.DEPOSE, Groupe.AUTORISE, Groupe.CHANTIER, Groupe.RECOURS, Groupe.REFUSE);

    /** Requested categories, de-duplicated in canonical order, or all of them when absent. */
    public List<Groupe> categoriesOrDefault() {
        if (categories == null) {
            return NOTIFIABLE;
        }
        Set<Groupe> requested = Set.copyOf(categories);
        return NOTIFIABLE.stream().filter(requested::contains).toList();
    }

    @AssertTrue(message = "categories must be among DEPOSE, AUTORISE, CHANTIER, RECOURS, REFUSE")
    boolean isCategoriesValid() {
        return categories == null || NOTIFIABLE.containsAll(categories);
    }
}
