package io.nexuspay.billing.application.service;

import io.nexuspay.billing.application.port.out.BillingOutboxPort;
import io.nexuspay.billing.application.port.out.ProductRepository;
import io.nexuspay.billing.application.port.out.SubscriptionRepository;
import io.nexuspay.billing.domain.Subscription;
import io.nexuspay.billing.domain.SubscriptionState;
import io.nexuspay.common.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SEC-26 tests for {@link SubscriptionLifecycleService}: the tenant-scoped by-id teeth that stop a
 * tenant-A caller from reading/mutating a tenant-B subscription. Every mutation resolves the
 * subscription via a tenant-scoped finder ({@code findByIdAndTenantId}) and 404s (no oracle) on a
 * foreign/absent id — never falling back to the unscoped {@code findById}.
 */
class SubscriptionLifecycleServiceTest {

    private SubscriptionRepository subscriptionRepository;
    private ProductRepository productRepository;
    private InvoiceGenerationService invoiceService;
    private BillingOutboxPort outboxPort;
    private SubscriptionLifecycleService service;

    @BeforeEach
    void setUp() {
        subscriptionRepository = mock(SubscriptionRepository.class);
        productRepository = mock(ProductRepository.class);
        invoiceService = mock(InvoiceGenerationService.class);
        outboxPort = mock(BillingOutboxPort.class);
        service = new SubscriptionLifecycleService(
                subscriptionRepository, productRepository, invoiceService, outboxPort);
        when(subscriptionRepository.save(any(Subscription.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private static Subscription activeSub() {
        Subscription s = new Subscription();
        s.setId("sub_1");
        s.setTenantId("t1");
        s.setCustomerId("cus_1");
        s.setPriceId("price_1");
        s.setStatus(SubscriptionState.ACTIVE);
        s.setQuantity(1);
        s.setCurrentPeriodStart(Instant.parse("2026-01-01T00:00:00Z"));
        s.setCurrentPeriodEnd(Instant.parse("2026-02-01T00:00:00Z"));
        s.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        return s;
    }

    // ---- findById ----

    @Test
    void findByIdScopesToTenant_andDoesNotUseUnscopedLookup() {
        Subscription sub = activeSub();
        when(subscriptionRepository.findByIdAndTenantId("sub_1", "t1")).thenReturn(Optional.of(sub));

        assertThat(service.findById("sub_1", "t1")).contains(sub);

        verify(subscriptionRepository).findByIdAndTenantId("sub_1", "t1");
        verify(subscriptionRepository, never()).findById(anyString());
    }

    // ---- cancel ----

    @Test
    void cancelForeignTenantSubscriptionThrows404_andNeverSaves() {
        when(subscriptionRepository.findByIdAndTenantId("sub_victim", "attacker"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cancel("sub_victim", "attacker", true))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(subscriptionRepository).findByIdAndTenantId("sub_victim", "attacker");
        verify(subscriptionRepository, never()).save(any(Subscription.class));
        verify(subscriptionRepository, never()).findById(anyString());
    }

    @Test
    void cancelOwnedSubscriptionScopesByTenant_andSaves() {
        Subscription sub = activeSub();
        when(subscriptionRepository.findByIdAndTenantId("sub_1", "t1")).thenReturn(Optional.of(sub));

        Subscription result = service.cancel("sub_1", "t1", true);

        assertThat(result).isSameAs(sub);
        verify(subscriptionRepository).findByIdAndTenantId("sub_1", "t1");
        verify(subscriptionRepository).save(sub);
    }

    // ---- pause / resume / changePlan: foreign tenant 404 ----

    @Test
    void pauseForeignTenantSubscriptionThrows404() {
        when(subscriptionRepository.findByIdAndTenantId("sub_victim", "attacker"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.pause("sub_victim", "attacker"))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(subscriptionRepository, never()).save(any(Subscription.class));
    }

    @Test
    void resumeForeignTenantSubscriptionThrows404_andNeverTouchesPriceRepo() {
        when(subscriptionRepository.findByIdAndTenantId("sub_victim", "attacker"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resume("sub_victim", "attacker"))
                .isInstanceOf(ResourceNotFoundException.class);
        // Ownership is asserted BEFORE any price lookup — short-circuits cleanly.
        verify(productRepository, never()).findPriceById(anyString());
        verify(subscriptionRepository, never()).save(any(Subscription.class));
    }

    @Test
    void changePlanForeignTenantSubscriptionThrows404_andNeverTouchesPriceRepo() {
        when(subscriptionRepository.findByIdAndTenantId("sub_victim", "attacker"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.changePlan("sub_victim", "attacker", "price_2"))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(productRepository, never()).findPriceById(anyString());
        verify(subscriptionRepository, never()).save(any(Subscription.class));
    }

    // ---- foreign-tenant priceId: scoped price lookup 404s, never reaches create/setPriceId ----

    @Test
    void createSubscriptionWithForeignTenantPriceThrows404_andNeverPersistsOrPublishes() {
        // The caller (tenant t1) supplies a priceId owned by another tenant. The tenant-scoped finder
        // returns empty for (priceId, t1), so the subscription is never created/saved and no terms leak.
        when(productRepository.findPriceByIdAndTenantId("price_foreign", "t1"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createSubscription(
                "t1", "cus_1", "price_foreign", 1, "pm_1", Map.of()))
                .isInstanceOf(ResourceNotFoundException.class);

        // Resolved via the tenant-scoped finder only — never the unscoped findPriceById.
        verify(productRepository).findPriceByIdAndTenantId("price_foreign", "t1");
        verify(productRepository, never()).findPriceById(anyString());
        // Never reached Subscription.create / persistence / event publication.
        verify(subscriptionRepository, never()).save(any(Subscription.class));
        verify(invoiceService, never()).generateInvoice(any(), any());
        verify(outboxPort, never()).publishEvent(anyString(), anyString(), anyString(), any(), anyString());
    }

    @Test
    void changePlanWithForeignTenantPriceThrows404_andNeverRepointsOrPublishes() {
        // The subscription is owned by the caller (t1), but the requested newPriceId belongs to another
        // tenant. The scoped price finder returns empty, so setPriceId is never invoked.
        Subscription sub = activeSub();
        when(subscriptionRepository.findByIdAndTenantId("sub_1", "t1")).thenReturn(Optional.of(sub));
        when(productRepository.findPriceByIdAndTenantId("price_foreign", "t1"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.changePlan("sub_1", "t1", "price_foreign"))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(productRepository).findPriceByIdAndTenantId("price_foreign", "t1");
        verify(productRepository, never()).findPriceById(anyString());
        // The subscription's price was NOT repointed and nothing was saved/published.
        assertThat(sub.getPriceId()).isEqualTo("price_1");
        verify(subscriptionRepository, never()).save(any(Subscription.class));
        verify(outboxPort, never()).publishEvent(anyString(), anyString(), anyString(), any(), anyString());
    }

    @Test
    void resumeRoutesPriceLookupThroughTenantScopedFinder() {
        // resume re-reads the sub's own stored priceId; it must still go through the tenant-scoped
        // finder (not the unscoped one) to keep the price-lookup invariant uniform.
        Subscription sub = activeSub();
        sub.setStatus(SubscriptionState.PAUSED);
        when(subscriptionRepository.findByIdAndTenantId("sub_1", "t1")).thenReturn(Optional.of(sub));
        when(productRepository.findPriceByIdAndTenantId(eq("price_1"), eq("t1")))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resume("sub_1", "t1"))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(productRepository).findPriceByIdAndTenantId("price_1", "t1");
        verify(productRepository, never()).findPriceById(anyString());
        verify(subscriptionRepository, never()).save(any(Subscription.class));
    }
}
