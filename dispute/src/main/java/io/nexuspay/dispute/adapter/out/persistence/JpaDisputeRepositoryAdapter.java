package io.nexuspay.dispute.adapter.out.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.nexuspay.dispute.application.port.out.DisputeRepository;
import io.nexuspay.dispute.domain.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * JPA implementation of {@link DisputeRepository}.
 *
 * @since 0.2.4 (Sprint 2.4)
 */
@Repository
public class JpaDisputeRepositoryAdapter implements DisputeRepository {

    private final JpaDisputeRepo jpaDisputeRepo;
    private final JpaEvidenceRepo jpaEvidenceRepo;
    private final JpaEventRepo jpaEventRepo;
    private final ObjectMapper objectMapper;

    public JpaDisputeRepositoryAdapter(JpaDisputeRepo jpaDisputeRepo,
                                        JpaEvidenceRepo jpaEvidenceRepo,
                                        JpaEventRepo jpaEventRepo,
                                        ObjectMapper objectMapper) {
        this.jpaDisputeRepo = jpaDisputeRepo;
        this.jpaEvidenceRepo = jpaEvidenceRepo;
        this.jpaEventRepo = jpaEventRepo;
        this.objectMapper = objectMapper;
    }

    @Override
    public Dispute save(Dispute dispute) {
        DisputeEntity entity = toEntity(dispute);
        jpaDisputeRepo.save(entity);
        return dispute;
    }

    @Override
    public Optional<Dispute> findById(String id) {
        return jpaDisputeRepo.findById(id).map(this::toDomain);
    }

    @Override
    public Optional<Dispute> findByIdAndTenantId(String id, String tenantId) {
        // SEC-27: tenant predicate pushed to SQL — a foreign-tenant row never materialises.
        return jpaDisputeRepo.findByIdAndTenantId(id, tenantId).map(this::toDomain);
    }

    @Override
    public Optional<Dispute> findByTenantIdAndExternalDisputeId(String tenantId, String externalDisputeId) {
        return jpaDisputeRepo.findByTenantIdAndExternalDisputeId(tenantId, externalDisputeId)
                .map(this::toDomain);
    }

    @Override
    public List<Dispute> findByTenant(String tenantId, int limit, int offset) {
        return jpaDisputeRepo.findByTenantIdOrderByCreatedAtDesc(tenantId, PageRequest.of(offset / limit, limit))
                .stream().map(this::toDomain).toList();
    }

    @Override
    public List<Dispute> findByPaymentId(String paymentId) {
        return jpaDisputeRepo.findByPaymentId(paymentId)
                .stream().map(this::toDomain).toList();
    }

    @Override
    public List<Dispute> findByStatus(String tenantId, String status, int limit, int offset) {
        return jpaDisputeRepo.findByTenantIdAndStatusOrderByCreatedAtDesc(
                        tenantId, status, PageRequest.of(offset / limit, limit))
                .stream().map(this::toDomain).toList();
    }

    @Override
    public DisputeEvidence saveEvidence(DisputeEvidence evidence) {
        jpaEvidenceRepo.save(toEvidenceEntity(evidence));
        return evidence;
    }

    @Override
    public List<DisputeEvidence> findEvidenceByDisputeId(String disputeId) {
        return jpaEvidenceRepo.findByDisputeIdOrderByUploadedAt(disputeId)
                .stream().map(this::toEvidenceDomain).toList();
    }

    @Override
    public DisputeEvent saveEvent(DisputeEvent event) {
        jpaEventRepo.save(toEventEntity(event));
        return event;
    }

    @Override
    public List<DisputeEvent> findEventsByDisputeId(String disputeId) {
        return jpaEventRepo.findByDisputeIdOrderByCreatedAt(disputeId)
                .stream().map(this::toEventDomain).toList();
    }

    @Override
    public List<DisputeEvent> findEventsByDisputeIdAndTenantId(String disputeId, String tenantId) {
        // SEC-27: tenant predicate pushed to SQL — foreign-tenant event rows never leave the DB.
        return jpaEventRepo.findByDisputeIdAndTenantIdOrderByCreatedAt(disputeId, tenantId)
                .stream().map(this::toEventDomain).toList();
    }

    // -- Entity ↔ Domain mappers --

    private DisputeEntity toEntity(Dispute d) {
        DisputeEntity e = new DisputeEntity();
        e.setId(d.getId());
        e.setTenantId(d.getTenantId());
        e.setPaymentId(d.getPaymentId());
        e.setExternalDisputeId(d.getExternalDisputeId());
        e.setReasonCode(d.getReasonCode());
        e.setReasonDescription(d.getReasonDescription());
        e.setAmount(d.getAmount());
        e.setCurrency(d.getCurrency());
        e.setStatus(d.getStatus().name());
        e.setNetwork(d.getNetwork());
        e.setEvidenceDueDate(d.getEvidenceDueDate());
        e.setEvidenceSubmittedAt(d.getEvidenceSubmittedAt());
        e.setResolvedAt(d.getResolvedAt());
        e.setOutcome(d.getOutcome());
        e.setCreatedAt(d.getCreatedAt());
        e.setUpdatedAt(d.getUpdatedAt());
        return e;
    }

