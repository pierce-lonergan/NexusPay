package io.nexuspay.app;

import com.jayway.jsonpath.JsonPath;
import io.nexuspay.app.config.TestSecurityConfig;
import io.nexuspay.fraud.application.port.out.FraudAssessmentRepository;
import io.nexuspay.fraud.domain.model.RiskAssessment;
import io.nexuspay.fraud.domain.model.RiskDecision;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SEC-BATCH-1 end-to-end tenant-isolation proof against the real advice chain (gateway-api's
 * GlobalExceptionHandler) and the real {@code findByIdAndTenantId} SQL on a Testcontainers Postgres.
 *
 * <p>Pattern: seed a resource under tenant A, then act as tenant B → must get 404 (not 403, not 500,
 * and the row must not be mutated). Authentication carries a tenant-bearing {@link
 * io.nexuspay.iam.domain.NexusPayPrincipal} via {@link TestSecurityConfig#authForRole(String, String)}
 * — there is no X-Tenant-Id header anywhere.</p>
 */
@AutoConfigureMockMvc
@Import(TestSecurityConfig.class)
class TenantIsolationIntegrationTest extends IntegrationTestBase {

    @Autowired
    private MockMvc mockMvc;

    // SEC-23: the fraud module has no public create-assessment endpoint, so the fraud cases seed an
    // assessment directly via the repository bean (full Spring context) rather than over HTTP.
    @Autowired
    private FraudAssessmentRepository fraudAssessmentRepository;

    @Test
    @DisplayName("Vault card: tenant B cannot read tenant A's vaulted card (404, no PAN disclosure)")
    void vaultCard_crossTenantRead_returns404() throws Exception {
        // Seed a card under tenant-A.
        MvcResult created = mockMvc.perform(post("/v1/vault/cards")
                        .with(authentication(TestSecurityConfig.authForRole("admin", "tenant-A")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "pan": "4111111111111111",
                                    "expMonth": 12,
                                    "expYear": 2030,
                                    "cardholderName": "Alice A"
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        String token = JsonPath.read(created.getResponse().getContentAsString(), "$.token");

        // Tenant-A can read its own card.
        mockMvc.perform(get("/v1/vault/cards/" + token)
                        .with(authentication(TestSecurityConfig.authForRole("admin", "tenant-A"))))
                .andExpect(status().isOk());

        // Tenant-B reading tenant-A's card → 404 (same as truly-absent; no existence oracle).
        mockMvc.perform(get("/v1/vault/cards/" + token)
                        .with(authentication(TestSecurityConfig.authForRole("admin", "tenant-B"))))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Vault card: tenant B cannot delete tenant A's card; tenant A's card survives")
    void vaultCard_crossTenantDelete_returns404_andRowSurvives() throws Exception {
        MvcResult created = mockMvc.perform(post("/v1/vault/cards")
                        .with(authentication(TestSecurityConfig.authForRole("admin", "tenant-A")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "pan": "5555555555554444",
                                    "expMonth": 6,
                                    "expYear": 2031,
                                    "cardholderName": "Alice A"
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        String token = JsonPath.read(created.getResponse().getContentAsString(), "$.token");

        // Tenant-B attempts delete → 404, no cascade.
        mockMvc.perform(delete("/v1/vault/cards/" + token)
                        .with(authentication(TestSecurityConfig.authForRole("admin", "tenant-B"))))
                .andExpect(status().isNotFound());

        // The card still belongs to tenant-A and is readable by tenant-A — it was not deleted.
        mockMvc.perform(get("/v1/vault/cards/" + token)
                        .with(authentication(TestSecurityConfig.authForRole("admin", "tenant-A"))))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Connected account: tenant B cannot read tenant A's account (404)")
    void connectedAccount_crossTenantRead_returns404() throws Exception {
        MvcResult created = mockMvc.perform(post("/v1/connected-accounts")
                        .with(authentication(TestSecurityConfig.authForRole("admin", "tenant-A")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"businessName":"Acme A","email":"a@acme.test","country":"US","defaultCurrency":"USD"}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        String accountId = JsonPath.read(created.getResponse().getContentAsString(), "$.accountId");

        mockMvc.perform(get("/v1/connected-accounts/" + accountId)
                        .with(authentication(TestSecurityConfig.authForRole("admin", "tenant-A"))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/v1/connected-accounts/" + accountId)
                        .with(authentication(TestSecurityConfig.authForRole("admin", "tenant-B"))))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Webhook endpoint: tenant B's delete silently no-ops; tenant A's endpoint survives")
    void webhookEndpoint_crossTenantDelete_noOpsAndSurvives() throws Exception {
        MvcResult created = mockMvc.perform(post("/v1/webhook-endpoints")
                        .with(authentication(TestSecurityConfig.authForRole("admin", "tenant-A")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "url": "https://example.com/webhooks",
                                  "events": ["payment.succeeded"]
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        String endpointId = JsonPath.read(created.getResponse().getContentAsString(), "$.id");

        // Tenant-B delete → 204 no-op (SEC-19 keeps 204-on-miss, no existence oracle).
        mockMvc.perform(delete("/v1/webhook-endpoints/" + endpointId)
                        .with(authentication(TestSecurityConfig.authForRole("admin", "tenant-B"))))
                .andExpect(status().isNoContent());

        // Endpoint is still enabled for tenant-A — the cross-tenant delete did NOT disable it.
        mockMvc.perform(get("/v1/webhook-endpoints")
                        .with(authentication(TestSecurityConfig.authForRole("admin", "tenant-A"))))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$[?(@.id=='" + endpointId + "')]").exists());
    }

    // ================================================================================================
    // SEC-23 — b2b PurchaseOrderController, b2b B2bInvoiceController, fraud FraudAssessmentController.
    // Each case is RED on the header-trusting code (today the global by-id finder returns the foreign
    // row → tenant-B gets 200 / the row is mutated) and GREEN after CallerTenant + the tenant-scoped
    // finder + the TenantOwnership 404.
    // ================================================================================================

    // --- b2b Purchase Order ------------------------------------------------------------------------

    /** Seeds a DRAFT purchase order under {@code tenant-A} and returns its id. */
    private String seedPurchaseOrder() throws Exception {
        MvcResult created = mockMvc.perform(post("/v1/purchase-orders")
                        .with(authentication(TestSecurityConfig.authForRole("admin", "tenant-A")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"buyerId":"buyer-A","sellerId":"seller-A","poNumber":"PO-SEC23",
                                 "currency":"USD","terms":"NET_30","taxAmount":0,
                                 "lineItems":[{"description":"Widget","quantity":10,"unitCost":1000,
                                               "commodityCode":"WDGT","unitOfMeasure":"EA"}]}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(created.getResponse().getContentAsString(), "$.poId");
    }

    @Test
    @DisplayName("PO: tenant B cannot read tenant A's purchase order (404); tenant A still can (200)")
    void purchaseOrder_crossTenantRead_returns404() throws Exception {
        String poId = seedPurchaseOrder();

        mockMvc.perform(get("/v1/purchase-orders/" + poId)
                        .with(authentication(TestSecurityConfig.authForRole("admin", "tenant-A"))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/v1/purchase-orders/" + poId)
                        .with(authentication(TestSecurityConfig.authForRole("admin", "tenant-B"))))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("PO: tenant B cannot approve tenant A's PO (404); the row stays SUBMITTED (not mutated)")
    void purchaseOrder_crossTenantApprove_returns404_andRowNotMutated() throws Exception {
        String poId = seedPurchaseOrder();

        // tenant-A submits → SUBMITTED (the only state from which approve is legal).
        mockMvc.perform(post("/v1/purchase-orders/" + poId + "/submit")
                        .with(authentication(TestSecurityConfig.authForRole("admin", "tenant-A"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"));

        // tenant-B attempts approve → 404, and must NOT act (load-then-act under the trusted tenant).
        mockMvc.perform(post("/v1/purchase-orders/" + poId + "/approve")
                        .with(authentication(TestSecurityConfig.authForRole("admin", "tenant-B"))))
                .andExpect(status().isNotFound());

        // tenant-A re-reads: still SUBMITTED — the foreign approve did not flip it to APPROVED.
        mockMvc.perform(get("/v1/purchase-orders/" + poId)
                        .with(authentication(TestSecurityConfig.authForRole("admin", "tenant-A"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"));
    }

    @Test
    @DisplayName("PO: tenant B cannot cancel tenant A's PO (404); the row survives (not CANCELLED)")
    void purchaseOrder_crossTenantCancel_returns404_andRowSurvives() throws Exception {
        String poId = seedPurchaseOrder();

        mockMvc.perform(post("/v1/purchase-orders/" + poId + "/cancel")
                        .with(authentication(TestSecurityConfig.authForRole("admin", "tenant-B"))))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/v1/purchase-orders/" + poId)
                        .with(authentication(TestSecurityConfig.authForRole("admin", "tenant-A"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"));  // not CANCELLED
    }

    // --- b2b Invoice -------------------------------------------------------------------------------

    /** Seeds an APPROVED (not-yet-invoiced) purchase order under {@code tenant-A} and returns its id. */
    private String seedApprovedPurchaseOrder() throws Exception {
        String poId = seedPurchaseOrder();
        mockMvc.perform(post("/v1/purchase-orders/" + poId + "/submit")
                        .with(authentication(TestSecurityConfig.authForRole("admin", "tenant-A"))))
                .andExpect(status().isOk());
        mockMvc.perform(post("/v1/purchase-orders/" + poId + "/approve")
                        .with(authentication(TestSecurityConfig.authForRole("admin", "tenant-A"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
        return poId;
    }

    /** Seeds a DRAFT invoice under {@code tenant-A} (create+submit+approve a PO first) and returns its id. */
    private String seedInvoice() throws Exception {
        String poId = seedApprovedPurchaseOrder();

        MvcResult created = mockMvc.perform(post("/v1/b2b-invoices")
                        .with(authentication(TestSecurityConfig.authForRole("admin", "tenant-A")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"purchaseOrderId":"%s","invoiceNumber":"INV-SEC23"}
                                """.formatted(poId)))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(created.getResponse().getContentAsString(), "$.invoiceId");
    }

    @Test
    @DisplayName("Invoice: tenant B cannot read tenant A's invoice (404); tenant A still can (200)")
    void invoice_crossTenantRead_returns404() throws Exception {
        String invoiceId = seedInvoice();

        mockMvc.perform(get("/v1/b2b-invoices/" + invoiceId)
                        .with(authentication(TestSecurityConfig.authForRole("admin", "tenant-A"))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/v1/b2b-invoices/" + invoiceId)
                        .with(authentication(TestSecurityConfig.authForRole("admin", "tenant-B"))))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Invoice: tenant B cannot send tenant A's invoice (404); status stays DRAFT")
    void invoice_crossTenantSend_returns404_andStatusUnchanged() throws Exception {
        String invoiceId = seedInvoice();

        mockMvc.perform(post("/v1/b2b-invoices/" + invoiceId + "/send")
                        .with(authentication(TestSecurityConfig.authForRole("admin", "tenant-B"))))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/v1/b2b-invoices/" + invoiceId)
                        .with(authentication(TestSecurityConfig.authForRole("admin", "tenant-A"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"));  // send did not act
    }

    @Test
    @DisplayName("Invoice: tenant B cannot mark tenant A's invoice paid (404); status not PAID")
    void invoice_crossTenantMarkPaid_returns404_andStatusUnchanged() throws Exception {
        String invoiceId = seedInvoice();

        mockMvc.perform(post("/v1/b2b-invoices/" + invoiceId + "/mark-paid")
                        .with(authentication(TestSecurityConfig.authForRole("admin", "tenant-B"))))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/v1/b2b-invoices/" + invoiceId)
                        .with(authentication(TestSecurityConfig.authForRole("admin", "tenant-A"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"));  // not PAID
    }

    @Test
    @DisplayName("Invoice create: tenant B cannot create an invoice from tenant A's PO (404); PO stays APPROVED")
    void invoice_crossTenantCreateFromForeignPO_returns404_andPONotInvoiced() throws Exception {
        // tenant-A owns an APPROVED PO (a legal invoice source).
        String poId = seedApprovedPurchaseOrder();

        // tenant-B POSTs /v1/b2b-invoices referencing tenant-A's PO id. The service resolves the PO
        // tenant-scoped, so this must 404 BEFORE reading tenant-A's financials into a tenant-B invoice
        // (data-exfiltration oracle) and BEFORE flipping the PO to INVOICED (cross-tenant mutation).
        mockMvc.perform(post("/v1/b2b-invoices")
                        .with(authentication(TestSecurityConfig.authForRole("admin", "tenant-B")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"purchaseOrderId":"%s","invoiceNumber":"INV-EVIL"}
                                """.formatted(poId)))
                .andExpect(status().isNotFound());

        // tenant-A re-reads: the PO is still APPROVED — the foreign create did NOT flip it to INVOICED.
        mockMvc.perform(get("/v1/purchase-orders/" + poId)
                        .with(authentication(TestSecurityConfig.authForRole("admin", "tenant-A"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));  // not INVOICED
    }

    // --- fraud Assessment --------------------------------------------------------------------------

    /**
     * Seeds a PENDING_REVIEW fraud assessment under {@code tenant-A} directly via the repository bean
     * (there is no public create-assessment endpoint). Returns the seeded id.
     */
    private UUID seedFraudAssessment() {
        // Unique payment ref per call: fraud dedups on (tenant_id, payment_id), so a shared ref would
        // make only the first-seeding test persist a real row and the rest collide/no-op (their id then
        // resolves to nothing → spurious 404s). A distinct ref keeps each test's data isolated.
        RiskAssessment assessment = RiskAssessment.create(
                "tenant-A", "pay_seed_sec23_" + UUID.randomUUID().toString().substring(0, 8));
        // REVIEW decision → reviewStatus = PENDING_REVIEW, so it also shows in the pending list.
        assessment.applyDecision(80, 80, "NATIVE_ONLY", 80, RiskDecision.REVIEW);
        fraudAssessmentRepository.save(assessment);
        return assessment.getId();
    }

    @Test
    @DisplayName("Fraud assessment: tenant B cannot read tenant A's assessment (404); tenant A can (200)")
    void fraudAssessment_crossTenantRead_returns404() throws Exception {
        UUID id = seedFraudAssessment();

        mockMvc.perform(get("/v1/fraud/assessments/" + id)
                        .with(authentication(TestSecurityConfig.authForRole("admin", "tenant-A"))))
                .andExpect(status().isOk());

        // RED on the vulnerable code two ways: the X-Tenant-Id defaultValue + the old
        // IllegalArgumentException (→500). With the header gone + tenant-scoped finder, foreign → 404.
        mockMvc.perform(get("/v1/fraud/assessments/" + id)
                        .with(authentication(TestSecurityConfig.authForRole("admin", "tenant-B"))))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Fraud assessment: tenant B cannot approve tenant A's assessment (404); stays PENDING_REVIEW")
    void fraudAssessment_crossTenantApprove_returns404_andNotReviewed() throws Exception {
        UUID id = seedFraudAssessment();

        mockMvc.perform(post("/v1/fraud/assessments/" + id + "/approve")
                        .with(authentication(TestSecurityConfig.authForRole("admin", "tenant-B"))))
                .andExpect(status().isNotFound());

        // tenant-A re-reads: still PENDING_REVIEW — the foreign approve did not act.
        mockMvc.perform(get("/v1/fraud/assessments/" + id)
                        .with(authentication(TestSecurityConfig.authForRole("admin", "tenant-A"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewStatus").value("PENDING_REVIEW"));
    }

    @Test
    @DisplayName("Fraud assessment: tenant B cannot reject tenant A's assessment (404)")
    void fraudAssessment_crossTenantReject_returns404() throws Exception {
        UUID id = seedFraudAssessment();

        mockMvc.perform(post("/v1/fraud/assessments/" + id + "/reject")
                        .with(authentication(TestSecurityConfig.authForRole("admin", "tenant-B"))))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Fraud pending list is tenant-scoped: tenant B does not see tenant A's seeded assessment")
    void fraudAssessment_pending_isTenantScoped() throws Exception {
        UUID id = seedFraudAssessment();

        // tenant-A sees its own seeded assessment in the pending list.
        mockMvc.perform(get("/v1/fraud/assessments/pending")
                        .with(authentication(TestSecurityConfig.authForRole("admin", "tenant-A"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='" + id + "')]").exists());

        // tenant-B does NOT see tenant-A's assessment.
        mockMvc.perform(get("/v1/fraud/assessments/pending")
                        .with(authentication(TestSecurityConfig.authForRole("admin", "tenant-B"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='" + id + "')]").doesNotExist());
    }

    // --- b2b Vendor Payment (SEC-23-requested money-moving cross-tenant coverage; controller already
    //     fixed under SEC-BATCH-1 — this adds the missing IT coverage) -------------------------------

    /** Seeds a vendor payment under {@code tenant-A} and returns its id. */
    private String seedVendorPayment() throws Exception {
        MvcResult created = mockMvc.perform(post("/v1/vendor-payments")
                        .with(authentication(TestSecurityConfig.authForRole("admin", "tenant-A")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"vendorId":"vendor-A","amount":50000,"currency":"USD","method":"ACH"}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(created.getResponse().getContentAsString(), "$.paymentId");
    }

    @Test
    @DisplayName("Vendor payment: tenant B cannot read tenant A's payment (404)")
    void vendorPayment_crossTenantGet_returns404() throws Exception {
        String paymentId = seedVendorPayment();

        mockMvc.perform(get("/v1/vendor-payments/" + paymentId)
                        .with(authentication(TestSecurityConfig.authForRole("admin", "tenant-A"))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/v1/vendor-payments/" + paymentId)
                        .with(authentication(TestSecurityConfig.authForRole("admin", "tenant-B"))))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Vendor payment: tenant B cannot approve tenant A's payment (404); not APPROVED")
    void vendorPayment_crossTenantApprove_returns404_andNotApproved() throws Exception {
        String paymentId = seedVendorPayment();

        // Money-moving approve as tenant-B → 404, must not fire cross-tenant.
        mockMvc.perform(post("/v1/vendor-payments/" + paymentId + "/approve")
                        .with(authentication(TestSecurityConfig.authForRole("admin", "tenant-B"))))
                .andExpect(status().isNotFound());

        // tenant-A re-reads: status is not APPROVED (the foreign approve did not act).
        mockMvc.perform(get("/v1/vendor-payments/" + paymentId)
                        .with(authentication(TestSecurityConfig.authForRole("admin", "tenant-A"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(org.hamcrest.Matchers.not("APPROVED")));
    }
}
