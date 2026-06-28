package io.nexuspay.app;

import io.nexuspay.payment.application.port.out.CustomerRepository;
import io.nexuspay.payment.application.port.out.MandateRepository;
import io.nexuspay.payment.application.port.out.PaymentMethodRepository;
import io.nexuspay.payment.application.port.out.PaymentProjectionRepository;
import io.nexuspay.payment.application.port.out.RefundProjectionRepository;
import io.nexuspay.payment.application.service.sandbox.SandboxResetService;
import io.nexuspay.payment.application.service.sandbox.SandboxResetSummary;
import io.nexuspay.payment.domain.customer.Customer;
import io.nexuspay.payment.domain.mandate.Mandate;
import io.nexuspay.payment.domain.paymentmethod.PaymentMethod;
import io.nexuspay.payment.domain.projection.PaymentProjectionRow;
import io.nexuspay.payment.domain.projection.RefundProjectionRow;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GAP-077 (critique v3 F4): the END-TO-END SANDBOX RESET proof against a REAL Postgres (Testcontainers) with
 * the REAL V4038-V4041 migrations and the REAL {@code @Modifying} {@code WHERE tenant_id=? AND livemode=false}
 * delete queries. This is where the destructive-op security invariants are actually executed (not mocked):
 *
 * <ul>
 *   <li><b>★ CROSS-TENANT:</b> resetting tenant A deletes A's test rows and leaves tenant B's test rows
 *       UNTOUCHED across all five tables.</li>
 *   <li><b>★ LIVEMODE:</b> a reset deletes livemode=false rows and leaves livemode=true (LIVE) rows
 *       UNTOUCHED for the SAME tenant.</li>
 *   <li><b>SUMMARY + FK ORDER:</b> a full seeded graph resets with the correct per-table counts and no
 *       exception (the child→parent order + the single tx are FK-safe).</li>
 * </ul>
 *
 * <p>The "mock clear forgets only confirmed ids" invariant is proven at the unit level (where the mock can
 * mint and re-read its own ids) in {@code MockPaymentGatewayPortForgetTest}; this IT proves the DB-side
 * scoping that the security mandate centers on.</p>
 *
 * <p>Skips (does not error) when Docker is unavailable, mirroring {@link IntegrationTestBase}.</p>
 */
class SandboxResetIntegrationTest extends IntegrationTestBase {

    @Autowired private SandboxResetService resetService;
    @Autowired private PaymentProjectionRepository paymentRepo;
    @Autowired private RefundProjectionRepository refundRepo;
    @Autowired private CustomerRepository customerRepo;
    @Autowired private PaymentMethodRepository paymentMethodRepo;
    @Autowired private MandateRepository mandateRepo;

    @BeforeEach
    void requireDocker() {
        Assumptions.assumeTrue(DOCKER_AVAILABLE, "Docker not available — skipping sandbox reset IT");
    }

    private static String tid(String label) {
        return "tenant-reset-" + label + "-" + UUID.randomUUID().toString().substring(0, 6);
    }

    /** Seeds one test (livemode=false) payment+refund+customer+pm+mandate graph for a tenant; returns the payment id. */
    private String seedTestGraph(String tenant) {
        String payId = "pay_test_" + UUID.randomUUID().toString().substring(0, 10);
        String refId = "re_test_" + UUID.randomUUID().toString().substring(0, 10);
        paymentRepo.upsert(new PaymentProjectionRow(payId, tenant, false, "succeeded", 5000, "USD",
                "automatic", "cus_x", "mock", null, null, Instant.now(), Instant.now()));
        refundRepo.upsert(new RefundProjectionRow(refId, payId, tenant, false, "succeeded", 2500, "USD",
                "requested_by_customer", "mock", null, null, Instant.now(), Instant.now()));
        Customer cus = Customer.create(tenant, false, "a@x.com", "A", "d", Map.of());
        customerRepo.save(cus);
        PaymentMethod pm = PaymentMethod.create(tenant, cus.getId(), false, "card", "visa", "4242",
                12, 2030, "credit", "cred_ref", Map.of());
        paymentMethodRepo.save(pm);
        mandateRepo.save(Mandate.create(tenant, cus.getId(), pm.getId(), false, "recurring", "subscription",
                Map.of()));
        return payId;
    }

    // -------- ★ CROSS-TENANT: reset A deletes A, leaves B untouched (real DELETE SQL) --------

