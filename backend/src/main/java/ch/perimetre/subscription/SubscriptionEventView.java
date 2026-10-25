package ch.perimetre.subscription;

import java.time.OffsetDateTime;

import com.fasterxml.jackson.annotation.JsonUnwrapped;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

import ch.perimetre.dossier.DossierView;
import ch.perimetre.dossier.Groupe;
import ch.perimetre.event.EventKind;

/**
 * An event in a subscription's perimeter: the dossier fields of /dossiers (flattened; {@code distance_m}
 * is from the subscription point, {@code groupe} is the dossier's current one) plus the event fields.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record SubscriptionEventView(
        long eventId,
        EventKind kind,
        Groupe oldGroupe,
        Groupe newGroupe,
        OffsetDateTime occurredAt,
        @JsonUnwrapped DossierView dossier) {
}
