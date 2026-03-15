package io.nexuspay.payment.application.fx;

import io.nexuspay.payment.application.port.fx.MerchantCurrencyPrefsRepository;
import io.nexuspay.payment.application.port.fx.MerchantCurrencyPrefsRepository.MerchantCurrencyPrefs;
import io.nexuspay.payment.domain.fx.CurrencyConversion;
import io.nexuspay.payment.domain.fx.FxRateLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Orchestrates currency conversion for merchant payouts.
 * Determines if conversion is needed, locks rates, and creates
 * conversion records for ledger processing.
 *
 * @since 0.3.0 (Sprint 3.2)
 */
@Service
public class PayoutCurrencyService {

    private static final Logger LOG = LoggerFactory.getLogger(PayoutCurrencyService.class);

    private final FxRateService rateService;
    private final FxRateLockService lockService;
    private final MerchantCurrencyPrefsRepository prefsRepository;

    public PayoutCurrencyService(FxRateService rateService,
                                 FxRateLockService lockService,
                                 MerchantCurrencyPrefsRepository prefsRepository) {
        this.rateService = rateService;
        this.lockService = lockService;
        this.prefsRepository = prefsRepository;
    }

    /**
     * Processes a currency conversion for a payment settlement.
     * Returns null if no conversion is needed (same currency).
     *
     * @param tenantId              merchant tenant
     * @param paymentId             payment identifier
     * @param presentmentCurrency   the currency the customer paid in
     * @param presentmentAmount     amount in minor units
     * @return currency conversion record, or null if no conversion needed
     */
    public CurrencyConversion processSettlement(
            String tenantId, String paymentId,
            String presentmentCurrency, long presentmentAmount) {

        MerchantCurrencyPrefs prefs = prefsRepository.findByTenantId(tenantId)
                .orElse(MerchantCurrencyPrefs.defaults(tenantId));

        String settlementCurrency = prefs.settlementCurrency();

        // No conversion needed if same currency
        if (settlementCurrency.equalsIgnoreCase(presentmentCurrency)) {
            LOG.debug("No FX conversion needed for payment {} (same currency: {})",
                    paymentId, presentmentCurrency);
            return null;
        }

        if (!prefs.autoConvert()) {
            LOG.debug("Auto-convert disabled for tenant {}, skipping FX for payment {}",
                    tenantId, paymentId);
            return null;
        }

        // Check if there's an existing rate lock for this payment
        FxRateLock lock = lockService.findByPaymentId(paymentId)
                .map(existingLock -> {
                    if (existingLock.isValid()) {
                        return existingLock;
                    }
                    LOG.info("Existing rate lock for payment {} expired, creating new lock", paymentId);
                    return lockService.lockRate(tenantId, presentmentCurrency, settlementCurrency);
                })
                .orElseGet(() -> {
                    LOG.info("No rate lock found for payment {}, creating new lock", paymentId);
                    return lockService.lockRate(tenantId, presentmentCurrency, settlementCurrency);
                });

        // Assign payment and consume the lock
        lock.assignPayment(paymentId);
        lock.consume(paymentId);

        // Create conversion record
        CurrencyConversion conversion = CurrencyConversion.fromLock(
                tenantId, paymentId, presentmentAmount, lock, prefs.fxMarkupBps());

        LOG.info("FX conversion for payment {}: {} {} → {} {} (rate: {}, markup: {}bps)",
                paymentId,
                presentmentAmount, presentmentCurrency,
                conversion.settlementAmountMinorUnits(), settlementCurrency,
                lock.getRate(), prefs.fxMarkupBps());

        return conversion;
    }
}
