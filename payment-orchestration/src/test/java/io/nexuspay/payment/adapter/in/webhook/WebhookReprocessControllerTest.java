package io.nexuspay.payment.adapter.in.webhook;

import io.nexuspay.common.tenant.TenantPrincipal;
import io.nexuspay.payment.adapter.out.outbox.OutboxEvent;
import io.nexuspay.payment.adapter.out.outbox.OutboxEventRepository;
import io.nexuspay.payment.application.screening.ScreeningMode;
import io.nexuspay.payment.application.screening.ScreeningOriginService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GAP-015: full contract for the operator webhook-reprocess endpoint
 * {@code POST /v1/admin/webhooks/reprocess/{id}} (the {@code /v1/admin} surface, so it is authenticated,
 * off the {@code /internal/webhooks/**} permitAll tree, AND covered by the per-principal RateLimitFilter).
 *
 * <p>Uses the REAL {@link WebhookOutboxWriter} (imported) with mocked infrastructure collaborators so the
 * tenant resolution + single-outbox-row guarantee are exercised through production code (L-068: every
 * ctor dep of the controller AND the writer is a {@code @MockBean}). Asserts:
 * <ol>
 *   <li>FAILED row -&gt; 200, exactly ONE OutboxEvent saved with the RESOLVED origin tenant (NOT
 *       "default"), inbound row marked PROCESSED + reprocessed_at set;</li>
 *   <li>already-PROCESSED row -&gt; 200 no-op, ZERO outbox saves (no double insert);</li>
 *   <li>RECEIVED row -&gt; 409;</li>
 *   <li>absent id -&gt; 404;</li>
 *   <li>tenant is taken from {@code ScreeningOriginService.find(paymentId)}, never a request body/header;</li>
 *   <li>admin-auth: a non-admin caller is 403 (proves it is NOT permitAll like the webhook tree).</li>
 * </ol>
 */
@WebMvcTest(WebhookReprocessController.class)
@Import({WebhookOutboxWriter.class, WebhookReprocessControllerTest.MetricsConfig.class})
class WebhookReprocessControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockBean private InboundWebhookRepository webhookRepository;
    // WebhookOutboxWriter ctor deps (the real writer is @Import-ed):
    @MockBean private OutboxEventRepository outboxRepository;
    @MockBean private ScreeningOriginService screeningOrigins;

    /**
     * The real {@link WebhookOutboxWriter} needs a {@link io.micrometer.core.instrument.MeterRegistry}; a
     * {@code @WebMvcTest} slice does not auto-configure one, so provide a real in-memory registry as a
     * test bean (ObjectMapper IS auto-configured by the slice).
     */
    @org.springframework.boot.test.context.TestConfiguration
    static class MetricsConfig {
        @org.springframework.context.annotation.Bean
        io.micrometer.core.instrument.MeterRegistry meterRegistry() {
            return new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        }
    }

    private static final String URL = "/v1/admin/webhooks/reprocess/wh_1";

    private static final String RAW_PAYLOAD = """
            {
              "event_id": "evt_orig",
              "event_type": "payment_succeeded",
              "content": { "object": { "payment_id": "pay_123" } }
            }
            """;

    /** A minimal admin/non-admin principal (mirrors the tenant-principal contract without importing iam). */
    private record Principal(String tenantId) implements TenantPrincipal {
    }

    private static Authentication auth(String role) {
        return new UsernamePasswordAuthenticationToken(
                new Principal("tenant-caller"), null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    private InboundWebhook webhook(String status) {
        InboundWebhook w = new InboundWebhook("wh_1", "evt_orig", "payment_succeeded", RAW_PAYLOAD);
        if (InboundWebhook.STATUS_FAILED.equals(status)) {
            w.markFailed();
        } else if (InboundWebhook.STATUS_PROCESSED.equals(status)) {
            w.markProcessed();
        }
        // RECEIVED is the ctor default.
        return w;
    }

    @Test
    void failedRow_reprocessed_insertsOneOutboxRow_withResolvedTenant_andMarksProcessed() throws Exception {
        InboundWebhook w = webhook(InboundWebhook.STATUS_FAILED);
        when(webhookRepository.findByIdForUpdate("wh_1")).thenReturn(Optional.of(w));
        when(screeningOrigins.find("pay_123"))
                .thenReturn(Optional.of(new ScreeningOriginService.Origin("tenant-A", ScreeningMode.INTERACTIVE)));

        mockMvc.perform(post(URL).with(authentication(auth("admin"))))
                .andExpect(status().isOk());

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).save(captor.capture());
        // Tenant is the RESOLVED origin tenant, never "default", never the caller's tenant, never a header.
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getTenantId()).isEqualTo("tenant-A");
        // Inbound row flipped to PROCESSED + reprocessed_at stamped.
        org.assertj.core.api.Assertions.assertThat(w.getStatus()).isEqualTo(InboundWebhook.STATUS_PROCESSED);
        org.assertj.core.api.Assertions.assertThat(w.getReprocessedAt()).isNotNull();
        verify(webhookRepository).save(w);
        verify(screeningOrigins).find("pay_123");
    }

    @Test
    void alreadyProcessedRow_isNoOp_neverDoubleInsertsOutbox() throws Exception {
        when(webhookRepository.findByIdForUpdate("wh_1"))
                .thenReturn(Optional.of(webhook(InboundWebhook.STATUS_PROCESSED)));

        mockMvc.perform(post(URL).with(authentication(auth("admin"))))
                .andExpect(status().isOk());

        // The status guard means NO outbox row is written for an already-processed webhook.
        verify(outboxRepository, never()).save(any());
        verify(screeningOrigins, never()).find(anyString());
    }

    @Test
    void receivedRow_isConflict() throws Exception {
        when(webhookRepository.findByIdForUpdate("wh_1"))
                .thenReturn(Optional.of(webhook(InboundWebhook.STATUS_RECEIVED)));

        mockMvc.perform(post(URL).with(authentication(auth("admin"))))
                .andExpect(status().isConflict());

        verify(outboxRepository, never()).save(any());
    }

    @Test
    void absentId_isNotFound() throws Exception {
        when(webhookRepository.findByIdForUpdate("wh_1")).thenReturn(Optional.empty());

        mockMvc.perform(post(URL).with(authentication(auth("admin"))))
                .andExpect(status().isNotFound());

        verify(outboxRepository, never()).save(any());
    }

    @Test
    void nonAdminCaller_isForbidden_provingNotPermitAll() throws Exception {
        // A viewer/operator role must be rejected — the endpoint is admin-guarded, NOT permitAll like the
        // /internal/webhooks/** PSP-delivery tree.
        mockMvc.perform(post(URL).with(authentication(auth("operator"))))
                .andExpect(status().isForbidden());

        verify(webhookRepository, never()).findByIdForUpdate(anyString());
        verify(outboxRepository, never()).save(any());
    }

    @Test
    void unauthenticatedCaller_isUnauthorized() throws Exception {
        mockMvc.perform(post(URL))
                .andExpect(status().isUnauthorized());

        verify(webhookRepository, never()).findByIdForUpdate(anyString());
    }
}
