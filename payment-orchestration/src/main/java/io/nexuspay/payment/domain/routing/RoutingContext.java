package io.nexuspay.payment.domain.routing;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Context for a routing decision. Contains all payment and tenant attributes
 * needed by routing strategies to score PSP candidates.
 *
 * @since 0.3.0 (Sprint 3.3)
 */
public record RoutingContext(
        String tenantId,
        String paymentId,
        BigDecimal amount,
        String currency,
        String cardBrand,
        String cardType,
        String issuingCountry,
        String ipCountry,
        boolean fraudCleared,
        Map<String, Object> metadata
) {

    /**
     * Builder-style factory for common use cases.
     */
    public static RoutingContext of(String tenantId, String paymentId,
                                     BigDecimal amount, String currency) {
        return new RoutingContext(tenantId, paymentId, amount, currency,
                null, null, null, null, true, Map.of());
    }

    public RoutingContext withCard(String brand, String type, String issuingCountry) {
        return new RoutingContext(tenantId, paymentId, amount, currency,
                brand, type, issuingCountry, ipCountry, fraudCleared, metadata);
    }

    public RoutingContext withFraudResult(boolean cleared) {
        return new RoutingContext(tenantId, paymentId, amount, currency,
                cardBrand, cardType, issuingCountry, ipCountry, cleared, metadata);
    }
}
