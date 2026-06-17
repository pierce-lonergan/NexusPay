package io.nexuspay.payment.adapter.in.rest;

import io.nexuspay.common.tenant.CallerTenant;
import io.nexuspay.payment.application.port.fx.MerchantCurrencyPrefsRepository;
import io.nexuspay.payment.application.port.fx.MerchantCurrencyPrefsRepository.MerchantCurrencyPrefs;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * REST controller for managing merchant currency preferences.
 *
 * @since 0.3.0 (Sprint 3.2)
 */
@RestController
@RequestMapping("/v1/merchant/currency-preferences")
public class MerchantCurrencyPrefsController {

    private final MerchantCurrencyPrefsRepository prefsRepository;

    public MerchantCurrencyPrefsController(MerchantCurrencyPrefsRepository prefsRepository) {
        this.prefsRepository = prefsRepository;
    }

    /**
     * Get the current merchant currency preferences.
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('admin', 'operator', 'viewer')")
    public ResponseEntity<Map<String, Object>> getPreferences() {

        // SEC-27: tenant from the authenticated principal, never a client header.
        String tenantId = CallerTenant.require();
        MerchantCurrencyPrefs prefs = prefsRepository.findByTenantId(tenantId)
                .orElse(MerchantCurrencyPrefs.defaults(tenantId));

        return ResponseEntity.ok(prefsToMap(prefs));
    }

    /**
     * Update merchant currency preferences.
     */
    @PutMapping
    @PreAuthorize("hasRole('admin')")
    public ResponseEntity<Map<String, Object>> updatePreferences(
            @RequestBody UpdatePrefsRequest request) {

        // SEC-27: tenant from the authenticated principal, never a client header.
        String tenantId = CallerTenant.require();
        MerchantCurrencyPrefs existing = prefsRepository.findByTenantId(tenantId)
                .orElse(MerchantCurrencyPrefs.defaults(tenantId));

        MerchantCurrencyPrefs updated = new MerchantCurrencyPrefs(
                existing.id(),
                tenantId,
                request.settlementCurrency() != null ? request.settlementCurrency() : existing.settlementCurrency(),
                request.autoConvert() != null ? request.autoConvert() : existing.autoConvert(),
                request.fxMarkupBps() != null ? request.fxMarkupBps() : existing.fxMarkupBps(),
                request.rateProvider() != null ? request.rateProvider() : existing.rateProvider(),
                request.rateLockDurationMinutes() != null ? request.rateLockDurationMinutes() : existing.rateLockDurationMinutes(),
                // merchant_country is server-authoritative (B-025) and NOT settable via this
                // self-service endpoint — preserve the existing value to avoid a merchant
                // weakening their own sanctions geography.
                existing.merchantCountry()
        );

        updated = prefsRepository.save(updated);
        return ResponseEntity.ok(prefsToMap(updated));
    }

    private Map<String, Object> prefsToMap(MerchantCurrencyPrefs prefs) {
        return Map.of(
                "tenant_id", prefs.tenantId(),
                "settlement_currency", prefs.settlementCurrency(),
                "auto_convert", prefs.autoConvert(),
                "fx_markup_bps", prefs.fxMarkupBps(),
                "rate_provider", prefs.rateProvider(),
                "rate_lock_duration_minutes", prefs.rateLockDurationMinutes()
        );
    }

    record UpdatePrefsRequest(
            String settlementCurrency,
            Boolean autoConvert,
            Integer fxMarkupBps,
            String rateProvider,
            Integer rateLockDurationMinutes
    ) {}
}
