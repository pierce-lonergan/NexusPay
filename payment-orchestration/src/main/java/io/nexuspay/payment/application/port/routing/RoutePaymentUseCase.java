package io.nexuspay.payment.application.port.routing;

import io.nexuspay.payment.domain.routing.RoutingContext;
import io.nexuspay.payment.domain.routing.RoutingDecision;

/**
 * Inbound port for routing a payment to the optimal PSP.
 *
 * @since 0.3.0 (Sprint 3.3)
 */
public interface RoutePaymentUseCase {

    /**
     * Routes a payment by evaluating candidate PSPs using the tenant's configured strategy.
     * Returns an ordered decision with cascade fallback order.
     */
    RoutingDecision route(RoutingContext context);
}
