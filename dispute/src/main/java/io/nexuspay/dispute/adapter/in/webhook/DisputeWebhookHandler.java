package io.nexuspay.dispute.adapter.in.webhook;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.nexuspay.dispute.application.service.AutoRepresentmentService;
import io.nexuspay.dispute.application.service.DisputeLifecycleService;
import io.nexuspay.dispute.domain.Dispute;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;

/**
 * Receives dispute notifications from PSPs (HyperSwitch webhook relay).
 *
 * <p>When HyperSwitch forwards a dispute/chargeback event from the payment
 * processor, this handler creates or updates the dispute in NexusPay and
 * triggers the auto-representment evaluation pipeline.</p>
 *
 * <h3>Security (SEC-BATCH-2 / audit SEC-01, B-001)</h3>
 * <p>This is a money-moving webhook: {@code dispute.opened} posts a chargeback
 * reserve to the ledger. It is therefore authenticated at the application layer
 * by an HMAC-SHA512 signature over the RAW request body, mirroring
 * {@code HyperSwitchWebhookController} — EXCEPT the gate here is UNCONDITIONAL
 * and FAIL-CLOSED: a missing secret, a missing signature, or an invalid
 * signature ALL return 401 BEFORE any JSON is parsed or any state is touched
 * (HyperSwitch's dev fail-open "skip verification when no secret configured"
 * branch is intentionally dropped). The transport endpoint stays {@code permitAll}
 * (iam SecurityConfig) because there is no authenticated principal on a PSP
 * webhook thread — authenticity is established by the shared-secret HMAC.</p>
 *
 * <p>The tenant is SERVER-AUTHORITATIVE: it is read from the HMAC-VERIFIED
 * payload ({@code tenant_id}), never from a client-supplied {@code X-Tenant-Id}
 * header (SEC-BATCH-1 / L-048). A client header, if sent, is ignored.</p>
 *
 * <p>Replay safety is provided by {@code DisputeLifecycleService.openDispute},
 * which is idempotent on {@code (tenantId, externalDisputeId)} (DB UNIQUE
 * backstop, Flyway V4026) — a replayed {@code dispute.opened} books NO second
 * chargeback reserve and returns {@code status:duplicate}.</p>
 *
 * <h3>Supported Event Types</h3>
 * <ul>
 *   <li>{@code dispute.opened} — new chargeback notification</li>
 *   <li>{@code dispute.evidence_needed} — evidence requested by network</li>
 *   <li>{@code dispute.won} — dispute resolved in merchant's favour</li>
 *   <li>{@code dispute.lost} — dispute resolved in cardholder's favour</li>
 *   <li>{@code dispute.expired} — evidence deadline passed</li>
 * </ul>
 *
 * @since 0.2.4 (Sprint 2.4)
 */
@RestController
@RequestMapping("/internal/webhooks/disputes")
public class DisputeWebhookHandler {

    private static final Logger log = LoggerFactory.getLogger(DisputeWebhookHandler.class);
    private static final String HMAC_ALGORITHM = "HmacSHA512";

    private final DisputeLifecycleService lifecycleService;
    private final AutoRepresentmentService autoRepresentmentService;
    private final ObjectMapper objectMapper;
    private final String webhookSecret;

    public DisputeWebhookHandler(DisputeLifecycleService lifecycleService,
                                  AutoRepresentmentService autoRepresentmentService,
                                  ObjectMapper objectMapper,
                                  @Value("${nexuspay.dispute.webhook-secret:}") String webhookSecret) {
        this.lifecycleService = lifecycleService;
        this.autoRepresentmentService = autoRepresentmentService;
        this.objectMapper = objectMapper;
        this.webhookSecret = webhookSecret;
    }

