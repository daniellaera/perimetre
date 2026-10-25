package ch.perimetre.notification;

import java.util.List;

/**
 * Turns one subscription's notifications of a run into pushes: one per event up to
 * {@value #MAX_INDIVIDUAL_PUSHES}, otherwise a single summary covering all of them.
 */
final class PushPlanner {

    static final int MAX_INDIVIDUAL_PUSHES = 3;

    record PlannedPush(PushMessage message, List<Long> notificationIds) {
    }

    private PushPlanner() {
    }

    static List<PlannedPush> plan(List<PendingNotification> pending) {
        if (pending.size() <= MAX_INDIVIDUAL_PUSHES) {
            return pending.stream()
                    .map(n -> new PlannedPush(PushMessages.forEvent(n), List.of(n.notificationId())))
                    .toList();
        }
        return List.of(new PlannedPush(
                PushMessages.summary(pending.size()),
                pending.stream().map(PendingNotification::notificationId).toList()));
    }
}
