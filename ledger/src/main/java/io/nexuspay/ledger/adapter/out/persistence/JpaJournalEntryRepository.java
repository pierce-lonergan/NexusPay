package io.nexuspay.ledger.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface JpaJournalEntryRepository extends JpaRepository<JournalEntryEntity, String> {

    // SEC-08 (B-008): cross-tenant variant retained ONLY for internal, non-HTTP callers that legitimately
    // have no caller-tenant in scope (reconciliation settlement matching, the ledger redelivery red-team
    // gate). HTTP read paths MUST use the tenant-scoped finder below.
    List<JournalEntryEntity> findByPaymentReference(String paymentReference);

    // SEC-08 (B-008): tenant-scoped lookup for the HTTP /v1/ledger/journal-entries path. JournalEntryEntity
    // maps the column tenant_id onto the property `tenantId`, so derived-query keyword resolves correctly.
    List<JournalEntryEntity> findByPaymentReferenceAndTenantId(String paymentReference, String tenantId);

    @Query("SELECT j FROM JournalEntryEntity j WHERE j.postedAt >= :from AND j.postedAt <= :to " +
           "AND j.tenantId = :tenantId ORDER BY j.postedAt DESC")
    List<JournalEntryEntity> findByDateRange(@Param("from") Instant from,
                                              @Param("to") Instant to,
                                              @Param("tenantId") String tenantId,
                                              org.springframework.data.domain.Pageable pageable);

    boolean existsByPaymentReferenceAndDescription(String paymentReference, String description);
}
