package io.nexuspay.ledger.application;

import io.nexuspay.ledger.application.port.JournalEntryRepository;
import io.nexuspay.ledger.domain.JournalEntry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Queries journal entries by payment reference or date range.
 */
@Service
@Transactional(readOnly = true)
public class GetJournalEntriesUseCase {

    private final JournalEntryRepository journalEntryRepository;

    public GetJournalEntriesUseCase(JournalEntryRepository journalEntryRepository) {
        this.journalEntryRepository = journalEntryRepository;
    }

    public Optional<JournalEntry> getById(String id) {
        return journalEntryRepository.findById(id);
    }

    /**
     * SEC-08 (B-008): tenant-scoped. {@code tenantId} comes from the authenticated principal so a caller
     * can only read its own tenant's journal entries (mirrors {@code GetBalanceUseCase.getAllBalances}).
     */
    public List<JournalEntry> getByPaymentReference(String paymentReference, String tenantId) {
        return journalEntryRepository.findByPaymentReferenceAndTenantId(paymentReference, tenantId);
    }

    /**
     * SEC-08 (B-008): tenant-scoped. {@code tenantId} comes from the authenticated principal.
     */
    public List<JournalEntry> getByDateRange(Instant from, Instant to, int limit, int offset, String tenantId) {
        return journalEntryRepository.findByDateRange(from, to,
                Math.min(limit, 100),  // cap at 100
                Math.max(offset, 0),
                tenantId);
    }
}
