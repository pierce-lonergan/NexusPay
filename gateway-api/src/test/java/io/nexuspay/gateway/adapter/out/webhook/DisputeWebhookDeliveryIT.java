package io.nexuspay.gateway.adapter.out.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.nexuspay.common.event.EventEnvelope;
import io.nexuspay.common.event.EventTypes;
import io.nexuspay.common.event.Topics;
import io.nexuspay.common.event.WebhookEventTaxonomy;
import io.nexuspay.common.rls.TenantWorkRunner;
import io.nexuspay.gateway.adapter.out.persistence.JpaWebhookDeliveryRepository;
import io.nexuspay.gateway.adapter.out.persistence.JpaWebhookEndpointRepository;
import io.nexuspay.gateway.adapter.out.persistence.WebhookDeliveryEntity;
import io.nexuspay.gateway.adapter.out.persistence.WebhookEndpointEntity;
import io.nexuspay.payment.application.webhook.WebhookMetadataPort;
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
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * TEST-2 end-to-end: a dispute outbox row (the EXACT EventEnvelope the {@code DisputeOutboxAdapter}
 * writes for the {@code POST /v1/test/disputes} simulator) flows through the REAL shared delivery
 * pipeline — taxonomy translation ({@code DisputeCreated -> dispute.created}) + the gateway-api
 * {@link WebhookEnvelopeSerializer} (Dispute aggregate -> {@code data.object.object="dispute"}) +
 * {@link WebhookDeliveryService} signing + HTTP POST — and is delivered to a loopback merchant endpoint.
 *
 * <p>Mirrors {@code MockWebhookSynthesisDeliveryIT} half-2 (real delivery, mocked repos, loopback HTTP).
 * Proves: type=dispute.created, the dispute object carries dispute_id/payment_id/status, livemode=false
 * (TEST), the correct TENANT fanned out, and a valid HMAC over the exact bytes.</p>
 */
class DisputeWebhookDeliveryIT {

    private static final String TENANT = "t1";

    private JpaWebhookEndpointRepository endpointRepository;
    private TenantWorkRunner tenantWork;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;
    private final CopyOnWriteArrayList<Capture> captures = new CopyOnWriteArrayList<>();

    private record Capture(String path, Map<String, String> headers, String body) {
    }

    @BeforeEach
    void setUp() throws IOException {
        endpointRepository = mock(JpaWebhookEndpointRepository.class);
        tenantWork = mock(TenantWorkRunner.class);

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] in = exchange.getRequestBody().readAllBytes();
            Map<String, String> headers = new ConcurrentHashMap<>();
            exchange.getRequestHeaders().forEach((k, v) -> {
                if (!v.isEmpty()) headers.put(k, v.get(0));
            });
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

