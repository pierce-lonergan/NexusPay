package io.nexuspay.ledger.application;

import io.nexuspay.ledger.application.port.JournalEntryRepository;
import io.nexuspay.ledger.domain.JournalEntry;
import io.nexuspay.ledger.domain.Posting;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SEC-08 (B-008): the journal-entry read path MUST be tenant-scoped. Before this fix
 * {@code GetJournalEntriesUseCase} called the cross-tenant {@code findByPaymentReference(String)} /
 * {@code findByDateRange(...)} finders, so any authenticated caller could read EVERY tenant's
 * double-entry lines.
 *
 * <p>This test drives the use-case against an in-memory {@link JournalEntryRepository} that performs
 * REAL tenant filtering in the new tenant-scoped finders and (deliberately) NO filtering in the legacy
 * cross-tenant finders. So if the use-case ever reverts to the cross-tenant methods, both tenants'
 * entries leak through and these assertions FAIL — i.e. the test fails if the fix is reverted
 * (no vacuous assertion).</p>
 */
class GetJournalEntriesUseCaseTenantScopeTest {

    private FakeJournalEntryRepository repo;
    private GetJournalEntriesUseCase useCase;

    private static final String TENANT_A = "tenant-A";
    private static final String TENANT_B = "tenant-B";

    @BeforeEach
    void setUp() {
        repo = new FakeJournalEntryRepository();
        useCase = new GetJournalEntriesUseCase(repo);

        // Two tenants both have an entry for the SAME shared payment reference, plus date-range entries.
        repo.add(entry("je_a1", "pi_shared", TENANT_A, Instant.parse("2026-01-01T10:00:00Z")));
        repo.add(entry("je_b1", "pi_shared", TENANT_B, Instant.parse("2026-01-01T11:00:00Z")));
        repo.add(entry("je_a2", "pi_only_a", TENANT_A, Instant.parse("2026-01-02T10:00:00Z")));
        repo.add(entry("je_b2", "pi_only_b", TENANT_B, Instant.parse("2026-01-02T11:00:00Z")));
    }

    @Test
    void getByPaymentReference_returnsOnlyCallerTenantEntries() {
        List<JournalEntry> aResult = useCase.getByPaymentReference("pi_shared", TENANT_A);

        assertThat(aResult)
                .as("tenant-A must only see its own entry for the shared payment reference")
                .extracting(JournalEntry::getId)
                .containsExactly("je_a1");
        assertThat(aResult)
                .as("no tenant-B entry may leak into tenant-A's result")
                .noneMatch(e -> TENANT_B.equals(e.getTenantId()));
    }

    @Test
    void getByDateRange_returnsOnlyCallerTenantEntries() {
        List<JournalEntry> bResult = useCase.getByDateRange(
                Instant.EPOCH, Instant.parse("2026-12-31T00:00:00Z"), 50, 0, TENANT_B);

        assertThat(bResult)
                .as("tenant-B's date-range query must return ONLY tenant-B entries")
                .extracting(JournalEntry::getId)
                .containsExactlyInAnyOrder("je_b1", "je_b2");
        assertThat(bResult)
                .as("no tenant-A entry may leak into tenant-B's date-range result")
                .noneMatch(e -> TENANT_A.equals(e.getTenantId()));
    }

    private static JournalEntry entry(String id, String paymentRef, String tenantId, Instant postedAt) {
        List<Posting> postings = List.of(
                new Posting("post_" + id + "_d", "la_merchant_recv_usd", 10000, "USD"),
                new Posting("post_" + id + "_c", "la_customer_liab_usd", -10000, "USD"));
        return new JournalEntry(id, paymentRef, "Payment captured", tenantId, postedAt, Map.of(), postings);
    }

    /**
     * In-memory fake. The tenant-scoped finders filter by tenant (the secure path); the legacy
     * cross-tenant finders intentionally do NOT — so a regression to them is observable as a leak.
     */
    private static final class FakeJournalEntryRepository implements JournalEntryRepository {
        private final List<JournalEntry> store = new ArrayList<>();

        void add(JournalEntry e) { store.add(e); }

        @Override
        public Optional<JournalEntry> findById(String id) {
            return store.stream().filter(e -> e.getId().equals(id)).findFirst();
        }

        @Override
        public List<JournalEntry> findByPaymentReference(String paymentReference) {
            // Cross-tenant (legacy) — NO tenant filter on purpose.
            return store.stream().filter(e -> e.getPaymentReference().equals(paymentReference)).toList();
        }

        @Override
        public List<JournalEntry> findByPaymentReferenceAndTenantId(String paymentReference, String tenantId) {
            return store.stream()
                    .filter(e -> e.getPaymentReference().equals(paymentReference))
                    .filter(e -> e.getTenantId().equals(tenantId))
                    .toList();
        }

        @Override
        public List<JournalEntry> findByDateRange(Instant from, Instant to, int limit, int offset, String tenantId) {
            return store.stream()
                    .filter(e -> !e.getPostedAt().isBefore(from) && !e.getPostedAt().isAfter(to))
                    .filter(e -> e.getTenantId().equals(tenantId))
                    .skip(Math.max(offset, 0))
                    .limit(Math.min(limit, 100))
                    .toList();
        }

        @Override
        public JournalEntry save(JournalEntry journalEntry) {
            store.add(journalEntry);
            return journalEntry;
        }

        @Override
        public JournalEntry saveAndFlush(JournalEntry journalEntry) {
            return save(journalEntry);
        }

        @Override
        public boolean existsByPaymentReferenceAndDescription(String paymentReference, String description) {
            return store.stream().anyMatch(e ->
                    e.getPaymentReference().equals(paymentReference) && e.getDescription().equals(description));
        }
    }
}
