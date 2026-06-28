package io.nexuspay.gateway.adapter.out.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.nexuspay.common.event.EventTypes;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TEST-4a (D1): pins the {@link TestEventOutboxAdapter} native INSERT — the DB-free precedent is
 * {@code DisputeOutboxAdapterTest}. This proves the invariants the controller test CANNOT prove (it mocks
 * this adapter): that a row is actually INSERTed into {@code event_outbox} with the billing-mirror column
 * set + {@code jsonb} cast, that the serialized envelope carries {@code metadata.__livemode='false'} (the
 * livemode=false guarantee lives HERE, not in the controller's literal arg), that {@code tenant_id} binds
 * to the CALLER (never "default"), and that the dotted→internal event type + aggregate type land in the
 * row. Mocks EntityManager + Query, reflectively injects the {@code @PersistenceContext} field, captures
 * the bound payload param and parses the envelope JSON.
 *
 * <p>L-071: {@code new ObjectMapper().findAndRegisterModules()} picks up JavaTimeModule so the
 * {@code EventEnvelope.timestamp} Instant serializes (a bare ObjectMapper would throw before any row is
 * written). The server-minted {@code evt_*} id is never asserted literally — only its prefix / non-blank.</p>
 */
class TestEventOutboxAdapterTest {

    private static final String TENANT = "tenant-X";

    private EntityManager entityManager;
    private Query query;
    // findAndRegisterModules() registers JavaTimeModule (jsr310, transitive via spring-boot-starter-web) so
    // the EventEnvelope's Instant timestamp serializes exactly as under the Spring-configured ObjectMapper in
    // production. A bare new ObjectMapper() cannot serialize Instant -> writeValueAsString would throw.
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private TestEventOutboxAdapter adapter;

    @BeforeEach
    void setUp() throws Exception {
        entityManager = mock(EntityManager.class);
        query = mock(Query.class);
        when(entityManager.createNativeQuery(anyString())).thenReturn(query);
        // Fluent setParameter chain returns the same query.
        when(query.setParameter(anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(query);

        adapter = new TestEventOutboxAdapter(objectMapper);
        // Inject the mocked @PersistenceContext EntityManager (field injection in production).
        Field em = TestEventOutboxAdapter.class.getDeclaredField("entityManager");
        em.setAccessible(true);
        em.set(adapter, entityManager);
    }

    private Map<String, Object> paymentObject(String paymentId) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("payment_id", paymentId);
        o.put("amount", 1000L);
        o.put("currency", "USD");
        o.put("status", "succeeded");
        return o;
    }

    @Test
    void synthesize_insertsIntoEventOutbox_withBillingMirrorShape_underCallerTenant() {
        String eventId = adapter.synthesize(
                TENANT, EventTypes.PAYMENT_CAPTURED, EventTypes.AGGREGATE_PAYMENT,
                "pay_test_1", paymentObject("pay_test_1"), false);

        // L-071: never assert the literal server-minted id — only prefix/non-blank.
        assertThat(eventId).startsWith("evt_").isNotBlank();

        // 1) the native INSERT targets event_outbox with the SAME column set + jsonb cast as billing/dispute.
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(entityManager).createNativeQuery(sql.capture());
        String insert = sql.getValue();
        assertThat(insert).contains("INSERT INTO event_outbox");
        assertThat(insert).contains(
                "aggregate_type, aggregate_id, event_type, payload, " +
                "created_at, tenant_id, routing_key, event_version"); // exact column list (billing-mirror)
        assertThat(insert).contains("CAST(:payload AS jsonb)");

        // 2) the bound params carry the Payment aggregate, the internal event type, the CALLER tenant
        //    (NOT "default"), and the lower-cased routing key.
        verify(query).setParameter("aggregateType", EventTypes.AGGREGATE_PAYMENT);
        verify(query).setParameter("aggregateId", "pay_test_1");
        verify(query).setParameter("eventType", EventTypes.PAYMENT_CAPTURED);
        verify(query).setParameter("tenantId", TENANT);
        verify(query).setParameter("routingKey", "payment");
        verify(query).setParameter("eventVersion", 1);
        verify(query).executeUpdate();
    }

    @Test
    void synthesize_payloadEnvelope_carriesCallerTenantAndLivemodeFalse() throws Exception {
        adapter.synthesize(
                TENANT, EventTypes.PAYMENT_CAPTURED, EventTypes.AGGREGATE_PAYMENT,
                "pay_test_1", paymentObject("pay_test_1"), false);

        // Capture the serialized envelope written into the payload column.
        ArgumentCaptor<Object> value = ArgumentCaptor.forClass(Object.class);
        verify(query).setParameter(eq("payload"), value.capture());
        JsonNode envelope = objectMapper.readTree(value.getValue().toString());

        assertThat(envelope.path("aggregate_type").asText()).isEqualTo(EventTypes.AGGREGATE_PAYMENT);
        assertThat(envelope.path("aggregate_id").asText()).isEqualTo("pay_test_1");
        assertThat(envelope.path("event_type").asText()).isEqualTo(EventTypes.PAYMENT_CAPTURED);
        // SEC-24: the envelope metadata carries the CALLER tenant, never "default".
        assertThat(envelope.path("metadata").path("tenant_id").asText()).isEqualTo(TENANT);
        // TEST-4a: the reserved mode flag rides on the envelope metadata, stamped "false" so the delivered
        // webhook is unambiguously a TEST event (WebhookEnvelopeSerializer lifts it to top-level livemode).
        assertThat(envelope.path("metadata").path("__livemode").asText()).isEqualTo("false");
        // the synthesized data.object is the envelope payload.
        assertThat(envelope.path("payload").path("payment_id").asText()).isEqualTo("pay_test_1");
        assertThat(envelope.path("payload").path("amount").asLong()).isEqualTo(1000L);
    }

    @Test
    void synthesize_nullTenant_fallsBackToDefault() {
        adapter.synthesize(
                null, EventTypes.PAYMENT_CAPTURED, EventTypes.AGGREGATE_PAYMENT,
                "pay_test_1", paymentObject("pay_test_1"), false);
        // mirrors billing/dispute: a null tenant degrades to "default" (a tenant matching no real endpoint).
        verify(query).setParameter("tenantId", "default");
    }
}