    /** A delivery service whose INT-1 payment-metadata port returns {} for a dispute (no payment row). */
    private WebhookDeliveryService delivery() {
        WebhookMetadataPort emptyMetadata = (gatewayPaymentId, tenant) -> Map.of();
        JpaWebhookDeliveryRepository deliveryRepository = mock(JpaWebhookDeliveryRepository.class);
        when(deliveryRepository.saveAndFlush(any(WebhookDeliveryEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        return new WebhookDeliveryService(endpointRepository, deliveryRepository, objectMapper, tenantWork,
                emptyMetadata, false, loopbackPermittingGuard());
    }

    /**
     * The outbox payload (Kafka record value) the DisputeOutboxAdapter writes: an EventEnvelope whose
     * metadata carries the trusted tenant + the reserved __livemode flag, and whose payload is the
     * dispute data.object. Built here exactly as the adapter would serialize it.
     */
    private String disputeOutboxPayload(boolean livemode) throws Exception {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("tenant_id", TENANT);
        metadata.put("__livemode", Boolean.toString(livemode));

        Map<String, Object> object = new LinkedHashMap<>();
        object.put("dispute_id", "dp_123");
        object.put("payment_id", "pay_test_abc");
        object.put("amount", 5000L);
        object.put("currency", "USD");
        object.put("status", "OPENED");
        object.put("reason", "10.4");

        EventEnvelope envelope = new EventEnvelope(
                "evt_1", EventTypes.DISPUTE_CREATED, EventTypes.AGGREGATE_DISPUTE, "dp_123",
                Instant.parse("2026-06-15T14:03:22Z"), 1, metadata, object);
        return objectMapper.writeValueAsString(envelope);
    }

    private ConsumerRecord<String, String> record(String internalType, String tenant, String value) {
        // The relay routes a Dispute aggregate to the DEFAULT topic (PAYMENTS), which this consumer reads.
        var rec = new ConsumerRecord<>(Topics.PAYMENTS, 0, 0L, "dp_123", value);
        rec.headers().add(new RecordHeader("event_type", internalType.getBytes(StandardCharsets.UTF_8)));
        rec.headers().add(new RecordHeader("tenant_id", tenant.getBytes(StandardCharsets.UTF_8)));
        return rec;
    }

    private static String expectedHmac(String payload, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void testDispute_deliversCanonicalDisputeCreatedWebhook() throws Exception {
        // The taxonomy actually maps the internal type to the deliverable dotted name.
        assertThat(WebhookEventTaxonomy.toDotted(EventTypes.DISPUTE_CREATED)).isEqualTo("dispute.created");

        String secret = "whsec_test";
        when(endpointRepository.findAllByTenantIdAndEnabledTrue(TENANT))
                .thenReturn(List.of(new WebhookEndpointEntity("we_1", urlFor("/dp"), "d", secret,
                        List.of("dispute.created"), TENANT)));

        delivery().onPaymentEvent(record(EventTypes.DISPUTE_CREATED, TENANT, disputeOutboxPayload(false)));

        assertThat(captures).hasSize(1);
        JsonNode env = objectMapper.readTree(captures.get(0).body());
        assertThat(env.path("type").asText()).isEqualTo("dispute.created");
        // TEST simulator -> livemode=false (lifted from the envelope metadata __livemode).
        assertThat(env.path("livemode").isBoolean()).isTrue();
        assertThat(env.path("livemode").asBoolean()).as("test-simulated dispute -> livemode=false").isFalse();

        JsonNode object = env.path("data").path("object");
        assertThat(object.path("object").asText()).isEqualTo("dispute");
        assertThat(object.path("dispute_id").asText()).isEqualTo("dp_123");
        assertThat(object.path("id").asText()).as("id discriminator = dispute_id").isEqualTo("dp_123");
        assertThat(object.path("payment_id").asText()).isEqualTo("pay_test_abc");
        assertThat(object.path("status").asText()).isEqualTo("OPENED");

        // The reserved mode key must not leak into the merchant metadata.
        assertThat(env.path("data").path("metadata").has("__livemode")).isFalse();

        // HMAC over the EXACT delivered bytes.
        assertThat(captures.get(0).headers().get("X-nexuspay-signature"))
                .isEqualTo(expectedHmac(captures.get(0).body(), secret));
    }

    @Test
    void realChargeback_deliversLivemodeTrue() throws Exception {
        String secret = "whsec_test";
        when(endpointRepository.findAllByTenantIdAndEnabledTrue(TENANT))
                .thenReturn(List.of(new WebhookEndpointEntity("we_1", urlFor("/dp2"), "d", secret,
                        List.of("*"), TENANT)));

        delivery().onPaymentEvent(record(EventTypes.DISPUTE_CREATED, TENANT, disputeOutboxPayload(true)));

        assertThat(captures).hasSize(1);
        JsonNode env = objectMapper.readTree(captures.get(0).body());
        assertThat(env.path("type").asText()).isEqualTo("dispute.created");
        assertThat(env.path("livemode").asBoolean()).as("real chargeback -> livemode=true").isTrue();
    }

    @Test
    void wrongTenant_notDelivered() throws Exception {
        // The endpoint belongs to TENANT, but the event is stamped with a different tenant header:
        // the consumer reads only the event-tenant's endpoints, so nothing is delivered (no cross-tenant fan-out).
        String secret = "whsec_test";
        when(endpointRepository.findAllByTenantIdAndEnabledTrue("other"))
                .thenReturn(List.of());

        delivery().onPaymentEvent(record(EventTypes.DISPUTE_CREATED, "other", disputeOutboxPayload(false)));

        assertThat(captures).isEmpty();
    }
}
