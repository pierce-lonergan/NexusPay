package io.nexuspay.app;

import io.nexuspay.marketplace.application.port.out.MarketplaceRepository;
import io.nexuspay.marketplace.domain.ConnectedAccount;
import io.nexuspay.marketplace.domain.KycStatus;
import io.nexuspay.marketplace.domain.Payout;
import io.nexuspay.marketplace.domain.PayoutMethod;
import io.nexuspay.marketplace.domain.PayoutStatus;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SEC-25: integration test driving the REAL {@code JpaPayoutRepository} reconciler SQL (via the
 * {@link MarketplaceRepository} port) against a real Postgres (Testcontainers + Flyway V4032).
 *
 * <p>The {@code PayoutReconcilerTest} mocks the service and re-implements the guards in Java, so the
 * actual no-double-finalize mechanism — the {@code WHERE status='PROCESSING' AND tenant_id=...}
 * conditional UPDATEs, the {@code processing_since} stamp in {@code claimForProcessing}, the
 * {@code COALESCE(processing_since,'epoch')} legacy-row finder, and the {@code failure_reason}
 * VARCHAR(256) width that bounds the FAILED reason — is never executed there. This exercises it on a
 * real DB. Self-skips when Docker is absent (Testcontainers), matching the module's IT convention.</p>
 */
@DisplayName("SEC-25 GATE: payout reconciler conditional-UPDATE SQL against real Postgres")
class PayoutReconcileRepositoryIntegrationTest extends IntegrationTestBase {

    private static final int MAX_ATTEMPTS = 5;
    private static final int BATCH = 100;

    @Autowired
    private MarketplaceRepository repository;

    private String accountIdA;
    private String tenantA;
    private String tenantB;

    @BeforeEach
    void setUp() {
        Assumptions.assumeTrue(DOCKER_AVAILABLE,
                "Docker unavailable — SEC-25 reconciler SQL gate self-skips (Testcontainers required)");
        tenantA = "tenantA-" + UUID.randomUUID();
        tenantB = "tenantB-" + UUID.randomUUID();
        accountIdA = seedActiveAccount(tenantA);
    }

    private String seedActiveAccount(String tenant) {
        ConnectedAccount account = ConnectedAccount.create(
                tenant, "SEC-25 LLC", "sec25-" + UUID.randomUUID() + "@example.com", "US", "USD");
        account.updateKycStatus(KycStatus.VERIFIED);
        account.activate();
        return repository.saveAccount(account).getId();
    }

    /** Seeds a row already in PROCESSING with a chosen processing_since (or NULL for a legacy row). */
    private String seedProcessing(String tenant, String accountId, Instant processingSince) {
        Payout p = Payout.create(accountId, tenant, 100_000L, "USD", PayoutMethod.BANK_TRANSFER);
        p.setStatus(PayoutStatus.PROCESSING);
        p.setProcessingSince(processingSince);
        return repository.savePayout(p).getId();
    }

    // ---- claimForProcessing stamps processing_since --------------------------------------------

    @Test
    @DisplayName("claimForProcessing flips PENDING->PROCESSING exactly once and stamps processing_since")
    void claimStampsProcessingSinceAndIsAtomic() {
        Payout pending = Payout.create(accountIdA, tenantA, 100_000L, "USD", PayoutMethod.BANK_TRANSFER);
        pending.schedule(Instant.now().minusSeconds(60));
        String id = repository.savePayout(pending).getId();

        assertThat(repository.claimPayoutForProcessing(id))
                .as("first claim wins").isTrue();
        assertThat(repository.claimPayoutForProcessing(id))
                .as("second claim affects 0 rows (no longer PENDING)").isFalse();

        Payout claimed = repository.findPayoutById(id, tenantA).orElseThrow();
        assertThat(claimed.getStatus()).isEqualTo(PayoutStatus.PROCESSING);
        assertThat(claimed.getProcessingSince())
                .as("processing_since stamped by the claim UPDATE (the reconciler's stuck clock)")
                .isNotNull();
    }

    // ---- findStuckProcessing: threshold + legacy NULL (COALESCE epoch) -------------------------

    @Test
    @DisplayName("findStuckProcessing selects rows past the cutoff, including legacy NULL processing_since")
    void findStuckSelectsAgedAndLegacyRows() {
        String aged = seedProcessing(tenantA, accountIdA, Instant.now().minus(10, ChronoUnit.MINUTES));
        String fresh = seedProcessing(tenantA, accountIdA, Instant.now().minusSeconds(30));
        String legacyNull = seedProcessing(tenantA, accountIdA, null); // pre-SEC-25 stranded row

        Instant now = Instant.now();
        Instant cutoff = now.minus(5, ChronoUnit.MINUTES);
        List<Payout> stuck = repository.findStuckProcessingPayouts(cutoff, now, MAX_ATTEMPTS, BATCH);

        assertThat(stuck).extracting(Payout::getId)
                .as("aged + legacy-NULL (treated as infinitely old) are re-drivable; fresh is not")
                .contains(aged, legacyNull)
                .doesNotContain(fresh);
    }

