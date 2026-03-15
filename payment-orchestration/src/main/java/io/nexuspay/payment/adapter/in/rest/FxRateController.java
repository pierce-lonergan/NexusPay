package io.nexuspay.payment.adapter.in.rest;

import io.nexuspay.payment.application.fx.CrossBorderComplianceService;
import io.nexuspay.payment.application.fx.CurrencyRoutingService;
import io.nexuspay.payment.application.fx.FxRateLockService;
import io.nexuspay.payment.application.fx.FxRateService;
import io.nexuspay.payment.domain.fx.FxRate;
import io.nexuspay.payment.domain.fx.FxRateLock;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST controller for FX rate operations.
 *
 * @since 0.3.0 (Sprint 3.2)
 */
@RestController
@RequestMapping("/v1/fx")
public class FxRateController {

    private final FxRateService rateService;
    private final FxRateLockService lockService;
    private final CurrencyRoutingService routingService;
    private final CrossBorderComplianceService complianceService;

    public FxRateController(FxRateService rateService,
                            FxRateLockService lockService,
                            CurrencyRoutingService routingService,
                            CrossBorderComplianceService complianceService) {
        this.rateService = rateService;
        this.lockService = lockService;
        this.routingService = routingService;
        this.complianceService = complianceService;
    }

    /**
     * Get the current FX rate for a currency pair.
     */
    @GetMapping("/rates/{from}/{to}")
    @PreAuthorize("hasAnyRole('admin', 'operator', 'viewer')")
    public ResponseEntity<Map<String, Object>> getRate(
            @PathVariable String from,
            @PathVariable String to,
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId) {

        FxRate rate = rateService.getRate(tenantId, from.toUpperCase(), to.toUpperCase());
        return ResponseEntity.ok(Map.of(
                "pair", rate.pair().pairId(),
                "rate", rate.rate(),
                "inverse_rate", rate.inverseRate(),
                "provider", rate.provider(),
                "timestamp", rate.timestamp().toString()
        ));
    }

    /**
     * Get all available rates for a base currency.
     */
    @GetMapping("/rates/{baseCurrency}")
    @PreAuthorize("hasAnyRole('admin', 'operator', 'viewer')")
    public ResponseEntity<List<Map<String, Object>>> getAllRates(
            @PathVariable String baseCurrency,
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId) {

        List<FxRate> rates = rateService.getAllRates(tenantId, baseCurrency.toUpperCase());
        List<Map<String, Object>> response = rates.stream()
                .map(r -> Map.<String, Object>of(
                        "pair", r.pair().pairId(),
                        "rate", r.rate(),
                        "provider", r.provider(),
                        "timestamp", r.timestamp().toString()
                ))
                .toList();
        return ResponseEntity.ok(response);
    }

    /**
     * Lock an FX rate for a payment.
     */
    @PostMapping("/locks")
    @PreAuthorize("hasAnyRole('admin', 'operator')")
    public ResponseEntity<Map<String, Object>> lockRate(
            @RequestBody LockRateRequest request,
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId) {

        FxRateLock lock = lockService.lockRate(tenantId, request.fromCurrency(), request.toCurrency());
        return ResponseEntity.ok(lockToMap(lock));
    }

    /**
     * Get a rate lock by ID.
     */
    @GetMapping("/locks/{lockId}")
    @PreAuthorize("hasAnyRole('admin', 'operator', 'viewer')")
    public ResponseEntity<Map<String, Object>> getLock(@PathVariable UUID lockId) {
        FxRateLock lock = lockService.getValidLock(lockId);
        return ResponseEntity.ok(lockToMap(lock));
    }

    /**
     * Find PSPs that support a specific currency.
     */
    @GetMapping("/routing/presentment/{currency}")
    @PreAuthorize("hasAnyRole('admin', 'operator')")
    public ResponseEntity<Map<String, Object>> findPresentmentPsps(
            @PathVariable String currency,
            @RequestParam(defaultValue = "0") BigDecimal amount) {

        List<String> psps = routingService.findPresentmentCapablePsps(currency.toUpperCase(), amount);
        return ResponseEntity.ok(Map.of("currency", currency.toUpperCase(), "psps", psps));
    }

    /**
     * Validate a cross-border transaction against compliance rules.
     */
    @PostMapping("/compliance/validate")
    @PreAuthorize("hasAnyRole('admin', 'operator')")
    public ResponseEntity<CrossBorderComplianceService.ComplianceResult> validateCompliance(
            @RequestBody ComplianceRequest request) {

        var result = complianceService.validateTransaction(
                request.sourceCountry(), request.destinationCountry(),
                request.amount(), request.currency());
        return ResponseEntity.ok(result);
    }

    private Map<String, Object> lockToMap(FxRateLock lock) {
        return Map.of(
                "id", lock.getId(),
                "from_currency", lock.getFromCurrency(),
                "to_currency", lock.getToCurrency(),
                "rate", lock.getRate(),
                "inverse_rate", lock.getInverseRate(),
                "provider", lock.getRateProvider(),
                "locked_at", lock.getLockedAt().toString(),
                "expires_at", lock.getExpiresAt().toString(),
                "consumed", lock.isConsumed(),
                "valid", lock.isValid()
        );
    }

    record LockRateRequest(String fromCurrency, String toCurrency) {}

    record ComplianceRequest(String sourceCountry, String destinationCountry,
                              BigDecimal amount, String currency) {}
}
