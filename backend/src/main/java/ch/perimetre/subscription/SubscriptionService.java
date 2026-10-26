package ch.perimetre.subscription;

import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes that touch a device and its subscriptions together: the per-device cap, and the legacy
 * single-subscription API mapped onto devices (a legacy subscription's device has the subscription's id).
 */
@Service
class SubscriptionService {

    private final DeviceRepository devices;
    private final SubscriptionRepository subscriptions;
    private final int maxPerDevice;

    SubscriptionService(DeviceRepository devices, SubscriptionRepository subscriptions,
            @Value("${perimetre.subscriptions.max-per-device:25}") int maxPerDevice) {
        this.devices = devices;
        this.subscriptions = subscriptions;
        this.maxPerDevice = maxPerDevice;
    }

    int maxPerDevice() {
        return maxPerDevice;
    }

    /**
     * Empty when the device does not exist; {@link SubscriptionLimitException} at the cap. The device row is
     * locked, so concurrent creations cannot overshoot it.
     */
    @Transactional
    Optional<SubscriptionView> create(UUID deviceId, SubscriptionInput input) {
        if (!devices.lock(deviceId)) {
            return Optional.empty();
        }
        if (subscriptions.count(deviceId) >= maxPerDevice) {
            throw new SubscriptionLimitException(maxPerDevice);
        }
        return Optional.of(subscriptions.create(null, deviceId, input));
    }

    /** Legacy POST /subscriptions: a new device and its one subscription, sharing a fresh id. */
    @Transactional
    LegacySubscriptionView createLegacy(LegacySubscriptionRequest request) {
        UUID id = UUID.randomUUID();
        devices.create(id, request.toDevice());
        subscriptions.create(id, id, request.toInput());
        return subscriptions.findLegacy(id).orElseThrow();
    }

    /** Legacy PUT: the subscription's perimeter and its device's push token (refresh) in one go. */
    @Transactional
    Optional<LegacySubscriptionView> updateLegacy(UUID id, LegacySubscriptionRequest request) {
        Optional<UUID> deviceId = subscriptions.deviceOf(id);
        if (deviceId.isEmpty()) {
            return Optional.empty();
        }
        devices.update(deviceId.get(), request.toDevice());
        subscriptions.updateLegacy(id, request);
        return subscriptions.findLegacy(id);
    }

    /** Legacy DELETE: the subscription, and its device once that holds no other subscription. */
    @Transactional
    boolean deleteLegacy(UUID id) {
        Optional<UUID> deviceId = subscriptions.deviceOf(id);
        if (deviceId.isEmpty() || !subscriptions.deleteById(id)) {
            return false;
        }
        if (subscriptions.count(deviceId.get()) == 0) {
            devices.delete(deviceId.get());
        }
        return true;
    }
}
