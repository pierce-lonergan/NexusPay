package io.nexuspay.dispute.adapter.in.webhook;

import io.nexuspay.dispute.application.service.AutoRepresentmentService;
import io.nexuspay.dispute.application.service.DisputeLifecycleService;
import io.nexuspay.dispute.domain.Dispute;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;

/**
 * Receives dispute notifications from PSPs (HyperSwitch webhook relay).
 *
 * <p>When HyperSwitch forwards a dispute/chargeback event from the payment
 * processor, this handler creates or updates the dispute in NexusPay and
 * triggers the auto-representment evaluation pipeline.</p>
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

    private final DisputeLifecycleService lifecycleService;
    private final AutoRepresentmentService autoRepresentmentService;

    public DisputeWebhookHandler(DisputeLifecycleService lifecycleService,
                                  AutoRepresentmentService autoRepresentmentService) {
        this.lifecycleService = lifecycleService;
        this.autoRepresentmentService = autoRepresentmentService;
    }

    /**
     * Processes incoming dispute webhook events from HyperSwitch.
     */
    @PostMapping
    public ResponseEntity<Map<String, String>> handleDisputeWebhook(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @RequestBody Map<String, Object> payload) {

        String eventType = (String) payload.get("event_type");
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

    private ResponseEntity<Map<String, String>> handleOpened(String tenantId, Map<String, Object> payload) {
        String paymentId = (String) payload.get("payment_id");
        String externalId = (String) payload.get("external_dispute_id");
        String reasonCode = (String) payload.getOrDefault("reason_code", "OTHER");
        String reasonDesc = (String) payload.get("reason_description");
        long amount = ((Number) payload.getOrDefault("amount", 0)).longValue();
        String currency = (String) payload.getOrDefault("currency", "USD");
        String network = (String) payload.get("network");
        String dueDateStr = (String) payload.get("evidence_due_date");
        Instant dueDate = dueDateStr != null ? Instant.parse(dueDateStr) : null;

        Dispute dispute = lifecycleService.openDispute(
                tenantId, paymentId, externalId, reasonCode, reasonDesc,
                amount, currency, network, dueDate);

        // Trigger auto-representment evaluation
        autoRepresentmentService.evaluate(dispute.getId());

        return ResponseEntity.ok(Map.of("status", "created", "dispute_id", dispute.getId()));
    }

    private ResponseEntity<Map<String, String>> handleEvidenceNeeded(Map<String, Object> payload) {
        String disputeId = resolveDisputeId(payload);
        lifecycleService.requestEvidence(disputeId, "webhook");
        return ResponseEntity.ok(Map.of("status", "updated", "dispute_id", disputeId));
    }

    private ResponseEntity<Map<String, String>> handleWon(Map<String, Object> payload) {
        String disputeId = resolveDisputeId(payload);
        lifecycleService.win(disputeId, "webhook");
        return ResponseEntity.ok(Map.of("status", "resolved", "dispute_id", disputeId));
    }

    private ResponseEntity<Map<String, String>> handleLost(Map<String, Object> payload) {
        String disputeId = resolveDisputeId(payload);
        lifecycleService.lose(disputeId, "webhook");
        return ResponseEntity.ok(Map.of("status", "resolved", "dispute_id", disputeId));
    }

    private ResponseEntity<Map<String, String>> handleExpired(Map<String, Object> payload) {
        String disputeId = resolveDisputeId(payload);
        lifecycleService.expire(disputeId);
        return ResponseEntity.ok(Map.of("status", "expired", "dispute_id", disputeId));
    }

    /**
     * Resolves the NexusPay dispute ID from the webhook payload.
     * Prefers {@code dispute_id} (NexusPay ID), falls back to lookup by
     * {@code external_dispute_id}.
     */
    private String resolveDisputeId(Map<String, Object> payload) {
        String disputeId = (String) payload.get("dispute_id");
        if (disputeId != null) return disputeId;

        // Fallback: external ID → lookup
        String externalId = (String) payload.get("external_dispute_id");
        if (externalId != null) {
            log.warn("Dispute ID not in payload — lookup by external_id not yet implemented. " +
                    "Using external ID as fallback: {}", externalId);
        }
        throw new IllegalArgumentException("Cannot resolve dispute ID from webhook payload");
    }
}
