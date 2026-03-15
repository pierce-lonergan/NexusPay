# Double-Entry Ledger Flow Diagrams

## 1. Journal Entry Creation (SERIALIZABLE Transaction)

```
┌───────────────┐     ┌──────────────────────────────┐     ┌──────────────┐
│  Use Case:    │     │      PostgreSQL               │     │  Ledger      │
│  CreateJournal│     │  (SERIALIZABLE isolation)     │     │  Accounts    │
│  EntryUseCase │     │                                │     │  Table       │
└──────┬────────┘     └──────────────┬─────────────────┘     └──────┬───────┘
       │                             │                              │
       │ BEGIN SERIALIZABLE TX       │                              │
       ├────────────────────────────►│                              │
       │                             │                              │
       │  Validate zero-sum          │                              │
       │  (in JournalEntry           │                              │
       │   constructor)              │                              │
       │                             │                              │
       │  For each posting:          │                              │
       │  ┌─────────────────────┐    │                              │
       │  │ SELECT account      │    │                              │
       │  │ WHERE id = ?        ├───►│                              │
       │  │                     │    │                              │
       │  │ account + version   │    │                              │
       │  │◄────────────────────┤    │                              │
       │  │                     │    │                              │
       │  │ UPDATE accounts     │    │  UPDATE ledger_accounts      │
       │  │ SET posted_balance  │    │  SET posted_balance = ?,     │
       │  │   = old + amount    ├───►│      updated_at = NOW()      │
       │  │ WHERE id = ?        │    │  WHERE id = ?                │
       │  │ AND version = ?     │    │  AND version = ?             │
       │  │                     │    │                              │
       │  │ rows_updated > 0?   │    │                              │
       │  │ YES → continue      │    │                              │
       │  │ NO  → retry (max 3) │    │                              │
       │  └─────────────────────┘    │                              │
       │                             │                              │
       │  INSERT journal_entries     │                              │
       ├────────────────────────────►│                              │
       │                             │                              │
       │  INSERT postings (batch)    │                              │
       ├────────────────────────────►│                              │
       │                             │                              │
       │  COMMIT                     │                              │
       ├────────────────────────────►│                              │
       │                             │                              │
```

## 2. Payment Captured → Ledger Entry Flow

```
                                                    ┌──────────────────┐
  Kafka: nexuspay.payments                          │ PaymentEvent     │
  ─────────────────────────                         │ Consumer         │
  key: pi_abc123                                    │ (ledger module)  │
  type: PaymentCaptured                             └────────┬─────────┘
  payload:                                                   │
    amount: 10000                                            │
    currency: USD                                            │
    status: succeeded                                        │
                                                             │
                                                             ▼
                                              ┌──────────────────────────┐
                                              │ 1. Idempotency check:    │
                                              │    EXISTS journal entry  │
                                              │    for this payment +    │
                                              │    "Payment captured"?   │
                                              │                          │
                                              │    YES → skip (log info) │
                                              │    NO  → continue        │
                                              └────────────┬─────────────┘
                                                           │
                                                           ▼
                                              ┌──────────────────────────┐
                                              │ 2. Ensure currency       │
                                              │    accounts exist        │
                                              │    (idempotent create)   │
                                              └────────────┬─────────────┘
                                                           │
                                                           ▼
                                              ┌──────────────────────────┐
                                              │ 3. Create journal entry: │
                                              │                          │
                                              │ ┌──────────────────────┐ │
                                              │ │ DR la_merchant_recv  │ │
                                              │ │    _usd    +10000    │ │
                                              │ ├──────────────────────┤ │
                                              │ │ CR la_customer_liab  │ │
                                              │ │    _usd    -10000    │ │
                                              │ ├──────────────────────┤ │
                                              │ │ SUM =         0  ✓  │ │
                                              │ └──────────────────────┘ │
                                              └──────────────────────────┘
```

## 3. Refund Completed → Ledger Entry Flow

```
  Kafka: nexuspay.payments
  ─────────────────────────
  key: ref_xyz789
  type: RefundCompleted
  payload:
    payment_id: pi_abc123
    amount: 2500
    currency: USD

                    │
                    ▼
  ┌──────────────────────────────┐
  │ PaymentEventConsumer         │
  │                              │
  │ 1. Idempotency check         │
  │ 2. Ensure USD accounts exist │
  │ 3. Create journal entry:     │
  │                              │
  │ ┌──────────────────────────┐ │
  │ │ DR la_refunds_usd +2500 │ │
  │ ├──────────────────────────┤ │
  │ │ CR la_merchant_recv_usd  │ │
  │ │              -2500       │ │
  │ ├──────────────────────────┤ │
  │ │ SUM =         0  ✓      │ │
  │ └──────────────────────────┘ │
  └──────────────────────────────┘
```

## 4. Chart of Accounts (Default USD)