    private Dispute toDomain(DisputeEntity e) {
        Dispute d = new Dispute();
        d.setId(e.getId());
        d.setTenantId(e.getTenantId());
        d.setPaymentId(e.getPaymentId());
        d.setExternalDisputeId(e.getExternalDisputeId());
        d.setReasonCode(e.getReasonCode());
        d.setReasonDescription(e.getReasonDescription());
        d.setAmount(e.getAmount());
        d.setCurrency(e.getCurrency());
        d.setStatus(DisputeState.valueOf(e.getStatus()));
        d.setNetwork(e.getNetwork());
        d.setEvidenceDueDate(e.getEvidenceDueDate());
        d.setEvidenceSubmittedAt(e.getEvidenceSubmittedAt());
        d.setResolvedAt(e.getResolvedAt());
        d.setOutcome(e.getOutcome());
        d.setCreatedAt(e.getCreatedAt());
        d.setUpdatedAt(e.getUpdatedAt());
        return d;
    }

    private DisputeEvidenceEntity toEvidenceEntity(DisputeEvidence ev) {
        DisputeEvidenceEntity e = new DisputeEvidenceEntity();
        e.setId(ev.getId());
        e.setDisputeId(ev.getDisputeId());
        e.setTenantId(ev.getTenantId());
        e.setEvidenceType(ev.getEvidenceType().name());
        e.setFileKey(ev.getFileKey());
        e.setFileName(ev.getFileName());
        e.setFileSize(ev.getFileSize());
        e.setDescription(ev.getDescription());
        e.setUploadedAt(ev.getUploadedAt());
        return e;
    }

    private DisputeEvidence toEvidenceDomain(DisputeEvidenceEntity e) {
        DisputeEvidence ev = new DisputeEvidence();
        ev.setId(e.getId());
        ev.setDisputeId(e.getDisputeId());
        ev.setTenantId(e.getTenantId());
        ev.setEvidenceType(DisputeEvidenceType.valueOf(e.getEvidenceType()));
        ev.setFileKey(e.getFileKey());
        ev.setFileName(e.getFileName());
        ev.setFileSize(e.getFileSize());
        ev.setDescription(e.getDescription());
        ev.setUploadedAt(e.getUploadedAt());
        return ev;
    }

    private DisputeEventEntity toEventEntity(DisputeEvent ev) {
        DisputeEventEntity e = new DisputeEventEntity();
        e.setId(ev.getId());
        e.setDisputeId(ev.getDisputeId());
        e.setTenantId(ev.getTenantId());
        e.setEventType(ev.getEventType());
        e.setOldStatus(ev.getOldStatus() != null ? ev.getOldStatus().name() : null);
        e.setNewStatus(ev.getNewStatus() != null ? ev.getNewStatus().name() : null);
        e.setActor(ev.getActor());
        e.setCreatedAt(ev.getCreatedAt());
        try {
            e.setDetails(ev.getDetails() != null ? objectMapper.writeValueAsString(ev.getDetails()) : null);
        } catch (JsonProcessingException ex) {
            e.setDetails("{}");
        }
        return e;
    }

    private DisputeEvent toEventDomain(DisputeEventEntity e) {
        DisputeEvent ev = new DisputeEvent();
        ev.setId(e.getId());
        ev.setDisputeId(e.getDisputeId());
        ev.setTenantId(e.getTenantId());
        ev.setEventType(e.getEventType());
        ev.setOldStatus(e.getOldStatus() != null ? DisputeState.valueOf(e.getOldStatus()) : null);
        ev.setNewStatus(e.getNewStatus() != null ? DisputeState.valueOf(e.getNewStatus()) : null);
        ev.setActor(e.getActor());
        ev.setCreatedAt(e.getCreatedAt());
        try {
            ev.setDetails(e.getDetails() != null
                    ? objectMapper.readValue(e.getDetails(), new TypeReference<Map<String, Object>>() {})
                    : null);
        } catch (JsonProcessingException ex) {
            ev.setDetails(Map.of());
        }
        return ev;
    }

    // -- Spring Data JPA interfaces --

    interface JpaDisputeRepo extends JpaRepository<DisputeEntity, String> {
        List<DisputeEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId, PageRequest page);
        List<DisputeEntity> findByPaymentId(String paymentId);
        List<DisputeEntity> findByTenantIdAndStatusOrderByCreatedAtDesc(String tenantId, String status, PageRequest page);
        Optional<DisputeEntity> findByTenantIdAndExternalDisputeId(String tenantId, String externalDisputeId);
        // SEC-27: tenant-scoped by-id finder backing the REST read/mutation control.
        Optional<DisputeEntity> findByIdAndTenantId(String id, String tenantId);
    }

    interface JpaEvidenceRepo extends JpaRepository<DisputeEvidenceEntity, String> {
        List<DisputeEvidenceEntity> findByDisputeIdOrderByUploadedAt(String disputeId);
    }

    interface JpaEventRepo extends JpaRepository<DisputeEventEntity, String> {
        List<DisputeEventEntity> findByDisputeIdOrderByCreatedAt(String disputeId);
        // SEC-27: tenant-scoped event-timeline finder for GET /v1/disputes/{id}/events.
        List<DisputeEventEntity> findByDisputeIdAndTenantIdOrderByCreatedAt(String disputeId, String tenantId);
    }
}
