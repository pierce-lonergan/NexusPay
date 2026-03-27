package io.nexuspay.analytics.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface JpaRevenueDailyRepository extends JpaRepository<RevenueDailyEntity, UUID> {

    List<RevenueDailyEntity> findByTenantIdAndBucketDateBetween(
            String tenantId, LocalDate from, LocalDate to);
}
