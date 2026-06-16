package io.nexuspay.gateway.adapter.out.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.nexuspay.common.event.Topics;
import io.nexuspay.common.event.WebhookEventTaxonomy;
import io.nexuspay.common.net.WebhookUrlValidator;
import io.nexuspay.common.rls.TenantWorkRunner;
import io.nexuspay.gateway.adapter.out.persistence.JpaWebhookEndpointRepository;
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
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Delivers domain events to registered merchant webhook endpoints (GAP-030).
 *
 * Consumes from the nexuspay.payments Kafka topic and forwards events
 * to all enabled webhook endpoints that subscribe to the event type.
 * Each delivery is HMAC-SHA256 signed using the endpoint's secret.
 *
 * Retry/backoff for failed deliveries is deferred to Phase 2.
 * Currently logs failures without retry.
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
    private static final String HMAC_ALGORITHM = "HmacSHA256";

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
     * <p>{@code deliverToEndpoint} runs synchronously on the Kafka consumer thread and the POST
     * completes before it returns, so a {@link ThreadLocal} safely scopes the pin to a single
     * delivery. The custom {@link PinnedDnsResolver} reads this map at connect time and returns the
     * EXACT addresses the validator approved for the host — never performing its own DNS lookup. The
     * entry is set immediately before the POST and removed in a finally block.</p>
     */
    private static final ThreadLocal<Map<String, InetAddress[]>> PINNED_ADDRESSES = new ThreadLocal<>();

    private final JpaWebhookEndpointRepository endpointRepository;
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
                                   ObjectMapper objectMapper,
                                   TenantWorkRunner tenantWork,
                                   WebhookMetadataPort webhookMetadata,
                                   @Value("${nexuspay.multi-tenancy.rls.enforce:false}") boolean rlsEnforced) {
        this(endpointRepository, objectMapper, tenantWork, webhookMetadata, rlsEnforced,
                WebhookUrlValidator::validateAndResolve);
    }

    /**
     * Test/seam constructor (package-private): lets a test inject a relaxed-but-still-resolving guard
     * for the loopback receiver WITHOUT weakening the production validator, plus a stub
     * {@link WebhookMetadataPort}. The IP pin and redirect-disable are identical on both paths.
     */
    WebhookDeliveryService(JpaWebhookEndpointRepository endpointRepository,
                           ObjectMapper objectMapper,
                           TenantWorkRunner tenantWork,
                           WebhookMetadataPort webhookMetadata,
                           boolean rlsEnforced,
                           Function<String, List<InetAddress>> urlGuard) {
        this.endpointRepository = endpointRepository;
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
            outbox = objectMapper.readTree(record.value());
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
        // over EXACTLY these transformed bytes (see deliverToEndpoint), preserving SEC's signature
        // guarantee on the bytes the merchant actually receives.
        String transformedBody;
        try {
            transformedBody = envelopeSerializer.serialize(outbox, dottedType, metadata);
        } catch (Exception e) {
            log.warn("Failed to build canonical webhook envelope for event={} aggregate={}: {}",
                    dottedType, aggregateId, e.getMessage());
            return;
        }

        for (WebhookEndpointEntity endpoint : endpoints) {
            // INT-1: subscriptions are stored as the merchant-registered DOTTED name (the validator
            // enforces canonical names), so match on the dotted type; "*"/empty still = all.
            if (!subscribesToEvent(endpoint, dottedType)) continue;

            try {
                deliverToEndpoint(endpoint, transformedBody, dottedType);
            } catch (Exception e) {
                log.warn("Webhook delivery failed: endpoint={} url={} event={}: {}",
                        endpoint.getId(), endpoint.getUrl(), dottedType, e.getMessage());
            }
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

    private void deliverToEndpoint(WebhookEndpointEntity endpoint, String payload, String eventType) {
        // SEC-14 SECOND GATE (delivery-time, anti-DNS-rebinding): re-resolve AND re-validate the target
        // URL immediately before the POST. Registration validation alone is bypassable — a host that
        // resolved to a public IP at registration can rebind to an internal/metadata IP by delivery
        // time, so this re-check must run on every delivery. On reject the guard throws
        // WebhookUrlValidationException, which onPaymentEvent's per-endpoint try/catch logs+skips, so no
        // server-side request is ever made to the internal target.
        //
        // FIX 2 (true IP pin): validateAndResolve returns the EXACT InetAddress[] it just validated from
        // a SINGLE DNS resolution. We pin those addresses (keyed by host) into a ThreadLocal that the
        // PinnedDnsResolver consults at connect time, so the TCP socket goes to ONE OF THE VALIDATED IPs
        // — the check and the fetch share the SAME resolution; the JDK/Apache never re-resolves the host
        // independently at connect. TLS SNI and the Host header stay the hostname, so cert validation is
        // unaffected. The pin is cleared in finally so it never leaks to the next delivery.
        List<InetAddress> validated = urlGuard.apply(endpoint.getUrl());
        String host = hostOf(endpoint.getUrl());

        String signature = computeSignature(payload, endpoint.getSecret());
        String timestamp = Instant.now().toString();

        Map<String, InetAddress[]> pin = Map.of(host, validated.toArray(new InetAddress[0]));
        PINNED_ADDRESSES.set(pin);
        try {
            restClient.post()
                    .uri(endpoint.getUrl())
                    .header("X-NexusPay-Signature", signature)
                    .header("X-NexusPay-Timestamp", timestamp)
                    .header("X-NexusPay-Event", eventType)
                    .body(payload)
                    .retrieve()
                    // FIX 1: redirect-following is OFF at the client (above), so a malicious 3xx is
                    // returned, not followed. RestClient does NOT throw on 3xx by default — it would
                    // be (mis)read as a successful bodiless delivery. Treat ANY 3xx as a delivery
                    // FAILURE so it is logged + skipped (and surfaces in metrics), never silently OK.
                    .onStatus(HttpStatusCode::is3xxRedirection, (req, resp) -> {
                        throw new WebhookDeliveryException(
                                "endpoint returned a redirect (" + resp.getStatusCode()
                                        + ") — refusing to follow (SSRF guard)");
                    })
                    .toBodilessEntity();
        } finally {
            PINNED_ADDRESSES.remove();
        }

        log.debug("Webhook delivered: endpoint={} event={}", endpoint.getId(), eventType);
    }

    /** Lower-cased host of the URL, matched against the pin map key. */
    private static String hostOf(String url) {
        String host = URI.create(url).getHost();
        return host == null ? "" : host.toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Thrown when a delivery must be treated as failed for a security reason (e.g. the endpoint
     * returned a 3xx redirect, which the SSRF guard refuses to follow). Caught by the per-endpoint
     * try/catch in {@link #onPaymentEvent}, which logs and skips the endpoint.
     */
    static final class WebhookDeliveryException extends RuntimeException {
        WebhookDeliveryException(String message) {
            super(message);
        }
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
