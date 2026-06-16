package io.nexuspay.gateway.adapter.out.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.nexuspay.common.net.WebhookUrlValidationException;
import io.nexuspay.common.rls.TenantWorkRunner;
import io.nexuspay.gateway.adapter.out.persistence.JpaWebhookDeliveryRepository;
import io.nexuspay.gateway.adapter.out.persistence.JpaWebhookEndpointRepository;
import io.nexuspay.gateway.adapter.out.persistence.WebhookDeliveryEntity;
import io.nexuspay.gateway.adapter.out.persistence.WebhookEndpointEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.StringRedisTemplate;

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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * INT-4 (tests C + D + F): the retrier re-drives a FAILED delivery through the SAME SSRF-safe + canonical +
 * signed {@link WebhookDeliveryService#send} path, signs with the endpoint's CURRENT secret (so a rotation
 * takes effect on the next attempt), and a DELIVERED row is never auto-re-sent.
 *
 * <p>Uses a loopback receiver (L-055: a real {@code com.sun.net.httpserver.HttpServer} on 127.0.0.1 via the
 * loopback-permitting guard, which still resolves+pins the IP). Redis is mocked to throw so the leader lock
 * fails OPEN and the single-instance retrier proceeds (mirrors OutboxRelay's policy).</p>
 */
class WebhookDeliveryRetrierSecurePathTest {

    private static final String TENANT = "t1";

    private HttpServer server;
    private final CopyOnWriteArrayList<Capture> captures = new CopyOnWriteArrayList<>();

    private JpaWebhookEndpointRepository endpointRepository;
    private JpaWebhookDeliveryRepository deliveryRepository;
    private TenantWorkRunner tenantWork;
    private StringRedisTemplate redis;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private record Capture(Map<String, String> headers, String body) {
    }

    @BeforeEach
    void setUp() throws IOException {
        endpointRepository = mock(JpaWebhookEndpointRepository.class);
        deliveryRepository = mock(JpaWebhookDeliveryRepository.class);
        when(deliveryRepository.save(any(WebhookDeliveryEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        tenantWork = mock(TenantWorkRunner.class);
        // Redis throws -> acquireLeaderLock fails OPEN -> the single-instance retrier proceeds.
        redis = mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenThrow(new RuntimeException("valkey down"));

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] in = exchange.getRequestBody().readAllBytes();
            Map<String, String> headers = new ConcurrentHashMap<>();
            exchange.getRequestHeaders().forEach((k, v) -> {
                if (!v.isEmpty()) headers.put(k, v.get(0));
            });
            captures.add(new Capture(headers, new String(in, StandardCharsets.UTF_8)));
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
                throw new WebhookUrlValidationException("test guard: host unresolvable: " + host);
            }
        };
    }

    private WebhookDeliveryService deliveryService(Function<String, List<InetAddress>> guard) {
        return new WebhookDeliveryService(endpointRepository, deliveryRepository, objectMapper, tenantWork,
                (gatewayPaymentId, tenant) -> Map.of(), false, guard);
    }

    private WebhookDeliveryRetrier retrier(WebhookDeliveryService svc) {
        return new WebhookDeliveryRetrier(deliveryRepository, endpointRepository, svc, redis, tenantWork, false);
    }

    private static String hmac(String body, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    private WebhookDeliveryEntity failedRow(String body) {
        WebhookDeliveryEntity d = WebhookDeliveryEntity.pending(
                "whd_1", TENANT, "we_1", "evt_1", "payment.succeeded", body);
        // mark it FAILED + immediately due so the retrier's due-scan would pick it up.
        d.incrementAttempt();
        d.markTransientFailure(503, "down", Instant.now().minusSeconds(1));
        return d;
    }

    /**
     * Wires the repository mock for the retrier's claim-then-reload path: the scan returns {@code due}, the
     * atomic per-row claim succeeds (1 row), and the post-claim {@code findById} returns the SAME instance the
     * test asserts on (so {@code recordOutcome} mutations are observable here).
     */
    private void arrangeDue(WebhookDeliveryEntity due) {
        when(deliveryRepository.findDueForRetry(any(Instant.class), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(due));
        when(deliveryRepository.claimForRetry(eq(due.getId()), any(Instant.class), any(Instant.class),
                any(Instant.class))).thenReturn(1);
        when(deliveryRepository.findById(due.getId())).thenReturn(Optional.of(due));
    }

    // ---- C: retrier re-sends through the SSRF-safe + canonical + signed path ----

    @Test
    void retrier_resends_signedWithCurrentSecret_overCanonicalBody() throws Exception {
        String secret = "whsec_current";
        String body = "{\"id\":\"evt_1\",\"type\":\"payment.succeeded\",\"livemode\":true,"
                + "\"data\":{\"object\":{\"id\":\"pay_1\"},\"metadata\":{}}}";
        WebhookDeliveryEntity due = failedRow(body);

        WebhookEndpointEntity ep = new WebhookEndpointEntity("we_1", urlFor("/hook"), "d", secret,
                List.of("payment.succeeded"), TENANT);
        when(endpointRepository.findByIdAndTenantId("we_1", TENANT)).thenReturn(Optional.of(ep));
        arrangeDue(due);

        WebhookDeliveryService svc = deliveryService(loopbackPermittingGuard());
        retrier(svc).retryDue();

        assertThat(captures).as("the retrier POSTed once to the receiver").hasSize(1);
        Capture c = captures.get(0);
        // body byte-identical to the canonical envelope; INT-1/INT-3 fields intact.
        assertThat(c.body()).isEqualTo(body);
        JsonNode env = objectMapper.readTree(c.body());
        assertThat(env.path("type").asText()).isEqualTo("payment.succeeded");
        assertThat(env.path("livemode").isBoolean()).isTrue();
        // signature is HMAC over those exact bytes with the CURRENT secret.
        assertThat(c.headers().get("X-nexuspay-signature")).isEqualTo(hmac(c.body(), secret));
        assertThat(c.headers().get("X-nexuspay-event")).isEqualTo("payment.succeeded");
        // outcome recorded DELIVERED.
        assertThat(due.getStatus()).isEqualTo(WebhookDeliveryEntity.Status.DELIVERED);
    }

    // ---- C (IP-pin still applies): a host resolving to a private IP is refused, receiver never hit ----

    @Test
    void retrier_ssrfGuardRejectsPrivateTarget_neverHitsReceiver_marksDead() {
        String body = "{\"id\":\"evt_1\"}";
        WebhookDeliveryEntity due = failedRow(body);
        WebhookEndpointEntity ep = new WebhookEndpointEntity("we_1", urlFor("/hook"), "d", "whsec_x",
                List.of("payment.succeeded"), TENANT);
        when(endpointRepository.findByIdAndTenantId("we_1", TENANT)).thenReturn(Optional.of(ep));
        arrangeDue(due);

        // A guard that REJECTS (as the real validator would for a private/rebinding target).
        Function<String, List<InetAddress>> rejectingGuard = url -> {
            throw new WebhookUrlValidationException("resolves to a private address");
        };
        WebhookDeliveryService svc = deliveryService(rejectingGuard);
        retrier(svc).retryDue();

        assertThat(captures).as("an SSRF-rejected target must never reach the receiver").isEmpty();
        assertThat(due.getStatus())
                .as("SSRF-now-private is a PermanentFailure -> DEAD (don't re-hit on a timer)")
                .isEqualTo(WebhookDeliveryEntity.Status.DEAD);
    }

    // ---- C (redirect-disable still applies): a 3xx is refused, not followed; stays retryable ----

    @Test
    void retrier_refusesRedirect_treatsAsTransient_doesNotFollow() throws IOException {
        // A receiver that 302s to a second context; the second context must NEVER be hit.
        server.createContext("/redirector", exchange -> {
            exchange.getResponseHeaders().add("Location", urlFor("/internal"));
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        CopyOnWriteArrayList<String> internalHits = new CopyOnWriteArrayList<>();
        server.createContext("/internal", exchange -> {
            internalHits.add("hit");
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });

        String body = "{\"id\":\"evt_1\"}";
        WebhookDeliveryEntity due = failedRow(body);
        WebhookEndpointEntity ep = new WebhookEndpointEntity("we_1", urlFor("/redirector"), "d", "whsec_x",
                List.of("payment.succeeded"), TENANT);
        when(endpointRepository.findByIdAndTenantId("we_1", TENANT)).thenReturn(Optional.of(ep));
        arrangeDue(due);

        WebhookDeliveryService svc = deliveryService(loopbackPermittingGuard());
        retrier(svc).retryDue();

        assertThat(internalHits).as("a 3xx Location must NOT be followed (SSRF guard)").isEmpty();
        assertThat(due.getStatus())
                .as("a refused 3xx is transient -> stays FAILED (re-scheduled), not DEAD/DELIVERED")
                .isEqualTo(WebhookDeliveryEntity.Status.FAILED);
        assertThat(due.getAttemptCount()).isEqualTo(2); // started at 1, retrier bumped to 2
    }

    // ---- D: rotation changes the signing secret on the NEXT attempt ----

    @Test
    void rotation_nextAttemptSignsWithNewSecret_notOld() throws Exception {
        String oldSecret = "whsec_old";
        String newSecret = "whsec_new";
        assertThat(newSecret).isNotEqualTo(oldSecret);

        String body = "{\"id\":\"evt_1\"}";
        WebhookDeliveryEntity due = failedRow(body);

        // The endpoint the retrier re-loads has ALREADY been rotated to the new secret.
        WebhookEndpointEntity ep = new WebhookEndpointEntity("we_1", urlFor("/hook"), "d", oldSecret,
                List.of("payment.succeeded"), TENANT);
        ep.rotateSecret(newSecret);
        when(endpointRepository.findByIdAndTenantId("we_1", TENANT)).thenReturn(Optional.of(ep));
        arrangeDue(due);

        WebhookDeliveryService svc = deliveryService(loopbackPermittingGuard());
        retrier(svc).retryDue();

        assertThat(captures).hasSize(1);
        String sig = captures.get(0).headers().get("X-nexuspay-signature");
        assertThat(sig).as("signs with the CURRENT (rotated) secret").isEqualTo(hmac(body, newSecret));
        assertThat(sig).as("never the old secret").isNotEqualTo(hmac(body, oldSecret));
    }

    // ---- retrier: a disabled/absent endpoint stops retries (DEAD), never loops forever ----

    @Test
    void retrier_disabledEndpoint_marksDead() {
        WebhookDeliveryEntity due = failedRow("{\"id\":\"evt_1\"}");
        when(endpointRepository.findByIdAndTenantId("we_1", TENANT)).thenReturn(Optional.empty());
        arrangeDue(due);

        WebhookDeliveryService svc = deliveryService(loopbackPermittingGuard());
        retrier(svc).retryDue();

        assertThat(captures).isEmpty();
        assertThat(due.getStatus()).isEqualTo(WebhookDeliveryEntity.Status.DEAD);
    }

    // ---- BLOCKER: a STALE PENDING row (pre-outcome crash) is RE-DRIVEN by the retrier ----
    // Reproduces the lost-delivery bug: the PENDING row committed, then the process crashed BEFORE the outcome
    // was persisted. The old FAILED-only sweep skipped it forever AND a Kafka redelivery is short-circuited by
    // recordDelivery's idempotency probe, so the delivery was silently lost. The sweep now picks up stale
    // PENDING rows and the loopback receiver must be hit exactly once, ending DELIVERED.
    @Test
    void stalePendingRow_isReDriven_recoversLostDelivery() throws Exception {
        String secret = "whsec_current";
        String body = "{\"id\":\"evt_1\",\"type\":\"payment.succeeded\"}";

        // A PENDING row, never advanced past its first attempt (no outcome ever written) — i.e. a crash victim.
        WebhookDeliveryEntity stalePending = WebhookDeliveryEntity.pending(
                "whd_1", TENANT, "we_1", "evt_1", "payment.succeeded", body);
        assertThat(stalePending.getStatus()).isEqualTo(WebhookDeliveryEntity.Status.PENDING);

        WebhookEndpointEntity ep = new WebhookEndpointEntity("we_1", urlFor("/hook"), "d", secret,
                List.of("payment.succeeded"), TENANT);
        when(endpointRepository.findByIdAndTenantId("we_1", TENANT)).thenReturn(Optional.of(ep));
        // The recovery sweep surfaces the stale PENDING row; the claim flips it FAILED and the reload returns it.
        arrangeDue(stalePending);

        WebhookDeliveryService svc = deliveryService(loopbackPermittingGuard());
        retrier(svc).retryDue();

        assertThat(captures).as("the stale PENDING delivery is re-driven exactly once").hasSize(1);
        assertThat(captures.get(0).headers().get("X-nexuspay-signature")).isEqualTo(hmac(body, secret));
        assertThat(stalePending.getStatus())
                .as("a recovered stale PENDING delivery completes DELIVERED, not stuck PENDING")
                .isEqualTo(WebhookDeliveryEntity.Status.DELIVERED);
    }

    // ---- SHOULD_FIX: a row that LOST the per-row claim race is NOT re-sent (no double-send) ----
    // Simulates a mid-batch leader-lock expiry: a second leader loaded the SAME due row, but the atomic claim
    // returns 0 (another leader already claimed/advanced it). The receiver must NEVER be hit and the endpoint
    // must never even be loaded — proving the claim, not just the leader lock, blocks double-SEND.
    @Test
    void lostClaimRace_doesNotReSend() {
        WebhookDeliveryEntity due = failedRow("{\"id\":\"evt_1\"}");
        when(deliveryRepository.findDueForRetry(any(Instant.class), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(due));
        // The atomic claim loses the race -> 0 rows updated.
        when(deliveryRepository.claimForRetry(eq(due.getId()), any(Instant.class), any(Instant.class),
                any(Instant.class))).thenReturn(0);

        WebhookDeliveryService svc = deliveryService(loopbackPermittingGuard());
        retrier(svc).retryDue();

        assertThat(captures).as("a lost claim must never re-send").isEmpty();
        verify(endpointRepository, never()).findByIdAndTenantId(anyString(), anyString());
        // the row's status is untouched by this leader (still FAILED, owned by the winner).
        assertThat(due.getStatus()).isEqualTo(WebhookDeliveryEntity.Status.FAILED);
    }

    // ---- F: a DELIVERED row is not auto-re-sent by the consumer ----

    @Test
    void deliveredRow_isNotReSent_byConsumerRecordDelivery() {
        String body = "{\"id\":\"evt_1\"}";
        WebhookEndpointEntity ep = new WebhookEndpointEntity("we_1", urlFor("/hook"), "d", "whsec_x",
                List.of("payment.succeeded"), TENANT);

        // A prior DELIVERED row already exists for (we_1, evt_1).
        WebhookDeliveryEntity delivered = WebhookDeliveryEntity.pending(
                "whd_1", TENANT, "we_1", "evt_1", "payment.succeeded", body);
        delivered.markDelivered(200);
        when(deliveryRepository.findByEndpointIdAndEventId("we_1", "evt_1"))
                .thenReturn(Optional.of(delivered));

        WebhookDeliveryService svc = deliveryService(loopbackPermittingGuard());
        WebhookDeliveryEntity result = svc.recordDelivery(TENANT, ep, "evt_1", "payment.succeeded", body);

        assertThat(result).as("an existing (delivered) row -> null, so the consumer does NOT re-attempt").isNull();
        assertThat(captures).as("no POST is made for an already-recorded event").isEmpty();
    }
}
