package io.nexuspay.app;

import io.nexuspay.iam.adapter.out.persistence.JpaPendingApprovalRepository;
import io.nexuspay.iam.adapter.out.persistence.PendingApprovalEntity;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * GAP-028: DB-level maker-checker — the {@code chk_no_self_approval} CHECK constraint (V4044) makes
 * self-approval STRUCTURALLY impossible, not merely application-prevented. This IT proves the
 * constraint FIRES against a REAL Postgres (Flyway V4044 applied) even for a direct persistence
 * write that bypasses {@code ApprovalService}/{@code B2bApprovalService} entirely — the
 * defense-in-depth proof a mock cannot give.
 *
 * <ul>
 *   <li>A save with {@code reviewed_by == requested_by} throws {@link DataIntegrityViolationException}
 *       (constraint rejects self-approval).</li>
 *   <li>A PENDING row ({@code reviewed_by} NULL) persists — {@code IS DISTINCT FROM NULL} passes.</li>
 *   <li>A reviewed row with {@code reviewed_by != requested_by} persists — legitimate maker-checker.</li>
 * </ul>
 *
 * The application-level checks are UNTOUCHED (belt AND suspenders); this only asserts the storage
 * layer is now itself a control. Self-skips when Docker is unavailable (Testcontainers required).
 */
@DisplayName("GAP-028: pending_approvals chk_no_self_approval DB constraint fires on self-approval")
class PendingApprovalSelfApprovalConstraintIT extends IntegrationTestBase {

    private static final String TENANT = "default";

    @Autowired
    private JpaPendingApprovalRepository approvalRepository;

    @BeforeEach
    void requireDocker() {
        Assumptions.assumeTrue(DOCKER_AVAILABLE,
                "Docker unavailable — GAP-028 constraint IT self-skips (Testcontainers required)");
    }

    private PendingApprovalEntity entity(String id, String requestedBy, String reviewedBy,
                                         String status, Instant reviewedAt) {
        return new PendingApprovalEntity(
                id, "refund", "Refund", "rf_" + id,
                Map.of("payment_id", "pay_" + id), status,
                requestedBy, reviewedBy, TENANT, Instant.now(), reviewedAt);
    }

    @Test
    @DisplayName("direct save with reviewed_by == requested_by throws DataIntegrityViolationException")
    void selfApprovalWriteRejectedByDbConstraint() {
        PendingApprovalEntity selfApproved = entity(
                "gap028selfapprove", "alice", "alice", "APPROVED", Instant.now());

        assertThatThrownBy(() -> approvalRepository.saveAndFlush(selfApproved))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("PENDING row (reviewed_by NULL) persists — IS DISTINCT FROM NULL passes")
    void pendingRowWithNullReviewerPersists() {
        PendingApprovalEntity pending = entity(
                "gap028pending", "alice", null, "PENDING", null);

        PendingApprovalEntity saved = approvalRepository.saveAndFlush(pending);

        assertThat(saved.getId()).isEqualTo("gap028pending");
        assertThat(saved.getReviewedBy()).isNull();
    }

    @Test
    @DisplayName("reviewed_by != requested_by persists — legitimate maker-checker allowed")
    void distinctReviewerPersists() {
        PendingApprovalEntity reviewed = entity(
                "gap028distinct", "alice", "bob", "APPROVED", Instant.now());

        PendingApprovalEntity saved = approvalRepository.saveAndFlush(reviewed);

        assertThat(saved.getRequestedBy()).isEqualTo("alice");
        assertThat(saved.getReviewedBy()).isEqualTo("bob");
    }
}
