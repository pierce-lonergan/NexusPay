package io.nexuspay.gateway.config;

import io.nexuspay.common.exception.AuthorizationException;
import io.nexuspay.common.exception.ConflictException;
import io.nexuspay.common.exception.InvalidRequestException;
import io.nexuspay.common.exception.LedgerException;
import io.nexuspay.common.exception.NexusPayException;
import io.nexuspay.common.exception.PaymentException;
import io.nexuspay.common.exception.ResourceNotFoundException;
import io.nexuspay.gateway.adapter.in.filter.CorrelationIdFilter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * INT-2: every {@link GlobalExceptionHandler} branch emits the stable envelope
 * {@code { error: { type, code, message, request_id } }} with the documented (UNCHANGED) HTTP status,
 * the stable taxonomy {@code type}, a present {@code request_id} that equals the {@code X-Request-Id}
 * correlation header, and NO {@code param} field. 500s emit a generic message and never leak the
 * underlying exception text.
 *
 * <p>Each assertion FAILS if the INT-2 change is reverted (old taxonomy strings, missing request_id,
 * a present param, or a leaked 500 message).</p>
 */
class GlobalExceptionHandlerEnvelopeTest {

    private static final String RID = "test-rid-123";
    private static final String SENTINEL = "SECRET-SQL-leak-42 at com.evil.Hacker.run(Hacker.java:7)";

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // Explicit JSR-303 validator so @Valid @RequestBody reliably throws MethodArgumentNotValidException
        // (rather than depending on standalone-setup auto-discovery of a validation provider).
        var validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .addFilter(new CorrelationIdFilter(), "/*")
                .build();
    }

    /** Drives a path with an explicit X-Request-Id, asserting status + taxonomy + request_id echo + no param. */
    private ResultActions assertEnvelope(String path, HttpStatus expectedStatus, String type, String code)
            throws Exception {
        return mockMvc.perform(get(path).header(CorrelationIdFilter.REQUEST_ID_HEADER, RID))
                .andExpect(status().is(expectedStatus.value()))
                .andExpect(jsonPath("$.error.type", is(type)))
                .andExpect(jsonPath("$.error.code", is(code)))
                .andExpect(jsonPath("$.error.message").isNotEmpty())
                .andExpect(jsonPath("$.error.request_id", is(RID)))
                .andExpect(jsonPath("$.error.param").doesNotExist());
    }

    @Test
    void methodArgumentNotValid_is400Validation() throws Exception {
        // A @Valid @RequestBody with a violated @NotBlank field throws MethodArgumentNotValidException → 400.
        // The field hint is folded into the message (no separate param on the wire).
        mockMvc.perform(post("/throw/validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}")
                        .header(CorrelationIdFilter.REQUEST_ID_HEADER, RID))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type", is("validation_error")))
                .andExpect(jsonPath("$.error.code", is("invalid_parameter")))
                .andExpect(jsonPath("$.error.message", containsString("name")))
                .andExpect(jsonPath("$.error.request_id", is(RID)))
                .andExpect(jsonPath("$.error.param").doesNotExist());
    }

    @Test
    void resourceNotFound_is404NotFound() throws Exception {
        assertEnvelope("/throw/not-found", HttpStatus.NOT_FOUND, "not_found", "resource_not_found");
    }

    @Test
    void authorizationException_is403Forbidden() throws Exception {
        assertEnvelope("/throw/authz", HttpStatus.FORBIDDEN, "forbidden", "forbidden");
    }

    @Test
    void accessDenied_is403Forbidden() throws Exception {
        assertEnvelope("/throw/access-denied", HttpStatus.FORBIDDEN, "forbidden", "access_denied");
    }

    @Test
    void paymentNotFound_is404Payment() throws Exception {
        assertEnvelope("/throw/payment-not-found", HttpStatus.NOT_FOUND, "payment_error", "payment_not_found");
    }

    @Test
    void paymentInvalidState_is409Payment() throws Exception {
        assertEnvelope("/throw/payment-conflict", HttpStatus.CONFLICT, "payment_error", "invalid_state");
    }

    @Test
    void paymentCrossBorderBlocked_is403Payment() throws Exception {
        assertEnvelope("/throw/payment-forbidden", HttpStatus.FORBIDDEN, "payment_error", "cross_border_blocked");
    }

    @Test
    void paymentFraudBlocked_is422Payment() throws Exception {
        assertEnvelope("/throw/payment-unprocessable", HttpStatus.UNPROCESSABLE_ENTITY,
                "payment_error", "fraud_blocked");
    }

    @Test
    void ledgerAccountNotFound_is404Payment() throws Exception {
        assertEnvelope("/throw/ledger-not-found", HttpStatus.NOT_FOUND, "payment_error", "account_not_found");
    }

    @Test
    void ledger500_isInternalAndGeneric_noLeak() throws Exception {
        // A non-mapped ledger error code → 500 → generic message + internal_error type, no leak.
        mockMvc.perform(get("/throw/ledger-500").header(CorrelationIdFilter.REQUEST_ID_HEADER, RID))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.type", is("internal_error")))
                .andExpect(jsonPath("$.error.message", is("An unexpected error occurred")))
                .andExpect(jsonPath("$.error.request_id", is(RID)))
                .andExpect(content().string(not(containsString("SECRET-SQL-leak"))));
    }

    @Test
    void genericNexusPayException_is500GenericMessage_noLeak() throws Exception {
        mockMvc.perform(get("/throw/nexuspay-generic").header(CorrelationIdFilter.REQUEST_ID_HEADER, RID))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.type", is("internal_error")))
                .andExpect(jsonPath("$.error.code", is("internal_error")))
                .andExpect(jsonPath("$.error.message", is("An unexpected error occurred")))
                .andExpect(jsonPath("$.error.request_id", is(RID)))
                .andExpect(jsonPath("$.error.param").doesNotExist())
                .andExpect(content().string(not(containsString("SECRET-SQL-leak"))));
    }

    @Test
    void uncaughtException_is500GenericMessage_noLeak() throws Exception {
        mockMvc.perform(get("/throw/runtime").header(CorrelationIdFilter.REQUEST_ID_HEADER, RID))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.type", is("internal_error")))
                .andExpect(jsonPath("$.error.message", is("An unexpected error occurred")))
                .andExpect(jsonPath("$.error.request_id", is(RID)))
                .andExpect(content().string(not(containsString("SECRET-SQL-leak"))));
    }

    @Test
    void requestId_isGeneratedWhenHeaderAbsent() throws Exception {
        // No X-Request-Id supplied → CorrelationIdFilter generates one → body request_id is non-blank.
        mockMvc.perform(get("/throw/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.request_id").isNotEmpty());
    }

    @Test
    void responseStatus400_is400Validation() throws Exception {
        // SEC-28: a ResponseStatusException(400) (the family that includes Spring 6.1's
        // HandlerMethodValidationException for method-parameter @Size/@NotEmpty) maps to a 400 validation
        // envelope, NOT the generic 500 it produced before the dedicated handler existed.
        assertEnvelope("/throw/response-status-400", HttpStatus.BAD_REQUEST, "validation_error", "invalid_request");
    }

    @Test
    void conflictException_is409Conflict() throws Exception {
        // DX-5c: ApiKeyService.rotateApiKey throws a TYPED ConflictException for a revoked/expired or
        // already-replaced key — mapped to a 409 conflict envelope with the curated, id-free message. (A
        // raw IllegalStateException is NOT handled here: it stays a 500, see internalInvariant test below.)
        mockMvc.perform(get("/throw/conflict").header(CorrelationIdFilter.REQUEST_ID_HEADER, RID))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.type", is("conflict")))
                .andExpect(jsonPath("$.error.code", is("key_not_rotatable")))
                .andExpect(jsonPath("$.error.message", is("API key has already been rotated")))
                .andExpect(jsonPath("$.error.request_id", is(RID)))
                .andExpect(jsonPath("$.error.param").doesNotExist());
    }

    @Test
    void invalidRequestException_is400Validation() throws Exception {
        // DX-5c: ApiKeyService.createApiKey throws a TYPED InvalidRequestException for an at-or-before-now
        // expiresAt (defence-in-depth behind the @Future DTO validation) — mapped to a 400 validation
        // envelope with the curated message.
        mockMvc.perform(get("/throw/invalid-request").header(CorrelationIdFilter.REQUEST_ID_HEADER, RID))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type", is("validation_error")))
                .andExpect(jsonPath("$.error.code", is("invalid_request")))
                .andExpect(jsonPath("$.error.message", is("API key expiresAt must be in the future")))
                .andExpect(jsonPath("$.error.request_id", is(RID)))
                .andExpect(jsonPath("$.error.param").doesNotExist());
    }

    @Test
    void rawIllegalState_internalInvariant_is500GenericMessage_noLeak() throws Exception {
        // A RAW IllegalStateException (an INTERNAL invariant/corruption guard, e.g.
        // ApiKeyService.generateKey's prefix/is_live mismatch) must remain a leak-hardened 500 — NOT a 409.
        // This guards against re-introducing a blanket IllegalStateException->409 advice that would mislabel
        // server faults as client conflicts across the codebase's 40-plus internal throws.
        mockMvc.perform(get("/throw/illegal-state").header(CorrelationIdFilter.REQUEST_ID_HEADER, RID))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.type", is("internal_error")))
                .andExpect(jsonPath("$.error.message", is("An unexpected error occurred")))
                .andExpect(jsonPath("$.error.request_id", is(RID)))
                .andExpect(content().string(not(containsString("SECRET-SQL-leak"))));
    }

    @Test
    void responseStatus500_isGenericMessage_noLeak() throws Exception {
        // A 5xx ResponseStatusException is leak-hardened to the generic message (mirrors the other 500s).
        mockMvc.perform(get("/throw/response-status-500").header(CorrelationIdFilter.REQUEST_ID_HEADER, RID))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.type", is("internal_error")))
                .andExpect(jsonPath("$.error.message", is("An unexpected error occurred")))
                .andExpect(jsonPath("$.error.request_id", is(RID)))
                .andExpect(content().string(not(containsString("SECRET-SQL-leak"))));
    }

    /** A concrete NexusPayException subtype with no dedicated handler (exercises the fallback handler). */
    private static class GenericDomainException extends NexusPayException {
        GenericDomainException(String message) {
            super(message, "some_domain_code");
        }
    }

    /** @Valid body whose @NotBlank field drives MethodArgumentNotValidException. */
    record ValidatedBody(@NotBlank String name) {
    }

    @RestController
    static class ThrowingController {

        @PostMapping("/throw/validation")
        String validation(@Valid @RequestBody ValidatedBody body) {
            return "never";
        }

        @GetMapping("/throw/not-found")
        String notFound() {
            throw ResourceNotFoundException.of("Payment", "pay_x");
        }

        @GetMapping("/throw/authz")
        String authz() {
            throw AuthorizationException.forbidden("refund");
        }

        @GetMapping("/throw/access-denied")
        String accessDenied() {
            throw new AccessDeniedException("nope");
        }

        @GetMapping("/throw/payment-not-found")
        String paymentNotFound() {
            throw PaymentException.notFound("pay_x");
        }

        @GetMapping("/throw/payment-conflict")
        String paymentConflict() {
            throw new PaymentException("bad state", "invalid_state");
        }

        @GetMapping("/throw/payment-forbidden")
        String paymentForbidden() {
            throw new PaymentException("sanctioned", "cross_border_blocked");
        }

        @GetMapping("/throw/payment-unprocessable")
        String paymentUnprocessable() {
            throw new PaymentException("fraud", "fraud_blocked");
        }

        @GetMapping("/throw/ledger-not-found")
        String ledgerNotFound() {
            throw LedgerException.accountNotFound("acct_x");
        }

        @GetMapping("/throw/ledger-500")
        String ledger500() {
            throw new LedgerException(SENTINEL, "unbalanced_entry");
        }

        @GetMapping("/throw/nexuspay-generic")
        String nexuspayGeneric() {
            throw new GenericDomainException(SENTINEL);
        }

        @GetMapping("/throw/runtime")
        String runtime() {
            throw new RuntimeException(SENTINEL);
        }

        @GetMapping("/throw/response-status-400")
        String responseStatus400() {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "batch size must not exceed 100");
        }

        @GetMapping("/throw/response-status-500")
        String responseStatus500() {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, SENTINEL);
        }

        @GetMapping("/throw/conflict")
        String conflict() {
            // Mirrors ApiKeyService.rotateApiKey's typed state-conflict throw (already-rotated). The
            // curated, id-free message IS echoed onto the 409 body (safe code-with-text, like PaymentException).
            throw new ConflictException("API key has already been rotated", "key_not_rotatable");
        }

        @GetMapping("/throw/invalid-request")
        String invalidRequest() {
            // Mirrors ApiKeyService.createApiKey's typed past-expiry guard (defence-in-depth behind @Future).
            throw new InvalidRequestException("API key expiresAt must be in the future");
        }

        @GetMapping("/throw/illegal-state")
        String illegalState() {
            // A RAW IllegalStateException stands in for an INTERNAL invariant/corruption guard (e.g.
            // ApiKeyService.generateKey's prefix/is_live mismatch). It must remain a leak-hardened 500 — NOT
            // a 409 — so a server fault still trips 5xx alerts. The SENTINEL must not reach the wire.
            throw new IllegalStateException("internal invariant violated: " + SENTINEL);
        }
    }
}
