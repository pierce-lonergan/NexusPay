package io.nexuspay.gateway.adapter.out.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.nexuspay.common.event.Topics;
import io.nexuspay.common.net.WebhookUrlValidator;
import io.nexuspay.common.rls.TenantWorkRunner;
import io.nexuspay.gateway.adapter.out.persistence.JpaWebhookEndpointRepository;
import io.nexuspay.gateway.adapter.out.persistence.WebhookEndpointEntity;
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

    /**
     * SEC-14 test seam: the delivery-time SSRF guard. Returns the validated, pin-able address set for
     * a URL or throws to reject the delivery. Production wires {@link WebhookUrlValidator}; tests can
     * inject a guard that permits loopback (so the loopback receiver works) while STILL returning the
     * resolved addresses so the IP pin remains exercised. Never relaxed in production.
     */
    private final Function<String, List<InetAddress>> urlGuard;

    /** Production constructor — full SSRF validation via {@link WebhookUrlValidator}. */
    public WebhookDeliveryService(JpaWebhookEndpointRepository endpointRepository,
                                   ObjectMapper objectMapper,
                                   TenantWorkRunner tenantWork,
                                   @Value("${nexuspay.multi-tenancy.rls.enforce:false}") boolean rlsEnforced) {
        this(endpointRepository, objectMapper, tenantWork, rlsEnforced,
                WebhookUrlValidator::validateAndResolve);
    }

    /**
     * Test/seam constructor (package-private): lets a test inject a relaxed-but-still-resolving guard
     * for the loopback receiver WITHOUT weakening the production validator. The IP pin and
     * redirect-disable are identical on both paths.
     */
    WebhookDeliveryService(JpaWebhookEndpointRepository endpointRepository,
                           ObjectMapper objectMapper,
                           TenantWorkRunner tenantWork,
                           boolean rlsEnforced,
                           Function<String, List<InetAddress>> urlGuard) {
        this.endpointRepository = endpointRepository;
        this.objectMapper = objectMapper;
        this.tenantWork = tenantWork;
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

        // DORMANCY (B-002): the tenant-scoped finder adds an UNCONDITIONAL SQL `WHERE tenant_id = ?`,
        // which is NOT gated by the RLS GUC — so it would change behavior even at enforce=false (events
        // resolve to "default" until cutover Step 0 stamps the real tenant, and real endpoints are stored
        // under real tenants → zero matches → deliveries silently stop). Gate it on the enforce flag:
        // keep the pre-existing all-enabled read while dormant; switch to the tenant-scoped, RLS-bound
        // read only under enforcement (which also closes the pre-existing cross-tenant fan-out, paired
        // with Step 0 supplying the real per-event tenant). HTTP POSTs stay OUTSIDE any tx.
        List<WebhookEndpointEntity> endpoints;
        if (rlsEnforced) {
            String tenant = extractTenant(record);
            endpoints = tenantWork.callInTenant(tenant,
                    () -> endpointRepository.findAllByTenantIdAndEnabledTrue(tenant));
        } else {
            endpoints = endpointRepository.findAllByEnabledTrue();
        }
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
