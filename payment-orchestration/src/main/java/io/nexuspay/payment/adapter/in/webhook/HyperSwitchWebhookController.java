package io.nexuspay.payment.adapter.in.webhook;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.nexuspay.common.id.PrefixedId;
import io.nexuspay.payment.adapter.out.outbox.OutboxEvent;
import io.nexuspay.payment.adapter.out.outbox.OutboxEventRepository;
import io.nexuspay.payment.domain.event.PaymentEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;

/**
 * Receives webhooks from HyperSwitch and processes them through the outbox pattern.
 *
 * Flow:
 *   1. Verify HMAC-SHA512 signature
 *   2. Persist raw JSON payload to inbound_webhooks (debugging/replay)
 *   3. Dedup by event_id in Valkey (SET NX with 24h TTL)
 *   4. Write to event_outbox in same DB transaction
 *   5. Return 200 OK immediately
 *
 * Processing happens asynchronously via OutboxRelay → Kafka.
 *
 * @see <a href="https://docs.hyperswitch.io/explore-hyperswitch/webhooks">HyperSwitch Webhooks</a>
 */
@RestController
@RequestMapping("/internal/webhooks")
public class HyperSwitchWebhookController {

    private static final Logger log = LoggerFactory.getLogger(HyperSwitchWebhookController.class);
    private static final String HMAC_ALGORITHM = "HmacSHA512";
    private static final String DEDUP_PREFIX = "webhook:dedup:";
    private static final Duration DEDUP_TTL = Duration.ofHours(24);

    private final InboundWebhookRepository webhookRepository;
    private final OutboxEventRepository outboxRepository;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final String webhookSecret;

    public HyperSwitchWebhookController(
            InboundWebhookRepository webhookRepository,
            OutboxEventRepository outboxRepository,
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            @org.springframework.beans.factory.annotation.Value("${nexuspay.hyperswitch.webhook-secret:}") String webhookSecret) {
        this.webhookRepository = webhookRepository;
        this.outboxRepository = outboxRepository;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.webhookSecret = webhookSecret;
    }

    @PostMapping("/hyperswitch")
    @Transactional
    public ResponseEntity<Void> handleWebhook(
            @RequestBody String rawPayload,
            @RequestHeader(value = "x-webhook-signature", required = false) String signature) {

        // Step 1: Verify HMAC signature (skip in dev if no secret configured)
        if (webhookSecret != null && !webhookSecret.isBlank()) {
            if (!verifySignature(rawPayload, signature)) {
                log.warn("Webhook signature verification failed");
                return ResponseEntity.status(401).build();
            }
        }

        // Parse the payload to extract event metadata
        JsonNode payload;
        try {
            payload = objectMapper.readTree(rawPayload);
        } catch (JsonProcessingException e) {
            log.error("Failed to parse webhook payload", e);
            return ResponseEntity.badRequest().build();
        }

        String eventId = extractField(payload, "event_id", PrefixedId.event());
        String eventType = extractField(payload, "event_type", "unknown");
        String paymentId = extractNestedField(payload, "content", "object", "payment_id");

        MDC.put("payment_id", paymentId != null ? paymentId : "");
        log.info("Webhook received: event_id={} type={} payment_id={}", eventId, eventType, paymentId);

        // Step 2: Persist raw payload
        String webhookId = PrefixedId.webhook();
        InboundWebhook webhook = new InboundWebhook(webhookId, eventId, eventType, rawPayload);

        // Step 3: Dedup via Valkey
        Boolean isNew = redisTemplate.opsForValue()
                .setIfAbsent(DEDUP_PREFIX + eventId, webhookId, DEDUP_TTL);

        if (Boolean.FALSE.equals(isNew)) {
            log.info("Duplicate webhook ignored: event_id={}", eventId);
            return ResponseEntity.ok().build();
        }

        webhookRepository.save(webhook);

        // Step 4: Write to event_outbox
        String nexusEventType = mapHyperSwitchEventType(eventType);
        String aggregateType = nexusEventType.startsWith("Refund") ? "Refund" : "Payment";
        String aggregateId = paymentId != null ? paymentId : eventId;

        try {
            String outboxPayload = objectMapper.writeValueAsString(Map.of(
                    "event_id", PrefixedId.event(),
                    "event_type", nexusEventType,
                    "aggregate_type", aggregateType,
                    "aggregate_id", aggregateId,
                    "timestamp", Instant.now().toString(),
                    "version", 1,
                    "metadata", Map.of(
                            "source", "hyperswitch_webhook",
                            "original_event_id", eventId
                    ),
                    "payload", payload.path("content").path("object")
            ));

            outboxRepository.save(new OutboxEvent(aggregateType, aggregateId, nexusEventType, outboxPayload));
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize outbox event", e);
            webhook.markFailed();
            webhookRepository.save(webhook);
            return ResponseEntity.internalServerError().build();
        }

        webhook.markProcessed();
        webhookRepository.save(webhook);

        // Step 5: Return 200 immediately
        return ResponseEntity.ok().build();
    }

    /**
     * Verifies the HMAC-SHA512 signature of the webhook payload.
     */
    private boolean verifySignature(String payload, String signature) {
        if (signature == null || signature.isBlank()) return false;
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            String computed = HexFormat.of().formatHex(hash);
            return computed.equalsIgnoreCase(signature);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            log.error("HMAC verification error", e);
            return false;
        }
    }

    /**
     * Maps HyperSwitch event types to NexusPay event types.
     */
    private String mapHyperSwitchEventType(String hsEventType) {
        if (hsEventType == null) return "Unknown";
        return switch (hsEventType.toLowerCase()) {
            case "payment_succeeded" -> PaymentEvent.PAYMENT_CAPTURED;
            case "payment_failed" -> PaymentEvent.PAYMENT_FAILED;
            case "payment_processing" -> PaymentEvent.PAYMENT_AUTHORIZED;
            case "payment_cancelled" -> PaymentEvent.PAYMENT_VOIDED;
            case "refund_succeeded" -> PaymentEvent.REFUND_COMPLETED;
            case "refund_failed" -> PaymentEvent.REFUND_FAILED;
            default -> hsEventType;
        };
    }

    private String extractField(JsonNode node, String field, String defaultValue) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? defaultValue : value.asText();
    }

    private String extractNestedField(JsonNode node, String... path) {
        JsonNode current = node;
        for (String field : path) {
            current = current.path(field);
            if (current.isMissingNode()) return null;
        }
        return current.isNull() ? null : current.asText();
    }
}
