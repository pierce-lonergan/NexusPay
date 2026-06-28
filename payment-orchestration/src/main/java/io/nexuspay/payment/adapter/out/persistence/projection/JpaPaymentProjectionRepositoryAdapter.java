package io.nexuspay.payment.adapter.out.persistence.projection;

import io.nexuspay.payment.application.port.out.PaymentProjectionRepository;
import io.nexuspay.payment.domain.projection.PaymentProjectionRow;
import io.nexuspay.payment.domain.projection.ProjectionStatusPrecedence;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * GAP-076 (critique v3 F1): JPA implementation of {@link PaymentProjectionRepository}. Mirrors
 * {@code JpaCustomerRepositoryAdapter}: an inner Spring-Data repo with tenant + livemode-scoped derived
 * finders, plus the idempotent find-then-save upsert.
 *
 * <p>The upsert reads the existing row by PK and either INSERTs (new) or UPDATEs the mutable columns
 * guarded by {@link ProjectionStatusPrecedence} (a late/out-of-order write never regresses a terminal
 * status; {@code created_at} is preserved from the first write). The PK collapses any
 * create-retry/sync+webhook race onto ONE row (no duplicates).</p>
 *
 * <p>Every list finder pushes (tenant_id, livemode [, status][, customer_id]) to SQL — a foreign-tenant
 * or wrong-livemode row never materialises (no IDOR, no count leak). There is NO unscoped finder.</p>
 */
@Repository
public class JpaPaymentProjectionRepositoryAdapter implements PaymentProjectionRepository {

    private final JpaPaymentProjectionRepo repo;

    public JpaPaymentProjectionRepositoryAdapter(JpaPaymentProjectionRepo repo) {
        this.repo = repo;
    }

    @Override
    public void upsert(PaymentProjectionRow row) {
        Optional<PaymentProjectionEntity> existing = repo.findById(row.paymentId());
        if (existing.isEmpty()) {
            repo.save(toNewEntity(row));
            return;
        }
        PaymentProjectionEntity e = existing.get();
        // Idempotency + no-regression: only advance status when precedence allows; never overwrite a
        // terminal status with a different one. created_at + tenant_id + livemode stay from the first write.
        if (ProjectionStatusPrecedence.acceptPaymentStatus(e.getStatus(), row.status())) {
            e.setStatus(row.status());
            e.setAmount(row.amount());
            e.setCurrency(row.currency());
            e.setCaptureMethod(row.captureMethod());
            e.setCustomerId(row.customerId());
            e.setConnectorName(row.connectorName());
            e.setErrorCode(row.errorCode());
            e.setErrorMessage(row.errorMessage());
            e.setUpdatedAt(Instant.now());
            repo.save(e);
        }
        // else: a stale/out-of-order write — drop it silently (the row already holds a >= state).
    }

    @Override
    public void updateStatusIfExists(String paymentId, String tenantId, String status, boolean livemode) {
        // Update-if-exists: a missing row (forward-fill gap) is intentionally NOT created here (the {id}
        // endpoint serves it from HyperSwitch). When the row exists, advance precedence-guarded.
        repo.findById(paymentId).ifPresent(e -> {
            if (ProjectionStatusPrecedence.acceptPaymentStatus(e.getStatus(), status)) {
                e.setStatus(status);
                e.setUpdatedAt(Instant.now());
                repo.save(e);
            }
        });
    }

    @Override
    public List<PaymentProjectionRow> listByTenant(String tenantId, boolean livemode, String statusFilter,
                                                   String customerFilter, int limit, int offset) {
        // ABSOLUTE-offset paging: PageRequest.of(page, size) takes a PAGE INDEX (offset = page*size), so the
        // old PageRequest.of(offset/limit, limit) only honored multiple-of-limit offsets and otherwise
        // skipped+duplicated rows. OffsetLimitRequest reports getOffset()==offset exactly. The created_at
        // DESC ordering comes from the derived finder name, so the Pageable's own sort stays unsorted.
        Pageable page = new OffsetLimitRequest(offset, limit, Sort.unsorted());
        boolean hasStatus = statusFilter != null && !statusFilter.isBlank();
        boolean hasCustomer = customerFilter != null && !customerFilter.isBlank();

        List<PaymentProjectionEntity> rows;
        if (hasStatus && hasCustomer) {
            rows = repo.findByTenantIdAndLivemodeAndStatusAndCustomerIdOrderByCreatedAtDesc(
                    tenantId, livemode, statusFilter, customerFilter, page);
        } else if (hasStatus) {
            rows = repo.findByTenantIdAndLivemodeAndStatusOrderByCreatedAtDesc(
                    tenantId, livemode, statusFilter, page);
        } else if (hasCustomer) {
            rows = repo.findByTenantIdAndLivemodeAndCustomerIdOrderByCreatedAtDesc(
                    tenantId, livemode, customerFilter, page);
        } else {
            rows = repo.findByTenantIdAndLivemodeOrderByCreatedAtDesc(tenantId, livemode, page);
        }
        return rows.stream().map(JpaPaymentProjectionRepositoryAdapter::toDomain).toList();
    }

    // -- Entity <-> Domain mappers --

    private static PaymentProjectionEntity toNewEntity(PaymentProjectionRow r) {
        PaymentProjectionEntity e = new PaymentProjectionEntity();
        e.setPaymentId(r.paymentId());
        e.setTenantId(r.tenantId());
        e.setLivemode(r.livemode());
        e.setStatus(r.status());
        e.setAmount(r.amount());
        e.setCurrency(r.currency());
        e.setCaptureMethod(r.captureMethod());
        e.setCustomerId(r.customerId());
        e.setConnectorName(r.connectorName());
        e.setErrorCode(r.errorCode());
        e.setErrorMessage(r.errorMessage());
        // created_at from the response (NOT now()); updated_at = now() on the first write.
        e.setCreatedAt(r.createdAt() != null ? r.createdAt() : Instant.now());
        e.setUpdatedAt(Instant.now());
        return e;
    }

    private static PaymentProjectionRow toDomain(PaymentProjectionEntity e) {
        return new PaymentProjectionRow(
                e.getPaymentId(), e.getTenantId(), e.isLivemode(), e.getStatus(), e.getAmount(),
                e.getCurrency(), e.getCaptureMethod(), e.getCustomerId(), e.getConnectorName(),
                e.getErrorCode(), e.getErrorMessage(), e.getCreatedAt(), e.getUpdatedAt());
    }

    // -- Spring Data JPA interface (tenant + livemode-scoped finders only; NO unscoped finder) --

    interface JpaPaymentProjectionRepo extends JpaRepository<PaymentProjectionEntity, String> {

        List<PaymentProjectionEntity> findByTenantIdAndLivemodeOrderByCreatedAtDesc(
                String tenantId, boolean livemode, Pageable page);

        List<PaymentProjectionEntity> findByTenantIdAndLivemodeAndStatusOrderByCreatedAtDesc(
                String tenantId, boolean livemode, String status, Pageable page);

        List<PaymentProjectionEntity> findByTenantIdAndLivemodeAndCustomerIdOrderByCreatedAtDesc(
                String tenantId, boolean livemode, String customerId, Pageable page);

        List<PaymentProjectionEntity> findByTenantIdAndLivemodeAndStatusAndCustomerIdOrderByCreatedAtDesc(
                String tenantId, boolean livemode, String status, String customerId, Pageable page);
    }
}
