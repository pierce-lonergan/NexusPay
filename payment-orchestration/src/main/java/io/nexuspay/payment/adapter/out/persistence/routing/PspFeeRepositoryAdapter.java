package io.nexuspay.payment.adapter.out.persistence.routing;

import io.nexuspay.payment.application.port.routing.PspFeeRepository;
import io.nexuspay.payment.domain.routing.PspFeeModel;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Adapter implementing PspFeeRepository port via JPA.
 *
 * @since 0.3.0 (Sprint 3.3)
 */
@Component
public class PspFeeRepositoryAdapter implements PspFeeRepository {

    private final JpaPspFeeModelRepository jpaRepo;

    public PspFeeRepositoryAdapter(JpaPspFeeModelRepository jpaRepo) {
        this.jpaRepo = jpaRepo;
    }

    @Override
    public PspFeeModel save(PspFeeModel model) {
        PspFeeModelEntity entity = toEntity(model);
        PspFeeModelEntity saved = jpaRepo.save(entity);
        return toDomain(saved);
    }

    @Override
    public Optional<PspFeeModel> findById(UUID id) {
        return jpaRepo.findById(id).map(this::toDomain);
    }

    @Override
    public List<PspFeeModel> findByTenantAndCurrency(String tenantId, String currency) {
        return jpaRepo.findByTenantIdAndCurrency(tenantId, currency).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public Optional<PspFeeModel> findEffective(String tenantId, String pspConnector, String currency, LocalDate date) {
        return jpaRepo.findEffective(tenantId, pspConnector, currency, date)
                .map(this::toDomain);
    }

    @Override
    public List<PspFeeModel> findByTenantId(String tenantId) {
        return jpaRepo.findByTenantId(tenantId).stream()
                .map(this::toDomain)
                .toList();
    }

    private PspFeeModelEntity toEntity(PspFeeModel model) {
        PspFeeModelEntity entity = new PspFeeModelEntity();
        entity.setId(model.id());
        entity.setTenantId(model.tenantId());
        entity.setPspConnector(model.pspConnector());
        entity.setFeeType(model.feeType().name());
        entity.setPerTxFee(model.perTxFee());
        entity.setPercentageFee(model.percentageFee());
        entity.setInterchangeMarkupBps(model.interchangeMarkupBps());
        entity.setSchemeFeeBps(model.schemeFeeBps());
        entity.setCurrency(model.currency());
        entity.setEffectiveFrom(model.effectiveFrom());
        entity.setEffectiveTo(model.effectiveTo());
        return entity;
    }

    private PspFeeModel toDomain(PspFeeModelEntity entity) {
        return new PspFeeModel(
                entity.getId(),
                entity.getTenantId(),
                entity.getPspConnector(),
                PspFeeModel.FeeType.valueOf(entity.getFeeType()),
                entity.getPerTxFee(),
                entity.getPercentageFee(),
                entity.getInterchangeMarkupBps() != null ? entity.getInterchangeMarkupBps() : 0,
                entity.getSchemeFeeBps() != null ? entity.getSchemeFeeBps() : 0,
                entity.getCurrency(),
                entity.getEffectiveFrom(),
                entity.getEffectiveTo()
        );
    }
}
