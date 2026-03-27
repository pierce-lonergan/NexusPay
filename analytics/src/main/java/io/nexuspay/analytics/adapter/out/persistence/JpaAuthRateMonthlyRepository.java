package io.nexuspay.analytics.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface JpaAuthRateMonthlyRepository extends JpaRepository<AuthRateMonthlyEntity, UUID> {

    List<AuthRateMonthlyEntity> findByTenantIdAndBucketMonthBetween(
            String tenantId, LocalDate from, LocalDate to);

    List<AuthRateMonthlyEntity> findByTenantIdAndBucketMonthBetweenAndPspConnector(
            String tenantId, LocalDate from, LocalDate to, String pspConnector);
}
