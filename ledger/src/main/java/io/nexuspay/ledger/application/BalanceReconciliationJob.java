package io.nexuspay.ledger.application;

import io.nexuspay.ledger.adapter.out.persistence.JpaLedgerAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Hourly reconciliation job that verifies posted_balance on each account
 * matches the computed SUM(postings.amount) for that account.
 * Logs warnings on drift — does not auto-correct.
 */
@Component
public class BalanceReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(BalanceReconciliationJob.class);

    private final JpaLedgerAccountRepository accountRepository;

    public BalanceReconciliationJob(JpaLedgerAccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    @Scheduled(cron = "${nexuspay.ledger.reconciliation-cron:0 0 * * * *}")
    public void reconcileBalances() {
        log.info("Starting balance reconciliation");
        int driftCount = 0;

        List<Object[]> computedBalances = accountRepository.computeBalancesFromPostings();
        var accounts = accountRepository.findAll();

        var computedMap = new java.util.HashMap<String, Long>();
        for (Object[] row : computedBalances) {
            computedMap.put((String) row[0], ((Number) row[1]).longValue());
        }

        for (var account : accounts) {
            long computed = computedMap.getOrDefault(account.getId(), 0L);
            if (computed != account.getPostedBalance()) {
                log.warn("BALANCE DRIFT detected on account {}: posted_balance={}, computed={}",
                        account.getId(), account.getPostedBalance(), computed);
                driftCount++;
            }
        }

        if (driftCount == 0) {
            log.info("Balance reconciliation complete: all {} accounts balanced", accounts.size());
        } else {
            log.error("Balance reconciliation found {} account(s) with drift!", driftCount);
        }
    }
}
