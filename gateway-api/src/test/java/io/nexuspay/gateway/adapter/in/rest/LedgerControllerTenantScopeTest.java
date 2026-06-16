package io.nexuspay.gateway.adapter.in.rest;

import io.nexuspay.gateway.adapter.in.rest.dto.JournalEntryResponse;
import io.nexuspay.iam.domain.NexusPayPrincipal;
import io.nexuspay.ledger.application.GetBalanceUseCase;
import io.nexuspay.ledger.application.GetJournalEntriesUseCase;
import io.nexuspay.ledger.domain.JournalEntry;
import io.nexuspay.ledger.domain.Posting;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SEC-08 (B-008): {@code GET /v1/ledger/journal-entries} must scope BOTH query paths
 * (payment-reference and date-range) to the authenticated caller's tenant. Before this fix the
 * controller called the use-case WITHOUT a tenant, so any authenticated user could read every tenant's
 * double-entry lines.
 *
 * <p>The use-case is mocked to be tenant-aware (returns ONLY the entries for the tenant it is asked
 * for). A tenant-A principal must therefore receive ONLY tenant-A entries — and the controller must
 * pass {@code principal.tenantId()} to the use-case. If the controller reverts to the un-scoped call,
 * it will not compile (the use-case methods now REQUIRE a tenant); the {@code verify(...)} on the
 * tenant argument additionally pins that the caller's tenant — not some other value — is forwarded.</p>
 */
class LedgerControllerTenantScopeTest {

    private GetBalanceUseCase getBalanceUseCase;
    private GetJournalEntriesUseCase getJournalEntriesUseCase;
    private LedgerController controller;

    private static final String TENANT_A = "tenant-A";
    private static final String TENANT_B = "tenant-B";
    private static final String SHARED_REF = "pi_shared";

    private final NexusPayPrincipal viewerA =
            new NexusPayPrincipal("viewer_A", TENANT_A, "viewer", NexusPayPrincipal.AuthMethod.JWT);

    @BeforeEach
    void setUp() {
        getBalanceUseCase = mock(GetBalanceUseCase.class);
        getJournalEntriesUseCase = mock(GetJournalEntriesUseCase.class);
        controller = new LedgerController(getBalanceUseCase, getJournalEntriesUseCase);

        // Tenant-aware stubs: each tenant only ever sees its OWN entry for the shared reference.
        when(getJournalEntriesUseCase.getByPaymentReference(SHARED_REF, TENANT_A))
                .thenReturn(List.of(entry("je_a", SHARED_REF, TENANT_A)));
        when(getJournalEntriesUseCase.getByPaymentReference(SHARED_REF, TENANT_B))
                .thenReturn(List.of(entry("je_b", SHARED_REF, TENANT_B)));
        when(getJournalEntriesUseCase.getByDateRange(
                any(Instant.class), any(Instant.class), anyInt(), anyInt(), eq(TENANT_A)))
                .thenReturn(List.of(entry("je_a", SHARED_REF, TENANT_A)));
        when(getJournalEntriesUseCase.getByDateRange(
                any(Instant.class), any(Instant.class), anyInt(), anyInt(), eq(TENANT_B)))
                .thenReturn(List.of(entry("je_b", SHARED_REF, TENANT_B)));
    }

    @Test
    void byPaymentReference_tenantA_receivesOnlyTenantAEntries() {
        ResponseEntity<List<JournalEntryResponse>> resp =
                controller.listJournalEntries(SHARED_REF, null, null, 50, 0, viewerA);

        // tenant-A's call is scoped to tenant-A — never tenant-B's "je_b".
        verify(getJournalEntriesUseCase).getByPaymentReference(SHARED_REF, TENANT_A);
        assertThat(resp.getBody())
                .extracting(JournalEntryResponse::id)
                .containsExactly("je_a")
                .doesNotContain("je_b");
    }

    @Test
    void byDateRange_tenantA_receivesOnlyTenantAEntries() {
        ResponseEntity<List<JournalEntryResponse>> resp =
                controller.listJournalEntries(null, Instant.EPOCH, Instant.now(), 50, 0, viewerA);

        verify(getJournalEntriesUseCase)
                .getByDateRange(any(Instant.class), any(Instant.class), anyInt(), anyInt(), eq(TENANT_A));
        assertThat(resp.getBody())
                .extracting(JournalEntryResponse::id)
                .containsExactly("je_a")
                .doesNotContain("je_b");
    }

    private static JournalEntry entry(String id, String paymentRef, String tenantId) {
        List<Posting> postings = List.of(
                new Posting("post_" + id + "_d", "la_merchant_recv_usd", 10000, "USD"),
                new Posting("post_" + id + "_c", "la_customer_liab_usd", -10000, "USD"));
        return new JournalEntry(id, paymentRef, "Payment captured", tenantId, Instant.now(), Map.of(), postings);
    }
}
