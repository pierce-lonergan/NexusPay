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

import java.util.HashMap;
import java.util.Map;

/**
 * FRM adapter for Sift Science fraud detection API.
 *
 * <p>Sends transaction data to Sift's decision API and returns a risk score.
 * Wrapped with Resilience4j circuit breaker for fault tolerance.</p>
 *
 * @since 0.3.0 (Sprint 3.1)
 */
@Component
@ConditionalOnProperty(name = "nexuspay.fraud.enabled", havingValue = "true", matchIfMissing = true)
public class SiftFraudAdapter implements FraudRiskPort {

    private static final Logger log = LoggerFactory.getLogger(SiftFraudAdapter.class);

    private final RestClient restClient;
    private final FraudProperties.Frm.SiftConfig config;

    public SiftFraudAdapter(FraudProperties fraudProperties) {
        this.config = fraudProperties.getFrm().getSift();
        this.restClient = RestClient.builder()
                .baseUrl(config.getApiUrl())
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    @Override
    @CircuitBreaker(name = "sift-frm", fallbackMethod = "assessFallback")
    public int assess(PaymentContext context) {
        if (config.getApiKey().isBlank()) {
            log.debug("Sift API key not configured, returning neutral score");
            return 50; // Neutral when not configured
        }

        Map<String, Object> request = buildSiftRequest(context);

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restClient.post()
                    .uri("/events")
                    .header("Authorization", "Bearer " + config.getApiKey())
                    .body(request)
                    .retrieve()
                    .body(Map.class);

            return extractScore(response);
        } catch (Exception e) {
            log.error("Sift API call failed for payment {}: {}", context.paymentId(), e.getMessage());
            throw e; // Let circuit breaker handle it
        }
    }

    @Override
    public String providerName() {
        return "sift";
    }

    @Override
    public int priority() {
        return 1; // Primary provider
    }

    private Map<String, Object> buildSiftRequest(PaymentContext context) {
        Map<String, Object> req = new HashMap<>();
        req.put("$type", "$transaction");
        req.put("$api_key", config.getApiKey());
        req.put("$user_id", context.customerId());
        req.put("$user_email", context.customerEmail());
        req.put("$amount", context.amountMinorUnits() * 10000L); // Sift uses micros
        req.put("$currency_code", context.currency());
        req.put("$ip", context.ipAddress());
        req.put("$transaction_id", context.paymentId());

        if (context.cardBin() != null) {
            Map<String, Object> paymentMethod = new HashMap<>();
            paymentMethod.put("$payment_type", "$credit_card");
            paymentMethod.put("$card_bin", context.cardBin());
            req.put("$payment_method", paymentMethod);
        }

        return req;
    }

    @SuppressWarnings("unchecked")
    private int extractScore(Map<String, Object> response) {
        if (response == null) return 50;

        Object scoreResponse = response.get("score_response");
        if (scoreResponse instanceof Map<?, ?> sr) {
            Object scores = ((Map<String, Object>) sr).get("scores");
            if (scores instanceof Map<?, ?> scoresMap) {
                Object paymentAbuse = ((Map<String, Object>) scoresMap).get("payment_abuse");
                if (paymentAbuse instanceof Map<?, ?> pa) {
                    Object score = ((Map<String, Object>) pa).get("score");
                    if (score instanceof Number n) {
                        return (int) (n.doubleValue() * 100); // Sift returns 0.0-1.0
                    }
                }
            }
        }

        return 50; // Default neutral if response structure unexpected
    }

    @SuppressWarnings("unused")
    private int assessFallback(PaymentContext context, Throwable t) {
        log.warn("Sift circuit breaker triggered for payment {}: {}", context.paymentId(), t.getMessage());
        throw new RuntimeException("Sift FRM unavailable", t);
    }
}
