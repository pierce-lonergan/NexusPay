package io.nexuspay.app;

import io.nexuspay.payment.application.port.PaymentGatewayPort;
import io.nexuspay.payment.application.port.out.PaymentProjectionRepository;
import io.nexuspay.payment.application.screening.CallContext;
import io.nexuspay.payment.application.screening.ScreeningMode;
import io.nexuspay.payment.application.service.projection.PaymentProjectionQueryService;
import io.nexuspay.payment.application.service.projection.PaymentProjectionService;
import io.nexuspay.payment.domain.PaymentRequest;
import io.nexuspay.payment.domain.PaymentResponse;
import io.nexuspay.payment.domain.RefundRequest;
import io.nexuspay.payment.domain.projection.PaymentProjectionRow;
import io.nexuspay.payment.domain.projection.RefundProjectionRow;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * GAP-076 (critique v3 F1): the END-TO-END read-model proof against a REAL Postgres (Testcontainers) with
 * the REAL V4041 migration, the REAL derived finders, the REAL {@code OffsetLimitRequest} offset math, and
 * the REAL {@code PaymentProjectionService -> PaymentProjectionTxWriter (REQUIRES_NEW) -> JPA adapter}
 * write path. This is the test the review's findings #1/#2/#3/#5/#6/#7 demand: the previous coverage ran
 * against hand-rolled fakes / Mockito-call assertions that re-implemented (or never executed) the SQL
 * predicate + the offset math + the commit-time failure path.
 *
 * <p>All cases drive TEST mode ({@code ctx.live()==FALSE} -> the in-process mock route -> livemode=false),
 * so no PSP / WireMock is needed; the projection rows + the query are entirely real.</p>
 *
 * <p>Skips (does not error) when Docker is unavailable, mirroring {@link IntegrationTestBase}.</p>
 */
class PaymentProjectionReadModelIntegrationTest extends IntegrationTestBase {

    @Autowired
    private PaymentGatewayPort gateway;            // @Primary GatedPaymentGateway over the real beans

    @Autowired
    private PaymentProjectionQueryService query;   // the only read surface

    @Autowired
    private PaymentProjectionService projection;   // best-effort writer (wraps the inner REQUIRES_NEW writer)

    @Autowired
    private PaymentProjectionRepository paymentRepo; // direct seeding for the offset / cross-tenant SQL proof

    @BeforeEach
    void requireDocker() {
        Assumptions.assumeTrue(DOCKER_AVAILABLE, "Docker not available — skipping projection IT");
        io.nexuspay.common.mode.PaymentMode.clear();
    }

    private static CallContext testCtx(String tenant) {
        // ctx.live()==FALSE forces the deterministic in-process mock (livemode=false rows).
        return new CallContext(tenant, ScreeningMode.INTERACTIVE, Boolean.FALSE);
    }

    private static PaymentRequest req(Map<String, Object> metadata) {
        return new PaymentRequest(5000, "USD", "cus_proj_" + UUID.randomUUID().toString().substring(0, 6),
                "card", "4111111111111111", null, "desc", "automatic",
                "idem_" + UUID.randomUUID(), metadata);
    }

    // -------- read-your-write: a created TEST payment is immediately listable with its status --------

    @Test
    @DisplayName("READ-YOUR-WRITE: a created test payment is immediately listable via the real finder")
    void createdPayment_isImmediatelyListable_inTestMode() {
        String tenant = "tenant-proj-A-" + UUID.randomUUID().toString().substring(0, 6);
        PaymentResponse created = gateway.createPayment(req(Map.of()), testCtx(tenant));

        List<PaymentProjectionRow> rows = query.listPayments(tenant, false, null, null, 20, 0);

        assertThat(rows).extracting(PaymentProjectionRow::paymentId).contains(created.gatewayPaymentId());
        assertThat(rows).allMatch(r -> r.tenantId().equals(tenant) && !r.livemode());
    }

