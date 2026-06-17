package io.nexuspay.billing.adapter.in.rest;

import io.nexuspay.billing.application.service.SubscriptionLifecycleService;
import io.nexuspay.billing.domain.Subscription;
import io.nexuspay.common.tenant.CallerTenant;
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
            @RequestBody CreateSubscriptionRequest request) {

        // SEC-26: tenant resolved from the authenticated principal, never from a client X-Tenant-Id header.
        String tenantId = CallerTenant.require();
        Subscription sub = lifecycleService.createSubscription(
                tenantId, request.customerId(), request.priceId(),
                request.quantity() != null ? request.quantity() : 1,
                request.paymentMethodId(), request.metadata());

        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(sub));
    }

    @GetMapping("/{id}")
    public ResponseEntity<SubscriptionResponse> get(@PathVariable String id) {
        // SEC-26: by-id read scoped to the caller's tenant — a foreign-tenant id 404s (no oracle).
        return lifecycleService.findById(id, CallerTenant.require())
                .map(s -> ResponseEntity.ok(toResponse(s)))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping
    public ResponseEntity<List<SubscriptionResponse>> list(
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset) {

        // SEC-26: tenant resolved from the authenticated principal, never from a client X-Tenant-Id header.
        String tenantId = CallerTenant.require();
        return ResponseEntity.ok(lifecycleService.listByTenant(tenantId, limit, offset)
                .stream().map(this::toResponse).toList());
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<SubscriptionResponse> cancel(
            @PathVariable String id,
            @RequestBody(required = false) Map<String, Object> body) {

        boolean atPeriodEnd = body != null && Boolean.TRUE.equals(body.get("at_period_end"));
        // SEC-26: mutation scoped to the caller's tenant — a tenant-A caller cannot cancel a tenant-B sub.
        Subscription sub = lifecycleService.cancel(id, CallerTenant.require(), atPeriodEnd);
        return ResponseEntity.ok(toResponse(sub));
    }

    @PostMapping("/{id}/pause")
    public ResponseEntity<SubscriptionResponse> pause(@PathVariable String id) {
        // SEC-26: mutation scoped to the caller's tenant.
        return ResponseEntity.ok(toResponse(lifecycleService.pause(id, CallerTenant.require())));
    }

    @PostMapping("/{id}/resume")
    public ResponseEntity<SubscriptionResponse> resume(@PathVariable String id) {
        // SEC-26: mutation scoped to the caller's tenant.
        return ResponseEntity.ok(toResponse(lifecycleService.resume(id, CallerTenant.require())));
    }

    @PostMapping("/{id}/change")
    public ResponseEntity<SubscriptionResponse> changePlan(
            @PathVariable String id,
            @RequestBody Map<String, String> body) {

        String newPriceId = body.get("price_id");
        if (newPriceId == null) return ResponseEntity.badRequest().build();
        // SEC-26: mutation scoped to the caller's tenant.
        return ResponseEntity.ok(toResponse(lifecycleService.changePlan(id, CallerTenant.require(), newPriceId)));
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
