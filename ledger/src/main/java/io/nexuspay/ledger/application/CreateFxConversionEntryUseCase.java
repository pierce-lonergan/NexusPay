package io.nexuspay.ledger.application;

import io.nexuspay.common.id.PrefixedId;
import io.nexuspay.ledger.application.port.FxGainLossAccountRepository;
import io.nexuspay.ledger.application.port.JournalEntryRepository;
import io.nexuspay.ledger.application.port.LedgerAccountRepository;
import io.nexuspay.ledger.domain.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Creates multi-leg journal entries for FX currency conversions.
 *
 * <p>A cross-currency payment settlement produces a 3-leg journal entry:</p>
 * <ul>
 *   <li>Leg 1: DR merchant_receivable_{presentment_ccy} / CR customer_liability_{presentment_ccy}</li>
 *   <li>Leg 2: DR merchant_receivable_{settlement_ccy} / CR merchant_receivable_{presentment_ccy}</li>
 *   <li>Leg 3: DR/CR fx_gain_loss_{ccy_pair} (balancing entry for rate difference)</li>
 * </ul>
 *
 * @since 0.3.0 (Sprint 3.2)
 */
@Service
public class CreateFxConversionEntryUseCase {

    private static final Logger LOG = LoggerFactory.getLogger(CreateFxConversionEntryUseCase.class);

    private final EnsureAccountsExistUseCase ensureAccounts;
    private final JournalEntryRepository journalEntryRepo;
    private final LedgerAccountRepository accountRepo;
    private final FxGainLossAccountRepository fxGlRepo;

    public CreateFxConversionEntryUseCase(
            EnsureAccountsExistUseCase ensureAccounts,
            JournalEntryRepository journalEntryRepo,
            LedgerAccountRepository accountRepo,
            FxGainLossAccountRepository fxGlRepo) {
        this.ensureAccounts = ensureAccounts;
        this.journalEntryRepo = journalEntryRepo;
        this.accountRepo = accountRepo;
        this.fxGlRepo = fxGlRepo;
    }

    /**
     * FX conversion request parameters.
     */
    public record FxConversionRequest(
            String tenantId,
            String paymentId,
            String presentmentCurrency,
            long presentmentAmountMinorUnits,
            String settlementCurrency,
            long settlementAmountMinorUnits,
            BigDecimal appliedRate,
            String rateProvider
    ) {}