    @Test
    @DisplayName("A forced-decline create is listable with status=failed + the error_code")
    void forcedDecline_listsAsFailed_withErrorCode() {
        String tenant = "tenant-proj-decline-" + UUID.randomUUID().toString().substring(0, 6);
        PaymentResponse created = gateway.createPayment(
                req(Map.of("__test_outcome", "declined")), testCtx(tenant));
        assertThat(created.status()).isEqualTo(PaymentResponse.STATUS_FAILED);

        List<PaymentProjectionRow> rows = query.listPayments(tenant, false, "failed", null, 20, 0);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).status()).isEqualTo(PaymentResponse.STATUS_FAILED);
        assertThat(rows.get(0).errorCode()).isEqualTo("card_declined");
    }

    @Test
    @DisplayName("A requires_action / processing create is listable in that NON-TERMINAL state")
    void nonTerminalCreate_isListable() {
        String tenant = "tenant-proj-nonterm-" + UUID.randomUUID().toString().substring(0, 6);
        PaymentResponse action = gateway.createPayment(
                req(Map.of("__test_outcome", "requires_action")), testCtx(tenant));
        PaymentResponse processing = gateway.createPayment(
                req(Map.of("__test_outcome", "processing")), testCtx(tenant));

        List<PaymentProjectionRow> rows = query.listPayments(tenant, false, null, null, 20, 0);
        assertThat(rows).extracting(PaymentProjectionRow::status)
                .contains(PaymentResponse.STATUS_REQUIRES_ACTION, PaymentResponse.STATUS_PROCESSING);
        assertThat(query.listPayments(tenant, false, "requires_action", null, 20, 0))
                .extracting(PaymentProjectionRow::paymentId).containsExactly(action.gatewayPaymentId());
        assertThat(query.listPayments(tenant, false, "processing", null, 20, 0))
                .extracting(PaymentProjectionRow::paymentId).containsExactly(processing.gatewayPaymentId());
    }

    // -------- refunds listable, filtered by payment_id (real ?payment= finder) --------

    @Test
    @DisplayName("A refund is listable via the real refund finder, filtered by payment_id")
    void refund_isListable_filteredByPaymentId() {
        String tenant = "tenant-proj-refund-" + UUID.randomUUID().toString().substring(0, 6);
        PaymentResponse paid = gateway.createPayment(req(Map.of()), testCtx(tenant));
        // The refund routes to the mock via the pay_test_* id fail-safe; tenant resolves from the origin row.
        gateway.createRefund(new RefundRequest(paid.gatewayPaymentId(), 2500L, "USD", "requested_by_customer",
                "rk_" + UUID.randomUUID()));

        List<RefundProjectionRow> byPayment = query.listRefunds(tenant, false, paid.gatewayPaymentId(), null, 20, 0);
        assertThat(byPayment).isNotEmpty();
        assertThat(byPayment).allMatch(r -> r.paymentId().equals(paid.gatewayPaymentId()) && !r.livemode());

        // A foreign payment_id filter returns empty (no leak, no error).
        assertThat(query.listRefunds(tenant, false, "pay_test_does_not_exist", null, 20, 0)).isEmpty();
    }

    // -------- ★ cross-tenant: tenant B never sees tenant A's rows (real SQL predicate, no IDOR) --------

    @Test
    @DisplayName("★ CROSS-TENANT: tenant-B's list never returns tenant-A's payments (real finder SQL)")
    void crossTenant_noLeak_throughRealFinder() {
        String a = "tenant-proj-leakA-" + UUID.randomUUID().toString().substring(0, 6);
        String b = "tenant-proj-leakB-" + UUID.randomUUID().toString().substring(0, 6);
        PaymentResponse aPay = gateway.createPayment(req(Map.of()), testCtx(a));
        gateway.createPayment(req(Map.of()), testCtx(b));

        List<PaymentProjectionRow> bRows = query.listPayments(b, false, null, null, 100, 0);
        assertThat(bRows).extracting(PaymentProjectionRow::tenantId).containsOnly(b);
        assertThat(bRows).extracting(PaymentProjectionRow::paymentId).doesNotContain(aPay.gatewayPaymentId());
    }

    // -------- ★ livemode: a test key lists only livemode=false rows (real SQL predicate) --------

    @Test
    @DisplayName("★ LIVEMODE: a live-stamped row is invisible to a test-key list of the same tenant")
    void livemodeScoping_throughRealFinder() {
        String tenant = "tenant-proj-mode-" + UUID.randomUUID().toString().substring(0, 6);
        PaymentResponse testPay = gateway.createPayment(req(Map.of()), testCtx(tenant)); // livemode=false

        // Seed a LIVE row for the same tenant DIRECTLY via the real repo (no PSP needed).
        String liveId = "pay_live_seed_" + UUID.randomUUID().toString().substring(0, 8);
        paymentRepo.upsert(new PaymentProjectionRow(liveId, tenant, true, "succeeded", 9999, "USD",
                "automatic", "cus_live", "stripe", null, null, Instant.now(), Instant.now()));

        // test key (livemode=false) sees only its test row, NOT the live one.
        assertThat(query.listPayments(tenant, false, null, null, 100, 0))
                .extracting(PaymentProjectionRow::paymentId)
                .contains(testPay.gatewayPaymentId()).doesNotContain(liveId);
        // live key (livemode=true) sees only the live row, NOT the test one.
        assertThat(query.listPayments(tenant, true, null, null, 100, 0))
                .extracting(PaymentProjectionRow::paymentId)
                .contains(liveId).doesNotContain(testPay.gatewayPaymentId());
    }

    // -------- ★ offset/limit pagination through the REAL OffsetLimitRequest (the offset BLOCKER) --------

    @Test
    @DisplayName("★ PAGINATION: offset=5,limit=10 returns rows 5..14 (the offset-math fix), not page-0 dups")
    void offsetPagination_honorsArbitraryOffset_throughRealAdapter() {
        String tenant = "tenant-proj-page-" + UUID.randomUUID().toString().substring(0, 6);
        // Seed 25 rows with strictly increasing created_at so created_at DESC order is deterministic.
        Instant base = Instant.parse("2026-06-01T00:00:00Z");
        for (int i = 0; i < 25; i++) {
            String id = String.format("pay_page_%02d_%s", i, UUID.randomUUID().toString().substring(0, 6));
            paymentRepo.upsert(new PaymentProjectionRow(id, tenant, false, "succeeded", 1000 + i, "USD",
                    "automatic", "cus_page", "stripe", null, null, base.plusSeconds(i), Instant.now()));
        }

        // newest-first: index 0 = the row created at base+24s. offset=5,limit=10 -> rows at base+19 .. base+10.
        List<PaymentProjectionRow> page = query.listPayments(tenant, false, null, null, 10, 5);
        assertThat(page).hasSize(10);
        List<Instant> created = page.stream().map(PaymentProjectionRow::createdAt).toList();
        // Strictly descending and starting at base+19 (the 6th-newest), proving offset=5 was honored (the
        // old PageRequest.of(5/10,10)=page0 would have re-served the newest 10 = base+24..base+15).
        assertThat(created.get(0)).isEqualTo(base.plusSeconds(19));
        assertThat(created.get(9)).isEqualTo(base.plusSeconds(10));
        for (int i = 1; i < created.size(); i++) {
            assertThat(created.get(i)).isBefore(created.get(i - 1));
        }

        // page boundary: the next page (offset=15) continues at base+9, no overlap with the prior page.
        List<PaymentProjectionRow> page2 = query.listPayments(tenant, false, null, null, 10, 15);
        assertThat(page2.get(0).createdAt()).isEqualTo(base.plusSeconds(9));
        assertThat(page2).extracting(PaymentProjectionRow::paymentId)
                .doesNotContainAnyElementsOf(page.stream().map(PaymentProjectionRow::paymentId).toList());

        // over-cap limit is clamped to 100 (does not throw, returns all 25).
        assertThat(query.listPayments(tenant, false, null, null, 5000, 0)).hasSize(25);
    }

    // -------- ★ NON-BLOCKING at the COMMIT boundary (the BLOCKER the mock-throw test could not prove) --------

    @Test
    @DisplayName("★ NON-BLOCKING: a null-tenant live create does NOT throw (the NOT NULL would fail at commit)")
    void nullTenantLiveCreate_doesNotThrow_commitTimeConstraintIsSafe() {
        // The service SKIPS a null-tenant write (a null-tenant row is unqueryable + violates NOT NULL).
        // Even if it did not skip, the commit-time failure would be swallowed OUTSIDE the inner tx. Either
        // way, record() must NEVER throw. This is the production path the mock-throw unit test could not
        // reach (no Spring proxy / no commit boundary there).
        PaymentResponse resp = new PaymentResponse("pay_nulltenant_" + UUID.randomUUID(),
                PaymentResponse.STATUS_PROCESSING, 5000, "USD", "automatic", "cus_1", "stripe",
                "txn_1", null, null, Instant.now(), Map.of());
        assertThatCode(() -> projection.record(resp, null, true)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("★ NON-BLOCKING: a commit-time constraint violation in the REQUIRES_NEW writer is swallowed")
    void commitTimeConstraintViolation_isSwallowed_byTheOuterWrapper() {
        // Force a genuine COMMIT-TIME failure: write a row whose status (a NOT NULL VARCHAR(32) column) is
        // null. repo.save() defers; the violation surfaces at the REQUIRES_NEW proxy COMMIT — i.e. AFTER the
        // inner method body returns. The OUTER PaymentProjectionService.record swallow surrounds that commit,
        // so the live payment op (here, record()) does NOT throw. This is exactly the path the in-method
        // swallow could not catch (the BLOCKER).
        PaymentResponse nullStatus = new PaymentResponse("pay_nullstatus_" + UUID.randomUUID(),
                null /* status -> NOT NULL violation at commit */, 5000, "USD", "automatic", "cus_1",
                "stripe", "txn_1", null, null, Instant.now(), Map.of());
        assertThatCode(() -> projection.record(nullStatus, "tenant-commit-fail", false))
                .doesNotThrowAnyException();
        // And the failed write left NO row (the tx rolled back) — the list is unaffected, op proceeded.
        assertThat(query.listPayments("tenant-commit-fail", false, null, null, 20, 0)).isEmpty();
    }

    // -------- ★ async-live: updateStatusIfExists behaviorally advances processing -> succeeded on a row --------

    @Test
    @DisplayName("★ ASYNC-LIVE: updateStatusIfExists advances a stored processing row to succeeded (behavioral)")
    void asyncLive_processingRow_advancesToSucceeded_andNeverRegresses_andForwardFillNoOp() {
        String tenant = "tenant-proj-async-" + UUID.randomUUID().toString().substring(0, 6);
        String id = "pay_async_" + UUID.randomUUID().toString().substring(0, 8);
        // Seed a stored row at status=processing (as a live create would leave it pre-settlement).
        paymentRepo.upsert(new PaymentProjectionRow(id, tenant, true, PaymentResponse.STATUS_PROCESSING, 5000,
                "USD", "automatic", "cus_async", "stripe", null, null, Instant.now(), Instant.now()));

        // The HyperSwitch payment_succeeded webhook path advances it (precedence-guarded) — behavioral, not a mock.
        projection.recordStatusUpdate(id, tenant, PaymentResponse.STATUS_SUCCEEDED, true);
        assertThat(query.listPayments(tenant, true, null, null, 20, 0))
                .filteredOn(r -> r.paymentId().equals(id))
                .extracting(PaymentProjectionRow::status)
                .containsExactly(PaymentResponse.STATUS_SUCCEEDED);

        // A LATE processing after succeeded must NOT regress the row (the precedence guard, on real SQL).
        projection.recordStatusUpdate(id, tenant, PaymentResponse.STATUS_PROCESSING, true);
        assertThat(query.listPayments(tenant, true, null, null, 20, 0))
                .filteredOn(r -> r.paymentId().equals(id))
                .extracting(PaymentProjectionRow::status)
                .containsExactly(PaymentResponse.STATUS_SUCCEEDED);

        // FORWARD-FILL: updateStatusIfExists on a MISSING id is a no-op (no row is created).
        String missing = "pay_missing_" + UUID.randomUUID().toString().substring(0, 8);
        projection.recordStatusUpdate(missing, tenant, PaymentResponse.STATUS_SUCCEEDED, true);
        assertThat(query.listPayments(tenant, true, null, null, 100, 0))
                .extracting(PaymentProjectionRow::paymentId).doesNotContain(missing);
    }
}
