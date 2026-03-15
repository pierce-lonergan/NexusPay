package io.nexuspay.fraud.adapter.out.frm;

import io.nexuspay.fraud.application.dto.PaymentContext;
import io.nexuspay.fraud.application.port.out.FraudRiskPort;
import io.nexuspay.fraud.config.FraudProperties;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * FRM adapter for Signifyd fraud detection API.
 *
 * <p>Sends case data to Signifyd's guarantee API and returns a risk score.
 * Serves as the fallback FRM provider when Sift is unavailable.</p>
 *
 * @since 0.3.0 (Sprint 3.1)
 */
@Component
@ConditionalOnProperty(name = "nexuspay.fraud.enabled", havingValue = "true", matchIfMissing = true)
public class SignifydFraudAdapter implements FraudRiskPort {

    private static final Logger log = LoggerFactory.getLogger(SignifydFraudAdapter.class);

    private final RestClient restClient;
    private final FraudProperties.Frm.SignifydConfig config;

    public SignifydFraudAdapter(FraudProperties fraudProperties) {
        this.config = fraudProperties.getFrm().getSignifyd();
        String authHeader = "Basic " + Base64.getEncoder()
                .encodeToString((config.getApiKey() + ":").getBytes());

        this.restClient = RestClient.builder()
                .baseUrl(config.getApiUrl())
                .defaultHeader("Content-Type", "application/json")
                .defaultHeader("Authorization", authHeader)
                .build();
    }

    @Override
    @CircuitBreaker(name = "signifyd-frm", fallbackMethod = "assessFallback")
    public int assess(PaymentContext context) {
        if (config.getApiKey().isBlank()) {
            log.debug("Signifyd API key not configured, returning neutral score");
            return 50;
        }

        Map<String, Object> request = buildSignifydRequest(context);

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restClient.post()
                    .uri("/cases")
                    .body(request)
                    .retrieve()
                    .body(Map.class);

            return extractScore(response);
        } catch (Exception e) {
            log.error("Signifyd API call failed for payment {}: {}", context.paymentId(), e.getMessage());
            throw e;
        }
    }

    @Override
    public String providerName() {
        return "signifyd";
    }

    @Override
    public int priority() {
        return 2; // Secondary/fallback provider
    }

    private Map<String, Object> buildSignifydRequest(PaymentContext context) {
        Map<String, Object> req = new HashMap<>();

        Map<String, Object> purchase = new HashMap<>();
        purchase.put("orderAmount", context.amountMinorUnits() / 100.0); // Signifyd uses major units
        purchase.put("orderCurrency", context.currency());
        purchase.put("avsResponseCode", "Y");
        purchase.put("cvvResponseCode", "M");
        req.put("purchase", purchase);

        Map<String, Object> card = new HashMap<>();
        card.put("cardBin", context.cardBin());
        card.put("last4", "0000"); // We don't have last4, placeholder
        req.put("card", card);

        Map<String, Object> userAccount = new HashMap<>();
        userAccount.put("accountNumber", context.customerId());
        userAccount.put("email", context.customerEmail());
        req.put("userAccount", userAccount);

        return req;
    }

    @SuppressWarnings("unchecked")
    private int extractScore(Map<String, Object> response) {
        if (response == null) return 50;

        // Signifyd returns "investigationId" and "guaranteeDisposition"
        Object score = response.get("score");
        if (score instanceof Number n) {
            // Signifyd scores are 0-1000, normalize to 0-100
            return Math.min(100, n.intValue() / 10);
        }

        return 50;
    }

    @SuppressWarnings("unused")
    private int assessFallback(PaymentContext context, Throwable t) {
        log.warn("Signifyd circuit breaker triggered for payment {}: {}", context.paymentId(), t.getMessage());
        throw new RuntimeException("Signifyd FRM unavailable", t);
    }
}
