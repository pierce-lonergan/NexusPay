package io.nexuspay.payment.application.service.paymentmethod;

import io.nexuspay.common.exception.InvalidRequestException;
import io.nexuspay.common.metadata.MetadataSanitizer;
import io.nexuspay.common.tenant.TenantOwnership;
import io.nexuspay.payment.adapter.out.mock.TestPaymentMethodFixtures;
import io.nexuspay.payment.adapter.out.mock.TestPaymentMethodFixtures.FixtureCard;
import io.nexuspay.payment.application.port.out.PaymentMethodRepository;
import io.nexuspay.payment.application.service.customer.CustomerService;
import io.nexuspay.payment.domain.customer.Customer;
import io.nexuspay.payment.domain.paymentmethod.PaymentMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Orchestrates the saved payment-method lifecycle (TEST-3b): attach / list / retrieve / detach. Mirrors
 * {@code CustomerService}'s tenant-scoped, no-oracle access pattern.
 *
 * <p>Collaborators are all IN-MODULE: {@link PaymentMethodRepository}, the merged 3a
 * {@link CustomerService} (so the {@code pm_ -> cus_} reference is an in-module edge — NO new
 * Spring-Modulith dependency, and NO {@code payment-orchestration -> gateway-api} edge: the credential is
 * an opaque string), and the static {@link TestPaymentMethodFixtures} registry.</p>
 *
 * <h3>PCI (SEC-BATCH-3)</h3>
 * <p>This service NEVER accepts or stores a raw PAN. In TEST mode a fixture token resolves to canned
 * display fields + a synthetic opaque {@code credentialRef}; in LIVE mode the integrator supplies an
 * already-tokenized opaque ref stored verbatim (display fields from the request). The controller actively
 * rejects a PAN-like top-level field BEFORE this service is even called; {@link MetadataSanitizer} strips
 * any card material from the metadata map here too (belt-and-suspenders).</p>
 *
 * @since TEST-3b
 */
@Service
public class PaymentMethodService {

    private static final Logger log = LoggerFactory.getLogger(PaymentMethodService.class);

    /**
     * PCI (SEC-BATCH-3) value-level backstop: a run of 13-19 digits (after stripping spaces/dashes) is
     * PAN-shaped. ANY field that could carry a PAN on the LIVE/opaque path ({@code credentialRef},
     * {@code last4}, {@code brand}, {@code funding}) is rejected (400) if it matches — so the field most
     * likely to receive a pasted card number ({@code credential_ref}, the "chargeable handle") is actively
     * defended, not merely "happened not to be parsed". The whole-string match keeps a legitimate opaque
     * token (e.g. {@code ptok_live_abc123}) accepted while a bare 16-digit PAN is refused.
     */
    private static final Pattern PAN_SHAPE = Pattern.compile("\\d{13,19}");

    /** {@code last4} on the LIVE/opaque path must be exactly 4 digits (or absent). */
    private static final Pattern LAST4_SHAPE = Pattern.compile("\\d{4}");

    /** Defensive caps for free-form display strings supplied on the live path. */
    private static final int MAX_BRAND_LEN = 32;
    private static final int MAX_FUNDING_LEN = 32;

    private final PaymentMethodRepository paymentMethodRepository;
    private final CustomerService customerService;

    public PaymentMethodService(PaymentMethodRepository paymentMethodRepository,
                                CustomerService customerService) {
        this.paymentMethodRepository = paymentMethodRepository;
        this.customerService = customerService;
    }