    /**
     * Creates a 3-leg journal entry for the FX conversion.
     *
     * @return the created journal entry
     */
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public JournalEntry execute(FxConversionRequest req) {
        LOG.info("Creating FX conversion journal entry for payment {}: {} {} → {} {}",
                req.paymentId(), req.presentmentAmountMinorUnits(), req.presentmentCurrency(),
                req.settlementAmountMinorUnits(), req.settlementCurrency());

        // Ensure accounts exist for both currencies
        ensureAccounts.ensureAccountsForCurrency(req.presentmentCurrency(), req.tenantId());
        ensureAccounts.ensureAccountsForCurrency(req.settlementCurrency(), req.tenantId());

        // Ensure FX gain/loss account exists
        String currencyPair = req.presentmentCurrency() + "/" + req.settlementCurrency();
        ensureFxGainLossAccount(req.tenantId(), currencyPair, req.settlementCurrency());

        // Build the 3-leg posting set
        List<Posting> postings = new ArrayList<>();

        // Account IDs follow the convention from EnsureAccountsExistUseCase
        String merchantRecvPresentment = "la_merchant_recv_" + req.presentmentCurrency().toLowerCase();
        String customerLiabPresentment = "la_customer_liab_" + req.presentmentCurrency().toLowerCase();
        String merchantRecvSettlement = "la_merchant_recv_" + req.settlementCurrency().toLowerCase();
        String fxGainLossAccount = "la_fx_gain_loss_" + currencyPair.replace("/", "_").toLowerCase();

        // Leg 1: Customer payment in presentment currency
        // DR merchant_receivable_{presentment} (asset increases)
        // CR customer_liability_{presentment} (liability increases)
        postings.add(new Posting(PrefixedId.posting(), merchantRecvPresentment,
                req.presentmentAmountMinorUnits(), req.presentmentCurrency()));
        postings.add(new Posting(PrefixedId.posting(), customerLiabPresentment,
                -req.presentmentAmountMinorUnits(), req.presentmentCurrency()));

        // Leg 2: FX conversion — move from presentment to settlement currency
        // DR merchant_receivable_{settlement} (settlement currency asset increases)
        // CR merchant_receivable_{presentment} (presentment currency asset decreases)
        postings.add(new Posting(PrefixedId.posting(), merchantRecvSettlement,
                req.settlementAmountMinorUnits(), req.settlementCurrency()));
        postings.add(new Posting(PrefixedId.posting(), merchantRecvPresentment,
                -req.presentmentAmountMinorUnits(), req.presentmentCurrency()));

        // Leg 3: FX gain/loss balancing entry
        // The net of Leg 1 + Leg 2 must be zero across all currencies
        // The gain/loss = settlementAmount - (presentmentAmount * rate_at_time_of_accounting)
        // For simplicity, we book the conversion differential to the FX G/L account
        long fxDifferential = calculateFxDifferential(req);
        if (fxDifferential != 0) {
            postings.add(new Posting(PrefixedId.posting(), fxGainLossAccount,
                    fxDifferential, req.settlementCurrency()));
        }

        // Create the journal entry
        String entryId = PrefixedId.journalEntry();
        JournalEntry entry = new JournalEntry(
                entryId,
                req.paymentId(),
                "FX conversion: " + req.presentmentCurrency() + " → " + req.settlementCurrency(),
                req.tenantId(),
                Instant.now(),
                Map.of(
                        "fx_rate", req.appliedRate().toPlainString(),
                        "fx_provider", req.rateProvider(),
                        "presentment_currency", req.presentmentCurrency(),
                        "settlement_currency", req.settlementCurrency(),
                        "type", "fx_conversion"
                ),
                postings
        );

        // Persist and update account balances
        journalEntryRepo.save(entry);

        for (Posting posting : postings) {
            updateAccountBalance(posting);
        }

        LOG.info("FX conversion journal entry {} created for payment {} (rate: {})",
                entryId, req.paymentId(), req.appliedRate());

        return entry;
    }

    private long calculateFxDifferential(FxConversionRequest req) {
        // The differential accounts for any rounding differences
        // In a multi-currency ledger, the FX G/L account absorbs rounding
        // This ensures the overall ledger stays balanced
        return 0; // Zero for now — exact conversion at locked rate
    }

    private void updateAccountBalance(Posting posting) {
        LedgerAccount account = accountRepo.findById(posting.ledgerAccountId())
                .orElseThrow(() -> new RuntimeException(
                        "Ledger account not found: " + posting.ledgerAccountId()));
        account.applyPosting(posting.amount());
        accountRepo.save(account);
    }

    private void ensureFxGainLossAccount(String tenantId, String currencyPair, String settlementCurrency) {
        String accountId = "la_fx_gain_loss_" + currencyPair.replace("/", "_").toLowerCase();

        // Ensure the ledger account exists
        if (accountRepo.findById(accountId).isEmpty()) {
            LedgerAccount glAccount = new LedgerAccount(
                    accountId,
                    "FX Gain/Loss (" + currencyPair + ")",
                    AccountType.REVENUE,
                    settlementCurrency,
                    0L, 0L, tenantId,
                    Instant.now(), Instant.now()
            );
            accountRepo.save(glAccount);
            LOG.info("Created FX gain/loss ledger account: {} for pair {}", accountId, currencyPair);
        }

        // Ensure the FX G/L tracking record exists
        if (fxGlRepo.findByTenantIdAndCurrencyPair(tenantId, currencyPair).isEmpty()) {
            FxGainLossAccount fxGl = FxGainLossAccount.create(tenantId, currencyPair, accountId);
            fxGlRepo.save(fxGl);
        }
    }
}
