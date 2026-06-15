package io.nexuspay.marketplace.adapter.in.rest;

import io.nexuspay.common.tenant.CallerTenant;
import io.nexuspay.marketplace.adapter.in.rest.dto.*;
import io.nexuspay.marketplace.application.port.in.ConfigureFeeUseCase;
import io.nexuspay.marketplace.application.port.in.OnboardAccountUseCase;
import io.nexuspay.marketplace.domain.PayoutSchedule;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller for connected account management.
 *
 * @since 0.4.1 (Sprint 4.2)
 */
@RestController
@RequestMapping("/v1/connected-accounts")
public class ConnectedAccountController {

    private final OnboardAccountUseCase onboardUseCase;
    private final ConfigureFeeUseCase feeUseCase;

    public ConnectedAccountController(OnboardAccountUseCase onboardUseCase,
                                       ConfigureFeeUseCase feeUseCase) {
        this.onboardUseCase = onboardUseCase;
        this.feeUseCase = feeUseCase;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('admin', 'operator')")
    public ResponseEntity<ConnectedAccountResponse> onboardAccount(
            @Valid @RequestBody OnboardAccountRequest request) {

        String tenantId = CallerTenant.require();

        PayoutSchedule schedule = request.payoutSchedule() != null
                ? PayoutSchedule.valueOf(request.payoutSchedule())
                : null;

        var result = onboardUseCase.onboardAccount(new OnboardAccountUseCase.OnboardCommand(
                tenantId, request.businessName(), request.email(),
                request.country(), request.defaultCurrency(), schedule));

        var info = onboardUseCase.getAccount(result.accountId(), tenantId);

        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(info));
    }

    @GetMapping("/{accountId}")
    @PreAuthorize("hasAnyRole('admin', 'operator', 'viewer')")
    public ResponseEntity<ConnectedAccountResponse> getAccount(
            @PathVariable String accountId) {

        var info = onboardUseCase.getAccount(accountId, CallerTenant.require());
        return ResponseEntity.ok(toResponse(info));
    }

    @PutMapping("/{accountId}")
    @PreAuthorize("hasAnyRole('admin', 'operator')")
    public ResponseEntity<ConnectedAccountResponse> updateAccount(
            @PathVariable String accountId,
            @Valid @RequestBody UpdateAccountRequest request) {

        PayoutSchedule schedule = request.payoutSchedule() != null
                ? PayoutSchedule.valueOf(request.payoutSchedule())
                : null;

        var info = onboardUseCase.updateAccount(accountId, CallerTenant.require(),
                new OnboardAccountUseCase.UpdateAccountCommand(
                        request.businessName(), request.email(), schedule,
                        request.payoutMinimum(), request.platformFeePercent(),
                        request.platformFeeFixed()));

        return ResponseEntity.ok(toResponse(info));
    }

    @PostMapping("/{accountId}/suspend")
    @PreAuthorize("hasRole('admin')")
    public ResponseEntity<Void> suspendAccount(
            @PathVariable String accountId,
            @RequestParam(defaultValue = "Manual suspension") String reason) {

        onboardUseCase.suspendAccount(accountId, CallerTenant.require(), reason);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{accountId}")
    @PreAuthorize("hasRole('admin')")
    public ResponseEntity<Void> closeAccount(
            @PathVariable String accountId) {

        onboardUseCase.closeAccount(accountId, CallerTenant.require());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{accountId}/fees")
    @PreAuthorize("hasAnyRole('admin', 'operator')")
    public ResponseEntity<FeeConfigResponse> configureFee(
            @PathVariable String accountId,
            @Valid @RequestBody ConfigureFeeRequest request) {

        var result = feeUseCase.configureFee(new ConfigureFeeUseCase.ConfigureFeeCommand(
                CallerTenant.require(), accountId, request.feePercent(), request.feeFixed()));

        return ResponseEntity.ok(new FeeConfigResponse(
                result.connectedAccountId(), result.feePercent(), result.feeFixed()));
    }

    @GetMapping("/{accountId}/fees")
    @PreAuthorize("hasAnyRole('admin', 'operator', 'viewer')")
    public ResponseEntity<FeeConfigResponse> getFeeConfig(
            @PathVariable String accountId) {

        var result = feeUseCase.getFeeConfig(accountId, CallerTenant.require());
        return ResponseEntity.ok(new FeeConfigResponse(
                result.connectedAccountId(), result.feePercent(), result.feeFixed()));
    }

    private ConnectedAccountResponse toResponse(OnboardAccountUseCase.AccountInfo info) {
        return new ConnectedAccountResponse(
                info.accountId(), info.businessName(), info.email(),
                info.status().name(), info.kycStatus().name(),
                info.country(), info.defaultCurrency(),
                info.payoutSchedule().name(), info.payoutMinimum(),
                info.platformFeePercent(), info.platformFeeFixed(),
                info.createdAt(), info.updatedAt());
    }
}
