package io.nexuspay.common.exception;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Contract tests for the domain exception factories. The (errorCode, message) pairs are part
 * of the cross-module API error envelope and are matched on by other modules — a changed code
 * or message is a silent API contract break. These tests pin the wording, not just non-null.
 */
class DomainExceptionTest {

    // ---------- PaymentException ----------

    @Test
    void paymentNotFoundContract() {
        PaymentException ex = PaymentException.notFound("pi_123");
        assertEquals("payment_not_found", ex.getErrorCode());
        assertTrue(ex.getMessage().contains("pi_123"), ex.getMessage());
    }

    @Test
    void paymentProcessingFailedContract() {
        PaymentException ex = PaymentException.processingFailed("gateway timeout");
        assertEquals("processing_failed", ex.getErrorCode());
        assertTrue(ex.getMessage().contains("gateway timeout"), ex.getMessage());
    }

    @Test
    void paymentInvalidStateUsesStateMachineGuardWording() {
        PaymentException ex = PaymentException.invalidState("CAPTURED", "refund");
        assertEquals("invalid_state", ex.getErrorCode());
        // Exact guard wording: "Cannot <action> payment in state: <state>"
        assertEquals("Cannot refund payment in state: CAPTURED", ex.getMessage());
    }

    @Test
    void paymentGatewayErrorPreservesCause() {
        RuntimeException cause = new RuntimeException("socket reset");
        PaymentException ex = PaymentException.gatewayError("Upstream gateway failed", cause);
        assertEquals("gateway_error", ex.getErrorCode());
        assertEquals("Upstream gateway failed", ex.getMessage());
        assertSame(cause, ex.getCause());
    }

    // ---------- LedgerException ----------

    @Test
    void ledgerUnbalancedEntryEmbedsSum() {
        // Double-entry invariant: the offending non-zero sum must be in the message.
        LedgerException ex = LedgerException.unbalancedEntry(150L);
        assertEquals("unbalanced_entry", ex.getErrorCode());
        assertTrue(ex.getMessage().contains("150"), ex.getMessage());
    }

    @Test
    void ledgerUnbalancedEntryWithCurrencyEmbedsBoth() {
        LedgerException ex = LedgerException.unbalancedEntry("USD", -25L);
        assertEquals("unbalanced_entry", ex.getErrorCode());
        assertTrue(ex.getMessage().contains("USD"), ex.getMessage());
        assertTrue(ex.getMessage().contains("-25"), ex.getMessage());
    }

    @Test
    void ledgerAccountNotFoundContract() {
        LedgerException ex = LedgerException.accountNotFound("acct_77");
        assertEquals("account_not_found", ex.getErrorCode());
        assertTrue(ex.getMessage().contains("acct_77"), ex.getMessage());
    }

    @Test
    void ledgerConcurrencyConflictContract() {
        LedgerException ex = LedgerException.concurrencyConflict("acct_77");
        assertEquals("concurrency_conflict", ex.getErrorCode());
        assertTrue(ex.getMessage().contains("acct_77"), ex.getMessage());
    }

    // ---------- AuthorizationException ----------

    @Test
    void authForbiddenContract() {
        AuthorizationException ex = AuthorizationException.forbidden("capture_payment");
        assertEquals("forbidden", ex.getErrorCode());
        assertTrue(ex.getMessage().contains("capture_payment"), ex.getMessage());
    }

    @Test
    void authInvalidApiKeyContract() {
        AuthorizationException ex = AuthorizationException.invalidApiKey();
        assertEquals("invalid_api_key", ex.getErrorCode());
        assertNotNull(ex.getMessage());
    }

    @Test
    void authApprovalRequiredContract() {
        AuthorizationException ex = AuthorizationException.approvalRequired("refund_over_limit");
        assertEquals("approval_required", ex.getErrorCode());
        assertTrue(ex.getMessage().contains("refund_over_limit"), ex.getMessage());
    }

    // ---------- hierarchy: all extend NexusPayException / RuntimeException ----------

    @Test
    void allExtendNexusPayExceptionAndRuntimeException() {
        // Existing catch blocks and @ExceptionHandler advice match on these supertypes.
        assertInstanceOf(NexusPayException.class, PaymentException.notFound("x"));
        assertInstanceOf(NexusPayException.class, LedgerException.unbalancedEntry(1L));
        assertInstanceOf(NexusPayException.class, AuthorizationException.invalidApiKey());

        assertInstanceOf(RuntimeException.class, PaymentException.notFound("x"));
        assertInstanceOf(RuntimeException.class, LedgerException.unbalancedEntry(1L));
        assertInstanceOf(RuntimeException.class, AuthorizationException.invalidApiKey());
    }
}
