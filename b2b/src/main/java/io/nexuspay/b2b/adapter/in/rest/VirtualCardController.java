package io.nexuspay.b2b.adapter.in.rest;

import io.nexuspay.b2b.adapter.in.rest.dto.IssueVirtualCardRequest;
import io.nexuspay.b2b.adapter.in.rest.dto.VirtualCardResponse;
import io.nexuspay.b2b.application.port.in.IssueVirtualCardUseCase;
import io.nexuspay.b2b.domain.VirtualCardType;
import io.nexuspay.common.tenant.CallerTenant;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST controller for virtual card issuance and management.
 *
 * @since 0.4.2 (Sprint 4.3)
 */
@RestController
@RequestMapping("/v1/virtual-cards")
public class VirtualCardController {

    private final IssueVirtualCardUseCase virtualCardUseCase;

    public VirtualCardController(IssueVirtualCardUseCase virtualCardUseCase) {
        this.virtualCardUseCase = virtualCardUseCase;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('admin', 'operator')")
    public ResponseEntity<VirtualCardResponse> issueCard(
            @Valid @RequestBody IssueVirtualCardRequest request) {

        VirtualCardType cardType = VirtualCardType.valueOf(request.cardType());
        List<String> mccCodes = request.merchantCategoryCodes() != null
                ? request.merchantCategoryCodes()
                : List.of();

        var result = virtualCardUseCase.issueCard(
                new IssueVirtualCardUseCase.IssueCardCommand(
                        CallerTenant.require(), cardType, request.amountLimit(), request.currency(),
                        request.expiresAt(), mccCodes, request.purchaseOrderId()));

        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(result));
    }

    @GetMapping("/{cardId}")
    @PreAuthorize("hasAnyRole('admin', 'operator', 'viewer')")
    public ResponseEntity<VirtualCardResponse> getCard(
            @PathVariable String cardId) {

        var result = virtualCardUseCase.getCard(cardId, CallerTenant.require());
        return ResponseEntity.ok(toResponse(result));
    }

    @PostMapping("/{cardId}/freeze")
    @PreAuthorize("hasAnyRole('admin', 'operator')")
    public ResponseEntity<Void> freezeCard(
            @PathVariable String cardId) {

        virtualCardUseCase.freezeCard(cardId, CallerTenant.require());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{cardId}/cancel")
    @PreAuthorize("hasRole('admin')")
    public ResponseEntity<Void> cancelCard(
            @PathVariable String cardId) {

        virtualCardUseCase.cancelCard(cardId, CallerTenant.require());
        return ResponseEntity.noContent().build();
    }

    private VirtualCardResponse toResponse(IssueVirtualCardUseCase.VirtualCardResult result) {
        return new VirtualCardResponse(
                result.cardId(), result.cardLast4(), result.cardType().name(),
                result.amountLimit(), result.spentAmount(), result.availableBalance(),
                result.currency(), result.status().name(), result.issuingProvider(),
                result.expiresAt(), result.createdAt());
    }
}
