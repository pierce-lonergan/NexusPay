package io.nexuspay.gateway.adapter.out.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.nexuspay.common.event.Topics;
import io.nexuspay.common.rls.TenantWorkRunner;
import io.nexuspay.gateway.adapter.out.persistence.JpaWebhookDeliveryRepository;
import io.nexuspay.gateway.adapter.out.persistence.JpaWebhookEndpointRepository;
import io.nexuspay.gateway.adapter.out.persistence.WebhookDeliveryEntity;
import io.nexuspay.gateway.adapter.out.persistence.WebhookEndpointEntity;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B-014: {@link WebhookDeliveryService} signs every delivery with HMAC-SHA256 over the payload using
 * the endpoint secret (merchants verify X-NexusPay-Signature to trust the event). It also filters by
 * event subscription and extracts the event type from header-or-payload. A bug means forged-looking
 * or mis-routed payment events.
 *
 * <p>The service builds its own {@link org.springframework.web.client.RestClient} internally, so the
 * delivery path is exercised end-to-end against a real loopback HTTP server that captures the request
 * (proving algorithm/key/encoding) rather than asserting on a mock chain. Delivery is synchronous
 * ({@code toBodilessEntity()} blocks), so captures are populated by the time onPaymentEvent returns.</p>
 *
 * <p><strong>SEC-14 test seam:</strong> the delivery-time SSRF gate
 * ({@code WebhookUrlValidator.validateAndResolve}) rejects the loopback {@code http://127.0.0.1} target
 * this test uses (non-https + loopback). To keep this untagged test green in the default gate WITHOUT
 * weakening production validation, the service is built through its package-private seam constructor
 * with a {@link #loopbackPermittingGuard() loopback-permitting guard}. The guard still RESOLVES the
 * host and returns its real {@link InetAddress}[], so the production IP-pin path (the custom
 * {@code DnsResolver} connecting to exactly the validator-approved addresses) is still exercised
 * end-to-end — only the public/https policy checks are relaxed for the in-process receiver.</p>
 */
class WebhookDeliveryServiceTest {

    private JpaWebhookEndpointRepository repository;
    private JpaWebhookDeliveryRepository deliveryRepository;
    private TenantWorkRunner tenantWork;
    private ObjectMapper objectMapper;
    private WebhookDeliveryService service;

    private HttpServer server;
    private final CopyOnWriteArrayList<Capture> captures = new CopyOnWriteArrayList<>();

    /** Captured request details from the loopback receiver. */
    private record Capture(String path, Map<String, String> headers, String body) {
    }

    @BeforeEach
    void setUp() throws IOException {
        repository = mock(JpaWebhookEndpointRepository.class);
        // INT-4: the consumer now RECORDS a PENDING delivery before attempting. A mocked delivery repo
        // whose saveAndFlush echoes the row (default findByEndpointIdAndEventId -> Optional.empty()) lets
        // recordDelivery return a non-null PENDING row so the existing routing/HMAC assertions still drive a
        // real POST; recordOutcome's save() is a no-op against the mock.
        deliveryRepository = recordingDeliveryRepo();
        tenantWork = mock(TenantWorkRunner.class);
        objectMapper = new ObjectMapper();
        // rlsEnforced=false. SEC-09 (B-009): even with RLS dormant the consumer now filters endpoints by
        // the event's tenant (findAllByTenantIdAndEnabledTrue) — the cross-tenant fan-out is closed in the
        // default config too. The seam constructor injects a guard that PERMITS the loopback receiver but
        // still resolves+returns its addresses, so the IP-pin (DnsResolver) path is still exercised.
        // Production validation is untouched.
        // INT-1: seam ctor now takes a WebhookMetadataPort. These existing tests assert routing/HMAC, not
        // enrichment, so stub it to return empty metadata ({} -> data.metadata == {}). The port now takes
        // (gatewayPaymentId, tenant) — the tenant is the app-layer ownership check (see WebhookMetadataPort).
        service = new WebhookDeliveryService(repository, deliveryRepository, objectMapper, tenantWork,
                (gatewayPaymentId, tenant) -> java.util.Map.of(), false,
                loopbackPermittingGuard());

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] in = exchange.getRequestBody().readAllBytes();
            Map<String, String> headers = new ConcurrentHashMap<>();
            exchange.getRequestHeaders().forEach((k, v) -> {
                if (!v.isEmpty()) headers.put(k, v.get(0));
            });
            // Record BEFORE responding so the capture is visible once the synchronous
            // client call returns.
            captures.add(new Capture(exchange.getRequestURI().getPath(), headers,
                    new String(in, StandardCharsets.UTF_8)));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    private String urlFor(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    /**
     * SEC-14 seam: a delivery-time guard that RESOLVES the URL's host and returns its real addresses
     * (so the production IP-pin / DnsResolver path still runs against the loopback receiver) but skips
     * the public/https policy checks the real {@code WebhookUrlValidator} enforces. Used ONLY in tests;
     * production keeps the full validator. A genuinely unresolvable host still rejects (fail-closed).
     */
    private static Function<String, List<InetAddress>> loopbackPermittingGuard() {
        return url -> {
            String host = URI.create(url).getHost();
            try {
                return List.of(InetAddress.getAllByName(host));
            } catch (UnknownHostException e) {
                throw new io.nexuspay.common.net.WebhookUrlValidationException(
                        "test guard: host unresolvable: " + host);
            }
        };
    }

    /**
     * INT-4: a mocked delivery repo whose saveAndFlush ECHOES the entity (so recordDelivery returns a
     * non-null PENDING row and the attempt proceeds). findByEndpointIdAndEventId defaults to empty, so the
     * idempotency fast-path never short-circuits these routing/HMAC tests.
     */
    private static JpaWebhookDeliveryRepository recordingDeliveryRepo() {
        JpaWebhookDeliveryRepository repo = mock(JpaWebhookDeliveryRepository.class);
        when(repo.saveAndFlush(any(WebhookDeliveryEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        return repo;
    }

    private WebhookEndpointEntity endpoint(String id, String path, String secret, List<String> events) {
        return new WebhookEndpointEntity(id, urlFor(path), "desc", secret, events, "t1");
    }

    /** Endpoints in this test are registered under tenant "t1"; stamp the matching event tenant header. */
    private static final String TENANT = "t1";

    private ConsumerRecord<String, String> recordWithHeader(String eventType, String payload) {
        var rec = new ConsumerRecord<>(Topics.PAYMENTS, 0, 0L, "k", payload);
        rec.headers().add(new RecordHeader("event_type", eventType.getBytes(StandardCharsets.UTF_8)));
        // SEC-09 (B-009): the consumer now ALWAYS filters by the event's tenant, so the event must carry
        // the tenant its target endpoints are registered under.
        rec.headers().add(new RecordHeader("tenant_id", TENANT.getBytes(StandardCharsets.UTF_8)));
        return rec;
    }

    /**
     * INT-1: a minimal valid internal outbox payload for a captured payment. The consumer transforms this
     * into the canonical envelope at send time; tests assert on the transformed body the receiver gets.
     */
    private static String captureOutbox() {
        return "{\"event_id\":\"evt_1\",\"event_type\":\"PaymentCaptured\",\"aggregate_type\":\"Payment\","
                + "\"aggregate_id\":\"pay_abc123\",\"timestamp\":\"2026-06-15T14:03:22.511Z\",\"version\":1,"
                + "\"metadata\":{\"source\":\"hyperswitch_webhook\",\"original_event_id\":\"hs_evt_77\"},"
                + "\"payload\":{\"payment_id\":\"pay_abc123\",\"amount\":4999,\"currency\":\"USD\","
                + "\"status\":\"succeeded\"}}";
    }

    private static String expectedHmac(String payload, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hash);
    }

    // ---- INT-1: canonical envelope + signature over the transformed body + metadata enrichment ----

    @Test
    void deliversCanonicalEnvelope_withSignatureOverTransformedBody() throws Exception {
        String secret = "whsec_supersecret";
        when(repository.findAllByTenantIdAndEnabledTrue(TENANT))
                .thenReturn(List.of(endpoint("we_1", "/canonical", secret, List.of("payment.succeeded"))));
        // A service whose metadata port returns merchant correlation keys (tenant-scoped store stub).
        java.util.Map<String, Object> stubMeta = new java.util.LinkedHashMap<>();
        stubMeta.put("userId", "u_42");
        stubMeta.put("packId", "gold");
        var enriched = new WebhookDeliveryService(repository, deliveryRepository, objectMapper, tenantWork,
                (gatewayPaymentId, tenant) -> stubMeta,
                false, loopbackPermittingGuard());

        enriched.onPaymentEvent(recordWithHeader("PaymentCaptured", captureOutbox()));

        assertThat(captures).hasSize(1);
        Capture c = captures.get(0);
        var env = objectMapper.readTree(c.body());
        // canonical shape + values
        assertThat(env.path("type").asText()).isEqualTo("payment.succeeded");
        assertThat(env.path("api_version").asText()).isEqualTo("2026-06-16");
        assertThat(env.path("id").asText()).isEqualTo("hs_evt_77");
        assertThat(env.path("data").path("metadata").path("userId").asText()).isEqualTo("u_42");
        assertThat(env.path("data").path("metadata").path("packId").asText()).isEqualTo("gold");
        // signature covers the EXACT transformed body the receiver got (re-sign proof).
        assertThat(c.headers().get("X-nexuspay-signature"))
                .as("signature must be HMAC-SHA256 over the transformed envelope body, not the raw payload")
                .isEqualTo(expectedHmac(c.body(), secret));
        assertThat(c.headers().get("X-nexuspay-event")).isEqualTo("payment.succeeded");
    }

    // ---- HMAC signature (INT-1: over the TRANSFORMED canonical body) ----

    @Test
    void deliversWithCorrectHmacSha256Signature() throws Exception {
        String secret = "whsec_supersecret";
        when(repository.findAllByTenantIdAndEnabledTrue(TENANT))
                .thenReturn(List.of(endpoint("we_1", "/hook", secret, List.of("payment.succeeded"))));

        service.onPaymentEvent(recordWithHeader("PaymentCaptured", captureOutbox()));

        assertThat(captures).hasSize(1);
        Capture c = captures.get(0);
        assertThat(c.path()).isEqualTo("/hook");
        // INT-1: the POSTed body is the canonical envelope, NOT the raw outbox payload.
        assertThat(c.body()).contains("\"api_version\":\"2026-06-16\"");
        assertThat(c.body()).contains("\"type\":\"payment.succeeded\"");
        // com.sun.net.httpserver.Headers normalizes header names by uppercasing ONLY the first
        // character and lowercasing the rest, so production's "X-NexusPay-Signature" is captured as
        // "X-nexuspay-signature".
        // INT-1: the signature must cover the EXACT transformed body the receiver got — recompute over
        // the captured body. If the re-sign were reverted (signing the raw payload), this fails.
        assertThat(c.headers().get("X-nexuspay-signature"))
                .as("signature must be HMAC-SHA256(transformedBody, secret) hex")
                .isEqualTo(expectedHmac(c.body(), secret));
        assertThat(c.headers().get("X-nexuspay-event")).isEqualTo("payment.succeeded");
    }

    // ---- subscription filtering (INT-1: dotted names) ----

    @Test
    void emptyEvents_receivesAllEvents() {
        when(repository.findAllByTenantIdAndEnabledTrue(TENANT))
                .thenReturn(List.of(endpoint("we_1", "/all", "s", Collections.emptyList())));

        service.onPaymentEvent(recordWithHeader("RefundCompleted", captureOutbox()));

        assertThat(captures).hasSize(1);
        assertThat(captures.get(0).path()).isEqualTo("/all");
    }

    @Test
    void nullEvents_receivesAllEvents() {
        when(repository.findAllByTenantIdAndEnabledTrue(TENANT))
                .thenReturn(List.of(endpoint("we_1", "/null", "s", null)));

        service.onPaymentEvent(recordWithHeader("RefundCompleted", captureOutbox()));

        assertThat(captures).hasSize(1);
        assertThat(captures.get(0).path()).isEqualTo("/null");
    }

    @Test
    void wildcardEvents_receivesAnyEvent() {
        when(repository.findAllByTenantIdAndEnabledTrue(TENANT))
                .thenReturn(List.of(endpoint("we_1", "/star", "s", List.of("*"))));

        service.onPaymentEvent(recordWithHeader("PaymentCaptured", captureOutbox()));

        assertThat(captures).hasSize(1);
        assertThat(captures.get(0).path()).isEqualTo("/star");
    }

    @Test
    void specificSubscription_skipsNonMatchingType() {
        when(repository.findAllByTenantIdAndEnabledTrue(TENANT))
                .thenReturn(List.of(endpoint("we_1", "/specific", "s", List.of("payment.succeeded"))));

        // PaymentFailed -> payment.failed, which the endpoint does NOT subscribe to.
        service.onPaymentEvent(recordWithHeader("PaymentFailed", captureOutbox()));

        assertThat(captures).as("non-subscribed event type must not be delivered").isEmpty();
    }

    @Test
    void specificSubscription_deliversMatchingType() {
        when(repository.findAllByTenantIdAndEnabledTrue(TENANT))
                .thenReturn(List.of(endpoint("we_1", "/specific", "s", List.of("payment.succeeded"))));

        service.onPaymentEvent(recordWithHeader("PaymentCaptured", captureOutbox()));

        assertThat(captures).hasSize(1);
        assertThat(captures.get(0).body()).contains("\"type\":\"payment.succeeded\"");
    }

    // ---- INT-1: unmapped internal type is NOT deliverable ----

    @Test
    void unmappedInternalType_isDropped_noLookupNoDelivery() {
        // SubscriptionCreated has no canonical webhook mapping -> dropped before any endpoint lookup.
        service.onPaymentEvent(recordWithHeader("SubscriptionCreated", captureOutbox()));

        verify(repository, never()).findAllByTenantIdAndEnabledTrue(anyString());
        assertThat(captures).isEmpty();
    }

    // ---- event type extraction ----

    @Test
    void eventType_parsedFromPayload_whenHeaderAbsent() {
        // No tenant header either; extractTenant falls back to "default" (no metadata.tenant_id in payload).
        when(repository.findAllByTenantIdAndEnabledTrue("default"))
                .thenReturn(List.of(endpoint("we_1", "/frompayload", "s", List.of("payment.succeeded"))));
        // No event_type header — extractEventType parses event_type from the payload (PaymentCaptured).
        var rec = new ConsumerRecord<>(Topics.PAYMENTS, 0, 0L, "k", captureOutbox());

        service.onPaymentEvent(rec);

        assertThat(captures).hasSize(1);
        // Sun Headers lowercases everything after the first char: "X-NexusPay-Event" -> "X-nexuspay-event".
        assertThat(captures.get(0).headers().get("X-nexuspay-event")).isEqualTo("payment.succeeded");
    }

    @Test
    void noEventType_anywhere_skipsWithoutRepositoryLookup() {
        // Neither header nor payload event_type -> early return before any endpoint lookup.
        var rec = new ConsumerRecord<>(Topics.PAYMENTS, 0, 0L, "k", "{\"no\":\"type\"}");

        service.onPaymentEvent(rec);

        // Early-return on missing event_type happens BEFORE any endpoint lookup (either finder).
        verify(repository, never()).findAllByTenantIdAndEnabledTrue(anyString());
        verify(repository, never()).findAllByEnabledTrue();
        assertThat(captures).isEmpty();
    }

    // ---- no endpoints ----

    @Test
    void noEndpoints_returnsEarly_noDelivery() {
        when(repository.findAllByTenantIdAndEnabledTrue(TENANT)).thenReturn(Collections.emptyList());

        service.onPaymentEvent(recordWithHeader("PaymentCaptured", captureOutbox()));

        assertThat(captures).isEmpty();
    }

    // ---- resilience across endpoints ----

    @Test
    void oneFailingEndpoint_doesNotAbortDeliveryToOthers() {
        // First endpoint points at a dead port (connection refused -> RestClient throws);
        // the loop must continue and still deliver to the live endpoint.
        var dead = new WebhookEndpointEntity("we_dead", "http://127.0.0.1:1/dead", "d", "s",
                List.of("payment.succeeded"), "t1");
        var live = endpoint("we_live", "/live", "s", List.of("payment.succeeded"));
        when(repository.findAllByTenantIdAndEnabledTrue(TENANT)).thenReturn(List.of(dead, live));

        service.onPaymentEvent(recordWithHeader("PaymentCaptured", captureOutbox()));

        assertThat(captures)
                .as("a failing endpoint must not block the others")
                .hasSize(1);
        assertThat(captures.get(0).path()).isEqualTo("/live");
    }
}
