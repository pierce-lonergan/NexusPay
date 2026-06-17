package io.nexuspay.dispute.application.service;

import io.nexuspay.common.exception.ResourceNotFoundException;
import io.nexuspay.dispute.application.port.out.DisputeRepository;
import io.nexuspay.dispute.application.port.out.EvidenceStoragePort;
import io.nexuspay.dispute.application.port.out.LedgerPort;
import io.nexuspay.dispute.domain.Dispute;
import io.nexuspay.dispute.domain.DisputeEvent;
import io.nexuspay.dispute.domain.DisputeEvidenceType;
import io.nexuspay.dispute.domain.DisputeState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SEC-27 tests for {@link DisputeLifecycleService}: the tenant-scoped by-id teeth that stop a
 * tenant-A caller from reading/mutating a tenant-B dispute through the REST controller.
 *
 * <p>Every REST-facing read/mutation resolves the dispute via the tenant-scoped finder
 * ({@code findByIdAndTenantId} / {@code findEventsByDisputeIdAndTenantId}) and 404s
 * ({@link ResourceNotFoundException}, via {@code TenantOwnership.require}) on a foreign/absent id —
 * never falling back to the unscoped {@code findById}. The unscoped lifecycle transitions used by the
 * SEC-2-hardened webhook ({@code win}/{@code lose}/{@code expire}/{@code submitEvidence(id, actor)})
 * are deliberately left server-authoritative and are NOT exercised here.</p>
 */
class DisputeLifecycleServiceTenantScopingTest {

    private static final String TENANT = "t1";
    private static final String ATTACKER = "attacker";

    private DisputeRepository disputeRepo;
    private EvidenceStoragePort evidenceStorage;
    private LedgerPort ledger;
    private DisputeLifecycleService svc;

