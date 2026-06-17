package io.nexuspay.payment.application.fx;

import io.nexuspay.common.tenant.TenantOwnership;
import io.nexuspay.payment.domain.fx.DccOffer;
import io.nexuspay.payment.domain.fx.FxRate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages Dynamic Currency Conversion (DCC) offers for cross-currency payments.
 * <p>
 * DCC allows cardholders to see and choose to pay in their home currency
 * at the point of sale. Regulatory requirements mandate clear rate disclosure
 * and explicit customer consent before DCC is applied.
 * <p>
 * Flow:
 * 1. {@link #createOffer} — generates a DCC offer with rate + markup disclosure
 * 2. Customer reviews offer (rate, margin, converted amount)
 * 3. {@link #acceptOffer} or {@link #declineOffer} — records the customer's decision
 * 4. If accepted, the payment proceeds in the cardholder's currency with the DCC rate
 * <p>
 * Resolves GAP-044: adds complete DCC flow with rate disclosure and consent tracking.
 *
 * @since 0.3.1 (GAP-044)
 */
@Service
public class DynamicCurrencyConversionService {

    private static final Logger LOG = LoggerFactory.getLogger(DynamicCurrencyConversionService.class);

    private final FxRateService rateService;
    private final CurrencyRoutingService routingService;
    private final int defaultDccMarkupBps;
    private final int offerValidityMinutes;

    /**
     * In-memory store for DCC offers (Phase 1). Production would use a persistence layer.
     * Offers are short-lived (5min default) so in-memory is acceptable.
     */
    private final Map<UUID, DccOffer> offerStore = new ConcurrentHashMap<>();

    public DynamicCurrencyConversionService(
            FxRateService rateService,
            CurrencyRoutingService routingService,
            @Value("${nexuspay.fx.dcc.default-markup-bps:300}") int defaultDccMarkupBps,
            @Value("${nexuspay.fx.dcc.offer-validity-minutes:5}") int offerValidityMinutes) {
        this.rateService = rateService;
        this.routingService = routingService;
        this.defaultDccMarkupBps = defaultDccMarkupBps;
        this.offerValidityMinutes = offerValidityMinutes;
    }

    /**
     * Checks if DCC is available for a payment.
     * DCC requires: currencies differ, PSP supports DCC for the presentment currency.
     */
    public boolean isDccAvailable(String tenantId, String pspConnector,
                                   String presentmentCurrency, String cardholderCurrency) {
        if (presentmentCurrency.equalsIgnoreCase(cardholderCurrency)) {
            return false;
        }
        return routingService.supportsDcc(pspConnector, presentmentCurrency);
    }

    /**
     * Creates a DCC offer for a payment.
     * The offer includes the converted amount, applied rate, and markup — all required
     * for regulatory-compliant DCC disclosure to the cardholder.
     *
     * @param tenantId                    the merchant tenant
     * @param paymentId                   the payment to offer DCC for
     * @param pspConnector                the PSP processing the payment
     * @param presentmentCurrency         the original payment currency
     * @param presentmentAmountMinorUnits the original amount
     * @param cardholderCurrency          the cardholder's home currency
     * @return the DCC offer, or empty if DCC is not available
     */
    public Optional<DccOffer> createOffer(
            String tenantId, String paymentId, String pspConnector,
            String presentmentCurrency, long presentmentAmountMinorUnits,
            String cardholderCurrency) {

        if (!isDccAvailable(tenantId, pspConnector, presentmentCurrency, cardholderCurrency)) {
            LOG.debug("DCC not available for {} on PSP {} ({} → {})",
                    paymentId, pspConnector, presentmentCurrency, cardholderCurrency);
            return Optional.empty();
        }

        try {
            // Get the base rate (without DCC markup — markup is applied in the offer)
            FxRate baseRate = rateService.getRate(tenantId, presentmentCurrency, cardholderCurrency);

            DccOffer offer = DccOffer.create(
                    tenantId, paymentId,
                    presentmentCurrency, presentmentAmountMinorUnits,
                    cardholderCurrency, baseRate, defaultDccMarkupBps,
                    offerValidityMinutes
            );

            offerStore.put(offer.id(), offer);
            LOG.info("Created DCC offer {} for payment {}: {} {} → {} {} (rate: {}, markup: {}bps)",
                    offer.id(), paymentId,
                    presentmentAmountMinorUnits, presentmentCurrency,
                    offer.cardholderAmountMinorUnits(), cardholderCurrency,
                    offer.offeredRate(), defaultDccMarkupBps);

            return Optional.of(offer);
        } catch (Exception e) {
            LOG.warn("Failed to create DCC offer for payment {}: {}", paymentId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Retrieves a DCC offer by ID.
     */
    public Optional<DccOffer> getOffer(UUID offerId) {
        return Optional.ofNullable(offerStore.get(offerId));
    }

    /**
     * Records customer acceptance of a DCC offer, scoped to the caller's tenant.
     * After acceptance, the payment should proceed in the cardholder's currency.
     *
     * <p>SEC-27: a by-id mutation must not be reachable cross-tenant. The offer is resolved through
     * the in-memory store and its tenant asserted against {@code callerTenant} before mutation, so a
     * foreign-owned or absent id collapses to a single {@code ResourceNotFoundException} -> 404 with
     * no existence oracle.</p>
     *
     * @return the accepted offer with updated status
     */
    public DccOffer acceptOffer(UUID offerId, String callerTenant) {
        DccOffer offer = TenantOwnership.assertOwned(
                getOffer(offerId), callerTenant, DccOffer::tenantId, "DCC offer");

        DccOffer accepted = offer.accept();
        offerStore.put(offer.id(), accepted);
        LOG.info("DCC offer {} accepted for payment {} — proceeding in {} at rate {}",
                offer.id(), accepted.paymentId(), accepted.cardholderCurrency(), accepted.offeredRate());
        return accepted;
    }

    /**
     * Records customer decline of a DCC offer, scoped to the caller's tenant.
     * The payment proceeds in the original presentment currency.
     *
     * <p>SEC-27: same tenant-scoped resolution as {@link #acceptOffer(UUID, String)} — foreign/absent
     * ids both 404 with no existence oracle.</p>
     *
     * @return the declined offer with updated status
     */
    public DccOffer declineOffer(UUID offerId, String callerTenant) {
        DccOffer offer = TenantOwnership.assertOwned(
                getOffer(offerId), callerTenant, DccOffer::tenantId, "DCC offer");

        DccOffer declined = offer.decline();
        offerStore.put(offer.id(), declined);
        LOG.info("DCC offer {} declined for payment {} — proceeding in {}",
                offer.id(), declined.paymentId(), declined.presentmentCurrency());
        return declined;
    }

    /**
     * Gets the DCC offer associated with a payment, if any.
     */
    public Optional<DccOffer> findByPaymentId(String paymentId) {
        return offerStore.values().stream()
                .filter(o -> paymentId.equals(o.paymentId()))
                .findFirst();
    }

    /**
     * Returns a disclosure map suitable for including in the payment response.
     * Contains all fields required for regulatory DCC disclosure.
     */
    public Map<String, Object> buildDisclosure(DccOffer offer) {
        return Map.of(
                "dcc_offer_id", offer.id().toString(),
                "presentment_currency", offer.presentmentCurrency(),
                "presentment_amount", offer.presentmentAmountMinorUnits(),
                "cardholder_currency", offer.cardholderCurrency(),
                "cardholder_amount", offer.cardholderAmountMinorUnits(),
                "exchange_rate", offer.offeredRate().toPlainString(),
                "markup_bps", offer.markupBps(),
                "margin_amount", offer.marginAmount().toPlainString(),
                "rate_provider", offer.rateProvider(),
                "expires_at", offer.expiresAt().toString()
        );
    }
}
