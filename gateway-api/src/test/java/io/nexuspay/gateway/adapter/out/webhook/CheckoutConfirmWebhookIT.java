package io.nexuspay.gateway.adapter.out.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.nexuspay.common.event.Topics;
import io.nexuspay.common.mode.PaymentMode;
import io.nexuspay.common.net.WebhookUrlValidationException;
import io.nexuspay.common.rls.TenantWorkRunner;
import io.nexuspay.gateway.adapter.in.rest.CheckoutController;
import io.nexuspay.gateway.adapter.in.rest.dto.ConfirmResponse;
import io.nexuspay.gateway.adapter.in.rest.dto.ConfirmSessionRequest;
import io.nexuspay.gateway.adapter.out.persistence.JpaWebhookDeliveryRepository;
import io.nexuspay.gateway.adapter.out.persistence.JpaWebhookEndpointRepository;
import io.nexuspay.gateway.adapter.out.persistence.WebhookDeliveryEntity;
import io.nexuspay.gateway.adapter.out.persistence.WebhookEndpointEntity;
import io.nexuspay.gateway.application.port.in.TokenizePaymentMethodUseCase;
import io.nexuspay.gateway.application.port.out.PaymentTokenRepository;
import io.nexuspay.gateway.application.service.PaymentSessionService;
import io.nexuspay.gateway.domain.PaymentSession;
import io.nexuspay.iam.domain.NexusPayPrincipal;
import io.nexuspay.payment.adapter.out.hyperswitch.HyperSwitchPaymentAdapter;
import io.nexuspay.payment.adapter.out.mock.MockPaymentGatewayPort;
import io.nexuspay.payment.adapter.out.outbox.OutboxEvent;
import io.nexuspay.payment.adapter.out.outbox.OutboxEventRepository;
import io.nexuspay.payment.application.screening.CaptureHoldService;
import io.nexuspay.payment.application.screening.GatedPaymentGateway;
import io.nexuspay.payment.application.screening.PreAuthorizationGate;
import io.nexuspay.payment.application.screening.ScreeningOriginService;
import io.nexuspay.payment.application.webhook.MockWebhookSynthesizer;
import io.nexuspay.payment.application.webhook.WebhookMetadataService;
import io.nexuspay.payment.domain.event.PaymentEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
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
 * INT-6 end-to-end: a TEST-mode {@code POST /v1/checkout/confirm} flows the REAL pipeline
 * (CheckoutController -&gt; @Primary GatedPaymentGateway -&gt; MockPaymentGatewayPort auto-capture -&gt;
 * MockWebhookSynthesizer) to a {@code succeeded} {@link ConfirmResponse}, AND the synthesized canonical
 * outbox event delivers a {@code payment.succeeded} webhook (livemode=false, valid HMAC) to the merchant
 * endpoint via the existing {@link WebhookDeliveryService}.
 *
 * <p>Two halves, both real code (mirrors {@link MockWebhookSynthesisDeliveryIT}):
 * <ol>
 *   <li>Drive a real test-mode confirm: assert the returned {@link ConfirmResponse} is
 *       {@code {status:"succeeded", mode:"test", livemode:false, paymentId starts "pay_test_"}} and capture
 *       the synthesized {@link OutboxEvent} (mocked {@link OutboxEventRepository}).</li>
 *   <li>Feed that outbox payload through {@link WebhookDeliveryService#onPaymentEvent} to a 127.0.0.1
 *       loopback {@link HttpServer} (L-055: the loopback-permitting guard pins the IP; the merchant
 *       endpoint config uses a resolvable {@code example.com}-class host); assert exactly one delivered
 *       {@code payment.succeeded} envelope, {@code livemode=false}, with the correct HMAC.</li>
 * </ol>
 * Reverting the INT-6 return-type fix fails half-1's {@code instanceof ConfirmResponse}; reverting INT-3
 * synthesis/mode stamping fails half-2's {@code type}/{@code livemode} assertions.</p>
 */
class CheckoutConfirmWebhookIT {

    private static final String TENANT = "tenant-A";

    // ---- confirm-side (half 1) collaborators ----
    private PaymentSessionService sessionService;
    private PaymentTokenRepository paymentTokenRepository;
    private OutboxEventRepository outboxRepository;
    private CheckoutController controller;

    // ---- delivery-side (half 2) collaborators ----
    private JpaWebhookEndpointRepository endpointRepository;
    private TenantWorkRunner tenantWork;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;
    private final CopyOnWriteArrayList<Capture> captures = new CopyOnWriteArrayList<>();

    /** Session-scoped TEST-key principal (live=false) -> mode "test", livemode false. */
    private final NexusPayPrincipal testPrincipal = new NexusPayPrincipal(
            "checkout_user", TENANT, "operator",
            NexusPayPrincipal.AuthMethod.SESSION_TOKEN, "ps_1", false);

    private record Capture(String path, Map<String, String> headers, String body) {
    }

    @BeforeEach
    void setUp() throws IOException {
        // --- half 1: a REAL GatedPaymentGateway routed to the REAL MockPaymentGatewayPort in test mode ---
        sessionService = mock(PaymentSessionService.class);
        paymentTokenRepository = mock(PaymentTokenRepository.class);
        outboxRepository = mock(OutboxEventRepository.class);

        var hyperSwitch = mock(HyperSwitchPaymentAdapter.class); // never reached on the test-mode path
        var mockGateway = new MockPaymentGatewayPort();
        var gate = mock(PreAuthorizationGate.class);             // SKIPPED on the test-mode path
        var captureHolds = mock(CaptureHoldService.class);

        // Real origin/metadata services over mocked repos: their idempotent saves succeed (no-op verify).
        var originRepo = mock(io.nexuspay.payment.adapter.out.persistence.ScreeningOriginRepository.class);
        when(originRepo.existsById(any())).thenReturn(false);
        var origins = new ScreeningOriginService(originRepo);
        var metaRepo = mock(io.nexuspay.payment.adapter.out.persistence.PaymentWebhookMetadataRepository.class);
        when(metaRepo.existsById(any())).thenReturn(false);
        var webhookMetadata = new WebhookMetadataService(metaRepo, objectMapper);
        var synthesizer = new MockWebhookSynthesizer(outboxRepository, objectMapper, origins);

        var gatedGateway = new GatedPaymentGateway(hyperSwitch, mockGateway, gate, captureHolds,
                origins, webhookMetadata, synthesizer,
                mock(io.nexuspay.payment.application.service.projection.PaymentProjectionService.class),
                new io.nexuspay.payment.application.service.clock.TestClockService(
                        mock(io.nexuspay.payment.application.port.out.TestClockRepository.class)));

        var tokenizeUseCase = mock(TokenizePaymentMethodUseCase.class);
        controller = new CheckoutController(sessionService, tokenizeUseCase, paymentTokenRepository, gatedGateway);

        // --- half 2: loopback receiver + delivery collaborators ---
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
        PaymentMode.clear();
        RequestContextHolder.resetRequestAttributes();
        if (server != null) server.stop(0);
    }

    private String urlFor(String path) {
        // L-055: the seam guard skips the public/https policy but still pins the IP to loopback.
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    private static Function<String, List<InetAddress>> loopbackPermittingGuard() {
        return url -> {
            String host = URI.create(url).getHost();
            try {
                return List.of(InetAddress.getAllByName(host));
            } catch (UnknownHostException e) {
                throw new WebhookUrlValidationException("test guard: host unresolvable: " + host);
            }
        };
    }

    private WebhookDeliveryService deliveryWithMetadata(Map<String, Object> storedMeta) {
        JpaWebhookDeliveryRepository deliveryRepository = mock(JpaWebhookDeliveryRepository.class);
        when(deliveryRepository.saveAndFlush(any(WebhookDeliveryEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        return new WebhookDeliveryService(endpointRepository, deliveryRepository, objectMapper, tenantWork,
                (gatewayPaymentId, tenant) -> storedMeta, false, loopbackPermittingGuard());
    }

    private ConsumerRecord<String, String> record(String internalType, String outboxPayload) {
        var rec = new ConsumerRecord<>(Topics.PAYMENTS, 0, 0L, "k", outboxPayload);
        rec.headers().add(new RecordHeader("event_type", internalType.getBytes(StandardCharsets.UTF_8)));
        rec.headers().add(new RecordHeader("tenant_id", TENANT.getBytes(StandardCharsets.UTF_8)));
        return rec;
    }

    private static String expectedHmac(String payload, String secret) throws Exception {
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return java.util.HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }

    private PaymentSession openSession() {
        Instant now = Instant.now();
        return new PaymentSession("ps_1", TENANT, null, "secret",
                4999L, "USD", PaymentSession.STATUS_OPEN, "cust_1", List.of("card"),
                "https://example.com/success", "https://example.com/cancel",
                Map.of(), Map.of("userId", "u1", "packId", "p1"), 0, now.plusSeconds(600), now, now);
    }

    @Test
    void testModeConfirm_returnsSucceeded_andDeliversCanonicalTestWebhook() throws Exception {
        // Bind a servlet request + TEST mode so GatedPaymentGateway.routeToMock() resolves to the mock.
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        PaymentMode.set(false); // is_live=false -> TEST

        when(sessionService.findById("ps_1")).thenReturn(Optional.of(openSession()));
        when(paymentTokenRepository.findById(any())).thenReturn(Optional.empty());

        // --- half 1: drive the real test-mode confirm ---
        var resp = controller.confirmPayment(new ConfirmSessionRequest("ptok_1"), testPrincipal);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody())
                .as("INT-6: confirm returns ConfirmResponse, not the session status object")
                .isInstanceOf(ConfirmResponse.class);
        ConfirmResponse body = (ConfirmResponse) resp.getBody();
        assertThat(body.status()).isEqualTo("succeeded");
        assertThat(body.mode()).isEqualTo("test");
        assertThat(body.livemode()).isFalse();
        assertThat(body.paymentId()).startsWith(MockPaymentGatewayPort.PAY_PREFIX);

        // The mock auto-capture create synthesized exactly one canonical outbox event (PaymentCaptured).
        ArgumentCaptor<OutboxEvent> rowCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).save(rowCaptor.capture());
        OutboxEvent row = rowCaptor.getValue();
        assertThat(row.getEventType()).isEqualTo(PaymentEvent.PAYMENT_CAPTURED);
        assertThat(row.getAggregateId()).isEqualTo(body.paymentId());
        assertThat(row.getTenantId()).isEqualTo(TENANT);

        // --- half 2: deliver the synthesized canonical event to a loopback merchant endpoint ---
        String secret = "whsec_test";
        when(endpointRepository.findAllByTenantIdAndEnabledTrue(TENANT))
                .thenReturn(List.of(new WebhookEndpointEntity("we_1", urlFor("/hook"), "d", secret,
                        List.of("payment.succeeded"), TENANT)));
        Map<String, Object> stored = new java.util.LinkedHashMap<>();
        stored.put("userId", "u1");
        stored.put("packId", "p1");
        stored.put("__livemode", false);
        WebhookDeliveryService delivery = deliveryWithMetadata(stored);

        delivery.onPaymentEvent(record(PaymentEvent.PAYMENT_CAPTURED, row.getPayload()));

        assertThat(captures).hasSize(1);
        JsonNode env = objectMapper.readTree(captures.get(0).body());
        assertThat(env.path("type").asText()).isEqualTo("payment.succeeded");
        assertThat(env.path("livemode").isBoolean()).isTrue();
        assertThat(env.path("livemode").asBoolean()).as("TEST webhook -> livemode=false").isFalse();
        // HMAC over the EXACT delivered bytes.
        assertThat(captures.get(0).headers().get("X-nexuspay-signature"))
                .isEqualTo(expectedHmac(captures.get(0).body(), secret));
    }
}
