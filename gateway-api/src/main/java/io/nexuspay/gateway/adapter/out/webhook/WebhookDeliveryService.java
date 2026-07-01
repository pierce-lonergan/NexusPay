package io.nexuspay.gateway.adapter.out.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.nexuspay.common.event.Topics;
import io.nexuspay.common.event.WebhookEventTaxonomy;
import io.nexuspay.common.net.WebhookUrlValidationException;
import io.nexuspay.common.net.WebhookUrlValidator;
import io.nexuspay.common.rls.TenantWorkRunner;
import io.nexuspay.common.id.PrefixedId;
import io.nexuspay.gateway.adapter.out.persistence.JpaWebhookDeliveryRepository;
import io.nexuspay.gateway.adapter.out.persistence.JpaWebhookEndpointRepository;
import io.nexuspay.gateway.adapter.out.persistence.WebhookDeliveryEntity;
import io.nexuspay.gateway.adapter.out.persistence.WebhookEndpointEntity;
import io.nexuspay.payment.application.webhook.WebhookMetadataPort;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Delivers domain events to registered merchant webhook endpoints (GAP-030).
 *
 * Consumes from the nexuspay.payments Kafka topic and forwards events
 * to all enabled webhook endpoints that subscribe to the event type.
 * Each delivery is HMAC-SHA256 signed using the endpoint's secret.
 *
 * <p><strong>INT-4 at-least-once reliability:</strong> each matching event is first RECORDED as a PENDING
 * {@code webhook_deliveries} row (one per (endpoint, stable event id), idempotent), then ATTEMPTED via the
 * shared {@link #send} path. A transient failure schedules an exponential-backoff retry; the leader-locked
 * {@code WebhookDeliveryRetrier} re-drives due rows through the SAME {@link #send}; exhausting max attempts
 * parks the row DEAD (a DLQ, never dropped). A DELIVERED row is never auto-re-sent — re-delivery is an
 * explicit admin replay only.</p>
 *
 * <p><strong>SSRF hardening (SEC-14):</strong> every delivery re-validates the target URL via
 * {@link WebhookUrlValidator#validateAndResolve(String)} (anti-DNS-rebinding second gate) AND pins the
 * actual TCP connection to one of the validator-approved IPs. The pin is implemented with an Apache
 * HttpClient5 {@link DnsResolver} that, for the host being delivered to, returns ONLY the pre-validated
 * {@link InetAddress}[] computed by that same validation call — so the check and the fetch use the SAME
 * DNS resolution and no independent re-resolution happens at connect time (closing the TOCTOU window).
 * TLS SNI and the {@code Host} header stay the hostname, so the certificate still validates against the
 * hostname rather than the IP literal. Redirect-following is disabled, so a malicious 3xx
 * {@code Location} pointing at an internal address cannot be followed.</p>
 */
@Component
public class WebhookDeliveryService {

    private static final Logger log = LoggerFactory.getLogger(WebhookDeliveryService.class);

    private static final String EVENT_TYPE_HEADER = "event_type";
    private static final String TENANT_ID_HEADER = "tenant_id";
    private static final String DEFAULT_TENANT = "default";

    // SEC-14: bound the delivery socket so a hung/slow internal target cannot tie up the consumer
    // thread (the prior shared RestClient had NO timeouts — independent hardening).
    private static final int CONNECT_TIMEOUT_MS = 3_000;
    private static final int READ_TIMEOUT_MS = 10_000;

    /**
     * SEC-14 IP PIN: per-thread host -> validator-approved IPs for the delivery currently in flight.
     *
     * <p>{@link #send} runs synchronously (on the Kafka consumer thread or the retrier thread) and the POST
     * completes before it returns, so a {@link ThreadLocal} safely scopes the pin to a single
     * delivery. The custom {@link PinnedDnsResolver} reads this map at connect time and returns the
     * EXACT addresses the validator approved for the host — never performing its own DNS lookup. The
     * entry is set immediately before the POST and removed in a finally block.</p>
     */
    private static final ThreadLocal<Map<String, InetAddress[]>> PINNED_ADDRESSES = new ThreadLocal<>();

    private final JpaWebhookEndpointRepository endpointRepository;
    private final JpaWebhookDeliveryRepository deliveryRepository;
    private final ObjectMapper objectMapper;
    private final TenantWorkRunner tenantWork;
    private final boolean rlsEnforced;
    private final RestClient restClient;

    /** INT-1: read port for server-owned merchant correlation metadata (data.metadata enrichment). */
    private final WebhookMetadataPort webhookMetadata;

    /** INT-1: builds the canonical public envelope from the internal outbox payload at send time. */
    private final WebhookEnvelopeSerializer envelopeSerializer;

    /**
     * SEC-14 test seam: the delivery-time SSRF guard. Returns the validated, pin-able address set for
     * a URL or throws to reject the delivery. Production wires {@link WebhookUrlValidator}; tests can
     * inject a guard that permits loopback (so the loopback receiver works) while STILL returning the
     * resolved addresses so the IP pin remains exercised. Never relaxed in production.
     */
    private final Function<String, List<InetAddress>> urlGuard;

    /**
     * Production constructor — full SSRF validation via {@link WebhookUrlValidator}.
     *
     * <p>{@code @Autowired} is REQUIRED: this class has a second (package-private) seam constructor
     * for tests, so Spring sees two candidate constructors and cannot pick one by default — it would
     * fall back to a non-existent no-arg constructor and fail bean instantiation
     * ({@code NoSuchMethodException}), collapsing the whole application context. Marking the intended
     * injection constructor disambiguates it. (Caught by CI integration tests, not unit tests: the
     * unit test calls the seam constructor directly, so the ambiguity only surfaces under Spring.)</p>
     */
    @Autowired
    public WebhookDeliveryService(JpaWebhookEndpointRepository endpointRepository,
                                   JpaWebhookDeliveryRepository deliveryRepository,
                                   ObjectMapper objectMapper,
                                   TenantWorkRunner tenantWork,
                                   WebhookMetadataPort webhookMetadata,
                                   @Value("${nexuspay.multi-tenancy.rls.enforce:false}") boolean rlsEnforced) {
        this(endpointRepository, deliveryRepository, objectMapper, tenantWork, webhookMetadata, rlsEnforced,
                WebhookUrlValidator::validateAndResolve);
    }

    /**
     * Test/seam constructor (package-private): lets a test inject a relaxed-but-still-resolving guard
     * for the loopback receiver WITHOUT weakening the production validator, plus a stub
     * {@link WebhookMetadataPort}. The IP pin and redirect-disable are identical on both paths.
     */
    WebhookDeliveryService(JpaWebhookEndpointRepository endpointRepository,
                           JpaWebhookDeliveryRepository deliveryRepository,
                           ObjectMapper objectMapper,
                           TenantWorkRunner tenantWork,
                           WebhookMetadataPort webhookMetadata,
                           boolean rlsEnforced,
                           Function<String, List<InetAddress>> urlGuard) {
        this.endpointRepository = endpointRepository;
        this.deliveryRepository = deliveryRepository;
        this.objectMapper = objectMapper;
        this.tenantWork = tenantWork;
        this.webhookMetadata = webhookMetadata;
        this.envelopeSerializer = new WebhookEnvelopeSerializer(objectMapper);
        this.rlsEnforced = rlsEnforced;
        this.urlGuard = urlGuard;

        // SEC-14: Apache HttpClient5 with (1) a DnsResolver that pins the connect IP to the
        // validator-approved set (anti-DNS-rebinding), (2) redirect-following DISABLED (a malicious
        // 3xx cannot bounce us to an internal address), and (3) connect/response timeouts (the prior
        // RestClient had NONE) so a hung internal target cannot tie up the consumer thread.
        // The connect timeout is a connection-establishment concern in HttpClient5 5.2, so it is set on
        // the connection manager via ConnectionConfig (RequestConfig.setConnectTimeout is deprecated);
        // the response (socket-read) timeout stays on RequestConfig.
        RequestConfig requestConfig = RequestConfig.custom()
                .setResponseTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .setRedirectsEnabled(false)   // FIX 1: do not follow 3xx -> no redirect-based SSRF
                .build();

        ConnectionConfig connectionConfig = ConnectionConfig.custom()
                .setConnectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .build();

        PoolingHttpClientConnectionManager connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(new PinnedDnsResolver())   // FIX 2: connect ONLY to pre-validated IPs
                .setDefaultConnectionConfig(connectionConfig)
                .build();

        CloseableHttpClient httpClient = HttpClientBuilder.create()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(requestConfig)
                .disableRedirectHandling()    // FIX 1 (defense in depth): never auto-follow redirects
                .disableAutomaticRetries()    // do not silently re-issue against a rebinding host
                .build();

        this.restClient = RestClient.builder()
                .requestFactory(new HttpComponentsClientHttpRequestFactory(httpClient))
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

        // INT-1: translate the INTERNAL PascalCase type to the dotted, merchant-facing canonical name
        // ONCE, right after extraction. An internal type with no canonical mapping is NOT deliverable on
        // the public contract — drop it before any endpoint lookup (the §3.3 unknown-type drop). This is
        // the ONLY place translation happens; the internal Kafka value bytes are never changed.
        String dottedType = WebhookEventTaxonomy.toDotted(eventType);
        if (dottedType == null) {
            log.debug("Skipping event with no canonical webhook mapping: {}", eventType);
            return;
        }

        // SEC-09 (B-009): the tenant filter is now UNCONDITIONAL — application-level authorization that does
        // NOT depend on rls.enforce. The consumer ALWAYS resolves the event's tenant and reads ONLY that
        // tenant's enabled endpoints (findAllByTenantIdAndEnabledTrue), closing the cross-tenant fan-out
        // that existed in the default (RLS-dormant) config where findAllByEnabledTrue() delivered EVERY
        // tenant's payment event to EVERY tenant's endpoint. The RLS GUC binding (tenantWork.callInTenant)
        // is applied ONLY under rlsEnforced — it is a defense-in-depth DB-row guard, not the authz itself.
        //
        // TRUST BOUNDARY (SEC-batch-1b): the tenant is resolved ONLY from the relay-stamped, server-trusted
        // `tenant_id` Kafka header (extractTenant). The event JSON body — the LOWEST-trust input in the
        // pipeline — is NEVER consulted for the tenant: a body-derived fallback would reopen cross-tenant
        // fan-out the moment any producer (or an operator/attacker who can shape a DLT entry's body) placed
        // a top-level metadata.tenant_id. When the header is absent/blank, extractTenant returns "default";
        // a "default" tenant matches no real-tenant endpoint, so the event is simply not delivered (a
        // delivery gap, never a leak). The relay (OutboxRelay) ALWAYS stamps the header, and the DLT
        // reprocessor re-attaches it on republish (DeadLetterReprocessor.retryEntry), so a legitimately
        // produced event keeps its trusted tenant rather than degrading to "default". Any residual
        // header-less payments are covered by the V4029 origin backfill on the producer side.
        // HTTP POSTs stay OUTSIDE any tx.
        String tenant = extractTenant(record);

        // INT-1: parse the internal outbox value ONCE — used both for the metadata lookup key
        // (aggregate_id) and to build the canonical envelope. A malformed body is not deliverable.
        JsonNode outbox;
        try {
            outbox = objectMapper.readTree(valueAsJson(record));
        } catch (Exception e) {
            log.debug("Skipping event with unparseable payload: {}", e.getMessage());
            return;
        }
        String aggregateId = textOrNull(outbox.path("aggregate_id"));

        // SEC-09 / INT-1: load the owning tenant's endpoints AND look up that payment's merchant metadata
        // for the SAME resolved tenant. BOTH reads are tenant-scoped at the APPLICATION layer, independent
        // of rls.enforce (which is DORMANT by default): the endpoint read uses
        // findAllByTenantIdAndEnabledTrue(tenant), and the metadata read passes that same tenant into
        // find(aggregateId, tenant), which returns {} unless the stored row's tenant_id matches (see
        // WebhookMetadataService.find / ownedBy — mirrors ScreeningOriginService.assertOwnedBy). Under
        // rlsEnforced both ALSO run inside callInTenant so the V4030 RLS policy binds the rows as
        // defense-in-depth — but the cross-tenant guarantee no longer DEPENDS on that dormant flag.
        TenantScopedLoad loaded = rlsEnforced
                ? tenantWork.callInTenant(tenant, () -> loadEndpointsAndMetadata(tenant, aggregateId))
                : loadEndpointsAndMetadata(tenant, aggregateId);
        List<WebhookEndpointEntity> endpoints = loaded.endpoints();
        if (endpoints.isEmpty()) return;
        Map<String, Object> metadata = loaded.metadata();

        // INT-1: build the canonical envelope ONCE per record — it is identical for every endpoint of this
        // tenant. Only the per-secret HMAC signature and the POST are per-endpoint. The HMAC is computed
        // over EXACTLY these transformed bytes (see send), preserving SEC's signature
        // guarantee on the bytes the merchant actually receives.
        String transformedBody;
        try {
            transformedBody = envelopeSerializer.serialize(outbox, dottedType, metadata);
        } catch (Exception e) {
            log.warn("Failed to build canonical webhook envelope for event={} aggregate={}: {}",
                    dottedType, aggregateId, e.getMessage());
            return;
        }

        // INT-4: the rows' event_id is the INT-1 STABLE id (anchored on the PSP original_event_id) so a
        // Kafka redelivery / DLT replay of the SAME logical event collapses onto the SAME (endpoint,event)
        // unique key — never a second send.
        String stableEventId = WebhookEnvelopeSerializer.stableId(outbox);

        // INT-4: record-then-attempt per subscribed endpoint. Under rlsEnforced the recorder/recordOutcome
        // writes (and the load above) all run inside the SAME tenant scope so the V4031 RLS WITH CHECK binds
        // the webhook_deliveries rows to the trusted header tenant (SEC-1b: never body-derived). The HTTP
        // POSTs themselves stay OUTSIDE any tenant transaction.
        for (WebhookEndpointEntity endpoint : endpoints) {
            // INT-1: subscriptions are stored as the merchant-registered DOTTED name (the validator
            // enforces canonical names), so match on the dotted type; "*"/empty still = all.
            if (!subscribesToEvent(endpoint, dottedType)) continue;

            // 1. RECORD one PENDING row per subscribed endpoint (idempotent on (endpoint_id,event_id)).
            //    A row that already exists (redelivery / concurrent recorder / already DELIVERED) -> null,
            //    so the consumer NEVER re-sends it (invariants #1, #6); only the retrier or an explicit
            //    replay re-attempts.
            WebhookDeliveryEntity delivery = rlsEnforced
                    ? tenantWork.callInTenant(tenant,
                        () -> recordDelivery(tenant, endpoint, stableEventId, dottedType, transformedBody))
                    : recordDelivery(tenant, endpoint, stableEventId, dottedType, transformedBody);
            if (delivery == null) continue;

            // 2. ATTEMPT now via the shared SSRF-safe + canonical + signed send.
            try {
                SendOutcome outcome = send(endpoint, delivery.getCanonicalBody(), dottedType);
                recordOutcomeScoped(tenant, delivery, outcome);
            } catch (Exception e) {
                // Belt: one endpoint must never kill the consumer thread. Treat as transient so the retrier
                // re-drives it (and surfaces in metrics) rather than silently dropping the delivery.
                log.warn("Webhook delivery failed: endpoint={} url={} event={}: {}",
                        endpoint.getId(), endpoint.getUrl(), dottedType, e.getMessage());
                recordOutcomeScoped(tenant, delivery,
                        new SendOutcome.TransientFailure(null, e.getClass().getSimpleName()));
            }
        }
    }

    /** Applies an outcome inside the row's tenant scope when RLS is enforced (no-op wrapper otherwise). */
    private void recordOutcomeScoped(String tenant, WebhookDeliveryEntity delivery, SendOutcome outcome) {
        if (rlsEnforced) {
            tenantWork.runInTenant(tenant, () -> recordOutcome(delivery, outcome));
        } else {
            recordOutcome(delivery, outcome);
        }
    }

    /** Result of the tenant-scoped load: the owning tenant's endpoints + that payment's metadata. */
    private record TenantScopedLoad(List<WebhookEndpointEntity> endpoints, Map<String, Object> metadata) {
    }

    /**
     * INT-1: loads the tenant's enabled endpoints and the payment's merchant metadata in ONE tenant
     * scope (the caller wraps this in {@code callInTenant} when RLS is enforced). The metadata defaults
     * to {@code {}} when there is no aggregate id or no stored row, so delivery never fails on absence.
     */
    private TenantScopedLoad loadEndpointsAndMetadata(String tenant, String aggregateId) {
        List<WebhookEndpointEntity> endpoints = endpointRepository.findAllByTenantIdAndEnabledTrue(tenant);
        // INT-1: pass the resolved delivery tenant so the metadata read is tenant-checked at the app layer
        // (returns {} for a row owned by another tenant), independent of rls.enforce.
        Map<String, Object> metadata = aggregateId == null ? Map.of() : webhookMetadata.find(aggregateId, tenant);
        return new TenantScopedLoad(endpoints, metadata != null ? metadata : Map.of());
    }

    private static String textOrNull(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        String s = node.asText();
        return s.isEmpty() ? null : s;
    }

    // ============================ INT-4 shared send + persistence ============================

    /**
     * INT-4: outcome of ONE HTTP attempt, mapped by the caller to the persisted {@code Status}. Sealed so
     * the {@code recordOutcome} switch is exhaustive.
     */
    public sealed interface SendOutcome {
        /** 2xx — terminal success. */
        record Delivered(int statusCode) implements SendOutcome {}
        /** Retryable (5xx/408/429, network, refused 3xx) — schedule a backoff retry or DEAD when exhausted. */
        record TransientFailure(Integer statusCode, String error) implements SendOutcome {}
        /** Non-retryable (4xx≠408/429, SSRF-now-private) — go DEAD immediately; recover via replay. */
        record PermanentFailure(Integer statusCode, String error) implements SendOutcome {}
    }

    /**
     * INT-4 shared send unit — used by BOTH the initial consumer path ({@link #onPaymentEvent}) and the
     * {@code WebhookDeliveryRetrier}, so a retry is EXACTLY as SSRF-safe and canonical as a first attempt
     * (it IS this same method). Re-validates + IP-pins the URL (SEC-4b), POSTs the EXACT {@code canonicalBody}
     * with the HMAC computed over those bytes using the endpoint's CURRENT secret (rotation takes effect next
     * attempt), redirects disabled, any 3xx is a failure (refused, not followed).
     *
     * @param endpoint      the CURRENT endpoint row (current URL + current secret)
     * @param canonicalBody the exact INT-1 envelope bytes to sign + POST
     * @param eventType     dotted canonical type (X-NexusPay-Event header)
     */
    public SendOutcome send(WebhookEndpointEntity endpoint, String canonicalBody, String eventType) {
        // SEC-4b second gate / anti-DNS-rebinding: re-resolve AND re-validate immediately before the POST.
        final List<InetAddress> validated;
        try {
            validated = urlGuard.apply(endpoint.getUrl());
        } catch (WebhookUrlValidationException e) {
            // The URL now resolves to a non-public/unresolvable address. NOT transient — do not bang an
            // SSRF target on a 30s timer. PermanentFailure -> DEAD. A merchant who fixes DNS uses replay.
            return new SendOutcome.PermanentFailure(null, "ssrf-guard: " + e.getMessage());
        }
        String host = hostOf(endpoint.getUrl());
        String signature = computeSignature(canonicalBody, endpoint.getSecret());  // CURRENT secret, per attempt
        String timestamp = Instant.now().toString();

        // FIX 2 (true IP pin): pin to the EXACT InetAddress[] the validator just resolved+approved (keyed by
        // host), consulted by PinnedDnsResolver at connect time so the socket goes to a validated IP and the
        // host is never independently re-resolved. TLS SNI/Host stay the hostname so the cert still validates.
        Map<String, InetAddress[]> pin = Map.of(host, validated.toArray(new InetAddress[0]));
        PINNED_ADDRESSES.set(pin);
        try {
            return restClient.post()
                    .uri(endpoint.getUrl())
                    .header("X-NexusPay-Signature", signature)
                    .header("X-NexusPay-Timestamp", timestamp)
                    .header("X-NexusPay-Event", eventType)
                    .body(canonicalBody)
                    // exchange() lets us inspect the status WITHOUT onStatus throwing, so we can map every
                    // status to an outcome. Redirect-following is OFF at the client (disableRedirectHandling
                    // + setRedirectsEnabled(false)), so a malicious 3xx is RETURNED, not followed — we map it
                    // to a TransientFailure (refused), preserving the SSRF guard while letting a transient
                    // 3xx-misconfig self-heal on retry.
                    .exchange((req, resp) -> {
                        int code = resp.getStatusCode().value();
                        if (resp.getStatusCode().is2xxSuccessful()) {
                            return new SendOutcome.Delivered(code);
                        }
                        if (resp.getStatusCode().is3xxRedirection()) {
                            return new SendOutcome.TransientFailure(code,
                                    "endpoint returned redirect " + code + " — refusing to follow (SSRF guard)");
                        }
                        if (resp.getStatusCode().is4xxClientError() && code != 408 && code != 429) {
                            // 4xx (except 408/429) won't fix on retry -> DEAD now; recover via replay.
                            return new SendOutcome.PermanentFailure(code, "client error " + code);
                        }
                        // 5xx, 408, 429, or anything else server-side -> retryable.
                        return new SendOutcome.TransientFailure(code, "server/other status " + code);
                    });
        } catch (Exception e) {
            // connect timeout, read timeout, connection refused, DNS pin miss, etc. -> retryable.
            return new SendOutcome.TransientFailure(null, classify(e));
        } finally {
            PINNED_ADDRESSES.remove();
        }
    }

    /** Compact, bounded classification of a thrown transport exception (never the body/secret). */
    private static String classify(Exception e) {
        String msg = e.getMessage();
        String summary = e.getClass().getSimpleName() + (msg != null ? ": " + msg : "");
        return summary.length() <= 256 ? summary : summary.substring(0, 256);
    }

    /**
     * INT-4: records ONE PENDING delivery row, idempotent on {@code (endpoint_id, event_id)} (L-041).
     * Returns {@code null} when a row already exists (redelivery / concurrent recorder / already DELIVERED) so
     * the consumer never re-attempts it — only the retrier (FAILED+due) or an explicit replay re-sends.
     */
    @Transactional
    WebhookDeliveryEntity recordDelivery(String tenant, WebhookEndpointEntity ep, String eventId,
                                         String dottedType, String canonicalBody) {
        // Fast path: a prior recording exists -> the consumer does NOT re-attempt (the retrier/idempotency
        // owns it; a DELIVERED row is never re-sent here).
        if (deliveryRepository.findByEndpointIdAndEventId(ep.getId(), eventId).isPresent()) {
            return null;
        }
        WebhookDeliveryEntity row = WebhookDeliveryEntity.pending(PrefixedId.webhookDelivery(), tenant,
                ep.getId(), eventId, dottedType, canonicalBody);
        try {
            // saveAndFlush (NOT save): the pre-assigned @Id makes save() do merge() and DEFER the INSERT past
            // this try/catch (L-041), so a concurrent recorder's unique-violation would escape. Flushing forces
            // the violation HERE.
            return deliveryRepository.saveAndFlush(row);
        } catch (DataIntegrityViolationException dup) {
            if (!isDeliveryIdemViolation(dup)) {
                throw dup;   // a genuine/unrelated integrity error (FK to a missing endpoint, etc.) must propagate
            }
            // A concurrent recorder won the (endpoint_id,event_id) race; do not double-attempt.
            return null;
        }
    }

    /** Name of the unique index enforcing (endpoint_id, event_id) idempotency (V4031). */
    private static final String DELIVERY_IDEM_CONSTRAINT = "uq_webhook_deliveries_endpoint_event";

    /**
     * Narrows the dup-key no-op to OUR idempotency constraint only (mirrors L-041 / CreateJournalEntryUseCase):
     * Spring's {@link DuplicateKeyException} (SQLSTATE 23505) OR the constraint name anywhere in the chain. Any
     * other {@link DataIntegrityViolationException} returns {@code false} and is re-thrown.
     */
    private static boolean isDeliveryIdemViolation(DataIntegrityViolationException ex) {
        if (ex instanceof DuplicateKeyException) {
            return true;
        }
        for (Throwable t = ex; t != null; t = t.getCause()) {
            String msg = t.getMessage();
            if (msg != null && msg.contains(DELIVERY_IDEM_CONSTRAINT)) {
                return true;
            }
        }
        return false;
    }

    /**
     * INT-4: the ONE shared state transition for an attempt outcome, used by both the consumer and the
     * retrier. Delivered -> DELIVERED; PermanentFailure -> DEAD now; TransientFailure -> increment the attempt
     * then DEAD if exhausted (DLQ — never dropped) else FAILED + exponential backoff.
     */
    @Transactional
    public void recordOutcome(WebhookDeliveryEntity d, SendOutcome outcome) {
        switch (outcome) {
            case SendOutcome.Delivered del -> d.markDelivered(del.statusCode());
            case SendOutcome.PermanentFailure pf -> d.markDead(pf.statusCode(), pf.error());
            case SendOutcome.TransientFailure tf -> {
                d.incrementAttempt();
                if (d.getAttemptCount() >= d.getMaxAttempts()) {
                    d.markDead(tf.statusCode(), tf.error());
                } else {
                    d.markTransientFailure(tf.statusCode(), tf.error(), nextAttemptAt(d.getAttemptCount()));
                }
            }
        }
        deliveryRepository.save(d);
    }

    /**
     * INT-4 backoff: base 30s * 2^(attempt-1), capped at 1h, with half-to-full jitter. {@code attempt} is
     * 1-based (the post-increment {@code attempt_count}). With max_attempts=8 the cumulative window is
     * ~30s,1m,2m,4m,8m,16m,32m ≈ 1h05m before DEAD.
     */
    static Instant nextAttemptAt(int attempt) {
        long base = 30L;                                  // seconds
        long exp = Math.min(attempt - 1, 12);             // cap the shift so it never overflows
        long delay = Math.min(base << exp, 3600L);        // cap at 1h
        long jittered = ThreadLocalRandom.current().nextLong(delay / 2, delay + 1); // half-to-full jitter
        return Instant.now().plusSeconds(jittered);
    }

    /** Lower-cased host of the URL, matched against the pin map key. */
    private static String hostOf(String url) {
        String host = URI.create(url).getHost();
        return host == null ? "" : host.toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Apache HttpClient5 resolver that returns ONLY the validator-approved IPs for the in-flight
     * delivery's host. It never performs its own DNS lookup, so the connection is pinned to exactly the
     * resolution the validator inspected. A host that is not pinned (or an empty pin) is treated as
     * unresolvable and rejected fail-closed — no delivery proceeds unpinned.
     */
    private static final class PinnedDnsResolver implements DnsResolver {
        @Override
        public InetAddress[] resolve(String host) throws UnknownHostException {
            Map<String, InetAddress[]> pin = PINNED_ADDRESSES.get();
            InetAddress[] addresses = pin == null ? null
                    : pin.get(host == null ? "" : host.toLowerCase(java.util.Locale.ROOT));
            if (addresses == null || addresses.length == 0) {
                // Fail closed: never let HttpClient fall back to its own resolution for an unpinned host.
                throw new UnknownHostException("No validator-pinned address for host: " + host);
            }
            return addresses;
        }

        @Override
        public String resolveCanonicalHostname(String host) {
            // Returning the host unchanged avoids a reverse lookup; canonicalization is not needed for
            // pinning and a reverse lookup would re-introduce an independent DNS query.
            return host;
        }
    }

    private boolean subscribesToEvent(WebhookEndpointEntity endpoint, String eventType) {
        List<String> events = endpoint.getEvents();
        if (events == null || events.isEmpty()) return true; // empty = all events
        return events.contains("*") || events.contains(eventType);
    }

    /**
     * Normalize the Kafka record value to a JSON String, tolerating BOTH a raw JSON String
     * (StringDeserializer) AND an already-deserialized Map/Object (a JSON-typed deserializer). The listener
     * is declared {@code <String,String>}, but the configured value deserializer hands back a LinkedHashMap;
     * reading {@code record.value()} directly then throws a ClassCastException (the synthetic String cast),
     * which the callers caught and skipped — so EVERY event was dropped and NO webhook was ever delivered.
     * Re-serializing the Map here is safe: the signed/delivered canonical_body is the REBUILT envelope (from
     * this node + metadata), never the raw Kafka bytes, so key-order/whitespace of the re-serialization
     * cannot change what is signed.
     */
    private String valueAsJson(ConsumerRecord<?, ?> record) {
        Object value = record.value();
        if (value == null) return "null";
        if (value instanceof String s) return s;
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return null; // unparseable → caller's readTree throws → skipped (unchanged behavior)
        }
    }

    private String extractEventType(ConsumerRecord<String, String> record) {
        var header = record.headers().lastHeader(EVENT_TYPE_HEADER);
        if (header == null) {
            // Try parsing from payload
            try {
                JsonNode node = objectMapper.readTree(valueAsJson(record));
                return node.path("event_type").asText(null);
            } catch (Exception e) {
                return null;
            }
        }
        return new String(header.value(), StandardCharsets.UTF_8);
    }

    /**
     * Resolves the tenant that owns this event from the relay-stamped, server-trusted {@code tenant_id}
     * Kafka header — and ONLY that header. The event JSON body is deliberately NOT consulted: it is the
     * lowest-trust input in the pipeline, and a body-derived {@code metadata.tenant_id} fallback would be
     * a cross-tenant fan-out vector (a producer, or an operator/attacker who can shape a redelivered DLT
     * entry's body, could route an event to an arbitrary tenant's endpoints). When the header is
     * absent/blank we fail SAFE to {@code "default"}, which matches no real-tenant endpoint, so the event
     * is not delivered (a delivery gap, never a cross-tenant leak). The relay always stamps this header
     * and the DLT reprocessor re-attaches it on republish, so legitimate events keep their trusted tenant.
     */
    private String extractTenant(ConsumerRecord<String, String> record) {
        var header = record.headers().lastHeader(TENANT_ID_HEADER);
        if (header != null) {
            String value = new String(header.value(), StandardCharsets.UTF_8);
            if (!value.isBlank()) return value;
        }
        return DEFAULT_TENANT;
    }

    /**
     * TEST-4a: delegates to the single-sourced {@link WebhookSignature#sign(String, String)} so the
     * sender and the F2 signature-inspection endpoint can never fork the algorithm. Byte-identical to the
     * prior inline {@code HmacSHA256}-over-UTF-8-bytes, hex routine.
     */
    private String computeSignature(String payload, String secret) {
        return WebhookSignature.sign(payload, secret);
    }
}
