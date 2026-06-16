package io.nexuspay.marketplace.application.service;

import io.nexuspay.marketplace.application.port.out.MarketplaceEventPublisher;
import io.nexuspay.marketplace.application.port.out.MarketplaceRepository;
import io.nexuspay.marketplace.application.port.out.PayoutExecutionPort;
import io.nexuspay.marketplace.application.port.out.PayoutExecutionPort.PayoutExecutionRequest;
import io.nexuspay.marketplace.application.port.out.PayoutExecutionPort.PayoutExecutionResult;
import io.nexuspay.marketplace.domain.Payout;
import io.nexuspay.marketplace.domain.PayoutMethod;
import io.nexuspay.marketplace.domain.PayoutStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SEC-25: gate-level unit test for the REAL {@link PayoutReconcileService} (the marketplace mirror of
 * iam's {@code ApprovalServiceReconcilerTest}). The {@code PayoutReconcilerTest} mocks this service away
 * and re-implements its guards in Java, so the actual money-safety logic — the deterministic re-drive
 * key, the flip-then-publish guard, and the failure-reason truncation that protects the terminal UPDATE
 * — is never executed there. This class constructs {@code new PayoutReconcileService(...)} with mocked
 * collaborators and pins that logic directly, in the DEFAULT (untagged) gate so a regression reds CI.
 *
 * <p>Each test below FAILS if the corresponding production behavior is reverted:
 * <ul>
 *   <li>{@link #redriveForwardsTheDeterministicIdempotencyKey} — fails if {@code redrive()} randomizes
 *       the key (the crux no-double-pay invariant; the soak test that also catches this is
 *       {@code @Tag("simulation")}-excluded from the gate);</li>
 *   <li>{@link #markPaidPublishesEventOnlyWhenTheConditionalUpdateFlipped} /
 *       {@link #markPaidDoesNotPublishWhenAnotherWriterAlreadyFinalized} — fail if the {@code if(flipped)}
 *       guard is dropped (double-publish on a 0-row UPDATE → downstream double-credit);</li>
 *   <li>{@link #markFailedCapsReasonToTheFailureReasonColumnWidth} — fails if the FAILED reason is not
 *       capped to the {@code failure_reason} VARCHAR(256) width (oversized reason → value-too-long
 *       DataException → row never reaches FAILED, burns every retry).</li>
 * </ul></p>
 */
class PayoutReconcileServiceTest {

    private MarketplaceRepository repository;
    private PayoutExecutionPort gateway;
    private MarketplaceEventPublisher events;
    private PayoutReconcileService service;

    @BeforeEach
    void setUp() {
        repository = mock(MarketplaceRepository.class);
        gateway = mock(PayoutExecutionPort.class);
        events = mock(MarketplaceEventPublisher.class);
        service = new PayoutReconcileService(repository, gateway, events);
    }

    private static Payout stuck(String id, String tenant) {
        Payout p = new Payout();
        p.setId(id);
        p.setConnectedAccountId("ca_" + id);
        p.setTenantId(tenant);
        p.setAmount(5000);
        p.setCurrency("USD");
        p.setMethod(PayoutMethod.BANK_TRANSFER);
        p.setStatus(PayoutStatus.PROCESSING);
        return p;
    }

    // ---- no-double-pay key invariant (SHOULD_FIX #4) -------------------------------------------

    @Test
    void redriveForwardsTheDeterministicIdempotencyKey() {
        // Mutating the REAL redrive() to send a random UUID (a genuine double-pay regression) must
        // turn this assertion red — the soak test that also catches it is excluded from the gate.
        when(gateway.execute(any(PayoutExecutionRequest.class)))
                .thenReturn(new PayoutExecutionResult(true, "pex_1", null));

        service.redrive(stuck("po_42", "t1"));

        ArgumentCaptor<PayoutExecutionRequest> cap = ArgumentCaptor.forClass(PayoutExecutionRequest.class);
        verify(gateway).execute(cap.capture());
        assertThat(cap.getValue().idempotencyKey())
                .as("re-drive MUST reuse the deterministic key the original disburse used — never randomize")
                .isEqualTo("payout-po_42")
                .isEqualTo(Payout.idempotencyKey("po_42"));
        // The request also carries the payout's identity unchanged.
        assertThat(cap.getValue().payoutId()).isEqualTo("po_42");
        assertThat(cap.getValue().connectedAccountId()).isEqualTo("ca_po_42");
        assertThat(cap.getValue().amount()).isEqualTo(5000);
        assertThat(cap.getValue().currency()).isEqualTo("USD");
        assertThat(cap.getValue().method()).isEqualTo(PayoutMethod.BANK_TRANSFER);
    }

    // ---- flip-then-publish guard: markPaid (SHOULD_FIX #5) -------------------------------------

    @Test
    void markPaidReturnsTheRepoFlipResultAndPublishesWhenFlipped() {
        when(repository.markPayoutPaid("po_1", "t1", "pex_ok")).thenReturn(true);

        boolean flipped = service.markPaid("po_1", "t1", "pex_ok");

        assertThat(flipped).isTrue();
        verify(events).publishEvent(eq("Payout"), eq("po_1"), eq("PayoutPaid"), anyMap(), eq("t1"));
    }

    @Test
    void markPaidDoesNotPublishWhenAnotherWriterAlreadyFinalized() {
        // 0-row UPDATE (status already flipped by the racing original disburse): MUST NOT re-publish,
        // or a reconcile pass double-credits downstream.
        when(repository.markPayoutPaid("po_1", "t1", "pex_ok")).thenReturn(false);

        boolean flipped = service.markPaid("po_1", "t1", "pex_ok");

        assertThat(flipped).isFalse();
        verify(events, never()).publishEvent(anyString(), anyString(), anyString(), anyMap(), anyString());
    }

    // ---- flip-then-publish guard: markFailed (SHOULD_FIX #5) -----------------------------------

    @Test
    void markFailedReturnsTheRepoFlipResultAndPublishesWhenFlipped() {
        when(repository.markPayoutFailed(eq("po_1"), eq("t1"), anyString())).thenReturn(true);

        boolean flipped = service.markFailed("po_1", "t1", "account closed");

        assertThat(flipped).isTrue();
        verify(events).publishEvent(eq("Payout"), eq("po_1"), eq("PayoutFailed"), anyMap(), eq("t1"));
    }

    @Test
    void markFailedDoesNotPublishWhenNotFlipped() {
        when(repository.markPayoutFailed(eq("po_1"), eq("t1"), anyString())).thenReturn(false);

        boolean flipped = service.markFailed("po_1", "t1", "account closed");

        assertThat(flipped).isFalse();
        verify(events, never()).publishEvent(anyString(), anyString(), anyString(), anyMap(), anyString());
    }

    // ---- failure_reason column-width cap (SHOULD_FIX #1) ---------------------------------------

    @Test
    void markFailedCapsReasonToTheFailureReasonColumnWidth() {
        // payouts.failure_reason is VARCHAR(256). A PSP reason in (256, 480] must be capped to <=256
        // BEFORE the conditional UPDATE, or the terminal write throws value-too-long and the row can
        // never reach FAILED (burns every reconcile attempt). 480 (the old MAX_ERROR_LEN) would NOT cap.
        when(repository.markPayoutFailed(eq("po_1"), eq("t1"), anyString())).thenReturn(true);
        String huge = "x".repeat(5000);

        service.markFailed("po_1", "t1", huge);

        ArgumentCaptor<String> cap = ArgumentCaptor.forClass(String.class);
        verify(repository).markPayoutFailed(eq("po_1"), eq("t1"), cap.capture());
        assertThat(cap.getValue())
                .as("FAILED reason must fit the failure_reason VARCHAR(256) column")
                .hasSize(256);
    }

    @Test
    void markFailedPassesShortReasonThrough() {
        when(repository.markPayoutFailed(eq("po_1"), eq("t1"), anyString())).thenReturn(true);

        service.markFailed("po_1", "t1", "account closed");

        verify(repository).markPayoutFailed(eq("po_1"), eq("t1"), eq("account closed"));
    }

    // ---- last_reconcile_error (TEXT) cap on the transient-failure path -------------------------

    @Test
    void recordFailureIsTenantBoundAndCapsTheErrorToMaxErrorLen() {
        Instant next = Instant.now().plusSeconds(120);
        String huge = "y".repeat(5000);

        service.recordFailure("po_1", "t1", next, huge);

        ArgumentCaptor<String> cap = ArgumentCaptor.forClass(String.class);
        verify(repository).recordPayoutReconcileFailure(eq("po_1"), eq("t1"), eq(next), cap.capture());
        assertThat(cap.getValue())
                .as("last_reconcile_error is TEXT; the 480 cap only bounds bloat")
                .hasSize(480);
    }

    @Test
    void recordFailureLeavesShortErrorUntouched() {
        Instant next = Instant.now().plusSeconds(120);
        service.recordFailure("po_1", "t1", next, "circuit breaker open");
        verify(repository).recordPayoutReconcileFailure(
                eq("po_1"), eq("t1"), eq(next), eq("circuit breaker open"));
    }

    // ---- discovery + reload pass-throughs ------------------------------------------------------

    @Test
    void findStuckProcessingDelegatesToTheRepositoryFinder() {
        Instant cutoff = Instant.now().minusSeconds(300);
        Instant now = Instant.now();
        when(repository.findStuckProcessingPayouts(cutoff, now, 5, 100))
                .thenReturn(java.util.List.of(stuck("po_1", "t1")));

        var out = service.findStuckProcessing(cutoff, now, 5, 100);

        assertThat(out).extracting(Payout::getId).containsExactly("po_1");
        verify(repository).findStuckProcessingPayouts(cutoff, now, 5, 100);
    }

    @Test
    void reloadStuckForUpdateReturnsEmptyWhenNoLongerProcessing() {
        when(repository.reloadStuckPayoutForUpdate("po_1")).thenReturn(java.util.Optional.empty());
        assertThat(service.reloadStuckForUpdate("po_1")).isEmpty();
        verify(repository).reloadStuckPayoutForUpdate("po_1");
    }

    @Test
    void markPaidEventPayloadCarriesReferenceAndTenant() {
        when(repository.markPayoutPaid("po_1", "t1", "pex_ref")).thenReturn(true);

        service.markPaid("po_1", "t1", "pex_ref");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(events).publishEvent(eq("Payout"), eq("po_1"), eq("PayoutPaid"), payload.capture(), eq("t1"));
        assertThat(payload.getValue())
                .containsEntry("externalReference", "pex_ref")
                .containsEntry("tenantId", "t1");
    }
}
