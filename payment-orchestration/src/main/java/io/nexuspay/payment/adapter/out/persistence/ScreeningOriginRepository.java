package io.nexuspay.payment.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link ScreeningOriginEntity} (B-029 originating screening context).
 */
public interface ScreeningOriginRepository extends JpaRepository<ScreeningOriginEntity, String> {
}
