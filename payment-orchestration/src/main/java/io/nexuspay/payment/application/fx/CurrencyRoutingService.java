package io.nexuspay.payment.application.fx;

import io.nexuspay.payment.application.port.fx.CurrencyCapabilityRepository;
import io.nexuspay.payment.domain.fx.CurrencyCapability;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Currency-aware PSP routing service.
 * Filters available PSP connectors based on their currency capabilities
 * for presentment and settlement.
 *
 * @since 0.3.0 (Sprint 3.2)
 */
@Service
public class CurrencyRoutingService {

    private static final Logger LOG = LoggerFactory.getLogger(CurrencyRoutingService.class);

    private final CurrencyCapabilityRepository capabilityRepository;

    public CurrencyRoutingService(CurrencyCapabilityRepository capabilityRepository) {
        this.capabilityRepository = capabilityRepository;
    }

    /**
     * Finds PSP connectors that can process a payment in the given presentment currency.
     *
     * @param presentmentCurrency the payment currency (e.g., EUR)
     * @param amount              the payment amount for range validation
     * @return list of capable PSP connector names
     */
    public List<String> findPresentmentCapablePsps(String presentmentCurrency, BigDecimal amount) {
        List<CurrencyCapability> capabilities = capabilityRepository.findPresentmentCapable(presentmentCurrency);

        List<String> psps = capabilities.stream()
                .filter(cap -> cap.isAmountInRange(amount))
                .map(CurrencyCapability::pspConnector)
                .distinct()
                .collect(Collectors.toList());

        LOG.debug("Found {} PSPs supporting {} presentment for amount {}",
                psps.size(), presentmentCurrency, amount);
        return psps;
    }

    /**
     * Finds PSP connectors that can settle in the given currency.
     *
     * @param settlementCurrency the target settlement currency
     * @return list of capable PSP connector names
     */
    public List<String> findSettlementCapablePsps(String settlementCurrency) {
        return capabilityRepository.findSettlementCapable(settlementCurrency).stream()
                .map(CurrencyCapability::pspConnector)
                .distinct()
                .collect(Collectors.toList());
    }

    /**
     * Finds PSPs that support both presentment in one currency and settlement in another.
     * This is the optimal routing for cross-currency payments — the PSP handles the conversion.
     */
    public List<String> findCrossConversionCapablePsps(
            String presentmentCurrency, String settlementCurrency, BigDecimal amount) {

        List<String> presentmentPsps = findPresentmentCapablePsps(presentmentCurrency, amount);
        List<String> settlementPsps = findSettlementCapablePsps(settlementCurrency);

        List<String> intersection = presentmentPsps.stream()
                .filter(settlementPsps::contains)
                .collect(Collectors.toList());

        LOG.debug("Found {} PSPs supporting {} → {} conversion for amount {}",
                intersection.size(), presentmentCurrency, settlementCurrency, amount);
        return intersection;
    }

    /**
     * Checks if a specific PSP supports DCC (Dynamic Currency Conversion) for a currency.
     */
    public boolean supportsDcc(String pspConnector, String currencyCode) {
        return capabilityRepository.findByPspConnector(pspConnector).stream()
                .anyMatch(cap -> cap.currencyCode().equalsIgnoreCase(currencyCode) && cap.supportsDcc());
    }
}