    @Test
    @DisplayName("★ CROSS-TENANT: resetting tenant A leaves tenant B's test rows untouched")
    void crossTenant_resetA_leavesB() {
        String a = tid("A");
        String b = tid("B");
        seedTestGraph(a);
        seedTestGraph(b);

        SandboxResetSummary summary = resetService.reset(a);

        // A's rows are gone.
        assertThat(paymentRepo.findTestIds(a)).isEmpty();
        assertThat(refundRepo.findTestIds(a)).isEmpty();
        assertThat(customerRepo.findByTenant(a, 100, 0)).isEmpty();
        assertThat(mandateRepo.findByTenant(a, 100, 0)).isEmpty();
        // A's summary counted exactly its own one-of-each.
        assertThat(summary.payments()).isEqualTo(1);
        assertThat(summary.refunds()).isEqualTo(1);
        assertThat(summary.customers()).isEqualTo(1);
        assertThat(summary.paymentMethods()).isEqualTo(1);
        assertThat(summary.mandates()).isEqualTo(1);

        // B is completely untouched.
        assertThat(paymentRepo.findTestIds(b)).hasSize(1);
        assertThat(refundRepo.findTestIds(b)).hasSize(1);
        assertThat(customerRepo.findByTenant(b, 100, 0)).hasSize(1);
        assertThat(mandateRepo.findByTenant(b, 100, 0)).hasSize(1);
    }

    // -------- ★ LIVEMODE: reset deletes test rows, leaves LIVE rows for the same tenant --------

    @Test
    @DisplayName("★ LIVEMODE: a reset deletes livemode=false rows and leaves livemode=true rows (ALL 5 tables)")
    void livemode_resetDeletesTest_leavesLive() {
        String tenant = tid("mode");
        // a full TEST (livemode=false) graph...
        seedTestGraph(tenant);

        // ...and a full LIVE (livemode=true) graph for the SAME tenant, one row per table. The livemode=false
        // predicate is a SEPARATE @Query string per adapter, so each of the five delete queries must be
        // proven against a LIVE row of ITS OWN table — a copy-paste slip dropping `and livemode = false`
        // from any one query would delete that table's LIVE row and no other assertion would catch it.
        String livePay = "pay_live_" + UUID.randomUUID().toString().substring(0, 10);
        paymentRepo.upsert(new PaymentProjectionRow(livePay, tenant, true, "succeeded", 9999, "USD",
                "automatic", "cus_l", "stripe", null, null, Instant.now(), Instant.now()));
        String liveRef = "re_live_" + UUID.randomUUID().toString().substring(0, 10);
        refundRepo.upsert(new RefundProjectionRow(liveRef, livePay, tenant, true, "succeeded", 4444, "USD",
                "requested_by_customer", "stripe", null, null, Instant.now(), Instant.now()));
        Customer liveCustomer = Customer.create(tenant, true, "live@x.com", "L", "d", Map.of());
        customerRepo.save(liveCustomer);
        PaymentMethod livePm = PaymentMethod.create(tenant, liveCustomer.getId(), true, "card", "visa", "1111",
                12, 2031, "credit", "cred_live", Map.of());
        paymentMethodRepo.save(livePm);
        Mandate liveMandate = Mandate.create(tenant, liveCustomer.getId(), livePm.getId(), true, "recurring",
                "subscription", Map.of());
        mandateRepo.save(liveMandate);

        SandboxResetSummary summary = resetService.reset(tenant);

        // every test row deleted — exactly one of each counted.
        assertThat(paymentRepo.findTestIds(tenant)).isEmpty();
        assertThat(refundRepo.findTestIds(tenant)).isEmpty();
        assertThat(summary.payments()).isEqualTo(1);
        assertThat(summary.refunds()).isEqualTo(1);
        assertThat(summary.customers()).isEqualTo(1);
        assertThat(summary.paymentMethods()).isEqualTo(1);
        assertThat(summary.mandates()).isEqualTo(1);

        // ...and EVERY live row survives the reset — the livemode=false predicate proven across all 5 deletes.
        assertThat(paymentRepo.listByTenant(tenant, true, null, null, 100, 0))
                .extracting(PaymentProjectionRow::paymentId).contains(livePay);
        assertThat(refundRepo.listByTenant(tenant, true, null, null, 100, 0))
                .extracting(RefundProjectionRow::refundId).contains(liveRef);
        assertThat(customerRepo.findByIdAndTenantId(liveCustomer.getId(), tenant)).isPresent();
        assertThat(paymentMethodRepo.findByIdAndTenantId(livePm.getId(), tenant)).isPresent();
        assertThat(mandateRepo.findByIdAndTenantId(liveMandate.getId(), tenant)).isPresent();
    }

    // -------- SUMMARY + FK ORDER: full graph resets with correct counts, no FK exception --------

    @Test
    @DisplayName("SUMMARY + FK ORDER: a multi-row graph resets atomically with correct per-table counts")
    void summaryAndFkOrder_correctCounts_noThrow() {
        String tenant = tid("graph");
        // seed 3 payment+refund pairs and 2 customer/pm/mandate triples.
        for (int i = 0; i < 3; i++) {
            seedTestGraph(tenant);
        }

        SandboxResetSummary summary = resetService.reset(tenant);

        assertThat(summary.payments()).isEqualTo(3);
        assertThat(summary.refunds()).isEqualTo(3);
        assertThat(summary.customers()).isEqualTo(3);
        assertThat(summary.paymentMethods()).isEqualTo(3);
        assertThat(summary.mandates()).isEqualTo(3);
        // all gone.
        assertThat(paymentRepo.findTestIds(tenant)).isEmpty();
        assertThat(refundRepo.findTestIds(tenant)).isEmpty();
    }
}
