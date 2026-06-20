package io.nexuspay.marketplace.adapter.in.rest;

import io.nexuspay.common.tenant.CallerTenant;
import io.nexuspay.marketplace.adapter.in.rest.dto.*;
import io.nexuspay.marketplace.application.port.in.SchedulePayoutUseCase;
import io.nexuspay.marketplace.domain.PayoutMethod;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST controller for payout operations.
 *
 * @since 0.4.1 (Sprint 4.2)
 */
@RestController
@RequestMapping("/v1/payouts")
public class PayoutController {

    private final SchedulePayoutUseCase payoutUseCase;

    public PayoutController(SchedulePayoutUseCase payoutUseCase) {
        this.payoutUseCase = payoutUseCase;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('admin', 'operator') and @scopeAuth.has('payouts:write')")
    public ResponseEntity<PayoutResponse> createPayout(
            @Valid @RequestBody CreatePayoutRequest request) {

        var result = payoutUseCase.createPayout(new SchedulePayoutUseCase.CreatePayoutCommand(
                CallerTenant.require(), request.connectedAccountId(), request.amount(),
                request.currency(), PayoutMethod.valueOf(request.method()),
                request.scheduledAt()));

        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(result));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('admin', 'operator', 'viewer') and @scopeAuth.has('payouts:read')")
    public ResponseEntity<List<PayoutResponse>> listPayouts(
            @RequestParam String connectedAccountId) {

        var results = payoutUseCase.listPayouts(CallerTenant.require(), connectedAccountId);
        return ResponseEntity.ok(results.stream().map(this::toResponse).toList());
    }

    @GetMapping("/{payoutId}")
    @PreAuthorize("hasAnyRole('admin', 'operator', 'viewer') and @scopeAuth.has('payouts:read')")
    public ResponseEntity<PayoutResponse> getPayout(
            @PathVariable String payoutId) {

        var result = payoutUseCase.getPayout(payoutId, CallerTenant.require());
        return ResponseEntity.ok(toResponse(result));
    }

    private PayoutResponse toResponse(SchedulePayoutUseCase.PayoutResult result) {
        return new PayoutResponse(
                result.payoutId(), result.connectedAccountId(),
                result.amount(), result.currency(), result.status().name(),
                result.method().name(), result.scheduledAt(), result.paidAt(),
                result.failureReason(), result.externalReference(), result.createdAt());
    }
}
