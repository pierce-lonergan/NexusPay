package io.nexuspay.payment.adapter.in.webhook;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.nexuspay.common.id.PrefixedId;
import io.nexuspay.payment.adapter.out.outbox.OutboxEvent;
import io.nexuspay.payment.adapter.out.outbox.OutboxEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.nexuspay.payment.application.screening.ScreeningOriginService;
import io.nexuspay.payment.domain.event.PaymentEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;

/**
 * Receives webhooks from HyperSwitch and processes them through the outbox pattern.
 *
 * Flow:
 *   1. Verify HMAC-SHA512 signature — UNCONDITIONALLY FAIL-CLOSED (SEC-28): a missing/blank secret,
 *      a missing signature, or an invalid signature ALL return 401 before any parse/state touch.
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
    private final ScreeningOriginService screeningOrigins;
    private final String webhookSecret;

    /**
     * SEC-09 (SEC-batch-1b) observability: counts webhook events for a real payment id whose owning
     * tenant could NOT be resolved from the origin store, so they were stamped {@code "default"} and will
     * be dropped by the tenant-scoped webhook consumer. Alert on a non-zero rate — it means a backfill
     * gap or a missing origin write, i.e. a merchant silently not receiving webhooks.
     */
    private final Counter webhookDefaultTenantStamped;

    public HyperSwitchWebhookController(
            InboundWebhookRepository webhookRepository,
            OutboxEventRepository outboxRepository,
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            ScreeningOriginService screeningOrigins,
            MeterRegistry meterRegistry,
            @org.springframework.beans.factory.annotation.Value("${nexuspay.hyperswitch.webhook-secret:}") String webhookSecret) {
        this.webhookRepository = webhookRepository;
        this.outboxRepository = outboxRepository;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.screeningOrigins = screeningOrigins;
        this.webhookSecret = webhookSecret;
        this.webhookDefaultTenantStamped = Counter.builder("nexuspay.webhook.outbox.default_tenant_stamped")
                .description("Webhook events stamped tenant=default because the payment's origin tenant "
                        + "could not be resolved (these events are NOT delivered to the owning tenant)")
                .register(meterRegistry);
    }

    @PostMapping("/hyperswitch")
    @Transactional
    public ResponseEntity<Void> handleWebhook(
            @RequestBody String rawPayload,
            @RequestHeader(value = "x-webhook-signature", required = false) String signature) {

        // Step 1: Verify HMAC signature — UNCONDITIONALLY FAIL-CLOSED (SEC-28).
        // Mirrors DisputeWebhookHandler: a missing/blank secret, a missing signature, or an invalid
        // signature ALL return 401 BEFORE any parse / state touch. The previous "skip verification when
        // no secret configured" dev branch FAILED OPEN — an unsigned webhook was accepted and written to
        // the outbox. Dev still works because the resolved dev secret is the non-blank
        // "webhook_secret_for_local" (prod-boot-guarded by StartupSecretsValidator.KNOWN_DEFAULTS), so
        // verification runs against it rather than being skipped.
        if (webhookSecret == null || webhookSecret.isBlank()) {
            log.error("hyperswitch webhook secret not configured — rejecting (fail-closed)");
            return ResponseEntity.status(401).build();
        }
        if (!verifySignature(rawPayload, signature)) {
            log.warn("Webhook signature verification failed");
            return ResponseEntity.status(401).build();
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

        // SEC-15: the claim above is NOT transactional (Valkey is not enlisted in the DB tx). If this
        // @Transactional handler rolls back AFTER the claim (a DB/outbox failure, a constraint violation,
        // or any unchecked exception before commit) the eventId would stay suppressed for DEDUP_TTL (24h)
        // and the legitimate, RETRYABLE webhook would never be redelivered -> a lost capture/refund.
        // Release the claim UNLESS the tx COMMITS:
        //   * In a Spring-managed tx (the production @Transactional path) register an afterCompletion
        //     synchronization that deletes the dedup key on any non-COMMITTED status (ROLLED_BACK or the
        //     heuristic UNKNOWN). A COMMITTED tx keeps the key, so a true duplicate of a SUCCESSFULLY
        //     processed event stays deduped for the TTL (invariant 3 preserved).
        //   * With no active synchronization (e.g. a direct unit-test call with no tx manager) fall back
        //     to try/catch: release the claim if the post-claim body throws. Defense-in-depth.
        // The claim stays FIRST (above) so genuinely-concurrent duplicates are still rejected during
        // processing — the no-double-processing window is unchanged.
        final String dedupKey = DEDUP_PREFIX + eventId;
        boolean syncRegistered = false;
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status != TransactionSynchronization.STATUS_COMMITTED) {
                        // ROLLED_BACK or UNKNOWN -> the event was NOT durably recorded; release the claim
                        // so HyperSwitch's redelivery is processed instead of being suppressed for 24h.
                        redisTemplate.delete(dedupKey);
                    }
                }
            });
            syncRegistered = true;
        }

        try {
            return processClaimedWebhook(webhook, payload, eventId, eventType, paymentId);
        } catch (RuntimeException e) {
            if (!syncRegistered) {
                // No tx synchronization is driving the release; do it here so the claim is freed even on
                // the no-active-tx path (keeps the existing direct-call tests + defense-in-depth honest).
                redisTemplate.delete(dedupKey);
            }
            throw e;
        }
    }

    /**
     * Persists the claimed webhook + writes the canonical outbox row (SEC-09 tenant stamping) inside the
     * caller's transaction. Extracted from {@link #handleWebhook} so the SEC-15 dedup-release wrapping is
     * a single, readable seam around the durable writes. Throws on a serialization/persistence failure so
     * the caller can release the Valkey claim (the tx rolls back).
     */
    private ResponseEntity<Void> processClaimedWebhook(InboundWebhook webhook, JsonNode payload,
            String eventId, String eventType, String paymentId) {
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

            // SEC-09 (B-009): stamp the TRUSTED event tenant on the outbox row so the webhook consumer can
            // fan out ONLY to the owning tenant's endpoints. The tenant is recalled from the server-owned
            // screening-origin store keyed by the gateway payment id (never client metadata). When the
            // origin is absent/blank we fall back to "default", which matches no real-tenant endpoint, so
            // the event is NOT delivered (a delivery gap, never a cross-tenant leak).
            //
            // SEC-batch-1b correction: this "default" fallback is NOT only a transient backlog the 7-day
            // outbox retention clears. For payments created BEFORE the origin store (V4022) — which have no
            // origin row — EVERY new lifecycle event (capture/refund/dispute) stamps "default" for the
            // whole payment lifecycle, so the owning merchant would silently stop receiving webhooks for
            // older active payments. The V4029 backfill populates payment_screening_origin from the
            // authoritative event_outbox/journal_entries records for those pre-existing payments, so
            // find(paymentId) resolves the REAL tenant again. The warn+metric below makes any RESIDUAL
            // "default" stamping (a payment id with no recoverable origin) observable rather than silent.
            boolean hasPaymentId = paymentId != null;
            String resolvedTenant = hasPaymentId
                    ? screeningOrigins.find(paymentId)
                        .map(ScreeningOriginService.Origin::tenantId)
                        .filter(t -> t != null && !t.isBlank())
                        .orElse(null)
                    : null;
            String eventTenant = resolvedTenant != null ? resolvedTenant : "default";

            if (hasPaymentId && resolvedTenant == null) {
                // A real payment event whose tenant we could not recover -> it will route to "default" and
                // be dropped by the tenant-scoped webhook consumer. Surface it so the drop volume is visible
                // (alert on this counter; a non-zero rate means a backfill gap or a missing origin write).
                log.warn("SEC-09 webhook tenant unresolved for payment_id={} event_type={} — stamping "
                        + "\"default\"; this event will NOT be delivered to the owning tenant's endpoints",
                        paymentId, nexusEventType);
                webhookDefaultTenantStamped.increment();
            }

            outboxRepository.save(new OutboxEvent(
                    aggregateType, aggregateId, nexusEventType, outboxPayload, eventTenant, 1));
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
            // Constant-time comparison — String.equalsIgnoreCase short-circuits on
            // the first differing char, leaking a timing side-channel usable to
            // forge a signature byte by byte.
            return MessageDigest.isEqual(
                    computed.getBytes(StandardCharsets.UTF_8),
                    signature.toLowerCase().getBytes(StandardCharsets.UTF_8));
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
