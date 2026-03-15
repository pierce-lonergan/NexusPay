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
     */
    record MerchantCurrencyPrefs(
            UUID id,
            String tenantId,
            String settlementCurrency,
            boolean autoConvert,
            int fxMarkupBps,
            String rateProvider,
            int rateLockDurationMinutes
    ) {
        public static MerchantCurrencyPrefs defaults(String tenantId) {
            return new MerchantCurrencyPrefs(
                    UUID.randomUUID(), tenantId, "USD", true, 0, "ECB", 15
            );
        }
    }
}