    @BeforeEach
    void setUp() {
        disputeRepo = mock(DisputeRepository.class);
        evidenceStorage = mock(EvidenceStoragePort.class);
        ledger = mock(LedgerPort.class);
        svc = new DisputeLifecycleService(disputeRepo, evidenceStorage, ledger);
        when(disputeRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(disputeRepo.saveEvidence(any())).thenAnswer(i -> i.getArgument(0));
    }

    /** A loaded, non-terminal dispute owned by {@link #TENANT} in a submit-able state. */
    private Dispute ownedDispute() {
        Dispute d = new Dispute();
        d.setId("dsp_1");
        d.setTenantId(TENANT);
        d.setPaymentId("pay_1");
        d.setExternalDisputeId("ext_1");
        d.setReasonCode("10.4");
        d.setAmount(5000L);
        d.setCurrency("USD");
        d.setStatus(DisputeState.EVIDENCE_NEEDED);
        d.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        d.setUpdatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        return d;
    }

    private static InputStream content() {
        return new ByteArrayInputStream("evidence".getBytes(StandardCharsets.UTF_8));
    }

    // ---- findById (GET /v1/disputes/{id}) ----

    @Test
    void findByIdScopesToTenant_andDoesNotUseUnscopedLookup() {
        Dispute d = ownedDispute();
        when(disputeRepo.findByIdAndTenantId("dsp_1", TENANT)).thenReturn(Optional.of(d));

        assertThat(svc.findById("dsp_1", TENANT)).contains(d);

        verify(disputeRepo).findByIdAndTenantId("dsp_1", TENANT);
        verify(disputeRepo, never()).findById(anyString());
    }

    @Test
    void findByIdForeignTenantReturnsEmpty_noOracle() {
        // A real dispute exists, but it belongs to another tenant: the scoped finder returns empty,
        // so the controller 404s identically to a truly-absent id (no cross-tenant existence oracle).
        when(disputeRepo.findByIdAndTenantId("dsp_victim", ATTACKER)).thenReturn(Optional.empty());

        assertThat(svc.findById("dsp_victim", ATTACKER)).isEmpty();

        verify(disputeRepo).findByIdAndTenantId("dsp_victim", ATTACKER);
        verify(disputeRepo, never()).findById(anyString());
    }

    // ---- submitEvidence (POST /v1/disputes/{id}/submit) ----

    @Test
    void submitEvidenceForeignTenantThrows404_andNeverSaves() {
        when(disputeRepo.findByIdAndTenantId("dsp_victim", ATTACKER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> svc.submitEvidence("dsp_victim", ATTACKER, "api"))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(disputeRepo).findByIdAndTenantId("dsp_victim", ATTACKER);
        verify(disputeRepo, never()).save(any());
        verify(disputeRepo, never()).saveEvent(any());
        // Never falls back to the unscoped (webhook) lookup.
        verify(disputeRepo, never()).findById(anyString());
    }

    @Test
    void submitEvidenceOwnedScopesByTenant_andTransitions() {
        Dispute d = ownedDispute();
        when(disputeRepo.findByIdAndTenantId("dsp_1", TENANT)).thenReturn(Optional.of(d));

        Dispute result = svc.submitEvidence("dsp_1", TENANT, "api");

        assertThat(result.getStatus()).isEqualTo(DisputeState.EVIDENCE_SUBMITTED);
        verify(disputeRepo).findByIdAndTenantId("dsp_1", TENANT);
        verify(disputeRepo).save(d);
        verify(disputeRepo, never()).findById(anyString());
    }

    // ---- uploadEvidence (POST /v1/disputes/{id}/evidence) ----

    @Test
    void uploadEvidenceForeignTenantThrows404_andNeverStoresOrSaves() {
        when(disputeRepo.findByIdAndTenantId("dsp_victim", ATTACKER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> svc.uploadEvidence(
                "dsp_victim", ATTACKER, DisputeEvidenceType.RECEIPT, "r.pdf",
                content(), "application/pdf", "desc"))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(disputeRepo).findByIdAndTenantId("dsp_victim", ATTACKER);
        // Ownership is asserted BEFORE the file is written to storage and BEFORE any persistence.
        verify(evidenceStorage, never()).store(anyString(), anyString(), anyString(), any(), anyString());
        verify(disputeRepo, never()).save(any());
        verify(disputeRepo, never()).saveEvidence(any());
        verify(disputeRepo, never()).findById(anyString());
    }

    @Test
    void uploadEvidenceOwnedScopesByTenant_andStores() {
        Dispute d = ownedDispute();
        when(disputeRepo.findByIdAndTenantId("dsp_1", TENANT)).thenReturn(Optional.of(d));
        when(evidenceStorage.store(anyString(), anyString(), anyString(), any(), anyString()))
                .thenReturn("key_1");

        svc.uploadEvidence("dsp_1", TENANT, DisputeEvidenceType.RECEIPT, "r.pdf",
                content(), "application/pdf", "desc");

        verify(disputeRepo).findByIdAndTenantId("dsp_1", TENANT);
        // Stored under the AUTHENTICATED caller's tenant for key namespacing (not a client header).
        verify(evidenceStorage).store(eq(TENANT), eq("dsp_1"), eq("r.pdf"), any(), eq("application/pdf"));
        verify(disputeRepo).saveEvidence(any());
        verify(disputeRepo, never()).findById(anyString());
    }

    // ---- getTimeline (GET /v1/disputes/{id}/events) ----

    @Test
    void getTimelineScopesToTenant_andDoesNotUseUnscopedLookup() {
        DisputeEvent ev = new DisputeEvent();
        ev.setId("dev_1");
        ev.setDisputeId("dsp_1");
        ev.setTenantId(TENANT);
        ev.setEventType("OPENED");
        ev.setActor("system");
        ev.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        when(disputeRepo.findEventsByDisputeIdAndTenantId("dsp_1", TENANT))
                .thenReturn(List.of(ev));

        assertThat(svc.getTimeline("dsp_1", TENANT)).containsExactly(ev);

        verify(disputeRepo).findEventsByDisputeIdAndTenantId("dsp_1", TENANT);
        verify(disputeRepo, never()).findEventsByDisputeId(anyString());
    }

    @Test
    void getTimelineForeignTenantReturnsEmpty_noOracle() {
        when(disputeRepo.findEventsByDisputeIdAndTenantId("dsp_victim", ATTACKER))
                .thenReturn(List.of());

        assertThat(svc.getTimeline("dsp_victim", ATTACKER)).isEmpty();

        verify(disputeRepo).findEventsByDisputeIdAndTenantId("dsp_victim", ATTACKER);
        verify(disputeRepo, never()).findEventsByDisputeId(anyString());
    }
}
