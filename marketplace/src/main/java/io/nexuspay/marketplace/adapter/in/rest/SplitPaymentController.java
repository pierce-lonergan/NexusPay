package io.nexuspay.marketplace.adapter.in.rest;

import io.nexuspay.common.tenant.CallerTenant;
import io.nexuspay.marketplace.adapter.in.rest.dto.*;
import io.nexuspay.marketplace.application.port.in.CreateSplitPaymentUseCase;
import io.nexuspay.marketplace.domain.SplitType;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST controller for split payment operations.
 *
 * @since 0.4.1 (Sprint 4.2)
 */
@RestController
@RequestMapping("/v1/split-payments")
public class SplitPaymentController {

    private final CreateSplitPaymentUseCase splitPaymentUseCase;

    public SplitPaymentController(CreateSplitPaymentUseCase splitPaymentUseCase) {
        this.splitPaymentUseCase = splitPaymentUseCase;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('admin', 'operator') and @scopeAuth.has('payments:write')")
    public ResponseEntity<SplitPaymentResponse> createSplitPayment(
            @Valid @RequestBody CreateSplitPaymentRequest request) {

        List<CreateSplitPaymentUseCase.SplitRuleCommand> rules = request.rules().stream()
                .map(r -> new CreateSplitPaymentUseCase.SplitRuleCommand(
                        r.connectedAccountId(), SplitType.valueOf(r.splitType()),
                        r.amount(), r.percentage()))
                .toList();

        var result = splitPaymentUseCase.createSplitPayment(
                new CreateSplitPaymentUseCase.CreateSplitCommand(
                        CallerTenant.require(), request.paymentId(), request.totalAmount(),
                        request.currency(), rules));

        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(result));
    }

    @GetMapping("/{splitPaymentId}")
    @PreAuthorize("hasAnyRole('admin', 'operator', 'viewer') and @scopeAuth.has('payments:read')")
    public ResponseEntity<SplitPaymentResponse> getSplitPayment(
            @PathVariable String splitPaymentId) {

        var result = splitPaymentUseCase.getSplitPayment(splitPaymentId, CallerTenant.require());
        return ResponseEntity.ok(toResponse(result));
    }

    private SplitPaymentResponse toResponse(CreateSplitPaymentUseCase.SplitPaymentResult result) {
        List<SplitPaymentResponse.SplitRuleResponse> ruleResponses = result.rules().stream()
                .map(r -> new SplitPaymentResponse.SplitRuleResponse(
                        r.ruleId(), r.connectedAccountId(), r.splitType().name(),
                        r.calculatedAmount(), r.currency()))
                .toList();

        return new SplitPaymentResponse(
                result.splitPaymentId(), result.paymentId(), result.status().name(),
                result.totalAmount(), result.currency(), ruleResponses,
                result.platformFeeAmount());
    }
}
