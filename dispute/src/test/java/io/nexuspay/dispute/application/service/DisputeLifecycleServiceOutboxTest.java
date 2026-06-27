package io.nexuspay.dispute.application.service;

import io.nexuspay.common.event.EventTypes;
import io.nexuspay.common.event.WebhookEventTaxonomy;
import io.nexuspay.dispute.application.port.out.DisputeOutboxPort;
import io.nexuspay.dispute.application.port.out.DisputeRepository;
import io.nexuspay.dispute.application.port.out.EvidenceStoragePort;
import io.nexuspay.dispute.application.port.out.LedgerPort;
import io.nexuspay.dispute.domain.Dispute;
import io.nexuspay.dispute.domain.DisputeState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TEST-2: pins that every dispute state transition publishes its MAPPED outbound event through the
 * transactional outbox, under the dispute's server-authoritative TENANT (SEC-24, never "default"), and
 * that each emitted internal type has a canonical dotted mapping (so it is actually deliverable).
 *
 * <p>Transition → internal type → dotted:
 * open → DisputeCreated/DisputeFundsWithdrawn (dispute.created/dispute.funds_withdrawn);
 * requestEvidence → DisputeEvidenceNeeded (dispute.evidence_needed);
 * submitEvidence → DisputeEvidenceSubmitted (dispute.evidence_submitted);
 * win → DisputeWon (dispute.won); lose → DisputeLost (dispute.lost);
 * expire → DisputeClosed (dispute.closed).</p>
 */
class DisputeLifecycleServiceOutboxTest {

    private static final String TENANT = "tenant-X";

    private DisputeRepository disputeRepo;
    private EvidenceStoragePort evidence;
    private LedgerPort ledger;
    private DisputeOutboxPort outbox;
    private DisputeLifecycleService svc;

