package io.nexuspay.payment.application.webhook;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import io.nexuspay.payment.adapter.out.persistence.PaymentWebhookMetadataEntity;
import io.nexuspay.payment.adapter.out.persistence.PaymentWebhookMetadataRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
 *   <li>PAN/card NEVER stored — {@link #sanitize(Map)} drops any {@link #FORBIDDEN} key at any depth.</li>
 *   <li>Size cap {@link #MAX_BYTES} (serialized) and key cap {@link #MAX_KEYS}; over-cap → store {@code {}}.</li>
 *   <li>The metadata contents are NEVER logged.</li>
 *   <li>The tenant stored is the server-derived trusted tenant (the {@code CallContext}), never a client echo.</li>
 * </ul></p>
 */
@Service
public class WebhookMetadataService implements WebhookMetadataPort {

    private static final Logger log = LoggerFactory.getLogger(WebhookMetadataService.class);

    /** Serialized size cap (bytes/chars) — over-cap maps are stored as {@code {}} + warn. */
    static final int MAX_BYTES = 4096;
    /** Top-level key cap — over-cap maps are stored as {@code {}} + warn. */
    static final int MAX_KEYS = 50;

    /**
     * INT-3: reserved, SERVER-ONLY key holding the payment's key mode for the delivered webhook's
     * top-level {@code livemode}. A client can never set it: {@link #sanitize(Map)} strips ANY
     * client-supplied {@code __livemode} (the {@code __} prefix is server-reserved), and {@link
     * #record(String, String, Map, boolean)} stamps the true, server-derived value back in AFTER
     * sanitize. {@code WebhookEnvelopeSerializer} lifts it to the top-level {@code livemode} and removes
     * it from the delivered {@code data.metadata}.
     */
    static final String LIVEMODE_KEY = "__livemode";

    /**
     * Keys that must NEVER be persisted (PAN/card material) plus the authority markers the gate owns
     * (so a client-echoed {@code source}/{@code workflow}/{@code tenant_id} is not stored as a
     * correlation key). Matched case-insensitively at any nesting depth.
     */
    private static final Set<String> FORBIDDEN = Set.of(
            "payment_method_data", "card", "number", "cvc", "cvv", "pan", "payment_method",
            "source", "workflow", "tenant_id",
            // INT-3: the reserved server-only mode key — a client-echoed __livemode must NEVER survive to
            // the stored map; record(...) re-stamps the true server-derived value after sanitize().
            LIVEMODE_KEY);

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
     * <p>The {@code __livemode} value is server-set, not client-set: {@link #sanitize(Map)} strips any
     * client-supplied {@code __livemode} (it is in {@link #FORBIDDEN}), then we re-stamp the true value
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
            Map<String, Object> safe = sanitize(merchantMetadata); // any client __livemode already dropped
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

    /**
     * Returns a copy of {@code metadata} with every {@link #FORBIDDEN} key removed (case-insensitive) at
     * ANY nesting depth, and capped at {@link #MAX_KEYS} top-level keys. {@code null} → empty map. The
     * recursion strips forbidden subtrees inside nested maps/lists too (belt-and-suspenders PAN guard).
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> sanitize(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return Map.of();
        }
        if (metadata.size() > MAX_KEYS) {
            // Too many keys — store nothing rather than a partial/truncated map.
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : metadata.entrySet()) {
            String key = e.getKey();
            if (key == null || FORBIDDEN.contains(key.toLowerCase(Locale.ROOT))) {
                continue;
            }
            out.put(key, scrubValue(e.getValue()));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Object scrubValue(Object value) {
        if (value instanceof Map<?, ?> nested) {
            Map<String, Object> cleaned = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : nested.entrySet()) {
                Object k = e.getKey();
                if (k != null && FORBIDDEN.contains(k.toString().toLowerCase(Locale.ROOT))) {
                    continue;
                }
                cleaned.put(String.valueOf(k), scrubValue(e.getValue()));
            }
            return cleaned;
        }
        if (value instanceof Iterable<?> list) {
            java.util.List<Object> cleaned = new java.util.ArrayList<>();
            for (Object item : list) {
                cleaned.add(scrubValue(item));
            }
            return cleaned;
        }
        return value;
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
