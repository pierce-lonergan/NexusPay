package io.nexuspay.payment.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link CaptureHoldEntity} (B-024/B-027 capture holds).
 */
public interface CaptureHoldRepository extends JpaRepository<CaptureHoldEntity, String> {
}
