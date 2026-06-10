-- Default chart of accounts for USD
-- Additional currency accounts are created dynamically by the ledger service
INSERT INTO ledger_accounts (id, name, type, currency) VALUES
    ('la_merchant_recv_usd', 'Merchant Receivables (USD)', 'ASSET', 'USD'),
    ('la_customer_liab_usd', 'Customer Liability (USD)', 'LIABILITY', 'USD'),
    ('la_revenue_usd', 'Revenue (USD)', 'REVENUE', 'USD'),
    ('la_processing_fees_usd', 'Processing Fees (USD)', 'EXPENSE', 'USD'),
    ('la_refunds_usd', 'Refunds (USD)', 'EXPENSE', 'USD');
