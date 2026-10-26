package ch.perimetre.notification;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import ch.perimetre.notification.PushPlanner.PlannedPush;
import ch.perimetre.subscription.DeviceRepository;

/**
 * Matches events against subscriptions (radius + categories), records PENDING notifications and sends
 * them per device: an event matching several of a device's subscriptions is one push, at most
 * {@value PushPlanner#MAX_INDIVIDUAL_PUSHES} individual pushes per device and run, else one summary push.
 */
@Service
public class NotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);

    /** A device (with its subscriptions) is deleted after this many consecutive runs failing with an invalid token. */
    static final int MAX_TOKEN_FAILURES = 3;

    private final NotificationRepository notifications;
    private final DeviceRepository devices;
    private final PushSender sender;

    NotificationDispatcher(NotificationRepository notifications, DeviceRepository devices, PushSender sender) {
        this.notifications = notifications;
        this.devices = devices;
        this.sender = sender;
    }

    /**
     * {@code notifications}: rows created; {@code pushes}: pushes sent successfully; {@code pushErrors}:
     * pushes that failed (invalid token or any other error).
     */
    public record DispatchResult(int notifications, int pushes, int pushErrors) {

        public static final DispatchResult NONE = new DispatchResult(0, 0, 0);
    }

    /** Per device: pushes sent and pushes failed. */
    private record SendResult(int sent, int failed) {
    }

    public DispatchResult dispatch(List<Long> eventIds) {
        if (eventIds.isEmpty()) {
            return DispatchResult.NONE;
        }
        int created = notifications.createPending(eventIds);
        Map<UUID, List<PendingNotification>> byDevice = notifications.findPending(eventIds).stream()
                .collect(Collectors.groupingBy(PendingNotification::deviceId, LinkedHashMap::new,
                        Collectors.toList()));
        int pushes = 0;
        int errors = 0;
        for (Map.Entry<UUID, List<PendingNotification>> entry : byDevice.entrySet()) {
            SendResult result = sendTo(entry.getKey(), entry.getValue());
            pushes += result.sent();
            errors += result.failed();
        }
        log.info("Dispatched {} events: {} notifications, {} pushes ({} failed) to {} devices",
                eventIds.size(), created, pushes, errors, byDevice.size());
        return new DispatchResult(created, pushes, errors);
    }

    private SendResult sendTo(UUID deviceId, List<PendingNotification> pending) {
        String token = pending.getFirst().pushToken();
        List<PlannedPush> plan = PushPlanner.plan(pending);
        int sent = 0;
        int failed = 0;
        for (int i = 0; i < plan.size(); i++) {
            PlannedPush push = plan.get(i);
            PushResult result = send(token, push.message());
            switch (result.status()) {
                case SENT -> {
                    notifications.markSent(push.notificationIds(), result.messageId());
                    sent++;
                }
                case FAILED -> {
                    log.warn("Push to device {} failed: {}", deviceId, result.error());
                    notifications.markFailed(push.notificationIds(), result.error());
                    failed++;
                }
                case INVALID_TOKEN -> {
                    notifications.markFailed(push.notificationIds(), result.error());
                    // The same token would fail again: skip the rest of this run, count one failure.
                    notifications.markSkipped(plan.subList(i + 1, plan.size()).stream()
                            .flatMap(p -> p.notificationIds().stream()).toList(), "invalid token");
                    int failures = devices.recordTokenFailure(deviceId);
                    log.warn("Push token of device {} rejected ({} consecutive): {}",
                            deviceId, failures, result.error());
                    if (failures >= MAX_TOKEN_FAILURES) {
                        devices.delete(deviceId);
                        log.info("Deleted device {} and its subscriptions after {} invalid-token failures",
                                deviceId, failures);
                    }
                    return new SendResult(sent, failed + 1);
                }
            }
        }
        if (sent > 0) {
            devices.resetTokenFailures(deviceId);
        }
        return new SendResult(sent, failed);
    }

    private PushResult send(String token, PushMessage message) {
        try {
            return sender.send(token, message);
        } catch (RuntimeException e) {
            return PushResult.failed(e.toString());
        }
    }
}
