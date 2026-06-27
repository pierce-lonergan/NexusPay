package io.nexuspay.payment.adapter.out.persistence.paymentmethod;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.nexuspay.payment.application.port.out.PaymentMethodRepository;
import io.nexuspay.payment.domain.paymentmethod.PaymentMethod;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * JPA implementation of {@link PaymentMethodRepository} (TEST-3b). Mirrors
 * {@code JpaCustomerRepositoryAdapter}.
 *
 * <p>Both finders push the {@code deleted_at IS NULL} predicate to SQL alongside the tenant predicate so
 * a detached (soft-deleted) OR foreign-tenant row never materialises — the SEC-26 no-oracle +
 * no-resurrection guarantee.</p>
 *
 * @since TEST-3b
 */
@Repository
public class JpaPaymentMethodRepositoryAdapter implements PaymentMethodRepository {

    /** Hard cap on the per-customer page size (a customer realistically has few saved methods). */
    private static final int MAX_LIST = 100;

    private final JpaPaymentMethodRepo jpaPaymentMethodRepo;
    private final ObjectMapper objectMapper;

    public JpaPaymentMethodRepositoryAdapter(JpaPaymentMethodRepo jpaPaymentMethodRepo,
                                             ObjectMapper objectMapper) {
        this.jpaPaymentMethodRepo = jpaPaymentMethodRepo;
        this.objectMapper = objectMapper;
    }

    @Override
    public PaymentMethod save(PaymentMethod paymentMethod) {
        jpaPaymentMethodRepo.save(toEntity(paymentMethod));
        return paymentMethod;
    }

    @Override
    public Optional<PaymentMethod> findByIdAndTenantId(String id, String tenantId) {
        // SEC-26: tenant + not-deleted predicates pushed to SQL — a foreign-tenant or detached row never
        // materialises (no existence oracle, no resurrection).
        return jpaPaymentMethodRepo.findByIdAndTenantIdAndDeletedAtIsNull(id, tenantId).map(this::toDomain);
    }

    @Override
    public List<PaymentMethod> findByCustomerAndTenant(String customerId, String tenantId,
                                                       int limit, int offset) {
        // Clamp to a sane, bounded page (mirror CustomerService's defaults applied at the controller) so a
        // bogus limit/offset can't request an unbounded or negative page.
        int pageSize = Math.min(Math.max(limit, 1), MAX_LIST);
        int safeOffset = Math.max(offset, 0);
        return jpaPaymentMethodRepo
                .findByCustomerIdAndTenantIdAndDeletedAtIsNullOrderByCreatedAtDesc(
                        customerId, tenantId, PageRequest.of(safeOffset / pageSize, pageSize))
                .stream().map(this::toDomain).toList();
    }

    // -- Entity <-> Domain mappers --

    private PaymentMethodEntity toEntity(PaymentMethod pm) {
        PaymentMethodEntity e = new PaymentMethodEntity();
        e.setId(pm.getId());
        e.setTenantId(pm.getTenantId());
        e.setCustomerId(pm.getCustomerId());
        e.setLivemode(pm.isLivemode());
        e.setType(pm.getType());
        e.setBrand(pm.getBrand());
        e.setLast4(pm.getLast4());
        e.setExpMonth(pm.getExpMonth());
        e.setExpYear(pm.getExpYear());
        e.setFunding(pm.getFunding());
        e.setCredentialRef(pm.getCredentialRef());
        try {
            e.setMetadataJson(pm.getMetadata() != null
                    ? objectMapper.writeValueAsString(pm.getMetadata()) : null);
        } catch (JsonProcessingException ex) {
            e.setMetadataJson(null);
        }
        e.setCreatedAt(pm.getCreatedAt());
        e.setUpdatedAt(pm.getUpdatedAt());
        e.setDeletedAt(pm.getDeletedAt());
        return e;
    }

    private PaymentMethod toDomain(PaymentMethodEntity e) {
        PaymentMethod pm = new PaymentMethod();
        pm.setId(e.getId());
        pm.setTenantId(e.getTenantId());
        pm.setCustomerId(e.getCustomerId());
        pm.setLivemode(e.isLivemode());
        pm.setType(e.getType());
        pm.setBrand(e.getBrand());
        pm.setLast4(e.getLast4());
        pm.setExpMonth(e.getExpMonth());
        pm.setExpYear(e.getExpYear());
        pm.setFunding(e.getFunding());
        pm.setCredentialRef(e.getCredentialRef());
        try {
            pm.setMetadata(e.getMetadataJson() != null
                    ? objectMapper.readValue(e.getMetadataJson(), new TypeReference<Map<String, Object>>() {})
                    : null);
        } catch (JsonProcessingException ex) {
            pm.setMetadata(null);
        }
        pm.setCreatedAt(e.getCreatedAt());
        pm.setUpdatedAt(e.getUpdatedAt());
        pm.setDeletedAt(e.getDeletedAt());
        return pm;
    }

    // -- Spring Data JPA interface --

    interface JpaPaymentMethodRepo extends JpaRepository<PaymentMethodEntity, String> {
        // SEC-26: tenant-scoped, soft-delete-aware by-id finder backing retrieve/detach.
        Optional<PaymentMethodEntity> findByIdAndTenantIdAndDeletedAtIsNull(String id, String tenantId);

        // SEC-26: tenant-scoped, soft-delete-aware enumeration of a customer's saved methods, newest first.
        List<PaymentMethodEntity> findByCustomerIdAndTenantIdAndDeletedAtIsNullOrderByCreatedAtDesc(
                String customerId, String tenantId, PageRequest page);
    }
}
