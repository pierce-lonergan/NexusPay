package io.nexuspay.ledger.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface JpaJournalEntryRepository extends JpaRepository<JournalEntryEntity, String> {

    List<JournalEntryEntity> findByPaymentReference(String paymentReference);

    @Query("SELECT j FROM JournalEntryEntity j WHERE j.postedAt >= :from AND j.postedAt <= :to " +
           "ORDER BY j.postedAt DESC")
    List<JournalEntryEntity> findByDateRange(@Param("from") Instant from,
                                              @Param("to") Instant to,
                                              org.springframework.data.domain.Pageable pageable);

    boolean existsByPaymentReferenceAndDescription(String paymentReference, String description);
}
