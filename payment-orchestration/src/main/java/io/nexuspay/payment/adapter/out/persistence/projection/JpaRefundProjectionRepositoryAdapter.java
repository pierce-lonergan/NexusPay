package io.nexuspay.payment.adapter.out.persistence.projection;

import io.nexuspay.payment.application.port.out.RefundProjectionRepository;
import io.nexuspay.payment.domain.projection.ProjectionStatusPrecedence;
import io.nexuspay.payment.domain.projection.RefundProjectionRow;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * GAP-076 (critique v3 F1): JPA implementation of {@link RefundProjectionRepository}. Mirrors
 * {@link JpaPaymentProjectionRepositoryAdapter}: idempotent find-then-save upsert by refund-id PK with a
 * monotonic (pending &lt; succeeded/failed) precedence guard, and tenant + livemode-scoped derived
 * finders only (incl. the {@code ?payment=} payment_id filter). NO unscoped finder.
 */
@Repository
public class JpaRefundProjectionRepositoryAdapter implements RefundProjectionRepository {

    private final JpaRefundProjectionRepo repo;

    public JpaRefundProjectionRepositoryAdapter(JpaRefundProjectionRepo repo) {
        this.repo = repo;
    }

    @Override
    public void upsert(RefundProjectionRow row) {
        Optional<RefundProjectionEntity> existing = repo.findById(row.refundId());
        if (existing.isEmpty()) {
            repo.save(toNewEntity(row));
            return;
        }
        RefundProjectionEntity e = existing.get();
        if (ProjectionStatusPrecedence.acceptRefundStatus(e.getStatus(), row.status())) {
            e.setStatus(row.status());
            e.setAmount(row.amount());
            e.setCurrency(row.currency());
            e.setReason(row.reason());
            e.setConnectorName(row.connectorName());
            e.setErrorCode(row.errorCode());
            e.setErrorMessage(row.errorMessage());
            e.setUpdatedAt(Instant.now());
            repo.save(e);
        }
    }

    @Override
    public void updateStatusIfExists(String refundId, String status) {
        repo.findById(refundId).ifPresent(e -> {
            if (ProjectionStatusPrecedence.acceptRefundStatus(e.getStatus(), status)) {
                e.setStatus(status);
                e.setUpdatedAt(Instant.now());
                repo.save(e);
            }
        });
    }

    @Override
    public List<RefundProjectionRow> listByTenant(String tenantId, boolean livemode, String paymentFilter,
                                                  String statusFilter, int limit, int offset) {
        // ABSOLUTE-offset paging (see JpaPaymentProjectionRepositoryAdapter / OffsetLimitRequest): honor an
        // arbitrary offset exactly instead of the old PageRequest.of(offset/limit, limit) page-index math.
        Pageable page = new OffsetLimitRequest(offset, limit, Sort.unsorted());
        boolean hasPayment = paymentFilter != null && !paymentFilter.isBlank();
        boolean hasStatus = statusFilter != null && !statusFilter.isBlank();

        List<RefundProjectionEntity> rows;
        if (hasPayment && hasStatus) {
            rows = repo.findByTenantIdAndLivemodeAndPaymentIdAndStatusOrderByCreatedAtDesc(
                    tenantId, livemode, paymentFilter, statusFilter, page);
        } else if (hasPayment) {
            rows = repo.findByTenantIdAndLivemodeAndPaymentIdOrderByCreatedAtDesc(
                    tenantId, livemode, paymentFilter, page);
        } else if (hasStatus) {
            rows = repo.findByTenantIdAndLivemodeAndStatusOrderByCreatedAtDesc(
                    tenantId, livemode, statusFilter, page);
        } else {
            rows = repo.findByTenantIdAndLivemodeOrderByCreatedAtDesc(tenantId, livemode, page);
        }
        return rows.stream().map(JpaRefundProjectionRepositoryAdapter::toDomain).toList();
    }

    // -- Entity <-> Domain mappers --

    private static RefundProjectionEntity toNewEntity(RefundProjectionRow r) {
        RefundProjectionEntity e = new RefundProjectionEntity();
        e.setRefundId(r.refundId());
        e.setPaymentId(r.paymentId());
        e.setTenantId(r.tenantId());
        e.setLivemode(r.livemode());
        e.setStatus(r.status());
        e.setAmount(r.amount());
        e.setCurrency(r.currency());
        e.setReason(r.reason());
        e.setConnectorName(r.connectorName());
        e.setErrorCode(r.errorCode());
        e.setErrorMessage(r.errorMessage());
        e.setCreatedAt(r.createdAt() != null ? r.createdAt() : Instant.now());
        e.setUpdatedAt(Instant.now());
        return e;
    }

    private static RefundProjectionRow toDomain(RefundProjectionEntity e) {
        return new RefundProjectionRow(
                e.getRefundId(), e.getPaymentId(), e.getTenantId(), e.isLivemode(), e.getStatus(),
                e.getAmount(), e.getCurrency(), e.getReason(), e.getConnectorName(),
                e.getErrorCode(), e.getErrorMessage(), e.getCreatedAt(), e.getUpdatedAt());
    }

    // -- Spring Data JPA interface (tenant + livemode-scoped finders only) --

    interface JpaRefundProjectionRepo extends JpaRepository<RefundProjectionEntity, String> {

        List<RefundProjectionEntity> findByTenantIdAndLivemodeOrderByCreatedAtDesc(
                String tenantId, boolean livemode, Pageable page);

        List<RefundProjectionEntity> findByTenantIdAndLivemodeAndStatusOrderByCreatedAtDesc(
                String tenantId, boolean livemode, String status, Pageable page);

        List<RefundProjectionEntity> findByTenantIdAndLivemodeAndPaymentIdOrderByCreatedAtDesc(
                String tenantId, boolean livemode, String paymentId, Pageable page);

        List<RefundProjectionEntity> findByTenantIdAndLivemodeAndPaymentIdAndStatusOrderByCreatedAtDesc(
                String tenantId, boolean livemode, String paymentId, String status, Pageable page);
    }
}
