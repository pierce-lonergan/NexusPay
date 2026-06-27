package io.nexuspay.payment.adapter.out.hyperswitch;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.nexuspay.common.exception.PaymentException;
import io.nexuspay.payment.adapter.out.hyperswitch.dto.*;
import io.nexuspay.payment.application.port.PaymentGatewayPort;
import io.nexuspay.payment.domain.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * HyperSwitch adapter implementing PaymentGatewayPort.
 *
 * This is a hand-written thin client covering the ~10 HyperSwitch endpoints
 * NexusPay uses. We chose this over OpenAPI codegen because HyperSwitch's
 * utoipa-generated spec has known oneOf polymorphism issues that break
 * openapi-generator (see ADR-002).
 *
 * All methods:
 *  - Are wrapped with Resilience4j circuit breaker ("hyperswitch" instance)
 *  - Propagate the caller's Idempotency-Key to HyperSwitch
 *  - Log canonical fields via MDC (payment_id, connector)
 *  - Map HyperSwitch DTOs to domain objects via HyperSwitchResponseMapper
 */
@Component
public class HyperSwitchPaymentAdapter implements PaymentGatewayPort {

    private static final Logger log = LoggerFactory.getLogger(HyperSwitchPaymentAdapter.class);
    private final RestClient restClient;

    public HyperSwitchPaymentAdapter(RestClient hyperSwitchRestClient) {
        this.restClient = hyperSwitchRestClient;
    }

    @Override
    @CircuitBreaker(name = "hyperswitch", fallbackMethod = "createPaymentFallback")
    public PaymentResponse createPayment(PaymentRequest request) {
        log.info("Creating payment: amount={} currency={}", request.amount(), request.currency());

        var hsRequest = new HsPaymentCreateRequest(
                request.amount(),
                request.currency(),
                request.customerId(),
                request.captureMethod() != null ? request.captureMethod() : "automatic",
                request.description(),
                request.returnUrl(),
                request.metadata(),
                // TEST-3c: pure passthrough of the off-session hints. All null for an inline-card create,
                // so the wire body is byte-identical (HsPaymentCreateRequest is @JsonInclude(NON_NULL)).
                request.paymentMethod(),
                request.offSession(),
                request.setupFutureUsage(),
                request.mandateId()
        );

        try {
            HsPaymentResponse response = restClient.post()
                    .uri("/payments")
                    .header("Idempotency-Key", request.idempotencyKey())
                    .body(hsRequest)
                    .retrieve()
                    .body(HsPaymentResponse.class);

            PaymentResponse result = HyperSwitchResponseMapper.toPaymentResponse(response);
            MDC.put("payment_id", result.gatewayPaymentId());
            log.info("Payment created: id={} status={} connector={}",
                    result.gatewayPaymentId(), result.status(), result.connectorName());
            return result;
        } catch (RestClientException e) {
            throw PaymentException.gatewayError("Failed to create payment at HyperSwitch", e);
        }
    }

    @Override
    @CircuitBreaker(name = "hyperswitch")
    public PaymentResponse confirmPayment(String paymentId, ConfirmRequest request) {
        log.info("Confirming payment: id={}", paymentId);

        var hsRequest = new HsConfirmRequest(
                request.paymentMethodType(),
                request.paymentMethodType(),
                null, // paymentMethodData mapped separately if needed
                request.returnUrl()
        );

        try {
            HsPaymentResponse response = restClient.post()
                    .uri("/payments/{id}/confirm", paymentId)
                    .header("Idempotency-Key", request.idempotencyKey())
                    .body(hsRequest)
                    .retrieve()
                    .body(HsPaymentResponse.class);

            return HyperSwitchResponseMapper.toPaymentResponse(response);
        } catch (RestClientException e) {
            throw PaymentException.gatewayError("Failed to confirm payment " + paymentId, e);
        }
    }

    @Override
    @CircuitBreaker(name = "hyperswitch")
    public PaymentResponse capturePayment(String paymentId, CaptureRequest request) {
        log.info("Capturing payment: id={} amount={}", paymentId, request.amountToCapture());

        var hsRequest = new HsCaptureRequest(request.amountToCapture());

        try {
            HsPaymentResponse response = restClient.post()
                    .uri("/payments/{id}/capture", paymentId)
                    .header("Idempotency-Key", request.idempotencyKey())
                    .body(hsRequest)
                    .retrieve()
                    .body(HsPaymentResponse.class);

            return HyperSwitchResponseMapper.toPaymentResponse(response);
        } catch (RestClientException e) {
            throw PaymentException.gatewayError("Failed to capture payment " + paymentId, e);
        }
    }

    @Override
    @CircuitBreaker(name = "hyperswitch")
    public PaymentResponse voidPayment(String paymentId, VoidRequest request) {
        log.info("Voiding payment: id={} reason={}", paymentId, request.cancellationReason());

        var hsRequest = new HsCancelRequest(request.cancellationReason());

        try {
            HsPaymentResponse response = restClient.post()
                    .uri("/payments/{id}/cancel", paymentId)
                    .header("Idempotency-Key", request.idempotencyKey())
                    .body(hsRequest)
                    .retrieve()
                    .body(HsPaymentResponse.class);

            return HyperSwitchResponseMapper.toPaymentResponse(response);
        } catch (RestClientException e) {
            throw PaymentException.gatewayError("Failed to void payment " + paymentId, e);
        }
    }

    @Override
    @CircuitBreaker(name = "hyperswitch")
    public PaymentResponse getPayment(String paymentId) {
        try {
            HsPaymentResponse response = restClient.get()
                    .uri("/payments/{id}", paymentId)
                    .retrieve()
                    .body(HsPaymentResponse.class);

            return HyperSwitchResponseMapper.toPaymentResponse(response);
        } catch (RestClientException e) {
            throw PaymentException.gatewayError("Failed to retrieve payment " + paymentId, e);
        }
    }

    @Override
    @CircuitBreaker(name = "hyperswitch")
    public RefundResponse createRefund(RefundRequest request) {
        log.info("Creating refund: paymentId={} amount={}", request.paymentId(), request.amount());

        var hsRequest = new HsRefundCreateRequest(
                request.paymentId(),
                request.amount(),
                request.currency(),
                request.reason()
        );

        try {
            HsRefundResponse response = restClient.post()
                    .uri("/refunds")
                    .header("Idempotency-Key", request.idempotencyKey())
                    .body(hsRequest)
                    .retrieve()
                    .body(HsRefundResponse.class);

            return HyperSwitchResponseMapper.toRefundResponse(response);
        } catch (RestClientException e) {
            throw PaymentException.gatewayError("Failed to create refund for " + request.paymentId(), e);
        }
    }

    @Override
    @CircuitBreaker(name = "hyperswitch")
    public RefundResponse getRefund(String refundId) {
        try {
            HsRefundResponse response = restClient.get()
                    .uri("/refunds/{id}", refundId)
                    .retrieve()
                    .body(HsRefundResponse.class);

            return HyperSwitchResponseMapper.toRefundResponse(response);
        } catch (RestClientException e) {
            throw PaymentException.gatewayError("Failed to retrieve refund " + refundId, e);
        }
    }

    // Circuit breaker fallback
    @SuppressWarnings("unused")
    private PaymentResponse createPaymentFallback(PaymentRequest request, Throwable t) {
        log.error("Circuit breaker OPEN for HyperSwitch — payment creation unavailable", t);
        throw PaymentException.gatewayError(
                "Payment gateway is temporarily unavailable. Please retry.", t);
    }
}
