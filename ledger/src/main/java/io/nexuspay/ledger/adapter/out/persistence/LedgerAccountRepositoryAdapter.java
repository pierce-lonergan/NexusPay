package io.nexuspay.ledger.adapter.out.persistence;

import io.nexuspay.ledger.application.port.LedgerAccountRepository;
import io.nexuspay.ledger.domain.AccountType;
import io.nexuspay.ledger.domain.LedgerAccount;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class LedgerAccountRepositoryAdapter implements LedgerAccountRepository {

    private final JpaLedgerAccountRepository jpaRepository;

    public LedgerAccountRepositoryAdapter(JpaLedgerAccountRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Optional<LedgerAccount> findById(String id) {
        return jpaRepository.findById(id).map(this::toDomain);
    }

    @Override
    public Optional<LedgerAccount> findByNameAndCurrency(String name, String currency) {
        return jpaRepository.findByNameAndCurrency(name, currency).map(this::toDomain);
    }

    @Override
    public List<LedgerAccount> findAllByTenantId(String tenantId) {
        return jpaRepository.findAllByTenantId(tenantId).stream().map(this::toDomain).toList();
    }

    @Override
    public List<LedgerAccount> findAllByCurrency(String currency) {
        return jpaRepository.findAllByCurrency(currency).stream().map(this::toDomain).toList();
    }

    @Override
    public LedgerAccount save(LedgerAccount account) {
        var entity = toEntity(account);
        var saved = jpaRepository.save(entity);
        return toDomain(saved);
    }

    @Override
    public boolean updateBalanceWithVersion(String accountId, long newBalance, long expectedVersion) {
        int updated = jpaRepository.updateBalanceWithVersion(accountId, newBalance, expectedVersion, Instant.now());
        return updated > 0;
    }

    private LedgerAccount toDomain(LedgerAccountEntity entity) {
        return new LedgerAccount(
                entity.getId(),
                entity.getName(),
                AccountType.valueOf(entity.getType()),
                entity.getCurrency(),
                entity.getPostedBalance(),
                entity.getVersion(),
                entity.getTenantId(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    private LedgerAccountEntity toEntity(LedgerAccount domain) {
        return new LedgerAccountEntity(
                domain.getId(),
                domain.getName(),
                domain.getType().name(),
                domain.getCurrency(),
                domain.getPostedBalance(),
                domain.getVersion(),
                domain.getTenantId(),
                domain.getCreatedAt(),
                domain.getUpdatedAt()
        );
    }
}
