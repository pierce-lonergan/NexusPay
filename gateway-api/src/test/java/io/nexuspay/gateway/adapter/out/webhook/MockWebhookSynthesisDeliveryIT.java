package io.nexuspay.gateway.adapter.out.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.nexuspay.common.event.Topics;
import io.nexuspay.common.rls.TenantWorkRunner;
import io.nexuspay.gateway.adapter.out.persistence.JpaWebhookEndpointRepository;
import io.nexuspay.gateway.adapter.out.persistence.WebhookEndpointEntity;
import io.nexuspay.payment.adapter.out.outbox.OutboxEvent;
import io.nexuspay.payment.adapter.out.outbox.OutboxEventRepository;
import io.nexuspay.payment.application.screening.ScreeningOriginService;
import io.nexuspay.payment.application.webhook.MockWebhookSynthesizer;
import io.nexuspay.payment.domain.PaymentResponse;
import io.nexuspay.payment.domain.RefundResponse;
import io.nexuspay.payment.domain.event.PaymentEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * INT-3 (T5/T6): the mock-payment round-trip — a terminal TEST op synthesizes the SAME outbox event the
 * real webhook controller would, and the existing {@code WebhookDeliveryService} pipeline delivers a
 * canonical, TEST-mode webhook (livemode=false) to the merchant with the INT-1 metadata enriched and the
 * HMAC over the exact bytes.
 *
 * <p>Two halves, both real code:
 * <ol>
 *   <li>{@link MockWebhookSynthesizer} writes the outbox row (captured via a mocked
 *       {@link OutboxEventRepository}); assert its shape (aggregate type/id, internal event type, tenant).</li>
 *   <li>Feed that outbox payload through {@link WebhookDeliveryService#onPaymentEvent} (the INT-1 loopback
 *       seam, L-055 honoured by the loopback-permitting guard) with the INT-1 metadata store returning
 *       {@code {userId, packId, __livemode:false}}; assert the delivered envelope.</li>
 * </ol>
 * Reverting synthesis (no outbox), mode stamping (livemode missing/true), or metadata persistence
 * (metadata {}) each fails a distinct assertion.</p>
 */
class MockWebhookSynthesisDeliveryIT {

    private static final String TENANT = "t1";

    // ---- half 1: synthesizer collaborators ----
    private OutboxEventRepository outboxRepository;
    private ScreeningOriginService origins;
    private MockWebhookSynthesizer synthesizer;

    // ---- half 2: delivery collaborators (loopback receiver) ----
    private JpaWebhookEndpointRepository endpointRepository;
    private TenantWorkRunner tenantWork;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;
    private final CopyOnWriteArrayList<Capture> captures = new CopyOnWriteArrayList<>();

    private record Capture(String path, Map<String, String> headers, String body) {
    }

    @BeforeEach
    void setUp() throws IOException {
        outboxRepository = mock(OutboxEventRepository.class);
        origins = mock(ScreeningOriginService.class);
        synthesizer = new MockWebhookSynthesizer(outboxRepository, objectMapper, origins);

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
        // L-055: example.com resolves; the seam guard skips the public/https policy but still pins the IP.
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

    /** Delivery service whose INT-1 metadata port returns the stored correlation map + __livemode. */
    private WebhookDeliveryService deliveryWithMetadata(Map<String, Object> storedMeta) {
        return new WebhookDeliveryService(endpointRepository, objectMapper, tenantWork,
                (gatewayPaymentId, tenant) -> storedMeta, false, loopbackPermittingGuard());
    }

    private ConsumerRecord<String, String> record(String internalType, String outboxPayload) {
        var rec = new ConsumerRecord<>(Topics.PAYMENTS, 0, 0L, "k", outboxPayload);
        rec.headers().add(new RecordHeader("event_type", internalType.getBytes(StandardCharsets.UTF_8)));
        rec.headers().add(new RecordHeader("tenant_id", TENANT.getBytes(StandardCharsets.UTF_8)));
        return rec;
    }

    private static String expectedHmac(String payload, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }

    private static PaymentResponse capturedPayment(String id) {
        return new PaymentResponse(id, PaymentResponse.STATUS_SUCCEEDED, 4999, "USD", "automatic",
                "cust_1", "mock", "txn_test_1", null, null, Instant.EPOCH, Map.of());
    }

    /** The INT-1 metadata the V4030 store returns for a TEST payment: correlation keys + __livemode=false. */
    private static Map<String, Object> testStoreMetadata() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("userId", "u1");
        m.put("packId", "p1");
        m.put("__livemode", false);
        return m;
    }

    // ---- T5: capture synthesizes a deliverable canonical TEST webhook with metadata ----

