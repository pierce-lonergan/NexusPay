# ADR-005: Double-Entry Ledger Design

**Status**: Accepted
**Date**: 2026-03-01
**Deciders**: Architecture team

## Context

NexusPay must track all financial movements (payments, refunds, fees) with accounting-grade accuracy. Enterprise customers require reconcilable books and audit trails. We need to decide between:

1. **Simple balance tracking** — increment/decrement a single balance field
2. **Double-entry bookkeeping** — every movement has equal debits and credits

## Decision

Implement a **double-entry ledger** with immutable journal entries, signed postings, and SERIALIZABLE transaction isolation.

## Rationale

**Why double-entry over simple balance:**
- Self-auditing: `SUM(amount) = 0` per journal entry is a built-in integrity check
- Full audit trail: every movement is traceable to a journal entry
- Supports complex flows: fees, splits, multi-party settlements
- Industry standard for financial systems — enterprise customers expect it
- Reconciliation is built into the model (balance = `SUM(postings)`)

**Design decisions:**

1. **Signed BIGINT for amounts** (positive = debit, negative = credit)
   - Simpler than separate debit/credit columns
   - Zero-sum invariant: `SUM(postings.amount) WHERE journal_entry_id = X` must equal 0
   - Minor units (cents) to avoid floating-point errors

2. **Optimistic concurrency on posted_balance** (version column)
   - `posted_balance` on `ledger_accounts` is a denormalized running total
   - Updated atomically with version check on each posting
   - Avoids hot-path SELECT FOR UPDATE locks

3. **SERIALIZABLE isolation** for journal entry creation
   - Prevents phantom reads during balance updates
   - Performance acceptable for Phase 1 volumes

4. **No Valkey cache** on balances in Phase 1
   - Premature optimization — direct DB read is fast enough
   - Cache invalidation on concurrent writes is complex
   - Phase 2 can add caching if profiling shows need

## Consequences

**Positive:**
- Accounting-grade integrity with mathematical guarantees
- Built-in reconciliation (balance drift is detectable)
- Immutable audit trail (postings are append-only)
- Supports multi-currency (account per currency)

**Negative:**
- More complex than simple balance tracking
- SERIALIZABLE isolation limits write throughput (acceptable for Phase 1)
- Optimistic concurrency retries under contention

## Schema Summary

```
ledger_accounts
├── id (la_xxx)
├── type (ASSET | LIABILITY | REVENUE | EXPENSE)
├── currency (ISO 4217)
├── posted_balance (BIGINT, minor units)
└── version (optimistic lock)

journal_entries
├── id (je_xxx)
├── payment_reference (links to payment_id)
└── posted_at

postings
├── id (post_xxx)
├── journal_entry_id → journal_entries.id
├── ledger_account_id → ledger_accounts.id
├── amount (BIGINT, signed: + debit, - credit)
└── currency

INVARIANT: SUM(postings.amount) WHERE journal_entry_id = X == 0
```

## Accounting Entries

**Payment captured ($100 USD):**
```
DR la_merchant_recv_usd   +10000  (Asset increases)
CR la_customer_liab_usd   -10000  (Liability increases)
SUM = 0 ✓
```

**Refund ($25 USD):**
```
DR la_refunds_usd         +2500   (Expense increases)
CR la_merchant_recv_usd   -2500   (Asset decreases)
SUM = 0 ✓
```