    /**
     * Processes incoming dispute webhook events from HyperSwitch.
     *
     * <p>Reads the RAW body + {@code x-webhook-signature} header (same header
     * name + algorithm as HyperSwitch, so the PSP/relay signs identically). The
     * fail-closed HMAC gate runs BEFORE any parse: missing secret / missing sig /
     * invalid sig → 401. After the gate, the tenant is taken from the verified
     * payload, never a header.</p>
     */
    @PostMapping
    @Transactional
    public ResponseEntity<Map<String, String>> handleDisputeWebhook(
            @RequestBody String rawPayload,
            @RequestHeader(value = "x-webhook-signature", required = false) String signature) {

        // --- SEC-BATCH-2 fail-closed HMAC gate (BEFORE any parse / tenant read) ---
        if (webhookSecret == null || webhookSecret.isBlank()) {
            log.error("dispute webhook secret not configured — rejecting (fail-closed)");
            return ResponseEntity.status(401).build();
        }
        if (!verifySignature(rawPayload, signature)) {
            log.warn("dispute webhook signature verification failed");
            return ResponseEntity.status(401).build();
        }

        // --- Parse the now-authenticated raw body ---
        JsonNode payload;
        try {
            payload = objectMapper.readTree(rawPayload);
        } catch (JsonProcessingException e) {
            log.error("Failed to parse dispute webhook payload", e);
            return ResponseEntity.badRequest().body(Map.of("error", "Malformed JSON"));
        }

        String eventType = text(payload, "event_type");
        // Server-authoritative tenant: from the HMAC-verified payload only,
        // NEVER from a client-supplied X-Tenant-Id header (SEC-BATCH-1 / L-048).
        String tenantId = text(payload, "tenant_id");
        log.info("Dispute webhook received: type={}, tenant={}", eventType, tenantId);

        if (eventType == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Missing event_type"));
        }

        return switch (eventType) {
            case "dispute.opened" -> handleOpened(tenantId, payload);
            case "dispute.evidence_needed" -> handleEvidenceNeeded(payload);
            case "dispute.won" -> handleWon(payload);
            case "dispute.lost" -> handleLost(payload);
            case "dispute.expired" -> handleExpired(payload);
            default -> {
                log.warn("Unrecognised dispute webhook event: {}", eventType);
                yield ResponseEntity.ok(Map.of("status", "ignored"));
            }
        };
    }

    private ResponseEntity<Map<String, String>> handleOpened(String tenantId, JsonNode payload) {
        // Tenant is server-authoritative — it must be present in the signed body.
        if (tenantId == null || tenantId.isBlank()) {
            log.warn("dispute.opened missing server-authoritative tenant_id in verified payload");
            return ResponseEntity.badRequest().body(Map.of("error", "Missing tenant_id"));
        }

        String externalId = text(payload, "external_dispute_id");
        // Idempotency depends on a non-blank external id: dedup keys on
        // (tenantId, externalDisputeId) at both the service lookup and the V4026
        // UNIQUE constraint, and Postgres treats multiple NULLs as DISTINCT — so a
        // signed dispute.opened with a missing/blank external_dispute_id would
        // BYPASS dedup and let two such events each post a chargeback reserve. A
        // real PSP dispute always carries an external id, so reject (400) here,
        // BEFORE openDispute/createChargebackReserve. (V4026 stays unchanged.)
        if (externalId == null || externalId.isBlank()) {
            log.warn("dispute.opened missing external_dispute_id (tenant={}) — rejecting to preserve "
                    + "idempotency; a blank external id bypasses (tenant, external_id) dedup", tenantId);
            return ResponseEntity.badRequest().body(Map.of("error", "Missing external_dispute_id"));
        }

        String paymentId = text(payload, "payment_id");
        String reasonCode = textOrDefault(payload, "reason_code", "OTHER");
        String reasonDesc = text(payload, "reason_description");
        long amount = payload.path("amount").asLong(0L);
        String currency = textOrDefault(payload, "currency", "USD");
        String network = text(payload, "network");
        String dueDateStr = text(payload, "evidence_due_date");
        Instant dueDate = dueDateStr != null ? Instant.parse(dueDateStr) : null;

        // openDispute is idempotent on (tenantId, externalDisputeId): a replay
        // returns the EXISTING dispute without re-posting the chargeback reserve.
        // To report "created" vs "duplicate" we check whether a dispute with this
        // (tenant, external id) already existed BEFORE the call. externalId is
        // guaranteed non-blank here (rejected above).
        boolean preExisting =
                lifecycleService.findByTenantIdAndExternalDisputeId(tenantId, externalId).isPresent();

        Dispute dispute = lifecycleService.openDispute(
                tenantId, paymentId, externalId, reasonCode, reasonDesc,
                amount, currency, network, dueDate);

        if (preExisting) {
            // Replay / redelivery — no second reserve was posted. Return a
            // non-"created" status so the replay contract holds.
            return ResponseEntity.ok(Map.of("status", "duplicate", "dispute_id", dispute.getId()));
        }

        // First delivery — trigger auto-representment evaluation.
        autoRepresentmentService.evaluate(dispute.getId());

        return ResponseEntity.ok(Map.of("status", "created", "dispute_id", dispute.getId()));
    }

