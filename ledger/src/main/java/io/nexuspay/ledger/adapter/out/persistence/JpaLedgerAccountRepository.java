package io.nexuspay.ledger.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface JpaLedgerAccountRepository extends JpaRepository<LedgerAccountEntity, String> {

    Optional<LedgerAccountEntity> findByNameAndCurrency(String name, String currency);

    List<LedgerAccountEntity> findAllByTenantId(String tenantId);

    List<LedgerAccountEntity> findAllByCurrency(String currency);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE LedgerAccountEntity a SET a.postedBalance = :newBalance, " +
           "a.version = a.version + 1, " +
           "a.updatedAt = :now WHERE a.id = :id AND a.version = :expectedVersion")
    int updateBalanceWithVersion(@Param("id") String id,
                                 @Param("newBalance") long newBalance,
                                 @Param("expectedVersion") long expectedVersion,
                                 @Param("now") Instant now);

    @Query("SELECT a.id, COALESCE(SUM(p.amount), 0) FROM LedgerAccountEntity a " +
           "LEFT JOIN PostingEntity p ON p.ledgerAccountId = a.id " +
           "GROUP BY a.id")
    List<Object[]> computeBalancesFromPostings();
}
