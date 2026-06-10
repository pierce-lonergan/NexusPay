-- Ledger accounts: chart of accounts with running balances
CREATE TABLE ledger_accounts (
    id              VARCHAR(64) PRIMARY KEY,
    name            VARCHAR(128) NOT NULL,
    type            VARCHAR(16) NOT NULL,
    currency        VARCHAR(3) NOT NULL,
    posted_balance  BIGINT NOT NULL DEFAULT 0,
    version         BIGINT NOT NULL DEFAULT 0,
    tenant_id       VARCHAR(64) NOT NULL DEFAULT 'default',
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_account_type CHECK (type IN ('ASSET', 'LIABILITY', 'REVENUE', 'EXPENSE'))
);

CREATE INDEX idx_ledger_accounts_type ON ledger_accounts(type);
CREATE INDEX idx_ledger_accounts_currency ON ledger_accounts(currency);
CREATE INDEX idx_ledger_accounts_tenant ON ledger_accounts(tenant_id);

-- Journal entries: immutable record grouping balanced postings
CREATE TABLE journal_entries (
    id                 VARCHAR(64) PRIMARY KEY,
    payment_reference  VARCHAR(64),
    description        TEXT,
    tenant_id          VARCHAR(64) NOT NULL DEFAULT 'default',
    posted_at          TIMESTAMP NOT NULL DEFAULT NOW(),
    metadata           JSONB
);

CREATE INDEX idx_journal_entries_payment_ref ON journal_entries(payment_reference);
CREATE INDEX idx_journal_entries_posted_at ON journal_entries(posted_at);
CREATE INDEX idx_journal_entries_tenant ON journal_entries(tenant_id);

-- Postings: individual debit/credit lines within a journal entry
-- INVARIANT: SUM(amount) per journal_entry_id = 0
-- Convention: positive = debit, negative = credit
CREATE TABLE postings (
    id                 VARCHAR(64) PRIMARY KEY,
    journal_entry_id   VARCHAR(64) NOT NULL REFERENCES journal_entries(id),
    ledger_account_id  VARCHAR(64) NOT NULL REFERENCES ledger_accounts(id),
    amount             BIGINT NOT NULL,
    currency           VARCHAR(3) NOT NULL,

    CONSTRAINT fk_posting_journal FOREIGN KEY (journal_entry_id) REFERENCES journal_entries(id),
    CONSTRAINT fk_posting_account FOREIGN KEY (ledger_account_id) REFERENCES ledger_accounts(id)
);

CREATE INDEX idx_postings_journal_entry ON postings(journal_entry_id);
CREATE INDEX idx_postings_ledger_account ON postings(ledger_account_id);
