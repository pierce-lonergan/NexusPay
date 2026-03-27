package io.nexuspay.analytics.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface JpaAuthRateDailyRepository extends JpaRepository<AuthRateDailyEntity, UUID> {

    List<AuthRateDailyEntity> findByTenantIdAndBucketDateBetween(
            String tenantId, LocalDate from, LocalDate to);

    List<AuthRateDailyEntity> findByTenantIdAndBucketDateBetweenAndPspConnector(
            String tenantId, LocalDate from, LocalDate to, String pspConnector);
}