```
┌─────────────────────────────────────────────────────────────────┐
│                    CHART OF ACCOUNTS (USD)                        │
├──────────────────────┬──────────┬────────────────────────────────┤
│ Account ID           │ Type     │ Natural Balance                │
├──────────────────────┼──────────┼────────────────────────────────┤
│ la_merchant_recv_usd │ ASSET    │ Debit (+)  — money owed to us │
│ la_customer_liab_usd │ LIABILITY│ Credit (-) — money we owe     │
│ la_revenue_usd       │ REVENUE  │ Credit (-) — income earned    │
│ la_processing_fees   │ EXPENSE  │ Debit (+)  — costs incurred   │
│   _usd               │          │                                │
│ la_refunds_usd       │ EXPENSE  │ Debit (+)  — money returned   │
└──────────────────────┴──────────┴────────────────────────────────┘

Multi-currency: when a payment arrives in EUR, the system
auto-creates: la_merchant_recv_eur, la_customer_liab_eur, etc.
```

## 5. Balance Reconciliation Job

```
┌─────────────────────────────────────────────────────────────────┐
│               Balance Reconciliation (Hourly)                    │
│               @Scheduled(cron = "0 0 * * * *")                   │
│                                                                  │
│  For each ledger_account:                                        │
│                                                                  │
│    computed = SELECT SUM(p.amount)                                │
│               FROM postings p                                    │
│               WHERE p.ledger_account_id = account.id             │
│                                                                  │
│    posted = account.posted_balance                               │
│                                                                  │
│    ┌─────────────────────────────────────────┐                   │
│    │ computed == posted?                      │                   │
│    │                                          │                   │
│    │  YES → ✓ Account balanced                │                   │
│    │                                          │                   │
│    │  NO  → ⚠ BALANCE DRIFT DETECTED          │                   │
│    │        Log WARN with account ID,         │                   │
│    │        expected vs actual values          │                   │
│    │        (does NOT auto-correct)            │                   │
│    └─────────────────────────────────────────┘                   │
│                                                                  │
│  Result:                                                         │
│    0 drift  → INFO "all N accounts balanced"                     │
│    N drift  → ERROR "N account(s) with drift!"                   │
└─────────────────────────────────────────────────────────────────┘
```

## 6. Optimistic Concurrency on Balance Updates

```
Thread A (PaymentCaptured)           Thread B (RefundCompleted)
          │                                    │
          ▼                                    ▼
  SELECT account                       SELECT account
  WHERE id = la_merchant_recv_usd      WHERE id = la_merchant_recv_usd
  → balance=50000, version=5           → balance=50000, version=5
          │                                    │
          ▼                                    ▼
  UPDATE SET balance=60000             UPDATE SET balance=47500
  WHERE id=... AND version=5           WHERE id=... AND version=5
          │                                    │
          ▼                                    ▼
  rows_updated=1 ✓                     rows_updated=0 ✗ (version mismatch)
  (version now 6)                              │
                                               ▼
                                       RETRY (attempt 2):
                                       SELECT → balance=60000, version=6
                                       UPDATE SET balance=57500
                                       WHERE version=6
                                               │
                                               ▼
                                       rows_updated=1 ✓
                                       (version now 7)
```

## 7. Entity Relationship Diagram

```
┌─────────────────────────┐
│    ledger_accounts       │
├─────────────────────────┤         ┌─────────────────────────┐
│ PK  id VARCHAR(64)      │         │    journal_entries       │
│     name VARCHAR(128)   │         ├─────────────────────────┤
│     type VARCHAR(16)    │         │ PK  id VARCHAR(64)      │
│     currency VARCHAR(3) │         │     payment_reference   │
│     posted_balance      │◄──┐     │     VARCHAR(64)         │
│       BIGINT            │   │     │     description TEXT    │
│     version BIGINT      │   │     │     tenant_id           │
│     tenant_id           │   │     │     VARCHAR(64)         │
│       VARCHAR(64)       │   │     │     posted_at TIMESTAMP │
│     created_at          │   │     │     metadata JSONB      │
│     updated_at          │   │     └──────────┬──────────────┘
└─────────────────────────┘   │                │
                              │                │ 1:N
                              │                │
                              │     ┌──────────┴──────────────┐
                              │     │      postings            │
                              │     ├─────────────────────────┤
                              │     │ PK  id VARCHAR(64)      │
                              └─────│ FK  ledger_account_id   │
                                    │     VARCHAR(64)         │
                                    │ FK  journal_entry_id    │
                                    │     VARCHAR(64)         │
                                    │     amount BIGINT       │
                                    │     (+ debit, - credit) │
                                    │     currency VARCHAR(3) │
                                    └─────────────────────────┘

INVARIANT: For any journal_entry_id:
  SUM(postings.amount) = 0
```
