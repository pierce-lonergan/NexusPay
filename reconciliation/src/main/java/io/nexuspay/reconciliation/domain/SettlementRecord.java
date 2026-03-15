package io.nexuspay.reconciliation.domain;

import java.time.Instant;

/**
 * A parsed line item from a PSP settlement file.
 *
 * <p>Settlement records represent what a payment processor reports as settled.
 * During reconciliation, these are matched against NexusPay's own payment
 * records and ledger entries to detect discrepancies.</p>
 *
 * <p>All monetary amounts are in minor currency units (cents for USD, yen for JPY).</p>
 *
 * @since 0.2.0 (Sprint 2.3)
 */
public class SettlementRecord {

    private String id;
    private String reconciliationRunId;
    private String tenantId;
    private String provider;
    private String externalId;
    private String paymentReference;
    private long amount;
    private String currency;
    private long feeAmount;
    private long netAmount;
    private Instant settledAt;
    private String matchStatus;
    private String matchedPaymentId;
    private String matchedJournalEntryId;
    private String rawData;
    private Instant createdAt;

    public SettlementRecord() {
    }

    public SettlementRecord(String id, String reconciliationRunId, String tenantId,
                            String provider, String externalId, String paymentReference,
                            long amount, String currency, long feeAmount, long netAmount,
                            Instant settledAt) {
        this.id = id;
        this.reconciliationRunId = reconciliationRunId;
        this.tenantId = tenantId;
        this.provider = provider;
        this.externalId = externalId;
        this.paymentReference = paymentReference;
        this.amount = amount;
        this.currency = currency;
        this.feeAmount = feeAmount;
        this.netAmount = netAmount;
        this.settledAt = settledAt;
        this.matchStatus = "PENDING";
        this.createdAt = Instant.now();
    }

    public void markMatched(String paymentId, String journalEntryId) {
        this.matchStatus = "MATCHED";
        this.matchedPaymentId = paymentId;
        this.matchedJournalEntryId = journalEntryId;
    }

    public void markUnmatched() {
        this.matchStatus = "UNMATCHED";
    }

    public void markException() {
        this.matchStatus = "EXCEPTION";
    }

    // Getters and setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getReconciliationRunId() { return reconciliationRunId; }
    public void setReconciliationRunId(String reconciliationRunId) { this.reconciliationRunId = reconciliationRunId; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getExternalId() { return externalId; }
    public void setExternalId(String externalId) { this.externalId = externalId; }
    public String getPaymentReference() { return paymentReference; }
    public void setPaymentReference(String paymentReference) { this.paymentReference = paymentReference; }
    public long getAmount() { return amount; }
    public void setAmount(long amount) { this.amount = amount; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public long getFeeAmount() { return feeAmount; }
    public void setFeeAmount(long feeAmount) { this.feeAmount = feeAmount; }
    public long getNetAmount() { return netAmount; }
    public void setNetAmount(long netAmount) { this.netAmount = netAmount; }
    public Instant getSettledAt() { return settledAt; }
    public void setSettledAt(Instant settledAt) { this.settledAt = settledAt; }
    public String getMatchStatus() { return matchStatus; }
    public void setMatchStatus(String matchStatus) { this.matchStatus = matchStatus; }
    public String getMatchedPaymentId() { return matchedPaymentId; }
    public void setMatchedPaymentId(String matchedPaymentId) { this.matchedPaymentId = matchedPaymentId; }
    public String getMatchedJournalEntryId() { return matchedJournalEntryId; }
    public void setMatchedJournalEntryId(String matchedJournalEntryId) { this.matchedJournalEntryId = matchedJournalEntryId; }
    public String getRawData() { return rawData; }
    public void setRawData(String rawData) { this.rawData = rawData; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
