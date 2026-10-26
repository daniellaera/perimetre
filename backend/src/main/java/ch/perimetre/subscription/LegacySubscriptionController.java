package ch.perimetre.subscription;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The single-subscription API of the first app build (2026-10), kept until every installed app uses
 * /devices. Each legacy subscription lives on its own device with the same id; the request's push token is
 * the device's. No authentication: the device keeps the subscription id (a random UUID), the only handle.
 */
@RestController
class LegacySubscriptionController {

    private final SubscriptionService service;
    private final SubscriptionRepository subscriptions;

    LegacySubscriptionController(SubscriptionService service, SubscriptionRepository subscriptions) {
        this.service = service;
        this.subscriptions = subscriptions;
    }

    @PostMapping("/subscriptions")
    ResponseEntity<LegacySubscriptionView> create(@Valid @RequestBody LegacySubscriptionRequest request) {
        LegacySubscriptionView created = service.createLegacy(request);
        return ResponseEntity.created(URI.create("/subscriptions/" + created.id())).body(created);
    }

    @GetMapping("/subscriptions/{id}")
    LegacySubscriptionView get(@PathVariable UUID id) {
        return subscriptions.findLegacy(id).orElseThrow(LegacySubscriptionController::notFound);
    }

    @PutMapping("/subscriptions/{id}")
    LegacySubscriptionView update(@PathVariable UUID id, @Valid @RequestBody LegacySubscriptionRequest request) {
        return service.updateLegacy(id, request).orElseThrow(LegacySubscriptionController::notFound);
    }

    @DeleteMapping("/subscriptions/{id}")
    ResponseEntity<Void> delete(@PathVariable UUID id) {
        if (!service.deleteLegacy(id)) {
            throw notFound();
        }
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/subscriptions/{id}/events")
    List<SubscriptionEventView> events(@PathVariable UUID id,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        if (subscriptions.deviceOf(id).isEmpty()) {
            throw notFound();
        }
        return subscriptions.events(id, limit);
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "subscription not found");
    }
}