    /**
     * Attaches a saved method to a tenant-owned customer.
     *
     * <ol>
     *   <li>SEC-26: resolve {@code customerId} tenant-scoped via {@link CustomerService#findById} ->
     *       404 ({@link TenantOwnership#require}) if it is not the caller's. A foreign customer id is
     *       indistinguishable from a missing one (no oracle); you cannot attach to another tenant's
     *       customer.</li>
     *   <li>LIVEMODE MATCH: the method's {@code livemode} (= caller key mode) MUST equal the resolved
     *       customer's livemode -> {@link InvalidRequestException} (400) on mismatch.</li>
     *   <li>CREDENTIAL MODEL: a fixture token ({@code pm_card_*}) requires {@code isTest} (else 400 — the
     *       mode gate) and resolves to canned display + a synthetic opaque ref; otherwise the LIVE/opaque
     *       path stores the token verbatim as {@code credentialRef}, display fields from the request.</li>
     *   <li>{@link MetadataSanitizer#sanitize} the metadata, then {@code PaymentMethod.create} + save.</li>
     * </ol>
     *
     * @param tenantId            authenticated caller's tenant (CallerTenant.require())
     * @param customerId          the target {@code cus_} (validated tenant-scoped here)
     * @param livemode            caller key mode (CallerMode.isLive()) — stamped onto the method
     * @param isTest              caller key mode (CallerMode.isTest()) — gates the fixture path
     * @param type                method type (e.g. {@code "card"}); defaults to {@code "card"} if blank
     * @param credentialOrFixture a fixture token (test) OR an opaque pre-tokenized ref (live) — NEVER a PAN
     * @param brand/last4/...     display fields used on the LIVE/opaque path (ignored on the fixture path)
     * @param metadata            free-form client metadata (sanitized before persist)
     */
    @Transactional
    public PaymentMethod attach(String tenantId, String customerId, boolean livemode, boolean isTest,
                                String type, String credentialOrFixture,
                                String brand, String last4, Integer expMonth, Integer expYear,
                                String funding, Map<String, Object> metadata) {

        // (1) SEC-26 customer ownership: resolve tenant-scoped or 404 (no oracle). A foreign/absent
        // customerId is indistinguishable — you cannot attach to another tenant's customer.
        Customer customer = TenantOwnership.require(
                customerService.findById(customerId, tenantId), "Customer");

        // (2) LIVEMODE MATCH: cannot attach a test method to a live customer or vice-versa.
        if (customer.isLivemode() != livemode) {
            throw new InvalidRequestException(
                    "Payment method livemode does not match the customer's livemode", "livemode_mismatch");
        }

        if (credentialOrFixture == null || credentialOrFixture.isBlank()) {
            throw new InvalidRequestException(
                    "A credential_ref (a test fixture token or an opaque tokenized reference) is required",
                    "missing_credential");
        }

        String resolvedType = (type == null || type.isBlank()) ? "card" : type;

        String credentialRef;
        String resolvedBrand;
        String resolvedLast4;
        Integer resolvedExpMonth;
        Integer resolvedExpYear;
        String resolvedFunding;

        // (3) CREDENTIAL MODEL.
        if (TestPaymentMethodFixtures.isFixture(credentialOrFixture)) {
            // A fixture token (pm_card_*) is TEST-MODE ONLY — the hard mode gate. Under a live key -> 400.
            if (!isTest) {
                throw new InvalidRequestException(
                        "Test fixture payment methods (pm_card_*) are only accepted with a test key",
                        "live_mode_fixture");
            }
            // An UNKNOWN pm_card_* is rejected (400) rather than silently stored with no display fields.
            FixtureCard fixture = TestPaymentMethodFixtures.resolve(credentialOrFixture)
                    .orElseThrow(() -> new InvalidRequestException(
                            "Unknown test fixture payment method token", "unknown_fixture"));
            // The fixture is authoritative on this path — the request's own display fields are ignored.
            credentialRef = fixture.credentialRef();
            resolvedBrand = fixture.brand();
            resolvedLast4 = fixture.last4();
            resolvedExpMonth = fixture.expMonth();
            resolvedExpYear = fixture.expYear();
            resolvedFunding = fixture.funding();
        } else {
            // LIVE / opaque path: store the already-tokenized reference VERBATIM; the server does NOT parse
            // a PAN — display fields come from the request body. (A real opaque token under a TEST key is
            // allowed; it just isn't fixture-resolvable.) Full live tokenize/charge resolution is 3c.
            //
            // PCI (SEC-BATCH-3): credential_ref is the field an integrator is most likely to paste a raw PAN
            // into ("the chargeable card handle"). Apply a value-level PAN-shape backstop to it AND to the
            // display fields supplied on this path so a bare 13-19 digit card number is REJECTED (400),
            // never persisted — the difference between "we cannot accept a PAN" and "we happen not to parse
            // one". A legitimate opaque token (ptok_…/PSP id) contains non-digits and passes.
            rejectPanShape(credentialOrFixture, "credential_ref");
            rejectPanShape(brand, "brand");
            rejectPanShape(funding, "funding");
            // last4 must be exactly 4 digits (or absent) — a full PAN smuggled into last4 is rejected 400
            // here rather than relying on the DB column width (which would 500 or silently truncate).
            if (last4 != null && !LAST4_SHAPE.matcher(last4).matches()) {
                throw new InvalidRequestException(
                        "last4 must be exactly 4 digits", "invalid_last4");
            }
            if (brand != null && brand.length() > MAX_BRAND_LEN) {
                throw new InvalidRequestException("brand is too long", "invalid_brand");
            }
            if (funding != null && funding.length() > MAX_FUNDING_LEN) {
                throw new InvalidRequestException("funding is too long", "invalid_funding");
            }

            credentialRef = credentialOrFixture;
            resolvedBrand = brand;
            resolvedLast4 = last4;
            resolvedExpMonth = expMonth;
            resolvedExpYear = expYear;
            resolvedFunding = funding;
        }

        // (4) Sanitize metadata (strip PAN/card keys + __-reserved control keys at any depth), then persist.
        Map<String, Object> safeMetadata = metadata != null ? MetadataSanitizer.sanitize(metadata) : null;

        PaymentMethod pm = PaymentMethod.create(
                tenantId, customer.getId(), livemode, resolvedType,
                resolvedBrand, resolvedLast4, resolvedExpMonth, resolvedExpYear, resolvedFunding,
                credentialRef, safeMetadata);
        pm = paymentMethodRepository.save(pm);
        // NEVER log credential_ref. Mirror CustomerService's id/tenant/livemode log line.
        log.info("Payment method attached: id={}, customer={}, tenant={}, livemode={}",
                pm.getId(), customer.getId(), tenantId, livemode);
        return pm;
    }

