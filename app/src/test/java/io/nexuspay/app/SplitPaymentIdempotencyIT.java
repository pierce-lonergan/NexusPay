package io.nexuspay.app;

import io.nexuspay.marketplace.application.port.in.CreateSplitPaymentUseCase;
import io.nexuspay.marketplace.application.port.in.CreateSplitPaymentUseCase.CreateSplitCommand;
import io.nexuspay.marketplace.application.port.in.CreateSplitPaymentUseCase.SplitPaymentResult;
import io.nexuspay.marketplace.application.port.in.CreateSplitPaymentUseCase.SplitRuleCommand;
import io.nexuspay.marketplace.application.port.out.MarketplaceRepository;
import io.nexuspay.marketplace.domain.ConnectedAccount;
import io.nexuspay.marketplace.domain.KycStatus;
import io.nexuspay.marketplace.domain.SplitType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SEC-20 GATE: split-payment creation is IDEMPOTENT per {@code (tenant_id, payment_id)} against a real
 * Postgres (Testcontainers + Flyway through V4034). A retried create for the same payment must NOT
 * double-create the split row tree — it returns the SAME split, and exactly one row survives.
 *
 * <p>The {@code SplitPaymentServiceTest} unit test mocks the repository, so the V4034
 * {@code uq_split_payments_tenant_payment} UNIQUE index and the read-through dedup against a live DB
 * are never executed there ({@code @Transactional(REQUIRES_NEW)} is also a no-op without a real tx
 * manager). This drives the REAL service + repository + migration end-to-end — the only test that
 * proves the UNIQUE actually exists and that a sequential retry reconciles to one row. Self-skips when
 * Docker is absent, matching the module IT convention. (True concurrent racing is non-deterministic to
 * assert in a unit test; the UNIQUE index — exercised here by the migration applying cleanly — is the
 * real concurrency backstop, with the service catch re-fetching the winner.)</p>
 */
@DisplayName("SEC-20 GATE: split-payment create is idempotent per (tenant,payment) on real Postgres")
class SplitPaymentIdempotencyIT extends IntegrationTestBase {

    @Autowired
    private CreateSplitPaymentUseCase splitPayments;

    @Autowired
    private MarketplaceRepository repository;

    private String tenant;
    private String accountA;
    private String accountB;

    @BeforeEach
    void setUp() {
        Assumptions.assumeTrue(DOCKER_AVAILABLE,
                "Docker unavailable — SEC-20 split idempotency gate self-skips (Testcontainers required)");
        tenant = "sec20-" + UUID.randomUUID();
        accountA = seedActiveAccount(tenant);
        accountB = seedActiveAccount(tenant);
    }

    private String seedActiveAccount(String tenantId) {
        ConnectedAccount account = ConnectedAccount.create(
                tenantId, "SEC-20 LLC", "sec20-" + UUID.randomUUID() + "@example.com", "US", "USD");
        account.updateKycStatus(KycStatus.VERIFIED);
        account.activate();
        return repository.saveAccount(account).getId();
    }

    @Test
    @DisplayName("a retried create for the same (tenant,payment) returns the SAME split, not a duplicate")
    void duplicateCreate_returnsSameSplit_andLeavesExactlyOneRow() {
        String paymentId = "pi_sec20_" + UUID.randomUUID();
        CreateSplitCommand cmd = new CreateSplitCommand(
                tenant, paymentId, 10000, "USD",
                List.of(
                        new SplitRuleCommand(accountA, SplitType.PERCENTAGE, 0, new BigDecimal("80")),
                        new SplitRuleCommand(accountB, SplitType.REMAINDER, 0, null)));

        SplitPaymentResult first = splitPayments.createSplitPayment(cmd);
        SplitPaymentResult retry = splitPayments.createSplitPayment(cmd); // same (tenant, payment)

        assertThat(retry.splitPaymentId())
                .as("the retry must return the existing split id (read-through dedup), not a new one")
                .isEqualTo(first.splitPaymentId());
        assertThat(retry.rules())
                .as("the retry must not append duplicate legs")
                .hasSameSizeAs(first.rules());
        assertThat(retry.totalAmount()).isEqualTo(first.totalAmount());

        // findByTenantIdAndPaymentId returns an Optional (single) — a second persisted row would make
        // this throw NonUniqueResult; presence + matching id proves exactly one split survived.
        assertThat(repository.findSplitPaymentByTenantAndPaymentId(tenant, paymentId))
                .as("exactly one split_payments row exists for the (tenant, payment) after the retry")
                .isPresent()
                .get()
                .satisfies(sp -> assertThat(sp.getId()).isEqualTo(first.splitPaymentId()));
    }

    @Test
    @DisplayName("distinct payments under the same tenant each create their own split")
    void distinctPayments_createSeparateSplits() {
        CreateSplitCommand a = new CreateSplitCommand(
                tenant, "pi_sec20_a_" + UUID.randomUUID(), 10000, "USD",
                List.of(new SplitRuleCommand(accountA, SplitType.REMAINDER, 0, null)));
        CreateSplitCommand b = new CreateSplitCommand(
                tenant, "pi_sec20_b_" + UUID.randomUUID(), 10000, "USD",
                List.of(new SplitRuleCommand(accountA, SplitType.REMAINDER, 0, null)));

        SplitPaymentResult ra = splitPayments.createSplitPayment(a);
        SplitPaymentResult rb = splitPayments.createSplitPayment(b);

        assertThat(rb.splitPaymentId())
                .as("a different payment_id must yield a distinct split (the UNIQUE is on the pair)")
                .isNotEqualTo(ra.splitPaymentId());
    }
}
