package ch.perimetre.notification;

import java.util.UUID;

import ch.perimetre.dossier.Groupe;
import ch.perimetre.event.EventKind;

/**
 * A PENDING notification row joined with everything needed to build its push. {@code label}, {@code address}
 * and {@code deviceSubscriptions} (how many subscriptions the device has) decide how the matched perimeter is
 * named in the push ({@link PushMessages#placeName}).
 */
record PendingNotification(
        long notificationId,
        UUID deviceId,
        UUID subscriptionId,
        String pushToken,
        String label,
        String address,
        int deviceSubscriptions,
        long eventId,
        String idDossier,
        EventKind kind,
        Groupe newGroupe,
        String typeDossier,
        String typeOperation,
        String description,
        double distanceM) {
}
