package io.nexuspay.gateway.adapter.out.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.nexuspay.common.event.Topics;
import io.nexuspay.common.rls.TenantWorkRunner;
import io.nexuspay.gateway.adapter.out.persistence.JpaWebhookDeliveryRepository;
import io.nexuspay.gateway.adapter.out.persistence.JpaWebhookEndpointRepository;
import io.nexuspay.gateway.adapter.out.persistence.WebhookDeliveryEntity;
import io.nexuspay.gateway.adapter.out.persistence.WebhookEndpointEntity;
import io.nexuspay.payment.adapter.out.outbox.OutboxEvent;
import io.nexuspay.payment.adapter.out.outbox.OutboxEventRepository;
import io.nexuspay.payment.adapter.out.persistence.PaymentWebhookMetadataEntity;
import io.nexuspay.payment.adapter.out.persistence.PaymentWebhookMetadataRepository;
import io.nexuspay.payment.application.screening.ScreeningOriginService;
import io.nexuspay.payment.application.webhook.MockWebhookSynthesizer;
import io.nexuspay.payment.application.webhook.WebhookMetadataPort;
import io.nexuspay.payment.application.webhook.WebhookMetadataService;
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
        return deliveryWithMetadataPort((gatewayPaymentId, tenant) -> storedMeta);
    }

    /**
     * Delivery service whose INT-1 metadata port is the supplied {@link WebhookMetadataPort}. Used by the
     * forced-decline leak test to wire the REAL {@link WebhookMetadataService} (so the delivered
     * {@code data.metadata} is exactly what {@code sanitize()} stored), rather than a fixed stub map.
     */
    private WebhookDeliveryService deliveryWithMetadataPort(WebhookMetadataPort metadataPort) {
        // INT-4: a mocked delivery repo whose saveAndFlush echoes the row so recordDelivery returns a PENDING
        // row and the synthesized event still drives a real loopback delivery.
        JpaWebhookDeliveryRepository deliveryRepository = mock(JpaWebhookDeliveryRepository.class);
        when(deliveryRepository.saveAndFlush(any(WebhookDeliveryEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        return new WebhookDeliveryService(endpointRepository, deliveryRepository, objectMapper, tenantWork,
                metadataPort, false, loopbackPermittingGuard());
    }

    /**
     * TEST-1: a REAL {@link WebhookMetadataService} backed by a mocked repository that echoes saved rows on
     * read. The merchant {@code merchantMeta} (which may include the reserved {@code __test_outcome} control
     * key) is pushed through the genuine {@code record() -> sanitize()} write path under {@code tenant}, then
     * recalled via {@code find()} at delivery — so a delivered envelope's {@code data.metadata} is exactly
     * what the production store would hold. This exercises the authoritative leak guard end-to-end (the same
     * one unit-proven by {@code WebhookMetadataServiceTest.testOutcomeControlKey_isStripped}).
     */
    private WebhookMetadataService realMetadataServiceWith(String paymentId, String tenant,
                                                           Map<String, Object> merchantMeta) {
        PaymentWebhookMetadataRepository repo = mock(PaymentWebhookMetadataRepository.class);
        when(repo.existsById(any())).thenReturn(false);
        Map<String, PaymentWebhookMetadataEntity> stored = new ConcurrentHashMap<>();
        when(repo.save(any(PaymentWebhookMetadataEntity.class))).thenAnswer(inv -> {
            PaymentWebhookMetadataEntity e = inv.getArgument(0);
            stored.put(e.getGatewayPaymentId(), e);
            return e;
        });
        when(repo.findById(any())).thenAnswer(inv -> Optional.ofNullable(stored.get(inv.getArgument(0))));
        WebhookMetadataService service = new WebhookMetadataService(repo, objectMapper);
        // server-derived livemode=false (TEST payment); record() sanitizes, stripping __test_outcome.
        service.record(paymentId, tenant, merchantMeta, false);
        return service;
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

    /** TEST-1: a forced-decline mock payment response (status=failed + error fields). */
    private static PaymentResponse failedPayment(String id) {
        return new PaymentResponse(id, PaymentResponse.STATUS_FAILED, 4999, "USD", "automatic",
                "cust_1", "mock", "txn_test_1", "card_declined", "Your card was declined.",
                Instant.EPOCH, Map.of());
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

    // ---- TEST-1: a forced decline synthesizes PaymentFailed -> delivers a canonical payment.failed ----

    @Test
    void forcedDecline_synthesizesPaymentFailed_deliversCanonicalFailedWebhook() throws Exception {
        when(origins.find("pay_test_x")).thenReturn(
                Optional.of(new ScreeningOriginService.Origin(TENANT,
                        io.nexuspay.payment.application.screening.ScreeningMode.INTERACTIVE)));
        synthesizer.onTerminalFailure(failedPayment("pay_test_x"), TENANT, PaymentEvent.PAYMENT_FAILED);

        ArgumentCaptor<OutboxEvent> rowCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).save(rowCaptor.capture());
        OutboxEvent row = rowCaptor.getValue();
        assertThat(row.getAggregateType()).isEqualTo("Payment");
        assertThat(row.getEventType()).isEqualTo(PaymentEvent.PAYMENT_FAILED);
        assertThat(row.getAggregateId()).isEqualTo("pay_test_x");
        assertThat(row.getTenantId()).isEqualTo(TENANT);

        String secret = "whsec_test";
        when(endpointRepository.findAllByTenantIdAndEnabledTrue(TENANT))
                .thenReturn(List.of(new WebhookEndpointEntity("we_1", urlFor("/fail"), "d", secret,
                        List.of("payment.failed"), TENANT)));
        // TEST-1 end-to-end leak guard: wire the REAL WebhookMetadataService and push the integrator's
        // merchant map — INCLUDING the reserved __test_outcome control key they set to force this decline —
        // through the genuine record()->sanitize() write path under the trusted tenant. The delivery then
        // recalls it via find(), so the DELIVERED data.metadata is exactly what production would store. This
        // assertion would FAIL if __test_outcome were dropped from WebhookMetadataService.FORBIDDEN (i.e. a
        // real leak), unlike the previous stub which sourced metadata that never contained the key at all.
        Map<String, Object> merchantMeta = new LinkedHashMap<>();
        merchantMeta.put("userId", "u1");
        merchantMeta.put("packId", "p1");
        merchantMeta.put("__test_outcome", "decline"); // forced-outcome control input — must never be delivered
        WebhookMetadataService metadataService = realMetadataServiceWith("pay_test_x", TENANT, merchantMeta);
        WebhookDeliveryService delivery = deliveryWithMetadataPort(metadataService);

        delivery.onPaymentEvent(record(PaymentEvent.PAYMENT_FAILED, row.getPayload()));

        assertThat(captures).hasSize(1);
        JsonNode env = objectMapper.readTree(captures.get(0).body());
        assertThat(env.path("type").asText()).isEqualTo("payment.failed");
        assertThat(env.path("livemode").asBoolean()).as("TEST webhook -> livemode=false").isFalse();
        JsonNode object = env.path("data").path("object");
        assertThat(object.path("status").asText()).isEqualTo("failed");
        assertThat(object.path("error_code").asText()).isEqualTo("card_declined");
        JsonNode meta = env.path("data").path("metadata");
        // (a) the reserved control key was stripped on the way INTO the store, so it can never be delivered.
        assertThat(meta.has("__test_outcome")).as("forced-outcome control key must NOT leak to the merchant")
                .isFalse();
        // (b) the merchant's real correlation keys survive the sanitize round-trip and ARE delivered.
        assertThat(meta.path("userId").asText()).isEqualTo("u1");
        assertThat(meta.path("packId").asText()).isEqualTo("p1");
        // (c) the server-reserved __livemode is also stripped by the serializer (lifted to top-level livemode).
        assertThat(meta.has("__livemode")).as("reserved mode key must not leak").isFalse();
    }

    // ---- TEST-1: a forced refund failure synthesizes RefundFailed -> delivers payment.refund.failed ----

    @Test
    void forcedRefundFailure_synthesizesRefundFailed_deliversPaymentRefundFailed() throws Exception {
        when(origins.find("pay_test_x")).thenReturn(
                Optional.of(new ScreeningOriginService.Origin(TENANT,
                        io.nexuspay.payment.application.screening.ScreeningMode.INTERACTIVE)));
        RefundResponse failed = new RefundResponse("re_test_1", "pay_test_x",
                RefundResponse.STATUS_FAILED, 1066, "USD", "req", "mock", "txn_test_2",
                "refund_failed", "The refund failed at the processor.", Instant.EPOCH);

        synthesizer.onRefundFailed(failed, TENANT, PaymentEvent.REFUND_FAILED);

        ArgumentCaptor<OutboxEvent> rowCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).save(rowCaptor.capture());
        OutboxEvent row = rowCaptor.getValue();
        assertThat(row.getAggregateType()).isEqualTo("Refund");
        assertThat(row.getEventType()).isEqualTo(PaymentEvent.REFUND_FAILED);
        assertThat(row.getAggregateId()).isEqualTo("pay_test_x");

        String secret = "whsec_test";
        when(endpointRepository.findAllByTenantIdAndEnabledTrue(TENANT))
                .thenReturn(List.of(new WebhookEndpointEntity("we_1", urlFor("/reffail"), "d", secret,
                        List.of("payment.refund.failed"), TENANT)));
        WebhookDeliveryService delivery = deliveryWithMetadata(testStoreMetadata());

        delivery.onPaymentEvent(record(PaymentEvent.REFUND_FAILED, row.getPayload()));

        assertThat(captures).hasSize(1);
        JsonNode env = objectMapper.readTree(captures.get(0).body());
        assertThat(env.path("type").asText()).isEqualTo("payment.refund.failed");
        assertThat(env.path("livemode").asBoolean()).isFalse();
        JsonNode object = env.path("data").path("object");
        assertThat(object.path("object").asText()).isEqualTo("refund");
        assertThat(object.path("status").asText()).isEqualTo("failed");
        assertThat(object.path("error_code").asText()).isEqualTo("refund_failed");
    }

    // ---- TEST-1 back-compat guard: a normal (success) terminal still delivers payment.succeeded ----

    @Test
    void unforcedSuccess_stillDeliversPaymentSucceeded() throws Exception {
        when(origins.find("pay_test_ok")).thenReturn(
                Optional.of(new ScreeningOriginService.Origin(TENANT,
                        io.nexuspay.payment.application.screening.ScreeningMode.INTERACTIVE)));
        synthesizer.onTerminal(capturedPayment("pay_test_ok"), TENANT, PaymentEvent.PAYMENT_CAPTURED);

        ArgumentCaptor<OutboxEvent> rowCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).save(rowCaptor.capture());

        String secret = "whsec_test";
        when(endpointRepository.findAllByTenantIdAndEnabledTrue(TENANT))
                .thenReturn(List.of(new WebhookEndpointEntity("we_1", urlFor("/ok"), "d", secret,
                        List.of("payment.succeeded"), TENANT)));
        WebhookDeliveryService delivery = deliveryWithMetadata(testStoreMetadata());

        delivery.onPaymentEvent(record(PaymentEvent.PAYMENT_CAPTURED, rowCaptor.getValue().getPayload()));

        assertThat(captures).hasSize(1);
        JsonNode env = objectMapper.readTree(captures.get(0).body());
        assertThat(env.path("type").asText()).isEqualTo("payment.succeeded");
        assertThat(env.path("data").path("object").path("status").asText()).isEqualTo("succeeded");
    }

    // ---- guard: reverting synthesis (no outbox write) would mean no delivery at all ----

    @Test
    void synthesizerWritesExactlyOneOutboxRow_perTerminalOp() {
        synthesizer.onTerminal(capturedPayment("pay_test_y"), TENANT, PaymentEvent.PAYMENT_CAPTURED);
        verify(outboxRepository).save(any(OutboxEvent.class));
    }
}
