package ch.perimetre.subscription;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * A device (app installation) and its subscriptions. No authentication: the app keeps the device id (a random
 * UUID), the only handle; a subscription is reached through its device.
 */
@RestController
class DeviceController {

    private final DeviceRepository devices;
    private final SubscriptionRepository subscriptions;
    private final SubscriptionService service;

    DeviceController(DeviceRepository devices, SubscriptionRepository subscriptions, SubscriptionService service) {
        this.devices = devices;
        this.subscriptions = subscriptions;
        this.service = service;
    }

    @PostMapping("/devices")
    ResponseEntity<DeviceView> create(@Valid @RequestBody DeviceRequest request) {
        UUID id = devices.create(null, request);
        return ResponseEntity.created(URI.create("/devices/" + id)).body(view(id));
    }

    @GetMapping("/devices/{id}")
    DeviceView get(@PathVariable UUID id) {
        return view(id);
    }

    /** Push token refresh; resets the invalid-token counter. */
    @PutMapping("/devices/{id}")
    DeviceView update(@PathVariable UUID id, @Valid @RequestBody DeviceRequest request) {
        if (!devices.update(id, request)) {
            throw notFound("device");
        }
        return view(id);
    }

    /** The device and all its subscriptions. */
    @DeleteMapping("/devices/{id}")
    ResponseEntity<Void> delete(@PathVariable UUID id) {
        if (!devices.delete(id)) {
            throw notFound("device");
        }
        return ResponseEntity.noContent().build();
    }

    /** Events matching any of the device's subscriptions, once each, newest first. */
    @GetMapping("/devices/{id}/events")
    List<SubscriptionEventView> events(@PathVariable UUID id,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        view(id);
        return subscriptions.deviceEvents(id, limit);
    }

    @GetMapping("/devices/{id}/subscriptions")
    List<SubscriptionView> listSubscriptions(@PathVariable UUID id) {
        view(id);
        return subscriptions.list(id);
    }

    /** 409 {@code SUBSCRIPTION_LIMIT_REACHED} at the per-device cap. */
    @PostMapping("/devices/{id}/subscriptions")
    ResponseEntity<SubscriptionView> createSubscription(@PathVariable UUID id,
            @Valid @RequestBody SubscriptionInput input) {
        SubscriptionView created = service.create(id, input).orElseThrow(() -> notFound("device"));
        return ResponseEntity.created(URI.create("/devices/" + id + "/subscriptions/" + created.id())).body(created);
    }

    @GetMapping("/devices/{id}/subscriptions/{subscriptionId}")
    SubscriptionView getSubscription(@PathVariable UUID id, @PathVariable UUID subscriptionId) {
        return subscriptions.find(id, subscriptionId).orElseThrow(() -> notFound("subscription"));
    }

    @PutMapping("/devices/{id}/subscriptions/{subscriptionId}")
    SubscriptionView updateSubscription(@PathVariable UUID id, @PathVariable UUID subscriptionId,
            @Valid @RequestBody SubscriptionInput input) {
        return subscriptions.update(id, subscriptionId, input).orElseThrow(() -> notFound("subscription"));
    }

    @DeleteMapping("/devices/{id}/subscriptions/{subscriptionId}")
    ResponseEntity<Void> deleteSubscription(@PathVariable UUID id, @PathVariable UUID subscriptionId) {
        if (!subscriptions.delete(id, subscriptionId)) {
            throw notFound("subscription");
        }
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/devices/{id}/subscriptions/{subscriptionId}/events")
    List<SubscriptionEventView> subscriptionEvents(@PathVariable UUID id, @PathVariable UUID subscriptionId,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        if (subscriptions.find(id, subscriptionId).isEmpty()) {
            throw notFound("subscription");
        }
        return subscriptions.events(subscriptionId, limit);
    }

    /** {@code {status: 409, code: SUBSCRIPTION_LIMIT_REACHED, detail: <French message>, max}}. */
    @ExceptionHandler
    ProblemDetail limitReached(SubscriptionLimitException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        problem.setTitle("Limite de périmètres atteinte");
        problem.setProperty("code", SubscriptionLimitException.CODE);
        problem.setProperty("max", e.max());
        return problem;
    }

    private DeviceView view(UUID id) {
        return devices.find(id, service.maxPerDevice()).orElseThrow(() -> notFound("device"));
    }

    private static ResponseStatusException notFound(String what) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, what + " not found");
    }
}
