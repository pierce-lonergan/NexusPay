package io.nexuspay.payment.application.port.routing;

import io.nexuspay.payment.domain.routing.PspFeeModel;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Outbound port for PSP fee model data.
 *
 * @since 0.3.0 (Sprint 3.3)
 */
public interface PspFeeRepository {

    PspFeeModel save(PspFeeModel model);

    Optional<PspFeeModel> findById(UUID id);

    List<PspFeeModel> findByTenantAndCurrency(String tenantId, String currency);

    Optional<PspFeeModel> findEffective(String tenantId, String pspConnector, String currency, LocalDate date);

    List<PspFeeModel> findByTenantId(String tenantId);
}
