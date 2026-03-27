package io.nexuspay.analytics.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface JpaDeclineDailyRepository extends JpaRepository<DeclineDailyEntity, UUID> {

    List<DeclineDailyEntity> findByTenantIdAndBucketDateBetween(
            String tenantId, LocalDate from, LocalDate to);

    List<DeclineDailyEntity> findByTenantIdAndBucketDateBetweenAndPspConnector(
            String tenantId, LocalDate from, LocalDate to, String pspConnector);
}
