package io.nexuspay.b2b.application.service;

import io.nexuspay.b2b.application.port.in.IssueVirtualCardUseCase;
import io.nexuspay.b2b.application.port.out.B2bEventPublisher;
import io.nexuspay.b2b.application.port.out.B2bRepository;
import io.nexuspay.b2b.application.port.out.CardIssuingPort;
import io.nexuspay.b2b.domain.VirtualCard;
import io.nexuspay.b2b.domain.VirtualCardStatus;
import io.nexuspay.b2b.domain.VirtualCardType;
import io.nexuspay.common.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link VirtualCardService}.
 *
 * @since 0.4.2 (Sprint 4.3)
 */
@ExtendWith(MockitoExtension.class)
class VirtualCardServiceTest {

    @Mock private B2bRepository repository;
    @Mock private CardIssuingPort cardIssuingPort;
    @Mock private B2bEventPublisher eventPublisher;

    private VirtualCardService service;

    @BeforeEach
    void setUp() {
        service = new VirtualCardService(repository, cardIssuingPort, eventPublisher);
    }

    @Test
    void issueCard_happyPath() {
        when(cardIssuingPort.issueCard(any())).thenReturn(
                new CardIssuingPort.IssuingResult("ext_123", "4567", "stub-issuer"));
        when(repository.saveVirtualCard(any())).thenAnswer(inv -> inv.getArgument(0));

        Instant expiry = Instant.now().plus(90, ChronoUnit.DAYS);
        var result = service.issueCard(new IssueVirtualCardUseCase.IssueCardCommand(
                "tenant-1", VirtualCardType.MULTI_USE, 500000, "USD", expiry,
                List.of("5411", "5541"), "po_abc123"));

        assertNotNull(result.cardId());
        assertTrue(result.cardId().startsWith("vc_"));
        assertEquals("4567", result.cardLast4());
        assertEquals(VirtualCardType.MULTI_USE, result.cardType());
        assertEquals(500000, result.amountLimit());
        assertEquals(0, result.spentAmount());
        assertEquals(500000, result.availableBalance());
        assertEquals(VirtualCardStatus.ACTIVE, result.status());
        assertEquals("stub-issuer", result.issuingProvider());

        verify(cardIssuingPort).issueCard(any());
        verify(repository).saveVirtualCard(any());
        verify(eventPublisher).publishEvent(eq("VirtualCard"), any(), eq("VirtualCardIssued"), any(), eq("tenant-1"));
    }

    @Test
    void getCard_returnsCardInfo() {
        VirtualCard card = VirtualCard.create("tenant-1", "stub", VirtualCardType.SINGLE_USE,
                100000, "USD", Instant.now().plus(30, ChronoUnit.DAYS));
        // SEC-BATCH-1: card loaded tenant-scoped.
        when(repository.findVirtualCardById(card.getId(), "tenant-1")).thenReturn(Optional.of(card));

        var result = service.getCard(card.getId(), "tenant-1");

        assertEquals(card.getId(), result.cardId());
        assertEquals(VirtualCardType.SINGLE_USE, result.cardType());
    }

    @Test
    void getCard_crossTenant_throwsNotFound() {
        when(repository.findVirtualCardById("vc_foreign", "tenant-1")).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.getCard("vc_foreign", "tenant-1"));
    }

    @Test
    void freezeCard_updatesStatusAndCallsProvider() {
        VirtualCard card = VirtualCard.create("tenant-1", "stub", VirtualCardType.MULTI_USE,
                500000, "USD", Instant.now().plus(90, ChronoUnit.DAYS));
        card.setExternalCardId("ext_456");
        when(repository.findVirtualCardById(card.getId(), "tenant-1")).thenReturn(Optional.of(card));
        when(repository.saveVirtualCard(any())).thenAnswer(inv -> inv.getArgument(0));

        service.freezeCard(card.getId(), "tenant-1");

        ArgumentCaptor<VirtualCard> captor = ArgumentCaptor.forClass(VirtualCard.class);
        verify(repository).saveVirtualCard(captor.capture());
        assertEquals(VirtualCardStatus.FROZEN, captor.getValue().getStatus());
        verify(cardIssuingPort).freezeCard("ext_456");
        verify(eventPublisher).publishEvent(eq("VirtualCard"), any(), eq("VirtualCardFrozen"), any(), eq("tenant-1"));
    }

    @Test
    void freezeCard_crossTenant_throwsNotFound() {
        // SEC-BATCH-1: cross-tenant freeze must 404 and never touch the issuing provider.
        when(repository.findVirtualCardById("vc_foreign", "tenant-1")).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.freezeCard("vc_foreign", "tenant-1"));
        verify(repository, never()).saveVirtualCard(any());
        verify(cardIssuingPort, never()).freezeCard(any());
    }

    @Test
    void cancelCard_updatesStatusAndCallsProvider() {
        VirtualCard card = VirtualCard.create("tenant-1", "stub", VirtualCardType.MULTI_USE,
                500000, "USD", Instant.now().plus(90, ChronoUnit.DAYS));
        card.setExternalCardId("ext_789");
        when(repository.findVirtualCardById(card.getId(), "tenant-1")).thenReturn(Optional.of(card));
        when(repository.saveVirtualCard(any())).thenAnswer(inv -> inv.getArgument(0));

        service.cancelCard(card.getId(), "tenant-1");

        ArgumentCaptor<VirtualCard> captor = ArgumentCaptor.forClass(VirtualCard.class);
        verify(repository).saveVirtualCard(captor.capture());
        assertEquals(VirtualCardStatus.CANCELLED, captor.getValue().getStatus());
        verify(cardIssuingPort).cancelCard("ext_789");
    }

    @Test
    void cancelCard_crossTenant_throwsNotFound() {
        when(repository.findVirtualCardById("vc_foreign", "tenant-1")).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.cancelCard("vc_foreign", "tenant-1"));
        verify(repository, never()).saveVirtualCard(any());
        verify(cardIssuingPort, never()).cancelCard(any());
    }

    @Test
    void getCard_throwsWhenNotFound() {
        when(repository.findVirtualCardById("vc_missing", "tenant-1")).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.getCard("vc_missing", "tenant-1"));
    }
}
