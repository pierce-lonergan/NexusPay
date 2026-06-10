package io.nexuspay.reconciliation.adapter.out.persistence;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * JPA entity for settlement_records table.
 *
 * @since 0.2.0 (Sprint 2.3)
 */
@Entity
@Table(name = "settlement_records",
        indexes = {
            @Index(name = "idx_settlement_records_run", columnList = "reconciliation_run_id"),
            @Index(name = "idx_settlement_records_match", columnList = "match_status")
        })
public class SettlementRecordEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "reconciliation_run_id", nullable = false, length = 64)
    private String reconciliationRunId;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(nullable = false, length = 64)
    private String provider;

    @Column(name = "external_id", nullable = false, length = 128)
    private String externalId;

    @Column(name = "payment_reference", length = 64)
    private String paymentReference;

    @Column(nullable = false)
    private long amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "fee_amount")
    private long feeAmount;

    @Column(name = "net_amount", nullable = false)
    private long netAmount;

    @Column(name = "settled_at", nullable = false)
    private Instant settledAt;

    @Column(name = "match_status", nullable = false, length = 16)
    private String matchStatus;

    @Column(name = "matched_payment_id", length = 64)
    private String matchedPaymentId;

    @Column(name = "matched_journal_entry_id", length = 64)
    private String matchedJournalEntryId;

    // raw_data is a Postgres jsonb column; this String holds an ALREADY-serialized
    // JSON document (both parsers emit valid JSON). Without @JdbcTypeCode(SqlTypes.JSON)
    // Hibernate binds the String as varchar and every INSERT aborts ("column is of
    // type jsonb but expression is of type character varying"). The codebase maps
    // structured JSON the same way (e.g. JournalEntryEntity.metadata, a Map); for a
    // pre-serialized String, Hibernate 6 writes the contents through as-is (no
    // re-encoding). NOTE: round-trip is unit-tested at the parser level only; a
    // Testcontainers jsonb round-trip is tracked as B-016 (needs Docker).
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_data", columnDefinition = "jsonb")
    private String rawData;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected SettlementRecordEntity() {}

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