    /**
     * SEC-26: tenant-scoped by-id lookup for {@code GET /v1/payment_methods/{id}}. Returns the method only
     * when it belongs to {@code tenantId} and is not detached; an absent, foreign-tenant, OR detached id
     * yields empty so the controller 404s identically (no oracle).
     */
    public Optional<PaymentMethod> findById(String id, String tenantId) {
        return paymentMethodRepository.findByIdAndTenantId(id, tenantId);
    }

    /**
     * SEC-26: lists a customer's live (non-detached) saved methods for {@code GET
     * /v1/customers/{customerId}/payment_methods}. Resolves the customer tenant-scoped FIRST (404 no-oracle
     * on a foreign/absent customer — NOT an empty list, which would still confirm the route and diverge
     * from attach), then enumerates via the tenant-scoped finder, paginated by {@code limit}/{@code offset}
     * (mirrors {@link CustomerService#listByTenant}).
     */
    public List<PaymentMethod> listByCustomer(String customerId, String tenantId, int limit, int offset) {
        TenantOwnership.require(customerService.findById(customerId, tenantId), "Customer");
        return paymentMethodRepository.findByCustomerAndTenant(customerId, tenantId, limit, offset);
    }

    /**
     * DETACH = soft delete a method the caller owns (sets {@code deleted_at}). Resolves via
     * {@link #getOrThrow} so a tenant-A caller cannot detach (or probe) a tenant-B method — a
     * foreign/absent/already-detached id 404s (no oracle). After detach the method no longer appears in
     * retrieve or the customer's list.
     */
    @Transactional
    public PaymentMethod detach(String id, String tenantId) {
        PaymentMethod pm = getOrThrow(id, tenantId);
        pm.markDeleted();
        pm = paymentMethodRepository.save(pm);
        log.info("Payment method detached: id={}, tenant={}", id, tenantId);
        return pm;
    }

    // -- Helpers --

    /**
     * SEC-26: tenant-scoped fetch-or-404 for REST mutations. Pairs the tenant-scoped finder with
     * {@link TenantOwnership#require} so a tenant-A caller cannot read/mutate a tenant-B method by id.
     */
    private PaymentMethod getOrThrow(String id, String tenantId) {
        return TenantOwnership.require(
                paymentMethodRepository.findByIdAndTenantId(id, tenantId), "Payment method");
    }

    /**
     * PCI (SEC-BATCH-3): rejects (400) a value that contains a PAN-shaped 13-19 digit run after stripping
     * spaces/dashes (the common card-number presentations). Applied to the live-path credential_ref and the
     * display fields so a raw card number pasted into any of them is refused before persist. A {@code null}/
     * blank value passes (the caller validates required-ness separately).
     */
    private void rejectPanShape(String value, String field) {
        if (value == null || value.isBlank()) {
            return;
        }
        String normalized = value.replace(" ", "").replace("-", "");
        if (PAN_SHAPE.matcher(normalized).find()) {
            throw new InvalidRequestException(
                    "Raw card data must never be sent to this endpoint; supply a tokenized credential_ref, "
                            + "not a card number (field: " + field + ")",
                    "raw_card_data_rejected");
        }
    }
}
