package io.nexuspay.payment.application.webhook;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import io.nexuspay.common.metadata.MetadataSanitizer;
import io.nexuspay.payment.adapter.out.persistence.PaymentWebhookMetadataEntity;
import io.nexuspay.payment.adapter.out.persistence.PaymentWebhookMetadataRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * INT-1: records (at payment-create) and recalls (at webhook delivery) the server-owned merchant
 * correlation metadata placed in the canonical outbound webhook envelope's {@code data.metadata}.
 *
 * <p>Mirrors {@code ScreeningOriginService} (B-029): idempotent on the gateway payment id, tenant-isolated,
 * and FAIL-SAFE — a persist failure must NEVER fail an already-authorized payment (the delivery simply
 * sends {@code {}} metadata). The {@link WebhookMetadataPort} read interface is the only surface
 * gateway-api consumes.</p>
 *
 * <p>Tenant isolation on the READ is enforced at the APPLICATION layer (see {@link #find(String, String)}):
 * the resolved delivery tenant is compared against the stored {@code tenant_id}, so isolation holds even
 * when {@code rls.enforce} is dormant (its default). The V4030 RLS policy is an additional defense-in-depth
 * DB-row guard when enforcement is active, not the sole control.</p>
 *
 * <p>Guardrails (security invariants):
 * <ul>
 *   <li>PAN/card NEVER stored — {@link MetadataSanitizer#sanitize(Map)} drops any forbidden key at any depth.</li>
 *   <li>Size cap {@link #MAX_BYTES} (serialized) and key cap {@link MetadataSanitizer#MAX_KEYS};
 *       over-cap → store {@code {}}.</li>
 *   <li>The metadata contents are NEVER logged.</li>
 *   <li>The tenant stored is the server-derived trusted tenant (the {@code CallContext}), never a client echo.</li>
 * </ul></p>
 */
@Service
public class WebhookMetadataService implements WebhookMetadataPort {

    private static final Logger log = LoggerFactory.getLogger(WebhookMetadataService.class);

    /** Serialized size cap (bytes/chars) — over-cap maps are stored as {@code {}} + warn. */
    static final int MAX_BYTES = MetadataSanitizer.MAX_BYTES;

    /**
     * INT-3: reserved, SERVER-ONLY key holding the payment's key mode for the delivered webhook's
     * top-level {@code livemode}. A client can never set it: {@link MetadataSanitizer#sanitize(Map)} strips
     * ANY client-supplied {@code __}-prefixed key (the prefix is server-reserved), and {@link
     * #record(String, String, Map, boolean)} stamps the true, server-derived value back in AFTER
     * sanitize. {@code WebhookEnvelopeSerializer} lifts it to the top-level {@code livemode} and removes
     * it from the delivered {@code data.metadata}.
     */
    static final String LIVEMODE_KEY = "__livemode";

    /**
     * TEST-1: the reserved, SERVER-CONTROL key an integrator sets on a TEST-mode create to force a
     * deterministic outcome (decline/insufficient_funds/expired_card). Like {@link #LIVEMODE_KEY} it is a
     * server-reserved control key ({@code __} prefix) and must NEVER reach the delivered
     * {@code data.metadata}; {@link MetadataSanitizer#sanitize(Map)} strips it via the {@code __} prefix
     * rule. Unlike {@code __livemode} it is never re-stamped — it is purely a control input. Must match
     * {@code MockPaymentGatewayPort.TEST_OUTCOME_KEY}.
     */
    static final String TEST_OUTCOME_KEY = "__test_outcome";

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final PaymentWebhookMetadataRepository repository;
    private final ObjectMapper objectMapper;

    public WebhookMetadataService(PaymentWebhookMetadataRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /**
     * Back-compat 3-arg overload for any non-INT-3 caller: records the metadata with {@code live=true}
     * (a real/LIVE payment is the safe default for the displayed {@code livemode}; it does NOT affect
     * routing, which already happened at create).
     */
    @Transactional
    public void record(String gatewayPaymentId, String tenantId, Map<String, Object> merchantMetadata) {
        record(gatewayPaymentId, tenantId, merchantMetadata, true);
    }

    /**
     * INT-3: idempotently records the sanitized merchant correlation metadata for a gateway payment id
     * under the server-derived trusted tenant, plus a SERVER-DERIVED {@link #LIVEMODE_KEY} reserved key
     * stamped from {@code live}. A no-op when the id is blank or a row already exists (create retried
     * under idempotency). NEVER throws — a persist failure is swallowed (delivery sends {@code {}}).
     *
     * <p>The {@code __livemode} value is server-set, not client-set: {@link MetadataSanitizer#sanitize(Map)}
     * strips any client-supplied {@code __livemode} (the {@code __} prefix is server-reserved), then we
     * re-stamp the true value
     * here. It is stamped even when the correlation map was dropped to {@code {}} for being over-cap, so
     * the delivered envelope's {@code livemode} is always server-sourced.</p>
     */
    @Transactional
    public void record(String gatewayPaymentId, String tenantId, Map<String, Object> merchantMetadata,
                       boolean live) {
        if (gatewayPaymentId == null || gatewayPaymentId.isBlank()) {
            return;
        }
        try {
            if (repository.existsById(gatewayPaymentId)) {
                return; // create is retried (idempotency) — keep the original metadata
            }
            Map<String, Object> safe = MetadataSanitizer.sanitize(merchantMetadata); // any client __livemode already dropped
            if (objectMapper.writeValueAsString(safe).length() > MAX_BYTES) {
                // Over the serialized cap — drop the correlation map rather than store an oversized blob.
                // Do NOT log the contents (only the id + the fact it was over-cap).
                log.warn("Webhook metadata for {} exceeds {} bytes — storing empty metadata",
                        gatewayPaymentId, MAX_BYTES);
                safe = new LinkedHashMap<>();
            }
            // INT-3: stamp the SERVER-DERIVED livemode AFTER the cap decision so it survives an over-cap
            // {} drop — the delivered envelope's top-level livemode must always be server-sourced.
            Map<String, Object> stored = new LinkedHashMap<>(safe);
            stored.put(LIVEMODE_KEY, live);
            String json = objectMapper.writeValueAsString(stored);
            repository.save(new PaymentWebhookMetadataEntity(gatewayPaymentId, tenantId, json, Instant.now()));
        } catch (JsonProcessingException | RuntimeException e) {
            // A failure to persist must not fail an already-authorized payment; delivery sends {} metadata.
            log.warn("Failed to persist webhook metadata for {} — delivery will send empty metadata",
                    gatewayPaymentId);
        }
    }

    /**
     * Recalls the stored metadata for a gateway payment id, scoped to the resolved delivery {@code tenant}.
     * Returns an empty map when absent (never {@code null}).
     *
     * <p>SEC (INT-1): tenant isolation here is enforced at the APPLICATION layer and does NOT depend on the
     * {@code rls.enforce} flag (which is DORMANT by default). The stored row's {@code tenant_id} MUST equal
     * the caller-supplied {@code tenant}; a row owned by a different tenant, or a row whose stored tenant is
     * {@code null} (cannot prove ownership), yields {@code {}} — the safe direction. This mirrors
     * {@code ScreeningOriginService.assertOwnedBy} (B-007), except a mismatch here is non-fatal (delivery
     * simply omits the enrichment) rather than a 404, because absent/foreign metadata must never fail an
     * authorized delivery. The V4030 RLS policy remains a defense-in-depth DB-row guard when enforcement is
     * active, but is no longer the SOLE tenant control on this read.</p>
     */
    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> find(String gatewayPaymentId, String tenant) {
        if (gatewayPaymentId == null || gatewayPaymentId.isBlank()) {
            return Map.of();
        }
        return repository.findById(gatewayPaymentId)
                .filter(e -> ownedBy(e, tenant))
                .map(e -> parse(e.getMetadataJson()))
                .orElseGet(Map::of);
    }

    /**
     * App-level tenant-ownership check for the metadata read (SEC, INT-1). The stored {@code tenant_id}
     * must equal the resolved delivery {@code tenant}. A {@code null} stored tenant or a {@code null}
     * resolved tenant cannot prove ownership and is rejected (returns {@code {}} upstream). A mismatch is
     * logged at WARN with the ids ONLY (never the metadata contents) so a stray cross-tenant lookup is
     * observable without leaking correlation data.
     */
    private static boolean ownedBy(PaymentWebhookMetadataEntity entity, String tenant) {
        String stored = entity.getTenantId();
        if (stored != null && stored.equals(tenant)) {
            return true;
        }
        log.warn("Webhook metadata for {} is owned by a different tenant than the delivery tenant {} — "
                + "omitting enrichment (sending empty metadata)", entity.getGatewayPaymentId(), tenant);
        return false;
    }

    private Map<String, Object> parse(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(json, MAP_TYPE);
            return parsed != null ? parsed : Map.of();
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse stored webhook metadata — returning empty");
            return Map.of();
        }
    }
}
