package io.nexuspay.billing.adapter.in.rest;

import io.nexuspay.billing.application.service.SubscriptionLifecycleService;
import io.nexuspay.billing.domain.Subscription;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST API for subscription management.
 *
 * @since 0.2.5 (Sprint 2.5a)
 */
@RestController
@RequestMapping("/v1/subscriptions")
public class SubscriptionController {

    private final SubscriptionLifecycleService lifecycleService;

    public SubscriptionController(SubscriptionLifecycleService lifecycleService) {
        this.lifecycleService = lifecycleService;
    }

    @PostMapping
    public ResponseEntity<SubscriptionResponse> create(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @RequestBody CreateSubscriptionRequest request) {

        Subscription sub = lifecycleService.createSubscription(
                tenantId, request.customerId(), request.priceId(),
                request.quantity() != null ? request.quantity() : 1,
                request.paymentMethodId(), request.metadata());

        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(sub));
    }

    @GetMapping("/{id}")
    public ResponseEntity<SubscriptionResponse> get(@PathVariable String id) {
        return lifecycleService.findById(id)
                .map(s -> ResponseEntity.ok(toResponse(s)))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping
    public ResponseEntity<List<SubscriptionResponse>> list(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset) {

        return ResponseEntity.ok(lifecycleService.listByTenant(tenantId, limit, offset)
                .stream().map(this::toResponse).toList());
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<SubscriptionResponse> cancel(
            @PathVariable String id,
            @RequestBody(required = false) Map<String, Object> body) {

        boolean atPeriodEnd = body != null && Boolean.TRUE.equals(body.get("at_period_end"));
        Subscription sub = lifecycleService.cancel(id, atPeriodEnd);
        return ResponseEntity.ok(toResponse(sub));
    }

    @PostMapping("/{id}/pause")
    public ResponseEntity<SubscriptionResponse> pause(@PathVariable String id) {
        return ResponseEntity.ok(toResponse(lifecycleService.pause(id)));
    }

    @PostMapping("/{id}/resume")
    public ResponseEntity<SubscriptionResponse> resume(@PathVariable String id) {
        return ResponseEntity.ok(toResponse(lifecycleService.resume(id)));
    }

    @PostMapping("/{id}/change")
    public ResponseEntity<SubscriptionResponse> changePlan(
            @PathVariable String id,
            @RequestBody Map<String, String> body) {

        String newPriceId = body.get("price_id");
        if (newPriceId == null) return ResponseEntity.badRequest().build();
        return ResponseEntity.ok(toResponse(lifecycleService.changePlan(id, newPriceId)));
    }

    // ---- DTOs ----

    private SubscriptionResponse toResponse(Subscription s) {
        return new SubscriptionResponse(
                s.getId(), s.getCustomerId(), s.getPriceId(), s.getStatus().name(),
                s.getQuantity(), s.isCancelAtPeriodEnd(),
                s.getCurrentPeriodStart() != null ? s.getCurrentPeriodStart().toString() : null,
                s.getCurrentPeriodEnd() != null ? s.getCurrentPeriodEnd().toString() : null,
                s.getTrialStart() != null ? s.getTrialStart().toString() : null,
                s.getTrialEnd() != null ? s.getTrialEnd().toString() : null,
                s.getCanceledAt() != null ? s.getCanceledAt().toString() : null,
                s.getCreatedAt().toString()
        );
    }

    record CreateSubscriptionRequest(String customerId, String priceId, Integer quantity,
                                      String paymentMethodId, Map<String, Object> metadata) {}

    record SubscriptionResponse(String id, String customerId, String priceId, String status,
                                 int quantity, boolean cancelAtPeriodEnd,
                                 String currentPeriodStart, String currentPeriodEnd,
                                 String trialStart, String trialEnd,
                                 String canceledAt, String createdAt) {}
}
