package io.nexuspay.payment.application.service;

import io.nexuspay.common.exception.InvalidRequestException;
import io.nexuspay.common.tenant.TenantOwnership;
import io.nexuspay.payment.adapter.out.mock.MockPaymentGatewayPort;
import io.nexuspay.payment.adapter.out.mock.TestPaymentMethodFixtures;
import io.nexuspay.payment.application.port.PaymentGatewayPort;
import io.nexuspay.payment.application.screening.CallContext;
import io.nexuspay.payment.application.service.paymentmethod.PaymentMethodService;
import io.nexuspay.payment.domain.PaymentRequest;
import io.nexuspay.payment.domain.PaymentResponse;
import io.nexuspay.payment.domain.paymentmethod.PaymentMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * TEST-3c: the SINGLE off-session orchestration point — charging a SAVED payment method ({@code pm_},
 * TEST-3b) attached to a customer ({@code cus_}, TEST-3a) when the cardholder is NOT present.
 *
 * <p>This lives in {@code payment-orchestration} (NOT {@code gateway-api}) so it can resolve the
 * {@code pm_} via the in-module {@link PaymentMethodService} and call the payment port directly, with NO
 * new Spring-Modulith edge. {@code gateway-api}'s {@code PaymentController} DELEGATES here over the
 * existing {@code gateway-api -> payment-orchestration} dependency; this service NEVER imports
 * {@code gateway-api}.</p>
 *
 * <h3>Money- and tenant-safety (the load-bearing invariants)</h3>
 * <ol>
 *   <li><b>Tenant-scoped resolve, no oracle.</b> The {@code pm_} is fetched via
 *       {@link PaymentMethodService#findById(String, String)} ({@code findByIdAndTenantId} pushed to SQL,
 *       soft-deleted excluded) + {@link TenantOwnership#require}. A foreign / missing / detached
 *       {@code pm_} is INDISTINGUISHABLE and yields a 404 — the gateway is NEVER reached, so no charge.</li>
 *   <li><b>livemode match.</b> The saved method's {@code livemode} MUST equal the caller key mode
 *       ({@code live == !isTest}); a mismatch is a 400 ({@code livemode_mismatch}), no charge. This upholds
 *       the CHARTER guarantee: a TEST {@code pm_} can never be charged on a LIVE key (and vice-versa), so
 *       {@code sk_live} never hits the mock and {@code sk_test} never hits the real PSP.</li>
 *   <li><b>PCI.</b> Only the opaque {@code credentialRef} (the chargeable handle) + the resolved
 *       {@code customerId} are forwarded to the PSP — never a raw PAN, never logged.</li>
 *   <li><b>Reused side-effects.</b> The single {@link PaymentGatewayPort#createPayment(PaymentRequest,
 *       CallContext)} call routes through the {@code @Primary GatedPaymentGateway}: the same fraud +
 *       sanctions screen (interactive rail), the same ledger/outbox/webhook synthesis (payment.captured /
 *       payment.failed), and — because the controller forwards the {@code Idempotency-Key} into
 *       {@code PaymentRequest.idempotencyKey} on the SAME {@code POST /v1/payments} path — the same
 *       Valkey-backed idempotency. NO parallel money path.</li>
 * </ol>
 *
 * <h3>TEST-mode forced outcome (single-sourced)</h3>
 * <p>When the call is TEST mode and the resolved {@code credentialRef} is a synthetic fixture ref
 * ({@code pmref_test_*}), {@link TestPaymentMethodFixtures#forcedOutcomeFor(String)} decodes the fixture
 * to a mock outcome ({@code "declined"} for {@code pm_card_chargeDeclined}, else none). When non-null the
 * service injects {@code __test_outcome} into a COPY of the metadata BEFORE the gateway call, so the
 * existing {@link MockPaymentGatewayPort} forced-failure path returns {@code STATUS_FAILED} and the gate
 * synthesizes {@code payment.failed} — no new mock concept, the prefix + outcome stay single-sourced in
 * the fixtures registry.</p>
 *
 * @since TEST-3c
 */
@Service
public class OffSessionChargeService {

    private static final Logger log = LoggerFactory.getLogger(OffSessionChargeService.class);

    private final PaymentMethodService paymentMethodService;
    /** Spring binds the {@code @Primary} {@code GatedPaymentGateway} here (gate + routing + side-effects). */
    private final PaymentGatewayPort paymentGateway;

    public OffSessionChargeService(PaymentMethodService paymentMethodService,
                                   PaymentGatewayPort paymentGateway) {
        this.paymentMethodService = paymentMethodService;
        this.paymentGateway = paymentGateway;
    }

    /**
     * Charges a saved payment method off-session.
     *
     * @param tenantId         the TRUSTED tenant from the authenticated principal (never a header/body)
     * @param paymentMethodId  the {@code pm_} saved-method id to charge (resolved tenant-scoped)
     * @param amount           amount in minor units (must be positive)
     * @param currency         ISO-4217 currency
     * @param offSession       off-session hint forwarded to the PSP (nullable)
     * @param setupFutureUsage future-usage hint (e.g. {@code off_session}) forwarded to the PSP (nullable)
     * @param mandateId        a 3d mandate hint — threaded through, no mandate resource is created (nullable)
     * @param isTest           caller key mode (test == {@code !principal.live()}); gates the fixture outcome
     * @param idempotencyKey   the caller {@code Idempotency-Key} (forwarded to the PSP; reuses the filter)
     * @param metadata         already-sanitized merchant metadata (the off-session control key is injected
     *                         into a COPY, never the caller's map)
     * @return the gateway {@link PaymentResponse} (STATUS_SUCCEEDED on success; STATUS_FAILED on a forced
     *         TEST decline) — the SAME shape as the inline-card create
     */
    public PaymentResponse charge(String tenantId, String paymentMethodId, long amount, String currency,
                                  Boolean offSession, String setupFutureUsage, String mandateId,
                                  boolean isTest, String idempotencyKey, Map<String, Object> metadata) {

        // (1) Resolve the pm_ TENANT-SCOPED -> 404 no-oracle. A foreign/missing/detached pm_ is empty ->
        // ResourceNotFoundException (-> 404). The gateway is NEVER reached on this path, so no charge.
        PaymentMethod pm = TenantOwnership.require(
                paymentMethodService.findById(paymentMethodId, tenantId), "Payment method");

        // (2) LIVEMODE MATCH: the caller key mode is live == !isTest. A TEST pm_ (livemode=false) under a
        // LIVE key, or a LIVE pm_ under a TEST key, is a 400 — no charge. Upholds the charter guarantee.
        boolean callerLive = !isTest;
        if (pm.isLivemode() != callerLive) {
            throw new InvalidRequestException(
                    "Payment method livemode does not match the caller key mode", "livemode_mismatch");
        }

        // (3) Read the opaque chargeable handle + the resolved customer (both server-trusted). NEVER a PAN;
        // credentialRef is NEVER logged.
        String credentialRef = pm.getCredentialRef();
        String customerId = pm.getCustomerId();
        String resolvedType = (pm.getType() == null || pm.getType().isBlank()) ? "card" : pm.getType();

        // (4) TEST-mode forced outcome (single-sourced in TestPaymentMethodFixtures). Inject the
        // __test_outcome control key into a COPY of the metadata BEFORE the gateway call, ONLY in TEST mode
        // and ONLY for a synthetic fixture ref. visa/mastercard/amex -> null -> no injection -> success.
        Map<String, Object> mergedMeta = metadata != null
                ? new LinkedHashMap<>(metadata) : new LinkedHashMap<>();
        if (isTest) {
            String forced = TestPaymentMethodFixtures.forcedOutcomeFor(credentialRef);
            if (forced != null) {
                mergedMeta.put(MockPaymentGatewayPort.TEST_OUTCOME_KEY, forced);
            }
        }

        // (5) Build the off-session PaymentRequest: resolved customer + opaque credentialRef as the
        // payment_method hint + the off-session fields. Auto-capture (off-session charges settle now).
        PaymentRequest req = new PaymentRequest(
                amount, currency, customerId, resolvedType, /* paymentMethodData (raw PAN path) */ null,
                /* returnUrl */ null, /* description */ null, /* captureMethod */ "automatic",
                idempotencyKey, mergedMeta.isEmpty() ? null : mergedMeta,
                /* paymentMethod = the opaque chargeable handle (NO PAN) */ credentialRef,
                offSession, setupFutureUsage, mandateId);

        log.info("Off-session charge: pm={}, customer={}, tenant={}, livemode={}, test={}",
                pm.getId(), customerId, tenantId, pm.isLivemode(), isTest);

        // (6) The ONE money path: same gate (interactive rail — an API-initiated off-session charge is
        // merchant-present), same idempotency, same ledger + webhook side-effects as the inline-card create.
        return paymentGateway.createPayment(req, CallContext.interactive(tenantId));
    }
}
