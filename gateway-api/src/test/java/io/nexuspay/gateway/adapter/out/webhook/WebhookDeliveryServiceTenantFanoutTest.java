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

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SEC-09 (B-009): the webhook consumer must fan a payment event out ONLY to endpoints owned by the
 * event's tenant — even with {@code rls.enforce=false} (the default config). Before this fix the
 * dormant path called {@code findAllByEnabledTrue()} and delivered EVERY tenant's payment event to
 * EVERY tenant's endpoint.
 *
 * <p>This is the exact regression test: an event stamped {@code tenant_id="tenant-A"} must POST ONLY to
 * tenant-A's endpoint and NEVER to tenant-B's. On the vulnerable {@code findAllByEnabledTrue()} code
 * (returning both tenants' endpoints) tenant-B would also receive a delivery and the
 * {@code captures}-size / never-lookup-B assertions FAIL.</p>
 *
 * <p>Reuses the package-private seam constructor + loopback-permitting guard pattern from
 * {@link WebhookDeliveryServiceTest}: the guard relaxes the public/https policy for the in-process
 * loopback receiver while still resolving real addresses (so the production IP-pin path runs);
 * {@code rlsEnforced=false} exercises the default config.</p>
 */
class WebhookDeliveryServiceTenantFanoutTest {

    private JpaWebhookEndpointRepository repository;
    private TenantWorkRunner tenantWork;
    private WebhookDeliveryService service;

    private HttpServer server;
    private final CopyOnWriteArrayList<String> deliveredPaths = new CopyOnWriteArrayList<>();

    private static final String TENANT_A = "tenant-A";
    private static final String TENANT_B = "tenant-B";

    @BeforeEach
    void setUp() throws IOException {
        repository = mock(JpaWebhookEndpointRepository.class);
        tenantWork = mock(TenantWorkRunner.class);
        ObjectMapper objectMapper = new ObjectMapper();
        service = new WebhookDeliveryService(repository, objectMapper, tenantWork, false,
                loopbackPermittingGuard());

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            deliveredPaths.add(exchange.getRequestURI().getPath());
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

    private WebhookEndpointEntity endpoint(String id, String path, String tenantId) {
        return new WebhookEndpointEntity(id, urlFor(path), "desc", "secret",
                List.of("payment.succeeded"), tenantId);
    }

    private ConsumerRecord<String, String> recordForTenant(String tenant, String eventType, String payload) {
        var rec = new ConsumerRecord<>(Topics.PAYMENTS, 0, 0L, "k", payload);
        rec.headers().add(new RecordHeader("event_type", eventType.getBytes(StandardCharsets.UTF_8)));
        rec.headers().add(new RecordHeader("tenant_id", tenant.getBytes(StandardCharsets.UTF_8)));
        return rec;
    }

    @Test
    void event_deliversOnlyToOwningTenantEndpoints_notOtherTenants() {
        // Each tenant has its OWN endpoint. A tenant-A event must reach ONLY /a.
        when(repository.findAllByTenantIdAndEnabledTrue(TENANT_A))
                .thenReturn(List.of(endpoint("we_a", "/a", TENANT_A)));
        // tenant-B's endpoint exists but must NEVER be looked up or delivered to for a tenant-A event.
        lenient().when(repository.findAllByTenantIdAndEnabledTrue(TENANT_B))
                .thenReturn(List.of(endpoint("we_b", "/b", TENANT_B)));

        service.onPaymentEvent(recordForTenant(TENANT_A, "payment.succeeded", "{\"x\":1}"));

        assertThat(deliveredPaths)
                .as("a tenant-A event must be delivered ONLY to tenant-A's endpoint")
                .containsExactly("/a");
        assertThat(deliveredPaths)
                .as("tenant-B's endpoint must NOT receive tenant-A's event (cross-tenant fan-out closed)")
                .doesNotContain("/b");
        // The consumer scoped the lookup to tenant-A and never queried tenant-B's endpoints.
        verify(repository).findAllByTenantIdAndEnabledTrue(TENANT_A);
        verify(repository, never()).findAllByTenantIdAndEnabledTrue(TENANT_B);
        verify(repository, never()).findAllByEnabledTrue();
    }

    /**
     * SEC-batch-1b: the tenant is resolved ONLY from the relay-stamped, server-trusted {@code tenant_id}
     * header. The (lowest-trust) event JSON body is NEVER consulted. So when the header is ABSENT, a
     * top-level {@code metadata.tenant_id} in the body MUST NOT route the event to that tenant's
     * endpoints — it falls back to "default", which matches no real-tenant endpoint, and nothing is
     * delivered. On the vulnerable body-fallback code, the payload's {@code tenant_id="tenant-B"} would
     * resolve to tenant-B and POST to /b — a cross-tenant fan-out driven by untrusted input.
     */
    @Test
    void headerAbsent_payloadTenantIsIgnored_noCrossTenantFanout() {
        // Body claims tenant-B; tenant-B's endpoint exists and would receive the delivery if the body
        // were trusted. It must NOT be: with no header, resolution falls back to "default".
        lenient().when(repository.findAllByTenantIdAndEnabledTrue(TENANT_B))
                .thenReturn(List.of(endpoint("we_b", "/b", TENANT_B)));
        lenient().when(repository.findAllByTenantIdAndEnabledTrue("default"))
                .thenReturn(java.util.Collections.emptyList());

        // event_type header present (so we get past the early return) but NO tenant_id header.
        var rec = new ConsumerRecord<>(Topics.PAYMENTS, 0, 0L, "k",
                "{\"metadata\":{\"tenant_id\":\"" + TENANT_B + "\"}}");
        rec.headers().add(new RecordHeader("event_type", "payment.succeeded".getBytes(StandardCharsets.UTF_8)));

        service.onPaymentEvent(rec);

        assertThat(deliveredPaths)
                .as("a body-claimed tenant must NOT drive fan-out when the trusted header is absent")
                .isEmpty();
        // The consumer resolved to "default" (the safe fallback), never to the body's claimed tenant.
        verify(repository).findAllByTenantIdAndEnabledTrue("default");
        verify(repository, never()).findAllByTenantIdAndEnabledTrue(TENANT_B);
    }

    /** SEC-14 seam (copied from WebhookDeliveryServiceTest): resolve real addresses, relax public/https. */
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
}
