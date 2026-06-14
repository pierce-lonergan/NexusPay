package io.nexuspay.gateway.adapter.out.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.nexuspay.common.event.Topics;
import io.nexuspay.common.rls.TenantWorkRunner;
import io.nexuspay.gateway.adapter.out.persistence.JpaWebhookEndpointRepository;
import io.nexuspay.gateway.adapter.out.persistence.WebhookEndpointEntity;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

/**
 * Delivers domain events to registered merchant webhook endpoints (GAP-030).
 *
 * Consumes from the nexuspay.payments Kafka topic and forwards events
 * to all enabled webhook endpoints that subscribe to the event type.
 * Each delivery is HMAC-SHA256 signed using the endpoint's secret.
 *
 * Retry/backoff for failed deliveries is deferred to Phase 2.
 * Currently logs failures without retry.
 */
@Component
public class WebhookDeliveryService {

    private static final Logger log = LoggerFactory.getLogger(WebhookDeliveryService.class);
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private static final String EVENT_TYPE_HEADER = "event_type";
    private static final String TENANT_ID_HEADER = "tenant_id";
    private static final String DEFAULT_TENANT = "default";

    private final JpaWebhookEndpointRepository endpointRepository;
    private final ObjectMapper objectMapper;
    private final TenantWorkRunner tenantWork;
    private final RestClient restClient;

    public WebhookDeliveryService(JpaWebhookEndpointRepository endpointRepository,
                                   ObjectMapper objectMapper,
                                   TenantWorkRunner tenantWork) {
        this.endpointRepository = endpointRepository;
        this.objectMapper = objectMapper;
        this.tenantWork = tenantWork;
        this.restClient = RestClient.builder()
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader("User-Agent", "NexusPay-Webhook/1.0")
                .build();
    }

    @KafkaListener(
            topics = Topics.PAYMENTS,
            groupId = Topics.GATEWAY_CONSUMER_GROUP,
            properties = "spring.json.trusted.packages=*"
    )
    public void onPaymentEvent(ConsumerRecord<String, String> record) {
        String eventType = extractEventType(record);
        if (eventType == null) {
            log.debug("Skipping event without event_type header");
            return;
        }

        String tenant = extractTenant(record);

        // Load the tenant's endpoints inside a tenant-bound REQUIRES_NEW tx (B-002): under
        // RLS enforcement the read must run on the APP role with the tenant bound at tx begin.
        // The blocking HTTP POSTs below stay OUTSIDE the tx so no DB connection is held across them.
        List<WebhookEndpointEntity> endpoints =
                tenantWork.callInTenant(tenant, () -> endpointRepository.findAllByTenantIdAndEnabledTrue(tenant));
        if (endpoints.isEmpty()) return;

        String payload = record.value();

        for (WebhookEndpointEntity endpoint : endpoints) {
            if (!subscribesToEvent(endpoint, eventType)) continue;

            try {
                deliverToEndpoint(endpoint, payload, eventType);
            } catch (Exception e) {
                log.warn("Webhook delivery failed: endpoint={} url={} event={}: {}",
                        endpoint.getId(), endpoint.getUrl(), eventType, e.getMessage());
            }
        }
    }

    private void deliverToEndpoint(WebhookEndpointEntity endpoint, String payload, String eventType) {
        String signature = computeSignature(payload, endpoint.getSecret());
        String timestamp = Instant.now().toString();

        restClient.post()
                .uri(endpoint.getUrl())
                .header("X-NexusPay-Signature", signature)
                .header("X-NexusPay-Timestamp", timestamp)
                .header("X-NexusPay-Event", eventType)
                .body(payload)
                .retrieve()
                .toBodilessEntity();

        log.debug("Webhook delivered: endpoint={} event={}", endpoint.getId(), eventType);
    }

    private boolean subscribesToEvent(WebhookEndpointEntity endpoint, String eventType) {
        List<String> events = endpoint.getEvents();
        if (events == null || events.isEmpty()) return true; // empty = all events
        return events.contains("*") || events.contains(eventType);
    }

    private String extractEventType(ConsumerRecord<String, String> record) {
        var header = record.headers().lastHeader(EVENT_TYPE_HEADER);
        if (header == null) {
            // Try parsing from payload
            try {
                JsonNode node = objectMapper.readTree(record.value());
                return node.path("event_type").asText(null);
            } catch (Exception e) {
                return null;
            }
        }
        return new String(header.value(), StandardCharsets.UTF_8);
    }

    /**
     * Resolves the tenant that owns this event. Prefers a Kafka {@code tenant_id} header
     * (forward-compat); otherwise reads {@code metadata.tenant_id} from the JSON payload,
     * falling back to {@code "default"} when neither is present.
     */
    private String extractTenant(ConsumerRecord<String, String> record) {
        var header = record.headers().lastHeader(TENANT_ID_HEADER);
        if (header != null) {
            String value = new String(header.value(), StandardCharsets.UTF_8);
            if (!value.isBlank()) return value;
        }
        try {
            JsonNode node = objectMapper.readTree(record.value());
            String tenantId = node.path("metadata").path("tenant_id").asText(null);
            if (tenantId != null && !tenantId.isBlank()) return tenantId;
        } catch (Exception e) {
            // fall through to default
        }
        return DEFAULT_TENANT;
    }

    private String computeSignature(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            log.error("Failed to compute webhook signature", e);
            return "";
        }
    }
}
