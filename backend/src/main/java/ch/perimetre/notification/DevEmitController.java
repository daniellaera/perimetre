package ch.perimetre.notification;

import java.util.List;
import java.util.Locale;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import ch.perimetre.dossier.DossierRepository;
import ch.perimetre.dossier.Groupe;
import ch.perimetre.event.EventKind;
import ch.perimetre.event.EventRepository;
import ch.perimetre.event.NewEvent;
import ch.perimetre.subscription.SubscriptionRequest;

/**
 * Dev profile only: fabricates an event for an existing dossier and dispatches it, to exercise the push
 * pipeline without waiting for SITG. The dossier row itself is not modified.
 */
@Profile("dev")
@RestController
class DevEmitController {

    private final DossierRepository dossiers;
    private final EventRepository events;
    private final NotificationDispatcher dispatcher;

    DevEmitController(DossierRepository dossiers, EventRepository events, NotificationDispatcher dispatcher) {
        this.dossiers = dossiers;
        this.events = events;
        this.dispatcher = dispatcher;
    }

    record EmitResult(long eventId, EventKind kind, Groupe oldGroupe, Groupe newGroupe, int notifications,
            int pushes) {
    }

    /** NEW when {@code new_groupe=DEPOSE}, otherwise GROUPE_CHANGE from the dossier's current groupe. */
    @PostMapping("/admin/dev/emit")
    EmitResult emit(@RequestParam("id_dossier") String idDossier, @RequestParam("new_groupe") String newGroupe) {
        Groupe target = parseGroupe(newGroupe);
        Groupe current = dossiers.findGroupe(idDossier)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "dossier not found: " + idDossier));
        NewEvent event = target == Groupe.DEPOSE
                ? new NewEvent(idDossier, EventKind.NEW, null, target)
                : new NewEvent(idDossier, EventKind.GROUPE_CHANGE, current, target);
        long eventId = events.insert(List.of(event)).getFirst();
        NotificationDispatcher.DispatchResult result = dispatcher.dispatch(List.of(eventId));
        return new EmitResult(eventId, event.kind(), event.oldGroupe(), target, result.notifications(),
                result.pushes());
    }

    private static Groupe parseGroupe(String value) {
        try {
            Groupe groupe = Groupe.valueOf(value.trim().toUpperCase(Locale.ROOT));
            if (SubscriptionRequest.NOTIFIABLE.contains(groupe)) {
                return groupe;
            }
        } catch (IllegalArgumentException e) {
            // fall through
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "new_groupe must be one of " + SubscriptionRequest.NOTIFIABLE);
    }
}
