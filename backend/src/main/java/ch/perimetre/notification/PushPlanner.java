package ch.perimetre.notification;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns one device's notifications of a run into pushes. An event matching several of the device's
 * subscriptions is one push (its rows all marked by it). Up to {@value #MAX_INDIVIDUAL_PUSHES} events: one push
 * each; more: a single summary covering all of them.
 */
final class PushPlanner {

    static final int MAX_INDIVIDUAL_PUSHES = 3;

    record PlannedPush(PushMessage message, List<Long> notificationIds) {
    }

    private PushPlanner() {
    }

    static List<PlannedPush> plan(List<PendingNotification> pending) {
        List<List<PendingNotification>> events = byEvent(pending);
        if (events.size() <= MAX_INDIVIDUAL_PUSHES) {
            return events.stream()
                    .map(matches -> new PlannedPush(PushMessages.forEvent(matches), ids(matches)))
                    .toList();
        }
        return List.of(new PlannedPush(PushMessages.summary(events), ids(pending)));
    }

    /** The notifications of each event (in first-seen order), nearest subscription first. */
    static List<List<PendingNotification>> byEvent(List<PendingNotification> pending) {
        Map<Long, List<PendingNotification>> byEvent = new LinkedHashMap<>();
        for (PendingNotification n : pending) {
            byEvent.computeIfAbsent(n.eventId(), id -> new ArrayList<>()).add(n);
        }
        Comparator<PendingNotification> nearestFirst = Comparator.comparingDouble(PendingNotification::distanceM)
                .thenComparing(PendingNotification::subscriptionId);
        return byEvent.values().stream().map(matches -> matches.stream().sorted(nearestFirst).toList()).toList();
    }

    private static List<Long> ids(List<PendingNotification> notifications) {
        return notifications.stream().map(PendingNotification::notificationId).toList();
    }
}
