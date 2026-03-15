package io.nexuspay.payment.application.fx;

import io.nexuspay.payment.application.port.fx.FxRatePort;
import io.nexuspay.payment.application.port.fx.MerchantCurrencyPrefsRepository;
import io.nexuspay.payment.application.port.fx.MerchantCurrencyPrefsRepository.MerchantCurrencyPrefs;
import io.nexuspay.payment.domain.fx.FxRate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Manages FX rate retrieval with provider selection and caching.
 * Delegates to the configured rate provider (ECB, OpenExchangeRates, etc.)
 * via the cache layer.
 *
 * @since 0.3.0 (Sprint 3.2)
 */
@Service
public class FxRateService {

    private static final Logger LOG = LoggerFactory.getLogger(FxRateService.class);

    private final Map<String, FxRatePort> providersByName;
    private final MerchantCurrencyPrefsRepository prefsRepository;
    private final FxRatePort defaultProvider;

    public FxRateService(List<FxRatePort> providers, MerchantCurrencyPrefsRepository prefsRepository) {
        this.providersByName = providers.stream()
                .collect(Collectors.toMap(
                        p -> p.providerName().toLowerCase(),
                        Function.identity()
                ));
        this.prefsRepository = prefsRepository;
        // Default to first provider if no explicit default configured
        this.defaultProvider = providers.isEmpty() ? null : providers.get(0);
    }

    /**
     * Gets the FX rate for a currency pair, using the tenant's preferred provider.
     */
    public FxRate getRate(String tenantId, String fromCurrency, String toCurrency) {
        if (fromCurrency.equalsIgnoreCase(toCurrency)) {
            throw new IllegalArgumentException("Cannot get FX rate for same currency: " + fromCurrency);
        }

        FxRatePort provider = resolveProvider(tenantId);
        FxRate rate = provider.getRate(fromCurrency, toCurrency);

        // Apply merchant markup if configured
        MerchantCurrencyPrefs prefs = prefsRepository.findByTenantId(tenantId)
                .orElse(null);
        if (prefs != null && prefs.fxMarkupBps() > 0) {
            rate = rate.withMarkup(prefs.fxMarkupBps());
            LOG.debug("Applied {}bps markup for tenant {} on {}", prefs.fxMarkupBps(), tenantId, rate.pair());
        }

        return rate;
    }

    /**
     * Gets all available rates for a base currency.
     */
    public List<FxRate> getAllRates(String tenantId, String baseCurrency) {
        FxRatePort provider = resolveProvider(tenantId);
        return provider.getAllRates(baseCurrency);
    }

    /**
     * Checks if a currency conversion is needed for a payment.
     */
    public boolean isConversionNeeded(String tenantId, String presentmentCurrency) {
        MerchantCurrencyPrefs prefs = prefsRepository.findByTenantId(tenantId)
                .orElse(MerchantCurrencyPrefs.defaults(tenantId));

        if (!prefs.autoConvert()) return false;
        return !prefs.settlementCurrency().equalsIgnoreCase(presentmentCurrency);
    }

    /**
     * Returns the settlement currency for a tenant.
     */
    public String getSettlementCurrency(String tenantId) {
        return prefsRepository.findByTenantId(tenantId)
                .map(MerchantCurrencyPrefs::settlementCurrency)
                .orElse("USD");
    }

    private FxRatePort resolveProvider(String tenantId) {
        MerchantCurrencyPrefs prefs = prefsRepository.findByTenantId(tenantId).orElse(null);
        if (prefs != null) {
            FxRatePort provider = providersByName.get(prefs.rateProvider().toLowerCase());
            if (provider != null) return provider;
            LOG.warn("Configured provider '{}' not found for tenant {}, falling back to default",
                    prefs.rateProvider(), tenantId);
        }
        if (defaultProvider == null) {
            throw new IllegalStateException("No FX rate provider available");
        }
        return defaultProvider;
    }
}
