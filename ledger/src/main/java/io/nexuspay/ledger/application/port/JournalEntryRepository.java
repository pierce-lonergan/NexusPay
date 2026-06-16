package io.nexuspay.ledger.application.port;

import io.nexuspay.ledger.domain.JournalEntry;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Outbound port for journal entry persistence.
 */
public interface JournalEntryRepository {

    Optional<JournalEntry> findById(String id);

    /**
     * SEC-08 (B-008): cross-tenant lookup, retained ONLY for internal non-HTTP callers that legitimately
     * have no caller-tenant in scope (reconciliation settlement matching keyed by payment id; the ledger
     * redelivery red-team gate). HTTP-facing reads MUST use {@link #findByPaymentReferenceAndTenantId}.
     */
    List<JournalEntry> findByPaymentReference(String paymentReference);

    /**
     * SEC-08 (B-008): tenant-scoped lookup for HTTP read paths. Returns ONLY the given tenant's entries,
     * so an authenticated caller can never read another tenant's double-entry lines.
     */
    List<JournalEntry> findByPaymentReferenceAndTenantId(String paymentReference, String tenantId);

    /**
     * SEC-08 (B-008): tenant-scoped date-range lookup. {@code tenantId} is mandatory — the only caller is
     * the HTTP {@code GetJournalEntriesUseCase}, which always sources it from the authenticated principal.
     */
    List<JournalEntry> findByDateRange(Instant from, Instant to, int limit, int offset, String tenantId);

    JournalEntry save(JournalEntry journalEntry);

    /**
     * SEC-10: flushing variant of {@link #save}. JournalEntryEntity has a pre-assigned String
     * {@code @Id} (no {@code @GeneratedValue}/{@code @Version}, not {@code Persistable}), so a plain
     * {@code save} routes through {@code em.merge()} and DEFERS the INSERT to flush/commit — the
     * {@code uq_journal_entries_payment_ref_desc} unique-violation would then surface OUTSIDE the
     * use-case try/catch (at commit) and propagate to the Kafka consumer (retry/DLT). saveAndFlush
     * forces the INSERT (and the constraint check) synchronously so the dup-key no-op can catch it.
     */
    JournalEntry saveAndFlush(JournalEntry journalEntry);

    boolean existsByPaymentReferenceAndDescription(String paymentReference, String description);
}
