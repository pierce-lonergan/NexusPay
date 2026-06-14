package io.nexuspay.gateway.adapter.out.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.nexuspay.common.event.Topics;
import io.nexuspay.common.rls.TenantWorkRunner;
import io.nexuspay.gateway.adapter.out.persistence.JpaWebhookEndpointRepository;
import io.nexuspay.gateway.adapter.out.persistence.WebhookEndpointEntity;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
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
 */
class WebhookDeliveryServiceTest {

    private JpaWebhookEndpointRepository repository;
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
        tenantWork = mock(TenantWorkRunner.class);
        objectMapper = new ObjectMapper();
        // rlsEnforced=false -> service uses findAllByEnabledTrue() (dormant path).
        service = new WebhookDeliveryService(repository, objectMapper, tenantWork, false);

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

    private WebhookEndpointEntity endpoint(String id, String path, String secret, List<String> events) {
        return new WebhookEndpointEntity(id, urlFor(path), "desc", secret, events, "t1");
    }

    private ConsumerRecord<String, String> recordWithHeader(String eventType, String payload) {
        var rec = new ConsumerRecord<>(Topics.PAYMENTS, 0, 0L, "k", payload);
        rec.headers().add(new RecordHeader("event_type", eventType.getBytes(StandardCharsets.UTF_8)));
        return rec;
    }

    private static String expectedHmac(String payload, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hash);
    }

    // ---- HMAC signature ----

    @Test
    void deliversWithCorrectHmacSha256Signature() throws Exception {
        String secret = "whsec_supersecret";
        String payload = "{\"event_type\":\"payment.succeeded\",\"id\":\"pi_1\"}";
        when(repository.findAllByEnabledTrue())
                .thenReturn(List.of(endpoint("we_1", "/hook", secret, List.of("payment.succeeded"))));

        service.onPaymentEvent(recordWithHeader("payment.succeeded", payload));

        assertThat(captures).hasSize(1);
        Capture c = captures.get(0);
        assertThat(c.path()).isEqualTo("/hook");
        assertThat(c.body()).isEqualTo(payload);
        // com.sun.net.httpserver.Headers normalizes header names by uppercasing ONLY the first
        // character and lowercasing the rest, so production's "X-NexusPay-Signature" is captured as
        // "X-nexuspay-signature". The test copies these normalized keys into a plain map, so the
        // lookup key must match that exact casing.
        assertThat(c.headers().get("X-nexuspay-signature"))
                .as("signature must be HMAC-SHA256(payload, secret) hex")
                .isEqualTo(expectedHmac(payload, secret));
        assertThat(c.headers().get("X-nexuspay-event")).isEqualTo("payment.succeeded");
    }

    // ---- subscription filtering ----

    @Test
    void emptyEvents_receivesAllEvents() {
        when(repository.findAllByEnabledTrue())
                .thenReturn(List.of(endpoint("we_1", "/all", "s", Collections.emptyList())));

        service.onPaymentEvent(recordWithHeader("payment.refunded", "{\"x\":1}"));

        assertThat(captures).hasSize(1);
        assertThat(captures.get(0).path()).isEqualTo("/all");
    }

    @Test
    void nullEvents_receivesAllEvents() {
        when(repository.findAllByEnabledTrue())
                .thenReturn(List.of(endpoint("we_1", "/null", "s", null)));

        service.onPaymentEvent(recordWithHeader("payment.refunded", "{\"x\":1}"));

        assertThat(captures).hasSize(1);
        assertThat(captures.get(0).path()).isEqualTo("/null");
    }

    @Test
    void wildcardEvents_receivesAnyEvent() {
        when(repository.findAllByEnabledTrue())
                .thenReturn(List.of(endpoint("we_1", "/star", "s", List.of("*"))));

        service.onPaymentEvent(recordWithHeader("anything.at.all", "{\"x\":1}"));

        assertThat(captures).hasSize(1);
        assertThat(captures.get(0).path()).isEqualTo("/star");
    }

    @Test
    void specificSubscription_skipsNonMatchingType() {
        when(repository.findAllByEnabledTrue())
                .thenReturn(List.of(endpoint("we_1", "/specific", "s", List.of("payment.succeeded"))));

        service.onPaymentEvent(recordWithHeader("payment.failed", "{\"x\":1}"));

        assertThat(captures).as("non-subscribed event type must not be delivered").isEmpty();
    }

    @Test
    void specificSubscription_deliversMatchingType() {
        when(repository.findAllByEnabledTrue())
                .thenReturn(List.of(endpoint("we_1", "/specific", "s", List.of("payment.succeeded"))));

        service.onPaymentEvent(recordWithHeader("payment.succeeded", "{\"y\":2}"));

        assertThat(captures).hasSize(1);
        assertThat(captures.get(0).body()).isEqualTo("{\"y\":2}");
    }

    // ---- event type extraction ----

    @Test
    void eventType_parsedFromPayload_whenHeaderAbsent() {
        when(repository.findAllByEnabledTrue())
                .thenReturn(List.of(endpoint("we_1", "/frompayload", "s", List.of("payment.captured"))));
        String payload = "{\"event_type\":\"payment.captured\",\"id\":\"pi_2\"}";
        var rec = new ConsumerRecord<>(Topics.PAYMENTS, 0, 0L, "k", payload); // no event_type header

        service.onPaymentEvent(rec);

        assertThat(captures).hasSize(1);
        // Sun Headers lowercases everything after the first char: "X-NexusPay-Event" -> "X-nexuspay-event".
        assertThat(captures.get(0).headers().get("X-nexuspay-event")).isEqualTo("payment.captured");
    }

    @Test
    void noEventType_anywhere_skipsWithoutRepositoryLookup() {
        // Neither header nor payload event_type -> early return before any endpoint lookup.
        var rec = new ConsumerRecord<>(Topics.PAYMENTS, 0, 0L, "k", "{\"no\":\"type\"}");

        service.onPaymentEvent(rec);

        verify(repository, never()).findAllByEnabledTrue();
        assertThat(captures).isEmpty();
    }

    // ---- no endpoints ----

    @Test
    void noEndpoints_returnsEarly_noDelivery() {
        when(repository.findAllByEnabledTrue()).thenReturn(Collections.emptyList());

        service.onPaymentEvent(recordWithHeader("payment.succeeded", "{\"x\":1}"));

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
        when(repository.findAllByEnabledTrue()).thenReturn(List.of(dead, live));

        service.onPaymentEvent(recordWithHeader("payment.succeeded", "{\"z\":3}"));

        assertThat(captures)
                .as("a failing endpoint must not block the others")
                .hasSize(1);
        assertThat(captures.get(0).path()).isEqualTo("/live");
    }
}
