package io.nexuspay.gateway.adapter.in.rest;

import io.nexuspay.gateway.adapter.in.rest.dto.JournalEntryResponse;
import io.nexuspay.gateway.adapter.in.rest.dto.LedgerAccountResponse;
import io.nexuspay.iam.domain.NexusPayPrincipal;
import io.nexuspay.ledger.application.GetBalanceUseCase;
import io.nexuspay.ledger.application.GetJournalEntriesUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/v1/ledger")
@Tag(name = "Ledger", description = "Double-entry ledger queries")
public class LedgerController {

    private final GetBalanceUseCase getBalanceUseCase;
    private final GetJournalEntriesUseCase getJournalEntriesUseCase;

    public LedgerController(GetBalanceUseCase getBalanceUseCase,
                             GetJournalEntriesUseCase getJournalEntriesUseCase) {
        this.getBalanceUseCase = getBalanceUseCase;
        this.getJournalEntriesUseCase = getJournalEntriesUseCase;
    }

    @GetMapping("/accounts")
    @PreAuthorize("hasAnyRole('admin', 'operator', 'viewer')")
    @Operation(summary = "List all ledger accounts with balances")
    public ResponseEntity<List<LedgerAccountResponse>> listAccounts(
            @AuthenticationPrincipal NexusPayPrincipal principal) {
        var accounts = getBalanceUseCase.getAllBalances(principal.tenantId()).stream()
                .map(ResponseMapper::toLedgerAccountResponse)
                .toList();
        return ResponseEntity.ok(accounts);
    }

    @GetMapping("/journal-entries")
    @PreAuthorize("hasAnyRole('admin', 'operator', 'viewer')")
    @Operation(summary = "List journal entries, filterable by payment reference or date range")
    public ResponseEntity<List<JournalEntryResponse>> listJournalEntries(
            @RequestParam(required = false) String payment_reference,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset,
            @AuthenticationPrincipal NexusPayPrincipal principal) {

        // SEC-08 (B-008): scope BOTH query paths to the caller's tenant (mirror listAccounts above).
        // Without this any authenticated user could read every tenant's double-entry lines.
        String tenantId = principal.tenantId();
        List<JournalEntryResponse> entries;
        if (payment_reference != null && !payment_reference.isBlank()) {
            entries = getJournalEntriesUseCase.getByPaymentReference(payment_reference, tenantId).stream()
                    .map(ResponseMapper::toJournalEntryResponse)
                    .toList();
        } else {
            var fromTime = from != null ? from : Instant.EPOCH;
            var toTime = to != null ? to : Instant.now();
            entries = getJournalEntriesUseCase.getByDateRange(fromTime, toTime, limit, offset, tenantId).stream()
                    .map(ResponseMapper::toJournalEntryResponse)
                    .toList();
        }
        return ResponseEntity.ok(entries);
    }
}
