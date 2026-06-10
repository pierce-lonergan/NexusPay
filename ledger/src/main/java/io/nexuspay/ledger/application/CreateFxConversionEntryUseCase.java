package io.nexuspay.ledger.application;

import io.nexuspay.ledger.application.CreateJournalEntryUseCase.CreateJournalEntryCommand;
import io.nexuspay.ledger.application.CreateJournalEntryUseCase.CreateJournalEntryCommand.PostingLine;
import io.nexuspay.ledger.application.port.FxGainLossAccountRepository;
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
 * <p>A cross-currency payment settlement books legs that balance to zero
 * <em>per currency</em>, using per-currency FX clearing (position) accounts:</p>
 * <ul>
 *   <li>Presentment ccy: DR merchant_recv / CR customer_liab (capture), then
 *       DR fx_clearing / CR merchant_recv (sell presentment currency)</li>
 *   <li>Settlement ccy: DR merchant_recv / CR fx_clearing (buy settlement currency)</li>
 * </ul>
 *
 * <p>The fx_clearing accounts carry the open FX position; revaluation gains and
 * losses are tracked against the {@code la_fx_gain_loss_{pair}} account.</p>
 *
 * @since 0.3.0 (Sprint 3.2)
 */
@Service
public class CreateFxConversionEntryUseCase {

    private static final Logger LOG = LoggerFactory.getLogger(CreateFxConversionEntryUseCase.class);

    private final EnsureAccountsExistUseCase ensureAccounts;
    private final CreateJournalEntryUseCase createJournalEntry;
    private final LedgerAccountRepository accountRepo;
    private final FxGainLossAccountRepository fxGlRepo;

    public CreateFxConversionEntryUseCase(
            EnsureAccountsExistUseCase ensureAccounts,
            CreateJournalEntryUseCase createJournalEntry,
            LedgerAccountRepository accountRepo,
            FxGainLossAccountRepository fxGlRepo) {
        this.ensureAccounts = ensureAccounts;
        this.createJournalEntry = createJournalEntry;
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

        String presentmentCcy = req.presentmentCurrency().toUpperCase();
        String settlementCcy = req.settlementCurrency().toUpperCase();

        // Ensure accounts exist for both currencies
        ensureAccounts.ensureAccountsForCurrency(presentmentCcy, req.tenantId());
        ensureAccounts.ensureAccountsForCurrency(settlementCcy, req.tenantId());
        ensureFxClearingAccount(req.tenantId(), presentmentCcy);
        ensureFxClearingAccount(req.tenantId(), settlementCcy);

        // Ensure FX gain/loss tracking exists for the pair
        String currencyPair = presentmentCcy + "/" + settlementCcy;
        ensureFxGainLossAccount(req.tenantId(), currencyPair, settlementCcy);

        String merchantRecvPresentment = EnsureAccountsExistUseCase.merchantReceivablesId(presentmentCcy);
        String customerLiabPresentment = EnsureAccountsExistUseCase.customerLiabilityId(presentmentCcy);
        String merchantRecvSettlement = EnsureAccountsExistUseCase.merchantReceivablesId(settlementCcy);
        String fxClearingPresentment = EnsureAccountsExistUseCase.fxClearingId(presentmentCcy);
        String fxClearingSettlement = EnsureAccountsExistUseCase.fxClearingId(settlementCcy);

        List<PostingLine> postings = new ArrayList<>();

        // Leg 1: Customer payment in presentment currency
        // DR merchant_receivable_{presentment} / CR customer_liability_{presentment}
        postings.add(new PostingLine(merchantRecvPresentment,
                req.presentmentAmountMinorUnits(), presentmentCcy));
        postings.add(new PostingLine(customerLiabPresentment,
                -req.presentmentAmountMinorUnits(), presentmentCcy));

        // Leg 2: Sell the presentment currency into the FX position
        // DR fx_clearing_{presentment} / CR merchant_receivable_{presentment}
        postings.add(new PostingLine(fxClearingPresentment,
                req.presentmentAmountMinorUnits(), presentmentCcy));
        postings.add(new PostingLine(merchantRecvPresentment,
                -req.presentmentAmountMinorUnits(), presentmentCcy));

        // Leg 3: Buy the settlement currency out of the FX position
        // DR merchant_receivable_{settlement} / CR fx_clearing_{settlement}
        postings.add(new PostingLine(merchantRecvSettlement,
                req.settlementAmountMinorUnits(), settlementCcy));
        postings.add(new PostingLine(fxClearingSettlement,
                -req.settlementAmountMinorUnits(), settlementCcy));

        var command = new CreateJournalEntryCommand(
                req.paymentId(),
                "FX conversion: " + presentmentCcy + " → " + settlementCcy,
                req.tenantId(),
                Map.of(
                        "fx_rate", req.appliedRate().toPlainString(),
                        "fx_provider", req.rateProvider(),
                        "presentment_currency", presentmentCcy,
                        "settlement_currency", settlementCcy,
                        "type", "fx_conversion"
                ),
                postings
        );

        JournalEntry entry = createJournalEntry.execute(command);

        LOG.info("FX conversion journal entry {} created for payment {} (rate: {})",
                entry.getId(), req.paymentId(), req.appliedRate());

        return entry;
    }

    private void ensureFxClearingAccount(String tenantId, String currency) {
        String accountId = EnsureAccountsExistUseCase.fxClearingId(currency);
        if (accountRepo.findById(accountId).isEmpty()) {
            LedgerAccount clearing = new LedgerAccount(
                    accountId,
                    "FX Clearing (" + currency + ")",
                    AccountType.ASSET,
                    currency,
                    0L, 0L, tenantId,
                    Instant.now(), Instant.now()
            );
            accountRepo.save(clearing);
            LOG.info("Created FX clearing ledger account: {}", accountId);
        }
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