    @BeforeEach
    void setUp() {
        disputeRepo = mock(DisputeRepository.class);
        evidence = mock(EvidenceStoragePort.class);
        ledger = mock(LedgerPort.class);
        outbox = mock(DisputeOutboxPort.class);
        svc = new DisputeLifecycleService(disputeRepo, evidence, ledger, outbox);
        when(disputeRepo.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private Dispute loaded(DisputeState state) {
        Dispute d = new Dispute();
        d.setId("dp_1");
        d.setTenantId(TENANT);
        d.setPaymentId("pay_test_1");
        d.setExternalDisputeId("ext_1");
        d.setAmount(5000L);
        d.setCurrency("USD");
        d.setStatus(state);
        return d;
    }

    @Test
    void open_emitsCreatedThenFundsWithdrawn_underDisputeTenant_inOrderWithReserve() {
        when(disputeRepo.findByTenantIdAndExternalDisputeId(TENANT, "ext_1")).thenReturn(Optional.empty());

        svc.openDispute(TENANT, "pay_test_1", "ext_1", "10.4", "fraud", 5000L, "USD", "visa", null, false);

        // dispute.created BEFORE the reserve; dispute.funds_withdrawn AFTER (the money-moving point).
        InOrder order = inOrder(outbox, ledger);
        order.verify(outbox).publishEvent(eq(EventTypes.AGGREGATE_DISPUTE), eq("dp_1"),
                eq(EventTypes.DISPUTE_CREATED), any(), eq(TENANT), eq(false));
        order.verify(ledger).createChargebackReserve(eq(TENANT), eq("dp_1"), eq(5000L), eq("USD"));
        order.verify(outbox).publishEvent(eq(EventTypes.AGGREGATE_DISPUTE), eq("dp_1"),
                eq(EventTypes.DISPUTE_FUNDS_WITHDRAWN), any(), eq(TENANT), eq(false));

        // Never under "default" (SEC-24) — events fan out to the right merchant.
        verify(outbox, never()).publishEvent(anyString(), anyString(), anyString(), any(),
                eq("default"), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void open_replay_emitsNoEvent() {
        // An already-opened (tenant, externalDisputeId) -> no-op: no second reserve AND no duplicate webhook.
        when(disputeRepo.findByTenantIdAndExternalDisputeId(TENANT, "ext_1"))
                .thenReturn(Optional.of(loaded(DisputeState.OPENED)));

        svc.openDispute(TENANT, "pay_test_1", "ext_1", "10.4", "fraud", 5000L, "USD", "visa", null);

        verify(outbox, never()).publishEvent(anyString(), anyString(), anyString(), any(), anyString());
        verify(outbox, never()).publishEvent(anyString(), anyString(), anyString(), any(), anyString(), eq(true));
        verify(outbox, never()).publishEvent(anyString(), anyString(), anyString(), any(), anyString(), eq(false));
    }

    @Test
    void requestEvidence_emitsEvidenceNeeded_underDisputeTenant() {
        when(disputeRepo.findById("dp_1")).thenReturn(Optional.of(loaded(DisputeState.OPENED)));

        svc.requestEvidence("dp_1", "agent");

        verify(outbox).publishEvent(eq(EventTypes.AGGREGATE_DISPUTE), eq("dp_1"),
                eq(EventTypes.DISPUTE_EVIDENCE_NEEDED), any(), eq(TENANT), eq(true));
    }

    @Test
    void submitEvidence_unscoped_emitsEvidenceSubmitted_underDisputeTenant() {
        when(disputeRepo.findById("dp_1")).thenReturn(Optional.of(loaded(DisputeState.EVIDENCE_NEEDED)));

        svc.submitEvidence("dp_1", "auto-representment");

        verify(outbox).publishEvent(eq(EventTypes.AGGREGATE_DISPUTE), eq("dp_1"),
                eq(EventTypes.DISPUTE_EVIDENCE_SUBMITTED), any(), eq(TENANT), eq(true));
    }

    @Test
    void submitEvidence_tenantScoped_emitsEvidenceSubmitted_underDisputeTenant() {
        when(disputeRepo.findByIdAndTenantId("dp_1", TENANT))
                .thenReturn(Optional.of(loaded(DisputeState.EVIDENCE_NEEDED)));

        svc.submitEvidence("dp_1", TENANT, "api");

        verify(outbox).publishEvent(eq(EventTypes.AGGREGATE_DISPUTE), eq("dp_1"),
                eq(EventTypes.DISPUTE_EVIDENCE_SUBMITTED), any(), eq(TENANT), eq(true));
    }

    @Test
    void win_emitsWon_underDisputeTenant() {
        when(disputeRepo.findById("dp_1")).thenReturn(Optional.of(loaded(DisputeState.EVIDENCE_SUBMITTED)));

        svc.win("dp_1", "agent");

        verify(outbox).publishEvent(eq(EventTypes.AGGREGATE_DISPUTE), eq("dp_1"),
                eq(EventTypes.DISPUTE_WON), any(), eq(TENANT), eq(true));
    }

    @Test
    void lose_emitsLost_underDisputeTenant() {
        when(disputeRepo.findById("dp_1")).thenReturn(Optional.of(loaded(DisputeState.EVIDENCE_SUBMITTED)));

        svc.lose("dp_1", "agent");

        verify(outbox).publishEvent(eq(EventTypes.AGGREGATE_DISPUTE), eq("dp_1"),
                eq(EventTypes.DISPUTE_LOST), any(), eq(TENANT), eq(true));
    }

    @Test
    void expire_emitsClosed_onlyOnRealTransition() {
        when(disputeRepo.findById("dp_1")).thenReturn(Optional.of(loaded(DisputeState.EVIDENCE_SUBMITTED)));

        svc.expire("dp_1");

        verify(outbox).publishEvent(eq(EventTypes.AGGREGATE_DISPUTE), eq("dp_1"),
                eq(EventTypes.DISPUTE_CLOSED), any(), eq(TENANT), eq(true));
    }

    @Test
    void expire_alreadyTerminal_emitsNoEvent() {
        // An already-LOST dispute -> expire() is a no-op; no duplicate dispute.closed webhook.
        when(disputeRepo.findById("dp_1")).thenReturn(Optional.of(loaded(DisputeState.LOST)));

        svc.expire("dp_1");

        verify(outbox, never()).publishEvent(anyString(), anyString(), eq(EventTypes.DISPUTE_CLOSED),
                any(), anyString(), eq(true));
    }

    @Test
    void everyEmittedInternalType_hasACanonicalDottedMapping() {
        // A transition that emits an internal type with no dotted mapping would be silently undeliverable.
        assertThat(WebhookEventTaxonomy.toDotted(EventTypes.DISPUTE_CREATED)).isEqualTo("dispute.created");
        assertThat(WebhookEventTaxonomy.toDotted(EventTypes.DISPUTE_FUNDS_WITHDRAWN)).isEqualTo("dispute.funds_withdrawn");
        assertThat(WebhookEventTaxonomy.toDotted(EventTypes.DISPUTE_EVIDENCE_NEEDED)).isEqualTo("dispute.evidence_needed");
        assertThat(WebhookEventTaxonomy.toDotted(EventTypes.DISPUTE_EVIDENCE_SUBMITTED)).isEqualTo("dispute.evidence_submitted");
        assertThat(WebhookEventTaxonomy.toDotted(EventTypes.DISPUTE_WON)).isEqualTo("dispute.won");
        assertThat(WebhookEventTaxonomy.toDotted(EventTypes.DISPUTE_LOST)).isEqualTo("dispute.lost");
        assertThat(WebhookEventTaxonomy.toDotted(EventTypes.DISPUTE_CLOSED)).isEqualTo("dispute.closed");
    }

    @Test
    void disputeObjectPayload_carriesIdentifyingFields() {
        when(disputeRepo.findByTenantIdAndExternalDisputeId(TENANT, "ext_1")).thenReturn(Optional.empty());

        svc.openDispute(TENANT, "pay_test_1", "ext_1", "10.4", "fraud", 5000L, "USD", "visa", null, false);

        org.mockito.ArgumentCaptor<Map<String, Object>> payload =
                org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(outbox).publishEvent(anyString(), anyString(), eq(EventTypes.DISPUTE_CREATED),
                payload.capture(), eq(TENANT), eq(false));
        Map<String, Object> obj = payload.getValue();
        assertThat(obj).containsEntry("dispute_id", "dp_1");
        assertThat(obj).containsEntry("payment_id", "pay_test_1");
        assertThat(obj).containsEntry("amount", 5000L);
        assertThat(obj).containsEntry("currency", "USD");
        assertThat(obj).containsEntry("status", "OPENED");
    }
}
