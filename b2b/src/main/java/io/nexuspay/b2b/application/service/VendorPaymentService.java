package io.nexuspay.b2b.application.service;

import io.nexuspay.b2b.application.port.in.ManageVendorPaymentUseCase;
import io.nexuspay.b2b.application.port.out.B2bEventPublisher;
import io.nexuspay.b2b.application.port.out.B2bRepository;
import io.nexuspay.b2b.application.port.out.VendorPaymentExecutionPort;
import io.nexuspay.b2b.domain.VendorPayment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Service for vendor payment creation, approval, batching, and execution.
 *
 * @since 0.4.2 (Sprint 4.3)
 */
@Service
public class VendorPaymentService implements ManageVendorPaymentUseCase {

    private static final Logger log = LoggerFactory.getLogger(VendorPaymentService.class);

    private final B2bRepository repository;
    private final VendorPaymentExecutionPort executionPort;
    private final B2bEventPublisher eventPublisher;

    public VendorPaymentService(B2bRepository repository,
                                 VendorPaymentExecutionPort executionPort,
                                 B2bEventPublisher eventPublisher) {
        this.repository = repository;
        this.executionPort = executionPort;
        this.eventPublisher = eventPublisher;
    }

    @Override
    @Transactional
    public VendorPaymentResult createVendorPayment(CreateVendorPaymentCommand command) {
        VendorPayment payment = VendorPayment.create(
                command.tenantId(), command.vendorId(), command.amount(),
                command.currency(), command.method());

        if (command.remittanceInfo() != null) {
            payment.setRemittanceInfo(command.remittanceInfo());
        }
        if (command.scheduledAt() != null) {
            payment.setScheduledAt(command.scheduledAt());
        }

        payment = repository.saveVendorPayment(payment);

        eventPublisher.publishEvent("VendorPayment", payment.getId(), "VendorPaymentCreated",
                Map.of("vendorId", command.vendorId(), "amount", command.amount(),
                        "currency", command.currency(), "method", command.method().name(),
                        "tenantId", command.tenantId()),
                command.tenantId());

        log.info("Vendor payment created: id={}, vendor={}, amount={}{}", payment.getId(),
                command.vendorId(), command.amount(), command.currency());

        return toResult(payment);
    }

    @Override
    @Transactional
    public VendorPaymentResult approveVendorPayment(String paymentId, String tenantId) {
        VendorPayment payment = findOrThrow(paymentId);
        payment.approve();
        payment = repository.saveVendorPayment(payment);

        eventPublisher.publishEvent("VendorPayment", paymentId, "VendorPaymentApproved",
                Map.of("tenantId", tenantId), tenantId);

        log.info("Vendor payment approved: id={}", paymentId);
        return toResult(payment);
    }

    @Override
    @Transactional
    public List<VendorPaymentResult> createBatch(List<CreateVendorPaymentCommand> commands, String tenantId) {
        String batchId = "batch_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        List<VendorPaymentResult> results = new ArrayList<>();

        for (CreateVendorPaymentCommand command : commands) {
            VendorPayment payment = VendorPayment.create(
                    command.tenantId(), command.vendorId(), command.amount(),
                    command.currency(), command.method());
            payment.assignToBatch(batchId);
            if (command.remittanceInfo() != null) {
                payment.setRemittanceInfo(command.remittanceInfo());
            }

            payment = repository.saveVendorPayment(payment);
            results.add(toResult(payment));
        }

        eventPublisher.publishEvent("VendorPayment", batchId, "VendorPaymentBatchCreated",
                Map.of("batchId", batchId, "count", commands.size(), "tenantId", tenantId),
                tenantId);

        log.info("Vendor payment batch created: batchId={}, count={}", batchId, commands.size());
        return results;
    }

    @Override
    @Transactional(readOnly = true)
    public VendorPaymentResult getVendorPayment(String paymentId, String tenantId) {
        return toResult(findOrThrow(paymentId));
    }

    private VendorPayment findOrThrow(String paymentId) {
        return repository.findVendorPaymentById(paymentId)
                .orElseThrow(() -> new IllegalArgumentException("Vendor payment not found: " + paymentId));
    }

    private VendorPaymentResult toResult(VendorPayment vp) {
        return new VendorPaymentResult(
                vp.getId(), vp.getVendorId(), vp.getAmount(), vp.getCurrency(),
                vp.getMethod(), vp.getStatus(), vp.getBatchId(), vp.getRemittanceInfo(),
                vp.getExternalReference(), vp.getScheduledAt(), vp.getPaidAt(), vp.getCreatedAt());
    }
}