    private ResponseEntity<Map<String, String>> handleEvidenceNeeded(JsonNode payload) {
        String disputeId = resolveDisputeId(payload);
        lifecycleService.requestEvidence(disputeId, "webhook");
        return ResponseEntity.ok(Map.of("status", "updated", "dispute_id", disputeId));
    }

    private ResponseEntity<Map<String, String>> handleWon(JsonNode payload) {
        String disputeId = resolveDisputeId(payload);
        lifecycleService.win(disputeId, "webhook");
        return ResponseEntity.ok(Map.of("status", "resolved", "dispute_id", disputeId));
    }

    private ResponseEntity<Map<String, String>> handleLost(JsonNode payload) {
        String disputeId = resolveDisputeId(payload);
        lifecycleService.lose(disputeId, "webhook");
        return ResponseEntity.ok(Map.of("status", "resolved", "dispute_id", disputeId));
    }

    private ResponseEntity<Map<String, String>> handleExpired(JsonNode payload) {
        String disputeId = resolveDisputeId(payload);
        lifecycleService.expire(disputeId);
        return ResponseEntity.ok(Map.of("status", "expired", "dispute_id", disputeId));
    }

    /**
     * Resolves the NexusPay dispute ID from the webhook payload.
     * Prefers {@code dispute_id} (NexusPay ID), falls back to lookup by
     * {@code external_dispute_id}.
     */
    private String resolveDisputeId(JsonNode payload) {
        String disputeId = text(payload, "dispute_id");
        if (disputeId != null) return disputeId;

        // Fallback: external ID → lookup
        String externalId = text(payload, "external_dispute_id");
        if (externalId != null) {
            log.warn("Dispute ID not in payload — lookup by external_id not yet implemented. " +
                    "Using external ID as fallback: {}", externalId);
        }
        throw new IllegalArgumentException("Cannot resolve dispute ID from webhook payload");
    }

    /**
     * Verifies the HMAC-SHA512 signature of the RAW webhook body.
     *
     * <p>Verbatim copy of {@code HyperSwitchWebhookController.verifySignature}:
     * hex-encoded HMAC-SHA512 over the UTF-8 body bytes, compared in constant
     * time via {@link MessageDigest#isEqual} (never {@code String.equals} /
     * {@code equalsIgnoreCase}, which short-circuits and leaks a byte-by-byte
     * timing side-channel, L-007). Returns false on a null/blank signature and
     * on any HMAC error.</p>
     */
    private boolean verifySignature(String payload, String signature) {
        if (signature == null || signature.isBlank()) return false;
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            String computed = HexFormat.of().formatHex(hash);
            return MessageDigest.isEqual(
                    computed.getBytes(StandardCharsets.UTF_8),
                    signature.toLowerCase().getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            log.error("Dispute HMAC verification error", e);
            return false;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private static String textOrDefault(JsonNode node, String field, String defaultValue) {
        String v = text(node, field);
        return v != null ? v : defaultValue;
    }
}
