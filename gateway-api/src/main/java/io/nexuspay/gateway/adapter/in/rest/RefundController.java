package io.nexuspay.gateway.adapter.in.rest;

import io.nexuspay.gateway.adapter.in.rest.dto.RefundApiResponse;
import io.nexuspay.payment.application.port.PaymentGatewayPort;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/refunds")
@Tag(name = "Refunds", description = "Refund retrieval")
public class RefundController {

    private final PaymentGatewayPort paymentGateway;

    public RefundController(PaymentGatewayPort paymentGateway) {
        this.paymentGateway = paymentGateway;
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('admin', 'operator', 'viewer')")
    @Operation(summary = "Retrieve a refund")
    public ResponseEntity<RefundApiResponse> getRefund(@PathVariable String id) {
        var response = paymentGateway.getRefund(id);
        return ResponseEntity.ok(ResponseMapper.toRefundResponse(response));
    }
}
