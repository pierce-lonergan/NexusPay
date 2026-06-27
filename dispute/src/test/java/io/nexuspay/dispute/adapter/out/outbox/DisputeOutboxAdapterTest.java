package io.nexuspay.dispute.adapter.out.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TEST-2: pins the {@link DisputeOutboxAdapter} native INSERT shape — the SAME columns / {@code jsonb}
 * cast / routing key as {@code BillingOutboxAdapter} (so the dispute module rides the shared outbox
 * pipeline without importing the payment-orchestration {@code OutboxEvent} entity), and that the
 * envelope it writes carries the dispute TENANT (never "default") plus the {@code __livemode} mode flag.
 */
class DisputeOutboxAdapterTest {

    private static final String TENANT = "tenant-X";

    private EntityManager entityManager;
    private Query query;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private DisputeOutboxAdapter adapter;

    @BeforeEach
    void setUp() throws Exception {
        entityManager = mock(EntityManager.class);
        query = mock(Query.class);
        when(entityManager.createNativeQuery(anyString())).thenReturn(query);
        // Fluent setParameter chain returns the same query.
        when(query.setParameter(anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(query);

        adapter = new DisputeOutboxAdapter(objectMapper);
        // Inject the mocked @PersistenceContext EntityManager (field injection in production).
        Field em = DisputeOutboxAdapter.class.getDeclaredField("entityManager");
        em.setAccessible(true);
        em.set(adapter, entityManager);
    }

    private Map<String, Object> disputeObject() {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("dispute_id", "dp_1");
        o.put("payment_id", "pay_test_1");
        o.put("amount", 5000L);
        o.put("currency", "USD");
        o.put("status", "OPENED");
        return o;
    }

    @Test
    void publishEvent_insertsIntoEventOutbox_withBillingMirrorShape() {
        adapter.publishEvent("Dispute", "dp_1", "DisputeCreated", disputeObject(), TENANT);

        // 1) the native INSERT targets event_outbox with the SAME column set + jsonb cast as billing.
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(entityManager).createNativeQuery(sql.capture());
        String insert = sql.getValue();
        assertThat(insert).contains("INSERT INTO event_outbox");
        assertThat(insert).contains(
                "aggregate_type, aggregate_id, event_type, payload, created_at, tenant_id, routing_key, event_version"
                        .replace(", ", ", ")); // exact column list (billing-mirror)
        assertThat(insert).contains("CAST(:payload AS jsonb)");

        // 2) the bound params carry the Dispute aggregate, the trusted tenant (NOT default), and the
        //    lower-cased routing key.
        verify(query).setParameter("aggregateType", "Dispute");
        verify(query).setParameter("aggregateId", "dp_1");
        verify(query).setParameter("eventType", "DisputeCreated");
        verify(query).setParameter("tenantId", TENANT);
        verify(query).setParameter("routingKey", "dispute");
        verify(query).setParameter("eventVersion", 1);
        verify(query).executeUpdate();
    }

    @Test
    void publishEvent_payloadEnvelope_carriesTenantAndLivemode() throws Exception {
        adapter.publishEvent("Dispute", "dp_1", "DisputeCreated", disputeObject(), TENANT, false);

        // Capture the serialized envelope written into the payload column.
        ArgumentCaptor<Object> value = ArgumentCaptor.forClass(Object.class);
        verify(query).setParameter(eq("payload"), value.capture());
        JsonNode envelope = objectMapper.readTree(value.getValue().toString());

        assertThat(envelope.path("aggregate_type").asText()).isEqualTo("Dispute");
        assertThat(envelope.path("aggregate_id").asText()).isEqualTo("dp_1");
        assertThat(envelope.path("event_type").asText()).isEqualTo("DisputeCreated");
        // SEC-24: the envelope metadata carries the dispute tenant, never "default".
        assertThat(envelope.path("metadata").path("tenant_id").asText()).isEqualTo(TENANT);
        // TEST-2: the reserved mode flag rides on the envelope metadata (test -> "false").
        assertThat(envelope.path("metadata").path("__livemode").asText()).isEqualTo("false");
        // the dispute data.object is the payload.
        assertThat(envelope.path("payload").path("dispute_id").asText()).isEqualTo("dp_1");
        assertThat(envelope.path("payload").path("payment_id").asText()).isEqualTo("pay_test_1");
    }

    @Test
    void publishEvent_baseOverload_defaultsLivemodeTrue() throws Exception {
        adapter.publishEvent("Dispute", "dp_1", "DisputeWon", disputeObject(), TENANT);

        ArgumentCaptor<Object> value = ArgumentCaptor.forClass(Object.class);
        verify(query).setParameter(eq("payload"), value.capture());
        JsonNode envelope = objectMapper.readTree(value.getValue().toString());
        // a real chargeback transition -> livemode true.
        assertThat(envelope.path("metadata").path("__livemode").asText()).isEqualTo("true");
    }

    @Test
    void publishEvent_nullTenant_fallsBackToDefault() {
        adapter.publishEvent("Dispute", "dp_1", "DisputeCreated", disputeObject(), null);
        // mirrors billing: a null tenant degrades to "default" (a tenant that matches no real endpoint).
        verify(query).setParameter("tenantId", "default");
    }
}
