package com.fincore.ledger;

public enum LedgerTransactionType {
    /** Funds a new account: DEBIT system funding account, CREDIT the new account. */
    OPENING_BALANCE,
    /** A completed payment: DEBIT payer, CREDIT payee. */
    TRANSFER,
    /** Compensating transaction for a refund: DEBIT original payee, CREDIT original payer. */
    REFUND
}
