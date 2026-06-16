package io.nexuspay.gateway.adapter.out.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.nexuspay.common.rls.TenantWorkRunner;
import io.nexuspay.gateway.adapter.out.persistence.JpaWebhookDeliveryRepository;
import io.nexuspay.gateway.adapter.out.persistence.JpaWebhookEndpointRepository;
import io.nexuspay.gateway.adapter.out.persistence.WebhookDeliveryEntity;
import io.nexuspay.gateway.adapter.out.persistence.WebhookEndpointEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * INT-4 (L-041 backstop): {@code WebhookDeliveryService.recordDelivery}'s second idempotency layer — the
 * {@code saveAndFlush} dup-key CATCH branch — is the concurrent-recorder race backstop, distinct from the
 * {@code findByEndpointIdAndEventId} fast-path (test F) and from the IT that calls {@code saveAndFlush}
 * directly (test G, which never exercises the SERVICE swallowing the violation). This test drives the catch:
 *
 * <ul>
 *   <li>a {@link DataIntegrityViolationException} naming {@code uq_webhook_deliveries_endpoint_event} ->
 *       swallowed into a {@code null} no-op (the concurrent recorder won the race);</li>
 *   <li>a {@link DuplicateKeyException} (SQLSTATE 23505, no message) -> swallowed into {@code null};</li>
 *   <li>an UNRELATED {@link DataIntegrityViolationException} (e.g. an FK violation, no constraint name) ->
 *       PROPAGATED, proving {@code isDeliveryIdemViolation} does NOT over-swallow.</li>
 * </ul>
 *
 * <p>A regression that returned {@code null} for ANY {@code DataIntegrityViolationException}, or that
 * re-threw the idempotency violation, fails one of these three assertions.</p>
 */
class WebhookDeliveryRecordIdempotencyTest {

    private static final String TENANT = "t1";
    private static final String IDEM_CONSTRAINT = "uq_webhook_deliveries_endpoint_event";

    private JpaWebhookDeliveryRepository deliveries;
    private WebhookDeliveryService service;
    private WebhookEndpointEntity endpoint;

    @BeforeEach
    void setUp() {
        JpaWebhookEndpointRepository endpoints = mock(JpaWebhookEndpointRepository.class);
        deliveries = mock(JpaWebhookDeliveryRepository.class);
        TenantWorkRunner tenantWork = mock(TenantWorkRunner.class);
        Function<String, List<InetAddress>> guard = url -> List.of(InetAddress.getLoopbackAddress());
        service = new WebhookDeliveryService(endpoints, deliveries, new ObjectMapper(), tenantWork,
                (gatewayPaymentId, tenant) -> Map.of(), false, guard);
        endpoint = new WebhookEndpointEntity("we_1", "https://example.com/hook", "d", "whsec_x",
                List.of("payment.succeeded"), TENANT);
        // The fast-path probe finds NO existing row, so recordDelivery proceeds to saveAndFlush (layer b).
        when(deliveries.findByEndpointIdAndEventId(anyString(), anyString())).thenReturn(Optional.empty());
    }

    private WebhookDeliveryEntity record() {
        return service.recordDelivery(TENANT, endpoint, "evt_1", "payment.succeeded", "{\"id\":\"evt_1\"}");
    }

    @Test
    void concurrentRecorderUniqueViolation_byConstraintName_isSwallowedToNull() {
        // A vendor-style DataIntegrityViolationException whose cause chain names OUR unique index.
        var cause = new RuntimeException("ERROR: duplicate key value violates unique constraint \""
                + IDEM_CONSTRAINT + "\"");
        when(deliveries.saveAndFlush(any(WebhookDeliveryEntity.class)))
                .thenThrow(new DataIntegrityViolationException("could not execute statement", cause));

        assertThat(record())
                .as("a concurrent recorder won the (endpoint,event) race -> null no-op, no double-attempt")
                .isNull();
    }

    @Test
    void concurrentRecorderUniqueViolation_asDuplicateKeyException_isSwallowedToNull() {
        // Spring maps a 23505 to DuplicateKeyException even without our constraint name in the message.
        when(deliveries.saveAndFlush(any(WebhookDeliveryEntity.class)))
                .thenThrow(new DuplicateKeyException("duplicate key"));

        assertThat(record())
                .as("a DuplicateKeyException (23505) is the idempotency backstop -> null no-op")
                .isNull();
    }

    @Test
    void unrelatedIntegrityViolation_isPropagated_notSwallowed() {
        // An FK violation (e.g. endpoint_id references a missing endpoint) — NOT our idempotency constraint
        // and NOT a DuplicateKeyException. It must propagate, never be mistaken for a dup no-op.
        var cause = new RuntimeException("ERROR: insert or update on table \"webhook_deliveries\" violates "
                + "foreign key constraint \"fk_webhook_delivery_endpoint\"");
        when(deliveries.saveAndFlush(any(WebhookDeliveryEntity.class)))
                .thenThrow(new DataIntegrityViolationException("could not execute statement", cause));

        assertThatThrownBy(this::record)
                .as("an unrelated integrity error must propagate, not be over-swallowed into a null no-op")
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
