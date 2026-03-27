package io.nexuspay.b2b.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface JpaVirtualCardRepository extends JpaRepository<VirtualCardEntity, String> {
    List<VirtualCardEntity> findByTenantId(String tenantId);
}
