package ch.perimetre.subscription;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonUnwrapped;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

import ch.perimetre.dossier.DossierView;
import ch.perimetre.dossier.Groupe;
import ch.perimetre.event.EventKind;

/**
 * An event in a subscription's perimeter: the dossier fields of /dossiers (flattened; {@code distance_m}
 * is from the subscription point, {@code groupe} is the dossier's current one) plus the event fields.
 * {@code subscriptionIds}: the subscriptions whose perimeter and categories it matches, nearest first (the
 * device feed lists an event once, {@code distance_m} then being from the nearest of them).
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record SubscriptionEventView(
        long eventId,
        EventKind kind,
        Groupe oldGroupe,
        Groupe newGroupe,
        OffsetDateTime occurredAt,
        List<UUID> subscriptionIds,
        @JsonUnwrapped DossierView dossier) {

    SubscriptionEventView withDossier(DossierView dossier) {
        return new SubscriptionEventView(eventId, kind, oldGroupe, newGroupe, occurredAt, subscriptionIds, dossier);
    }
}
