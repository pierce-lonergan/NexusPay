package io.nexuspay.payment.application.service.mandate;

import io.nexuspay.common.exception.InvalidRequestException;
import io.nexuspay.common.metadata.MetadataSanitizer;
import io.nexuspay.common.tenant.TenantOwnership;
import io.nexuspay.payment.application.port.out.MandateRepository;
import io.nexuspay.payment.application.service.paymentmethod.PaymentMethodService;
import io.nexuspay.payment.domain.mandate.Mandate;
import io.nexuspay.payment.domain.paymentmethod.PaymentMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Orchestrates the mandate / consent lifecycle (TEST-3d): create / retrieve / list / revoke, plus the
 * off-session charge consent gate ({@link #validateActiveForCharge}). Mirrors {@code PaymentMethodService}'s
 * tenant-scoped, no-oracle access pattern.
 *
 * <p>Collaborators are all IN-MODULE: {@link MandateRepository} + the merged 3b {@link PaymentMethodService}
 * (so the {@code mandate_ -> pm_ -> cus_} reference chain is an in-module edge — NO new Spring-Modulith
 * dependency, and NO {@code payment-orchestration -> gateway-api} edge).</p>
 *
 * <h3>Consent integrity (the load-bearing constraint)</h3>
 * <p>A mandate is created FROM a tenant-owned {@code pm_}: the {@code pm_} is resolved tenant-scoped (404
 * no-oracle on a foreign/missing one), its {@code livemode} must equal the caller key mode (400 on
 * mismatch), and the mandate's {@code customer} is DERIVED from the resolved {@code pm_}'s owner — never a
 * client-supplied customer that could disagree with the {@code pm_}'s real owner.</p>
 *
 * @since TEST-3d
 */
@Service
public class MandateService {

    private static final Logger log = LoggerFactory.getLogger(MandateService.class);

    private static final Set<String> VALID_TYPES = Set.of(Mandate.TYPE_MULTI_USE, Mandate.TYPE_SINGLE_USE);

    private final MandateRepository mandateRepository;
    private final PaymentMethodService paymentMethodService;

    public MandateService(MandateRepository mandateRepository,
                          PaymentMethodService paymentMethodService) {
        this.mandateRepository = mandateRepository;
        this.paymentMethodService = paymentMethodService;
    }

    /**
     * Records a mandate (consent) from a tenant-owned saved method.
     *
     * <ol>
     *   <li>SEC-26: resolve {@code paymentMethodId} tenant-scoped via {@link PaymentMethodService#findById}
     *       -> 404 ({@link TenantOwnership#require}) if it is not the caller's. A foreign/missing/detached
     *       {@code pm_} is indistinguishable (no oracle); you cannot record a mandate against another
     *       tenant's {@code pm_}.</li>
     *   <li>LIVEMODE MATCH: the mandate's {@code livemode} (= caller key mode) MUST equal the resolved
     *       {@code pm_}'s livemode -> 400 ({@code livemode_mismatch}) on mismatch. A TEST {@code pm_} never
     *       anchors a LIVE-mode mandate and vice-versa.</li>
     *   <li>CUSTOMER DERIVED: the mandate's customer is {@code pm.getCustomerId()} — never client-supplied.</li>
     *   <li>TYPE: defaults to {@link Mandate#TYPE_MULTI_USE} if blank; otherwise must be in
     *       {MULTI_USE, SINGLE_USE} else 400 ({@code invalid_type}).</li>
     *   <li>{@link MetadataSanitizer#sanitize} the metadata, then {@code Mandate.create} (status ACTIVE) +
     *       save.</li>
     * </ol>
     *
     * @param tenantId        authenticated caller's tenant (CallerTenant.require())
     * @param livemode        caller key mode (CallerMode.isLive()) — stamped onto the mandate
     * @param isTest          caller key mode (CallerMode.isTest()) — currently unused on this path but kept
     *                        for parity with the attach signature / future fixture gating
     * @param paymentMethodId the {@code pm_} the consent authorizes (validated tenant-scoped here)
     * @param type            {@code MULTI_USE} / {@code SINGLE_USE}; defaults to MULTI_USE if blank
     * @param scenario        e.g. {@code recurring} / {@code unscheduled} (nullable, free-form hint)
     * @param metadata        free-form client metadata (sanitized before persist)
     */
    @Transactional
    public Mandate create(String tenantId, boolean livemode, boolean isTest, String paymentMethodId,
                          String type, String scenario, Map<String, Object> metadata) {

        // (1) SEC-26 pm_ ownership: resolve tenant-scoped or 404 (no oracle). A foreign/absent/detached
        // paymentMethodId is indistinguishable — you cannot record a mandate against another tenant's pm_.
        PaymentMethod pm = TenantOwnership.require(
                paymentMethodService.findById(paymentMethodId, tenantId), "Payment method");

        // (2) LIVEMODE MATCH: a TEST pm_ never anchors a LIVE-mode mandate (and vice-versa).
        if (pm.isLivemode() != livemode) {
            throw new InvalidRequestException(
                    "Mandate livemode does not match the payment method's livemode", "livemode_mismatch");
        }

        // (3) CUSTOMER DERIVED from the resolved pm_'s owner — never a client-supplied customer.
        String customerId = pm.getCustomerId();

        // (4) TYPE: default MULTI_USE if blank; validate the vocabulary.
        String resolvedType = (type == null || type.isBlank()) ? Mandate.TYPE_MULTI_USE : type;
        if (!VALID_TYPES.contains(resolvedType)) {
            throw new InvalidRequestException(
                    "type must be one of MULTI_USE, SINGLE_USE", "invalid_type");
        }

        // (5) Sanitize metadata (strip PAN/card keys + __-reserved control keys at any depth), then persist.
        Map<String, Object> safeMetadata = metadata != null ? MetadataSanitizer.sanitize(metadata) : null;

        Mandate mandate = Mandate.create(
                tenantId, customerId, pm.getId(), livemode, resolvedType, scenario, safeMetadata);
        mandate = mandateRepository.save(mandate);
        log.info("Mandate created: id={}, customer={}, pm={}, tenant={}, livemode={}",
                mandate.getId(), customerId, pm.getId(), tenantId, livemode);
        return mandate;
    }

    /**
     * SEC-26: tenant-scoped by-id lookup for {@code GET /v1/mandates/{id}}. Returns the mandate only when it
     * belongs to {@code tenantId}; an absent OR foreign-tenant id yields empty so the controller 404s
     * identically (no oracle). A revoked (INACTIVE) mandate IS returned (no soft-delete filter).
     */
    public Optional<Mandate> findById(String id, String tenantId) {
        return mandateRepository.findByIdAndTenantId(id, tenantId);
    }

    /**
     * SEC-26: lists the caller tenant's mandates for {@code GET /v1/mandates}, newest first, paginated by
     * {@code limit}/{@code offset}. Enumerates only the caller tenant's rows (including revoked ones).
     */
    public List<Mandate> listByTenant(String tenantId, int limit, int offset) {
        return mandateRepository.findByTenant(tenantId, limit, offset);
    }

    /**
     * REVOKE = deactivate a mandate the caller owns ({@code status -> INACTIVE}, stamps {@code revoked_at}).
     * Resolves via {@link #getOrThrow} so a tenant-A caller cannot revoke (or probe) a tenant-B mandate — a
     * foreign/absent id 404s (no oracle). The mandate stays RETRIEVABLE after revoke (INACTIVE).
     */
    @Transactional
    public Mandate revoke(String id, String tenantId) {
        Mandate mandate = getOrThrow(id, tenantId);
        mandate.revoke();
        mandate = mandateRepository.save(mandate);
        log.info("Mandate revoked: id={}, tenant={}", id, tenantId);
        return mandate;
    }

    /**
     * The off-session charge CONSENT GATE (called by {@code OffSessionChargeService.charge} when a
     * {@code mandate_id} is cited). Three ordered checks:
     *
     * <ol>
     *   <li>EXISTS FOR TENANT: tenant-scoped resolve via {@link TenantOwnership#require} -> a
     *       foreign/missing mandate is empty -> {@code ResourceNotFoundException} (404 no-oracle); the
     *       gateway is NEVER reached.</li>
     *   <li>ACTIVE: a non-ACTIVE (revoked/INACTIVE/PENDING) mandate -> 400 ({@code invalid_mandate}); no
     *       charge.</li>
     *   <li>PM MATCH: the mandate's {@code paymentMethodId} MUST equal the {@code pm_} being charged
     *       (the trusted, already-tenant-resolved pm) -> else 400
     *       ({@code mandate_payment_method_mismatch}); no charge.</li>
     * </ol>
     *
     * <p><b>TYPE is intentionally NOT consulted here.</b> A {@link Mandate#TYPE_SINGLE_USE} mandate is a
     * recorded descriptive hint in 3d, not an enforced control: the gate does NOT flip it to a terminal
     * state after a charge, so a SINGLE_USE mandate stays ACTIVE and can pass this gate on more than one
     * off-session charge (Stripe parity; the blueprint scopes the gate to exactly exists + ACTIVE + pm-match).
     * Single-use consumption is a deferred increment; if added, the terminal transition must happen in
     * {@code OffSessionChargeService} ONLY after a SUCCEEDED gateway result and be idempotency-safe — NOT
     * inside this validation method. See {@link Mandate#TYPE_SINGLE_USE}.</p>
     *
     * @param mandateId       the cited {@code mandate_} (non-null/non-blank — the caller guards the
     *                        back-compat null path)
     * @param tenantId        the TRUSTED caller tenant
     * @param paymentMethodId the {@code pm_} being charged (already resolved tenant-scoped by the caller)
     */
    public void validateActiveForCharge(String mandateId, String tenantId, String paymentMethodId) {
        // (1) EXISTS FOR TENANT (404 no-oracle on foreign/missing).
        Mandate mandate = TenantOwnership.require(
                mandateRepository.findByIdAndTenantId(mandateId, tenantId), "Mandate");

        // (2) ACTIVE (covers revoked/INACTIVE/PENDING).
        if (!mandate.isActive()) {
            throw new InvalidRequestException("Mandate is not active", "invalid_mandate");
        }

        // (3) PM MATCH against the trusted tenant-owned pm_.
        if (!mandate.getPaymentMethodId().equals(paymentMethodId)) {
            throw new InvalidRequestException(
                    "Mandate does not authorize this payment method", "mandate_payment_method_mismatch");
        }
    }

    // -- Helpers --

    /**
     * SEC-26: tenant-scoped fetch-or-404 for REST mutations. Pairs the tenant-scoped finder with
     * {@link TenantOwnership#require} so a tenant-A caller cannot mutate a tenant-B mandate by id.
     */
    private Mandate getOrThrow(String id, String tenantId) {
        return TenantOwnership.require(
                mandateRepository.findByIdAndTenantId(id, tenantId), "Mandate");
    }
}
