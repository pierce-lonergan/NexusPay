package io.nexuspay.ledger.domain;

/**
 * Standard accounting account types.
 * ASSET and EXPENSE have natural debit balances (positive).
 * LIABILITY and REVENUE have natural credit balances (negative).
 */
public enum AccountType {
    ASSET,
    LIABILITY,
    REVENUE,
    EXPENSE
}
