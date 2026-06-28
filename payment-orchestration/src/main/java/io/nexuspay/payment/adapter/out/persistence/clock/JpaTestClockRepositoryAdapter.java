package io.nexuspay.payment.adapter.out.persistence.clock;

import io.nexuspay.payment.application.port.out.TestClockRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

/**
 * GAP-078 (critique v3 F5): JPA implementation of {@link TestClockRepository} over the {@code test_clocks}
 * table (V4042). Mirrors {@code JpaPaymentProjectionRepositoryAdapter}: an inner Spring-Data repo with a
 * find-then-save upsert. The PK is {@code tenant_id}, so the by-id read IS the tenant scope; there is NO
 * unscoped finder.
 *
 * @since GAP-078
 */
@Repository
public class JpaTestClockRepositoryAdapter implements TestClockRepository {

    private final JpaTestClockRepo repo;

    public JpaTestClockRepositoryAdapter(JpaTestClockRepo repo) {
        this.repo = repo;
    }

    @Override
    public Optional<Instant> findByTenantId(String tenantId) {
        return repo.findById(tenantId).map(TestClockEntity::getFixedAt);
    }

    @Override
    public void upsert(String tenantId, Instant fixedAt) {
        Instant now = Instant.now();
        TestClockEntity entity = repo.findById(tenantId).orElseGet(() -> {
            TestClockEntity e = new TestClockEntity();
            e.setTenantId(tenantId);
            e.setCreatedAt(now); // set once on insert (updatable=false thereafter)
            return e;
        });
        entity.setFixedAt(fixedAt);
        entity.setUpdatedAt(now);
        repo.save(entity);
    }

    @Override
    public void deleteByTenantId(String tenantId) {
        repo.deleteById(tenantId);
    }

    /** Spring Data JPA interface (PK = tenant_id; the by-id finder IS the tenant scope; no unscoped finder). */
    interface JpaTestClockRepo extends JpaRepository<TestClockEntity, String> {
    }
}
