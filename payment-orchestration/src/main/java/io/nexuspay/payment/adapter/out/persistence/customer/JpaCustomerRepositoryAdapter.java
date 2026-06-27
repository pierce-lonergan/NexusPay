package io.nexuspay.payment.adapter.out.persistence.customer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.nexuspay.payment.application.port.out.CustomerRepository;
import io.nexuspay.payment.domain.customer.Customer;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * JPA implementation of {@link CustomerRepository} (TEST-3a). Mirrors {@code JpaDisputeRepositoryAdapter}.
 *
 * <p>Both finders push the {@code deleted_at IS NULL} predicate to SQL alongside the tenant predicate so
 * a soft-deleted OR foreign-tenant row never materialises — the SEC-26 no-oracle + no-resurrection
 * guarantee.</p>
 *
 * @since TEST-3a
 */
@Repository
public class JpaCustomerRepositoryAdapter implements CustomerRepository {

    private final JpaCustomerRepo jpaCustomerRepo;
    private final ObjectMapper objectMapper;

    public JpaCustomerRepositoryAdapter(JpaCustomerRepo jpaCustomerRepo, ObjectMapper objectMapper) {
        this.jpaCustomerRepo = jpaCustomerRepo;
        this.objectMapper = objectMapper;
    }

    @Override
    public Customer save(Customer customer) {
        jpaCustomerRepo.save(toEntity(customer));
        return customer;
    }

    @Override
    public Optional<Customer> findByIdAndTenantId(String id, String tenantId) {
        // SEC-26: tenant + not-deleted predicates pushed to SQL — a foreign-tenant or soft-deleted row
        // never materialises (no existence oracle, no resurrection).
        return jpaCustomerRepo.findByIdAndTenantIdAndDeletedAtIsNull(id, tenantId).map(this::toDomain);
    }

    @Override
    public List<Customer> findByTenant(String tenantId, int limit, int offset) {
        return jpaCustomerRepo
                .findByTenantIdAndDeletedAtIsNullOrderByCreatedAtDesc(
                        tenantId, PageRequest.of(offset / limit, limit))
                .stream().map(this::toDomain).toList();
    }

    // -- Entity <-> Domain mappers --

    private CustomerEntity toEntity(Customer c) {
        CustomerEntity e = new CustomerEntity();
        e.setId(c.getId());
        e.setTenantId(c.getTenantId());
        e.setLivemode(c.isLivemode());
        e.setEmail(c.getEmail());
        e.setName(c.getName());
        e.setDescription(c.getDescription());
        try {
            e.setMetadataJson(c.getMetadata() != null ? objectMapper.writeValueAsString(c.getMetadata()) : null);
        } catch (JsonProcessingException ex) {
            e.setMetadataJson(null);
        }
        e.setCreatedAt(c.getCreatedAt());
        e.setUpdatedAt(c.getUpdatedAt());
        e.setDeletedAt(c.getDeletedAt());
        return e;
    }

    private Customer toDomain(CustomerEntity e) {
        Customer c = new Customer();
        c.setId(e.getId());
        c.setTenantId(e.getTenantId());
        c.setLivemode(e.isLivemode());
        c.setEmail(e.getEmail());
        c.setName(e.getName());
        c.setDescription(e.getDescription());
        try {
            c.setMetadata(e.getMetadataJson() != null
                    ? objectMapper.readValue(e.getMetadataJson(), new TypeReference<Map<String, Object>>() {})
                    : null);
        } catch (JsonProcessingException ex) {
            c.setMetadata(null);
        }
        c.setCreatedAt(e.getCreatedAt());
        c.setUpdatedAt(e.getUpdatedAt());
        c.setDeletedAt(e.getDeletedAt());
        return c;
    }

    // -- Spring Data JPA interface --

    interface JpaCustomerRepo extends JpaRepository<CustomerEntity, String> {
        // SEC-26: tenant-scoped, soft-delete-aware by-id finder backing the REST read/mutation control.
        Optional<CustomerEntity> findByIdAndTenantIdAndDeletedAtIsNull(String id, String tenantId);

        // SEC-26: tenant-scoped, soft-delete-aware enumeration for GET /v1/customers.
        List<CustomerEntity> findByTenantIdAndDeletedAtIsNullOrderByCreatedAtDesc(String tenantId, PageRequest page);
    }
}
