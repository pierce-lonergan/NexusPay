package io.nexuspay.payment.application.port.fx;

import java.util.Optional;
import java.util.UUID;

/**
 * Outbound port for merchant currency preferences persistence.
 *
 * @since 0.3.0 (Sprint 3.2)
 */
public interface MerchantCurrencyPrefsRepository {

    Optional<MerchantCurrencyPrefs> findByTenantId(String tenantId);

    MerchantCurrencyPrefs save(MerchantCurrencyPrefs prefs);

    /**
     * Merchant currency preference record.
     *
     * @param merchantCountry server-authoritative ISO-2 destination country for the OFAC screen
     *                        (B-025); may be {@code null} = unknown → sanctions gate fails closed
     *                        to REVIEW on cross-border-capable flows.
     */
    record MerchantCurrencyPrefs(
            UUID id,
            String tenantId,
            String settlementCurrency,
            boolean autoConvert,
            int fxMarkupBps,
            String rateProvider,
            int rateLockDurationMinutes,
            String merchantCountry
    ) {
        /** Backward-compatible constructor for callers that do not set merchant country (null). */
        public MerchantCurrencyPrefs(UUID id, String tenantId, String settlementCurrency,
                                     boolean autoConvert, int fxMarkupBps, String rateProvider,
                                     int rateLockDurationMinutes) {
            this(id, tenantId, settlementCurrency, autoConvert, fxMarkupBps, rateProvider,
                    rateLockDurationMinutes, null);
        }

        public static MerchantCurrencyPrefs defaults(String tenantId) {
            // No assumed country in defaults: an unconfigured tenant is "unknown" and must REVIEW,
            // not silently inherit a country. (Dev 'default' tenant is seeded US via migration.)
            return new MerchantCurrencyPrefs(
                    UUID.randomUUID(), tenantId, "USD", true, 0, "ECB", 15, null
            );
        }
    }
}
