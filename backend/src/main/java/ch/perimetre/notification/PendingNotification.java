package ch.perimetre.notification;

import java.util.UUID;

import ch.perimetre.dossier.Groupe;
import ch.perimetre.event.EventKind;

/** A PENDING notification row joined with everything needed to build its push. */
record PendingNotification(
        long notificationId,
        UUID subscriptionId,
        String pushToken,
        long eventId,
        String idDossier,
        EventKind kind,
        Groupe newGroupe,
        String typeDossier,
        String typeOperation,
        String description,
        double distanceM) {
}