    @Test
    @DisplayName("findStuckProcessing excludes attempts-exhausted rows; the exhausted finder surfaces them")
    void exhaustedRowsExcludedFromReDriveButSurfaced() {
        String exhausted = seedProcessing(tenantA, accountIdA, Instant.now().minus(10, ChronoUnit.MINUTES));
        // Drive reconcile_attempts to the cap via the real bookkeeping UPDATE.
        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            repository.recordPayoutReconcileFailure(exhausted, tenantA, null, "transient " + i);
        }

        Instant now = Instant.now();
        List<Payout> stuck = repository.findStuckProcessingPayouts(
                now.minus(5, ChronoUnit.MINUTES), now, MAX_ATTEMPTS, BATCH);
        assertThat(stuck).extracting(Payout::getId)
                .as("attempts >= max is excluded from re-drive (PSP not hammered)")
                .doesNotContain(exhausted);

        assertThat(repository.findExhaustedProcessingPayouts(MAX_ATTEMPTS))
                .extracting(Payout::getId)
                .as("but surfaced to the operator-signal sweep")
                .contains(exhausted);
    }

    @Test
    @DisplayName("findStuckProcessing honours the next_reconcile_at backoff gate")
    void backoffGateDefersReDrive() {
        String gated = seedProcessing(tenantA, accountIdA, Instant.now().minus(10, ChronoUnit.MINUTES));
        Instant future = Instant.now().plus(30, ChronoUnit.MINUTES);
        repository.recordPayoutReconcileFailure(gated, tenantA, future, "boom");

        Instant now = Instant.now();
        assertThat(repository.findStuckProcessingPayouts(now.minus(5, ChronoUnit.MINUTES), now, MAX_ATTEMPTS, BATCH))
                .extracting(Payout::getId)
                .as("row gated behind next_reconcile_at is not yet re-drivable")
                .doesNotContain(gated);

        Instant afterGate = future.plusSeconds(1);
        assertThat(repository.findStuckProcessingPayouts(
                afterGate.minus(5, ChronoUnit.MINUTES), afterGate, MAX_ATTEMPTS, BATCH))
                .extracting(Payout::getId)
                .as("re-drivable once the gate elapses")
                .contains(gated);
    }

    // ---- markPaid: PROCESSING gate (no double-finalize) + tenant scoping -----------------------

    @Test
    @DisplayName("markPayoutPaid flips PROCESSING->PAID once; a second call affects 0 rows")
    void markPaidIsConditionalOnProcessing() {
        String id = seedProcessing(tenantA, accountIdA, Instant.now().minus(10, ChronoUnit.MINUTES));

        assertThat(repository.markPayoutPaid(id, tenantA, "pex_ref"))
                .as("first finalize flips the row").isTrue();
        assertThat(repository.markPayoutPaid(id, tenantA, "pex_ref2"))
                .as("row no longer PROCESSING -> 0 rows; two writers can never both finalize").isFalse();

        Payout row = repository.findPayoutById(id, tenantA).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(PayoutStatus.PAID);
        assertThat(row.getExternalReference()).isEqualTo("pex_ref");
    }

    @Test
    @DisplayName("markPayoutPaid with the wrong tenant affects 0 rows (tenant clause)")
    void markPaidWrongTenantDoesNotFlip() {
        String id = seedProcessing(tenantA, accountIdA, Instant.now().minus(10, ChronoUnit.MINUTES));

        assertThat(repository.markPayoutPaid(id, tenantB, "pex_ref"))
                .as("a payout owned by tenantA must NOT be finalizable under tenantB").isFalse();

        assertThat(repository.findPayoutById(id, tenantA).orElseThrow().getStatus())
                .as("row untouched, still PROCESSING").isEqualTo(PayoutStatus.PROCESSING);
    }

    @Test
    @DisplayName("markPayoutFailed with the wrong tenant affects 0 rows (tenant clause)")
    void markFailedWrongTenantDoesNotFlip() {
        String id = seedProcessing(tenantA, accountIdA, Instant.now().minus(10, ChronoUnit.MINUTES));

        assertThat(repository.markPayoutFailed(id, tenantB, "account closed"))
                .as("wrong-tenant FAILED transition must not flip").isFalse();

        assertThat(repository.findPayoutById(id, tenantA).orElseThrow().getStatus())
                .isEqualTo(PayoutStatus.PROCESSING);
    }

    // ---- failure_reason column width (SHOULD_FIX #1) on a real DB ------------------------------

    @Test
    @DisplayName("markPayoutFailed persists a reason capped to 256 chars without a value-too-long error")
    void markFailedReasonAtColumnWidthPersistsCleanly() {
        String id = seedProcessing(tenantA, accountIdA, Instant.now().minus(10, ChronoUnit.MINUTES));
        // The service caps the FAILED reason to 256 before this call; a 256-char value is the boundary
        // the real failure_reason VARCHAR(256) column must accept. Anything longer would throw.
        String maxWidth = "x".repeat(256);

        assertThat(repository.markPayoutFailed(id, tenantA, maxWidth))
                .as("a 256-char reason fits the column and flips the row").isTrue();

        Payout row = repository.findPayoutById(id, tenantA).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(PayoutStatus.FAILED);
        assertThat(row.getFailureReason()).hasSize(256);
    }
}
