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
 * No authentication: the device keeps the subscription id (a random UUID) and that is the only handle.
 */
@RestController
class SubscriptionController {

    private final SubscriptionRepository subscriptions;

    SubscriptionController(SubscriptionRepository subscriptions) {
        this.subscriptions = subscriptions;
    }

    @PostMapping("/subscriptions")
    ResponseEntity<SubscriptionView> create(@Valid @RequestBody SubscriptionRequest request) {
        SubscriptionView created = subscriptions.create(request);
        return ResponseEntity.created(URI.create("/subscriptions/" + created.id())).body(created);
    }

    @GetMapping("/subscriptions/{id}")
    SubscriptionView get(@PathVariable UUID id) {
        return subscriptions.find(id).orElseThrow(SubscriptionController::notFound);
    }

    @PutMapping("/subscriptions/{id}")
    SubscriptionView update(@PathVariable UUID id, @Valid @RequestBody SubscriptionRequest request) {
        return subscriptions.update(id, request).orElseThrow(SubscriptionController::notFound);
    }

    @DeleteMapping("/subscriptions/{id}")
    ResponseEntity<Void> delete(@PathVariable UUID id) {
        if (!subscriptions.delete(id)) {
            throw notFound();
        }
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/subscriptions/{id}/events")
    List<SubscriptionEventView> events(@PathVariable UUID id,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        if (subscriptions.find(id).isEmpty()) {
            throw notFound();
        }
        return subscriptions.events(id, limit);
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "subscription not found");
    }
}
