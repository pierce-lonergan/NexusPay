package io.nexuspay.ledger.adapter.out.persistence;

import io.nexuspay.ledger.application.port.FxGainLossAccountRepository;
import io.nexuspay.ledger.domain.FxGainLossAccount;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Adapter implementing FxGainLossAccountRepository using JPA.
 *
 * @since 0.3.0 (Sprint 3.2)
 */
@Repository
public class FxGainLossAccountRepositoryAdapter implements FxGainLossAccountRepository {

    private final JpaFxGainLossAccountRepository jpaRepo;

    public FxGainLossAccountRepositoryAdapter(JpaFxGainLossAccountRepository jpaRepo) {
        this.jpaRepo = jpaRepo;
    }

    @Override
    public FxGainLossAccount save(FxGainLossAccount account) {
        FxGainLossAccountEntity entity = toEntity(account);
        entity = jpaRepo.save(entity);
        return toDomain(entity);
    }

    @Override
    public Optional<FxGainLossAccount> findByTenantIdAndCurrencyPair(String tenantId, String currencyPair) {
        return jpaRepo.findByTenantIdAndCurrencyPair(tenantId, currencyPair).map(this::toDomain);
    }

    @Override
    public List<FxGainLossAccount> findByTenantId(String tenantId) {
        return jpaRepo.findByTenantId(tenantId).stream()
                .map(this::toDomain).collect(Collectors.toList());
    }

    @Override
    public Optional<FxGainLossAccount> findById(UUID id) {
        return jpaRepo.findById(id).map(this::toDomain);
    }

    private FxGainLossAccount toDomain(FxGainLossAccountEntity e) {
        return new FxGainLossAccount(
                e.getId(), e.getTenantId(), e.getCurrencyPair(),
                e.getAccountId(), e.getRealizedGainLoss(),
                e.getUnrealizedGainLoss(), e.getLastCalculatedAt()
        );
    }

    private FxGainLossAccountEntity toEntity(FxGainLossAccount a) {
        FxGainLossAccountEntity e = new FxGainLossAccountEntity();
        e.setId(a.getId());
        e.setTenantId(a.getTenantId());
        e.setCurrencyPair(a.getCurrencyPair());
        e.setAccountId(a.getAccountId());
        e.setRealizedGainLoss(a.getRealizedGainLoss());
        e.setUnrealizedGainLoss(a.getUnrealizedGainLoss());
        e.setLastCalculatedAt(a.getLastCalculatedAt());
        return e;
    }
}