    @Test
    void mockCapture_synthesizesOutbox_thenDeliversCanonicalTestWebhook() throws Exception {
        // half 1: synthesize from a captured mock payment; origin resolves the real tenant.
        when(origins.find("pay_test_x")).thenReturn(
                Optional.of(new ScreeningOriginService.Origin(TENANT,
                        io.nexuspay.payment.application.screening.ScreeningMode.INTERACTIVE)));
        synthesizer.onTerminal(capturedPayment("pay_test_x"), TENANT, PaymentEvent.PAYMENT_CAPTURED);

        ArgumentCaptor<OutboxEvent> rowCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).save(rowCaptor.capture());
        OutboxEvent row = rowCaptor.getValue();
        assertThat(row.getAggregateType()).isEqualTo("Payment");
        assertThat(row.getEventType()).isEqualTo(PaymentEvent.PAYMENT_CAPTURED);
        assertThat(row.getAggregateId()).isEqualTo("pay_test_x");
        assertThat(row.getTenantId()).as("trusted tenant from the origin store, not 'default'").isEqualTo(TENANT);

        // half 2: feed the synthesized payload through the real delivery pipeline to a loopback endpoint.
        String secret = "whsec_test";
        when(endpointRepository.findAllByTenantIdAndEnabledTrue(TENANT))
                .thenReturn(List.of(new WebhookEndpointEntity("we_1", urlFor("/cap"), "d", secret,
                        List.of("payment.succeeded"), TENANT)));
        WebhookDeliveryService delivery = deliveryWithMetadata(testStoreMetadata());

        delivery.onPaymentEvent(record(PaymentEvent.PAYMENT_CAPTURED, row.getPayload()));

        assertThat(captures).hasSize(1);
        JsonNode env = objectMapper.readTree(captures.get(0).body());
        assertThat(env.path("type").asText()).isEqualTo("payment.succeeded");
        assertThat(env.path("livemode").isBoolean()).isTrue();
        assertThat(env.path("livemode").asBoolean()).as("TEST webhook -> livemode=false").isFalse();
        JsonNode meta = env.path("data").path("metadata");
        assertThat(meta.path("userId").asText()).isEqualTo("u1");
        assertThat(meta.path("packId").asText()).isEqualTo("p1");
        assertThat(meta.has("__livemode")).as("reserved key must not leak").isFalse();
        // HMAC over the EXACT delivered bytes (livemode is inside them).
        assertThat(captures.get(0).headers().get("X-nexuspay-signature"))
                .isEqualTo(expectedHmac(captures.get(0).body(), secret));
    }

    // ---- T6: refund synthesizes RefundCompleted -> payment.refunded ----

    @Test
    void mockRefund_synthesizesRefundCompleted_deliversPaymentRefunded() throws Exception {
        when(origins.find("pay_test_x")).thenReturn(
                Optional.of(new ScreeningOriginService.Origin(TENANT,
                        io.nexuspay.payment.application.screening.ScreeningMode.INTERACTIVE)));
        RefundResponse refund = new RefundResponse("re_test_1", "pay_test_x",
                RefundResponse.STATUS_SUCCEEDED, 4999, "USD", "req", "mock", "txn_test_2",
                null, null, Instant.EPOCH);

        synthesizer.onRefundTerminal(refund, TENANT, PaymentEvent.REFUND_COMPLETED);

        ArgumentCaptor<OutboxEvent> rowCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).save(rowCaptor.capture());
        OutboxEvent row = rowCaptor.getValue();
        assertThat(row.getAggregateType()).isEqualTo("Refund");
        assertThat(row.getEventType()).isEqualTo(PaymentEvent.REFUND_COMPLETED);
        // aggregate_id = payment id (mirrors the real controller), so INT-1 metadata round-trips by payment id.
        assertThat(row.getAggregateId()).isEqualTo("pay_test_x");
        assertThat(row.getTenantId()).isEqualTo(TENANT);

        String secret = "whsec_test";
        when(endpointRepository.findAllByTenantIdAndEnabledTrue(TENANT))
                .thenReturn(List.of(new WebhookEndpointEntity("we_1", urlFor("/ref"), "d", secret,
                        List.of("payment.refunded"), TENANT)));
        WebhookDeliveryService delivery = deliveryWithMetadata(testStoreMetadata());

        delivery.onPaymentEvent(record(PaymentEvent.REFUND_COMPLETED, row.getPayload()));

        assertThat(captures).hasSize(1);
        JsonNode env = objectMapper.readTree(captures.get(0).body());
        assertThat(env.path("type").asText()).isEqualTo("payment.refunded");
        assertThat(env.path("livemode").asBoolean()).isFalse();
        assertThat(env.path("data").path("object").path("object").asText()).isEqualTo("refund");
        assertThat(env.path("data").path("metadata").path("userId").asText()).isEqualTo("u1");
    }

    // ---- guard: reverting synthesis (no outbox write) would mean no delivery at all ----

    @Test
    void synthesizerWritesExactlyOneOutboxRow_perTerminalOp() {
        synthesizer.onTerminal(capturedPayment("pay_test_y"), TENANT, PaymentEvent.PAYMENT_CAPTURED);
        verify(outboxRepository).save(any(OutboxEvent.class));
    }
}
