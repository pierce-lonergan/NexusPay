package io.nexuspay.payment.application.webhook;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.nexuspay.payment.adapter.out.persistence.PaymentWebhookMetadataEntity;
import io.nexuspay.payment.adapter.out.persistence.PaymentWebhookMetadataRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * INT-1: unit tests for {@link WebhookMetadataService}. Each test fails if the corresponding guardrail is
 * weakened — the PAN/forbidden-key strip, the size/key cap, the idempotent write, the fail-safe persist,
 * and the server-derived tenant stamping.
 */
class WebhookMetadataServiceTest {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private PaymentWebhookMetadataRepository repository;
    private ObjectMapper objectMapper;
    private WebhookMetadataService service;

    @BeforeEach
    void setUp() {
        repository = mock(PaymentWebhookMetadataRepository.class);
        objectMapper = new ObjectMapper();
        service = new WebhookMetadataService(repository, objectMapper);
        when(repository.existsById(anyString())).thenReturn(false);
    }

    private PaymentWebhookMetadataEntity captureSaved() {
        ArgumentCaptor<PaymentWebhookMetadataEntity> captor =
                ArgumentCaptor.forClass(PaymentWebhookMetadataEntity.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void recordsServerDerivedTenant_andReadsBack() throws Exception {
        service.record("pay_1", "tenant-A", Map.of("userId", "u_42", "packId", "gold"));

        PaymentWebhookMetadataEntity saved = captureSaved();
        assertThat(saved.getGatewayPaymentId()).isEqualTo("pay_1");
        assertThat(saved.getTenantId()).as("the stored tenant is the server-derived trusted one")
                .isEqualTo("tenant-A");
        Map<String, Object> parsed = objectMapper.readValue(saved.getMetadataJson(), MAP_TYPE);
        assertThat(parsed).as("stored json round-trips the correlation keys")
                .containsEntry("userId", "u_42").containsEntry("packId", "gold");

        // read path — tenant-scoped at the app layer: the matching tenant gets the row back.
        when(repository.findById("pay_1")).thenReturn(Optional.of(saved));
        assertThat(service.find("pay_1", "tenant-A"))
                .containsEntry("userId", "u_42").containsEntry("packId", "gold");
    }

    @Test
    void find_returnsEmptyMap_whenAbsent() {
        when(repository.findById("missing")).thenReturn(Optional.empty());
        assertThat(service.find("missing", "tenant-A")).isEmpty();
    }

    @Test
    void find_returnsEmptyMap_whenRowOwnedByAnotherTenant_appLevelIsolation() throws Exception {
        // SEC (INT-1): the row is stored under tenant-A; a tenant-B-scoped read must NOT see it, even with
        // RLS dormant. This is the app-level defense the SHOULD_FIX requires — isolation independent of the
        // rls.enforce flag. On the bare-PK read (no tenant check) this would leak tenant-A's correlation map.
        PaymentWebhookMetadataEntity rowA = new PaymentWebhookMetadataEntity(
                "pay_x", "tenant-A", objectMapper.writeValueAsString(Map.of("userId", "u_42")), Instant.now());
        when(repository.findById("pay_x")).thenReturn(Optional.of(rowA));

        assertThat(service.find("pay_x", "tenant-B"))
                .as("a tenant-B read of a tenant-A row must return {} (app-level cross-tenant guard)")
                .isEmpty();
        assertThat(service.find("pay_x", "tenant-A"))
                .as("the owning tenant still reads its own row")
                .containsEntry("userId", "u_42");
    }

    @Test
    void find_returnsEmptyMap_whenStoredTenantIsNull_cannotProveOwnership() throws Exception {
        // A null stored tenant cannot prove ownership → fail to {} (mirrors assertOwnedBy's null-tenant case).
        PaymentWebhookMetadataEntity rowNull = new PaymentWebhookMetadataEntity(
                "pay_n", null, objectMapper.writeValueAsString(Map.of("userId", "u_42")), Instant.now());
        when(repository.findById("pay_n")).thenReturn(Optional.of(rowNull));

        assertThat(service.find("pay_n", "tenant-A")).isEmpty();
    }

    @Test
    void record_isIdempotent_whenRowExists() {
        when(repository.existsById("pay_1")).thenReturn(true);

        service.record("pay_1", "tenant-A", Map.of("userId", "different"));

        verify(repository, never()).save(any());
    }

    @Test
    void panAndCardKeys_areNeverStored() throws Exception {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("userId", "u_42");
        meta.put("payment_method_data", Map.of("number", "4111111111111111"));
        meta.put("card", Map.of("last4", "1111"));
        meta.put("cvv", "123");

        service.record("pay_1", "tenant-A", meta);

        Map<String, Object> parsed = objectMapper.readValue(captureSaved().getMetadataJson(), MAP_TYPE);
        // INT-3: the stored map carries the correlation keys PLUS the server-reserved __livemode flag
        // (default true via the 3-arg record). The forbidden PAN/card material is still never stored, and
        // the serializer strips __livemode before delivery so the merchant never sees it.
        assertThat(parsed).containsOnlyKeys("userId", "__livemode");
        assertThat(parsed).containsEntry("__livemode", true);
        assertThat(captureSaved().getMetadataJson())
                .doesNotContain("4111111111111111")
                .doesNotContain("payment_method_data")
                .doesNotContain("card")
                .doesNotContain("cvv");
    }

    @Test
    void authorityMarkers_areStripped() throws Exception {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("userId", "u_42");
        meta.put("source", "client");
        meta.put("workflow", "wf");
        meta.put("tenant_id", "tenant-EVIL");

        service.record("pay_1", "tenant-A", meta);

        Map<String, Object> parsed = objectMapper.readValue(captureSaved().getMetadataJson(), MAP_TYPE);
        // INT-3: authority markers still stripped; the only added key is the server-reserved __livemode.
        assertThat(parsed).containsOnlyKeys("userId", "__livemode");
    }

    @Test
    void clientSuppliedLivemode_isStripped_andServerValueStamped() throws Exception {
        // INT-3 SEC: a client-echoed __livemode must NEVER survive — sanitize() drops it (it is FORBIDDEN),
        // then record(...) re-stamps the true SERVER-derived value (here true via the 3-arg overload).
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("userId", "u_42");
        meta.put("__livemode", false); // forged client value claiming "test"

        service.record("pay_1", "tenant-A", meta);

        Map<String, Object> parsed = objectMapper.readValue(captureSaved().getMetadataJson(), MAP_TYPE);
        assertThat(parsed).containsEntry("__livemode", true); // server value wins, not the forged false
        assertThat(parsed).containsEntry("userId", "u_42");
    }

    @Test
    void record4Arg_stampsServerDerivedLivemodeFalse_forTestPayment() throws Exception {
        // INT-3: the TEST path stamps __livemode=false via the 4-arg overload.
        service.record("pay_1", "tenant-A", Map.of("userId", "u_42"), false);

        Map<String, Object> parsed = objectMapper.readValue(captureSaved().getMetadataJson(), MAP_TYPE);
        assertThat(parsed).containsEntry("__livemode", false).containsEntry("userId", "u_42");
    }

    @Test
    void nestedForbiddenKeys_areStrippedAtAnyDepth() throws Exception {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("note", "ok");
        nested.put("card", Map.of("number", "4111111111111111"));
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("detail", nested);

        service.record("pay_1", "tenant-A", meta);

        assertThat(captureSaved().getMetadataJson()).doesNotContain("4111111111111111");
    }

    @Test
    void tooManyKeys_storesEmpty() throws Exception {
        Map<String, Object> meta = new LinkedHashMap<>();
        for (int i = 0; i < 51; i++) {
            meta.put("k" + i, "v" + i);
        }

        service.record("pay_1", "tenant-A", meta);

        Map<String, Object> parsed = objectMapper.readValue(captureSaved().getMetadataJson(), MAP_TYPE);
        // INT-3: the over-cap correlation map is dropped, but the server-reserved __livemode still survives
        // (stamped AFTER the cap decision) so the delivered envelope's livemode stays server-sourced.
        assertThat(parsed).containsOnlyKeys("__livemode");
    }

    @Test
    void oversizedSerialized_storesEmpty() throws Exception {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("big", "x".repeat(5000)); // > 4096 bytes serialized

        service.record("pay_1", "tenant-A", meta);

        Map<String, Object> parsed = objectMapper.readValue(captureSaved().getMetadataJson(), MAP_TYPE);
        // INT-3: correlation map dropped; only the server-reserved __livemode remains.
        assertThat(parsed).containsOnlyKeys("__livemode");
    }

    @Test
    void persistFailure_isSwallowed_doesNotThrow() {
        when(repository.save(any())).thenThrow(new RuntimeException("db down"));

        assertThatCode(() -> service.record("pay_1", "tenant-A", Map.of("userId", "u_42")))
                .as("a persist failure must never fail an already-authorized payment")
                .doesNotThrowAnyException();
    }

    @Test
    void blankPaymentId_isNoOp() {
        service.record("  ", "tenant-A", Map.of("userId", "u_42"));
        verify(repository, never()).save(any());
    }
}
