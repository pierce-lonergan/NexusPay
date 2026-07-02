package io.nexuspay.dispute.adapter.out.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.nexuspay.dispute.domain.Dispute;
import io.nexuspay.dispute.domain.DisputeState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GAP-033: contract for the {@code findExpirable} finder on the JPA adapter. Proves the adapter queries
 * for EXACTLY the pre-terminal EVIDENCE states (OPENED, EVIDENCE_NEEDED) — never EVIDENCE_SUBMITTED or the
 * terminal WON/LOST/EXPIRED — with the supplied {@code now} and a bounded first-page limit. The
 * status/due-date predicate + ordering are expressed by the Spring Data derived method name
 * {@code findByStatusInAndEvidenceDueDateBeforeOrderByEvidenceDueDateAsc}; here we pin the arguments the
 * adapter passes to it (a divergence — e.g. accidentally including EVIDENCE_SUBMITTED — would be a
 * force-expire of a defended dispute).
 *
 * <p>Also serves as the interface-arity check: this test is a concrete caller of the new
 * {@code DisputeRepository.findExpirable} through the adapter.</p>
 */
class DisputeRepositoryFindExpirableTest {

    private JpaDisputeRepositoryAdapter.JpaDisputeRepo jpaDisputeRepo;
    private JpaDisputeRepositoryAdapter adapter;

    @BeforeEach
    void setUp() {
        jpaDisputeRepo = mock(JpaDisputeRepositoryAdapter.JpaDisputeRepo.class);
        adapter = new JpaDisputeRepositoryAdapter(
                jpaDisputeRepo,
                mock(JpaDisputeRepositoryAdapter.JpaEvidenceRepo.class),
                mock(JpaDisputeRepositoryAdapter.JpaEventRepo.class),
                new ObjectMapper());
    }

    @Test
    @SuppressWarnings("unchecked")
    void findExpirable_queriesOnlyPreTerminalEvidenceStates_boundedAndOrdered() {
        Instant now = Instant.parse("2026-07-02T00:00:00Z");
        when(jpaDisputeRepo.findByStatusInAndEvidenceDueDateBeforeOrderByEvidenceDueDateAsc(
                any(), eq(now), any(PageRequest.class))).thenReturn(List.of());

        adapter.findExpirable(now, 200);

        ArgumentCaptor<Collection<String>> statuses = ArgumentCaptor.forClass(Collection.class);
        ArgumentCaptor<PageRequest> page = ArgumentCaptor.forClass(PageRequest.class);
        verify(jpaDisputeRepo).findByStatusInAndEvidenceDueDateBeforeOrderByEvidenceDueDateAsc(
                statuses.capture(), eq(now), page.capture());

        // EXACTLY the two pre-terminal evidence states — nothing else.
        assertThat(statuses.getValue())
                .containsExactlyInAnyOrder(
                        DisputeState.OPENED.name(), DisputeState.EVIDENCE_NEEDED.name())
                .doesNotContain(
                        DisputeState.EVIDENCE_SUBMITTED.name(),
                        DisputeState.WON.name(),
                        DisputeState.LOST.name(),
                        DisputeState.EXPIRED.name());
        // Bounded first page of the requested size.
        assertThat(page.getValue().getPageNumber()).isZero();
        assertThat(page.getValue().getPageSize()).isEqualTo(200);
    }

    @Test
    void findExpirable_mapsEntitiesToDomain() {
        Instant now = Instant.parse("2026-07-02T00:00:00Z");
        DisputeEntity e = new DisputeEntity();
        e.setId("dp_1");
        e.setTenantId("tenant-A");
        e.setPaymentId("pay_1");
        e.setReasonCode("10.4");
        e.setAmount(5000L);
        e.setCurrency("USD");
        e.setStatus(DisputeState.OPENED.name());
        e.setEvidenceDueDate(now.minusSeconds(3600));
        e.setCreatedAt(now.minusSeconds(7200));
        e.setUpdatedAt(now.minusSeconds(7200));
        when(jpaDisputeRepo.findByStatusInAndEvidenceDueDateBeforeOrderByEvidenceDueDateAsc(
                any(), any(Instant.class), any(PageRequest.class))).thenReturn(List.of(e));

        List<Dispute> result = adapter.findExpirable(now, 200);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo("dp_1");
        assertThat(result.get(0).getTenantId()).isEqualTo("tenant-A");
        assertThat(result.get(0).getStatus()).isEqualTo(DisputeState.OPENED);
    }
}
