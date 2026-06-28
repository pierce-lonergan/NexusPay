package io.nexuspay.payment.adapter.out.persistence.mandate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.nexuspay.payment.application.port.out.MandateRepository;
import io.nexuspay.payment.domain.mandate.Mandate;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * JPA implementation of {@link MandateRepository} (TEST-3d). Mirrors
 * {@code JpaPaymentMethodRepositoryAdapter}.
 *
 * <p>Both finders push the {@code tenant_id} predicate to SQL so a foreign-tenant row never materialises —
 * the SEC-26 no-oracle guarantee. Unlike the payment_methods adapter there is NO {@code DeletedAtIsNull}
 * predicate: a revoked (INACTIVE) mandate MUST stay retrievable.</p>
 *
 * @since TEST-3d
 */
@Repository
public class JpaMandateRepositoryAdapter implements MandateRepository {

    /** Hard cap on the per-tenant page size. */
    private static final int MAX_LIST = 100;

    private final JpaMandateRepo jpaMandateRepo;
    private final ObjectMapper objectMapper;

    public JpaMandateRepositoryAdapter(JpaMandateRepo jpaMandateRepo, ObjectMapper objectMapper) {
        this.jpaMandateRepo = jpaMandateRepo;
        this.objectMapper = objectMapper;
    }

    @Override
    public Mandate save(Mandate mandate) {
        jpaMandateRepo.save(toEntity(mandate));
        return mandate;
    }

    @Override
    public Optional<Mandate> findByIdAndTenantId(String id, String tenantId) {
        // SEC-26: tenant predicate pushed to SQL — a foreign-tenant row never materialises (no existence
        // oracle). NO deleted/status filter: a revoked (INACTIVE) mandate stays retrievable.
        return jpaMandateRepo.findByIdAndTenantId(id, tenantId).map(this::toDomain);
    }

    @Override
    public List<Mandate> findByTenant(String tenantId, int limit, int offset) {
        // Clamp to a sane, bounded page so a bogus limit/offset can't request an unbounded or negative page.
        int pageSize = Math.min(Math.max(limit, 1), MAX_LIST);
        int safeOffset = Math.max(offset, 0);
        return jpaMandateRepo
                .findByTenantIdOrderByCreatedAtDesc(tenantId, PageRequest.of(safeOffset / pageSize, pageSize))
                .stream().map(this::toDomain).toList();
    }

    // -- Entity <-> Domain mappers --

    private MandateEntity toEntity(Mandate m) {
        MandateEntity e = new MandateEntity();
        e.setId(m.getId());
        e.setTenantId(m.getTenantId());
        e.setCustomerId(m.getCustomerId());
        e.setPaymentMethodId(m.getPaymentMethodId());
        e.setStatus(m.getStatus());
        e.setType(m.getType());
        e.setScenario(m.getScenario());
        e.setLivemode(m.isLivemode());
        try {
            e.setMetadataJson(m.getMetadata() != null
                    ? objectMapper.writeValueAsString(m.getMetadata()) : null);
        } catch (JsonProcessingException ex) {
            e.setMetadataJson(null);
        }
        e.setCreatedAt(m.getCreatedAt());
        e.setUpdatedAt(m.getUpdatedAt());
        e.setRevokedAt(m.getRevokedAt());
        return e;
    }

    private Mandate toDomain(MandateEntity e) {
        Mandate m = new Mandate();
        m.setId(e.getId());
        m.setTenantId(e.getTenantId());
        m.setCustomerId(e.getCustomerId());
        m.setPaymentMethodId(e.getPaymentMethodId());
        m.setStatus(e.getStatus());
        m.setType(e.getType());
        m.setScenario(e.getScenario());
        m.setLivemode(e.isLivemode());
        try {
            m.setMetadata(e.getMetadataJson() != null
                    ? objectMapper.readValue(e.getMetadataJson(), new TypeReference<Map<String, Object>>() {})
                    : null);
        } catch (JsonProcessingException ex) {
            m.setMetadata(null);
        }
        m.setCreatedAt(e.getCreatedAt());
        m.setUpdatedAt(e.getUpdatedAt());
        m.setRevokedAt(e.getRevokedAt());
        return m;
    }

    // -- Spring Data JPA interface --

    interface JpaMandateRepo extends JpaRepository<MandateEntity, String> {
        // SEC-26: tenant-scoped by-id finder backing retrieve/revoke + the off-session charge gate. NO
        // DeletedAtIsNull — a revoked (INACTIVE) mandate stays retrievable (the divergence from the pm repo).
        Optional<MandateEntity> findByIdAndTenantId(String id, String tenantId);

        // SEC-26: tenant-scoped enumeration of a tenant's mandates, newest first.
        List<MandateEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId, PageRequest page);
    }
}
